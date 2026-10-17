/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: UI Feature - Weather
 * File: AllergenCard.kt
 * Description: Allergen (pollen) section with an animated hourly chart.
 */

package com.sameerasw.overcast.ui.features.weather

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sameerasw.overcast.R
import com.sameerasw.overcast.data.repository.SettingsRepository
import com.sameerasw.overcast.ui.core.pickers.SegmentedPicker
import com.sameerasw.overcast.weather.allergen.AllergenData
import com.sameerasw.overcast.weather.allergen.AllergenException
import com.sameerasw.overcast.weather.allergen.AllergenLevel
import com.sameerasw.overcast.weather.allergen.AllergenRepository
import com.sameerasw.overcast.weather.allergen.AllergenSeries
import com.sameerasw.overcast.weather.allergen.AllergenType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private const val CHART_HOURS = 48

@Composable
fun AllergenSection(palette: WeatherPalette, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }
    if (!settings.isAllergenCardEnabled()) return
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<AllergenData?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(AllergenType.BIRCH) }

    fun load() {
        scope.launch {
            loading = true
            error = null
            try {
                val fresh = AllergenRepository.load(context)
                data = fresh
                if (fresh.of(selected)?.points?.isNotEmpty() != true) {
                    // Land on the pollen that is actually present instead of an empty chart.
                    selected = fresh.series.filter { it.points.isNotEmpty() }
                        .maxByOrNull { s -> s.points.maxOf { it.value } }?.type ?: selected
                }
            } catch (e: AllergenException) {
                error = e.message
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    Surface(
        color = palette.card,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    painterResource(R.drawable.rounded_eco_24),
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    stringResource(R.string.allergen_title),
                    color = palette.onBase,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { load() }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        painterResource(R.drawable.rounded_refresh_24),
                        contentDescription = stringResource(R.string.weather_refresh),
                        tint = palette.onBaseMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            when {
                loading -> LoadingIndicator(Modifier.size(48.dp))
                error != null -> Text(
                    error ?: stringResource(R.string.weather_error_unknown),
                    color = palette.onBaseMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                data != null -> {
                    val current = data!!
                    val series = current.of(selected)
                    if (series == null || series.points.isEmpty()) {
                        Text(
                            stringResource(R.string.allergen_empty),
                            color = palette.onBaseMuted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        val now = System.currentTimeMillis()
                        val live = series.valueAt(now)
                        val level = AllergenLevel.from(live)
                        val levelColor = levelColor(level)
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                live?.let { value -> if (value < 10) "%.1f".format(value) else value.toInt().toString() } ?: "—",
                                color = palette.onBase,
                                style = MaterialTheme.typography.displaySmall,
                            )
                            Column(Modifier.padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
            stringResource(R.string.allergen_unit),
                                    color = palette.onBaseMuted,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Surface(color = levelColor.copy(alpha = 0.18f), shape = RoundedCornerShape(50)) {
                                    Text(
                                        allergenLevelLabel(level),
                                        color = levelColor,
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                    )
                                }
                            }
                        }
                        val typeLabels = mapOf(
                            AllergenType.ALDER to stringResource(R.string.allergen_alder),
                            AllergenType.BIRCH to stringResource(R.string.allergen_birch),
                            AllergenType.GRASS to stringResource(R.string.allergen_grass),
                            AllergenType.MUGWORT to stringResource(R.string.allergen_mugwort),
                            AllergenType.RAGWEED to stringResource(R.string.allergen_ragweed),
                        )
                        SegmentedPicker(
                            items = AllergenType.entries,
                            selectedItem = selected,
                            onItemSelected = { selected = it },
                            labelProvider = { typeLabels[it].orEmpty() },
                            containerColor = palette.card,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        AllergenChart(
                            series = series,
                            now = now,
                            palette = palette,
                            accent = palette.accent,
                            modifier = Modifier.fillMaxWidth().height(156.dp),
                        )
                        val peak = series.peak(now)
                        if (peak != null && peak.value > 0) {
                            Text(
                                stringResource(
                                    R.string.allergen_peak,
                                    peak.value.toInt().toString(),
                                    formatChartTime(peak.timeMillis),
                                ),
                                color = palette.onBase,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        Text(
                            stringResource(R.string.allergen_source),
                            color = palette.onBaseMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AllergenChart(
    series: AllergenSeries,
    now: Long,
    palette: WeatherPalette,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val nowIndex = remember(series, now) {
        series.points.indexOfFirst { it.timeMillis >= now }.coerceAtLeast(0)
    }
    val points = remember(series, nowIndex) {
        series.points.drop(nowIndex).take(CHART_HOURS + 1).ifEmpty { series.points.takeLast(CHART_HOURS + 1) }
    }
    var revealed by remember(series, nowIndex) { mutableStateOf(false) }
    val progress by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow),
        label = "allergenChart",
    )
    LaunchedEffect(series, nowIndex) { revealed = true }
    val gridColor = palette.onBase.copy(alpha = 0.12f)
    val labelTextPx = with(LocalDensity.current) { 10.sp.toPx() }
    val labelPaint = remember(palette, labelTextPx) {
        android.graphics.Paint().apply {
            color = palette.onBaseMuted.toArgb()
            textSize = labelTextPx
            isAntiAlias = true
        }
    }

    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val labelSpace = 18.dp.toPx()
        val chartHeight = size.height - labelSpace
        val maxV = points.maxOf { it.value }.coerceAtLeast(1.0)
        val stepX = size.width / (points.size - 1)
        val yOf = { v: Double -> chartHeight - (v / maxV * (chartHeight - 8.dp.toPx())).toFloat() }

        for (g in 0..2) {
            val y = chartHeight * g / 2f
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }

        val visible = (progress * (points.size - 1)).coerceIn(0f, (points.size - 1).toFloat())
        val lastFull = visible.toInt()
        val line = Path()
        for (i in 0..lastFull) {
            val x = i * stepX
            val y = yOf(points[i].value)
            if (i == 0) {
                line.moveTo(x, y)
            } else {
                line.lineTo(x, y)
            }
        }
        val fraction = visible - lastFull
        if (fraction > 0f && lastFull < points.size - 1) {
            val x0 = lastFull * stepX
            val x1 = (lastFull + 1) * stepX
            val y0 = yOf(points[lastFull].value)
            val y1 = yOf(points[lastFull + 1].value)
            val x = x0 + (x1 - x0) * fraction
            val y = y0 + (y1 - y0) * fraction
            line.lineTo(x, y)
        }
        // Close the filled area along the bottom of the chart.
        val filled = Path().apply {
            addPath(line)
            val endX = if (fraction > 0f && lastFull < points.size - 1) {
                lastFull * stepX + stepX * fraction
            } else {
                lastFull * stepX
            }
            lineTo(endX, chartHeight)
            lineTo(0f, chartHeight)
            close()
        }
        drawPath(
            filled,
            brush = Brush.verticalGradient(
                colors = listOf(accent.copy(alpha = 0.35f), accent.copy(alpha = 0.02f)),
                startY = 0f,
                endY = chartHeight,
            ),
        )
        drawPath(line, color = accent, style = Stroke(width = 2.5.dp.toPx()))

        val peakIndex = points.indices.maxByOrNull { points[it].value } ?: 0
        if (progress > 0.95f && points[peakIndex].value > 0) {
            val px = peakIndex * stepX
            val py = yOf(points[peakIndex].value)
            drawCircle(color = accent, radius = 4.dp.toPx(), center = Offset(px, py))
            drawCircle(color = Color.White.copy(alpha = 0.85f), radius = 2.dp.toPx(), center = Offset(px, py))
        }

        // Hour labels: Now plus quarter points across the window.
        val labelIndexes = listOf(0, (points.size - 1) / 4, (points.size - 1) / 2, (points.size - 1) * 3 / 4, points.size - 1)
        labelIndexes.distinct().forEach { i ->
            val x = i * stepX
            val text = if (i == 0) "Now" else formatChartTime(points[i].timeMillis)
            val w = labelPaint.measureText(text)
            val clamped = x - w / 2f
            drawContext.canvas.nativeCanvas.drawText(
                text,
                clamped.coerceIn(0f, (size.width - w).coerceAtLeast(0f)),
                size.height - 4.dp.toPx(),
                labelPaint,
            )
        }
    }
}

private fun formatChartTime(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

@Composable
private fun allergenTypeLabel(type: AllergenType): String = stringResource(
    when (type) {
        AllergenType.ALDER -> R.string.allergen_alder
        AllergenType.BIRCH -> R.string.allergen_birch
        AllergenType.GRASS -> R.string.allergen_grass
        AllergenType.MUGWORT -> R.string.allergen_mugwort
        AllergenType.RAGWEED -> R.string.allergen_ragweed
    },
)

@Composable
private fun allergenLevelLabel(level: AllergenLevel): String = stringResource(
    when (level) {
        AllergenLevel.NONE -> R.string.allergen_level_none
        AllergenLevel.LOW -> R.string.allergen_level_low
        AllergenLevel.MODERATE -> R.string.allergen_level_moderate
        AllergenLevel.HIGH -> R.string.allergen_level_high
        AllergenLevel.VERY_HIGH -> R.string.allergen_level_very_high
    },
)

private fun levelColor(level: AllergenLevel): Color = when (level) {
    AllergenLevel.NONE -> Color(0xFF8AB4F8)
    AllergenLevel.LOW -> Color(0xFF7ED491)
    AllergenLevel.MODERATE -> Color(0xFFFFD166)
    AllergenLevel.HIGH -> Color(0xFFFF9E5E)
    AllergenLevel.VERY_HIGH -> Color(0xFFFF6B6B)
}
