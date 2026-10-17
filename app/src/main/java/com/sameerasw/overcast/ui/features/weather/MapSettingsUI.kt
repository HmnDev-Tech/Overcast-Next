/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: UI Feature - Weather
 * File: MapSettingsUI.kt
 * Description: Weather map section of the settings: default layer and radar animation.
 */

package com.sameerasw.overcast.ui.features.weather

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.sameerasw.overcast.utils.HapticUtil
import com.sameerasw.overcast.ui.features.weather.map.MapLayer

@Composable
fun MapSettingsUI(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    val settings = remember { SettingsRepository(context) }
    var layerId by remember { mutableStateOf(settings.getMapDefaultLayer()) }
    var animate by remember { mutableStateOf(settings.isMapAnimateRadarEnabled()) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionTitle(R.string.weather_section_map)
        RoundedCardContainer(spacing = 2.dp, cornerRadius = 24.dp) {
            ConfigPickerItem(
                title = stringResource(R.string.map_default_layer_title),
                selectedValue = mapLayerLabel(layerId),
                iconRes = R.drawable.rounded_map_24,
                modifier = Modifier.fillMaxWidth(),
            ) {
                MapLayer.entries.forEach { option ->
                    SegmentedDropdownMenuItem(
                        text = { Text(mapLayerLabel(option.name.lowercase())) },
                        onClick = {
                            HapticUtil.performVirtualKeyHaptic(view)
                            layerId = option.name.lowercase()
                            settings.setMapDefaultLayer(layerId)
                        },
                    )
                }
            }
            IconToggleItem(
                iconRes = R.drawable.rounded_satellite_24,
                title = stringResource(R.string.map_animate_radar_title),
                isChecked = animate,
                onCheckedChange = {
                    HapticUtil.performVirtualKeyHaptic(view)
                    animate = it
                    settings.setMapAnimateRadarEnabled(it)
                },
            )
        }
    }
}

@Composable
private fun mapLayerLabel(id: String): String = stringResource(
    when (id.lowercase()) {
        "temperature" -> R.string.map_layer_temperature
        "satellite" -> R.string.map_layer_satellite
        else -> R.string.map_layer_precipitation
    },
)
