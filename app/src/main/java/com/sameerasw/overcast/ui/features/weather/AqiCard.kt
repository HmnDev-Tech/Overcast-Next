package com.sameerasw.overcast.ui.features.weather

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sameerasw.overcast.R
import com.sameerasw.overcast.data.repository.SettingsRepository
import com.sameerasw.overcast.utils.HapticUtil
import com.sameerasw.overcast.weather.aqi.AqiCategory
import com.sameerasw.overcast.weather.aqi.AqiData
import com.sameerasw.overcast.weather.aqi.AqiProviders
import com.sameerasw.overcast.weather.aqi.AqiRepository
import com.sameerasw.overcast.weather.model.WeatherSnapshot
import kotlinx.coroutines.CancellationException

@Composable
fun AqiSection(snapshot: WeatherSnapshot, palette: WeatherPalette, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    var data by remember { mutableStateOf<AqiData?>(null) }
    var loading by remember { mutableStateOf(true) }
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
        loading = true
        failed = false
        try {
            data = AqiRepository.current(context)
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
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            aqi.aqi?.toString() ?: "—",
                            color = aqiColor(aqi.category),
                            style = MaterialTheme.typography.displaySmall,
                        )
                        Text(
                            aqiLabel(aqi.category),
                            color = palette.onBaseMuted,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        aqi.pm25?.let { Pollutant("PM2.5", it, palette) }
                        aqi.pm10?.let { Pollutant("PM10", it, palette) }
                        aqi.ozone?.let { Pollutant("O₃", it, palette) }
                        aqi.no2?.let { Pollutant("NO₂", it, palette) }
                    }
                    Text(
                        stringResource(R.string.aqi_source),
                        color = palette.onBaseMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
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
private fun Pollutant(name: String, value: Double, palette: WeatherPalette) {
    Column {
        Text(name, color = palette.onBaseMuted, style = MaterialTheme.typography.labelMedium)
        Text(
            if (value < 10) "%.1f".format(value) else value.toInt().toString(),
            color = palette.onBase,
            style = MaterialTheme.typography.titleMedium,
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
    AqiCategory.HAZARDOUS -> Color(0xFF8B0000)
    AqiCategory.UNKNOWN -> Color.White
}
