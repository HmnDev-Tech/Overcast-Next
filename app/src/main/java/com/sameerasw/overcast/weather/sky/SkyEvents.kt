package com.sameerasw.overcast.weather.sky

import android.content.Context
import com.sameerasw.overcast.R
import com.sameerasw.overcast.weather.MoonPhase
import com.sameerasw.overcast.weather.model.WeatherCondition
import com.sameerasw.overcast.weather.model.WeatherSnapshot
import com.sameerasw.overcast.weather.provider.ProviderHttp
import org.json.JSONArray
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.pow

enum class SkyEventKind(val iconRes: Int) {
    METEOR_SHOWER(R.drawable.rounded_auto_awesome_24),
    SOLSTICE(R.drawable.rounded_wb_sunny_24),
    EQUINOX(R.drawable.rounded_wb_sunny_24),
    NEW_MOON(R.drawable.rounded_bedtime_24),
    SOLAR_ECLIPSE(R.drawable.rounded_wb_sunny_24),
    LUNAR_ECLIPSE(R.drawable.rounded_bedtime_24),
    AURORA(R.drawable.round_star_24),
    FIRST_SNOW(R.drawable.rounded_weather_snowy_24),
}

class SkyEvent(val kind: SkyEventKind, val timeMillis: Long, val titleRes: Int, val name: String? = null, val detailRes: Int? = null, val detail: String? = null) {
    val searchable: Boolean get() = kind in SEARCHABLE
}

private val SEARCHABLE = setOf(SkyEventKind.METEOR_SHOWER, SkyEventKind.SOLAR_ECLIPSE, SkyEventKind.LUNAR_ECLIPSE, SkyEventKind.SOLSTICE, SkyEventKind.EQUINOX)

private class Shower(val month: Int, val day: Int, val name: String)

private class Eclipse(val utc: String, val solar: Boolean, val typeRes: Int, val where: String?)

object SkyEvents {
    private const val DAY_MS = 24 * 60 * 60_000L
    private const val WINDOW_DAYS = 45
    private const val ECLIPSE_WINDOW_DAYS = 120
    private const val PAST_DAYS = 14
    private const val PREFS = "sky_events"
    private const val KEY_SNOW_SEASON = "snow_season"

    private val showers = listOf(
        Shower(1, 3, "Quadrantids"),
        Shower(4, 22, "Lyrids"),
        Shower(5, 6, "Eta Aquariids"),
        Shower(7, 30, "Delta Aquariids"),
        Shower(8, 12, "Perseids"),
        Shower(10, 8, "Draconids"),
        Shower(10, 21, "Orionids"),
        Shower(11, 17, "Leonids"),
        Shower(12, 14, "Geminids"),
        Shower(12, 22, "Ursids"),
    )

    private val eclipses = listOf(
        Eclipse("2026-08-12T17:46", true, R.string.sky_eclipse_total, "Greenland, Iceland, Spain"),
        Eclipse("2026-08-28T04:13", false, R.string.sky_eclipse_partial, null),
        Eclipse("2027-02-06T16:00", true, R.string.sky_eclipse_annular, "South America, West Africa"),
        Eclipse("2027-02-20T23:13", false, R.string.sky_eclipse_penumbral, null),
        Eclipse("2027-07-18T16:03", false, R.string.sky_eclipse_penumbral, null),
        Eclipse("2027-08-02T10:07", true, R.string.sky_eclipse_total, "Spain, North Africa, Middle East"),
        Eclipse("2027-08-17T07:14", false, R.string.sky_eclipse_penumbral, null),
        Eclipse("2028-01-12T04:13", false, R.string.sky_eclipse_partial, null),
        Eclipse("2028-01-26T15:08", true, R.string.sky_eclipse_annular, "Ecuador, Peru, Brazil, Spain, Portugal"),
        Eclipse("2028-07-06T18:20", false, R.string.sky_eclipse_partial, null),
        Eclipse("2028-07-22T02:56", true, R.string.sky_eclipse_total, "Australia, New Zealand"),
        Eclipse("2028-12-31T16:52", false, R.string.sky_eclipse_total, null),
    )

    fun upcoming(context: Context, snapshot: WeatherSnapshot, latitude: Double?, auroraKp: List<Pair<Long, Double>>, now: Long): List<SkyEvent> {
        val events = mutableListOf<SkyEvent>()
        val horizon = now + WINDOW_DAYS * DAY_MS
        val since = now - PAST_DAYS * DAY_MS
        val year = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR)

        for (y in year - 1..year + 1) for (shower in showers) {
            val peak = Calendar.getInstance().apply {
                clear()
                set(y, shower.month - 1, shower.day, 22, 0)
            }.timeInMillis
            if (peak > since && peak < horizon) {
                val moonBright = MoonPhase.illumination(MoonPhase.compute(peak)) > 0.6
                events += SkyEvent(SkyEventKind.METEOR_SHOWER, peak, R.string.sky_meteor_shower, shower.name, if (moonBright) R.string.sky_bright_moon else null)
            }
        }

        for (y in year - 1..year + 1) for (season in 0..3) {
            val at = seasonStart(y, season)
            if (at <= since || at >= horizon) continue
            val north = (latitude ?: 1.0) >= 0
            val res = when (season) {
                0 -> if (north) R.string.sky_spring_equinox else R.string.sky_autumn_equinox
                1 -> if (north) R.string.sky_summer_solstice else R.string.sky_winter_solstice
                2 -> if (north) R.string.sky_autumn_equinox else R.string.sky_spring_equinox
                else -> if (north) R.string.sky_winter_solstice else R.string.sky_summer_solstice
            }
            events += SkyEvent(if (season % 2 == 0) SkyEventKind.EQUINOX else SkyEventKind.SOLSTICE, at, res)
        }

