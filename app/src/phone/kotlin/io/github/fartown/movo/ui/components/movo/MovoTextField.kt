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
import io.github.fartown.movo.ui.theme.MovoTypography
import top.yukonga.miuix.kmp.basic.TextFieldColors
import top.yukonga.miuix.kmp.basic.TextFieldDefaults

/**
 * Movo 的输入框配色。Miuix 只有一个 `labelColor`，同时管「空时的占位」和「有内容后浮起的字段名」：
 * 空时是占位，用 `text/tertiary`（规范 §4.1「占位符」）；有内容后它是唯一的字段名，用 `text/secondary`
 * （三级色只有 3.0:1，只能用于非关键信息）。Miuix 默认取 `onSecondaryContainer`，在 Movo 主题里是主文字色。
 */
@Composable
internal fun movoTextFieldColors(hasContent: Boolean = false): TextFieldColors =
    TextFieldDefaults.textFieldColors(labelColor = if (hasContent) MovoColors.textSecondary else MovoColors.textTertiary)

/** 输入框文字：规范字体表 `Input/Placeholder` 16/24 Regular，主文字色（Miuix 默认 `main` 是 17，不在字体表里）。 */
internal fun movoTextFieldStyle(): TextStyle = MovoTypography.inputPlaceholder.copy(color = MovoColors.textPrimary)

// 与 Miuix `TextField` 同签名的三个重载，只换默认配色与字号；页面代码 import 这里的 `TextField` 即可。

@Composable
internal fun TextField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    insideMargin: DpSize = TextFieldDefaults.InsideMargin,
    colors: TextFieldColors = movoTextFieldColors(hasContent = state.text.isNotEmpty()),
    cornerRadius: Dp = TextFieldDefaults.CornerRadius,
    label: String = "",
    useLabelAsPlaceholder: Boolean = false,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    inputTransformation: InputTransformation? = null,
    textStyle: TextStyle = movoTextFieldStyle(),
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
    colors: TextFieldColors = movoTextFieldColors(hasContent = value.text.isNotEmpty()),
    cornerRadius: Dp = TextFieldDefaults.CornerRadius,
    label: String = "",
    useLabelAsPlaceholder: Boolean = false,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = movoTextFieldStyle(),
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
    colors: TextFieldColors = movoTextFieldColors(hasContent = value.isNotEmpty()),
    cornerRadius: Dp = TextFieldDefaults.CornerRadius,
    label: String = "",
    useLabelAsPlaceholder: Boolean = false,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = movoTextFieldStyle(),
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
