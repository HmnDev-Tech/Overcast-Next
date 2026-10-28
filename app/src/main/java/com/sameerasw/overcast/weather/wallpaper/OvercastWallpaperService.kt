package com.sameerasw.overcast.weather.wallpaper

import android.content.SharedPreferences
import android.app.KeyguardManager
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.SurfaceHolder
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.lerp
import androidx.core.content.ContextCompat
import com.sameerasw.overcast.data.repository.SettingsRepository
import com.sameerasw.overcast.ui.features.weather.WeatherPalette
import com.sameerasw.overcast.ui.features.weather.drawSkyBody
import com.sameerasw.overcast.ui.features.weather.drawableRes
import com.sameerasw.overcast.ui.features.weather.sceneTint
import com.sameerasw.overcast.utils.DeviceUtils
import com.sameerasw.overcast.weather.WeatherRepository
import com.sameerasw.overcast.weather.effects.WeatherEffectSpec
import com.sameerasw.overcast.weather.effects.WeatherEffectsRenderer
import com.sameerasw.overcast.weather.effects.WeatherSimulation
import com.sameerasw.overcast.weather.model.WeatherSnapshot
import com.sameerasw.overcast.weather.scene.SceneRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.sqrt
import androidx.compose.ui.graphics.Canvas as ComposeCanvas

class OvercastWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = WeatherEngine()

    private inner class WeatherEngine : Engine() {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private val handler = Handler(Looper.getMainLooper())
        private val settings = SettingsRepository(applicationContext)
        private val renderer = WeatherEffectsRenderer()
        private val drawScope = CanvasDrawScope()
        private val density = Density(resources.displayMetrics.density)
        private val skyPaint = Paint()
        private val scenePaint = Paint()
        private val fadePaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        private var realSnapshot: WeatherSnapshot? = null
        private var shown: Presentation? = null
        private var sceneDrawable: Drawable? = null
        private var sceneJob: Job? = null
        private var visible = false
        private var offsetX = 0.5f
        private val start = System.nanoTime()
        private var lastFrame = 0L
        private var zoom = 1f
        private var zoomVelocity = 0f
        private var systemZoom = 0f

        private val keyguard = getSystemService(KeyguardManager::class.java)
        private var locked = false
        private val power = getSystemService(PowerManager::class.java)
        private var lockCheckedAt = 0L
        private var smooth = settings.isWallpaperSmoothEnabled()

        private val frame = object : Runnable {
            override fun run() {
                drawFrame()
                scheduleNext()
            }
        }

        private val vsyncFrame = Choreographer.FrameCallback {
            drawFrame()
            scheduleNext()
        }

        private fun scheduleNext() {
            if (!visible) return
            if (smooth) Choreographer.getInstance().postFrameCallback(vsyncFrame) else handler.postDelayed(frame, FRAME_MS)
        }

        private fun stopFrames() {
            handler.removeCallbacks(frame)
            Choreographer.getInstance().removeFrameCallback(vsyncFrame)
        }

        private fun checkLock(nanos: Long) {
            if (nanos - lockCheckedAt < LOCK_CHECK_NS) return
            lockCheckedAt = nanos
            locked = keyguard?.isKeyguardLocked == true
        }

        private fun advanceZoom(dt: Float) {
            val target = if (locked) LOCKED_ZOOM else 1f
            val force = ZOOM_STIFFNESS * (target - zoom) - 2f * sqrt(ZOOM_STIFFNESS) * zoomVelocity
            zoomVelocity += force * dt
            zoom += zoomVelocity * dt
        }

        private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            shown = null
            val nextSmooth = settings.isWallpaperSmoothEnabled()
            if (nextSmooth != smooth) {
                smooth = nextSmooth
                stopFrames()
                scheduleNext()
            }
            loadScene()
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            settings.registerChangeListener(settingsListener)
            scope.launch {
                WeatherRepository.ensureLoaded(applicationContext)
                if (WeatherRepository.isStale(applicationContext)) launch { WeatherRepository.refresh(applicationContext) }
                WeatherRepository.state.collect { state ->
                    val placeChanged = state.snapshot?.locationName != realSnapshot?.locationName
                    realSnapshot = state.snapshot
                    shown = null
                    if (placeChanged || sceneDrawable == null) loadScene()
                }
            }
        }

        private fun loadScene() {
            val current = realSnapshot ?: return
            sceneJob?.cancel()
            sceneJob = scope.launch {
                val type = runCatching { SceneRepository.current(applicationContext, current.locationName) }.getOrNull()
                sceneDrawable = type?.let { ContextCompat.getDrawable(applicationContext, it.drawableRes)?.mutate() }
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            stopFrames()
            if (visible) {
                lastFrame = 0L
                lockCheckedAt = 0L
                handler.post(frame)
            } else if (power?.isInteractive == false) {
                zoom = 0f
                zoomVelocity = 0f
            }
        }

        override fun onZoomChanged(zoom: Float) {
            systemZoom = zoom.coerceIn(0f, 1f)
            if (!visible) drawFrame()
        }

        override fun onOffsetsChanged(xOffset: Float, yOffset: Float, xOffsetStep: Float, yOffsetStep: Float, xPixelOffset: Int, yPixelOffset: Int) {
            offsetX = xOffset
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            drawFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            stopFrames()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            stopFrames()
            settings.unregisterChangeListener(settingsListener)
            scope.cancel()
            super.onDestroy()
        }

        private fun present(now: Long): Presentation {
            shown?.takeIf { now / 60_000L == it.minute }?.let { return it }
            val simulation = settings.getSimulatedWeather()
            val timeOverride = settings.getSimulatedTimeOfDay()
            val tempOverride = settings.getSimulatedTempC()
            val snapshot = realSnapshot
                ?.let { real -> simulation?.let { WeatherSimulation.apply(real, it) } ?: real }
                ?.let { WeatherSimulation.withTimeOfDay(it, timeOverride) }
                ?.let { s -> tempOverride?.let { s.copy(tempC = it) } ?: s }
            val clock = WeatherSimulation.timeFor(snapshot, timeOverride, now)
            val effects = snapshot != null && settings.isWallpaperEffectsEnabled() && settings.isWeatherEffectsEnabled() && !DeviceUtils.isPowerSaveMode(applicationContext)
            val spec = if (effects && snapshot != null) simulation?.spec ?: WeatherEffectSpec.from(snapshot) else WeatherEffectSpec.None
            return Presentation(snapshot, WeatherPalette.from(snapshot, clock, timeOverride), spec, clock, now / 60_000L).also { shown = it }
        }

        private fun drawFrame() {
            val holder = surfaceHolder
            val canvas = runCatching { holder.lockHardwareCanvas() }.getOrNull() ?: runCatching { holder.lockCanvas() }.getOrNull() ?: return
            try {
                draw(canvas)
            } finally {
                runCatching { holder.unlockCanvasAndPost(canvas) }
            }
        }

        private fun draw(canvas: Canvas) {
            val width = canvas.width.toFloat()
            val height = canvas.height.toFloat()
            val now = System.currentTimeMillis()
            val nanos = System.nanoTime()
            checkLock(nanos)
            val dt = if (lastFrame == 0L) 0f else ((nanos - lastFrame) / 1_000_000_000f).coerceIn(0f, 0.1f)
            lastFrame = nanos
            advanceZoom(dt)
            val presentation = present(now)
            val palette = presentation.palette
            val intro = zoom * (1f - systemZoom * OVERLAY_DEPTH)

            skyPaint.shader = LinearGradient(
                0f, 0f, 0f, height,
                intArrayOf(palette.glow.toArgb(), palette.glowSecondary.toArgb(), palette.base.toArgb(), palette.base.toArgb()),
                floatArrayOf(0f, 0.4f, 0.8f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, width, height, skyPaint)

            val composeCanvas = ComposeCanvas(canvas)
            presentation.snapshot?.let { snapshot ->
                val skyHeight = height * SKY_FRACTION
                canvas.save()
                lerp(SKY_START_SCALE, 1f, intro).let { canvas.scale(it, it, width / 2f, skyHeight * 0.66f) }
                drawScope.draw(density, LayoutDirection.Ltr, composeCanvas, Size(width, skyHeight)) { drawSkyBody(snapshot, presentation.clock) }
                canvas.restore()
            }

            sceneDrawable?.let { drawable ->
                val bottom = height * SCENE_BOTTOM
                canvas.save()
                lerp(SCENE_START_SCALE, 1f, intro).let { canvas.scale(it, it, width / 2f, bottom) }
                drawScene(canvas, drawable, palette, width, bottom)
                canvas.restore()
            }

            val t = (nanos - start) / 1_000_000_000f
            renderer.update(presentation.spec)
            canvas.save()
            lerp(EFFECTS_START_SCALE, 1f, intro).let { canvas.scale(it, it, width / 2f, height / 2f) }
            drawScope.draw(density, LayoutDirection.Ltr, composeCanvas, Size(width, height)) { renderer.draw(this, t, dt, EFFECT_STRENGTH) }
            canvas.restore()
        }

        private fun drawScene(canvas: Canvas, drawable: Drawable, palette: WeatherPalette, width: Float, bottom: Float) {
            val top = bottom - width * SCENE_ASPECT
            val shift = (offsetX - 0.5f) * -width * PARALLAX
            val bleed = width * PARALLAX
            drawable.setTint(sceneTint(palette).toArgb())
            drawable.setBounds((-bleed + shift).toInt(), top.toInt(), (width + bleed + shift).toInt(), bottom.toInt())
            scenePaint.alpha = (SCENE_ALPHA * 255).toInt()
            val layer = canvas.saveLayer(-bleed, top, width + bleed, bottom, scenePaint)
            drawable.draw(canvas)
            fadePaint.shader = LinearGradient(
                0f, top, 0f, bottom,
                intArrayOf(android.graphics.Color.BLACK, android.graphics.Color.BLACK, android.graphics.Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(-bleed, top, width + bleed, bottom, fadePaint)
            canvas.restoreToCount(layer)
        }
    }

    private class Presentation(
        val snapshot: WeatherSnapshot?,
        val palette: WeatherPalette,
        val spec: WeatherEffectSpec,
        val clock: Long,
        val minute: Long,
    )

    private companion object {
        const val FRAME_MS = 33L
        const val LOCK_CHECK_NS = 150_000_000L
        const val EFFECT_STRENGTH = 1.7f
        const val SKY_FRACTION = 0.42f
        const val SCENE_ASPECT = 140f / 360f
        const val SCENE_BOTTOM = 0.56f
        const val SCENE_ALPHA = 0.85f
        const val PARALLAX = 0.06f
        const val LOCKED_ZOOM = 0.55f
        const val OVERLAY_DEPTH = 0.6f
        const val ZOOM_STIFFNESS = 22f
        const val SKY_START_SCALE = 1.35f
        const val SCENE_START_SCALE = 0.9f
        const val EFFECTS_START_SCALE = 1.12f
    }
}
