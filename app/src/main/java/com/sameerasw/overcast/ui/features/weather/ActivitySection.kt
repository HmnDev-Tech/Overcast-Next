package com.sameerasw.overcast.ui.features.weather

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.ui.platform.LocalContext
import com.sameerasw.overcast.weather.WeatherFormat
import com.sameerasw.overcast.weather.WeatherUnits
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateBounds
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.LocalView
import com.sameerasw.overcast.utils.HapticUtil
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sameerasw.overcast.R
import com.sameerasw.overcast.weather.MoonPhase
import com.sameerasw.overcast.weather.moonPhaseAt
import com.sameerasw.overcast.weather.aqi.AqiRepository
import com.sameerasw.overcast.weather.model.HourlyForecast
import com.sameerasw.overcast.weather.model.WeatherSnapshot
import java.util.Calendar

enum class Activity(val iconRes: Int, val titleRes: Int) {
    RUNNING(R.drawable.rounded_directions_run_24, R.string.activity_running),
    CYCLING(R.drawable.rounded_directions_bike_24, R.string.activity_cycling),
    LAUNDRY(R.drawable.rounded_local_laundry_service_24, R.string.activity_laundry),
    STARGAZING(R.drawable.rounded_bedtime_24, R.string.activity_stargazing),
}

enum class ActivityRating(val labelRes: Int, val color: Color) {
    GREAT(R.string.activity_great, Color(0xFF7ED491)),
    GOOD(R.string.activity_good, Color(0xFFB8E07A)),
    FAIR(R.string.activity_fair, Color(0xFFFFD166)),
    POOR(R.string.activity_poor, Color(0xFFFF8A6B)),
    ;

    companion object {
        fun from(score: Double): ActivityRating = when {
            score >= 8 -> GREAT
            score >= 6 -> GOOD
            score >= 4 -> FAIR
            else -> POOR
        }
    }
}

// Each score starts at 10 and loses points for whatever makes the activity unpleasant over the next few hours.
internal fun activityScore(activity: Activity, snapshot: WeatherSnapshot, now: Long): Double? =
    windowScore(activity, snapshot.upcomingHours(now).take(windowHours(activity)), snapshot, now)

private fun windowHours(activity: Activity): Int = if (activity == Activity.LAUNDRY) 6 else 3

private fun windowScore(activity: Activity, window: List<HourlyForecast>, snapshot: WeatherSnapshot, now: Long): Double? {
    if (window.isEmpty()) return null
    fun avg(selector: (HourlyForecast) -> Double?) = window.mapNotNull(selector).takeIf { it.isNotEmpty() }?.average()
    val temp = window.map { it.tempC }.average()
    val rainChance = window.maxOf { it.chanceOfRain }.toDouble()
    val rainMm = window.sumOf { it.precipitationMm ?: 0.0 }
    val wind = avg { it.windKph } ?: snapshot.windKph
    val humidity = avg { it.humidity?.toDouble() } ?: snapshot.humidity?.toDouble() ?: 60.0
    val uv = avg { it.uvIndex } ?: 0.0
    val cloud = avg { it.cloudCover?.toDouble() } ?: snapshot.extras?.cloudCover?.toDouble() ?: 50.0
    val aqi = AqiRepository.cached()?.aqi ?: 0

    var score = 10.0
    fun outside(value: Double, low: Double, high: Double, perUnit: Double) {
        if (value < low) score -= (low - value) * perUnit
        if (value > high) score -= (value - high) * perUnit
    }
    when (activity) {
        Activity.RUNNING -> {
            outside(temp, 6.0, 20.0, 0.4)
            score -= rainChance / 15 + rainMm * 1.5
            if (wind > 25) score -= (wind - 25) / 5
            if (humidity > 80) score -= (humidity - 80) / 8
            if (uv > 6) score -= (uv - 6) * 0.6
            if (aqi > 100) score -= (aqi - 100) / 20.0
        }
        Activity.CYCLING -> {
            outside(temp, 10.0, 25.0, 0.35)
            score -= rainChance / 12 + rainMm * 2
            if (wind > 15) score -= (wind - 15) / 4
            if (aqi > 100) score -= (aqi - 100) / 25.0
        }
        Activity.LAUNDRY -> {
            score -= rainChance / 8 + rainMm * 3
            if (humidity > 60) score -= (humidity - 60) / 6
            if (wind < 5) score -= 1.5
            if (temp < 10) score -= (10 - temp) * 0.3
            score -= cloud / 40
            if (!window.first().isDay) score -= 3
        }
        Activity.STARGAZING -> {
            score -= cloud / 10
            score -= rainChance / 15
            score -= MoonPhase.illumination(snapshot.moonPhaseAt(now)) * 3
            if (window.first().isDay) score -= 2
        }
    }
    return score.coerceIn(0.0, 10.0)
}

