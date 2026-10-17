/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: UI Feature - Weather
 * File: WeatherMapScreen.kt
 * Description: Full screen weather map with precipitation, temperature and satellite layers.
 */

package com.sameerasw.overcast.ui.features.weather.map

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sameerasw.overcast.R
import com.sameerasw.overcast.data.repository.SettingsRepository
import com.sameerasw.overcast.ui.core.pickers.SegmentedPicker
import com.sameerasw.overcast.weather.location.DeviceLocationSource
import com.sameerasw.overcast.weather.map.MapTileCache
import com.sameerasw.overcast.weather.map.MapTilesSequence
import com.sameerasw.overcast.weather.map.TemperatureGrid
import com.sameerasw.overcast.weather.map.WeatherMapRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan
import kotlinx.coroutines.delay

enum class MapLayer { PRECIPITATION, TEMPERATURE, SATELLITE }

private const val MIN_ZOOM = 3f
private const val MAX_ZOOM = 16f
private const val OVERLAY_MAX_ZOOM = 12

private object Mercator {
    private const val TILE = 256.0

    fun worldX(lon: Double, zoom: Double): Double = (lon + 180.0) / 360.0 * TILE * 2.0.pow(zoom)

    fun worldY(lat: Double, zoom: Double): Double {
        val r = Math.toRadians(lat.coerceIn(-85.05, 85.05))
        val y = (1.0 - ln(tan(r) + 1.0 / cos(r)) / Math.PI) / 2.0
        return y * TILE * 2.0.pow(zoom)
    }

    fun lonAt(worldX: Double, zoom: Double): Double = worldX / (TILE * 2.0.pow(zoom)) * 360.0 - 180.0

    fun latAt(worldY: Double, zoom: Double): Double {
        val n = Math.PI - 2.0 * Math.PI * worldY / (TILE * 2.0.pow(zoom))
        return Math.toDegrees(Math.atan(sinh(n)))
    }
}

private class MapViewport(lat: Double, lon: Double, initialZoom: Float) {
    var centerLat by androidx.compose.runtime.mutableDoubleStateOf(lat)
    var centerLon by androidx.compose.runtime.mutableDoubleStateOf(lon)
    var zoom by mutableFloatStateOf(initialZoom)

    // Move the map so the content follows the finger.
    fun pan(dxPx: Float, dyPx: Float) {
        val wx = Mercator.worldX(centerLon, zoom.toDouble()) - dxPx
        val wy = Mercator.worldY(centerLat, zoom.toDouble()) - dyPx
        centerLon = Mercator.lonAt(wx, zoom.toDouble()).coerceIn(-180.0, 180.0)
        centerLat = Mercator.latAt(wy, zoom.toDouble()).coerceIn(-85.0, 85.0)
    }

    // Scale around a screen anchor so the point under the fingers stays put.
    fun zoomBy(factor: Float, anchor: Offset, viewSize: Size) {
        val newZoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (newZoom == zoom) return
        val scaleNow = 2.0.pow(zoom.toDouble())
        val scaleNext = 2.0.pow(newZoom.toDouble())
        val cx = Mercator.worldX(centerLon, zoom.toDouble()) + (anchor.x - viewSize.width / 2f)
        val cy = Mercator.worldY(centerLat, zoom.toDouble()) + (anchor.y - viewSize.height / 2f)
        val nextCx = cx * (scaleNext / scaleNow) - (anchor.x - viewSize.width / 2f)
        val nextCy = cy * (scaleNext / scaleNow) - (anchor.y - viewSize.height / 2f)
        zoom = newZoom
        centerLon = Mercator.lonAt(nextCx, newZoom.toDouble()).coerceIn(-180.0, 180.0)
        centerLat = Mercator.latAt(nextCy, newZoom.toDouble()).coerceIn(-85.0, 85.0)
    }

    fun snapTo(newLat: Double, newLon: Double) {
        centerLat = newLat
        centerLon = newLon
    }
}

// Last tapped coordinate, shared between the map gesture and the probe card.
private val probedPoint = mutableStateOf<Pair<Double, Double>?>(null)

