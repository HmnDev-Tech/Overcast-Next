/*
 * Copyright (c) 2026 sameerasw.com
 * License: MIT License
 *
 * Feature Module: Weather - Island
 * File: IslandStyle.kt
 * Description: Island presentation profiles so the weather island fits any host cutout,
 * from tiny punch holes to large pill style islands.
 */

package com.sameerasw.overcast.weather.island

import com.sameerasw.overcast.data.repository.SettingsRepository

enum class IslandStyle(
    val id: String,
    val heightDp: Int,
    val heightAlertDp: Int,
    val showDetails: Boolean,
) {
    // Auto follows the stock sizing and lets the host shape the island.
    AUTO("auto", 304, 378, true),
    // Large pill, similar to iPhone style islands.
    PILL("pill", 360, 432, true),
    // Compact pill for small punch hole and camera bar cutouts (Samsung, Honor, Xiaomi).
    COMPACT("compact", 272, 336, true),
    // Minimal keeps only the essentials for tiny cutouts.
    MINIMAL("minimal", 236, 288, false),
    ;

    companion object {
        fun fromId(id: String?): IslandStyle = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

fun SettingsRepository.getIslandStyleProfile(): IslandStyle = IslandStyle.fromId(getIslandStyle())
