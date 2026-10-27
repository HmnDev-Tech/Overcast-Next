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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sameerasw.overcast.R
import com.sameerasw.overcast.ui.core.sheets.OvercastBottomSheet
import com.sameerasw.overcast.weather.WeatherDetailSlot
import com.sameerasw.overcast.weather.WeatherFormat
import com.sameerasw.overcast.weather.WeatherUnits
import com.sameerasw.overcast.weather.model.DailyForecast
import com.sameerasw.overcast.weather.model.HourlyForecast
import com.sameerasw.overcast.weather.model.MinutelyPrecipitation
import com.sameerasw.overcast.weather.model.WeatherSnapshot
import kotlin.math.max
import kotlin.math.roundToInt

private const val RAIN_THRESHOLD_MM = 0.1

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

private fun nowcastMessage(context: Context, slots: List<MinutelyPrecipitation>, now: Long): String? {
    if (slots.isEmpty()) return null
    fun minutesUntil(slot: MinutelyPrecipitation) = max(0L, (slot.timeMillis - now) / 60_000L).toInt()
    val rainingNow = slots.first().mm >= RAIN_THRESHOLD_MM
    return if (rainingNow) {
        val stop = slots.firstOrNull { it.mm < RAIN_THRESHOLD_MM }
        if (stop == null) context.getString(R.string.nowcast_rain_continuing) else context.getString(R.string.nowcast_rain_stopping, max(5, minutesUntil(stop)))
    } else {
        slots.firstOrNull { it.mm >= RAIN_THRESHOLD_MM }?.let { context.getString(R.string.nowcast_rain_starting, max(5, minutesUntil(it))) }
    }
}

@Composable
fun NowcastBanner(snapshot: WeatherSnapshot, palette: WeatherPalette, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val now by rememberMinuteClock()
    val slots = snapshot.extras?.minutely.orEmpty().filter { it.timeMillis + 15 * 60_000L > now }
    val message = remember(slots, now) { nowcastMessage(context, slots, now) } ?: return
    val peak = slots.maxOf { it.mm }.coerceAtLeast(1.0)
    Surface(
        color = palette.card,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier.rainSurface("nowcast").fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(R.drawable.rounded_rainy_24), contentDescription = null, tint = palette.accent, modifier = Modifier.size(24.dp))
                Text(message, color = palette.onBase, style = MaterialTheme.typography.titleMedium)
            }
            Canvas(Modifier.fillMaxWidth().height(36.dp)) {
                val gap = 6.dp.toPx()
                val barWidth = (size.width - gap * (slots.size - 1)) / slots.size
                slots.forEachIndexed { index, slot ->
                    val fraction = (slot.mm / peak).toFloat().coerceIn(0f, 1f)
                    val barHeight = max(3.dp.toPx(), size.height * fraction)
                    drawRoundRect(
                        color = if (slot.mm >= RAIN_THRESHOLD_MM) palette.accent else palette.onBaseMuted.copy(alpha = 0.25f),
                        topLeft = Offset(index * (barWidth + gap), size.height - barHeight),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(barWidth / 2, barWidth / 2),
                    )
                }
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
