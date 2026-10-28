package com.sameerasw.overcast.ui.features.weather

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalView
import com.sameerasw.overcast.R
import com.sameerasw.overcast.utils.HapticUtil
import kotlinx.coroutines.delay
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size as CoilSize
import com.sameerasw.overcast.weather.radar.MapTile
import com.sameerasw.overcast.weather.provider.ProviderHttp
import com.sameerasw.overcast.data.repository.SettingsRepository
import com.sameerasw.overcast.weather.model.WeatherSnapshot
import com.sameerasw.overcast.weather.radar.RadarMap
import com.sameerasw.overcast.weather.radar.RadarProviders
import com.sameerasw.overcast.weather.radar.RadarRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import android.graphics.Bitmap
import android.text.format.DateUtils
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import com.sameerasw.overcast.weather.radar.ForecastGrid
import com.sameerasw.overcast.weather.radar.ForecastGridRepository
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.util.lerp
import com.sameerasw.overcast.ui.core.containers.RoundedCardContainer
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.SideEffect
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.abs
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.core.animateFloatAsState
import kotlin.random.Random
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import com.sameerasw.overcast.weather.WeatherFormat
import com.sameerasw.overcast.weather.WeatherUnits

@Composable
fun RadarSection(snapshot: WeatherSnapshot, units: WeatherUnits, palette: WeatherPalette, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }
    var providerId by remember { mutableStateOf(settings.getRadarProvider()) }
    var configVersion by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val prefs = context.getSharedPreferences(SettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == SettingsRepository.KEY_RADAR_PROVIDER || key?.startsWith(SettingsRepository.KEY_WEATHER_API_KEY) == true) {
                providerId = settings.getRadarProvider()
                configVersion++
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val enabled = RadarProviders.resolve(providerId) != RadarProviders.OFF_ID
    var map by remember { mutableStateOf<RadarMap?>(null) }
    var grid by remember { mutableStateOf<ForecastGrid?>(null) }

    LaunchedEffect(enabled, configVersion, snapshot.updatedAt, snapshot.locationName) {
        map = try {
            if (enabled) RadarRepository.current(context) else RadarRepository.baseMap(context)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            try {
                RadarRepository.baseMap(context)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        grid = map?.let { loaded ->
            try {
                ForecastGridRepository.load(loaded)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }

    val host = LocalMapFullscreenHost.current
    val scope = rememberCoroutineScope()
    val ui = remember { MapUiState() }
    val progress = remember { Animatable(0f) }
    var fullscreen by remember { mutableStateOf(false) }
    val cardBounds = remember { mutableStateOf(Rect.Zero) }
    val shownMap = remember { mutableStateOf<RadarMap?>(null) }
    map?.let { shownMap.value = it }
    val latestGrid by rememberUpdatedState(grid)
    val latestSnapshot by rememberUpdatedState(snapshot)
    val latestUnits by rememberUpdatedState(units)
    val latestPalette by rememberUpdatedState(palette)
    val mapView = remember {
        movableContentOf { contentModifier: Modifier, expanded: Boolean ->
            shownMap.value?.let { MapView(it, latestGrid, latestSnapshot, latestUnits, latestPalette, ui, contentModifier, expanded) }
        }
    }

    fun close() {
        scope.launch {
            progress.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
            fullscreen = false
            host?.content = null
        }
    }

    fun open() {
        if (host == null) return
        fullscreen = true
        host.content = {
            FullscreenMap(
                bounds = { cardBounds.value },
                progress = { progress.value },
                palette = latestPalette,
                onExit = { close() },
            ) { mapView(Modifier.fillMaxSize(), true) }
        }
        scope.launch { progress.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) }
    }

    PredictiveBackHandler(enabled = fullscreen) { events ->
        try {
            events.collect { event -> progress.snapTo(1f - event.progress * BACK_SHRINK) }
            progress.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
            fullscreen = false
            host?.content = null
        } catch (e: CancellationException) {
            scope.launch { progress.animateTo(1f) }
            throw e
        }
    }
    DisposableEffect(host) {
        onDispose { if (fullscreen) host?.content = null }
    }

    AnimatedVisibility(
        visible = map != null,
        modifier = modifier,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        val radar = shownMap.value ?: return@AnimatedVisibility
        val view = LocalView.current
        RoundedCardContainer(Modifier.rainSurface("radar"), spacing = 2.dp, cornerRadius = CARD_BOTTOM_CORNER) {
            Surface(color = palette.card, shape = MaterialTheme.shapes.extraSmall, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ModeCarousel(availableLayers(radar, grid), ui.layer, palette, Modifier.weight(1f)) {
                        HapticUtil.performVirtualKeyHaptic(view)
                        ui.layer = it
                    }
                    Surface(
                        onClick = {
                            HapticUtil.performVirtualKeyHaptic(view)
                            open()
                        },
                        color = Color.Black.copy(alpha = 0.32f),
                        contentColor = palette.onBase,
                        shape = MaterialTheme.shapes.extraLarge,
                        modifier = Modifier.padding(vertical = 6.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.rounded_fullscreen_24),
                            contentDescription = null,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).size(24.dp),
                        )
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.4f)
                    .clip(RoundedCornerShape(CARD_TOP_CORNER))
                    .background(MAP_BACKGROUND)
                    .onGloballyPositioned { cardBounds.value = it.boundsInRoot() },
            ) {
                if (!fullscreen) mapView(Modifier.fillMaxSize(), false)
            }
            AnimatedVisibility(visible = ui.layer.playable && ui.frameCount > 1) {
                TimelineSlider(ui, palette)
            }
        }
    }
}

internal class MapFullscreenHost {
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)
    val active: Boolean get() = content != null
}

