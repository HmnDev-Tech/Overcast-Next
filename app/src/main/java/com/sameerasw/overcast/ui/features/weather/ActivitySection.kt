package com.sameerasw.overcast.ui.features.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sameerasw.overcast.R
import com.sameerasw.overcast.weather.MoonPhase
import com.sameerasw.overcast.weather.moonPhaseAt
import com.sameerasw.overcast.weather.aqi.AqiRepository
import com.sameerasw.overcast.weather.model.HourlyForecast
import com.sameerasw.overcast.weather.model.WeatherSnapshot

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
internal fun activityScore(activity: Activity, snapshot: WeatherSnapshot, now: Long): Double? {
    val window = snapshot.upcomingHours(now).take(if (activity == Activity.LAUNDRY) 6 else 3)
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

@Composable
fun ActivitySection(snapshot: WeatherSnapshot, palette: WeatherPalette, modifier: Modifier = Modifier) {
    val now by rememberMinuteClock()
    val scores = remember(snapshot, now / (15 * 60_000L)) {
        Activity.entries.mapNotNull { activity -> activityScore(activity, snapshot, now)?.let { activity to ActivityRating.from(it) } }
    }
    if (scores.isEmpty()) return
    WeatherCard("activities", R.drawable.rounded_directions_run_24, stringResource(R.string.activity_title), palette, modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            scores.forEach { (activity, rating) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(painterResource(activity.iconRes), contentDescription = null, tint = palette.onBaseMuted, modifier = Modifier.size(22.dp))
                    Text(stringResource(activity.titleRes), color = palette.onBase, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Box(Modifier.size(8.dp).clip(CircleShape).background(rating.color))
                    Text(
                        stringResource(rating.labelRes),
                        color = rating.color,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                }
            }
        }
    }
}
