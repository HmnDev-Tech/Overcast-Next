package com.sameerasw.overcast.weather.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sameerasw.overcast.R
import com.sameerasw.overcast.data.repository.SettingsRepository
import com.sameerasw.overcast.ui.activities.WeatherActivity
import com.sameerasw.overcast.weather.WeatherFormat
import com.sameerasw.overcast.weather.WeatherRepository
import com.sameerasw.overcast.weather.WeatherUnits
import com.sameerasw.overcast.weather.model.WeatherSnapshot
import com.sameerasw.overcast.weather.sky.AuroraForecast
import com.sameerasw.overcast.weather.sky.SkyEventKind
import com.sameerasw.overcast.weather.sky.SkyEvents
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.math.abs

object WeatherNotifications {
    private const val CHANNEL_SUMMARY = "daily_summary"
    private const val CHANNEL_RAIN = "rain_soon"
    private const val CHANNEL_SKY = "sky_events"
    private const val ID_SKY_BASE = 3200
    private const val SKY_PREFS = "sky_alerts"
    private const val KEY_SKY_SENT = "sent"
    private const val HOUR_MS = 60 * 60_000L
    private const val ID_SUMMARY = 3101
    private const val ID_RAIN = 3102
    private const val SUMMARY_WORK = "morning_summary"
    private const val RAIN_THRESHOLD_MM = 0.1
    private const val RAIN_CHANCE = 60
    private const val RAIN_LOOK_AHEAD_MS = 45 * 60_000L
    // A new spell has to start this long after the last alerted one before it counts as new rain.
    private const val RAIN_COOLDOWN_MS = 3 * 60 * 60_000L

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SUMMARY, context.getString(R.string.notify_channel_summary), NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RAIN, context.getString(R.string.notify_channel_rain), NotificationManager.IMPORTANCE_HIGH),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SKY, context.getString(R.string.notify_channel_sky), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun post(context: Context, channel: String, id: Int, title: String, text: String, picture: Bitmap? = null): Boolean {
        if (!canPost(context)) return false
        ensureChannels(context)
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, WeatherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.rounded_cloud_24)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(
                if (picture != null) {
                    NotificationCompat.BigPictureStyle().bigPicture(picture).setSummaryText(text)
                } else {
                    NotificationCompat.BigTextStyle().bigText(text)
                },
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(id, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun scheduleSummary(context: Context) {
        val settings = SettingsRepository(context)
        val work = WorkManager.getInstance(context)
        if (!settings.isMorningSummaryEnabled()) {
            work.cancelUniqueWork(SUMMARY_WORK)
            return
        }
        val now = Calendar.getInstance()
        val next = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, settings.getMorningSummaryHour())
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        val request = PeriodicWorkRequestBuilder<MorningSummaryWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(next.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS)
            .build()
        work.enqueueUniquePeriodicWork(SUMMARY_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun postSummary(context: Context, snapshot: WeatherSnapshot): Boolean {
        val units = WeatherUnits.from(SettingsRepository(context))
        val now = System.currentTimeMillis()
        val rainFrom = snapshot.upcomingHours(now).firstOrNull { it.chanceOfRain >= RAIN_CHANCE && isToday(it.timeMillis, now) }
        val parts = buildList {
            add("${WeatherFormat.temperature(snapshot.todayHighC(now), units.temperature)} / ${WeatherFormat.temperature(snapshot.todayLowC(now), units.temperature)}")
            add(snapshot.conditionText)
            rainFrom?.let { add(context.getString(R.string.notify_rain_from, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.timeMillis)))) }
        }
        val picture = runCatching { DayPreviewRenderer.render(context, snapshot, now) }.getOrNull()
        return post(
            context,
            CHANNEL_SUMMARY,
            ID_SUMMARY,
            context.getString(R.string.notify_summary_title, snapshot.locationName),
            parts.joinToString(" · "),
            picture,
        )
    }

    // Runs after every refresh; prefers the 15-minute nowcast and falls back to the next hour's rain chance.
    fun checkRainSoon(context: Context, snapshot: WeatherSnapshot) {
        val settings = SettingsRepository(context)
        if (!settings.isRainAlertEnabled()) return
        val now = System.currentTimeMillis()
        val minutely = snapshot.extras?.minutely.orEmpty()
        val startsAt = if (minutely.isNotEmpty()) {
            if (minutely.first().mm >= RAIN_THRESHOLD_MM && minutely.first().timeMillis <= now) return
            minutely.firstOrNull { it.mm >= RAIN_THRESHOLD_MM && it.timeMillis - now in 0..RAIN_LOOK_AHEAD_MS }?.timeMillis
        } else {
            snapshot.upcomingHours(now).firstOrNull { it.chanceOfRain >= RAIN_CHANCE && it.timeMillis - now in 0..RAIN_LOOK_AHEAD_MS }?.timeMillis
        } ?: return
        if (startsAt - settings.getLastRainAlertMillis() < RAIN_COOLDOWN_MS) return
        settings.setLastRainAlertMillis(startsAt)
        val minutes = ((startsAt - now) / 60_000L).toInt().coerceAtLeast(5)
        post(context, CHANNEL_RAIN, ID_RAIN, context.getString(R.string.notify_rain_title), context.getString(R.string.nowcast_rain_starting, minutes))
    }

    suspend fun checkSkyEvents(context: Context, snapshot: WeatherSnapshot) {
        val settings = SettingsRepository(context)
        if (!settings.isSkyAlertEnabled() || !canPost(context)) return
        val now = System.currentTimeMillis()
        val latitude = WeatherRepository.lastFetchLocation()?.latitude
        val kp = if (latitude != null && abs(latitude) >= 44.0) runCatching { AuroraForecast.kpForecast() }.getOrDefault(emptyList()) else emptyList()
        val prefs = context.getSharedPreferences(SKY_PREFS, Context.MODE_PRIVATE)
        val sent = prefs.getStringSet(KEY_SKY_SENT, emptySet()).orEmpty().filter { key ->
            key.substringAfterLast(':').toLongOrNull()?.let { it * HOUR_MS > now - 30L * 24 * HOUR_MS } == true
        }.toMutableSet()
        val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        val quiet = hour >= 23 || hour < 7
        SkyEvents.upcoming(context, snapshot, latitude, kp, now).forEach { event ->
            val until = event.timeMillis - now
            val due = when (event.kind) {
                SkyEventKind.METEOR_SHOWER -> until in 0..8 * HOUR_MS
                SkyEventKind.AURORA -> until in -3 * HOUR_MS..12 * HOUR_MS
                SkyEventKind.FIRST_SNOW, SkyEventKind.SOLAR_ECLIPSE, SkyEventKind.LUNAR_ECLIPSE -> until in 0..24 * HOUR_MS
                SkyEventKind.SOLSTICE, SkyEventKind.EQUINOX -> until in -6 * HOUR_MS..12 * HOUR_MS
                SkyEventKind.NEW_MOON -> false
            }
            if (!due || (quiet && event.kind != SkyEventKind.AURORA)) return@forEach
            val key = "${event.kind.name}:${event.timeMillis / HOUR_MS}"
            if (key in sent) return@forEach
            val title = event.name?.let { context.getString(event.titleRes, it) } ?: context.getString(event.titleRes)
            val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(event.timeMillis))
            val text = when (event.kind) {
                SkyEventKind.METEOR_SHOWER -> {
                    val cloud = snapshot.hourly.minByOrNull { abs(it.timeMillis - event.timeMillis) }?.cloudCover
                    listOfNotNull(
                        context.getString(R.string.notify_sky_peaks, time),
                        cloud?.let { context.getString(if (it < 40) R.string.notify_sky_clear else R.string.notify_sky_cloudy) },
                        event.detailRes?.let { context.getString(it) },
                    ).joinToString(" · ")
                }
                SkyEventKind.AURORA -> context.getString(R.string.notify_sky_aurora, event.detail.orEmpty())
                SkyEventKind.FIRST_SNOW -> context.getString(R.string.notify_sky_snow, time)
                SkyEventKind.SOLAR_ECLIPSE, SkyEventKind.LUNAR_ECLIPSE ->
                    listOfNotNull(time, event.detail ?: event.detailRes?.let { context.getString(it) }).joinToString(" · ")
                else -> context.getString(R.string.notify_sky_today)
            }
            if (post(context, CHANNEL_SKY, ID_SKY_BASE + event.kind.ordinal, title, text)) sent += key
        }
        prefs.edit().putStringSet(KEY_SKY_SENT, sent).apply()
    }

    private fun isToday(millis: Long, now: Long): Boolean {
        val a = Calendar.getInstance().apply { timeInMillis = millis }
        val b = Calendar.getInstance().apply { timeInMillis = now }
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }
}

class MorningSummaryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        WeatherRepository.refresh(applicationContext, force = true)
        val snapshot = WeatherRepository.state.value.snapshot ?: return Result.retry()
        WeatherNotifications.postSummary(applicationContext, snapshot)
        return Result.success()
    }
}
