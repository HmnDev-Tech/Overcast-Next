/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Weather - Widget
 * File: ExpressiveWidget.kt
 * Description: Material 3 Expressive Glance widget with an animated hourly forecast reveal.
 */

package com.sameerasw.overcast.weather.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.unit.ColorProvider
import com.sameerasw.overcast.R
import com.sameerasw.overcast.data.repository.SettingsRepository
import com.sameerasw.overcast.weather.WeatherFormat
import com.sameerasw.overcast.weather.WeatherIconSlot
import com.sameerasw.overcast.weather.WeatherIconStyle
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val ANIM_STEPS = 12
private const val ANIM_FRAME_MS = 80L

// Glance has no in-composition animation, so the reveal is driven by re-rendering a few
// frames whenever the widget data updates. The sequence is short and stops by itself.
internal object ExpressiveWidgetAnimator {
    private val _progress = MutableStateFlow(1f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun play(context: Context) {
        val app = context.applicationContext
        if (!SettingsRepository(app).isWidgetExpressiveAnimationEnabled()) return
        if (job?.isActive == true) return
        job = scope.launch {
            for (step in 0..ANIM_STEPS) {
                _progress.value = step / ANIM_STEPS.toFloat()
                runCatching { ExpressiveWidget().updateAll(app) }
                delay(ANIM_FRAME_MS)
            }
        }
    }
}

class ExpressiveWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        WidgetHub.refresh(context)
        provideContent {
            GlanceTheme {
                ExpressiveRoot()
            }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val style = WeatherIconStyle.fromId(SettingsRepository(context).getWeatherIconStyle())
        val sample = listOf(
            Triple(context.getString(R.string.widget_hourly_now), "21°", WeatherIconSlot.PARTLY_CLOUDY_DAY),
            Triple("1pm", "22°", WeatherIconSlot.CLEAR_DAY),
            Triple("2pm", "23°", WeatherIconSlot.CLEAR_DAY),
            Triple("3pm", "22°", WeatherIconSlot.CLOUDY),
            Triple("4pm", "20°", WeatherIconSlot.RAIN),
        ).map { ForecastItem(it.first, style.icon(it.third), it.second) }
        provideContent {
            GlanceTheme {
                ExpressiveContent(
                    temperature = "21°",
                    condition = "Partly cloudy",
                    items = sample,
                    tintable = style.tintable,
                    icon = style.icon(WeatherIconSlot.PARTLY_CLOUDY_DAY),
                    progress = 1f,
                    emptyText = "",
                    background = GlanceModifier.background(ColorProvider(Color(0xFF14609F))).cornerRadius(WIDGET_CORNER),
                )
            }
        }
    }
}

class ExpressiveWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ExpressiveWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val store = WidgetConfigStore(context)
        appWidgetIds.forEach { store.remove(it) }
    }
}

@Composable
private fun ExpressiveRoot() {
    val context = LocalContext.current
    val weather by WidgetHub.weather.collectAsState()
    val progress by ExpressiveWidgetAnimator.progress.collectAsState()
    val current = weather ?: return
    val items = remember(current) { hourlyItems(context, current).take(8) }
    val snapshot = current.snapshot
    ExpressiveContent(
        temperature = snapshot?.let { WeatherFormat.temperature(it.tempC, current.units.temperature) } ?: "--°",
        condition = snapshot?.conditionText.orEmpty(),
        items = items,
        tintable = current.style.tintable,
        icon = snapshot?.let { current.style.icon(it.condition, it.isDay) } ?: current.style.icon(WeatherIconSlot.CLOUDY),
        progress = progress,
        emptyText = context.getString(R.string.widget_weather_empty),
    )
}

