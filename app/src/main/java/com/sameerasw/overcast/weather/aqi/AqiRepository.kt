package com.sameerasw.overcast.weather.aqi

import android.content.Context
import com.sameerasw.overcast.weather.WeatherRepository
import com.sameerasw.overcast.weather.provider.ProviderHttp
import java.util.Locale
import org.json.JSONException
import org.json.JSONObject

data class AqiData(
    val aqi: Int?,
    val pm25: Double?,
    val pm10: Double?,
    val ozone: Double?,
    val no2: Double?,
    val pollen: Map<PollenType, Double> = emptyMap(),
) {
    val category: AqiCategory get() = AqiCategory.from(aqi)
}

enum class PollenType(val field: String) {
    ALDER("alder_pollen"),
    BIRCH("birch_pollen"),
    GRASS("grass_pollen"),
    MUGWORT("mugwort_pollen"),
    OLIVE("olive_pollen"),
    RAGWEED("ragweed_pollen"),
}

// Grains per m³; shared thresholds across species keep the scale simple.
enum class PollenLevel {
    NONE,
    LOW,
    MODERATE,
    HIGH,
    VERY_HIGH,
    ;

    companion object {
        fun from(grains: Double): PollenLevel = when {
            grains < 1 -> NONE
            grains < 10 -> LOW
            grains < 50 -> MODERATE
            grains < 200 -> HIGH
            else -> VERY_HIGH
        }
    }
}

enum class AqiCategory {
    GOOD,
    MODERATE,
    SENSITIVE,
    UNHEALTHY,
    VERY_UNHEALTHY,
    HAZARDOUS,
    UNKNOWN,
    ;

    companion object {
        fun from(aqi: Int?): AqiCategory = when {
            aqi == null -> UNKNOWN
            aqi <= 50 -> GOOD
            aqi <= 100 -> MODERATE
            aqi <= 150 -> SENSITIVE
            aqi <= 200 -> UNHEALTHY
            aqi <= 300 -> VERY_UNHEALTHY
            else -> HAZARDOUS
        }
    }
}

object AqiProviders {
    const val OFF_ID = "off"
    const val OPEN_METEO_ID = "openmeteo"

    val all: List<Pair<String, String?>> = listOf(OFF_ID to null, OPEN_METEO_ID to "Open-Meteo")

    fun resolve(id: String?): String = all.firstOrNull { it.first == id }?.first ?: OPEN_METEO_ID
}

object AqiRepository {
    private const val MAX_AGE_MS = 30 * 60_000L

    private class Cached(val key: String, val at: Long, val data: AqiData)

    @Volatile private var last: Cached? = null

    fun cached(): AqiData? = last?.data

    suspend fun current(context: Context, force: Boolean = false): AqiData {
        val loc = WeatherRepository.lastKnownLocation(context) ?: throw AqiException("No location for AQI")
        val lat = "%.2f".format(Locale.US, loc.latitude)
        val lon = "%.2f".format(Locale.US, loc.longitude)
        val key = "$lat,$lon"
        last?.takeIf { !force && it.key == key && System.currentTimeMillis() - it.at < MAX_AGE_MS }?.let { return it.data }
        val url = "https://air-quality-api.open-meteo.com/v1/air-quality" +
            "?latitude=$lat&longitude=$lon&current=us_aqi,pm2_5,pm10,ozone,nitrogen_dioxide," +
            PollenType.entries.joinToString(",") { it.field } + "&timezone=auto"
        val raw = try {
            ProviderHttp.get(url)
        } catch (e: Exception) {
            throw AqiException(e.message)
        }
        val current = try {
            JSONObject(raw).optJSONObject("current")
        } catch (e: JSONException) {
            null
        } ?: throw AqiException("Bad AQI response")
        val data = AqiData(
            aqi = current.optInt("us_aqi", -1).takeIf { it >= 0 },
            pm25 = current.optDouble("pm2_5").takeIf { !it.isNaN() },
            pm10 = current.optDouble("pm10").takeIf { !it.isNaN() },
            ozone = current.optDouble("ozone").takeIf { !it.isNaN() },
            no2 = current.optDouble("nitrogen_dioxide").takeIf { !it.isNaN() },
            pollen = PollenType.entries.mapNotNull { type ->
                current.optDouble(type.field).takeIf { !it.isNaN() }?.let { type to it }
            }.toMap(),
        )
        last = Cached(key, System.currentTimeMillis(), data)
        return data
    }
}

class AqiException(message: String?) : Exception(message)
