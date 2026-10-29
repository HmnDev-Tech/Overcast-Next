package com.sameerasw.overcast.weather

import android.content.Context
import com.sameerasw.overcast.R
import com.sameerasw.overcast.weather.model.MinutelyPrecipitation
import kotlin.math.max

object Nowcast {
    const val RAIN_THRESHOLD_MM = 0.1

    fun message(context: Context, slots: List<MinutelyPrecipitation>, now: Long): String? {
        if (slots.isEmpty()) return null
        fun minutesUntil(slot: MinutelyPrecipitation) = max(0L, (slot.timeMillis - now) / 60_000L).toInt()
        val rainingNow = slots.first().mm >= RAIN_THRESHOLD_MM
        return if (rainingNow) {
            val stop = slots.firstOrNull { it.mm < RAIN_THRESHOLD_MM }
            if (stop == null) context.getString(R.string.nowcast_rain_continuing) else context.getString(R.string.nowcast_rain_stopping, max(5, minutesUntil(stop)))
        } else {
            slots.firstOrNull { it.mm >= RAIN_THRESHOLD_MM }?.let { context.getString(R.string.nowcast_rain_starting, max(5, minutesUntil(it))) }
        }
    }
}
