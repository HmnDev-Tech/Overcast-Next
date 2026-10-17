/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Weather - Map
 * File: WeatherMapRepository.kt
 * Description: Radar, satellite and temperature-grid data for the weather map.
 */

package com.sameerasw.overcast.weather.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.sameerasw.overcast.weather.provider.ProviderHttp
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class RadarFrame(val timeMillis: Long, val path: String)

data class MapTilesSequence(val host: String, val frames: List<RadarFrame>) {
    fun isEmpty(): Boolean = frames.isEmpty() || host.isBlank()

    fun radarUrl(frame: RadarFrame, z: Int, x: Int, y: Int): String =
        "$host${frame.path}/256/$z/$x/$y/2/1_1.png"

    fun satelliteUrl(frame: RadarFrame, z: Int, x: Int, y: Int): String =
        "$host${frame.path}/512/$z/$x/$y/0/0_0.png"
}

data class TemperatureGrid(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
    val cols: Int,
    val rows: Int,
    val values: List<Double?>,
) {
    fun value(col: Int, row: Int): Double? = values.getOrNull(row * cols + col)
}

class MapDataException(message: String?) : Exception(message)

object WeatherMapRepository {
    private const val GRID_COLS = 12
    private const val GRID_ROWS = 9
    private const val STALE_MS = 10 * 60_000L

    private val mutex = Mutex()
    private var radar: MapTilesSequence? = null
    private var radarAt = 0L
    private var satellite: MapTilesSequence? = null
    private var satelliteAt = 0L

    suspend fun radarFrames(force: Boolean = false): MapTilesSequence = mutex.withLock {
        val cached = radar?.takeIf { !force && System.currentTimeMillis() - radarAt < STALE_MS }
        if (cached != null) return cached
        val json = try {
            JSONObject(ProviderHttp.get("https://api.rainviewer.com/public/weather-maps.json"))
        } catch (e: Exception) {
            throw MapDataException(e.message ?: "Radar request failed")
        }
        val host = json.optString("host")
        val radarObj = json.optJSONObject("radar")
        val frames = ArrayList<RadarFrame>()
        listOf("past", "nowcast").forEach { key ->
            val arr = radarObj?.optJSONArray(key) ?: return@forEach
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                frames.add(RadarFrame(o.optLong("time") * 1000L, o.optString("path")))
            }
        }
        val sequence = MapTilesSequence(host, frames.sortedBy { it.timeMillis })
        radar = sequence
        radarAt = System.currentTimeMillis()
        sequence
    }

    suspend fun satelliteFrames(force: Boolean = false): MapTilesSequence = mutex.withLock {
        val cached = satellite?.takeIf { !force && System.currentTimeMillis() - satelliteAt < STALE_MS }
        if (cached != null) return cached
        val json = try {
            JSONObject(ProviderHttp.get("https://api.rainviewer.com/public/weather-maps.json"))
        } catch (e: Exception) {
            throw MapDataException(e.message ?: "Satellite request failed")
        }
        val host = json.optString("host")
        val arr = json.optJSONObject("satellite")?.optJSONArray("infrared")
        val frames = ArrayList<RadarFrame>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                frames.add(RadarFrame(o.optLong("time") * 1000L, o.optString("path")))
            }
        }
        val sequence = MapTilesSequence(host, frames.sortedBy { it.timeMillis })
        satellite = sequence
        satelliteAt = System.currentTimeMillis()
        sequence
    }

    // Open-Meteo pairs each latitude with the longitude at the same index, so the grid
    // is sent as one flat list of (row, col) coordinates and comes back in the same order.
    suspend fun temperatureGrid(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): TemperatureGrid {
        val lats = ArrayList<Double>(GRID_COLS * GRID_ROWS)
        val lons = ArrayList<Double>(GRID_COLS * GRID_ROWS)
        for (row in 0 until GRID_ROWS) {
            val lat = maxLat - (maxLat - minLat) * row / (GRID_ROWS - 1.0)
            for (col in 0 until GRID_COLS) {
                lats.add(lat)
                lons.add(minLon + (maxLon - minLon) * col / (GRID_COLS - 1.0))
            }
        }
        val latParam = lats.joinToString(",") { "%.3f".format(it) }
        val lonParam = lons.joinToString(",") { "%.3f".format(it) }
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$latParam&longitude=$lonParam" +
            "&current=temperature_2m&timezone=auto"
        val raw = try {
            ProviderHttp.get(url)
        } catch (e: Exception) {
            throw MapDataException(e.message ?: "Temperature request failed")
        }
        val arr = try {
            JSONArray(raw)
        } catch (e: Exception) {
            throw MapDataException("Bad temperature response")
        }
        val values = ArrayList<Double?>(GRID_COLS * GRID_ROWS)
        for (i in 0 until arr.length()) {
            val current = arr.getJSONObject(i).optJSONObject("current")
            val t = current?.optDouble("temperature_2m")?.takeIf { !it.isNaN() }
            values.add(t)
        }
        while (values.size < GRID_COLS * GRID_ROWS) values.add(null)
        return TemperatureGrid(minLat, maxLat, minLon, maxLon, GRID_COLS, GRID_ROWS, values)
    }

    suspend fun temperatureAt(latitude: Double, longitude: Double): Double? {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
            "&current=temperature_2m&timezone=auto"
        val raw = try {
            ProviderHttp.get(url)
        } catch (e: Exception) {
            return null
        }
        val current = JSONObject(raw).optJSONObject("current") ?: return null
        val t = current.optDouble("temperature_2m")
        return t.takeIf { !it.isNaN() }
    }
}

// Small shared bitmap cache for base-map and overlay tiles, sized in kilobytes.
internal object MapTileCache {
    private const val MAX_KB = 48 * 1024

    private val cache = object : LruCache<String, Bitmap>(MAX_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    suspend fun get(url: String): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(url) ?: fetch(url)?.also { cache.put(url, it) }
    }

    private fun fetch(url: String): Bitmap? {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (_: Exception) {
            return null
        }
        return try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", ProviderHttp.USER_AGENT)
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
