/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: UI Feature - Weather
 * File: IslandSettingsUI.kt
 * Description: Dynamic Island section of the settings: island style and content options.
 */

package com.sameerasw.overcast.ui.features.weather

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import com.sameerasw.overcast.weather.island.IslandStyle

@Composable
fun IslandSettingsUI(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    val settings = remember { SettingsRepository(context) }
    var styleId by remember { mutableStateOf(settings.getIslandStyle()) }
    var hourly by remember { mutableStateOf(settings.isIslandHourlyEnabled()) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionTitle(R.string.weather_section_island)
        RoundedCardContainer(spacing = 2.dp, cornerRadius = 24.dp) {
            ConfigPickerItem(
                title = stringResource(R.string.island_style_title),
                selectedValue = islandStyleLabel(styleId),
                iconRes = R.drawable.rounded_devices_24,
                modifier = Modifier.fillMaxWidth(),
            ) {
                IslandStyle.entries.forEach { option ->
                    SegmentedDropdownMenuItem(
                        text = { Text(islandStyleLabel(option.id)) },
                        onClick = {
                            HapticUtil.performVirtualKeyHaptic(view)
                            styleId = option.id
                            settings.setIslandStyle(option.id)
                        },
                    )
                }
            }
            IconToggleItem(
                iconRes = R.drawable.rounded_schedule_24,
                title = stringResource(R.string.island_hourly_title),
                isChecked = hourly,
                onCheckedChange = {
                    HapticUtil.performVirtualKeyHaptic(view)
                    hourly = it
                    settings.setIslandHourlyEnabled(it)
                },
            )
        }
        Text(
            text = stringResource(R.string.island_style_info),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun islandStyleLabel(id: String): String = stringResource(
    when (IslandStyle.fromId(id)) {
        IslandStyle.AUTO -> R.string.island_style_auto
        IslandStyle.PILL -> R.string.island_style_pill
        IslandStyle.COMPACT -> R.string.island_style_compact
        IslandStyle.MINIMAL -> R.string.island_style_minimal
    },
)
