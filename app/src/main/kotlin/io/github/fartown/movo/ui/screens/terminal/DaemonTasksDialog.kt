package io.github.fartown.movo.ui.screens.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.R
import io.github.fartown.movo.agent.terminal.TerminalEnvironment
import io.github.fartown.movo.ui.app.DaemonTaskUi
import io.github.fartown.movo.ui.components.movo.BlockTone
import io.github.fartown.movo.ui.components.movo.MovoBlockButton
import io.github.fartown.movo.ui.components.movo.MovoButtonRow
import io.github.fartown.movo.ui.components.movo.MovoDialogHost
import io.github.fartown.movo.ui.components.movo.MovoDivider
import io.github.fartown.movo.ui.components.movo.MovoPillButton
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoRadius
import io.github.fartown.movo.ui.theme.MovoSpacing
import io.github.fartown.movo.ui.theme.MovoTypography
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text

/**
 * 守护任务列表面板；终端块视图与控制台视图共用。
 * Movo 对话框（规范 8.11）：标题 Title/Section → 列表（命令等宽、状态行 + 行内按钮「日志 / 停止」、日志就地展开）→ 底部「关闭」。
 */
@Composable
internal fun DaemonTasksDialog(
    tasks: List<DaemonTaskUi>,
    onDismiss: () -> Unit,
    onStop: (String) -> Unit,
    onLoadLogs: suspend (String) -> String,
) {
    MovoDialogHost(show = true, onDismissRequest = onDismiss) {
        TerminalDialogTitle(stringResource(R.string.terminal_daemon_tasks))
        if (tasks.isEmpty()) {
            TerminalDialogEmpty(stringResource(R.string.terminal_daemon_empty))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
            ) {
                itemsIndexed(items = tasks, key = { _, task -> task.id }) { index, task ->
                    if (index > 0) MovoDivider(start = MovoSpacing.xxl, end = MovoSpacing.xxl)
                    DaemonTaskRow(task = task, onStop = onStop, onLoadLogs = onLoadLogs)
                }
            }
        }
        MovoButtonRow(modifier = Modifier.padding(MovoSpacing.xs)) {
            MovoBlockButton(
                label = stringResource(R.string.action_close),
                onClick = onDismiss,
                tone = BlockTone.Secondary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DaemonTaskRow(
    task: DaemonTaskUi,
    onStop: (String) -> Unit,
    onLoadLogs: suspend (String) -> String,
) {
    var logsExpanded by remember(task.id) { mutableStateOf(false) }
    var logs by remember(task.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MovoSpacing.xxl, vertical = MovoSpacing.md),
    ) {
        Text(
            text = task.command,
            style = MovoTypography.bodyRegular.copy(fontFamily = FontFamily.Monospace),
            color = MovoColors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MovoSpacing.sm),
        ) {
            Text(
                text = daemonMeta(task),
                style = MovoTypography.labelRegular,
                // 运行中用 Indigo 文字（执行中），同时文案本身写明状态。
                color = if (task.running) MovoColors.indigoFg else MovoColors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(MovoSpacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(MovoSpacing.sm)) {
                MovoPillButton(
                    label = stringResource(
                        if (logsExpanded) R.string.terminal_daemon_hide_logs else R.string.terminal_daemon_view_logs,
                    ),
                    onClick = {
                        logsExpanded = !logsExpanded
                        if (logsExpanded && logs == null) {
                            scope.launch { logs = onLoadLogs(task.id) }
                        }
                    },
                )
                MovoPillButton(
                    label = stringResource(R.string.terminal_stop),
                    onClick = { onStop(task.id) },
                )
            }
        }
        if (logsExpanded) {
            Box(
                modifier = Modifier
                    .padding(top = MovoSpacing.sm)
                    .fillMaxWidth()
                    .heightIn(max = 160.dp)
                    .clip(RoundedCornerShape(MovoRadius.xs))
                    .background(MovoColors.bgSurfaceMuted)
                    .verticalScroll(rememberScrollState())
                    .padding(MovoSpacing.sm),
            ) {
                if (logs == null) {
                    TerminalSpinner()
                } else {
                    Text(
                        text = logs.orEmpty(),
                        style = MovoTypography.labelRegular.copy(fontFamily = FontFamily.Monospace),
                        color = MovoColors.textPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun daemonMeta(task: DaemonTaskUi): String {
    val environmentLabel = when (task.environment) {
        TerminalEnvironment.ANDROID -> "Android"
        TerminalEnvironment.ALPINE -> "Alpine"
        TerminalEnvironment.DEBIAN -> "Debian"
    }
    val stateLabel = stringResource(
        if (task.running) R.string.terminal_daemon_running else R.string.terminal_daemon_exited,
    )
    return "$environmentLabel · ${task.identity} · $stateLabel"
}

/** 终端对话框标题：Title/Section 主色，左右 24、上 24、下 8（同 `MovoChoiceDialog`）。 */
@Composable
internal fun TerminalDialogTitle(text: String) {
    Text(
        text,
        style = MovoTypography.titleSection,
        color = MovoColors.textPrimary,
        modifier = Modifier.padding(
            start = MovoSpacing.xxl,
            end = MovoSpacing.xxl,
            top = MovoSpacing.xxl,
            bottom = MovoSpacing.sm,
        ),
    )
}

/** 终端对话框空状态：Body/Regular 次要色，左右 24、上下 24。 */
@Composable
internal fun TerminalDialogEmpty(text: String) {
    Text(
        text = text,
        style = MovoTypography.bodyRegular,
        color = MovoColors.textSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MovoSpacing.xxl, vertical = MovoSpacing.xxl),
    )
}