private class Guidance(val iconRes: Int, val questionRes: Int, val answer: String, val rating: ActivityRating)

private const val HOUR_MS = 60 * 60_000L

private fun guidance(context: Context, snapshot: WeatherSnapshot, units: WeatherUnits, now: Long): List<Guidance> {
    val hours = snapshot.upcomingHours(now)
    if (hours.isEmpty()) return emptyList()
    fun at(millis: Long): String {
        val time = formatHour(context, millis)
        return if (DateUtils.isToday(millis)) time else context.getString(R.string.guidance_tomorrow_at, time)
    }
    fun isNow(millis: Long) = millis <= now
    fun bestStart(activity: Activity, horizon: Int, accept: (HourlyForecast) -> Boolean): Pair<HourlyForecast, Double>? {
        val size = windowHours(activity)
        return hours.take(horizon).indices.mapNotNull { i ->
            val start = hours[i]
            if (!accept(start)) return@mapNotNull null
            windowScore(activity, hours.subList(i, minOf(i + size, hours.size)), snapshot, start.timeMillis)?.let { start to it }
        }.maxByOrNull { it.second }
    }
    fun localHour(millis: Long) = Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.HOUR_OF_DAY)
    val result = mutableListOf<Guidance>()

    val next = hours.take(12)
    val wet = next.firstOrNull { it.chanceOfRain >= 50 || (it.precipitationMm ?: 0.0) >= 0.3 }
    val maybe = next.filter { it.chanceOfRain >= 30 }.maxByOrNull { it.chanceOfRain }
    result += when {
        wet != null && isNow(wet.timeMillis) -> Guidance(R.drawable.rounded_rainy_24, R.string.guidance_umbrella, context.getString(R.string.guidance_umbrella_now), ActivityRating.POOR)
        wet != null -> Guidance(R.drawable.rounded_rainy_24, R.string.guidance_umbrella, context.getString(R.string.guidance_umbrella_from, at(wet.timeMillis)), ActivityRating.POOR)
        maybe != null -> Guidance(R.drawable.rounded_rainy_24, R.string.guidance_umbrella, context.getString(R.string.guidance_umbrella_maybe, maybe.chanceOfRain, at(maybe.timeMillis)), ActivityRating.FAIR)
        else -> Guidance(R.drawable.rounded_rainy_24, R.string.guidance_umbrella, context.getString(R.string.guidance_umbrella_no, at(next.last().timeMillis + HOUR_MS)), ActivityRating.GREAT)
    }

    for ((activity, question) in listOf(Activity.RUNNING to R.string.guidance_run, Activity.CYCLING to R.string.guidance_ride)) {
        val best = bestStart(activity, 16) { localHour(it.timeMillis) in 5..20 } ?: continue
        val rating = ActivityRating.from(best.second)
        val answer = when {
            rating == ActivityRating.POOR -> context.getString(R.string.guidance_not_today)
            isNow(best.first.timeMillis) -> context.getString(R.string.guidance_now_good)
            else -> context.getString(R.string.guidance_best_at, at(best.first.timeMillis), WeatherFormat.temperature(best.first.tempC, units.temperature))
        }
        result += Guidance(activity.iconRes, question, answer, rating)
    }

    val laundryNow = activityScore(Activity.LAUNDRY, snapshot, now)
    if (laundryNow != null) {
        result += if (laundryNow >= 6) {
            val until = hours.drop(1).firstOrNull { !it.isDay || it.chanceOfRain >= 40 }?.timeMillis
            Guidance(
                Activity.LAUNDRY.iconRes,
                R.string.guidance_laundry,
                until?.let { context.getString(R.string.guidance_laundry_until, at(it)) } ?: context.getString(R.string.guidance_laundry_yes),
                ActivityRating.from(laundryNow),
            )
        } else {
            val later = bestStart(Activity.LAUNDRY, 30) { it.isDay && it.timeMillis > now }?.takeIf { it.second >= 6 }
            Guidance(
                Activity.LAUNDRY.iconRes,
                R.string.guidance_laundry,
                later?.let { context.getString(R.string.guidance_wait_until, at(it.first.timeMillis)) } ?: context.getString(R.string.guidance_not_today),
                ActivityRating.from(laundryNow),
            )
        }
    }

    bestStart(Activity.STARGAZING, 18) { !it.isDay }?.let { (hour, score) ->
        val rating = ActivityRating.from(score)
        val answer = if (rating == ActivityRating.POOR || rating == ActivityRating.FAIR) {
            context.getString(R.string.guidance_stars_cloudy)
        } else {
            context.getString(R.string.guidance_stars_at, at(hour.timeMillis))
        }
        result += Guidance(Activity.STARGAZING.iconRes, R.string.guidance_stars, answer, rating)
    }

    val uv = uvProtectionWindow(snapshot, now)?.takeIf { it.second > now }
    result += if (uv != null) {
        Guidance(R.drawable.rounded_wb_sunny_24, R.string.guidance_sunscreen, context.getString(R.string.guidance_sunscreen_yes, formatHour(context, maxOf(uv.first, now)), formatHour(context, uv.second)), ActivityRating.FAIR)
    } else {
        Guidance(R.drawable.rounded_wb_sunny_24, R.string.guidance_sunscreen, context.getString(R.string.guidance_sunscreen_no), ActivityRating.GREAT)
    }
    return result
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ActivitySection(snapshot: WeatherSnapshot, units: WeatherUnits, palette: WeatherPalette, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    val now by rememberMinuteClock()
    val items = remember(snapshot, units, now / (15 * 60_000L)) { guidance(context, snapshot, units, now) }
    if (items.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    var listLayout by remember { mutableStateOf(expanded) }
    var detailsShown by remember { mutableStateOf(expanded) }
    LaunchedEffect(expanded) {
        if (expanded) {
            listLayout = true
            delay(DETAILS_DELAY_MS)
            detailsShown = true
        } else {
            detailsShown = false
            delay(DETAILS_FADE_MS.toLong())
            listLayout = false
        }
    }
    Surface(
        color = palette.card,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier
            .rainSurface("activities")
            .fillMaxWidth()
            .animateContentSize(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))
            .clip(MaterialTheme.shapes.extraLarge)
            .clickable {
                HapticUtil.performVirtualKeyHaptic(view)
                expanded = !expanded
            },
    ) {
        LookaheadScope {
            val icons = remember(items) {
                items.map { item ->
                    movableContentOf {
                        GuidanceIcon(item, Modifier.animateBounds(this@LookaheadScope, boundsTransform = GuidanceBounds))
                    }
                }
            }
            if (listLayout) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items.forEachIndexed { index, item ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            icons[index]()
                            val reveal by animateFloatAsState(
                                if (detailsShown) 1f else 0f,
                                tween(DETAILS_FADE_MS, delayMillis = if (detailsShown) index * DETAILS_STAGGER_MS else 0),
                                label = "guidanceRow",
                            )
                            Column(
                                Modifier.weight(1f).graphicsLayer {
                                    alpha = reveal
                                    translationX = (1f - reveal) * 12.dp.toPx()
                                },
                            ) {
                                Text(stringResource(item.questionRes), color = palette.onBaseMuted, style = MaterialTheme.typography.labelMedium)
                                Text(item.answer, color = palette.onBase, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    items.indices.forEach { icons[it]() }
                }
            }
        }
    }
}

@Composable
private fun GuidanceIcon(item: Guidance, modifier: Modifier) {
    Icon(
        painterResource(item.iconRes),
        contentDescription = null,
        tint = lerp(item.rating.color, Color.White, 0.35f),
        modifier = modifier.size(26.dp),
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
private val GuidanceBounds = BoundsTransform { _, _ -> spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow) }

private const val DETAILS_DELAY_MS = 320L
private const val DETAILS_FADE_MS = 220
private const val DETAILS_STAGGER_MS = 60
