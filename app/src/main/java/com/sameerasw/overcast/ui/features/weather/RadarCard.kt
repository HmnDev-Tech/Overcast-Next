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
    }

    AnimatedVisibility(
        visible = map != null,
        modifier = modifier,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        val shown = remember { mutableStateOf(map) }
        map?.let { shown.value = it }
        val radar = shown.value ?: return@AnimatedVisibility
        RadarTiles(radar, snapshot, units, palette)
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

// RainViewer's free palette draws light rain in a pale beige; boosting saturation turns it into a readable amber.
private val RainFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(2.4f) })

@Composable
private fun RadarTiles(radar: RadarMap, snapshot: WeatherSnapshot, units: WeatherUnits, palette: WeatherPalette) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val view = LocalView.current
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val halfSize = remember { floatArrayOf(0f, 0f) }
    var frameIndex by remember(radar) { mutableIntStateOf(radar.frames.lastIndex) }
    var playing by remember { mutableStateOf(true) }
    var compassOn by remember { mutableStateOf(false) }
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
    LaunchedEffect(radar, playing) {
        if (!playing || radar.frames.size < 2) return@LaunchedEffect
        while (true) {
            delay(if (frameIndex == radar.frames.lastIndex) LOOP_HOLD_MS else LOOP_FRAME_MS)
            frameIndex = (frameIndex + 1) % radar.frames.size
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
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.4f)
            .rainSurface("radar")
            .clip(MaterialTheme.shapes.extraLarge)
            .background(Color(0xFF0E0E10))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        HapticUtil.performVirtualKeyHaptic(view)
                        compassOn = !compassOn
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
        val unitPx = constraints.maxWidth / RADAR_TILES_ACROSS
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
            radar.baseTiles.forEach { tile ->
                MapTileImage(tile, unitPx, originX, originY, density, BaseMapFilter, 1f, context)
            }
            radar.frames.forEachIndexed { index, frame ->
                // Every frame stays composed so Coil has them all loaded; only the current one is opaque.
                val frameAlpha = if (index == frameIndex.coerceAtMost(radar.frames.lastIndex)) 1f else 0f
                frame.tiles.forEach { tile ->
                    MapTileImage(tile, unitPx, originX, originY, density, RainFilter, frameAlpha, context)
                }
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
        if (direction != null && snapshot.windKph > 0.5) {
            val windAlpha by animateFloatAsState(if (scale <= 1.01f) 1f else 0f, label = "windAlpha")
            if (windAlpha > 0f) {
                WindParticles(direction, snapshot.windKph, { rotation }, Modifier.matchParentSize().graphicsLayer { alpha = windAlpha })
            }
        }
        AnimatedVisibility(
            visible = scale <= 1.01f,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
        ) {
            WindStats(snapshot, units, direction)
        }
        val frameTime = radar.frames.getOrNull(frameIndex)?.timeMillis
        if (radar.frames.size > 1 && frameTime != null) {
            Surface(
                onClick = {
                    HapticUtil.performVirtualKeyHaptic(view)
                    playing = !playing
                },
                color = Color.Black.copy(alpha = 0.55f),
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        painterResource(if (playing) R.drawable.rounded_pause_24 else R.drawable.rounded_play_arrow_24),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(formatHour(context, frameTime), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

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
private fun WindStats(snapshot: WeatherSnapshot, units: WeatherUnits, direction: Double?) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
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
