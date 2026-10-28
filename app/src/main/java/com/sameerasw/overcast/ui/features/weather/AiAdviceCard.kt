package com.sameerasw.overcast.ui.features.weather

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.togetherWith
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
import com.sameerasw.overcast.utils.HapticUtil
import com.sameerasw.overcast.weather.WeatherUnits
import com.sameerasw.overcast.weather.ai.AiAdvisor
import com.sameerasw.overcast.weather.ai.AiException
import com.sameerasw.overcast.weather.model.WeatherSnapshot
import kotlinx.coroutines.CancellationException

private const val ADVICE_BOUNDS_KEY = "weather_advice"
private const val BACK_SCALE = 0.12f

@OptIn(ExperimentalSharedTransitionApi::class)
private val AdviceBoundsTransform = BoundsTransform { _, _ ->
    spring(dampingRatio = 0.92f, stiffness = 320f, visibilityThreshold = Rect.VisibilityThreshold)
}
private val adviceEnter = fadeIn(tween(220, delayMillis = 90, easing = LinearOutSlowInEasing))
private val adviceExit = fadeOut(tween(90, easing = FastOutLinearInEasing))

@Composable
fun rememberAiAdvice(snapshot: WeatherSnapshot?, units: WeatherUnits): String? {
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
    var advice by remember {
        mutableStateOf(snapshot?.takeIf { available }?.let { AiAdvisor.cached(context, it, units) })
    }

    LaunchedEffect(available, configVersion, snapshot?.updatedAt, snapshot?.locationName, units, adviceVersion) {
        if (!available || snapshot == null) {
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
    return advice
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun BoxScope.AiAdviceFab(advice: String?, visible: Boolean, palette: WeatherPalette) {
    val view = LocalView.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var shownAdvice by remember { mutableStateOf(advice) }
    if (advice != null) shownAdvice = advice
    val show = visible && advice != null
    LaunchedEffect(show) { if (!show) expanded = false }
    val scope = rememberCoroutineScope()
    val backProgress = remember { Animatable(0f) }
    LaunchedEffect(expanded) { if (expanded) backProgress.snapTo(0f) }
    PredictiveBackHandler(enabled = expanded) { events ->
        try {
            events.collect { backProgress.snapTo(it.progress) }
            expanded = false
        } catch (e: CancellationException) {
            scope.launch { backProgress.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
            throw e
        }
    }

    AnimatedVisibility(
        visible = expanded,
        modifier = Modifier.matchParentSize(),
        enter = fadeIn(tween(280, easing = LinearOutSlowInEasing)),
        exit = fadeOut(tween(220, easing = FastOutLinearInEasing)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 1f - backProgress.value * 0.5f }
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(interactionSource = null, indication = null) { expanded = false },
        )
    }

    SharedTransitionLayout(
        Modifier
            .align(Alignment.BottomEnd)
            .navigationBarsPadding()
            .padding(16.dp),
    ) {
        AnimatedContent(
            targetState = expanded,
            contentAlignment = Alignment.BottomEnd,
            transitionSpec = { EnterTransition.None togetherWith ExitTransition.None using SizeTransform(clip = false) },
            label = "adviceFab",
        ) { isExpanded ->
            if (!isExpanded) {
                FloatingActionButton(
                    onClick = {
                        HapticUtil.performVirtualKeyHaptic(view)
                        expanded = true
                    },
                    containerColor = palette.accent,
                    contentColor = Color.Black,
                    modifier = Modifier
                        .animateFloatingActionButton(visible = show, alignment = Alignment.BottomEnd)
                        .sharedBounds(
                            rememberSharedContentState(ADVICE_BOUNDS_KEY),
                            animatedVisibilityScope = this@AnimatedContent,
                            enter = adviceEnter,
                            exit = adviceExit,
                            boundsTransform = AdviceBoundsTransform,
                            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                            clipInOverlayDuringTransition = OverlayClip(FloatingActionButtonDefaults.shape),
                        ),
                ) {
                    Icon(
                        painterResource(R.drawable.rounded_auto_awesome_24),
                        contentDescription = stringResource(R.string.ai_section_title),
                    )
                }
            } else {
                Surface(
                    color = palette.card.compositeOver(palette.base),
                    shape = MaterialTheme.shapes.extraLarge,
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            val p = backProgress.value
                            val shrink = 1f - p * BACK_SCALE
                            scaleX = shrink
                            scaleY = shrink
                            transformOrigin = TransformOrigin(1f, 1f)
                        }
                        .sharedBounds(
                            rememberSharedContentState(ADVICE_BOUNDS_KEY),
                            animatedVisibilityScope = this@AnimatedContent,
                            enter = adviceEnter,
                            exit = adviceExit,
                            boundsTransform = AdviceBoundsTransform,
                            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                            clipInOverlayDuringTransition = OverlayClip(MaterialTheme.shapes.extraLarge),
                        ),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(
                                painterResource(R.drawable.rounded_auto_awesome_24),
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
                            IconButton(
                                onClick = {
                                    HapticUtil.performVirtualKeyHaptic(view)
                                    expanded = false
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    painterResource(R.drawable.rounded_close_24),
                                    contentDescription = null,
                                    tint = palette.onBaseMuted,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        Column(
                            Modifier
                                .heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            shownAdvice?.let { text ->
                                adviceLines(text).forEach { line ->
                                    Text(line, color = palette.onBase, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
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
            AiException.Reason.UNSUPPORTED -> R.string.ai_error_unsupported
            AiException.Reason.DOWNLOAD_FAILED -> R.string.ai_error_download
            AiException.Reason.RATE_LIMITED -> R.string.ai_error_rate_limited
            AiException.Reason.UNAVAILABLE -> R.string.ai_error_unavailable
            AiException.Reason.NETWORK -> R.string.ai_error_network
            AiException.Reason.NOT_CONFIGURED, AiException.Reason.BAD_RESPONSE -> R.string.ai_error_unknown
        },
    )
    val detail = error.message?.takeIf { it.isNotBlank() }
    return if (detail != null) "$base: $detail" else base
}
