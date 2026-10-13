package com.sameerasw.overcast.weather.island

import android.content.Context
import android.graphics.Bitmap
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sameerasw.island.sdk.HostInfo
import com.sameerasw.island.sdk.compose.islandSnapshot
import com.sameerasw.overcast.R
import com.sameerasw.overcast.data.repository.SettingsRepository
import com.sameerasw.overcast.ui.components.WeatherIcon
import com.sameerasw.overcast.weather.WeatherFormat
import com.sameerasw.overcast.weather.WeatherIconStyle
import com.sameerasw.overcast.weather.WeatherRepository
import com.sameerasw.overcast.weather.WeatherUnits
import com.sameerasw.overcast.weather.effects.DeviceWeatherHaptics
import com.sameerasw.overcast.weather.effects.WeatherEffectSpec
import com.sameerasw.overcast.weather.effects.WeatherEffects
import com.sameerasw.overcast.weather.model.WeatherAlert
import com.sameerasw.overcast.weather.model.WeatherError
import com.sameerasw.overcast.weather.model.WeatherSnapshot
import com.sameerasw.overcast.ui.features.weather.providerLabel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val body = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
private val title = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WeatherIslandExpanded(
    host: HostInfo?,
    onOpenApp: () -> Unit,
    onKeepAlive: () -> Unit,
    onRefresh: () -> Unit,
    onHeight: (Int) -> Unit,
    onSnapshot: (Bitmap) -> Unit,
) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }
    val state by WeatherRepository.state.collectAsState()
    val weather by WeatherIslandState.weather.collectAsState()
    val snapshot = weather?.snapshot ?: state.snapshot
    val density = LocalDensity.current
    val units = weather?.units ?: remember(snapshot) { WeatherUnits.from(settings) }
    val iconStyle = weather?.style ?: remember(snapshot) { WeatherIconStyle.fromId(settings.getWeatherIconStyle()) }
    val effectSpec = weather?.effectSpec?.takeIf { host?.powerSave != true } ?: WeatherEffectSpec.None
    val haptics = remember(context) { DeviceWeatherHaptics(context).takeIf { settings.isWeatherHapticsEnabled() } }
    val clearTop = with(density) { (host?.cameraClearancePx ?: 0).toDp() }
    val compactHeight = with(density) { (host?.compactHeightPx ?: 0).toDp() }
    val outset = with(density) { (host?.outsetPx ?: 0).toDp() }
    val sidePadding = with(density) { (host?.expandedPaddingPx ?: 0).toDp() } + outset + 8.dp

    val corner = with(density) { (host?.cornerPx ?: 0).toDp() }
    Box(
        Modifier
            .fillMaxWidth()
            .wrapContentHeight(align = Alignment.Top, unbounded = true)
            .onSizeChanged { onHeight(it.height) }
            .islandSnapshot(onSnapshot = onSnapshot)
            .clip(RoundedCornerShape(corner))
            .background(Color.Black)
            .clickable(onClick = onOpenApp),
    ) {
        if (!effectSpec.isEmpty) {
            WeatherEffects(
                spec = effectSpec,
                modifier = Modifier.matchParentSize(),
                clearTop = clearTop,
                haptics = haptics,
            )
        }
        Column(Modifier.fillMaxWidth()) {
            Spacer(Modifier.height((clearTop - compactHeight).coerceAtLeast(0.dp)))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(compactHeight)
                    .padding(horizontal = sidePadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                snapshot?.let {
                    WeatherIcon(it.condition, it.isDay, MaterialTheme.colorScheme.primary, Modifier.size(22.dp), iconStyle)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = snapshot?.locationName ?: stringResource(R.string.app_name),
                    style = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                snapshot?.let { Text(WeatherFormat.temperature(it.tempC, units.temperature), style = title.copy(fontSize = 22.sp)) }
            }
            Spacer(Modifier.height(6.dp))
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = sidePadding),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (snapshot == null) {
                    if (state.loading) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { LoadingIndicator() }
                    } else {
                        Text(errorText(state.error), style = body)
                    }
                } else {
                    Summary(snapshot, units)
                    WeatherIslandPayload.severeAlert(snapshot)?.let { AlertCard(it) }
                    HourlyStrip(snapshot, units, iconStyle)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DetailChip(R.drawable.rounded_water_drop_24, snapshot.humidity?.let { "$it%" } ?: "–", Modifier.weight(1f))
                        DetailChip(R.drawable.rounded_air_24, WeatherFormat.wind(snapshot.windKph, units.wind), Modifier.weight(1f))
                        DetailChip(R.drawable.rounded_rainy_24, snapshot.chanceOfRain?.let { "$it%" } ?: "–", Modifier.weight(1f))
                    }
                    UpdatedRow(snapshot, state.loading, state.error, onKeepAlive, onRefresh)
                }
            }
            Spacer(Modifier.height(12.dp + outset))
        }
    }
}

