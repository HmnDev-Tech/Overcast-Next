package com.sameerasw.overcast.weather

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

// NOAA "almanac" sunrise equation, generalised to any sun elevation; accurate to a minute or two, plenty for golden hour.
object SolarTimes {
    private const val HOUR_MS = 60 * 60_000L

    data class Window(val startMillis: Long, val endMillis: Long)

    class Day(val morningGolden: Window?, val eveningGolden: Window?, val morningBlue: Window?, val eveningBlue: Window?)

    fun forDay(dayMillis: Long, latitude: Double, longitude: Double): Day {
        fun at(elevation: Double, morning: Boolean) = timeAtElevation(dayMillis, latitude, longitude, elevation, morning)
        fun window(a: Long?, b: Long?) = if (a != null && b != null) Window(minOf(a, b), maxOf(a, b)) else null
        val sunrise = at(-0.833, true)
        val sunset = at(-0.833, false)
        return Day(
            morningGolden = window(sunrise, at(6.0, true)),
            eveningGolden = window(at(6.0, false), sunset),
            morningBlue = window(at(-6.0, true), at(-4.0, true)),
            eveningBlue = window(at(-4.0, false), at(-6.0, false)),
        )
    }

    fun timeAtElevation(dayMillis: Long, latitude: Double, longitude: Double, elevationDeg: Double, morning: Boolean): Long? {
        val local = Calendar.getInstance().apply { timeInMillis = dayMillis }
        val dayOfYear = local.get(Calendar.DAY_OF_YEAR)
        val lngHour = longitude / 15.0
        val t = dayOfYear + ((if (morning) 6.0 else 18.0) - lngHour) / 24.0
        val meanAnomaly = 0.9856 * t - 3.289
        val trueLongitude = normalize(meanAnomaly + 1.916 * sinDeg(meanAnomaly) + 0.020 * sinDeg(2 * meanAnomaly) + 282.634, 360.0)
        var rightAscension = normalize(Math.toDegrees(atan(0.91764 * tanDeg(trueLongitude))), 360.0)
        rightAscension += floor(trueLongitude / 90.0) * 90.0 - floor(rightAscension / 90.0) * 90.0
        rightAscension /= 15.0
        val sinDec = 0.39782 * sinDeg(trueLongitude)
        val cosDec = cos(asin(sinDec))
        val zenith = 90.0 - elevationDeg
        val cosH = (cosDeg(zenith) - sinDec * sinDeg(latitude)) / (cosDec * cosDeg(latitude))
        if (abs(cosH) > 1.0) return null
        val hourAngle = (if (morning) 360.0 - Math.toDegrees(acos(cosH)) else Math.toDegrees(acos(cosH))) / 15.0
        val localMeanTime = hourAngle + rightAscension - 0.06571 * t - 6.622
        val utcHours = normalize(localMeanTime - lngHour, 24.0)

        val utcMidnight = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
        var result = utcMidnight + (utcHours * HOUR_MS).toLong()
        // The UTC day can differ from the local one far from Greenwich; pull the answer back next to local noon.
        val localNoon = (local.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        while (result - localNoon > 12 * HOUR_MS) result -= 24 * HOUR_MS
        while (localNoon - result > 12 * HOUR_MS) result += 24 * HOUR_MS
        return result
    }

    private fun normalize(value: Double, range: Double) = ((value % range) + range) % range
    private fun sinDeg(deg: Double) = sin(Math.toRadians(deg))
    private fun cosDeg(deg: Double) = cos(Math.toRadians(deg))
    private fun tanDeg(deg: Double) = tan(Math.toRadians(deg))
}
