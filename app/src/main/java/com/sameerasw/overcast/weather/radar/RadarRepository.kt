package com.sameerasw.overcast.weather.radar

import android.content.Context
import com.sameerasw.overcast.data.repository.SettingsRepository
import com.sameerasw.overcast.weather.WeatherRepository
import com.sameerasw.overcast.weather.provider.ProviderHttp
import com.sameerasw.overcast.weather.provider.WeatherProviderException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONException
import org.json.JSONObject

object RadarProviders {
    const val OFF_ID = "off"
    const val RAINVIEWER_ID = "rainviewer"
    const val OPENWEATHERMAP_ID = "openweathermap"

    val all: List<Pair<String, String?>> = listOf(
        OFF_ID to null,
        RAINVIEWER_ID to "RainViewer",
        OPENWEATHERMAP_ID to "OpenWeatherMap",
    )

    fun resolve(id: String?): String = all.firstOrNull { it.first == id }?.first ?: OFF_ID
}

// Positions and sizes are in radar-tile units from the top-left of the grid.
data class MapTile(val url: String, val x: Float, val y: Float, val size: Float)

data class RadarFrame(val timeMillis: Long?, val tiles: List<MapTile>)

data class RadarMap(
    val baseTiles: List<MapTile>,
    val frames: List<RadarFrame>,
    val centerX: Float,
    val centerY: Float,
)

class RadarException(val reason: Reason, message: String? = null) : Exception(message) {
    enum class Reason { NO_LOCATION, MISSING_KEY, INVALID_KEY, RATE_LIMITED, NETWORK, BAD_RESPONSE }
}

sealed interface RadarStatus {
    data object Ok : RadarStatus
    data class Failed(val error: RadarException) : RadarStatus
}

object RadarRepository {
    // RainViewer's free tier caps zoom at 7.
    private const val ZOOM = 7
    private const val GRID = 1
    private const val BASE_ZOOM_STEP = 1
    private const val LOOP_FRAMES = 6

    private val _status = MutableStateFlow<RadarStatus?>(null)
    val status: StateFlow<RadarStatus?> = _status.asStateFlow()

    fun clearStatus() {
        _status.value = null
    }

    suspend fun current(context: Context): RadarMap = try {
        load(context).also { _status.value = RadarStatus.Ok }
    } catch (e: RadarException) {
        _status.value = RadarStatus.Failed(e)
        throw e
    }

    private suspend fun load(context: Context): RadarMap {
        val settings = SettingsRepository(context)
        val provider = RadarProviders.resolve(settings.getRadarProvider())
        val loc = WeatherRepository.lastKnownLocation(context) ?: throw RadarException(RadarException.Reason.NO_LOCATION)
        val n = 1 shl ZOOM
        val x = (loc.longitude + 180.0) / 360.0 * n
        val latRad = Math.toRadians(loc.latitude)
        val y = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n
        val tileX = floor(x).toInt()
        val tileY = floor(y).toInt()

        val frameUrls: List<Pair<Long?, (Int, Int) -> String>>
        when (provider) {
            RadarProviders.RAINVIEWER_ID -> {
                val json = try {
                    JSONObject(fetch("https://api.rainviewer.com/public/weather-maps.json"))
                } catch (e: JSONException) {
                    throw RadarException(RadarException.Reason.BAD_RESPONSE, e.message)
                }
                val host = json.optString("host").ifBlank { "https://tilecache.rainviewer.com" }
                val past = json.optJSONObject("radar")?.optJSONArray("past")
                if (past == null || past.length() == 0) throw RadarException(RadarException.Reason.BAD_RESPONSE)
                frameUrls = (maxOf(0, past.length() - LOOP_FRAMES) until past.length()).mapNotNull { i ->
                    val frame = past.optJSONObject(i) ?: return@mapNotNull null
                    val path = frame.optString("path")
                    val url: (Int, Int) -> String = { tx, ty -> "$host$path/512/$ZOOM/$tx/$ty/2/1_1.png" }
                    frame.optLong("time") * 1000L to url
                }
            }

            RadarProviders.OPENWEATHERMAP_ID -> {
                val key = settings.getWeatherApiKey(RadarProviders.OPENWEATHERMAP_ID) ?: throw RadarException(RadarException.Reason.MISSING_KEY)
                val url: (Int, Int) -> String = { tx, ty -> "https://tile.openweathermap.org/map/precipitation_new/$ZOOM/$tx/$ty.png?appid=${ProviderHttp.encode(key)}" }
                frameUrls = listOf(null to url)
            }

            else -> throw RadarException(RadarException.Reason.BAD_RESPONSE)
        }

        fetch(frameUrls.last().second(Math.floorMod(tileX, n), tileY.coerceIn(0, n - 1)))

        val frames = frameUrls.map { (time, overlay) ->
            RadarFrame(
                time,
                buildList {
                    for (dy in -GRID..GRID) for (dx in -GRID..GRID) {
                        val ty = tileY + dy
                        if (ty !in 0 until n) continue
                        add(MapTile(overlay(Math.floorMod(tileX + dx, n), ty), (dx + GRID).toFloat(), (dy + GRID).toFloat(), 1f))
                    }
                },
            )
        }
        val split = 1 shl BASE_ZOOM_STEP
        val baseN = n * split
        val baseSize = 1f / split
        val baseTiles = buildList {
            for (by in 0 until (GRID * 2 + 1) * split) for (bx in 0 until (GRID * 2 + 1) * split) {
                val ty = (tileY - GRID) * split + by
                if (ty !in 0 until baseN) continue
                val tx = Math.floorMod((tileX - GRID) * split + bx, baseN)
                add(MapTile("https://tile.openstreetmap.org/${ZOOM + BASE_ZOOM_STEP}/$tx/$ty.png", bx * baseSize, by * baseSize, baseSize))
            }
        }
        return RadarMap(baseTiles, frames, GRID + (x - tileX).toFloat(), GRID + (y - tileY).toFloat())
    }

    private suspend fun fetch(url: String): String = try {
        ProviderHttp.get(url)
    } catch (e: WeatherProviderException) {
        throw RadarException(
            when (e.reason) {
                WeatherProviderException.Reason.INVALID_KEY -> RadarException.Reason.INVALID_KEY
                WeatherProviderException.Reason.RATE_LIMITED -> RadarException.Reason.RATE_LIMITED
                WeatherProviderException.Reason.NETWORK -> RadarException.Reason.NETWORK
                WeatherProviderException.Reason.BAD_RESPONSE -> RadarException.Reason.BAD_RESPONSE
            },
            e.message,
        )
    }
}
