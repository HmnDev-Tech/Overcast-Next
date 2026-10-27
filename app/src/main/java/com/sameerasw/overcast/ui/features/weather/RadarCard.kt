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
import kotlin.math.roundToInt

@Composable
fun RadarSection(snapshot: WeatherSnapshot, palette: WeatherPalette, modifier: Modifier = Modifier) {
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

    LaunchedEffect(enabled, configVersion, snapshot.updatedAt, snapshot.locationName) {
        if (!enabled) {
            map = null
            return@LaunchedEffect
        }
        map = try {
            RadarRepository.current(context)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    AnimatedVisibility(
        visible = enabled && map != null,
        modifier = modifier,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        val shown = remember { mutableStateOf(map) }
        map?.let { shown.value = it }
        val radar = shown.value ?: return@AnimatedVisibility
        RadarTiles(radar, palette)
    }
}

private const val RADAR_TILES_ACROSS = 1.2f
private const val MAX_RADAR_ZOOM = 4f
private const val DOUBLE_TAP_ZOOM = 2.5f

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

// RainViewer's free palette draws light rain in a pale beige; boosting saturation turns it into a readable amber.
private val RainFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(2.4f) })

@Composable
private fun RadarTiles(radar: RadarMap, palette: WeatherPalette) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val halfSize = remember { floatArrayOf(0f, 0f) }
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
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.4f)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(Color(0xFF0E0E10))
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { tap ->
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
                })
            }
            .transformable(transformState, canPan = { scale > 1.01f }),
    ) {
        halfSize[0] = constraints.maxWidth / 2f
        halfSize[1] = constraints.maxHeight / 2f
        val unitPx = constraints.maxWidth / RADAR_TILES_ACROSS
        val originX = constraints.maxWidth / 2f - unitPx * radar.centerX
        val originY = constraints.maxHeight / 2f - unitPx * radar.centerY
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = pan.x
                    translationY = pan.y
                },
        ) {
            radar.baseTiles.forEach { tile ->
                MapTileImage(tile, unitPx, originX, originY, density, BaseMapFilter, 1f, context)
            }
            radar.overlayTiles.forEach { tile ->
                MapTileImage(tile, unitPx, originX, originY, density, RainFilter, 1f, context)
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
