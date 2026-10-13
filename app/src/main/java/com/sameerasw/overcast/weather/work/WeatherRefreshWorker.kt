package com.sameerasw.overcast.weather.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sameerasw.overcast.data.repository.SettingsRepository
import com.sameerasw.overcast.weather.WeatherRepository
import com.sameerasw.overcast.weather.provider.WeatherProviders
import java.util.concurrent.TimeUnit

class WeatherRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        WeatherRepository.refresh(applicationContext)
        return Result.success()
    }
}

object WeatherScheduler {
    private const val WORK_NAME = "weather_refresh"

    fun schedule(context: Context, intervalMinutes: Int) {
        // Yandex allows 30 requests a day, so its provider sets a longer minimum.
        val provider = WeatherProviders.byId(SettingsRepository(context).getWeatherProvider())
        val minutes = maxOf(intervalMinutes, 15, provider.minRefreshMinutes)
        val request = PeriodicWorkRequestBuilder<WeatherRefreshWorker>(minutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