internal val LocalMapFullscreenHost = staticCompositionLocalOf<MapFullscreenHost?> { null }

private class MapUiState {
    var layer by mutableStateOf(MapLayer.WIND)
    var frameIndex by mutableIntStateOf(0)
    var frameCount by mutableIntStateOf(0)
    var playing by mutableStateOf(true)
    var unitPx = 0f
}

private const val BACK_SHRINK = 0.3f
private val MAP_BACKGROUND = Color(0xFF0E0E10)

@Composable
private fun FullscreenMap(
    bounds: () -> Rect,
    progress: () -> Float,
    palette: WeatherPalette,
    onExit: () -> Unit,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val origin = remember { floatArrayOf(0f, 0f) }
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                val position = it.positionInRoot()
                origin[0] = position.x
                origin[1] = position.y
            },
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = progress().coerceIn(0f, 1f) }.background(Color.Black.copy(alpha = 0.6f)))
        Box(
            Modifier
                .layout { measurable, constraints ->
                    val p = progress().coerceIn(0f, 1f)
                    val from = bounds()
                    val left = lerp(from.left - origin[0], 0f, p)
                    val top = lerp(from.top - origin[1], 0f, p)
                    val width = lerp(from.width, constraints.maxWidth.toFloat(), p).roundToInt().coerceAtLeast(1)
                    val height = lerp(from.height, constraints.maxHeight.toFloat(), p).roundToInt().coerceAtLeast(1)
                    val placeable = measurable.measure(Constraints.fixed(width, height))
                    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(left.roundToInt(), top.roundToInt()) }
                }
                .graphicsLayer {
                    val p = progress().coerceIn(0f, 1f)
                    val top = lerp(CARD_TOP_CORNER.toPx(), 0f, p)
                    val bottom = lerp(CARD_BOTTOM_CORNER.toPx(), 0f, p)
                    shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
                    clip = true
                }
                .background(MAP_BACKGROUND),
        ) {
            content()
            Surface(
                onClick = {
                    HapticUtil.performVirtualKeyHaptic(view)
                    onExit()
                },
                color = Color.Black.copy(alpha = 0.55f),
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(12.dp)
                    .graphicsLayer { alpha = progress().coerceIn(0f, 1f) },
            ) {
                Icon(painterResource(R.drawable.rounded_fullscreen_exit_24), contentDescription = null, modifier = Modifier.padding(10.dp).size(24.dp))
            }
        }
    }
}

private val CARD_TOP_CORNER = 4.dp
private val CARD_BOTTOM_CORNER = 28.dp