@Composable
fun WeatherMapScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }
    val viewport = remember { MapViewport(50.0, 10.0, 4f) }
    var layer by remember {
        mutableStateOf(
            MapLayer.entries.firstOrNull { it.name.equals(settings.getMapDefaultLayer(), ignoreCase = true) }
                ?: MapLayer.PRECIPITATION,
        )
    }
    var viewSize by remember { mutableStateOf(Size.Zero) }
    var deviceLocation by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    var radar by remember { mutableStateOf<MapTilesSequence?>(null) }
    var satellite by remember { mutableStateOf<MapTilesSequence?>(null) }
    var frameIndex by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(settings.isMapAnimateRadarEnabled()) }
    var mapError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val manual = if (settings.getWeatherLocationMode() == "manual") settings.getWeatherManualLocation() else null
        val resolved = manual?.let { it.first to it.second }
            ?: DeviceLocationSource.current(context)?.let { it.latitude to it.longitude }
            ?: (50.11 to 8.68)
        deviceLocation = resolved
        viewport.snapTo(resolved.first, resolved.second)
        viewport.zoom = 9f
    }

    LaunchedEffect(layer) {
        mapError = null
        if (layer == MapLayer.PRECIPITATION || layer == MapLayer.SATELLITE) {
            try {
                val frames = if (layer == MapLayer.SATELLITE) {
                    WeatherMapRepository.satelliteFrames().also { satellite = it }
                } else {
                    WeatherMapRepository.radarFrames().also { radar = it }
                }
                frameIndex = (frames.frames.size - 1).coerceAtLeast(0)
            } catch (e: Exception) {
                mapError = e.message
            }
        }
    }

    val frameCount = when (layer) {
        MapLayer.PRECIPITATION -> radar?.frames?.size ?: 0
        MapLayer.SATELLITE -> satellite?.frames?.size ?: 0
        MapLayer.TEMPERATURE -> 0
    }
    // Frame playback for radar and satellite loops until the user or a layer change stops it.
    LaunchedEffect(playing, frameCount) {
        if (!playing || frameCount < 2) return@LaunchedEffect
        while (true) {
            delay(400)
            frameIndex = (frameIndex + 1) % frameCount
        }
    }

    probedPoint.value = null

    BackHandler(onBack = onClose)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(viewSize) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    if (viewSize == Size.Zero) return@detectTransformGestures
                    if (zoom != 1f) viewport.zoomBy(zoom, centroid, viewSize)
                    viewport.pan(pan.x, pan.y)
                }
            }
            .pointerInput(layer) {
                detectTapGestures { offset ->
                    val point = screenToLatLon(offset, viewport, viewSize)
                    if (point != null) probedPoint.value = point
                }
            },
    ) {
        MapCanvas(
            viewport = viewport,
            layer = layer,
            viewSize = viewSize,
            onSize = { viewSize = it },
            deviceLocation = deviceLocation,
            radar = radar,
            satellite = satellite,
            frameIndex = frameIndex,
            error = mapError,
        )

        Column(
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 8.dp)
                .padding(horizontal = 12.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MapIconButton(R.drawable.rounded_arrow_back_24, R.string.map_back, onClose)
                Surface(color = Color.Black.copy(alpha = 0.55f), shape = RoundedCornerShape(50)) {
                    Text(
                        stringResource(R.string.map_title),
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                MapIconButton(R.drawable.rounded_my_location_24, R.string.map_my_location) {
                    deviceLocation?.let { viewport.snapTo(it.first, it.second) }
                }
            }
            LayerPicker(
                selected = layer,
                onSelected = { layer = it },
                modifier = Modifier.fillMaxWidth(),
            )
            MapProbe()
            mapError?.let { message ->
                Surface(color = Color.Black.copy(alpha = 0.65f), shape = RoundedCornerShape(16.dp)) {
                    Text(
                        message,
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }

        MapLegend(
            layer = layer,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 16.dp, bottom = 88.dp),
        )

        RadarTimeline(
            frames = when (layer) {
                MapLayer.PRECIPITATION -> radar
                MapLayer.SATELLITE -> satellite
                MapLayer.TEMPERATURE -> null
            },
            frameIndex = frameIndex,
            playing = playing,
            onFrameIndex = { frameIndex = it.coerceAtLeast(0) },
            onPlaying = { playing = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth(),
        )

        Text(
            stringResource(R.string.map_attribution),
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 16.dp, bottom = 64.dp),
        )
    }
}

@Composable
private fun MapProbe() {
    val point by probedPoint
    val latLon = point ?: return
    var temperature by remember(point) { mutableStateOf<Double?>(null) }
    var loading by remember(point) { mutableStateOf(true) }

    LaunchedEffect(point) {
        loading = true
        temperature = WeatherMapRepository.temperatureAt(latLon.first, latLon.second)
        loading = false
    }

    Surface(color = Color.Black.copy(alpha = 0.65f), shape = RoundedCornerShape(24.dp)) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.map_probe_title),
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                when {
                    loading -> "…"
                    temperature != null -> "%.1f°".format(Locale.US, temperature)
                    else -> "—"
                },
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            IconButton(onClick = { probedPoint.value = null }, modifier = Modifier.size(24.dp)) {
                Icon(
                    painterResource(R.drawable.rounded_close_24),
                    stringResource(R.string.map_close),
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun LayerPicker(selected: MapLayer, onSelected: (MapLayer) -> Unit, modifier: Modifier = Modifier) {
    Surface(color = Color.Black.copy(alpha = 0.55f), shape = RoundedCornerShape(50), modifier = modifier) {
        val labels = mapOf(
            MapLayer.PRECIPITATION to stringResource(R.string.map_layer_precipitation),
            MapLayer.TEMPERATURE to stringResource(R.string.map_layer_temperature),
            MapLayer.SATELLITE to stringResource(R.string.map_layer_satellite),
        )
        SegmentedPicker(
            items = MapLayer.entries,
            selectedItem = selected,
            onItemSelected = onSelected,
            labelProvider = { labels[it].orEmpty() },
            iconProvider = {
                when (it) {
                    MapLayer.PRECIPITATION -> Icon(painterResource(R.drawable.rounded_rainy_24), null, Modifier.size(18.dp))
                    MapLayer.TEMPERATURE -> Icon(painterResource(R.drawable.rounded_thermostat_24), null, Modifier.size(18.dp))
                    MapLayer.SATELLITE -> Icon(painterResource(R.drawable.rounded_satellite_24), null, Modifier.size(18.dp))
                }
            },
            containerColor = Color.Transparent,
            contentPadding = PaddingValues(6.dp),
            modifier = Modifier.padding(4.dp),
        )
    }
}

@Composable
private fun MapIconButton(icon: Int, description: Int, onClick: () -> Unit) {
    Surface(color = Color.Black.copy(alpha = 0.55f), shape = CircleShape) {
        IconButton(onClick = onClick) {
            Icon(painterResource(icon), stringResource(description), tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

private val LEGEND_WIDTH = 184.dp

@Composable
private fun MapLegend(layer: MapLayer, modifier: Modifier = Modifier) {
    if (layer == MapLayer.SATELLITE) return
    val gradient = when (layer) {
        MapLayer.PRECIPITATION -> Brush.horizontalGradient(
            listOf(Color.Transparent, Color(0xFF4FC3F7), Color(0xFF7ED491), Color(0xFFFFD166), Color(0xFFFF6B6B)),
        )
        else -> Brush.horizontalGradient(temperatureRamp.map { it.first })
    }
    val start = stringResource(
        if (layer == MapLayer.PRECIPITATION) R.string.map_legend_light else R.string.map_legend_cold,
    )
    val end = stringResource(
        if (layer == MapLayer.PRECIPITATION) R.string.map_legend_heavy else R.string.map_legend_hot,
    )
    Surface(color = Color.Black.copy(alpha = 0.55f), shape = RoundedCornerShape(20.dp), modifier = modifier.width(LEGEND_WIDTH)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(start, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
                Text(end, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .background(gradient, RoundedCornerShape(50)),
            )
        }
    }
}

private val temperatureRamp = listOf(
    Color(0xFF2A0A6B) to -30f,
    Color(0xFF2E6BE6) to -15f,
    Color(0xFF4FC3F7) to -5f,
    Color(0xFF7ED491) to 5f,
    Color(0xFFFFD166) to 15f,
    Color(0xFFFF9E5E) to 25f,
    Color(0xFFFF6B6B) to 35f,
    Color(0xFFB3001B) to 45f,
)

private fun temperatureColor(value: Float): Color {
    val ramp = temperatureRamp
    if (value <= ramp.first().second) return ramp.first().first
    if (value >= ramp.last().second) return ramp.last().first
    for (i in 0 until ramp.size - 1) {
        val (c0, t0) = ramp[i]
        val (c1, t1) = ramp[i + 1]
        if (value in t0..t1) {
            val f = (value - t0) / (t1 - t0)
            return Color(
                red = c0.red + (c1.red - c0.red) * f,
                green = c0.green + (c1.green - c0.green) * f,
                blue = c0.blue + (c1.blue - c0.blue) * f,
            )
        }
    }
    return ramp.last().first
}

private fun screenToLatLon(offset: Offset, viewport: MapViewport, viewSize: Size): Pair<Double, Double>? {
    if (viewSize == Size.Zero) return null
    val zoom = viewport.zoom.toDouble()
    val cx = Mercator.worldX(viewport.centerLon, zoom) + (offset.x - viewSize.width / 2f)
    val cy = Mercator.worldY(viewport.centerLat, zoom) + (offset.y - viewSize.height / 2f)
    return Mercator.latAt(cy, zoom) to Mercator.lonAt(cx, zoom)
}

private fun osmUrl(z: Int, x: Int, y: Int) = "https://tile.openstreetmap.org/$z/$x/$y.png"

private data class TileWindow(val z: Int, val xs: IntRange, val ys: IntRange)

private fun tileWindow(z: Int, centerLat: Double, centerLon: Double, size: Size): TileWindow? {
    if (size == Size.Zero || size.width <= 0f || size.height <= 0f) return null
    val cx = Mercator.worldX(centerLon, z.toDouble())
    val cy = Mercator.worldY(centerLat, z.toDouble())
    val x0 = floor((cx - size.width / 2.0) / 256.0).toInt().coerceIn(0, (1 shl z) - 1)
    val x1 = floor((cx + size.width / 2.0) / 256.0).toInt().coerceIn(0, (1 shl z) - 1)
    val y0 = floor((cy - size.height / 2.0) / 256.0).toInt().coerceIn(0, (1 shl z) - 1)
    val y1 = floor((cy + size.height / 2.0) / 256.0).toInt().coerceIn(0, (1 shl z) - 1)
    // Cap the window so a wild gesture cannot request hundreds of tiles at once.
    return TileWindow(z, x0..min(x1, x0 + 40), y0..min(y1, y0 + 40))
}

@Composable
private fun MapCanvas(
    viewport: MapViewport,
    layer: MapLayer,
    viewSize: Size,
    onSize: (Size) -> Unit,
    deviceLocation: Pair<Double, Double>?,
    radar: MapTilesSequence?,
    satellite: MapTilesSequence?,
    frameIndex: Int,
    error: String?,
) {
    val baseTiles = remember { mutableStateMapOf<String, android.graphics.Bitmap>() }
    val overlayTiles = remember { mutableStateMapOf<String, android.graphics.Bitmap>() }
    var grid by remember { mutableStateOf<TemperatureGrid?>(null) }

    val zoom = viewport.zoom
    val centerLat = viewport.centerLat
    val centerLon = viewport.centerLon
    val zInt = zoom.toInt().coerceIn(0, 16)
    val window = remember(zInt, centerLat, centerLon, viewSize) {
        tileWindow(zInt, centerLat, centerLon, viewSize)
    }

    // Base map tiles for the visible window.
    val baseUrls = remember(window) {
        window?.let { w -> buildList { for (x in w.xs) for (y in w.ys) add(osmUrl(w.z, x, y)) } } ?: emptyList()
    }
    LaunchedEffect(baseUrls) {
        baseUrls.forEach { url ->
            if (!baseTiles.containsKey(url)) MapTileCache.get(url)?.let { baseTiles[url] = it }
        }
    }

    // Radar or satellite tiles for the current frame.
    val activeFrame = when (layer) {
        MapLayer.PRECIPITATION -> radar?.frames?.getOrNull(frameIndex)
        MapLayer.SATELLITE -> satellite?.frames?.getOrNull(frameIndex)
        MapLayer.TEMPERATURE -> null
    }
    val overlayUrls = remember(window, layer, activeFrame) {
        val seq = when (layer) {
            MapLayer.PRECIPITATION -> radar
            MapLayer.SATELLITE -> satellite
            MapLayer.TEMPERATURE -> null
        } ?: return@remember emptyList()
        val frame = activeFrame ?: return@remember emptyList()
        val w = window ?: return@remember emptyList()
        if (seq.isEmpty() || w.z > OVERLAY_MAX_ZOOM) return@remember emptyList()
        buildList {
            for (x in w.xs) for (y in w.ys) {
                add(if (layer == MapLayer.SATELLITE) seq.satelliteUrl(frame, w.z, x, y) else seq.radarUrl(frame, w.z, x, y))
            }
        }
    }
    LaunchedEffect(overlayUrls) {
        overlayUrls.forEach { url ->
            if (!overlayTiles.containsKey(url)) MapTileCache.get(url)?.let { overlayTiles[url] = it }
        }
    }

    // Temperature grid covers a quantized viewport so small pans do not refetch it.
    val gridKey = remember(layer, zInt, centerLat, centerLon, viewSize) {
        if (layer != MapLayer.TEMPERATURE || viewSize == Size.Zero) null
        else "%.1f/%.1f/%d/%d".format(
            Locale.US,
            (centerLat * 4).toLong(),
            (centerLon * 4).toLong(),
            zInt,
            viewSize.width.toInt(),
        )
    }
    LaunchedEffect(gridKey) {
        if (gridKey == null) return@LaunchedEffect
        val z = zoom.toDouble()
        val cx = Mercator.worldX(centerLon, z)
        val cy = Mercator.worldY(centerLat, z)
        val minLon = Mercator.lonAt(cx - viewSize.width / 2.0, z)
        val maxLon = Mercator.lonAt(cx + viewSize.width / 2.0, z)
        val minLat = Mercator.latAt(cy + viewSize.height / 2.0, z)
        val maxLat = Mercator.latAt(cy - viewSize.height / 2.0, z)
        try {
            grid = WeatherMapRepository.temperatureGrid(minLat, maxLat, minLon, maxLon)
        } catch (_: Exception) {
            grid = null
        }
    }

    val pulse = rememberInfiniteTransition(label = "mapPulse")
    val pulsePhase by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Restart),
        label = "mapPulsePhase",
    )

    val density = LocalDensity.current
    val labelTextPx = with(density) { 11.sp.toPx() }
    val labelPaint = remember(labelTextPx) {
        android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = labelTextPx
            isAntiAlias = true
            setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
        }
    }

    Canvas(
        Modifier
            .fillMaxSize()
            .onSizeChanged { onSize(Size(it.width.toFloat(), it.height.toFloat())) },
    ) {
        val scale = 2f.pow(zoom - zInt)
        val cwx = Mercator.worldX(centerLon, zInt.toDouble())
        val cwy = Mercator.worldY(centerLat, zInt.toDouble())
        fun screenX(absX: Double) = ((absX - cwx) * scale + size.width / 2f).toFloat()
        fun screenY(absY: Double) = ((absY - cwy) * scale + size.height / 2f).toFloat()
        fun xOfLon(lon: Double) = screenX(Mercator.worldX(lon, zoom.toDouble()))
        fun yOfLat(lat: Double) = screenY(Mercator.worldY(lat, zoom.toDouble()))

        window?.let { w ->
            val tileSize = (256 * scale).toInt().coerceAtLeast(1)
            for (x in w.xs) for (y in w.ys) {
                val bmp = baseTiles[osmUrl(w.z, x, y)] ?: continue
                drawImage(
                    bmp.asImageBitmap(),
                    dstOffset = IntOffset(screenX(x * 256.0).toInt(), screenY(y * 256.0).toInt()),
                    dstSize = IntSize(tileSize, tileSize),
                )
            }
            if (layer != MapLayer.TEMPERATURE && w.z <= OVERLAY_MAX_ZOOM) {
                val seq = if (layer == MapLayer.PRECIPITATION) radar else satellite
                val frame = activeFrame
                if (seq != null && frame != null) {
                    for (x in w.xs) for (y in w.ys) {
                        val url = if (layer == MapLayer.SATELLITE) seq.satelliteUrl(frame, w.z, x, y)
                        else seq.radarUrl(frame, w.z, x, y)
                        val bmp = overlayTiles[url] ?: continue
                        drawImage(
                            bmp.asImageBitmap(),
                            dstOffset = IntOffset(screenX(x * 256.0).toInt(), screenY(y * 256.0).toInt()),
                            dstSize = IntSize(tileSize, tileSize),
                            alpha = if (layer == MapLayer.PRECIPITATION) 0.85f else 1f,
                        )
                    }
                }
            }
        }

        // Temperature heat field, bilinearly interpolated so it reads as a smooth map.
        if (layer == MapLayer.TEMPERATURE) {
            grid?.let { g ->
                val sub = 6
                clipRect {
                    for (row in 0 until g.rows - 1) {
                        val latTop = g.maxLat - (g.maxLat - g.minLat) * row / (g.rows - 1.0)
                        val latBottom = g.maxLat - (g.maxLat - g.minLat) * (row + 1) / (g.rows - 1.0)
                        for (col in 0 until g.cols - 1) {
                            val lonLeft = g.minLon + (g.maxLon - g.minLon) * col / (g.cols - 1.0)
                            val lonRight = g.minLon + (g.maxLon - g.minLon) * (col + 1) / (g.cols - 1.0)
                            val v00 = g.value(col, row) ?: continue
                            val v10 = g.value(col + 1, row) ?: continue
                            val v01 = g.value(col, row + 1) ?: continue
                            val v11 = g.value(col + 1, row + 1) ?: continue
                            val left = xOfLon(lonLeft)
                            val right = xOfLon(lonRight)
                            val top = yOfLat(latTop)
                            val bottom = yOfLat(latBottom)
                            if (right < 0f || left > size.width || bottom < 0f || top > size.height) continue
                            val stepW = (right - left) / sub
                            val stepH = (bottom - top) / sub
                            if (stepW <= 0f || stepH <= 0f) continue
                            for (sy in 0 until sub) {
                                val fy = (sy + 0.5f) / sub
                                val va = v00 + (v01 - v00) * fy
                                val vb = v10 + (v11 - v10) * fy
                                for (sx in 0 until sub) {
                                    val fx = (sx + 0.5f) / sub
                                    val value = (va + (vb - va) * fx).toFloat()
                                    drawRect(
                                        color = temperatureColor(value).copy(alpha = 0.62f),
                                        topLeft = Offset(left + stepW * sx, top + stepH * sy),
                                        size = Size(stepW + 1f, stepH + 1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Current location marker with a pulsing ring.
        deviceLocation?.let { (lat, lon) ->
            val x = xOfLon(lon)
            val y = yOfLat(lat)
            if (x in -60f..(size.width + 60f) && y in -60f..(size.height + 60f)) {
                val ring = 12f + pulsePhase * 18f
                drawCircle(
                    color = Color(0xFF4FC3F7).copy(alpha = (1f - pulsePhase) * 0.55f),
                    radius = ring,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
                drawCircle(Color.White, radius = 7.dp.toPx(), center = Offset(x, y))
                drawCircle(Color(0xFF1565C0), radius = 4.5.dp.toPx(), center = Offset(x, y))
            }
        }

        activeFrame?.let { frame ->
            val text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(frame.timeMillis))
            drawContext.canvas.nativeCanvas.drawText(text, 16f, size.height - 108f, labelPaint)
        }
    }
}

@Composable
private fun RadarTimeline(
    frames: MapTilesSequence?,
    frameIndex: Int,
    playing: Boolean,
    onFrameIndex: (Int) -> Unit,
    onPlaying: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = frames?.frames?.size ?: 0
    Surface(color = Color.Black.copy(alpha = 0.55f), shape = RoundedCornerShape(24.dp), modifier = modifier) {
        if (frames == null || count == 0) {
            Text(
                stringResource(R.string.map_radar_empty),
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        } else {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        frames.frames.getOrNull(frameIndex)?.let {
                            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.timeMillis))
                        } ?: "",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.weight(1f))
                    FilledTonalButton(
                        onClick = { onPlaying(!playing) },
                        modifier = Modifier.height(30.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                    ) {
                        Text(
                            stringResource(if (playing) R.string.map_pause else R.string.map_play),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                Slider(
                    value = frameIndex.coerceIn(0, (count - 1).coerceAtLeast(0)).toFloat(),
                    onValueChange = {
                        onPlaying(false)
                        onFrameIndex(it.toInt())
                    },
                    valueRange = 0f..(count - 1).coerceAtLeast(0).toFloat(),
                    steps = (count - 2).coerceAtLeast(0),
                )
            }
        }
    }
}
