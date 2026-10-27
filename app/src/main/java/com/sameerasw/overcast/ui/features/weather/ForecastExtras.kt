package com.sameerasw.overcast.ui.features.weather

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sameerasw.overcast.R
import com.sameerasw.overcast.weather.Nowcast
import com.sameerasw.overcast.weather.Nowcast.RAIN_THRESHOLD_MM
import com.sameerasw.overcast.weather.ForecastChangeTracker
import com.sameerasw.overcast.weather.ForecastChange
import com.sameerasw.overcast.utils.HapticUtil
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.IconButton
import com.sameerasw.overcast.ui.core.sheets.OvercastBottomSheet
import com.sameerasw.overcast.weather.WeatherDetailSlot
import com.sameerasw.overcast.weather.WeatherFormat
import com.sameerasw.overcast.weather.WeatherUnits
import com.sameerasw.overcast.weather.model.DailyForecast
import com.sameerasw.overcast.weather.model.HourlyForecast
import com.sameerasw.overcast.weather.model.MinutelyPrecipitation
import com.sameerasw.overcast.weather.model.WeatherSnapshot
import kotlin.math.roundToInt
import kotlin.math.sqrt


enum class StormRisk { LOW, MODERATE, HIGH, VERY_HIGH }

// CAPE bands are the usual convective thresholds; any lightning potential bumps the risk to at least moderate.
internal fun stormRisk(cape: Double?, lightning: Double?): StormRisk? {
    if (cape == null && lightning == null) return null
    val fromCape = when {
        cape == null || cape < 300 -> StormRisk.LOW
        cape < 1000 -> StormRisk.MODERATE
        cape < 2500 -> StormRisk.HIGH
        else -> StormRisk.VERY_HIGH
    }
    return if ((lightning ?: 0.0) > 0.0 && fromCape == StormRisk.LOW) StormRisk.MODERATE else fromCape
}

internal fun stormRiskLabel(risk: StormRisk): Int = when (risk) {
    StormRisk.LOW -> R.string.pollen_level_low
    StormRisk.MODERATE -> R.string.aqi_moderate
    StormRisk.HIGH -> R.string.pollen_level_high
    StormRisk.VERY_HIGH -> R.string.pollen_level_very_high
}

internal fun formatDuration(context: Context, seconds: Double): String {
    val minutes = (seconds / 60).roundToInt()
    return context.getString(R.string.duration_hours_minutes, minutes / 60, minutes % 60)
}


@Composable
fun NowcastBanner(snapshot: WeatherSnapshot, palette: WeatherPalette, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val now by rememberMinuteClock()
    val slots = snapshot.extras?.minutely.orEmpty().filter { it.timeMillis + 15 * 60_000L > now }
    val message = remember(slots, now) { Nowcast.message(context, slots, now) } ?: return
    Surface(
        color = palette.card,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier.rainSurface("nowcast").fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(R.drawable.rounded_rainy_24), contentDescription = null, tint = palette.accent, modifier = Modifier.size(24.dp))
                Text(message, color = palette.onBase, style = MaterialTheme.typography.titleMedium)
            }
            Canvas(Modifier.fillMaxWidth().height(56.dp)) {
                val stroke = 3.dp.toPx()
                val top = stroke
                val bottom = size.height - stroke / 2
                // Square-root scale so light drizzle still reads; about 4 mm per 15 minutes fills the chart.
                fun level(mm: Double) = if (mm < RAIN_THRESHOLD_MM) 0f else sqrt((mm / 4.0).coerceAtMost(1.0)).toFloat().coerceAtLeast(0.12f)
                val points = slots.mapIndexed { index, slot ->
                    Offset(
                        if (slots.size == 1) 0f else size.width * index / (slots.size - 1),
                        bottom - (bottom - top) * level(slot.mm),
                    )
                }
                val line = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    for (index in 1 until points.size) {
                        val previous = points[index - 1]
                        val point = points[index]
                        val midX = (previous.x + point.x) / 2
                        cubicTo(midX, previous.y, midX, point.y, point.x, point.y)
                    }
                }
                val area = Path().apply {
                    addPath(line)
                    lineTo(points.last().x, size.height)
                    lineTo(points.first().x, size.height)
                    close()
                }
                drawLine(palette.onBaseMuted.copy(alpha = 0.25f), Offset(0f, bottom), Offset(size.width, bottom), strokeWidth = 1.dp.toPx())
                drawPath(area, Brush.verticalGradient(listOf(palette.accent.copy(alpha = 0.45f), palette.accent.copy(alpha = 0f))))
                drawPath(line, palette.accent, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawCircle(palette.accent, radius = 4.5.dp.toPx(), center = points.first())
            }
            Row(Modifier.fillMaxWidth()) {
                listOf(R.string.nowcast_now, R.string.nowcast_hour, R.string.nowcast_two_hours).forEachIndexed { index, label ->
                    Text(
                        stringResource(label),
                        color = palette.onBaseMuted,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = when (index) {
                            0 -> TextAlign.Start
                            1 -> TextAlign.Center
                            else -> TextAlign.End
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
fun ChangesBanner(units: WeatherUnits, palette: WeatherPalette, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    val latest by ForecastChangeTracker.latest.collectAsState()
    val now by rememberMinuteClock()
    val changes = latest?.takeIf { now - it.detectedAt < ForecastChangeTracker.SHOW_FOR_MS } ?: return
    fun time(millis: Long?) = millis?.let { formatHour(context, it) }
    val lines = changes.changes.map { change ->
        when (change) {
            is ForecastChange.RainTiming -> when {
                change.currentMillis == null -> context.getString(R.string.change_rain_cleared)
                change.previousMillis == null -> context.getString(R.string.change_rain_new, time(change.currentMillis))
                else -> context.getString(R.string.change_rain_moved, time(change.currentMillis), time(change.previousMillis))
            }
            is ForecastChange.TodayHigh -> context.getString(
                R.string.change_high,
                WeatherFormat.temperature(change.currentC, units.temperature),
                WeatherFormat.temperature(change.previousC, units.temperature),
            )
        }
    }
    Surface(
        color = palette.card,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier.rainSurface("changes").fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 20.dp, top = 16.dp, bottom = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.rounded_history_24), contentDescription = null, tint = palette.accent, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.change_title), color = palette.onBaseMuted, style = MaterialTheme.typography.labelMedium)
                lines.forEach { Text(it, color = palette.onBase, style = MaterialTheme.typography.bodyMedium) }
            }
            IconButton(onClick = {
                HapticUtil.performVirtualKeyHaptic(view)
                ForecastChangeTracker.dismiss()
            }) {
                Icon(painterResource(R.drawable.rounded_close_24), contentDescription = null, tint = palette.onBaseMuted, modifier = Modifier.size(20.dp))
            }
        }
    }
}