@Composable
private fun Summary(snapshot: WeatherSnapshot, units: WeatherUnits) {
    Text(
        text = listOf(
            snapshot.conditionText,
            stringResource(
                R.string.weather_high_low,
                WeatherFormat.temperature(snapshot.highC, units.temperature),
                WeatherFormat.temperature(snapshot.lowC, units.temperature),
            ),
            stringResource(R.string.weather_feels_like, WeatherFormat.temperature(snapshot.feelsLikeC, units.temperature)),
        ).filter { it.isNotBlank() }.joinToString(" · "),
        style = body,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun errorText(error: WeatherError?): String = stringResource(
    when (error) {
        WeatherError.LocationPermission -> R.string.weather_error_location_permission
        WeatherError.NoLocation -> R.string.weather_error_no_location
        else -> R.string.weather_error_unknown
    },
)

@Composable
private fun AlertCard(alert: WeatherAlert) {
    val context = LocalContext.current
    val alertColor = MaterialTheme.colorScheme.error
    val until = alert.expiresMillis?.let { stringResource(R.string.weather_alert_until, WeatherIslandPayload.formatTime(context, it)) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(alertColor.copy(alpha = 0.18f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.rounded_warning_24), null, tint = alertColor, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(alert.event, style = body.copy(color = Color.White, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(until, alert.headline.takeIf { it != alert.event }).joinToString(" · "),
                style = body,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HourlyStrip(snapshot: WeatherSnapshot, units: WeatherUnits, iconStyle: WeatherIconStyle) {
    val context = LocalContext.current
    val hours = snapshot.hourly.take(6)
    if (hours.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.1f))
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        hours.forEach { hour ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(formatHour(context, hour.timeMillis), style = body.copy(fontSize = 11.sp))
                WeatherIcon(hour.condition, hour.isDay, Color.White, Modifier.size(18.dp), iconStyle)
                Text(WeatherFormat.temperature(hour.tempC, units.temperature), style = body.copy(color = Color.White))
            }
        }
    }
}

@Composable
private fun DetailChip(icon: Int, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.1f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = body.copy(color = Color.White, fontSize = 12.sp), maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UpdatedRow(
    snapshot: WeatherSnapshot,
    loading: Boolean,
    error: WeatherError?,
    onKeepAlive: () -> Unit,
    onRefresh: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(snapshot.updatedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000L)
        }
    }
    val age = DateUtils.getRelativeTimeSpanString(
        snapshot.updatedAt,
        maxOf(now, snapshot.updatedAt),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (error != null) errorText(error) else "${providerLabel(snapshot.providerId)} - $age",
            style = body.copy(fontSize = 11.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (loading) {
            LoadingIndicator(Modifier.size(28.dp))
        } else {
            Icon(
                painterResource(R.drawable.rounded_refresh_24),
                null,
                tint = Color.White,
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .clickable {
                        onKeepAlive()
                        onRefresh()
                    }
                    .padding(6.dp),
            )
        }
    }
}

private fun formatHour(context: Context, millis: Long): String {
    val pattern = if (DateFormat.is24HourFormat(context)) "HH" else "ha"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis)).lowercase(Locale.getDefault())
}
