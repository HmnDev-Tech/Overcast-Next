package com.sameerasw.overcast.ui.features.weather

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sameerasw.overcast.R
import com.sameerasw.overcast.data.repository.SettingsRepository
import com.sameerasw.overcast.utils.HapticUtil
import com.sameerasw.overcast.weather.aqi.AqiCategory
import com.sameerasw.overcast.weather.aqi.AqiHour
import com.sameerasw.overcast.weather.aqi.AqiProviders
import com.sameerasw.overcast.weather.aqi.AqiRepository
import com.sameerasw.overcast.weather.aqi.PollenLevel
import com.sameerasw.overcast.weather.aqi.PollenType
import com.sameerasw.overcast.weather.model.WeatherSnapshot
import kotlinx.coroutines.CancellationException

private val BADGE_SIZE = 112.dp
private const val AQI_SCALE_MAX = 300f

@Composable
fun AqiSection(snapshot: WeatherSnapshot, palette: WeatherPalette, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    var data by remember { mutableStateOf(AqiRepository.cached()) }
    var loading by remember { mutableStateOf(false) }
    var forceNext by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }
    val settings = remember { SettingsRepository(context) }
    var enabled by remember { mutableStateOf(settings.getAqiProvider() != AqiProviders.OFF_ID) }
    DisposableEffect(context) {
        val prefs = context.getSharedPreferences(SettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == SettingsRepository.KEY_AQI_PROVIDER) enabled = settings.getAqiProvider() != AqiProviders.OFF_ID
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    LaunchedEffect(enabled, snapshot.updatedAt, snapshot.locationName, refreshTick) {
        if (!enabled) return@LaunchedEffect
        val force = forceNext
        forceNext = false
        loading = true
        failed = false
        try {
            data = AqiRepository.current(context, force)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }

    AnimatedVisibility(
        visible = enabled,
        modifier = modifier,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Surface(
            color = palette.card,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.rainSurface("aqi").fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .animateContentSize(spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(
                        painterResource(R.drawable.rounded_air_24),
                        contentDescription = null,
                        tint = palette.accent,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        stringResource(R.string.aqi_title),
                        color = palette.onBase,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                        if (loading) {
                            LoadingIndicator(Modifier.size(32.dp))
                        } else {
                            IconButton(
                                onClick = {
                                    HapticUtil.performVirtualKeyHaptic(view)
                                    forceNext = true
                                    refreshTick++
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    painterResource(R.drawable.rounded_refresh_24),
                                    contentDescription = stringResource(R.string.weather_refresh),
                                    tint = palette.onBaseMuted,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
                data?.let { aqi ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.width(BADGE_SIZE),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AqiBadge(aqi.aqi, aqiColor(aqi.category))
                            Text(
                                aqiLabel(aqi.category),
                                color = aqiColor(aqi.category),
                                style = MaterialTheme.typography.titleSmall,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            aqi.pm25?.let { Pollutant("PM2.5", it, palette) }
                            aqi.pm10?.let { Pollutant("PM10", it, palette) }
                            aqi.ozone?.let { Pollutant("O₃", it, palette) }
                            aqi.no2?.let { Pollutant("NO₂", it, palette) }
                            aqi.so2?.let { Pollutant("SO₂", it, palette) }
                            aqi.co?.let { Pollutant("CO", it, palette) }
                            aqi.dust?.takeIf { it > 0 }?.let { Pollutant(stringResource(R.string.aqi_dust), it, palette) }
                            aqi.euAqi?.let { Pollutant(stringResource(R.string.aqi_european), it.toDouble(), palette, unit = "") }
                        }
                    }
                    if (aqi.forecast.size > 1) AqiForecastStrip(aqi.forecast, palette)
                    if (aqi.pollen.isNotEmpty()) PollenSection(aqi.pollen, palette)
                }
                if (failed && data == null) {
                    Text(
                        stringResource(R.string.aqi_error),
                        color = palette.onBaseMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun AqiBadge(aqi: Int?, color: Color) {
    val progress by animateFloatAsState(
        targetValue = ((aqi ?: 0) / AQI_SCALE_MAX).coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "aqiProgress",
    )
    val shape = MaterialShapes.Cookie9Sided.toShape()
    val textMeasurer = rememberTextMeasurer()
    val textStyle = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold)
    val text = aqi?.toString() ?: "—"
    Box(
        Modifier
            .size(BADGE_SIZE)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val outline = shape.createOutline(size, layoutDirection, this)
                val layout = textMeasurer.measure(text, textStyle)
                val textOffset = Offset(
                    (size.width - layout.size.width) / 2f,
                    (size.height - layout.size.height) / 2f,
                )
                onDrawBehind {
                    val fillTop = size.height * (1f - progress)
                    drawOutline(outline, color.copy(alpha = 0.22f))
                    clipRect(bottom = fillTop) {
                        drawText(layout, color = color, topLeft = textOffset)
                    }
                    clipRect(top = fillTop) {
                        drawOutline(outline, color)
                        drawText(layout, color = Color.Black, topLeft = textOffset, blendMode = BlendMode.Clear)
                    }
                }
            },
    )
}

@Composable
private fun AqiForecastStrip(forecast: List<AqiHour>, palette: WeatherPalette) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().height(64.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        forecast.forEachIndexed { index, hour ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                val fraction = (hour.aqi / AQI_SCALE_MAX).coerceIn(0.08f, 1f)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(40.dp * fraction)
                        .clip(CircleShape)
                        .background(aqiColor(AqiCategory.from(hour.aqi))),
                )
                Text(
                    if (index % 3 == 0) formatHour(context, hour.timeMillis) else "",
                    color = palette.onBaseMuted,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun PollenSection(pollen: Map<PollenType, Double>, palette: WeatherPalette) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.pollen_title),
            color = palette.onBaseMuted,
            style = MaterialTheme.typography.titleSmall,
        )
        pollen.entries.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                pair.forEach { (type, grains) ->
                    val level = PollenLevel.from(grains)
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(pollenColor(level)),
                        )
                        Text(
                            stringResource(pollenName(type)),
                            color = palette.onBase,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .weight(1f),
                        )
                        Text(
                            stringResource(pollenLabel(level)),
                            color = pollenColor(level),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

private fun pollenName(type: PollenType): Int = when (type) {
    PollenType.ALDER -> R.string.pollen_alder
    PollenType.BIRCH -> R.string.pollen_birch
    PollenType.GRASS -> R.string.pollen_grass
    PollenType.MUGWORT -> R.string.pollen_mugwort
    PollenType.OLIVE -> R.string.pollen_olive
    PollenType.RAGWEED -> R.string.pollen_ragweed
}

private fun pollenLabel(level: PollenLevel): Int = when (level) {
    PollenLevel.NONE -> R.string.pollen_level_none
    PollenLevel.LOW -> R.string.pollen_level_low
    PollenLevel.MODERATE -> R.string.aqi_moderate
    PollenLevel.HIGH -> R.string.pollen_level_high
    PollenLevel.VERY_HIGH -> R.string.pollen_level_very_high
}

private fun pollenColor(level: PollenLevel): Color = when (level) {
    PollenLevel.NONE -> Color(0xFF9AA0A6)
    PollenLevel.LOW -> Color(0xFF7ED491)
    PollenLevel.MODERATE -> Color(0xFFFFD166)
    PollenLevel.HIGH -> Color(0xFFFF9E5E)
    PollenLevel.VERY_HIGH -> Color(0xFFFF6B6B)
}

@Composable
private fun Pollutant(name: String, value: Double, palette: WeatherPalette, unit: String = " µg/m³") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            name,
            color = palette.onBaseMuted,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (value < 10) "%.1f".format(value) else value.toInt().toString(),
            color = palette.onBase,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            unit,
            color = palette.onBaseMuted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun aqiLabel(category: AqiCategory): String = stringResource(
    when (category) {
        AqiCategory.GOOD -> R.string.aqi_good
        AqiCategory.MODERATE -> R.string.aqi_moderate
        AqiCategory.SENSITIVE -> R.string.aqi_sensitive
        AqiCategory.UNHEALTHY -> R.string.aqi_unhealthy
        AqiCategory.VERY_UNHEALTHY -> R.string.aqi_very_unhealthy
        AqiCategory.HAZARDOUS -> R.string.aqi_hazardous
        AqiCategory.UNKNOWN -> R.string.condition_unknown
    },
)

private fun aqiColor(category: AqiCategory): Color = when (category) {
    AqiCategory.GOOD -> Color(0xFF7ED491)
    AqiCategory.MODERATE -> Color(0xFFFFD166)
    AqiCategory.SENSITIVE -> Color(0xFFFF9E5E)
    AqiCategory.UNHEALTHY -> Color(0xFFFF6B6B)
    AqiCategory.VERY_UNHEALTHY -> Color(0xFFC77DFF)
    AqiCategory.HAZARDOUS -> Color(0xFFD9466A)
    AqiCategory.UNKNOWN -> Color.White
}
