package com.sameerasw.overcast.weather.island

import android.content.Context
import android.graphics.Bitmap
import android.text.format.DateFormat
import com.sameerasw.overcast.R
import com.sameerasw.overcast.data.repository.SettingsRepository
import com.sameerasw.overcast.weather.WeatherFormat
import com.sameerasw.overcast.weather.WeatherIconSlot
import com.sameerasw.overcast.weather.WeatherIconStyle
import com.sameerasw.overcast.weather.WeatherUnits
import com.sameerasw.overcast.weather.model.WeatherAlert
import com.sameerasw.overcast.weather.model.WeatherSnapshot
import com.sameerasw.island.sdk.BriefCardPayload
import com.sameerasw.island.sdk.IslandAction
import com.sameerasw.island.sdk.IslandActionType
import com.sameerasw.island.sdk.IslandCellPayload
import com.sameerasw.island.sdk.IslandExpandedPayload
import com.sameerasw.island.sdk.IslandInteractionsPayload
import com.sameerasw.island.sdk.IslandItemPayload
import com.sameerasw.island.sdk.IslandLinePayload
import com.sameerasw.island.sdk.IslandPayload
import com.sameerasw.island.sdk.IslandPlacement
import com.sameerasw.island.sdk.IslandTone
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WeatherIslandContent(val payload: IslandPayload, val assets: Map<String, Bitmap>)

object WeatherIslandPayload {
    const val ITEM_KEY = "weather"
    const val BRIEF_TARGET = "brief"

    private const val PRIORITY = 58
    private const val PRIORITY_ALERT = 48
    private const val HEIGHT_DP = 304
    private const val HEIGHT_ALERT_DP = 378

    fun severeAlert(snapshot: WeatherSnapshot): WeatherAlert? =
        snapshot.activeAlerts().filter { it.severity.isSevere }.maxByOrNull { it.severity.ordinal }

    fun build(context: Context, settings: SettingsRepository, snapshot: WeatherSnapshot?): WeatherIslandContent {
        if (snapshot == null) return WeatherIslandContent(IslandPayload(), emptyMap())
        val unit = WeatherUnits.from(settings).temperature
        val style = WeatherIconStyle.fromId(settings.getWeatherIconStyle())
        val alert = severeAlert(snapshot)
        val temperature = WeatherFormat.temperature(snapshot.tempC, unit)

        val assets = HashMap<String, Bitmap>()
        val iconId = if (alert != null) {
            WeatherIslandIcons.bitmap(context, R.drawable.rounded_warning_24)?.also { assets[ICON_ALERT] = it }
            ICON_ALERT
        } else {
            val id = "condition.${WeatherIconSlot.of(snapshot.condition, snapshot.isDay).name}"
            WeatherIslandIcons.bitmap(context, style.icon(snapshot.condition, snapshot.isDay))?.also { assets[id] = it }
            id
        }
        val tone = when {
            alert != null -> IslandTone.ALERT
            style.tintable -> IslandTone.ACCENT
            else -> IslandTone.NONE
        }

        val lineEnd = if (alert != null) {
            alert.expiresMillis?.let { context.getString(R.string.weather_alert_until, formatTime(context, it)) } ?: temperature
        } else {
            listOf(temperature, snapshot.conditionText).filter { it.isNotBlank() }.joinToString(" · ")
        }

        val item = IslandItemPayload(
            key = ITEM_KEY,
            priority = PRIORITY,
            priorityOverride = PRIORITY_ALERT.takeIf { alert != null },
            placement = IslandPlacement.DYNAMIC,
            compact = listOf(
                IslandCellPayload(key = "weather.icon", iconId = iconId, tone = tone),
                IslandCellPayload(key = "weather.temp", text = temperature, tone = IslandTone.NONE),
            ),
            line = IslandLinePayload(
                start = alert?.event ?: snapshot.locationName,
                end = lineEnd,
                iconId = iconId,
                tone = tone,
            ),
            expanded = IslandExpandedPayload(heightDp = if (alert != null) HEIGHT_ALERT_DP else HEIGHT_DP),
            interactions = IslandInteractionsPayload(
                onTap = IslandAction(IslandActionType.EXPAND_ITEM, BRIEF_TARGET),
                onLongPress = IslandAction(IslandActionType.OPEN_APP),
                onExpandedTap = IslandAction(IslandActionType.OPEN_APP),
            ),
        )
        val trailingId = if (alert != null) ICON_ALERT else RAIN_ICON.takeIf { (snapshot.chanceOfRain ?: 0) > 0 }
        if (alert == null && trailingId != null) {
            WeatherIslandIcons.bitmap(context, R.drawable.rounded_rainy_24)?.also { assets[RAIN_ICON] = it }
        }
        val card = BriefCardPayload(
            key = ITEM_KEY,
            title = temperature,
            subtitle = alert?.event ?: snapshot.conditionText,
            iconId = iconId,
            tone = if (style.tintable) IslandTone.ACCENT else IslandTone.NONE,
            trailingIconId = trailingId,
            trailingText = snapshot.chanceOfRain?.let { "$it%" }.takeIf { alert == null && trailingId != null },
            trailingTone = if (alert != null) IslandTone.ALERT else IslandTone.ACCENT,
            pageHeightDp = if (alert != null) HEIGHT_ALERT_DP else HEIGHT_DP,
        )
        return WeatherIslandContent(IslandPayload(items = listOf(item), briefCards = listOf(card)), assets)
    }

    private const val ICON_ALERT = "alert"
    private const val RAIN_ICON = "rain"

    fun formatTime(context: Context, millis: Long): String {
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
    }
}
