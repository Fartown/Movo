package io.github.fartown.movo.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import io.github.fartown.movo.R
import io.github.fartown.movo.ui.model.PermissionStatusUi
import io.github.fartown.movo.ui.model.RunStatusUi
import io.github.fartown.movo.ui.theme.MovoColors

// 语义状态色：只取规范 4.2 的类别色 Token（完成 Green、系统 / 提醒 Amber、错误 Rose、进行中 Indigo），不裸写色值。
// 状态色只用于图标与状态点；文字仍用主色 / 次要色（规范 4.2 规则 2）。
val StatusSuccess: Color = MovoColors.greenFg
val StatusWarning: Color = MovoColors.amberFg
val StatusError: Color = MovoColors.roseFg
val StatusRunning: Color = MovoColors.indigoFg
val StatusIdle: Color = MovoColors.textSecondary

// ── RunStatusUi 映射 ──────────────────────────────────────────────────

@Composable
fun RunStatusUi.color(): Color = when (this) {
    RunStatusUi.Running -> StatusRunning
    RunStatusUi.Success -> StatusSuccess
    RunStatusUi.Failed -> StatusError
    RunStatusUi.Cancelled -> StatusIdle
}

@Composable
fun RunStatusUi.label(): String = stringResource(when (this) {
    RunStatusUi.Running -> R.string.tool_status_running
    RunStatusUi.Success -> R.string.tool_status_success
    RunStatusUi.Failed -> R.string.tool_status_failed
    RunStatusUi.Cancelled -> R.string.status_cancelled
})

// ── PermissionStatusUi 映射 ───────────────────────────────────────────

@Composable
fun PermissionStatusUi.color(): Color = when (this) {
    PermissionStatusUi.Available -> StatusIdle
    PermissionStatusUi.Warning -> StatusWarning
    PermissionStatusUi.Missing -> StatusError
    PermissionStatusUi.Disabled -> StatusIdle
}

@Composable
fun PermissionStatusUi.label(): String = stringResource(when (this) {
    PermissionStatusUi.Available -> R.string.status_ready
    PermissionStatusUi.Warning -> R.string.status_needs_attention
    PermissionStatusUi.Missing -> R.string.status_unauthorized
    PermissionStatusUi.Disabled -> R.string.status_disabled
})
