package com.sameerasw.overcast.utils

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.sameerasw.overcast.data.repository.SettingsRepository

enum class CompatibilityFeature(val key: String) {
    STILL_EFFECTS("compat_still_effects"),
    NO_BLUR("compat_no_blur"),
    NO_GLASS("compat_no_glass"),
    NO_HEAT_HAZE("compat_no_heat_haze"),
    REDUCED_MOTION("compat_reduced_motion"),
}

object CompatibilityMode {
    val enabled = mutableStateOf(false)
    private val features = CompatibilityFeature.entries.associateWith { mutableStateOf(true) }

    fun initialize(context: Context) {
        val settings = SettingsRepository(context)
        enabled.value = settings.isCompatibilityMode()
        CompatibilityFeature.entries.forEach { features.getValue(it).value = settings.isCompatibilityFeatureEnabled(it) }
    }

    fun setFeature(feature: CompatibilityFeature, on: Boolean) {
        features.getValue(feature).value = on
    }

    fun applies(feature: CompatibilityFeature): Boolean = enabled.value && features.getValue(feature).value

    val stillEffects: Boolean get() = applies(CompatibilityFeature.STILL_EFFECTS)
    val noBlur: Boolean get() = applies(CompatibilityFeature.NO_BLUR)
    val noGlass: Boolean get() = applies(CompatibilityFeature.NO_GLASS)
    val noHeatHaze: Boolean get() = applies(CompatibilityFeature.NO_HEAT_HAZE)
    val reducedMotion: Boolean get() = applies(CompatibilityFeature.REDUCED_MOTION)
}
