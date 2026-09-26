package io.github.fartown.movo.ui.app

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Density
import io.github.fartown.movo.data.model.AppearanceSettings
import io.github.fartown.movo.data.model.AppearanceTopBarBlurStyle

internal val LocalAppearanceSettings = staticCompositionLocalOf { AppearanceSettings() }
internal val LocalBlurEnabled = staticCompositionLocalOf { true }
internal val LocalTopBarBlurStyle = staticCompositionLocalOf { AppearanceTopBarBlurStyle.GAUSSIAN }
internal val LocalPlatformDensity = staticCompositionLocalOf<Density?> { null }
