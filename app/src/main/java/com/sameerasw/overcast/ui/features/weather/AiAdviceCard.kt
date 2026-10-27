package com.sameerasw.overcast.ui.features.weather

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.sameerasw.overcast.R
import com.sameerasw.overcast.data.repository.SettingsRepository
import com.sameerasw.overcast.weather.WeatherUnits
import com.sameerasw.overcast.weather.ai.AiAdvisor
import com.sameerasw.overcast.weather.ai.AiException
import com.sameerasw.overcast.weather.model.WeatherSnapshot
import kotlinx.coroutines.CancellationException

@Composable
fun AiAdviceSection(snapshot: WeatherSnapshot, units: WeatherUnits, palette: WeatherPalette, modifier: Modifier = Modifier) {
    val context = LocalContext.current
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
    val adviceVersion by AiAdvisor.version.collectAsState()
    val available = remember(configVersion) { settings.isAiAdviceAvailable() }
    var advice by remember { mutableStateOf(if (available) AiAdvisor.cached(context, snapshot, units) else null) }

    LaunchedEffect(available, configVersion, snapshot.updatedAt, snapshot.locationName, units, adviceVersion) {
        if (!available) {
            advice = null
            return@LaunchedEffect
        }
        AiAdvisor.cached(context, snapshot, units)?.let {
            advice = it
            return@LaunchedEffect
        }
        try {
            advice = AiAdvisor.advise(context, snapshot, units)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    AnimatedVisibility(
        visible = available && advice != null,
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
                    )
                }
                advice?.let { text ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        adviceLines(text).forEach { line ->
                            Text(line, color = palette.onBase, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

private val HEADING_MARKER = Regex("^#+\\s*")
private val LIST_MARKER = Regex("^(?:[*\\-•+]|\\d+[.)])\\s+")
private val BOLD = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__")

private fun adviceLines(text: String): List<AnnotatedString> =
    text.lines()
        .map { it.trim().replace(HEADING_MARKER, "").replace(LIST_MARKER, "").trim() }
        .filter { it.isNotBlank() }
        .map { line ->
            buildAnnotatedString {
                var last = 0
                BOLD.findAll(line).forEach { match ->
                    append(line.substring(last, match.range.first).replace("*", ""))
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        append(match.groupValues[1].ifEmpty { match.groupValues[2] })
                    }
                    last = match.range.last + 1
                }
                append(line.substring(last).replace("*", ""))
            }
        }

@Composable
internal fun aiErrorText(error: AiException): String {
    val base = stringResource(
        when (error.reason) {
            AiException.Reason.INVALID_KEY -> R.string.ai_error_invalid_key
            AiException.Reason.MODEL_NOT_FOUND -> R.string.ai_error_model
            AiException.Reason.RATE_LIMITED -> R.string.ai_error_rate_limited
            AiException.Reason.UNAVAILABLE -> R.string.ai_error_unavailable
            AiException.Reason.NETWORK -> R.string.ai_error_network
            AiException.Reason.NOT_CONFIGURED, AiException.Reason.BAD_RESPONSE -> R.string.ai_error_unknown
        },
    )
    val detail = error.message?.takeIf { it.isNotBlank() }
    return if (detail != null) "$base: $detail" else base
}
