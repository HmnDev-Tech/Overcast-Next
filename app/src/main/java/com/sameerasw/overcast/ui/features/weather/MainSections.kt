package com.sameerasw.overcast.ui.features.weather

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.sameerasw.overcast.R
import com.sameerasw.overcast.data.repository.SettingsRepository

enum class MainSection(val key: String, val iconRes: Int, val titleRes: Int) {
    TREND("section_trend", R.drawable.rounded_show_chart_24, R.string.trend_title),
    DETAILS("section_details", R.drawable.rounded_dashboard_24, R.string.section_details),
    ACTIVITIES("section_activities", R.drawable.rounded_directions_run_24, R.string.activity_title),
    INSIGHTS("section_insights", R.drawable.rounded_insights_24, R.string.insights_title),
    RADAR("section_radar", R.drawable.rounded_radar_24, R.string.section_radar),
    AIR_QUALITY("section_air_quality", R.drawable.rounded_air_24, R.string.aqi_title),
    MARINE("section_marine", R.drawable.rounded_waves_24, R.string.marine_title),
    RIVER("section_river", R.drawable.rounded_flood_24, R.string.river_title),
    HISTORY("section_history", R.drawable.rounded_history_24, R.string.history_title),
    PLACES("section_places", R.drawable.rounded_location_city_24, R.string.places_title),
    SUN("section_sun", R.drawable.rounded_wb_sunny_24, R.string.section_sun),
    SKY_EVENTS("section_sky_events", R.drawable.rounded_auto_awesome_24, R.string.sky_events_title),
}

@Composable
internal fun rememberVisibleSections(): Set<MainSection> {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }
    fun read() = MainSection.entries.filter { settings.isMainSectionVisible(it) }.toSet()
    var visible by remember { mutableStateOf(read()) }
    DisposableEffect(context) {
        val prefs = context.getSharedPreferences(SettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null && MainSection.entries.any { it.key == key }) visible = read()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return visible
}
