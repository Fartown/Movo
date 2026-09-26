package io.github.fartown.movo.ui.components.movo

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import io.github.fartown.movo.ui.theme.MovoColors
import top.yukonga.miuix.kmp.basic.TextFieldColors
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Movo 的输入框配色：空时的占位文字与浮起的标签用 `text/tertiary`（规范 §2「占位符」）。
 * Miuix 默认取 `onSecondaryContainer`，在 Movo 主题里是主文字色，占位看起来像已填的内容。
 */
@Composable
internal fun movoTextFieldColors(): TextFieldColors = TextFieldDefaults.textFieldColors(labelColor = MovoColors.textTertiary)

// 与 Miuix `TextField` 同签名的三个重载，只换默认配色；页面代码 import 这里的 `TextField` 即可。

@Composable
internal fun TextField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    insideMargin: DpSize = TextFieldDefaults.InsideMargin,
    colors: TextFieldColors = movoTextFieldColors(),
    cornerRadius: Dp = TextFieldDefaults.CornerRadius,
    label: String = "",
    useLabelAsPlaceholder: Boolean = false,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    inputTransformation: InputTransformation? = null,
    textStyle: TextStyle = MiuixTheme.textStyles.main,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onKeyboardAction: KeyboardActionHandler? = null,
    lineLimits: TextFieldLineLimits = TextFieldLineLimits.Default,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    onTextLayout: (Density.(getResult: () -> TextLayoutResult?) -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
    cursorBrush: Brush = SolidColor(colors.borderColor),
    outputTransformation: OutputTransformation? = null,
    scrollState: ScrollState = rememberScrollState(),
) = top.yukonga.miuix.kmp.basic.TextField(
    state = state, modifier = modifier, insideMargin = insideMargin, colors = colors, cornerRadius = cornerRadius,
    label = label, useLabelAsPlaceholder = useLabelAsPlaceholder, enabled = enabled, readOnly = readOnly,
    inputTransformation = inputTransformation, textStyle = textStyle, keyboardOptions = keyboardOptions,
    onKeyboardAction = onKeyboardAction, lineLimits = lineLimits, leadingIcon = leadingIcon, trailingIcon = trailingIcon,
    onTextLayout = onTextLayout, interactionSource = interactionSource, cursorBrush = cursorBrush,
    outputTransformation = outputTransformation, scrollState = scrollState,
)

@Composable
internal fun TextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    insideMargin: DpSize = TextFieldDefaults.InsideMargin,
    colors: TextFieldColors = movoTextFieldColors(),
    cornerRadius: Dp = TextFieldDefaults.CornerRadius,
    label: String = "",
    useLabelAsPlaceholder: Boolean = false,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = MiuixTheme.textStyles.main,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    interactionSource: MutableInteractionSource? = null,
    cursorBrush: Brush = SolidColor(colors.borderColor),
) = top.yukonga.miuix.kmp.basic.TextField(
    value = value, onValueChange = onValueChange, modifier = modifier, insideMargin = insideMargin, colors = colors,
    cornerRadius = cornerRadius, label = label, useLabelAsPlaceholder = useLabelAsPlaceholder, enabled = enabled,
    readOnly = readOnly, textStyle = textStyle, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
    leadingIcon = leadingIcon, trailingIcon = trailingIcon, singleLine = singleLine, maxLines = maxLines,
    minLines = minLines, visualTransformation = visualTransformation, onTextLayout = onTextLayout,
    interactionSource = interactionSource, cursorBrush = cursorBrush,
)

@Composable
internal fun TextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    insideMargin: DpSize = TextFieldDefaults.InsideMargin,
    colors: TextFieldColors = movoTextFieldColors(),
    cornerRadius: Dp = TextFieldDefaults.CornerRadius,
    label: String = "",
    useLabelAsPlaceholder: Boolean = false,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = MiuixTheme.textStyles.main,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    interactionSource: MutableInteractionSource? = null,
    cursorBrush: Brush = SolidColor(colors.borderColor),
) = top.yukonga.miuix.kmp.basic.TextField(
    value = value, onValueChange = onValueChange, modifier = modifier, insideMargin = insideMargin, colors = colors,
    cornerRadius = cornerRadius, label = label, useLabelAsPlaceholder = useLabelAsPlaceholder, enabled = enabled,
    readOnly = readOnly, textStyle = textStyle, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
    leadingIcon = leadingIcon, trailingIcon = trailingIcon, singleLine = singleLine, maxLines = maxLines,
    minLines = minLines, visualTransformation = visualTransformation, onTextLayout = onTextLayout,
    interactionSource = interactionSource, cursorBrush = cursorBrush,
)