@Composable
private fun TimelineSlider(ui: MapUiState, palette: WeatherPalette) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val position = remember { Animatable(ui.frameIndex.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(ui.frameIndex, dragging) {
        if (dragging) return@LaunchedEffect
        val target = ui.frameIndex.toFloat()
        if (abs(target - position.value) > 1.5f) {
            position.snapTo(target)
        } else {
            position.animateTo(target, tween(SLIDER_STEP_MS, easing = FastOutSlowInEasing))
        }
    }
    Surface(color = palette.card, shape = MaterialTheme.shapes.extraSmall, modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = position.value.coerceIn(0f, (ui.frameCount - 1).coerceAtLeast(1).toFloat()),
            onValueChange = { value ->
                dragging = true
                ui.playing = false
                scope.launch { position.snapTo(value) }
                val next = value.roundToInt()
                if (next != ui.frameIndex) {
                    HapticUtil.performSliderHaptic(view)
                    ui.frameIndex = next
                }
            },
            onValueChangeFinished = { dragging = false },
            valueRange = 0f..(ui.frameCount - 1).coerceAtLeast(1).toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = palette.accent,
                activeTrackColor = palette.accent,
                inactiveTrackColor = palette.onBaseMuted.copy(alpha = 0.25f),
            ),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

private const val SLIDER_STEP_MS = 450

@Composable
private fun ModeCarousel(
    layers: List<MapLayer>,
    selected: MapLayer,
    palette: WeatherPalette,
    modifier: Modifier,
    onSelect: (MapLayer) -> Unit,
) {
    val state = rememberCarouselState { layers.size }
    HorizontalMultiBrowseCarousel(
        state = state,
        preferredItemWidth = 128.dp,
        itemSpacing = 6.dp,
        minSmallItemWidth = 44.dp,
        maxSmallItemWidth = 56.dp,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        modifier = modifier.height(56.dp),
    ) { index ->
        val option = layers[index]
        val active = option == selected
        Surface(
            color = if (active) palette.accent else Color.Black.copy(alpha = 0.32f),
            contentColor = if (active) Color.Black else palette.onBase,
            modifier = Modifier
                .fillMaxSize()
                .maskClip(MaterialTheme.shapes.extraLarge)
                .clickable { if (!active) onSelect(option) },
        ) {
            Row(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = carouselItemDrawInfo.maskRect.left }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(painterResource(option.iconRes), contentDescription = null, modifier = Modifier.size(20.dp))
                Text(stringResource(option.labelRes), style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
            }
        }
    }
}

private const val RADAR_TILES_ACROSS = 1.2f
private const val MAX_RADAR_ZOOM = 4f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val LOOP_FRAME_MS = 600L
private const val LOOP_HOLD_MS = 1800L

// Inverts the light OSM tiles and dims them so the map sits under the dark UI.
private val BaseMapFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -0.6f, 0f, 0f, 0f, 160f,
            0f, -0.6f, 0f, 0f, 160f,
            0f, 0f, -0.55f, 0f, 165f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

private val SatelliteFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(0.8f, 0.8f, 0.8f, 1f) })

// RainViewer's free palette draws light rain in a pale beige; boosting saturation turns it into a readable amber.
private val RainFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(2.4f) })

