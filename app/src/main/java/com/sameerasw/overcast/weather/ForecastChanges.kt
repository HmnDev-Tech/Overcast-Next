package com.sameerasw.overcast.weather

import com.sameerasw.overcast.weather.model.WeatherSnapshot
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ForecastChange {
    // Null means no rain in the look-ahead window.
    data class RainTiming(val previousMillis: Long?, val currentMillis: Long?) : ForecastChange
    data class TodayHigh(val previousC: Double, val currentC: Double) : ForecastChange
}

data class ForecastChanges(val changes: List<ForecastChange>, val detectedAt: Long)

object ForecastChangeTracker {
    private const val HOUR_MS = 60 * 60_000L
    private const val LOOK_AHEAD_HOURS = 12
    private const val RAIN_CHANCE = 50
    private const val SHIFT_MS = HOUR_MS
    private const val HIGH_DELTA_C = 2.0
    const val SHOW_FOR_MS = 6 * HOUR_MS

    private val _latest = MutableStateFlow<ForecastChanges?>(null)
    val latest: StateFlow<ForecastChanges?> = _latest.asStateFlow()

    fun dismiss() {
        _latest.value = null
    }

    internal fun record(previous: WeatherSnapshot?, current: WeatherSnapshot) {
        if (previous == null || previous.locationName != current.locationName || previous.providerId != current.providerId) return
        val now = current.updatedAt
        val changes = buildList {
            val before = rainStart(previous, now)
            val after = rainStart(current, now)
            val moved = before != null && after != null && abs(before - after) >= SHIFT_MS
            if ((before == null) != (after == null) || moved) add(ForecastChange.RainTiming(before, after))
            if (abs(previous.todayHighC(now) - current.todayHighC(now)) >= HIGH_DELTA_C) {
                add(ForecastChange.TodayHigh(previous.todayHighC(now), current.todayHighC(now)))
            }
        }
        if (changes.isNotEmpty()) _latest.value = ForecastChanges(changes, now)
    }

    private fun rainStart(snapshot: WeatherSnapshot, now: Long): Long? =
        snapshot.upcomingHours(now)
            .filter { it.timeMillis < now + LOOK_AHEAD_HOURS * HOUR_MS }
            .firstOrNull { it.chanceOfRain >= RAIN_CHANCE }
            ?.timeMillis
}
