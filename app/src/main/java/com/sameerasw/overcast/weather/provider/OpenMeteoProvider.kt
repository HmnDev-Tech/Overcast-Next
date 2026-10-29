package com.sameerasw.overcast.weather.provider

import com.sameerasw.overcast.OvercastApp
import com.sameerasw.overcast.data.repository.SettingsRepository
import com.sameerasw.overcast.weather.model.CityResult
import com.sameerasw.overcast.weather.model.DailyForecast
import com.sameerasw.overcast.weather.model.HourlyForecast
import com.sameerasw.overcast.weather.model.MinutelyPrecipitation
import com.sameerasw.overcast.weather.model.WeatherExtras
import com.sameerasw.overcast.weather.model.WeatherCondition
import com.sameerasw.overcast.weather.model.WeatherLocation
import com.sameerasw.overcast.weather.model.WeatherSnapshot
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

object OpenMeteoModels {
    class Model(val id: String?, val label: String)

    val all = listOf(
        Model(null, "Auto (best match)"),
        Model("ecmwf_ifs025", "ECMWF IFS"),
        Model("gfs_seamless", "NOAA GFS / HRRR"),
        Model("icon_seamless", "DWD ICON"),
        Model("meteofrance_seamless", "Météo-France"),
        Model("ukmo_seamless", "UK Met Office"),
        Model("gem_seamless", "Environment Canada GEM"),
        Model("jma_seamless", "JMA"),
    )

    fun label(id: String?): String = all.firstOrNull { it.id == id }?.label ?: all.first().label

    fun selected(): String? =
        SettingsRepository(OvercastApp.context).getWeatherOpenMeteoModel()?.takeIf { id -> all.any { it.id == id } }
}

class OpenMeteoProvider : WeatherProvider {
    override val id = "openmeteo"
    override val displayName = "Open-Meteo"
    override val requiresApiKey = false
    override val signupUrl: String? = null

    override suspend fun fetch(location: WeatherLocation, apiKey: String?): WeatherSnapshot {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${location.latitude}&longitude=${location.longitude}" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,weather_code,wind_speed_10m," +
            "surface_pressure,wind_gusts_10m,wind_direction_10m,cloud_cover,precipitation,dew_point_2m" +
            "&hourly=temperature_2m,precipitation_probability,weather_code,is_day,visibility,precipitation,snowfall," +
            "apparent_temperature,relative_humidity_2m,wind_speed_10m,wind_gusts_10m,wind_direction_10m,uv_index,cloud_cover," +
            "cape,lightning_potential,snow_depth,freezing_level_height" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code,uv_index_max,sunrise,sunset,moon_phase," +
            "precipitation_sum,precipitation_hours,snowfall_sum,sunshine_duration,daylight_duration,wind_speed_10m_max," +
            "wind_gusts_10m_max,wind_direction_10m_dominant" +
            "&minutely_15=precipitation&forecast_minutely_15=8" +
            "&forecast_days=7&timezone=auto&wind_speed_unit=kmh&timeformat=unixtime" +
            (OpenMeteoModels.selected()?.let { "&models=$it" } ?: "")
        val json = JSONObject(ProviderHttp.get(url))
        val place = location.name?.let { it to "" } ?: ProviderHttp.reverseGeocode(location.latitude, location.longitude)
        return try {
            parse(json, place)
        } catch (e: JSONException) {
            throw WeatherProviderException(WeatherProviderException.Reason.BAD_RESPONSE, e.message)
        }
    }

    override suspend fun searchCities(query: String, apiKey: String?): List<CityResult> = ProviderHttp.searchCities(query)

