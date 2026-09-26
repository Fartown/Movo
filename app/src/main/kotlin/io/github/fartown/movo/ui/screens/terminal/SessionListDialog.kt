package io.github.fartown.movo.ui.screens.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.R
import io.github.fartown.movo.agent.terminal.TerminalEnvironment
import io.github.fartown.movo.ui.app.displayName
import io.github.fartown.movo.ui.components.movo.BlockTone
import io.github.fartown.movo.ui.components.movo.MovoBlockButton
import io.github.fartown.movo.ui.components.movo.MovoButtonRow
import io.github.fartown.movo.ui.components.movo.MovoDialogHost
import io.github.fartown.movo.ui.components.movo.MovoPillButton
import io.github.fartown.movo.ui.components.movo.PressKind
import io.github.fartown.movo.ui.components.movo.movoClickable
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoRadius
import io.github.fartown.movo.ui.theme.MovoSpacing
import io.github.fartown.movo.ui.theme.MovoTypography
import top.yukonga.miuix.kmp.basic.Text

@Immutable
internal data class SessionDialogRow(
    val id: String,
    val environment: TerminalEnvironment,
    /** 块式终端传 cwd；控制台传空串。 */
    val subtitle: String,
    val active: Boolean,
    val running: Boolean,
    val alive: Boolean,
)

/**
 * 终端会话列表面板；块式终端与控制台共用。点按行切换会话，行内提供重启与关闭。
 * Movo 对话框（规范 8.11）：标题 → 会话行（行按压态，当前会话 Indigo 文字 +「当前」）→ 底部「关闭 | 新建会话」。
 */
@Composable
internal fun SessionListDialog(
    rows: List<SessionDialogRow>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onRestart: (String) -> Unit,
    onClose: (String) -> Unit,
    onNew: () -> Unit,
) {
    MovoDialogHost(show = true, onDismissRequest = onDismiss) {
        TerminalDialogTitle(stringResource(R.string.terminal_sessions))
        if (rows.isEmpty()) {
            TerminalDialogEmpty(stringResource(R.string.terminal_session_empty))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .padding(horizontal = MovoSpacing.sm),
            ) {
                items(items = rows, key = { it.id }) { row ->
                    SessionRow(
                        row = row,
                        onSelect = { onSelect(row.id); onDismiss() },
                        onRestart = { onRestart(row.id) },
                        onClose = { onClose(row.id) },
                    )
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
            MovoBlockButton(
                label = stringResource(R.string.terminal_new_session),
                onClick = { onNew(); onDismiss() },
                tone = BlockTone.Primary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SessionRow(
    row: SessionDialogRow,
    onSelect: () -> Unit,
    onRestart: () -> Unit,
    onClose: () -> Unit,
) {
    val shape = RoundedCornerShape(MovoRadius.sm)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .movoClickable(PressKind.Row, shape = shape, onClick = onSelect)
            .padding(horizontal = MovoSpacing.lg, vertical = MovoSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.environment.displayName,
                style = if (row.active) MovoTypography.bodyStrong else MovoTypography.bodyRegular,
                color = if (row.active) MovoColors.indigoFg else MovoColors.textPrimary,
            )
            if (row.active) {
                Spacer(Modifier.width(MovoSpacing.sm))
                Text(
                    text = stringResource(R.string.terminal_session_current),
                    style = MovoTypography.labelMedium,
                    color = MovoColors.indigoFg,
                )
            }
        }
        if (row.subtitle.isNotEmpty()) {
            Text(
                text = row.subtitle,
                style = MovoTypography.labelRegular.copy(fontFamily = FontFamily.Monospace),
                color = MovoColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MovoSpacing.sm),
        ) {
            Text(
                text = sessionStateLabel(row),
                style = MovoTypography.labelRegular,
                color = if (row.running) MovoColors.indigoFg else MovoColors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(MovoSpacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(MovoSpacing.sm)) {
                MovoPillButton(
                    label = stringResource(R.string.terminal_restart_session),
                    onClick = onRestart,
                )
                MovoPillButton(
                    label = stringResource(R.string.terminal_close_session),
                    onClick = onClose,
                )
            }
        }
    }
}

@Composable
private fun sessionStateLabel(row: SessionDialogRow): String = stringResource(
    when {
        !row.alive -> R.string.terminal_session_exited
        row.running -> R.string.terminal_daemon_running
        else -> R.string.terminal_session_idle
    },
)