@Composable
private fun ExpressiveContent(
    temperature: String,
    condition: String,
    items: List<ForecastItem>,
    tintable: Boolean,
    icon: Int,
    progress: Float,
    emptyText: String,
    background: GlanceModifier = GlanceModifier,
) {
    val context = LocalContext.current
    val size = LocalSize.current
    val density = context.resources.displayMetrics.density
    val fontScale = context.resources.configuration.fontScale
    val mode = WidgetBackground.MATERIAL
    val baseArgb = widgetTextArgb(mode)
    // The reveal ramps opacity in and lets the hour chips appear one by one.
    val animAlpha = (progress * 255).roundToInt().coerceIn(0, 255)
    val animatedArgb = ColorUtils.setAlphaComponent(baseArgb, animAlpha)
    val revealed = ceil(progress * items.size).roundToInt().coerceIn(0, items.size)

    val tempWidthPx = (size.width.value * 0.62f * density).toInt().coerceAtLeast(1)
    val tempHeightPx = (size.height.value * 0.4f * density).toInt().coerceAtLeast(1)
    val tempBitmap = remember(temperature, tempWidthPx, tempHeightPx, animatedArgb) {
        WidgetTemperatureRenderer.render(
            context, temperature, tempWidthPx, tempHeightPx, animatedArgb,
            weightAxis = 800, shadow = false,
        )
    }
    val conditionWidthPx = (size.width.value * 0.7f * density).toInt().coerceAtLeast(1)
    val conditionBitmap = remember(condition, conditionWidthPx, animatedArgb) {
        WidgetTemperatureRenderer.renderText(
            context, condition.ifBlank { " " }, 13f * density * fontScale, conditionWidthPx, animatedArgb, shadow = false,
        )
    }

    WidgetSurface(mode, weather = null, background = background) {
        if (items.isEmpty() && condition.isBlank()) {
            val emptyBitmap = remember(emptyText, baseArgb) {
                WidgetTemperatureRenderer.renderText(
                    context, emptyText, 14f * density * fontScale, (size.width.value * density).toInt(), baseArgb, shadow = false,
                )
            }
            Column(
                modifier = GlanceModifier.fillMaxSize().padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(provider = ImageProvider(emptyBitmap), contentDescription = emptyText)
            }
        } else {
            Column(
                modifier = GlanceModifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        provider = ImageProvider(icon),
                        contentDescription = null,
                        colorFilter = if (tintable) ColorFilter.tint(widgetTextColor(mode)) else null,
                        modifier = GlanceModifier.size(22.dp),
                    )
                    Spacer(GlanceModifier.size(6.dp))
                    Image(provider = ImageProvider(conditionBitmap), contentDescription = condition)
                }
                Spacer(GlanceModifier.size(2.dp))
                Image(
                    provider = ImageProvider(tempBitmap),
                    contentDescription = temperature,
                    contentScale = ContentScale.Fit,
                    modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                )
                if (revealed > 0) {
                    Spacer(GlanceModifier.size(6.dp))
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        items.take(revealed).forEachIndexed { index, item ->
                            ExpressiveHourChip(item, index == 0, tintable, mode, density, fontScale, GlanceModifier.defaultWeight())
                            if (index != revealed - 1) Spacer(GlanceModifier.size(4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpressiveHourChip(
    item: ForecastItem,
    isNow: Boolean,
    tintable: Boolean,
    mode: WidgetBackground,
    density: Float,
    fontScale: Float,
    modifier: GlanceModifier = GlanceModifier,
) {
    val context = LocalContext.current
    val textArgb = widgetTextArgb(mode)
    val labelWidthPx = (56f * density).toInt()
    val tempWidthPx = (56f * density).toInt()
    val labelBitmap = remember(item.label, textArgb) {
        WidgetTemperatureRenderer.renderText(context, item.label, 10f * density * fontScale, labelWidthPx, textArgb, shadow = false)
    }
    val tempBitmap = remember(item.primary, textArgb) {
        WidgetTemperatureRenderer.render(context, item.primary, tempWidthPx, (14f * density).toInt(), textArgb, weightAxis = 700, shadow = false)
    }
    // The "now" chip is filled with the theme accent, the rest stay neutral, echoing the
    // Material 3 Expressive connected button group treatment.
    val chipColor = if (isNow) GlanceTheme.colors.primary else ColorProvider(Color.White.copy(alpha = 0.10f))
    Column(
        modifier = modifier
            .cornerRadius(18.dp)
            .background(chipColor)
            .padding(horizontal = 2.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(provider = ImageProvider(labelBitmap), contentDescription = item.label)
        Spacer(GlanceModifier.size(2.dp))
        Image(
            provider = ImageProvider(item.icon),
            contentDescription = null,
            colorFilter = if (tintable) ColorFilter.tint(widgetTextColor(mode)) else null,
            modifier = GlanceModifier.size(16.dp),
        )
        Spacer(GlanceModifier.size(2.dp))
        Image(provider = ImageProvider(tempBitmap), contentDescription = item.primary)
    }
}