    private fun parse(json: JSONObject, place: Pair<String, String>?): WeatherSnapshot {
        val now = System.currentTimeMillis()
        val current = json.getJSONObject("current")
        val hourlyJson = json.getJSONObject("hourly")
        val daily = json.getJSONObject("daily")

        val times = hourlyJson.getJSONArray("time")
        val temps = hourlyJson.getJSONArray("temperature_2m")
        val rain = hourlyJson.optJSONArray("precipitation_probability")
        val codes = hourlyJson.getJSONArray("weather_code")
        val day = hourlyJson.optJSONArray("is_day")
        fun hourlyNumber(name: String, i: Int): Double? = hourlyJson.optJSONArray(name)?.takeIf { !it.isNull(i) }?.getDouble(i)
        fun dailyNumber(name: String, i: Int): Double? = daily.optJSONArray(name)?.takeIf { !it.isNull(i) }?.getDouble(i)
        val hourly = buildList {
            for (i in 0 until times.length()) {
                val time = times.getLong(i) * 1000L
                if (time + HOUR_MS <= now || temps.isNull(i)) continue
                add(
                    HourlyForecast(
                        timeMillis = time,
                        tempC = temps.getDouble(i),
                        condition = conditionFor(codes.optInt(i)),
                        isDay = day?.optInt(i, 1) != 0,
                        chanceOfRain = rain?.optInt(i) ?: 0,
                        precipitationMm = hourlyNumber("precipitation", i),
                        snowfallCm = hourlyNumber("snowfall", i),
                        feelsLikeC = hourlyNumber("apparent_temperature", i),
                        humidity = hourlyNumber("relative_humidity_2m", i)?.toInt(),
                        windKph = hourlyNumber("wind_speed_10m", i),
                        windGustKph = hourlyNumber("wind_gusts_10m", i),
                        windDirectionDeg = hourlyNumber("wind_direction_10m", i),
                        uvIndex = hourlyNumber("uv_index", i),
                        cloudCover = hourlyNumber("cloud_cover", i)?.toInt(),
                        cape = hourlyNumber("cape", i),
                        lightningPotential = hourlyNumber("lightning_potential", i),
                    ),
                )
            }
        }.take(HOURLY_COUNT)

        val code = current.optInt("weather_code")
        val temp = current.getDouble("temperature_2m")
        val visibilityArray = hourlyJson.optJSONArray("visibility")
        val currentHourIndex = (0 until times.length()).firstOrNull { times.getLong(it) * 1000L + HOUR_MS > now } ?: 0
        val dayTimes = daily.getJSONArray("time")
        val dailyCodes = daily.optJSONArray("weather_code")
        val dailyRain = daily.optJSONArray("precipitation_probability_max")
        val dailyHigh = daily.getJSONArray("temperature_2m_max")
        val dailyLow = daily.getJSONArray("temperature_2m_min")
        val dailyList = (0 until dayTimes.length()).filter { !dailyHigh.isNull(it) && !dailyLow.isNull(it) }.map { i ->
            DailyForecast(
                dayMillis = dayTimes.getLong(i) * 1000L,
                highC = dailyHigh.getDouble(i),
                lowC = dailyLow.getDouble(i),
                condition = conditionFor(dailyCodes?.optInt(i) ?: -1),
                chanceOfRain = dailyRain?.optInt(i) ?: 0,
                precipitationMm = dailyNumber("precipitation_sum", i),
                precipitationHours = dailyNumber("precipitation_hours", i),
                snowfallCm = dailyNumber("snowfall_sum", i),
                sunshineSeconds = dailyNumber("sunshine_duration", i),
                daylightSeconds = dailyNumber("daylight_duration", i),
                windMaxKph = dailyNumber("wind_speed_10m_max", i),
                windGustMaxKph = dailyNumber("wind_gusts_10m_max", i),
                windDirectionDeg = dailyNumber("wind_direction_10m_dominant", i),
                uvIndex = dailyNumber("uv_index_max", i),
                sunriseMillis = daily.optJSONArray("sunrise")?.takeIf { !it.isNull(i) }?.getLong(i)?.times(1000L),
                sunsetMillis = daily.optJSONArray("sunset")?.takeIf { !it.isNull(i) }?.getLong(i)?.times(1000L),
            )
        }
        val minutely = json.optJSONObject("minutely_15")?.let { block ->
            val minuteTimes = block.optJSONArray("time")
            val minuteRain = block.optJSONArray("precipitation")
            if (minuteTimes == null || minuteRain == null) return@let null
            (0 until minuteTimes.length())
                .filter { !minuteRain.isNull(it) }
                .map { MinutelyPrecipitation(minuteTimes.getLong(it) * 1000L, minuteRain.getDouble(it)) }
                .filter { it.timeMillis + QUARTER_HOUR_MS > now }
                .takeIf { it.isNotEmpty() }
        }
        fun optNumber(name: String): Double? = if (current.has(name) && !current.isNull(name)) current.getDouble(name) else null
        return WeatherSnapshot(
            locationName = place?.first.orEmpty(),
            region = place?.second.orEmpty(),
            tempC = temp,
            feelsLikeC = current.optDouble("apparent_temperature", temp),
            condition = conditionFor(code),
            conditionText = textFor(code),
            isDay = current.optInt("is_day", 1) == 1,
            humidity = current.optInt("relative_humidity_2m"),
            windKph = current.optDouble("wind_speed_10m"),
            chanceOfRain = daily.optJSONArray("precipitation_probability_max")?.optInt(0) ?: 0,
            highC = daily.getJSONArray("temperature_2m_max").getDouble(0),
            lowC = daily.getJSONArray("temperature_2m_min").getDouble(0),
            hourly = hourly,
            alerts = emptyList(),
            updatedAt = now,
            providerId = id,
            extras = WeatherExtras(
                pressureHpa = optNumber("surface_pressure"),
                visibilityKm = visibilityArray?.takeIf { !it.isNull(currentHourIndex) }?.getDouble(currentHourIndex)?.div(1000.0),
                dewPointC = optNumber("dew_point_2m"),
                cloudCover = optNumber("cloud_cover")?.toInt(),
                uvIndex = daily.optJSONArray("uv_index_max")?.takeIf { !it.isNull(0) }?.getDouble(0),
                windGustKph = optNumber("wind_gusts_10m"),
                windDirectionDeg = optNumber("wind_direction_10m"),
                precipitationMm = optNumber("precipitation"),
                sunriseMillis = daily.optJSONArray("sunrise")?.takeIf { !it.isNull(0) }?.getLong(0)?.times(1000L),
                sunsetMillis = daily.optJSONArray("sunset")?.takeIf { !it.isNull(0) }?.getLong(0)?.times(1000L),
                moonPhase = daily.optJSONArray("moon_phase")?.takeIf { !it.isNull(0) }?.getDouble(0),
                snowDepthCm = hourlyNumber("snow_depth", currentHourIndex)?.times(100.0),
                freezingLevelM = hourlyNumber("freezing_level_height", currentHourIndex),
                minutely = minutely,
            ),
            daily = dailyList,
        )
    }

