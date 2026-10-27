package com.sameerasw.overcast.weather.extra

import android.content.Context
import com.sameerasw.overcast.weather.WeatherRepository
import com.sameerasw.overcast.weather.model.WeatherLocation
import com.sameerasw.overcast.weather.provider.ProviderHttp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt
import org.json.JSONObject

data class MarineData(
    val waveHeightM: Double?,
    val waveDirectionDeg: Double?,
    val wavePeriodS: Double?,
    val swellHeightM: Double?,
    val seaTemperatureC: Double?,
)

data class RiverData(val todayM3s: Double, val peakM3s: Double, val peakDayMillis: Long)

enum class ForecastConfidence { HIGH, MEDIUM, LOW }

data class InsightData(val normalHighC: Double?, val confidence: ForecastConfidence?)

object ExtraRepository {
    private const val CACHE_MS = 60 * 60_000L
    // Below this the "river" is usually a ditch in the model grid, so the card would be noise.
    private const val MIN_RIVER_M3S = 10.0
    private const val NORMAL_YEARS = 10
    private const val NORMAL_WINDOW_DAYS = 7

    private class Entry<T>(val key: String, val at: Long, val value: T)

    @Volatile private var marineCache: Entry<MarineData?>? = null
    @Volatile private var riverCache: Entry<RiverData?>? = null
    @Volatile private var confidenceCache: Entry<ForecastConfidence?>? = null
    @Volatile private var normalCache: Entry<Double?>? = null

    private fun key(loc: WeatherLocation) = "%.2f,%.2f".format(Locale.US, loc.latitude, loc.longitude)

    private inline fun <T> cached(entry: Entry<T>?, key: String, maxAge: Long): Entry<T>? =
        entry?.takeIf { it.key == key && System.currentTimeMillis() - it.at < maxAge }

    suspend fun marine(context: Context): MarineData? {
        val loc = WeatherRepository.lastKnownLocation(context) ?: return null
        val key = key(loc)
        cached(marineCache, key, CACHE_MS)?.let { return it.value }
        val current = JSONObject(
            ProviderHttp.get(
                "https://marine-api.open-meteo.com/v1/marine?latitude=${loc.latitude}&longitude=${loc.longitude}" +
                    "&current=wave_height,wave_direction,wave_period,swell_wave_height,sea_surface_temperature",
            ),
        ).optJSONObject("current")
        fun number(name: String) = current?.optDouble(name)?.takeIf { !it.isNaN() }
        val data = MarineData(number("wave_height"), number("wave_direction"), number("wave_period"), number("swell_wave_height"), number("sea_surface_temperature"))
            .takeIf { it.waveHeightM != null || it.seaTemperatureC != null }
        marineCache = Entry(key, System.currentTimeMillis(), data)
        return data
    }

    suspend fun river(context: Context): RiverData? {
        val loc = WeatherRepository.lastKnownLocation(context) ?: return null
        val key = key(loc)
        cached(riverCache, key, CACHE_MS * 6)?.let { return it.value }
        val daily = JSONObject(
            ProviderHttp.get(
                "https://flood-api.open-meteo.com/v1/flood?latitude=${loc.latitude}&longitude=${loc.longitude}" +
                    "&daily=river_discharge&forecast_days=7&timeformat=unixtime",
            ),
        ).optJSONObject("daily")
        val times = daily?.optJSONArray("time")
        val values = daily?.optJSONArray("river_discharge")
        val data = if (times == null || values == null || values.length() == 0 || values.isNull(0)) {
            null
        } else {
            val points = (0 until values.length()).filter { !values.isNull(it) }.map { times.getLong(it) * 1000L to values.getDouble(it) }
            val peak = points.maxBy { it.second }
            RiverData(points.first().second, peak.second, peak.first).takeIf { peak.second >= MIN_RIVER_M3S }
        }
        riverCache = Entry(key, System.currentTimeMillis(), data)
        return data
    }

    suspend fun insights(context: Context): InsightData? {
        val loc = WeatherRepository.lastKnownLocation(context) ?: return null
        val normal = runCatching { normalHigh(loc) }.getOrNull()
        val confidence = runCatching { confidence(loc) }.getOrNull()
        return InsightData(normal, confidence).takeIf { normal != null || confidence != null }
    }

    // Mean daily high for this time of year over the last decade of ERA5 reanalysis.
    private suspend fun normalHigh(loc: WeatherLocation): Double? {
        val today = Calendar.getInstance()
        val key = key(loc) + ":" + today.get(Calendar.DAY_OF_YEAR)
        cached(normalCache, key, CACHE_MS * 24)?.let { return it.value }
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val end = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -NORMAL_WINDOW_DAYS - 7) }
        val start = (today.clone() as Calendar).apply { add(Calendar.YEAR, -NORMAL_YEARS) }
        val daily = JSONObject(
            ProviderHttp.get(
                "https://archive-api.open-meteo.com/v1/archive?latitude=${loc.latitude}&longitude=${loc.longitude}" +
                    "&start_date=${format.format(start.time)}&end_date=${format.format(end.time)}&daily=temperature_2m_max&timezone=auto",
            ),
        ).optJSONObject("daily")
        val dates = daily?.optJSONArray("time")
        val highs = daily?.optJSONArray("temperature_2m_max")
        val targetDay = today.get(Calendar.DAY_OF_YEAR)
        val matches = mutableListOf<Double>()
        if (dates != null && highs != null) {
            val cal = Calendar.getInstance()
            for (i in 0 until dates.length()) {
                if (highs.isNull(i)) continue
                cal.time = format.parse(dates.getString(i)) ?: continue
                val diff = abs(cal.get(Calendar.DAY_OF_YEAR) - targetDay).let { minOf(it, 365 - it) }
                if (diff <= NORMAL_WINDOW_DAYS) matches += highs.getDouble(i)
            }
        }
        val normal = matches.takeIf { it.size >= NORMAL_YEARS }?.average()
        normalCache = Entry(key, System.currentTimeMillis(), normal)
        return normal
    }

    // Spread of tomorrow's high across the ICON ensemble members: tight agreement means a dependable forecast.
    private suspend fun confidence(loc: WeatherLocation): ForecastConfidence? {
        val key = key(loc)
        cached(confidenceCache, key, CACHE_MS * 3)?.let { return it.value }
        val daily = JSONObject(
            ProviderHttp.get(
                "https://ensemble-api.open-meteo.com/v1/ensemble?latitude=${loc.latitude}&longitude=${loc.longitude}" +
                    "&models=icon_seamless&daily=temperature_2m_max&forecast_days=3",
            ),
        ).optJSONObject("daily")
        val members = daily?.keys()?.asSequence()
            ?.filter { it.startsWith("temperature_2m_max") }
            ?.mapNotNull { daily.optJSONArray(it)?.takeIf { arr -> arr.length() > 1 && !arr.isNull(1) }?.getDouble(1) }
            ?.toList()
            .orEmpty()
        val result = if (members.size < 5) {
            null
        } else {
            val mean = members.average()
            val spread = sqrt(members.sumOf { (it - mean) * (it - mean) } / members.size)
            when {
                spread < 1.5 -> ForecastConfidence.HIGH
                spread < 3.0 -> ForecastConfidence.MEDIUM
                else -> ForecastConfidence.LOW
            }
        }
        confidenceCache = Entry(key, System.currentTimeMillis(), result)
        return result
    }
}
