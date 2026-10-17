/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: UI Feature - Weather
 * File: WidgetSettingsUI.kt
 * Description: Widgets section of the settings.
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
import com.sameerasw.overcast.ui.core.cards.IconToggleItem
import com.sameerasw.overcast.ui.core.containers.RoundedCardContainer
import com.sameerasw.overcast.utils.HapticUtil

@Composable
fun WidgetSettingsUI(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    val settings = remember { SettingsRepository(context) }
    var animate by remember { mutableStateOf(settings.isWidgetExpressiveAnimationEnabled()) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionTitle(R.string.weather_section_widgets)
        RoundedCardContainer(spacing = 2.dp, cornerRadius = 24.dp) {
            IconToggleItem(
                iconRes = R.drawable.rounded_dashboard_24,
                title = stringResource(R.string.widget_expressive_animate_title),
                description = stringResource(R.string.widget_expressive_description),
                isChecked = animate,
                onCheckedChange = {
                    HapticUtil.performVirtualKeyHaptic(view)
                    animate = it
                    settings.setWidgetExpressiveAnimationEnabled(it)
                },
            )
        }
    }
}
