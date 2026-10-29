package com.sameerasw.overcast.weather.scene

import android.content.Context
import com.sameerasw.overcast.data.repository.SettingsRepository
import com.sameerasw.overcast.weather.WeatherRepository
import com.sameerasw.overcast.weather.provider.ProviderHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot

enum class SceneType { CITY, COASTAL_CITY, COAST, MOUNTAINS, HILLS, FOREST, FIELDS, DESERT }

private class PlaceSignals(val seaPoints: Int, val peakM: Double, val reliefM: Double, val population: Long, val rainMm: Double)

object SceneRepository {
    private const val PREFS = "location_scene_v2"
    private const val GRID = 2
    private const val GRID_STEP_KM = 4.0
    private const val BIG_CITY = 300_000L
    private const val TOWN = 50_000L

    suspend fun current(context: Context, placeName: String): SceneType? {
        val settings = SettingsRepository(context)
        if (!settings.isLocationSceneEnabled()) return null
        settings.getSimulatedScene()?.let { id -> SceneType.entries.firstOrNull { it.name == id }?.let { return it } }
        val location = WeatherRepository.lastKnownLocation(context) ?: return null
        return sceneFor(context, location.latitude, location.longitude, placeName)
    }

    suspend fun sceneFor(context: Context, latitude: Double, longitude: Double, placeName: String): SceneType? {
        val lat = String.format(Locale.US, "%.2f", latitude).toDouble()
        val lon = String.format(Locale.US, "%.2f", longitude).toDouble()
        val key = "$lat,$lon"
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(key, null)?.let { cached -> SceneType.entries.firstOrNull { it.name == cached }?.let { return it } }
        val scene = try {
            classify(detect(lat, lon, placeName))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        prefs.edit().putString(key, scene.name).apply()
        return scene
    }

    private fun classify(s: PlaceSignals): SceneType = when {
        s.population >= BIG_CITY -> SceneType.CITY
        s.seaPoints > 0 -> if (s.population >= TOWN) SceneType.COASTAL_CITY else SceneType.COAST
        s.peakM > 1500 || s.reliefM > 800 -> SceneType.MOUNTAINS
        s.rainMm in 0.0..250.0 -> SceneType.DESERT
        s.reliefM > 250 -> SceneType.HILLS
        s.rainMm > 1800 -> SceneType.FOREST
        else -> SceneType.FIELDS
    }

    private suspend fun detect(lat: Double, lon: Double, placeName: String): PlaceSignals = coroutineScope {
        val population = async { runCatching { population(lat, lon, placeName) }.getOrDefault(0L) }
        val rain = async { runCatching { yearlyRain(lat, lon) }.getOrDefault(-1.0) }
        val elevations = elevationGrid(lat, lon)
        val peak = elevations.max()
        PlaceSignals(elevations.count { it <= 0.0 }, peak, peak - elevations.min(), population.await(), rain.await())
    }

    private suspend fun elevationGrid(lat: Double, lon: Double): List<Double> {
        val dLat = GRID_STEP_KM / 111.0
        val dLon = GRID_STEP_KM / (111.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.1))
        val lats = ArrayList<String>()
        val lons = ArrayList<String>()
        for (y in -GRID..GRID) for (x in -GRID..GRID) {
            lats += String.format(Locale.US, "%.3f", lat + y * dLat)
            lons += String.format(Locale.US, "%.3f", lon + x * dLon)
        }
        val json = JSONObject(ProviderHttp.get("https://api.open-meteo.com/v1/elevation?latitude=${lats.joinToString(",")}&longitude=${lons.joinToString(",")}"))
        return json.getJSONArray("elevation").let { array -> List(array.length()) { array.getDouble(it) } }
    }

    private suspend fun population(lat: Double, lon: Double, placeName: String): Long {
        if (placeName.isBlank()) return 0L
        val json = JSONObject(ProviderHttp.get("https://geocoding-api.open-meteo.com/v1/search?count=10&name=${ProviderHttp.encode(placeName)}"))
        val results = json.optJSONArray("results") ?: return 0L
        val scale = cos(Math.toRadians(lat))
        return (0 until results.length()).mapNotNull { results.optJSONObject(it) }
            .filter { hypot(it.optDouble("latitude") - lat, (it.optDouble("longitude") - lon) * scale) < 0.2 }
            .maxOfOrNull { it.optLong("population") } ?: 0L
    }

    private suspend fun yearlyRain(lat: Double, lon: Double): Double {
        val end = LocalDate.now().minusDays(7)
        val start = end.minusDays(364)
        val json = JSONObject(
            ProviderHttp.get("https://archive-api.open-meteo.com/v1/archive?latitude=$lat&longitude=$lon&start_date=$start&end_date=$end&daily=precipitation_sum"),
        )
        val days = json.getJSONObject("daily").getJSONArray("precipitation_sum")
        return (0 until days.length()).sumOf { days.optDouble(it, 0.0).takeIf { v -> !v.isNaN() } ?: 0.0 }
    }
}
