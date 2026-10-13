package com.sameerasw.overcast.ui.features.weather

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.sameerasw.overcast.R
import com.sameerasw.overcast.weather.model.WeatherCondition
import com.sameerasw.overcast.weather.model.WeatherSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

internal class TimelinePoint(
    val timeMillis: Long,
    val tempC: Double,
    val condition: WeatherCondition,
    val isDay: Boolean,
    val chanceOfRain: Int?,
    val conditionText: String?,
)

internal fun timelinePoints(snapshot: WeatherSnapshot, now: Long): List<TimelinePoint> {
    val current = TimelinePoint(now, snapshot.tempC, snapshot.condition, snapshot.isDay, snapshot.chanceOfRain, snapshot.conditionText)
    val later = snapshot.upcomingHours(now).filter { it.timeMillis > now }
        .map { TimelinePoint(it.timeMillis, it.tempC, it.condition, it.isDay, it.chanceOfRain, null) }
    return listOf(current) + later
}

@Stable
internal class WeatherTimeline {
    var value by mutableFloatStateOf(0f)
        private set
    var points by mutableStateOf(emptyList<TimelinePoint>())

    private var job: Job? = null

    val maxPosition: Float get() = (points.size - 1).coerceAtLeast(0).toFloat()

    fun set(position: Float) {
        value = position.coerceIn(0f, maxPosition)
    }

    fun dragBy(delta: Float) = set(value + delta)

    fun cancelAnimation() {
        job?.cancel()
    }

    fun animateTo(scope: CoroutineScope, target: Float, velocity: Float = 0f) {
        job?.cancel()
        job = scope.launch {
            animate(
                initialValue = value,
                targetValue = target.coerceIn(0f, maxPosition),
                initialVelocity = velocity,
                animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow),
            ) { v, _ -> set(v) }
        }
    }

    fun settle(scope: CoroutineScope, velocityPxPerSec: Float, stepPx: Float) {
        val stepsPerSec = -velocityPxPerSec / stepPx
        animateTo(scope, (value + stepsPerSec * 0.18f).roundToInt().toFloat(), stepsPerSec)
    }

    fun timeAt(position: Float): Long = interpolate(position) { a, b, f -> lerp(a.timeMillis, b.timeMillis, f) } ?: 0L

    fun tempAt(position: Float): Double = interpolate(position) { a, b, f -> a.tempC + (b.tempC - a.tempC) * f } ?: 0.0

    fun pointAt(position: Float): TimelinePoint? = points.getOrNull(position.roundToInt().coerceIn(0, (points.size - 1).coerceAtLeast(0)))

    private fun <T> interpolate(position: Float, mix: (TimelinePoint, TimelinePoint, Float) -> T): T? {
        if (points.isEmpty()) return null
        val clamped = position.coerceIn(0f, maxPosition)
        val low = floor(clamped).toInt()
        val high = (low + 1).coerceAtMost(points.size - 1)
        return mix(points[low], points[high], clamped - low)
    }
}

internal fun Modifier.timelineGestures(
    timeline: WeatherTimeline,
    scope: CoroutineScope,
    stepPx: Float,
    onVerticalDrag: (Float) -> Unit,
    onVerticalEnd: () -> Unit,
): Modifier = pointerInput(timeline, stepPx) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        timeline.cancelAnimation()
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var horizontal = true
        val first = awaitTouchSlopOrCancellation(down.id) { change, overSlop ->
            horizontal = abs(overSlop.x) >= abs(overSlop.y)
            change.consume()
        } ?: return@awaitEachGesture
        tracker.addPosition(first.uptimeMillis, first.position)
        val firstDelta = first.positionChange()
        if (horizontal) timeline.dragBy(-firstDelta.x / stepPx) else onVerticalDrag(firstDelta.y)
        drag(first.id) { change ->
            tracker.addPosition(change.uptimeMillis, change.position)
            val delta = change.positionChange()
            if (horizontal) timeline.dragBy(-delta.x / stepPx) else onVerticalDrag(delta.y)
            change.consume()
        }
        if (horizontal) timeline.settle(scope, tracker.calculateVelocity().x, stepPx) else onVerticalEnd()
    }
}

@Composable
internal fun RollingClock(text: String, color: Color, forward: Boolean, style: androidx.compose.ui.text.TextStyle) {
    Row {
        text.forEach { char ->
            AnimatedContent(
                targetState = char,
                transitionSpec = {
                    val direction = if (forward) 1 else -1
                    (slideInVertically { it * direction } + fadeIn()) togetherWith (slideOutVertically { -it * direction } + fadeOut())
                },
                label = "timelineDigit",
            ) { digit ->
                Text(digit.toString(), color = color, style = style, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
internal fun CounterText(
    text: String,
    color: Color,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    forward: Boolean = true,
) {
    AnimatedContent(
        targetState = text,
        modifier = modifier,
        transitionSpec = {
            val direction = if (forward) 1 else -1
            (slideInVertically { it * direction } + fadeIn()) togetherWith (slideOutVertically { -it * direction } + fadeOut())
        },
        label = "counterText",
    ) { value ->
        Text(value, color = color, style = style, maxLines = 1, softWrap = false)
    }
}

@Composable
internal fun rememberAnimatedAvailable(target: Int): () -> Int {
    val animated = remember { Animatable(0f) }
    LaunchedEffect(target) {
        if (target <= 0) return@LaunchedEffect
        if (animated.value <= 0f) animated.snapTo(target.toFloat()) else animated.animateTo(target.toFloat(), spring(stiffness = Spring.StiffnessMediumLow))
    }
    return { animated.value.roundToInt() }
}

@StringRes
internal fun conditionLabelRes(condition: WeatherCondition): Int = when (condition) {
    WeatherCondition.CLEAR -> R.string.condition_clear
    WeatherCondition.PARTLY_CLOUDY -> R.string.condition_partly_cloudy
    WeatherCondition.CLOUDY -> R.string.condition_cloudy
    WeatherCondition.FOG -> R.string.condition_fog
    WeatherCondition.DRIZZLE -> R.string.condition_drizzle
    WeatherCondition.RAIN -> R.string.condition_rain
    WeatherCondition.HEAVY_RAIN -> R.string.condition_heavy_rain
    WeatherCondition.SLEET -> R.string.condition_sleet
    WeatherCondition.SNOW -> R.string.condition_snow
    WeatherCondition.HAIL -> R.string.condition_hail
    WeatherCondition.THUNDERSTORM -> R.string.condition_thunderstorm
    WeatherCondition.UNKNOWN -> R.string.condition_unknown
}

internal fun conditionLabel(context: Context, condition: WeatherCondition): String = context.getString(conditionLabelRes(condition))
