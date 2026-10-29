package com.sameerasw.overcast.weather.allergen

import android.content.Context
import com.sameerasw.overcast.data.repository.SettingsRepository
import com.sameerasw.overcast.weather.location.DeviceLocationSource
import com.sameerasw.overcast.weather.model.WeatherLocation
import com.sameerasw.overcast.weather.provider.ProviderHttp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject

enum class AllergenType(val field: String) {
    ALDER("alder_pollen"),
    BIRCH("birch_pollen"),
    GRASS("grass_pollen"),
    MUGWORT("mugwort_pollen"),
    RAGWEED("ragweed_pollen"),
}

data class AllergenPoint(val timeMillis: Long, val value: Double)

data class AllergenSeries(val type: AllergenType, val points: List<AllergenPoint>) {
    fun valueAt(timeMillis: Long): Double? =
        points.minByOrNull { kotlin.math.abs(it.timeMillis - timeMillis) }?.value

    fun peak(fromMillis: Long): AllergenPoint? = points.filter { it.timeMillis >= fromMillis }.maxByOrNull { it.value }
}

// Generic concentration bands in grains/m³, used for the level label only; not medical advice.
enum class AllergenLevel {
    NONE,
    LOW,
    MODERATE,
    HIGH,
    VERY_HIGH,
    ;

    companion object {
        fun from(value: Double?): AllergenLevel = when {
            value == null -> NONE
            value <= 0.0 -> NONE
            value < 10 -> LOW
            value < 50 -> MODERATE
            value < 100 -> HIGH
            else -> VERY_HIGH
        }
    }
}

data class AllergenData(val series: List<AllergenSeries>, val updatedAt: Long) {
    fun of(type: AllergenType): AllergenSeries? = series.firstOrNull { it.type == type }
}

class AllergenException(message: String?) : Exception(message)

object AllergenRepository {
    suspend fun load(context: Context): AllergenData {
        val loc = resolveLocation(context) ?: throw AllergenException("No location for allergens")
        val lat = "%.2f".format(Locale.US, loc.latitude)
        val lon = "%.2f".format(Locale.US, loc.longitude)
        val fields = AllergenType.entries.joinToString(",") { it.field }
        val url = "https://air-quality-api.open-meteo.com/v1/air-quality" +
            "?latitude=$lat&longitude=$lon&hourly=$fields&current=$fields" +
            "&timezone=auto&forecast_days=3"
        val raw = try {
            ProviderHttp.get(url)
        } catch (e: Exception) {
            throw AllergenException(e.message ?: "Allergen request failed")
        }
        val root = JSONObject(raw)
        val hourly = root.optJSONObject("hourly") ?: throw AllergenException("Bad allergen response")
        val offsetSeconds = root.optLong("utc_offset_seconds", 0L)
        val times = hourly.optJSONArray("time") ?: throw AllergenException("Bad allergen response")
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val epochs = (0 until times.length()).map { i ->
            format.parse(times.getString(i))?.time?.minus(offsetSeconds * 1000L) ?: 0L
        }
        val series = AllergenType.entries.map { type ->
            val values = hourly.optJSONArray(type.field)
            val points = ArrayList<AllergenPoint>(epochs.size)
            for (i in epochs.indices) {
                val v = values?.optDouble(i, Double.NaN) ?: Double.NaN
                if (epochs[i] != 0L && !v.isNaN()) points.add(AllergenPoint(epochs[i], v))
            }
            AllergenSeries(type, points)
        }
        return AllergenData(series, System.currentTimeMillis())
    }

    private suspend fun resolveLocation(context: Context): WeatherLocation? {
        val app = context.applicationContext
        val settings = SettingsRepository(app)
        if (settings.getWeatherLocationMode() == "manual") {
            settings.getWeatherManualLocation()?.let { return WeatherLocation(it.first, it.second, it.third) }
        }
        DeviceLocationSource.current(app)?.let { return it }
        return null
    }
}
