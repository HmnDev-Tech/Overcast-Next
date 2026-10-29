package com.sameerasw.overcast.ui.features.weather

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sameerasw.overcast.R
import com.sameerasw.overcast.data.repository.SettingsRepository
import com.sameerasw.overcast.ui.components.menus.SegmentedDropdownMenuItem
import com.sameerasw.overcast.ui.core.cards.ConfigPickerItem
import com.sameerasw.overcast.ui.core.cards.IconToggleItem
import com.sameerasw.overcast.ui.core.containers.RoundedCardContainer
import com.sameerasw.overcast.ui.core.pickers.SegmentedPicker
import com.sameerasw.overcast.utils.HapticUtil
import com.sameerasw.overcast.weather.WeatherRepository
import com.sameerasw.overcast.weather.effects.WeatherSimulation
import com.sameerasw.overcast.weather.notify.WeatherNotifications
import com.sameerasw.overcast.weather.scene.SceneType

private val SceneType.label: String
    get() = name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

@Composable
fun WeatherExperimentsUI(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    val settings = remember { SettingsRepository(context) }
    var simulatedWeather by remember { mutableStateOf(settings.getString(SettingsRepository.KEY_DEBUG_SIMULATED_WEATHER, WeatherSimulation.OFF) ?: WeatherSimulation.OFF) }
    var simulatedAlert by remember { mutableStateOf(settings.getString(SettingsRepository.KEY_DEBUG_SIMULATED_ALERT, WeatherSimulation.OFF) ?: WeatherSimulation.OFF) }
    var simulatedTime by remember { mutableStateOf(settings.getString(SettingsRepository.KEY_DEBUG_SIMULATED_TIME, "auto") ?: "auto") }
    var simulatedScene by remember { mutableStateOf(settings.getString(SettingsRepository.KEY_DEBUG_SIMULATED_SCENE, "auto") ?: "auto") }
    var simulatedTemp by remember { mutableStateOf(settings.getString(SettingsRepository.KEY_DEBUG_SIMULATED_TEMP, "auto") ?: "auto") }

    RoundedCardContainer(modifier = modifier, spacing = 2.dp, cornerRadius = 24.dp) {
        ConfigPickerItem(
            title = stringResource(R.string.dev_simulate_weather_title),
            iconRes = R.drawable.rounded_partly_cloudy_day_24,
            selectedValue = WeatherSimulation.presets.firstOrNull { it.id == simulatedWeather }?.label.orEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            WeatherSimulation.presets.forEach { preset ->
                SegmentedDropdownMenuItem(
                    text = { Text(preset.label) },
                    onClick = {
                        HapticUtil.performVirtualKeyHaptic(view)
                        simulatedWeather = preset.id
                        settings.putString(SettingsRepository.KEY_DEBUG_SIMULATED_WEATHER, preset.id)
                    },
                )
            }
        }
        ConfigPickerItem(
            title = stringResource(R.string.dev_simulate_alerts_title),
            iconRes = R.drawable.rounded_warning_24,
            selectedValue = WeatherSimulation.alertPresets.firstOrNull { it.id == simulatedAlert }?.label.orEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            WeatherSimulation.alertPresets.forEach { preset ->
                SegmentedDropdownMenuItem(
                    text = { Text(preset.label) },
                    onClick = {
                        HapticUtil.performVirtualKeyHaptic(view)
                        simulatedAlert = preset.id
                        settings.putString(SettingsRepository.KEY_DEBUG_SIMULATED_ALERT, preset.id)
                    },
                )
            }
        }
        SegmentedPicker(
            items = listOf("auto", "dawn", "day", "dusk", "night"),
            selectedItem = simulatedTime,
            onItemSelected = {
                simulatedTime = it
                settings.putString(SettingsRepository.KEY_DEBUG_SIMULATED_TIME, it)
            },
            labelProvider = {
                context.getString(
                    when (it) {
                        "dawn" -> R.string.weather_sim_time_dawn
                        "day" -> R.string.weather_sim_time_day
                        "dusk" -> R.string.weather_sim_time_dusk
                        "night" -> R.string.weather_sim_time_night
                        else -> R.string.weather_sim_time_auto
                    },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        ConfigPickerItem(
            title = stringResource(R.string.dev_simulate_location_title),
            iconRes = R.drawable.rounded_landscape_24,
            selectedValue = SceneType.entries.firstOrNull { it.name == simulatedScene }?.label ?: stringResource(R.string.weather_sim_time_auto),
            modifier = Modifier.fillMaxWidth(),
        ) {
            (listOf("auto") + SceneType.entries.map { it.name }).forEach { id ->
                SegmentedDropdownMenuItem(
                    text = { Text(SceneType.entries.firstOrNull { it.name == id }?.label ?: stringResource(R.string.weather_sim_time_auto)) },
                    onClick = {
                        HapticUtil.performVirtualKeyHaptic(view)
                        simulatedScene = id
                        settings.putString(SettingsRepository.KEY_DEBUG_SIMULATED_SCENE, id)
                    },
                )
            }
        }
        SegmentedPicker(
            items = listOf("auto", "-15", "5", "22", "38", "50"),
            selectedItem = simulatedTemp,
            onItemSelected = {
                simulatedTemp = it
                settings.putString(SettingsRepository.KEY_DEBUG_SIMULATED_TEMP, it)
            },
            labelProvider = { if (it == "auto") context.getString(R.string.weather_sim_time_auto) else "$it°" },
            modifier = Modifier.fillMaxWidth(),
        )
        val sendSummary = {
            val snapshot = WeatherRepository.state.value.snapshot
            val message = when {
                snapshot == null -> R.string.dev_notification_no_weather
                WeatherNotifications.postSummary(context, snapshot) -> R.string.dev_notification_sent
                else -> R.string.dev_notification_blocked
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { sendSummary() }
        IconToggleItem(
            iconRes = R.drawable.rounded_notifications_24,
            title = stringResource(R.string.dev_send_notification),
            showToggle = false,
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !WeatherNotifications.canPost(context)) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    sendSummary()
                }
            },
        )
        IconToggleItem(
            iconRes = R.drawable.rounded_history_24,
            title = stringResource(R.string.dev_reset_welcome_title),
            showToggle = false,
            onClick = {
                settings.setOnboardingCompleted(false)
                Toast.makeText(context, R.string.dev_reset_welcome_done, Toast.LENGTH_SHORT).show()
            },
        )
    }
}
