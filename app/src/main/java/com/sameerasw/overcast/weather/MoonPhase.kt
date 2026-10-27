package com.sameerasw.overcast.weather

import androidx.annotation.StringRes
import com.sameerasw.overcast.R
import com.sameerasw.overcast.weather.model.WeatherSnapshot
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.roundToInt

object MoonPhase {
    const val SYNODIC_DAYS = 29.530588853
    private const val SYNODIC_MS = SYNODIC_DAYS * 24 * 60 * 60_000.0
    private const val REFERENCE_NEW_MOON_MS = 947_182_440_000.0

    fun compute(nowMillis: Long): Double = wrap((nowMillis - REFERENCE_NEW_MOON_MS) / SYNODIC_MS)

    fun fromAgeDays(age: Double): Double = wrap(age / SYNODIC_DAYS)

    fun fromIllumination(percent: Double, waxing: Boolean): Double {
        val half = acos((1.0 - 2.0 * (percent / 100.0)).coerceIn(-1.0, 1.0)) / (2 * PI)
        return if (waxing) half else wrap(1.0 - half)
    }

    fun illumination(phase: Double): Double = (1.0 - cos(2 * PI * phase)) / 2.0

    fun illuminationPercent(phase: Double): Int = (illumination(phase) * 100).roundToInt()

    @StringRes
    fun nameRes(phase: Double): Int = when (((wrap(phase) * 8) + 0.5).toInt() % 8) {
        0 -> R.string.moon_new
        1 -> R.string.moon_waxing_crescent
        2 -> R.string.moon_first_quarter
        3 -> R.string.moon_waxing_gibbous
        4 -> R.string.moon_full
        5 -> R.string.moon_waning_gibbous
        6 -> R.string.moon_last_quarter
        else -> R.string.moon_waning_crescent
    }

    fun wrap(value: Double): Double = ((value % 1.0) + 1.0) % 1.0
}

fun WeatherSnapshot.moonPhaseAt(now: Long = System.currentTimeMillis()): Double {
    val reported = extras?.moonPhase ?: return MoonPhase.compute(now)
    val elapsedCycles = (now - updatedAt) / (29.530588853 * 24 * 60 * 60_000.0)
    return MoonPhase.wrap(reported + elapsedCycles)
}
