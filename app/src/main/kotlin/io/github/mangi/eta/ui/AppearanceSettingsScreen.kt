package io.github.mangi.eta.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceTopBarBlurStyle
import io.github.mangi.eta.data.model.MAX_INTERFACE_SCALE
import io.github.mangi.eta.data.model.MIN_INTERFACE_SCALE
import io.github.mangi.eta.data.model.normalizeInterfaceScale
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.LocalAppearanceSettings
import io.github.mangi.eta.ui.components.movo.CardTitle
import io.github.mangi.eta.ui.components.movo.MovoCard
import io.github.mangi.eta.ui.components.movo.MovoChoiceDialog
import io.github.mangi.eta.ui.components.movo.MovoConfirmDialog
import io.github.mangi.eta.ui.components.movo.MovoListPage
import io.github.mangi.eta.ui.components.movo.RowTrailing
import io.github.mangi.eta.ui.components.movo.SettingsRow
import io.github.mangi.eta.ui.theme.MovoSpacing
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import kotlin.math.roundToInt

/**
 * 设置 · 外观（规范 8.7 二级页）。规范只做浅色，主题模式、莫奈取色、调色板、强调色、纯黑背景不再显示
 * （存储值保留在 [AppearanceSettings] 里，不删除）；保留界面缩放、返回手势与模糊效果。
 */
@Composable
internal fun AppearanceSettingsScreen(onBack: () -> Unit) {
    val appearance = LocalAppearanceSettings.current
    val coroutineScope = rememberCoroutineScope()
    var scaleDraft by remember(appearance.interfaceScale) {
        mutableFloatStateOf(appearance.interfaceScale * 100f)
    }
    var showScaleDialog by remember { mutableStateOf(false) }
    var showBlurStyleDialog by remember { mutableStateOf(false) }
    var scaleInput by remember { mutableStateOf("") }
    val blurSupported = isRuntimeShaderSupported()

    fun update(transform: (AppearanceSettings) -> AppearanceSettings) {
        coroutineScope.launch {
            AppearanceSettingsRepository.update(transform)
        }
    }

    fun commitScale(percent: Float) {
        val scale = normalizeInterfaceScale(percent.roundToInt() / 100f)
        scaleDraft = scale * 100f
        update { current -> current.copy(interfaceScale = scale) }
    }

    val blurStyles = AppearanceTopBarBlurStyle.entries
    val blurStyleLabels = listOf(
        stringResource(R.string.appearance_blur_style_gaussian),
        stringResource(R.string.appearance_blur_style_progressive),
    )
    val blurStyleSummaries = listOf(
        stringResource(R.string.appearance_blur_style_gaussian_summary),
        stringResource(R.string.appearance_blur_style_progressive_summary),
    )
    val blurOn = appearance.blurEnabled && blurSupported

    MovoListPage(title = stringResource(R.string.appearance_title), onBack = onBack) {
        item(key = "display") {
            MovoCard {
                CardTitle(stringResource(R.string.movo_appearance_group_display))
                SettingsRow(
                    title = stringResource(R.string.appearance_interface_scale),
                    subtitle = stringResource(R.string.appearance_interface_scale_summary),
                    trailing = RowTrailing.Arrow("${scaleDraft.roundToInt()}%"),
                    showDivider = false,
                    onClick = {
                        scaleInput = scaleDraft.roundToInt().toString()
                        showScaleDialog = true
                    },
                )
                Slider(
                    value = scaleDraft.coerceIn(
                        MIN_INTERFACE_SCALE * 100f,
                        MAX_INTERFACE_SCALE * 100f,
                    ),
                    onValueChange = { scaleDraft = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = MovoSpacing.lg, end = MovoSpacing.lg, bottom = MovoSpacing.md),
                    valueRange = (MIN_INTERFACE_SCALE * 100f)..(MAX_INTERFACE_SCALE * 100f),
                    onValueChangeFinished = { commitScale(scaleDraft) },
                    showKeyPoints = true,
                    keyPoints = listOf(80f, 90f, 100f, 110f),
                    magnetThreshold = 0.01f,
                    hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                )
            }
        }
        item(key = "gesture") {
            MovoCard {
                CardTitle(stringResource(R.string.movo_appearance_group_gesture))
                SettingsRow(
                    title = stringResource(R.string.appearance_swipe_dismiss),
                    subtitle = stringResource(R.string.appearance_swipe_dismiss_summary),
                    trailing = RowTrailing.Switch(appearance.swipeDismissEnabled) { enabled ->
                        update { current -> current.copy(swipeDismissEnabled = enabled) }
                    },
                )
                SettingsRow(
                    title = stringResource(R.string.appearance_predictive_back),
                    subtitle = stringResource(R.string.movo_appearance_predictive_back_desc),
                    trailing = RowTrailing.Switch(appearance.predictiveBackEnabled) { enabled ->
                        update { current -> current.copy(predictiveBackEnabled = enabled) }
                    },
                    showDivider = false,
                )
            }
        }
        item(key = "effects") {
            MovoCard {
                CardTitle(stringResource(R.string.movo_appearance_group_effects))
                SettingsRow(
                    title = stringResource(R.string.appearance_blur),
                    subtitle = stringResource(R.string.movo_appearance_blur_desc),
                    trailing = RowTrailing.Switch(blurOn) { enabled ->
                        update { current -> current.copy(blurEnabled = enabled) }
                    },
                    enabled = blurSupported,
                    showDivider = blurOn,
                )
                AnimatedVisibility(
                    visible = blurOn,
                    enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                ) {
                    SettingsRow(
                        title = stringResource(R.string.appearance_blur_style),
                        subtitle = blurStyleSummaries[appearance.topBarBlurStyle.ordinal],
                        trailing = RowTrailing.Arrow(blurStyleLabels[appearance.topBarBlurStyle.ordinal]),
                        showDivider = false,
                        onClick = { showBlurStyleDialog = true },
                    )
                }
            }
        }
    }

    MovoChoiceDialog(
        show = showBlurStyleDialog,
        title = stringResource(R.string.appearance_blur_style),
        options = blurStyleLabels,
        selectedIndex = appearance.topBarBlurStyle.ordinal,
        onSelect = { index ->
            blurStyles.getOrNull(index)?.let { style ->
                update { current -> current.copy(topBarBlurStyle = style) }
            }
        },
        onDismissRequest = { showBlurStyleDialog = false },
    )

    val parsedScale = scaleInput.toIntOrNull()
    MovoConfirmDialog(
        show = showScaleDialog,
        title = stringResource(R.string.appearance_interface_scale_dialog_title),
        message = stringResource(R.string.appearance_interface_scale_dialog_summary),
        confirmText = stringResource(R.string.action_confirm),
        confirmEnabled = parsedScale != null && parsedScale in 80..110,
        onConfirm = {
            parsedScale?.let { commitScale(it.toFloat()) }
            showScaleDialog = false
        },
        onDismissRequest = { showScaleDialog = false },
        extraContent = {
            Spacer(Modifier.height(MovoSpacing.lg))
            TextField(
                value = scaleInput,
                onValueChange = { value -> scaleInput = value.filter(Char::isDigit).take(3) },
                label = stringResource(R.string.appearance_interface_scale_input_label),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}
