package com.sameerasw.overcast.ui.features.weather

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sameerasw.overcast.R
import com.sameerasw.overcast.weather.WeatherDetailSlot
import com.sameerasw.overcast.weather.TemperatureUnit
import com.sameerasw.overcast.weather.WeatherFormat
import com.sameerasw.overcast.weather.WeatherUnits
import com.sameerasw.overcast.weather.extra.ExtraRepository
import com.sameerasw.overcast.weather.extra.ForecastConfidence
import com.sameerasw.overcast.weather.extra.InsightData
import com.sameerasw.overcast.weather.extra.MarineData
import com.sameerasw.overcast.weather.extra.RiverData
import com.sameerasw.overcast.weather.model.WeatherSnapshot
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException

@Composable
private fun <T> rememberExtra(snapshot: WeatherSnapshot, load: suspend (Context) -> T?): T? {
    val context = LocalContext.current
    var value by remember { mutableStateOf<T?>(null) }
    LaunchedEffect(snapshot.updatedAt, snapshot.locationName) {
        value = try {
            load(context)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
    return value
}

@Composable
private fun ExtraCard(
    visible: Boolean,
    surfaceKey: String,
    iconRes: Int,
    title: Int,
    palette: WeatherPalette,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Surface(
            color = palette.card,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.rainSurface(surfaceKey).fillMaxWidth(),
        ) {
            Column(Modifier.padding(vertical = 16.dp, horizontal = 8.dp)) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(painterResource(iconRes), contentDescription = null, tint = palette.accent, modifier = Modifier.size(24.dp))
                    Text(stringResource(title), color = palette.onBase, style = MaterialTheme.typography.titleMedium)
                }
                content()
            }
        }
    }
}

@Composable
private fun DetailGrid(details: List<Detail>, palette: WeatherPalette) {
    details.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth()) {
            row.forEach { DetailTile(it, palette, Modifier.weight(1f)) }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
fun MarineSection(snapshot: WeatherSnapshot, units: WeatherUnits, palette: WeatherPalette, modifier: Modifier = Modifier) {
    val loaded = rememberExtra(snapshot) { ExtraRepository.marine(it) }
    val shown = remember { mutableStateOf<MarineData?>(null) }
    loaded?.let { shown.value = it }
    ExtraCard(loaded != null, "marine", R.drawable.rounded_waves_24, R.string.marine_title, palette, modifier) {
        val marine = shown.value ?: return@ExtraCard
        DetailGrid(
            buildList {
                marine.waveHeightM?.let {
                    add(Detail(WeatherDetailSlot.HUMIDITY, R.string.marine_waves, "%.1f m".format(it) + (marine.waveDirectionDeg?.let { d -> " ${compass(d)}" } ?: "")))
                }
                marine.wavePeriodS?.let { add(Detail(WeatherDetailSlot.WIND, R.string.marine_period, "${it.roundToInt()} s")) }
                marine.swellHeightM?.let { add(Detail(WeatherDetailSlot.GUSTS, R.string.marine_swell, "%.1f m".format(it))) }
                marine.seaTemperatureC?.let { add(Detail(WeatherDetailSlot.DEW_POINT, R.string.marine_sea_temperature, WeatherFormat.temperature(it, units.temperature))) }
            },
            palette,
        )
    }
}

@Composable
fun RiverSection(snapshot: WeatherSnapshot, palette: WeatherPalette, modifier: Modifier = Modifier) {
    val loaded = rememberExtra(snapshot) { ExtraRepository.river(it) }
    val shown = remember { mutableStateOf<RiverData?>(null) }
    loaded?.let { shown.value = it }
    ExtraCard(loaded != null, "river", R.drawable.rounded_flood_24, R.string.river_title, palette, modifier) {
        val river = shown.value ?: return@ExtraCard
        DetailGrid(
            listOf(
                Detail(WeatherDetailSlot.HUMIDITY, R.string.river_today, "${river.todayM3s.roundToInt()} m³/s"),
                Detail(WeatherDetailSlot.PRECIPITATION, R.string.river_peak, "${river.peakM3s.roundToInt()} m³/s · ${dayName(river.peakDayMillis)}"),
            ),
            palette,
        )
    }
}

@Composable
fun InsightsSection(snapshot: WeatherSnapshot, units: WeatherUnits, palette: WeatherPalette, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val loaded = rememberExtra(snapshot) { ExtraRepository.insights(it) }
    val shown = remember { mutableStateOf<InsightData?>(null) }
    loaded?.let { shown.value = it }
    ExtraCard(loaded != null, "insights", R.drawable.rounded_insights_24, R.string.insights_title, palette, modifier) {
        val insight = shown.value ?: return@ExtraCard
        DetailGrid(
            buildList {
                insight.normalHighC?.let { normal ->
                    val diff = snapshot.todayHighC() - normal
                    val delta = if (units.temperature == TemperatureUnit.FAHRENHEIT) abs(diff) * 9 / 5 else abs(diff)
                    val amount = "${delta.roundToInt()}°"
                    val text = when {
                        abs(diff) < 1 -> context.getString(R.string.insight_normal)
                        diff > 0 -> context.getString(R.string.insight_warmer, amount)
                        else -> context.getString(R.string.insight_cooler, amount)
                    }
                    add(Detail(WeatherDetailSlot.DEW_POINT, R.string.insight_vs_usual, text))
                }
                insight.confidence?.let {
                    val label = when (it) {
                        ForecastConfidence.HIGH -> R.string.pollen_level_high
                        ForecastConfidence.MEDIUM -> R.string.aqi_moderate
                        ForecastConfidence.LOW -> R.string.pollen_level_low
                    }
                    add(Detail(WeatherDetailSlot.CLOUD_COVER, R.string.insight_confidence, context.getString(label)))
                }
            },
            palette,
        )
    }
}
