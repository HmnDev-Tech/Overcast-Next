package com.sameerasw.overcast.weather.provider

import com.sameerasw.overcast.R
import com.sameerasw.overcast.weather.model.CityResult
import com.sameerasw.overcast.weather.model.DailyForecast
import com.sameerasw.overcast.weather.model.HourlyForecast
import com.sameerasw.overcast.weather.model.WeatherCondition
import com.sameerasw.overcast.weather.model.WeatherExtras
import com.sameerasw.overcast.weather.model.WeatherLocation
import com.sameerasw.overcast.weather.model.WeatherSnapshot
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.roundToInt

// Yandex Weather API v3 (GraphQL), the free tariff for personal, non-commercial use.
// Terms: https://yandex.ru/legal/apib2c_weather_agreement/ru/ (points 2.2, 2.4, 2.6, 2.7).
// Point 2.7 lists temperature, feels-like, wind, condition and icon. The owner also requests the other
// fields the key returns (rain chance, UV, gusts, wind direction, sunrise and sunset): see the commit message.
class YandexWeatherProvider : WeatherProvider {
    override val id = "yandex"
    override val displayName = "Yandex Weather"
    override val requiresApiKey = true
    override val signupUrl = "https://yandex.ru/pogoda/b2b/console/smarthome"
    // The tariff allows 30 requests a day. A 50-minute gap keeps any rolling day under that; the repository enforces it.
    override val minRefreshMinutes = 50
    override val attributionRes = R.string.weather_attribution_yandex

    override suspend fun fetch(location: WeatherLocation, apiKey: String?): WeatherSnapshot {
        val key = apiKey?.trim().orEmpty()
        if (key.isEmpty()) throw WeatherProviderException(WeatherProviderException.Reason.INVALID_KEY)
        val body = JSONObject().put("query", graphQuery(location)).toString()
        val json = try {
            JSONObject(ProviderHttp.post(BASE_URL, body, mapOf(KEY_HEADER to key)))
        } catch (e: JSONException) {
            throw WeatherProviderException(WeatherProviderException.Reason.BAD_RESPONSE, e.message)
        }
        throwOnApiErrors(json)
        val place = location.name?.let { it to "" } ?: ProviderHttp.reverseGeocode(location.latitude, location.longitude)
        return try {
            parseForecast(json.getJSONObject("data").getJSONObject("weatherByPoint"), place)
        } catch (e: JSONException) {
            throw WeatherProviderException(WeatherProviderException.Reason.BAD_RESPONSE, e.message)
        }
    }

    override suspend fun searchCities(query: String, apiKey: String?): List<CityResult> = ProviderHttp.searchCities(query)

    // GraphQL reports failures with HTTP 200 and an errors array instead of data.
    private fun throwOnApiErrors(json: JSONObject) {
        if (!json.isNull("data")) return
        val message = json.optJSONArray("errors")?.optJSONObject(0)?.optString("message").orEmpty()
        val reason = if (AUTH_ERROR.containsMatchIn(message)) {
            WeatherProviderException.Reason.INVALID_KEY
        } else {
            WeatherProviderException.Reason.BAD_RESPONSE
        }
        throw WeatherProviderException(reason, message.ifBlank { null })
    }

    // Point 2.7 parameters plus precProbability (see the header). The forecast covers today and tomorrow.
    private fun graphQuery(location: WeatherLocation) = """
        {
          weatherByPoint(request: { lat: ${coordinate(location.latitude)}, lon: ${coordinate(location.longitude)} }) {
            now {
              temperature(unit: CELSIUS)
              feelsLike(unit: CELSIUS)
              windSpeed(unit: KILOMETERS_PER_HOUR)
              windGust(unit: KILOMETERS_PER_HOUR)
              windDirection
              uvIndex
              condition
              icon(format: CODE)
            }
            forecast {
              days(limit: 2) {
                timestamp
                sunriseTimestamp
                sunsetTimestamp
                hours {
                  timestamp
                  temperature(unit: CELSIUS)
                  condition
                  icon(format: CODE)
                  precProbability
                }
              }
            }
          }
        }
    """.trimIndent()

    // Two decimals is about 1 km, which keeps the exact position off the wire.
    private fun coordinate(value: Double): Double = (value * 100).roundToInt() / 100.0

