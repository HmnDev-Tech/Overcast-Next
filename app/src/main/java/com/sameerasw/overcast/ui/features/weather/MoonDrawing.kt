package com.sameerasw.overcast.ui.features.weather

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

internal fun DrawScope.drawMoonPhase(center: Offset, radius: Float, phase: Double, color: Color, litAlpha: Float, darkAlpha: Float) {
    drawCircle(color.copy(alpha = darkAlpha), radius = radius, center = center)
    val waxing = phase < 0.5
    val terminator = cos(2 * PI * phase).toFloat()
    val lit = Path().apply {
        val disc = Rect(center - Offset(radius, radius), Size(radius * 2, radius * 2))
        arcTo(disc, -90f, 180f, true)
        val width = abs(terminator) * radius
        val ellipse = Rect(center.x - width, center.y - radius, center.x + width, center.y + radius)
        if (terminator >= 0f) arcTo(ellipse, 90f, -180f, false) else arcTo(ellipse, 90f, 180f, false)
        close()
    }
    scale(if (waxing) 1f else -1f, 1f, pivot = center) {
        drawPath(lit, color.copy(alpha = litAlpha))
    }
}
