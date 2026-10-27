package com.sameerasw.overcast.weather.extra

import android.content.Context
import com.sameerasw.overcast.weather.location.WeatherPlace
import com.sameerasw.overcast.weather.location.WeatherPlaces
import com.sameerasw.overcast.weather.model.WeatherCondition
import com.sameerasw.overcast.weather.provider.OpenMeteoProvider
import com.sameerasw.overcast.weather.provider.ProviderHttp
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class PlaceConditions(val place: WeatherPlace, val tempC: Double, val condition: WeatherCondition, val isDay: Boolean)

object PlacesRepository {
    private const val CACHE_MS = 20 * 60_000L

    @Volatile private var cache: Pair<Long, List<PlaceConditions>>? = null

    // One Open-Meteo call covers every saved place; it returns an array for several coordinates and an object for one.
    suspend fun saved(context: Context): List<PlaceConditions> {
        val places = WeatherPlaces(context).saved()
        if (places.isEmpty()) return emptyList()
        cache?.takeIf { (at, list) -> System.currentTimeMillis() - at < CACHE_MS && list.map { it.place.id } == places.map { it.id } }?.let { return it.second }
        val lats = places.joinToString(",") { "%.4f".format(Locale.US, it.latitude) }
        val lons = places.joinToString(",") { "%.4f".format(Locale.US, it.longitude) }
        val raw = ProviderHttp.get("https://api.open-meteo.com/v1/forecast?latitude=$lats&longitude=$lons&current=temperature_2m,weather_code,is_day")
        val results = if (raw.trimStart().startsWith("[")) JSONArray(raw).let { array -> List(array.length()) { array.getJSONObject(it) } } else listOf(JSONObject(raw))
        val provider = OpenMeteoProvider()
        val list = places.zip(results).mapNotNull { (place, json) ->
            val current = json.optJSONObject("current") ?: return@mapNotNull null
            val temp = current.optDouble("temperature_2m").takeIf { !it.isNaN() } ?: return@mapNotNull null
            PlaceConditions(place, temp, provider.conditionFor(current.optInt("weather_code")), current.optInt("is_day", 1) == 1)
        }
        cache = System.currentTimeMillis() to list
        return list
    }
}
