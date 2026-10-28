package com.sameerasw.overcast.ui.features.weather

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.sameerasw.overcast.R
import kotlinx.coroutines.CancellationException
import com.sameerasw.overcast.weather.extra.HistoryDay
import com.sameerasw.overcast.weather.extra.ExtraRepository
import com.sameerasw.overcast.weather.TemperatureUnit
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import com.sameerasw.overcast.weather.WeatherFormat
import com.sameerasw.overcast.weather.WeatherUnits
import com.sameerasw.overcast.weather.model.WeatherSnapshot
import java.util.Calendar

private const val TREND_HOURS = 24

@Composable
internal fun WeatherCard(
    surfaceKey: String,
    iconRes: Int,
    title: String,
    palette: WeatherPalette,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        color = palette.card,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier.rainSurface(surfaceKey).fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(iconRes), contentDescription = null, tint = palette.accent, modifier = Modifier.size(24.dp))
                Text(title, color = palette.onBase, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                trailing?.let { Text(it, color = palette.onBaseMuted, style = MaterialTheme.typography.labelLarge) }
            }
            content()
        }
    }
}

@Composable
fun TrendSection(snapshot: WeatherSnapshot, units: WeatherUnits, palette: WeatherPalette, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val now by rememberMinuteClock()
    val hours = remember(snapshot, now) { snapshot.upcomingHours(now).take(TREND_HOURS) }
    if (hours.size < 4) return
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = palette.onBaseMuted)
    val valueStyle = MaterialTheme.typography.labelMedium.copy(color = palette.onBase)
    val minTemp = hours.minOf { it.tempC }
    val maxTemp = hours.maxOf { it.tempC }
    WeatherCard("trend", R.drawable.rounded_show_chart_24, stringResource(R.string.trend_title), palette, modifier) {
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val labelBand = 18.dp.toPx()
            val valueBand = 16.dp.toPx()
            val rainBand = 36.dp.toPx()
            val chartTop = valueBand
            val chartBottom = size.height - labelBand - rainBand - 8.dp.toPx()
            val step = size.width / (hours.size - 1)
            val span = (maxTemp - minTemp).coerceAtLeast(1.0)
            fun yFor(temp: Double) = (chartBottom - (temp - minTemp) / span * (chartBottom - chartTop)).toFloat()

            val barWidth = (step * 0.55f).coerceAtMost(10.dp.toPx())
            val rainBottom = size.height - labelBand
            hours.forEachIndexed { index, hour ->
                if (hour.chanceOfRain <= 0) return@forEachIndexed
                val barHeight = rainBand * hour.chanceOfRain / 100f
                drawRoundRect(
                    color = palette.accent.copy(alpha = 0.25f + 0.55f * hour.chanceOfRain / 100f),
                    topLeft = Offset(index * step - barWidth / 2, rainBottom - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 2, barWidth / 2),
                )
            }

            val path = Path()
            hours.forEachIndexed { index, hour ->
                val x = index * step
                val y = yFor(hour.tempC)
                if (index == 0) {
                    path.moveTo(x, y)
                } else {
                    val px = (index - 1) * step
                    val py = yFor(hours[index - 1].tempC)
                    path.cubicTo(px + step / 2, py, x - step / 2, y, x, y)
                }
            }
            drawPath(path, palette.accent, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))

            val extremes = listOf(hours.indexOfFirst { it.tempC == maxTemp }, hours.indexOfFirst { it.tempC == minTemp }).distinct()
            extremes.forEach { index ->
                val x = index * step
                val y = yFor(hours[index].tempC)
                drawCircle(palette.onBase, radius = 4.dp.toPx(), center = Offset(x, y))
                val layout = measurer.measure(WeatherFormat.temperature(hours[index].tempC, units.temperature), valueStyle)
                val above = hours[index].tempC == maxTemp
                drawText(
                    layout,
                    topLeft = Offset(
                        (x - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width),
                        if (above) y - layout.size.height - 4.dp.toPx() else y + 6.dp.toPx(),
                    ),
                )
            }

            hours.forEachIndexed { index, hour ->
                if (index % 6 != 0) return@forEachIndexed
                val layout = measurer.measure(formatHour(context, hour.timeMillis), labelStyle)
                drawText(
                    layout,
                    topLeft = Offset((index * step - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width), size.height - layout.size.height),
                )
            }
        }
    }
}

@Composable
fun HistorySection(snapshot: WeatherSnapshot, units: WeatherUnits, palette: WeatherPalette, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var days by remember { mutableStateOf<List<HistoryDay>?>(null) }
    LaunchedEffect(snapshot.locationName, snapshot.updatedAt / (6 * 60 * 60_000L)) {
        days = try {
            ExtraRepository.recentVsNormal(context)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            days
        }
    }
    val history = days ?: return
    val anomalies = history.mapNotNull { day -> day.normalHighC?.let { day.highC - it } }
    val average = anomalies.takeIf { it.isNotEmpty() }?.average()
    val trailing = average?.let {
        val delta = if (units.temperature == TemperatureUnit.FAHRENHEIT) it * 9 / 5 else it
        "%+.1f°".format(delta)
    }
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = palette.onBaseMuted)
    WeatherCard("history", R.drawable.rounded_history_24, stringResource(R.string.history_title), palette, modifier, trailing) {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val labelBand = 18.dp.toPx()
            val top = 6.dp.toPx()
            val bottom = size.height - labelBand
            val values = history.map { it.highC } + history.mapNotNull { it.normalHighC }
            val low = values.min()
            val span = (values.max() - low).coerceAtLeast(1.0)
            val step = size.width / (history.size - 1).coerceAtLeast(1)
            fun y(temp: Double) = (bottom - (temp - low) / span * (bottom - top)).toFloat()
            fun line(selector: (HistoryDay) -> Double?): Path {
                val path = Path()
                var started = false
                history.forEachIndexed { index, day ->
                    val value = selector(day) ?: return@forEachIndexed
                    if (!started) path.moveTo(index * step, y(value)) else path.lineTo(index * step, y(value))
                    started = true
                }
                return path
            }
            drawPath(
                line { it.normalHighC },
                palette.onBaseMuted.copy(alpha = 0.6f),
                style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))),
            )
            drawPath(line { it.highC }, palette.accent, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
            listOf(0, history.lastIndex).forEach { index ->
                val layout = measurer.measure(dayName(history[index].dayMillis), labelStyle)
                drawText(layout, topLeft = Offset((index * step - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width), size.height - layout.size.height))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Legend(palette.accent, stringResource(R.string.history_actual), palette)
            Legend(palette.onBaseMuted, stringResource(R.string.history_usual), palette)
        }
    }
}

@Composable
private fun Legend(color: Color, label: String, palette: WeatherPalette) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(width = 14.dp, height = 3.dp)) { drawRect(color) }
        Text(label, color = palette.onBaseMuted, style = MaterialTheme.typography.labelMedium)
    }
}

// Hours today where UV reaches 3 or more, which is where sunscreen guidance starts.
internal fun uvProtectionWindow(snapshot: WeatherSnapshot, now: Long): Pair<Long, Long>? {
    val today = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.DAY_OF_YEAR)
    val cal = Calendar.getInstance()
    val strong = snapshot.hourly.filter {
        cal.timeInMillis = it.timeMillis
        cal.get(Calendar.DAY_OF_YEAR) == today && (it.uvIndex ?: 0.0) >= 3.0
    }
    if (strong.isEmpty()) return null
    return strong.first().timeMillis to strong.last().timeMillis + 60 * 60_000L
}