internal fun hourDetails(context: Context, hour: HourlyForecast, units: WeatherUnits): List<Detail> = buildList {
    hour.feelsLikeC?.let { add(Detail(WeatherDetailSlot.TEMPERATURE, R.string.weather_detail_feels_like, WeatherFormat.temperature(it, units.temperature))) }
    add(Detail(WeatherDetailSlot.RAIN_CHANCE, R.string.weather_detail_rain_chance, "${hour.chanceOfRain}%"))
    hour.precipitationMm?.let { add(Detail(WeatherDetailSlot.PRECIPITATION, R.string.weather_detail_precipitation, WeatherFormat.precipitation(it, units.precipitation))) }
    hour.snowfallCm?.takeIf { it > 0 }?.let { add(Detail(WeatherDetailSlot.PRECIPITATION, R.string.weather_detail_snowfall, "%.1f cm".format(it))) }
    hour.humidity?.let { add(Detail(WeatherDetailSlot.HUMIDITY, R.string.weather_detail_humidity, "$it%")) }
    hour.windKph?.let {
        add(Detail(WeatherDetailSlot.WIND, R.string.weather_detail_wind, WeatherFormat.wind(it, units.wind) + (hour.windDirectionDeg?.let { d -> " ${compass(d)}" } ?: "")))
    }
    hour.windGustKph?.let { add(Detail(WeatherDetailSlot.GUSTS, R.string.weather_detail_gusts, WeatherFormat.wind(it, units.wind))) }
    hour.uvIndex?.let { add(Detail(WeatherDetailSlot.UV, R.string.weather_detail_uv, it.roundToInt().toString())) }
    hour.cloudCover?.let { add(Detail(WeatherDetailSlot.CLOUD_COVER, R.string.weather_detail_cloud_cover, "$it%")) }
    stormRisk(hour.cape, hour.lightningPotential)?.let { add(Detail(WeatherDetailSlot.RAIN_CHANCE, R.string.weather_detail_storm_risk, context.getString(stormRiskLabel(it)))) }
}

internal fun dayDetails(context: Context, day: DailyForecast, units: WeatherUnits): List<Detail> = buildList {
    add(Detail(WeatherDetailSlot.RAIN_CHANCE, R.string.weather_detail_rain_chance, "${day.chanceOfRain}%"))
    day.precipitationMm?.let { add(Detail(WeatherDetailSlot.PRECIPITATION, R.string.weather_detail_precipitation, WeatherFormat.precipitation(it, units.precipitation))) }
    day.precipitationHours?.takeIf { it > 0 }?.let { add(Detail(WeatherDetailSlot.PRECIPITATION, R.string.weather_detail_rain_hours, "${it.roundToInt()} h")) }
    day.snowfallCm?.takeIf { it > 0 }?.let { add(Detail(WeatherDetailSlot.PRECIPITATION, R.string.weather_detail_snowfall, "%.1f cm".format(it))) }
    day.sunshineSeconds?.let { add(Detail(WeatherDetailSlot.UV, R.string.weather_detail_sunshine, formatDuration(context, it))) }
    day.daylightSeconds?.let { add(Detail(WeatherDetailSlot.UV, R.string.weather_detail_daylight, formatDuration(context, it))) }
    day.windMaxKph?.let {
        add(Detail(WeatherDetailSlot.WIND, R.string.weather_detail_wind, WeatherFormat.wind(it, units.wind) + (day.windDirectionDeg?.let { d -> " ${compass(d)}" } ?: "")))
    }
    day.windGustMaxKph?.let { add(Detail(WeatherDetailSlot.GUSTS, R.string.weather_detail_gusts, WeatherFormat.wind(it, units.wind))) }
    day.uvIndex?.let { add(Detail(WeatherDetailSlot.UV, R.string.weather_detail_uv, it.roundToInt().toString())) }
    day.sunriseMillis?.let { add(Detail(WeatherDetailSlot.UV, R.string.weather_detail_sunrise, formatHour(context, it))) }
    day.sunsetMillis?.let { add(Detail(WeatherDetailSlot.UV, R.string.weather_detail_sunset, formatHour(context, it))) }
}

@Composable
internal fun ForecastDetailSheet(title: String, details: List<Detail>, palette: WeatherPalette, onDismiss: () -> Unit) {
    OvercastBottomSheet(onDismissRequest = onDismiss, containerColor = palette.card.compositeOver(palette.base)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text(
                title,
                color = palette.onBase,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            )
            details.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { DetailTile(it, palette, Modifier.weight(1f)) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
