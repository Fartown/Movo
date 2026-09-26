package io.github.fartown.movo.ui.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import io.github.fartown.movo.data.model.AppearanceSettings
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.ProvideReducedMotion
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

@Composable
fun AgentAppTheme(
    appearance: AppearanceSettings,
    applyInterfaceScale: Boolean,
    onResolvedDarkModeChange: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    // 设计规范 v1 只做浅色（docs/DESIGN_SYSTEM.md 开头「当前只做浅色模式」，2026-09-25 确认）：
    // 深色、莫奈、调色板、强调色、纯黑的存储值保留不删，但不再参与配色，外观页也不再显示这几项。
    val isDark = false
    val controller = remember {
        ThemeController(
            colorSchemeMode = ColorSchemeMode.Light,
            keyColor = null,
            colorSpec = ThemeColorSpec.Spec2025,
            paletteStyle = ThemePaletteStyle.TonalSpot,
            isDark = false,
        )
    }
    val colors = controller.currentColors()
    val themedColors = remember(colors) { colors.withMovoPalette() }

    LaunchedEffect(isDark) { onResolvedDarkModeChange(isDark) }

    MiuixTheme(colors = themedColors) {
        val platformDensity = LocalDensity.current
        val appDensity = remember(platformDensity, appearance.interfaceScale, applyInterfaceScale) {
            if (applyInterfaceScale) {
                Density(
                    density = platformDensity.density * appearance.interfaceScale,
                    fontScale = platformDensity.fontScale,
                )
            } else {
                platformDensity
            }
        }
        val miuixColors = MiuixTheme.colorScheme
        val materialColors = if (isDark) {
            darkColorScheme(
                primary = miuixColors.primary,
                onPrimary = miuixColors.onPrimary,
                primaryContainer = miuixColors.primaryContainer,
                onPrimaryContainer = miuixColors.onPrimaryContainer,
                secondary = miuixColors.secondary,
                onSecondary = miuixColors.onSecondary,
                secondaryContainer = miuixColors.secondaryContainer,
                onSecondaryContainer = miuixColors.onSecondaryContainer,
                background = miuixColors.background,
                onBackground = miuixColors.onBackground,
                surface = miuixColors.surface,
                onSurface = miuixColors.onSurface,
                surfaceVariant = miuixColors.surfaceVariant,
                onSurfaceVariant = miuixColors.onSurfaceSecondary,
                error = miuixColors.error,
                onError = miuixColors.onError,
                errorContainer = miuixColors.errorContainer,
                onErrorContainer = miuixColors.onErrorContainer,
                outline = miuixColors.outline,
            )
        } else {
            lightColorScheme(
                primary = miuixColors.primary,
                onPrimary = miuixColors.onPrimary,
                primaryContainer = miuixColors.primaryContainer,
                onPrimaryContainer = miuixColors.onPrimaryContainer,
                secondary = miuixColors.secondary,
                onSecondary = miuixColors.onSecondary,
                secondaryContainer = miuixColors.secondaryContainer,
                onSecondaryContainer = miuixColors.onSecondaryContainer,
                background = miuixColors.background,
                onBackground = miuixColors.onBackground,
                surface = miuixColors.surface,
                onSurface = miuixColors.onSurface,
                surfaceVariant = miuixColors.surfaceVariant,
                onSurfaceVariant = miuixColors.onSurfaceSecondary,
                error = miuixColors.error,
                onError = miuixColors.onError,
                errorContainer = miuixColors.errorContainer,
                onErrorContainer = miuixColors.onErrorContainer,
                outline = miuixColors.outline,
            )
        }

        CompositionLocalProvider(
            LocalAppearanceSettings provides appearance,
            LocalBlurEnabled provides appearance.blurEnabled,
            LocalTopBarBlurStyle provides appearance.topBarBlurStyle,
            LocalPlatformDensity provides platformDensity,
            LocalDensity provides appDensity,
        ) {
            // MaterialTheme 仅向 markdown-renderer-m3 提供与 Miuix 一致的颜色上下文。
            MaterialTheme(colorScheme = materialColors) {
                ProvideReducedMotion(content)
            }
        }
    }
}

/**
 * 把 Miuix 浅色配色映射到 Movo Token，让尚未改版、仍用 Miuix 组件的页面与新页面同一套颜色
 * （页面底 bg/canvas、卡片白、文字三级灰、选中 Indigo、分隔线发丝线）。
 */
private fun top.yukonga.miuix.kmp.theme.Colors.withMovoPalette() = copy(
    primary = MovoColors.indigoFg,
    onPrimary = MovoColors.bgSurface,
    primaryContainer = MovoColors.indigoBg,
    onPrimaryContainer = MovoColors.indigoFg,
    error = MovoColors.roseFg,
    errorContainer = MovoColors.roseBg,
    onErrorContainer = MovoColors.roseFg,
    secondary = MovoColors.bgSurfaceMuted,
    secondaryVariant = MovoColors.bgSurfaceMuted,
    onSecondary = MovoColors.textPrimary,
    onSecondaryVariant = MovoColors.textPrimary,
    secondaryContainer = MovoColors.bgSurfaceMuted,
    onSecondaryContainer = MovoColors.textPrimary,
    background = MovoColors.bgCanvas,
    onBackground = MovoColors.textPrimary,
    onBackgroundVariant = MovoColors.textSecondary,
    surface = MovoColors.bgCanvas,
    onSurface = MovoColors.textPrimary,
    surfaceVariant = MovoColors.bgSurfaceMuted,
    onSurfaceSecondary = MovoColors.textSecondary,
    onSurfaceVariantSummary = MovoColors.textSecondary,
    onSurfaceVariantActions = MovoColors.textSecondary,
    surfaceContainer = MovoColors.bgSurface,
    onSurfaceContainer = MovoColors.textPrimary,
    onSurfaceContainerVariant = MovoColors.textSecondary,
    surfaceContainerHigh = MovoColors.bgSurface,
    onSurfaceContainerHigh = MovoColors.textPrimary,
    outline = MovoColors.borderStrong,
    dividerLine = MovoColors.borderHairline,
    windowDimming = MovoColors.overlayScrim,
)
