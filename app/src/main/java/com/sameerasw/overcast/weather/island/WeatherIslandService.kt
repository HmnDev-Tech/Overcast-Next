package com.sameerasw.overcast.weather.island

import android.content.SharedPreferences
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import com.sameerasw.overcast.ui.theme.Typography
import androidx.compose.ui.platform.LocalContext
import com.sameerasw.island.sdk.HostInfo
import com.sameerasw.island.sdk.IslandHostHandle
import com.sameerasw.island.sdk.IslandSurfaceKey
import com.sameerasw.island.sdk.compose.ComposeIslandProviderService
import com.sameerasw.overcast.data.repository.SettingsRepository
import com.sameerasw.overcast.weather.WeatherRepository
import com.sameerasw.overcast.weather.model.WeatherSnapshot
import com.sameerasw.overcast.weather.widget.loadWidgetWeather
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class WeatherIslandService : ComposeIslandProviderService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settingsTick = MutableStateFlow(0)
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> settingsTick.update { it + 1 } }
    private lateinit var settings: SettingsRepository
    private var publisher: Job? = null

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        settings.registerChangeListener(listener)
    }

    override fun onDestroy() {
        settings.unregisterChangeListener(listener)
        scope.cancel()
        super.onDestroy()
    }

    override fun onAttached(host: IslandHostHandle, info: HostInfo) {
        publisher?.cancel()
        publisher = scope.launch {
            WeatherRepository.ensureLoaded(applicationContext)
            launch {
                if (WeatherRepository.isStale(applicationContext)) {
                    WeatherRepository.refresh(applicationContext, force = true, fallbackToSaved = true)
                }
            }
            combine(WeatherRepository.state, settingsTick) { state, _ -> state }.collect {
                val weather = loadWidgetWeather(applicationContext)
                WeatherIslandState.set(weather)
                val snapshot = weather.snapshot
                val content = WeatherIslandPayload.build(applicationContext, settings, snapshot)
                host.publish(content.payload, content.assets)
                announceAlerts(host, snapshot)
            }
        }
    }

    override fun onHostDetached() {
        publisher?.cancel()
        publisher = null
        super.onHostDetached()
    }

    override fun hasExpandedContent(itemKey: String): Boolean =
        itemKey == WeatherIslandPayload.ITEM_KEY || itemKey == IslandSurfaceKey.brief(WeatherIslandPayload.ITEM_KEY)

    @Composable
    override fun ExpandedContent(itemKey: String) {
        val context = LocalContext.current
        val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicDarkColorScheme(context) else darkColorScheme()
        MaterialTheme(colorScheme = colors, typography = Typography) {
            WeatherIslandExpanded(
                host = hostInfoState.value,
                onOpenApp = { host?.openApp(itemKey) },
                onKeepAlive = { host?.keepAlive(itemKey) },
                onRefresh = { scope.launch { WeatherRepository.refresh(applicationContext, force = true) } },
                onHeight = { host?.resize(itemKey, it) },
                onSnapshot = { host?.snapshot(itemKey, it) },
            )
        }
    }

    private suspend fun announceAlerts(host: IslandHostHandle, snapshot: WeatherSnapshot?) {
        if (snapshot == null) return
        val severe = snapshot.activeAlerts().filter { it.severity.isSevere }
        if (severe.isEmpty()) return
        val fresh = WeatherRepository.takeUnnotifiedAlerts(applicationContext, severe.map { it.id })
        if (fresh.isNotEmpty()) host.peek(WeatherIslandPayload.ITEM_KEY)
    }
}
