package com.sameerasw.overcast.weather.notify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.sameerasw.overcast.data.repository.SettingsRepository
import com.sameerasw.overcast.ui.features.weather.WeatherPalette
import com.sameerasw.overcast.weather.WeatherFormat
import com.sameerasw.overcast.weather.WeatherIconStyle
import com.sameerasw.overcast.weather.WeatherUnits
import com.sameerasw.overcast.weather.model.WeatherSnapshot
import java.text.DateFormat
import java.util.Date

// Wide day-at-a-glance image for the morning summary's expanded notification.
object DayPreviewRenderer {
    private const val WIDTH = 1200
    private const val HEIGHT = 480
    private const val HOURS = 18
    private const val LABEL_EVERY = 3
    private const val ICON_TOP = 152

    fun render(context: Context, snapshot: WeatherSnapshot, now: Long): Bitmap? {
        val hours = snapshot.upcomingHours(now).take(HOURS)
        if (hours.size < 4) return null
        val settings = SettingsRepository(context)
        val units = WeatherUnits.from(settings)
        val iconStyle = WeatherIconStyle.fromId(settings.getWeatherIconStyle())
        val palette = WeatherPalette.from(snapshot, now)
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = 48f

        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, HEIGHT.toFloat(), palette.glow.toArgb(), palette.base.toArgb(), Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(RectF(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat()), radius, radius, background)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        text.typeface = bold
        text.textSize = 44f
        canvas.drawText(snapshot.locationName, 56f, 84f, text)
        text.typeface = Typeface.DEFAULT
        text.textSize = 34f
        text.color = ColorUtils.setAlphaComponent(Color.WHITE, 200)
        val range = "${WeatherFormat.temperature(snapshot.todayHighC(now), units.temperature)} / ${WeatherFormat.temperature(snapshot.todayLowC(now), units.temperature)} · ${snapshot.conditionText}"
        canvas.drawText(range, 56f, 132f, text)

        val left = 80f
        val right = WIDTH - 80f
        val chartTop = 270f
        val chartBottom = 350f
        val step = (right - left) / (hours.size - 1)
        val minTemp = hours.minOf { it.tempC }
        val span = (hours.maxOf { it.tempC } - minTemp).coerceAtLeast(1.0)
        fun x(index: Int) = left + index * step
        fun y(temp: Double) = (chartBottom - (temp - minTemp) / span * (chartBottom - chartTop)).toFloat()

        val rainPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rainBottom = HEIGHT - 70f
        hours.forEachIndexed { index, hour ->
            if (hour.chanceOfRain <= 0) return@forEachIndexed
            val barHeight = 60f * hour.chanceOfRain / 100f
            rainPaint.color = ColorUtils.setAlphaComponent(palette.accent.toArgb(), 90 + hour.chanceOfRain)
            canvas.drawRoundRect(RectF(x(index) - 9f, rainBottom - barHeight, x(index) + 9f, rainBottom), 9f, 9f, rainPaint)
        }

        val curve = Path()
        hours.forEachIndexed { index, hour ->
            if (index == 0) {
                curve.moveTo(x(0), y(hour.tempC))
            } else {
                val px = x(index - 1)
                val py = y(hours[index - 1].tempC)
                curve.cubicTo(px + step / 2, py, x(index) - step / 2, y(hour.tempC), x(index), y(hour.tempC))
            }
        }
        canvas.drawPath(curve, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.accent.toArgb()
            style = Paint.Style.STROKE
            strokeWidth = 7f
            strokeCap = Paint.Cap.ROUND
        })

        val hourFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        text.textAlign = Paint.Align.CENTER
        hours.forEachIndexed { index, hour ->
            if (index % LABEL_EVERY != 0) return@forEachIndexed
            val cx = x(index)
            val cy = y(hour.tempC)
            canvas.drawCircle(cx, cy, 8f, dot)
            text.color = Color.WHITE
            text.typeface = bold
            text.textSize = 32f
            canvas.drawText(WeatherFormat.temperature(hour.tempC, units.temperature), cx, cy - 22f, text)
            ContextCompat.getDrawable(context, iconStyle.icon(hour.condition, hour.isDay))?.mutate()?.let { icon ->
                if (iconStyle.tintable) icon.setTint(Color.WHITE)
                icon.setBounds((cx - 26).toInt(), ICON_TOP, (cx + 26).toInt(), ICON_TOP + 52)
                icon.draw(canvas)
            }
            text.typeface = Typeface.DEFAULT
            text.textSize = 28f
            text.color = ColorUtils.setAlphaComponent(Color.WHITE, 190)
            canvas.drawText(hourFormat.format(Date(hour.timeMillis)), cx, HEIGHT - 26f, text)
        }
        return bitmap
    }
}