@Composable
private fun MapView(
    radar: RadarMap,
    grid: ForecastGrid?,
    snapshot: WeatherSnapshot,
    units: WeatherUnits,
    palette: WeatherPalette,
    ui: MapUiState,
    modifier: Modifier,
    expanded: Boolean,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val view = LocalView.current
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val halfSize = remember { floatArrayOf(0f, 0f) }
    val layers = remember(radar, grid) { availableLayers(radar, grid) }
    val layer = if (ui.layer in layers) ui.layer else MapLayer.WIND
    val frames = remember(radar, grid, layer) { framesFor(layer, radar, grid) }
    val nowIndex = frames.indexOfLast { it.radarIndex >= 0 }.coerceAtLeast(0)
    LaunchedEffect(frames) {
        ui.frameCount = frames.size
        ui.frameIndex = 0
        ui.playing = true
    }
    val frameIndex = ui.frameIndex.coerceIn(0, (frames.size - 1).coerceAtLeast(0))
    val frame = frames.getOrNull(frameIndex)
    var compassOn by remember { mutableStateOf(false) }
    LaunchedEffect(layer) {
        if (layer.playable) compassOn = false
    }
    val heading by rememberCompassHeading(compassOn)
    var rotation by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(compassOn) {
        if (!compassOn) return@LaunchedEffect
        var facingNorth = false
        snapshotFlow { heading }.collect { h ->
            val off = abs(((h + 540f) % 360f) - 180f)
            if (!facingNorth && off < 4f) {
                facingNorth = true
                HapticUtil.performStrongTickHaptic(view.context)
            } else if (facingNorth && off > 12f) {
                facingNorth = false
            }
        }
    }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos {
            val target = if (compassOn) -heading else 0f
            val delta = ((target - rotation + 540f) % 360f) - 180f
            rotation = if (abs(delta) < 0.05f) target else rotation + delta * 0.12f
        }
    }
    LaunchedEffect(frames, ui.playing) {
        if (!ui.playing || frames.size < 2) return@LaunchedEffect
        while (true) {
            val current = ui.frameIndex
            delay(if (current == frames.lastIndex || (current == nowIndex && nowIndex > 0)) LOOP_HOLD_MS else LOOP_FRAME_MS)
            ui.frameIndex = (current + 1) % frames.size
        }
    }
    val scope = rememberCoroutineScope()
    var zoomJob by remember { mutableStateOf<Job?>(null) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        zoomJob?.cancel()
        scale = (scale * zoomChange).coerceIn(1f, MAX_RADAR_ZOOM)
        val limitX = halfSize[0] * (scale - 1f)
        val limitY = halfSize[1] * (scale - 1f)
        val next = pan + panChange
        pan = Offset(next.x.coerceIn(-limitX, limitX), next.y.coerceIn(-limitY, limitY))
    }
    BoxWithConstraints(
        modifier
            .background(MAP_BACKGROUND)
            .pointerInput(layer) {
                detectTapGestures(
                    onTap = {
                        HapticUtil.performVirtualKeyHaptic(view)
                        if (layer.playable) ui.playing = !ui.playing else compassOn = !compassOn
                    },
                    onDoubleTap = { tap ->
                        val fromScale = scale
                        val fromPan = pan
                        val toScale = if (fromScale > 1.01f) 1f else DOUBLE_TAP_ZOOM
                        val center = Offset(halfSize[0], halfSize[1])
                        val point = (tap - center - fromPan) / fromScale
                        val limitX = halfSize[0] * (toScale - 1f)
                        val limitY = halfSize[1] * (toScale - 1f)
                        val target = tap - center - point * toScale
                        val toPan = Offset(target.x.coerceIn(-limitX, limitX), target.y.coerceIn(-limitY, limitY))
                        zoomJob?.cancel()
                        zoomJob = scope.launch {
                            animate(0f, 1f, animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) { t, _ ->
                                scale = fromScale + (toScale - fromScale) * t
                                pan = fromPan + (toPan - fromPan) * t
                            }
                        }
                    },
                )
            }
            .transformable(transformState, canPan = { scale > 1.01f }),
    ) {
        halfSize[0] = constraints.maxWidth / 2f
        halfSize[1] = constraints.maxHeight / 2f
        val cardUnitPx = constraints.maxWidth / RADAR_TILES_ACROSS
        val unitPx = if (expanded && ui.unitPx > 0f) ui.unitPx else cardUnitPx
        SideEffect { if (!expanded) ui.unitPx = cardUnitPx }
        val originX = constraints.maxWidth / 2f - unitPx * radar.centerX
        val originY = constraints.maxHeight / 2f - unitPx * radar.centerY
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    rotationZ = rotation
                    translationX = pan.x
                    translationY = pan.y
                },
        ) {
            if (layer == MapLayer.SATELLITE) {
                radar.satelliteTiles.forEach { tile ->
                    if (expanded || tile.y + tile.size > 0f && tile.y < radar.gridTiles) {
                        MapTileImage(tile, unitPx, originX, originY, density, SatelliteFilter, 1f, context)
                    }
                }
            } else {
                radar.baseTiles.forEach { tile ->
                    if (expanded || tile.y + tile.size > 0f && tile.y < radar.gridTiles) {
                        MapTileImage(tile, unitPx, originX, originY, density, BaseMapFilter, 1f, context)
                    }
                }
            }
            if (layer == MapLayer.RAIN) {
                radar.frames.forEachIndexed { index, radarFrame ->
                    // Every frame stays composed so Coil has them all loaded; only the current one is opaque.
                    val frameAlpha = if (index == frame?.radarIndex) 1f else 0f
                    radarFrame.tiles.forEach { tile ->
                        MapTileImage(tile, unitPx, originX, originY, density, RainFilter, frameAlpha, context)
                    }
                }
            }
            if (grid != null && frame != null && frame.forecastIndex >= 0) {
                GridOverlay(grid, layer, frame.forecastIndex, unitPx, originX, originY, radar.gridTiles)
            }
        }
        Box(
            Modifier
                .align(Alignment.Center)
                .graphicsLayer {
                    translationX = pan.x
                    translationY = pan.y
                }
                .size(12.dp)
                .clip(CircleShape)
                .background(palette.accent)
                .border(2.dp, Color.White, CircleShape),
        )
        val direction = snapshot.extras?.windDirectionDeg
        val windAlpha by animateFloatAsState(if (layer.showsWind) 1f else 0f, label = "windAlpha")
        if (windAlpha > 0f) {
            if (direction != null && snapshot.windKph > 0.5) {
                WindParticles(direction, snapshot.windKph, { rotation }, Modifier.matchParentSize().graphicsLayer { alpha = windAlpha })
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .graphicsLayer { alpha = windAlpha },
            ) {
                WindStats(snapshot, units, direction, expanded)
            }
        }
        if (frame != null) {
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .then(if (expanded) Modifier.statusBarsPadding() else Modifier)
                    .padding(12.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (!ui.playing && frames.size > 1) {
                        Icon(painterResource(R.drawable.rounded_pause_24), contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Text(frameLabel(context, frame, layer, grid, radar, units), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

private enum class MapLayer(val labelRes: Int, val iconRes: Int, val playable: Boolean, val showsWind: Boolean) {
    WIND(R.string.weather_detail_wind, R.drawable.rounded_air_24, false, true),
    RAIN(R.string.map_layer_rain, R.drawable.rounded_rainy_24, true, false),
    TEMPERATURE(R.string.map_layer_temperature, R.drawable.rounded_thermostat_24, true, false),
    CLOUDS(R.string.map_layer_clouds, R.drawable.rounded_cloud_24, true, false),
    SATELLITE(R.string.map_layer_satellite, R.drawable.rounded_globe_24, false, true),
}

private class MapFrame(val timeMillis: Long, val radarIndex: Int = -1, val forecastIndex: Int = -1)

private fun availableLayers(radar: RadarMap, grid: ForecastGrid?): List<MapLayer> = buildList {
    add(MapLayer.WIND)
    if (radar.frames.isNotEmpty() || grid != null) add(MapLayer.RAIN)
    if (grid != null) {
        add(MapLayer.TEMPERATURE)
        add(MapLayer.CLOUDS)
    }
    if (radar.satelliteTiles.isNotEmpty()) add(MapLayer.SATELLITE)
}

private fun framesFor(layer: MapLayer, radar: RadarMap, grid: ForecastGrid?): List<MapFrame> {
    val now = System.currentTimeMillis()
    return when (layer) {
        MapLayer.RAIN -> {
            val past = radar.frames.mapIndexed { i, f -> MapFrame(f.timeMillis ?: now, radarIndex = i) }
            val after = past.lastOrNull()?.timeMillis ?: (now - HOUR_MS)
            past + grid?.times.orEmpty().mapIndexedNotNull { i, t -> if (t > after) MapFrame(t, forecastIndex = i) else null }
        }
        MapLayer.TEMPERATURE, MapLayer.CLOUDS -> grid?.times.orEmpty().mapIndexed { i, t -> MapFrame(t, forecastIndex = i) }
        MapLayer.WIND, MapLayer.SATELLITE -> emptyList()
    }
}

private fun frameLabel(context: Context, frame: MapFrame, layer: MapLayer, grid: ForecastGrid?, radar: RadarMap, units: WeatherUnits): String {
    val sameDay = DateUtils.isToday(frame.timeMillis)
    val time = (if (sameDay) "" else dayName(frame.timeMillis) + " ") + formatHour(context, frame.timeMillis)
    if (grid == null || frame.forecastIndex < 0) return time
    val value = sampleAt(grid, layerValues(grid, layer, frame.forecastIndex) ?: return time, radar.centerX, radar.centerY)
    val reading = when (layer) {
        MapLayer.TEMPERATURE -> WeatherFormat.temperature(value.toDouble(), units.temperature)
        MapLayer.CLOUDS -> "${value.roundToInt()}%"
        MapLayer.RAIN -> WeatherFormat.precipitation(value.toDouble(), units.precipitation)
        MapLayer.WIND, MapLayer.SATELLITE -> return time
    }
    return "$time · $reading"
}

private fun layerValues(grid: ForecastGrid, layer: MapLayer, index: Int): FloatArray? = when (layer) {
    MapLayer.RAIN -> grid.rainMm.getOrNull(index)
    MapLayer.TEMPERATURE -> grid.temperatureC.getOrNull(index)
    MapLayer.CLOUDS -> grid.cloudCover.getOrNull(index)
    MapLayer.WIND, MapLayer.SATELLITE -> null
}

private fun sampleAt(grid: ForecastGrid, values: FloatArray, x: Float, y: Float): Float {
    val gx = (x / grid.step).coerceIn(0f, grid.cols - 1f)
    val gy = (y / grid.step).coerceIn(0f, grid.rows - 1f)
    val c0 = gx.toInt().coerceAtMost(grid.cols - 2)
    val r0 = gy.toInt().coerceAtMost(grid.rows - 2)
    val fx = gx - c0
    val fy = gy - r0
    val top = values[r0 * grid.cols + c0] * (1 - fx) + values[r0 * grid.cols + c0 + 1] * fx
    val bottom = values[(r0 + 1) * grid.cols + c0] * (1 - fx) + values[(r0 + 1) * grid.cols + c0 + 1] * fx
    return top * (1 - fy) + bottom * fy
}

@Composable
private fun GridOverlay(grid: ForecastGrid, layer: MapLayer, index: Int, unitPx: Float, originX: Float, originY: Float, gridTiles: Int) {
    val image = remember(grid, layer, index) {
        val values = layerValues(grid, layer, index) ?: return@remember null
        val w = (grid.cols - 1) * GRID_RES + 1
        val h = (grid.rows - 1) * GRID_RES + 1
        val pixels = IntArray(w * h)
        for (py in 0 until h) for (px in 0 until w) {
            val v = sampleAt(grid, values, px.toFloat() / GRID_RES * grid.step, py.toFloat() / GRID_RES * grid.step)
            pixels[py * w + px] = when (layer) {
                MapLayer.TEMPERATURE -> rampColor(TemperatureRamp, v, 0.5f)
                MapLayer.CLOUDS -> argb((v / 100f).coerceIn(0f, 1f).let { it * it } * 0.75f, 0xFFFFFF)
                MapLayer.RAIN -> if (v < 0.05f) 0 else rampColor(RainRamp, v, (0.35f + v * 0.12f).coerceAtMost(0.8f))
                MapLayer.WIND, MapLayer.SATELLITE -> 0
            }
        }
        Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
    } ?: return
    Canvas(Modifier.fillMaxSize()) {
        val size = (gridTiles * unitPx).roundToInt()
        drawImage(
            image,
            dstOffset = IntOffset(originX.roundToInt(), originY.roundToInt()),
            dstSize = IntSize(size, size),
            filterQuality = FilterQuality.Low,
        )
    }
}

private const val GRID_RES = 12
private const val HOUR_MS = 60 * 60_000L

private val TemperatureRamp = listOf(
    -20f to 0x6A4BC4,
    -5f to 0x3D7DD8,
    5f to 0x39B8C9,
    15f to 0x6CC26A,
    22f to 0xF2D046,
    30f to 0xF08A3A,
    38f to 0xD63B3B,
    45f to 0x9E1F4F,
)

private val RainRamp = listOf(
    0.1f to 0x4FC3F7,
    0.5f to 0x29B6F6,
    1f to 0x66BB6A,
    3f to 0xFFEE58,
    6f to 0xFFA726,
    12f to 0xEF5350,
)

private fun rampColor(ramp: List<Pair<Float, Int>>, value: Float, alpha: Float): Int {
    val upper = ramp.indexOfFirst { it.first >= value }
    val rgb = when {
        upper <= 0 -> if (upper == 0) ramp.first().second else ramp.last().second
        else -> {
            val (v0, c0) = ramp[upper - 1]
            val (v1, c1) = ramp[upper]
            val t = (value - v0) / (v1 - v0)
            fun channel(shift: Int) = (((c0 shr shift) and 0xFF) * (1 - t) + ((c1 shr shift) and 0xFF) * t).roundToInt()
            (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
    }
    return argb(alpha, rgb)
}

private fun argb(alpha: Float, rgb: Int): Int = ((alpha.coerceIn(0f, 1f) * 255).roundToInt() shl 24) or (rgb and 0xFFFFFF)

@Composable
private fun rememberCompassHeading(active: Boolean): State<Float> {
    val context = LocalContext.current
    val heading = remember { mutableFloatStateOf(0f) }
    DisposableEffect(active) {
        if (!active) return@DisposableEffect onDispose {}
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return@DisposableEffect onDispose {}
        val matrix = FloatArray(9)
        val orientation = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                SensorManager.getOrientation(matrix, orientation)
                heading.floatValue = (Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { manager.unregisterListener(listener) }
    }
    return heading
}

@Composable
private fun WindStats(snapshot: WeatherSnapshot, units: WeatherUnits, direction: Double?, expanded: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
            .then(if (expanded) Modifier.navigationBarsPadding() else Modifier)
            .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 16.dp),
    ) {
        WindStat(stringResource(R.string.weather_detail_direction), direction?.let { compass(it) } ?: "–", Modifier.weight(1f))
        WindStat(stringResource(R.string.weather_detail_wind), WeatherFormat.wind(snapshot.windKph, units.wind), Modifier.weight(1f))
        WindStat(stringResource(R.string.weather_detail_gusts), snapshot.extras?.windGustKph?.let { WeatherFormat.wind(it, units.wind) } ?: "–", Modifier.weight(1f))
    }
}

@Composable
private fun WindStat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
        Text(value, color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

private const val WIND_PARTICLES = 24

@Composable
private fun WindParticles(directionDeg: Double, windKph: Double, rotation: () -> Float, modifier: Modifier) {
    val seeds = remember { Random(7).let { r -> FloatArray(WIND_PARTICLES * 3) { r.nextFloat() } } }
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { time = (it - start) / 1_000_000_000f }
    }
    // Meteorological direction is where the wind comes from; particles travel the other way.
    val speed = (windKph.toFloat() / 30f).coerceIn(0.25f, 2.5f)
    Canvas(modifier) {
        val heading = Math.toRadians(directionDeg + 180.0 + rotation())
        val dx = sin(heading).toFloat()
        val dy = -cos(heading).toFloat()
        val radius = size.minDimension * 0.4f
        val span = radius * 2f
        val travel = radius * 0.8f
        val streak = 18.dp.toPx() + 10.dp.toPx() * speed.coerceAtMost(1.5f)
        val stroke = 5.dp.toPx()
        for (i in 0 until WIND_PARTICLES) {
            val phase = (time * speed * 0.45f + seeds[i * 3 + 2]) % 1f
            val u = (seeds[i * 3] * span + dx * travel * phase).mod(span) - radius
            val v = (seeds[i * 3 + 1] * span + dy * travel * phase).mod(span) - radius
            val falloff = (1f - sqrt(u * u + v * v) / radius).coerceIn(0f, 1f)
            if (falloff <= 0f) continue
            val alpha = sin(phase * PI.toFloat()) * 0.8f * falloff
            val head = Offset(center.x + u, center.y + v)
            val tail = Offset(head.x - dx * streak, head.y - dy * streak)
            drawLine(
                Brush.linearGradient(listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = alpha)), start = tail, end = head),
                tail,
                head,
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun MapTileImage(
    tile: MapTile,
    unitPx: Float,
    originX: Float,
    originY: Float,
    density: Density,
    colorFilter: ColorFilter?,
    alpha: Float,
    context: Context,
) {
    // Snap to whole pixels and overlap by one so neighbouring tiles never leave hairline seams.
    val left = (originX + tile.x * unitPx).roundToInt()
    val top = (originY + tile.y * unitPx).roundToInt()
    val right = (originX + (tile.x + tile.size) * unitPx).roundToInt() + 1
    val bottom = (originY + (tile.y + tile.size) * unitPx).roundToInt() + 1
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(tile.url)
            .addHeader("User-Agent", ProviderHttp.USER_AGENT)
            .size(CoilSize.ORIGINAL)
            .build(),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        colorFilter = colorFilter,
        alpha = alpha,
        filterQuality = FilterQuality.High,
        modifier = Modifier
            .offset { IntOffset(left, top) }
            .size(with(density) { (right - left).toDp() }, with(density) { (bottom - top).toDp() }),
    )
}
