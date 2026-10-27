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

data class RadarTile(val dx: Int, val dy: Int, val baseUrl: String, val overlayUrl: String)

data class RadarMap(
    val tiles: List<RadarTile>,
    val offsetX: Float,
    val offsetY: Float,
    val timeMillis: Long?,
    val attribution: String,
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

        val overlay: (Int, Int) -> String
        val time: Long?
        val attribution: String
        when (provider) {
            RadarProviders.RAINVIEWER_ID -> {
                val json = try {
                    JSONObject(fetch("https://api.rainviewer.com/public/weather-maps.json"))
                } catch (e: JSONException) {
                    throw RadarException(RadarException.Reason.BAD_RESPONSE, e.message)
                }
                val host = json.optString("host").ifBlank { "https://tilecache.rainviewer.com" }
                val past = json.optJSONObject("radar")?.optJSONArray("past")
                val frame = past?.optJSONObject(past.length() - 1) ?: throw RadarException(RadarException.Reason.BAD_RESPONSE)
                val path = frame.optString("path")
                time = frame.optLong("time") * 1000L
                overlay = { tx, ty -> "$host$path/256/$ZOOM/$tx/$ty/2/1_1.png" }
                attribution = "RainViewer · © OpenStreetMap contributors"
            }

            RadarProviders.OPENWEATHERMAP_ID -> {
                val key = settings.getWeatherApiKey(RadarProviders.OPENWEATHERMAP_ID) ?: throw RadarException(RadarException.Reason.MISSING_KEY)
                time = null
                overlay = { tx, ty -> "https://tile.openweathermap.org/map/precipitation_new/$ZOOM/$tx/$ty.png?appid=${ProviderHttp.encode(key)}" }
                attribution = "OpenWeatherMap · © OpenStreetMap contributors"
            }

            else -> throw RadarException(RadarException.Reason.BAD_RESPONSE)
        }

        fetch(overlay(Math.floorMod(tileX, n), tileY.coerceIn(0, n - 1)))

        val tiles = buildList {
            for (dy in -GRID..GRID) for (dx in -GRID..GRID) {
                val tx = Math.floorMod(tileX + dx, n)
                val ty = (tileY + dy).coerceIn(0, n - 1)
                add(RadarTile(dx, dy, "https://tile.openstreetmap.org/$ZOOM/$tx/$ty.png", overlay(tx, ty)))
            }
        }
        return RadarMap(tiles, (x - tileX).toFloat(), (y - tileY).toFloat(), time, attribution)
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