        val phase = MoonPhase.compute(now)
        val synodicMs = MoonPhase.SYNODIC_DAYS * DAY_MS
        val toNew = ((1.0 - phase) % 1.0 + 1.0) % 1.0
        events += SkyEvent(SkyEventKind.NEW_MOON, now + (toNew * synodicMs).toLong(), R.string.sky_new_moon, detailRes = R.string.sky_dark_skies)

        val eclipseHorizon = now + ECLIPSE_WINDOW_DAYS * DAY_MS
        eclipses.forEach { eclipse ->
            val at = LocalDateTime.parse(eclipse.utc).toInstant(ZoneOffset.UTC).toEpochMilli()
            if (at <= since || at >= eclipseHorizon) return@forEach
            events += SkyEvent(
                if (eclipse.solar) SkyEventKind.SOLAR_ECLIPSE else SkyEventKind.LUNAR_ECLIPSE,
                at,
                if (eclipse.solar) R.string.sky_solar_eclipse else R.string.sky_lunar_eclipse,
                context.getString(eclipse.typeRes),
                detailRes = if (eclipse.where == null) R.string.sky_where_night else null,
                detail = eclipse.where,
            )
        }

        firstSnow(context, snapshot, latitude, now)?.let { events += SkyEvent(SkyEventKind.FIRST_SNOW, it, R.string.sky_first_snow) }

        if (latitude != null) {
            val needed = auroraKpNeeded(latitude)
            auroraKp.firstOrNull { it.first > now - 3 * 60 * 60_000L && it.second >= needed }?.let {
                events += SkyEvent(SkyEventKind.AURORA, maxOf(it.first, now), R.string.sky_aurora, detail = "Kp ${"%.0f".format(it.second)}")
            }
        }

        return events.sortedBy { it.timeMillis }
    }

    // Meeus, Astronomical Algorithms, table 27.b (mean equinoxes and solstices, years 2000–3000).
    private fun seasonStart(year: Int, season: Int): Long {
        val y = (year - 2000) / 1000.0
        val jde = when (season) {
            0 -> 2451623.80984 + 365242.37404 * y + 0.05169 * y.pow(2) - 0.00411 * y.pow(3) - 0.00057 * y.pow(4)
            1 -> 2451716.56767 + 365241.62603 * y + 0.00325 * y.pow(2) + 0.00888 * y.pow(3) - 0.00030 * y.pow(4)
            2 -> 2451810.21715 + 365242.01767 * y - 0.11575 * y.pow(2) + 0.00337 * y.pow(3) + 0.00078 * y.pow(4)
            else -> 2451900.05952 + 365242.74049 * y - 0.06223 * y.pow(2) - 0.00823 * y.pow(3) + 0.00032 * y.pow(4)
        }
        return ((jde - 2440587.5) * DAY_MS).toLong()
    }

    private fun auroraKpNeeded(latitude: Double): Double = when (abs(latitude)) {
        in 64.0..90.0 -> 3.0
        in 60.0..64.0 -> 4.0
        in 56.0..60.0 -> 5.0
        in 52.0..56.0 -> 6.0
        in 48.0..52.0 -> 7.0
        in 44.0..48.0 -> 8.0
        else -> 9.0
    }

    private fun snowSeason(millis: Long, latitude: Double?): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        val year = cal.get(Calendar.YEAR)
        return if ((latitude ?: 1.0) >= 0) (if (cal.get(Calendar.MONTH) >= Calendar.JULY) year else year - 1) else year
    }

    private fun isSnow(condition: WeatherCondition) = condition == WeatherCondition.SNOW

    private fun firstSnow(context: Context, snapshot: WeatherSnapshot, latitude: Double?, now: Long): Long? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val season = snowSeason(now, latitude)
        if (isSnow(snapshot.condition)) {
            prefs.edit().putInt(KEY_SNOW_SEASON, season).apply()
            return null
        }
        if (prefs.getInt(KEY_SNOW_SEASON, Int.MIN_VALUE) == season) return null
        val hourly = snapshot.upcomingHours(now).firstOrNull { (it.snowfallCm ?: 0.0) >= 0.2 || isSnow(it.condition) }?.timeMillis
        val daily = snapshot.upcomingDays(now).firstOrNull { (it.snowfallCm ?: 0.0) >= 0.5 || isSnow(it.condition) }?.dayMillis
        return listOfNotNull(hourly, daily).minOrNull()
    }
}

object AuroraForecast {
    private const val TTL_MS = 3 * 60 * 60_000L
    private var cached: Pair<Long, List<Pair<Long, Double>>>? = null

    suspend fun kpForecast(): List<Pair<Long, Double>> {
        cached?.takeIf { System.currentTimeMillis() - it.first < TTL_MS }?.let { return it.second }
        val rows = JSONArray(ProviderHttp.get("https://services.swpc.noaa.gov/products/noaa-planetary-k-index-forecast.json"))
        val points = (0 until rows.length()).mapNotNull { i ->
            val item = rows.optJSONObject(i)
            val array = rows.optJSONArray(i)
            val stamp = item?.optString("time_tag") ?: array?.optString(0) ?: return@mapNotNull null
            val kp = (item?.optDouble("kp") ?: array?.optString(1)?.toDoubleOrNull())?.takeIf { !it.isNaN() } ?: return@mapNotNull null
            val time = runCatching { LocalDateTime.parse(stamp.replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull() ?: return@mapNotNull null
            time to kp
        }
        cached = System.currentTimeMillis() to points
        return points
    }
}
