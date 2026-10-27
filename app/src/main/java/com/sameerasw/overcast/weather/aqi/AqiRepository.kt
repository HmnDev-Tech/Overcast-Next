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
) {
    val category: AqiCategory get() = AqiCategory.from(aqi)
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
    suspend fun current(context: Context): AqiData {
        val loc = WeatherRepository.lastKnownLocation(context) ?: throw AqiException("No location for AQI")
        val lat = "%.2f".format(Locale.US, loc.latitude)
        val lon = "%.2f".format(Locale.US, loc.longitude)
        val url = "https://air-quality-api.open-meteo.com/v1/air-quality" +
            "?latitude=$lat&longitude=$lon&current=us_aqi,pm2_5,pm10,ozone,nitrogen_dioxide&timezone=auto"
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
        return AqiData(
            aqi = current.optInt("us_aqi", -1).takeIf { it >= 0 },
            pm25 = current.optDouble("pm2_5").takeIf { !it.isNaN() },
            pm10 = current.optDouble("pm10").takeIf { !it.isNaN() },
            ozone = current.optDouble("ozone").takeIf { !it.isNaN() },
            no2 = current.optDouble("nitrogen_dioxide").takeIf { !it.isNaN() },
        )
    }
}

class AqiException(message: String?) : Exception(message)
