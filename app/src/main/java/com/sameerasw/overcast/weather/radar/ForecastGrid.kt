package com.sameerasw.overcast.weather.radar

import com.sameerasw.overcast.weather.provider.ProviderHttp
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

// Values are row-major per hour, sampled at grid nodes spaced `step` radar-tile units apart from the grid's top-left.
class ForecastGrid(
    val cols: Int,
    val rows: Int,
    val step: Float,
    val times: List<Long>,
    val temperatureC: List<FloatArray>,
    val cloudCover: List<FloatArray>,
    val rainMm: List<FloatArray>,
)

object ForecastGridRepository {
    private const val NODES = 9
    private const val HOURS = 24

    suspend fun load(map: RadarMap): ForecastGrid? {
        if (map.gridTiles == 0) return null
        val n = (1 shl map.zoom).toDouble()
        val step = map.gridTiles.toFloat() / (NODES - 1)
        val lats = ArrayList<String>()
        val lons = ArrayList<String>()
        for (row in 0 until NODES) for (col in 0 until NODES) {
            val tx = map.originTileX + col * step.toDouble()
            val ty = (map.originTileY + row * step.toDouble()).coerceIn(0.0, n)
            lons += String.format(Locale.US, "%.3f", tx / n * 360.0 - 180.0)
            lats += String.format(Locale.US, "%.3f", Math.toDegrees(atan(sinh(PI * (1 - 2 * ty / n)))))
        }
        val body = ProviderHttp.get(
            "https://api.open-meteo.com/v1/forecast?latitude=${lats.joinToString(",")}&longitude=${lons.joinToString(",")}" +
                "&hourly=temperature_2m,cloud_cover,precipitation&forecast_hours=$HOURS&timeformat=unixtime",
        )
        val points = body.trim().let { if (it.startsWith("[")) JSONArray(it) else JSONArray().put(JSONObject(it)) }
        if (points.length() != NODES * NODES) return null
        val times = points.getJSONObject(0).getJSONObject("hourly").getJSONArray("time").let { t -> List(t.length()) { t.getLong(it) * 1000L } }
        fun series(name: String): List<FloatArray> = List(times.size) { hour ->
            FloatArray(NODES * NODES) { i ->
                points.getJSONObject(i).getJSONObject("hourly").getJSONArray(name).optDouble(hour, 0.0).toFloat().takeIf { !it.isNaN() } ?: 0f
            }
        }
        return ForecastGrid(NODES, NODES, step, times, series("temperature_2m"), series("cloud_cover"), series("precipitation"))
    }
}