    // https://open-meteo.com/en/docs (WMO weather interpretation codes)
    internal fun conditionFor(code: Int): WeatherCondition = when (code) {
        0, 1 -> WeatherCondition.CLEAR
        2 -> WeatherCondition.PARTLY_CLOUDY
        3 -> WeatherCondition.CLOUDY
        45, 48 -> WeatherCondition.FOG
        51, 53, 55 -> WeatherCondition.DRIZZLE
        56, 57, 66, 67 -> WeatherCondition.SLEET
        61, 63, 80, 81 -> WeatherCondition.RAIN
        65, 82 -> WeatherCondition.HEAVY_RAIN
        71, 73, 75, 77, 85, 86 -> WeatherCondition.SNOW
        95, 96, 99 -> WeatherCondition.THUNDERSTORM
        else -> WeatherCondition.UNKNOWN
    }

    private fun textFor(code: Int): String = when (code) {
        0 -> "Clear sky"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71 -> "Light snow"
        73 -> "Snow"
        75, 86 -> "Heavy snow"
        77 -> "Snow grains"
        80, 81 -> "Rain showers"
        82 -> "Violent rain showers"
        85 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> String.format(Locale.ROOT, "Weather %d", code)
    }

    private companion object {
        const val HOUR_MS = 60 * 60 * 1000L
        const val QUARTER_HOUR_MS = 15 * 60 * 1000L
        const val HOURLY_COUNT = 24
    }
}