    private fun parseForecast(weather: JSONObject, place: Pair<String, String>?): WeatherSnapshot {
        val now = weather.getJSONObject("now")
        val days = weather.getJSONObject("forecast").getJSONArray("days")
        val nowMillis = System.currentTimeMillis()
        val daily = (0 until days.length()).mapNotNull { dayFrom(days.getJSONObject(it)) }
        val today = daily.firstOrNull()
        val extras = WeatherExtras(
            uvIndex = now.optDouble("uvIndex").takeUnless { it.isNaN() },
            windGustKph = now.optDouble("windGust").takeUnless { it.isNaN() },
            windDirectionDeg = windDirectionDeg(now.optString("windDirection")),
            sunriseMillis = days.getJSONObject(0).optLong("sunriseTimestamp").takeIf { it > 0 }?.times(1000L),
            sunsetMillis = days.getJSONObject(0).optLong("sunsetTimestamp").takeIf { it > 0 }?.times(1000L),
        )

        val hourly = buildList {
            for (d in 0 until days.length()) {
                val hours = days.getJSONObject(d).getJSONArray("hours")
                for (h in 0 until hours.length()) {
                    val hour = hours.getJSONObject(h)
                    val timestamp = hour.getLong("timestamp")
                    if ((timestamp + HOUR_S) * 1000L <= nowMillis) continue
                    add(
                        HourlyForecast(
                            timeMillis = timestamp * 1000L,
                            tempC = hour.getDouble("temperature"),
                            condition = conditionFor(hour.getString("condition")),
                            isDay = isDaytime(timestamp, days.getJSONObject(d), hour.optString("icon")),
                            chanceOfRain = percent(hour.optDouble("precProbability")),
                        ),
                    )
                }
            }
        }.take(HOURLY_COUNT)

        return WeatherSnapshot(
            locationName = place?.first.orEmpty(),
            region = place?.second.orEmpty(),
            tempC = now.getDouble("temperature"),
            feelsLikeC = now.getDouble("feelsLike"),
            condition = conditionFor(now.getString("condition")),
            conditionText = now.getString("condition"),
            isDay = isDaytime(nowMillis / 1000L, days.getJSONObject(0), now.optString("icon")),
            humidity = null,
            windKph = now.getDouble("windSpeed"),
            chanceOfRain = today?.chanceOfRain,
            highC = today?.highC ?: now.getDouble("temperature"),
            lowC = today?.lowC ?: now.getDouble("temperature"),
            hourly = hourly,
            alerts = emptyList(),
            updatedAt = nowMillis,
            providerId = id,
            extras = extras,
            daily = daily,
        )
    }

    // Highs and lows come from the hourly temperatures, since the free tariff has no daily summary.
    private fun dayFrom(day: JSONObject): DailyForecast? {
        val hours = day.getJSONArray("hours")
        if (hours.length() == 0) return null
        val temps = (0 until hours.length()).map { hours.getJSONObject(it).getDouble("temperature") }
        val midday = hours.getJSONObject(minOf(12, hours.length() - 1))
        return DailyForecast(
            dayMillis = day.getLong("timestamp") * 1000L,
            highC = temps.max(),
            lowC = temps.min(),
            condition = conditionFor(midday.getString("condition")),
            chanceOfRain = (0 until hours.length()).maxOf { percent(hours.getJSONObject(it).optDouble("precProbability")) },
        )
    }

    // The free tariff has no daytime field, so the day's sunrise and sunset decide; night icons end in "_n", for example "skc_n", as a fallback.
    private fun isDaytime(timestampS: Long, day: JSONObject, icon: String): Boolean {
        val sunrise = day.optLong("sunriseTimestamp")
        val sunset = day.optLong("sunsetTimestamp")
        return if (sunrise > 0 && sunset > sunrise) timestampS in sunrise until sunset else !icon.endsWith("_n")
    }

    // precProbability is a 0..1 chance; a missing field (NaN) counts as 0.
    private fun percent(probability: Double): Int = if (probability.isNaN()) 0 else (probability * 100).roundToInt()

    // Yandex sends a compass point; CALM has no direction.
    private fun windDirectionDeg(code: String): Double? = when (code) {
        "NORTH" -> 0.0
        "NORTH_EAST" -> 45.0
        "EAST" -> 90.0
        "SOUTH_EAST" -> 135.0
        "SOUTH" -> 180.0
        "SOUTH_WEST" -> 225.0
        "WEST" -> 270.0
        "NORTH_WEST" -> 315.0
        else -> null
    }

    // https://yandex.ru/dev/weather/doc/ru/concepts/spectaql.md (the Condition enum)
    private fun conditionFor(code: String): WeatherCondition = when (code) {
        "CLEAR" -> WeatherCondition.CLEAR
        "PARTLY_CLOUDY" -> WeatherCondition.PARTLY_CLOUDY
        "CLOUDY", "OVERCAST" -> WeatherCondition.CLOUDY
        "LIGHT_RAIN" -> WeatherCondition.DRIZZLE
        "RAIN", "SHOWERS" -> WeatherCondition.RAIN
        "HEAVY_RAIN" -> WeatherCondition.HEAVY_RAIN
        "SLEET" -> WeatherCondition.SLEET
        "LIGHT_SNOW", "SNOW", "SNOWFALL" -> WeatherCondition.SNOW
        "HAIL" -> WeatherCondition.HAIL
        "THUNDERSTORM", "THUNDERSTORM_WITH_RAIN", "THUNDERSTORM_WITH_HAIL" -> WeatherCondition.THUNDERSTORM
        else -> WeatherCondition.UNKNOWN
    }

    private companion object {
        const val BASE_URL = "https://api.weather.yandex.ru/graphql/query"
        const val KEY_HEADER = "X-Yandex-Weather-Key"
        const val HOUR_S = 60 * 60L
        const val HOURLY_COUNT = 24
        val AUTH_ERROR = Regex("unauthori[sz]ed|forbidden|api key|invalid key|access denied", RegexOption.IGNORE_CASE)
    }
}
