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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sameerasw.overcast.R
import com.sameerasw.overcast.data.repository.SettingsRepository
import com.sameerasw.overcast.utils.HapticUtil
import com.sameerasw.overcast.weather.WeatherUnits
import com.sameerasw.overcast.weather.ai.AiAdvisor
import com.sameerasw.overcast.weather.ai.AiException
import com.sameerasw.overcast.weather.model.WeatherSnapshot
import kotlinx.coroutines.CancellationException

@Composable
fun AiAdviceSection(snapshot: WeatherSnapshot, units: WeatherUnits, palette: WeatherPalette, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    val settings = remember { SettingsRepository(context) }
    var configVersion by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val prefs = context.getSharedPreferences(SettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key?.startsWith("ai_") == true) configVersion++
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val available = remember(configVersion) { settings.isAiAdviceAvailable() }
    var advice by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<AiException.Reason?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var forceNext by remember { mutableStateOf(false) }

    LaunchedEffect(available, configVersion, snapshot.updatedAt, snapshot.locationName, units, refreshTick) {
        if (!available) {
            advice = null
            error = null
            return@LaunchedEffect
        }
        val force = forceNext
        forceNext = false
        if (!force) AiAdvisor.cached(context, snapshot, units)?.let { advice = it }
        loading = true
        error = null
        try {
            advice = AiAdvisor.advise(context, snapshot, units, force)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiException) {
            error = e.reason
        } catch (_: Exception) {
            error = AiException.Reason.BAD_RESPONSE
        } finally {
            loading = false
        }
    }

    AnimatedVisibility(
        visible = available,
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
                        painterResource(R.drawable.rounded_model_training_24),
                        contentDescription = null,
                        tint = palette.accent,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        stringResource(R.string.ai_section_title),
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
                advice?.let {
                    Text(it, color = palette.onBase, style = MaterialTheme.typography.bodyMedium)
                }
                error?.let {
                    Text(
                        aiErrorText(it),
                        color = palette.onBaseMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun aiErrorText(reason: AiException.Reason): String = stringResource(
    when (reason) {
        AiException.Reason.INVALID_KEY -> R.string.ai_error_invalid_key
        AiException.Reason.RATE_LIMITED -> R.string.ai_error_rate_limited
        AiException.Reason.NETWORK -> R.string.ai_error_network
        AiException.Reason.NOT_CONFIGURED, AiException.Reason.BAD_RESPONSE -> R.string.ai_error_unknown
    },
)
