package io.github.mangi.eta.ui.screens.terminal

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.isLinux
import io.github.mangi.eta.ui.app.TerminalBlockUi
import io.github.mangi.eta.ui.app.UserTerminalStore
import io.github.mangi.eta.ui.app.UserTerminalUiState
import io.github.mangi.eta.ui.components.ansiPlainText
import io.github.mangi.eta.ui.components.ansiToAnnotatedString
import io.github.mangi.eta.ui.components.movo.MovoCircleButton
import io.github.mangi.eta.ui.components.movo.MovoIconButton
import io.github.mangi.eta.ui.components.movo.MovoPillButton
import io.github.mangi.eta.ui.components.movo.PressKind
import io.github.mangi.eta.ui.components.movo.movoClickable
import io.github.mangi.eta.ui.theme.MovoColors
import io.github.mangi.eta.ui.theme.MovoIcons
import io.github.mangi.eta.ui.theme.MovoRadius
import io.github.mangi.eta.ui.theme.MovoSize
import io.github.mangi.eta.ui.theme.MovoSpacing
import io.github.mangi.eta.ui.theme.MovoTypography
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup

/**
 * 用户手动终端：块式输出（命令、输出、退出码），给人用；与 AI 工具调用的任务模型分开。
 * 会话由 [UserTerminalStore] 持有，离开页面后正在运行的命令仍在常驻会话里继续。
 */
@Composable
internal fun UserTerminalScreen(
    store: UserTerminalStore,
    onOpenEnvironment: () -> Unit,
    onOpenConsole: (() -> Unit)? = null,
) {
    val state by store.uiState.collectAsState()
    var input by remember { mutableStateOf("") }
    var showTasks by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // 从安装页返回后刷新 Linux 就绪态，引导页才会自动让位给终端；守护任务状态一并刷新。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                store.refreshLinuxReady()
                store.refreshDaemonTasks()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val showLinuxGuide = state.environment.isLinux && !state.linuxReady

    fun submit() {
        val command = input.trim()
        if (command.isEmpty()) return
        // 运行中输入发给前台进程 stdin，否则作为新命令执行。
        if (state.running) {
            store.sendInput(command)
        } else {
            store.send(command)
        }
        input = ""
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MovoColors.bgCanvas)
            .imePadding()
            .navigationBarsPadding(),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                showLinuxGuide -> LinuxGuide(onOpenEnvironment = onOpenEnvironment)
                state.blocks.isEmpty() -> EmptyHint(state.failMessage)
                else -> BlockList(
                    blocks = state.blocks,
                    onReinput = { input = it },
                )
            }
        }
        StatusBar(
            state = state,
            onSwitchEnvironment = store::switchEnvironment,
            onStop = store::stop,
            onOpenTasks = {
                store.refreshDaemonTasks()
                showTasks = true
            },
            onOpenSessions = { showSessions = true },
            onOpenConsole = onOpenConsole,
        )
        if (!showLinuxGuide) {
            InputRow(
                input = input,
                running = state.running,
                onInputChange = { input = it },
                onSubmit = ::submit,
            )
        }
    }

    if (showTasks) {
        DaemonTasksDialog(
            tasks = state.daemonTasks,
            onDismiss = { showTasks = false },
            onStop = store::stopDaemonTask,
            onLoadLogs = store::daemonLogs,
        )
    }

    if (showSessions) {
        SessionListDialog(
            rows = state.sessions.map { session ->
                SessionDialogRow(
                    id = session.id,
                    environment = session.environment,
                    subtitle = session.cwd,
                    active = session.id == state.activeSessionId,
                    running = session.running,
                    alive = session.alive,
                )
            },
            onDismiss = { showSessions = false },
            onSelect = store::switchSession,
            onRestart = store::restartSession,
            onClose = store::closeSession,
            onNew = store::newSession,
        )
    }
}

@Composable
private fun BlockList(
    blocks: List<TerminalBlockUi>,
    onReinput: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val atBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: Int.MAX_VALUE
            lastVisible >= layoutInfo.totalItemsCount - 1
        }
    }
    // 输出增长只在用户本来就停留在底部时跟随，向上翻历史不被打断。
    LaunchedEffect(blocks.size, blocks.lastOrNull()?.output?.length) {
        if (atBottom && blocks.isNotEmpty()) {
            listState.scrollToItem(blocks.lastIndex)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        items(items = blocks, key = { it.id }) { block ->
            if (block.isSystem) {
                SystemBlock(block)
            } else {
                CommandBlock(block = block, onReinput = onReinput)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CommandBlock(
    block: TerminalBlockUi,
    onReinput: (String) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val exitCode = block.exitCode
    val failed = exitCode != null && exitCode != 0
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .combinedClickable(onClick = {}, onLongClick = { showMenu = true })
                .padding(vertical = 6.dp),
        ) {
            if (failed) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(MiuixTheme.colorScheme.error),
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = blockHeader(block),
                    style = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace),
                )
                if (block.output.isNotEmpty()) {
                    // 原始输出含 ANSI 序列；整段重解析保证流式截断的序列在下一次到达后恢复。
                    val parsedOutput = remember(block.output) { ansiToAnnotatedString(block.output) }
                    Text(
                        text = parsedOutput,
                        style = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (block.running) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        InfiniteProgressIndicator(size = 14.dp)
                        Text(
                            text = stringResource(R.string.terminal_running),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                if (failed) {
                    Text(
                        text = stringResource(R.string.terminal_exit_code, exitCode),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (block.truncated) {
                    Text(
                        text = stringResource(R.string.terminal_output_truncated),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        BlockMenu(
            show = showMenu,
            block = block,
            clipboard = clipboard,
            onDismiss = { showMenu = false },
            onReinput = onReinput,
        )
    }
}

@Composable
private fun blockHeader(block: TerminalBlockUi): AnnotatedString {
    val dim = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val accent = MiuixTheme.colorScheme.primary
    val body = MiuixTheme.colorScheme.onSurface
    return remember(block.cwdAtStart, block.command, dim, accent, body) {
        buildAnnotatedString {
            if (block.cwdAtStart.isNotEmpty()) {
                withStyle(SpanStyle(color = dim)) { append(block.cwdAtStart) }
                append("  ")
            }
            withStyle(SpanStyle(color = accent)) { append("❯") }
            append(" ")
            withStyle(SpanStyle(color = body, fontWeight = FontWeight.Medium)) { append(block.command) }
        }
    }
}

@Composable
private fun BlockMenu(
    show: Boolean,
    block: TerminalBlockUi,
    clipboard: ClipboardManager,
    onDismiss: () -> Unit,
    onReinput: (String) -> Unit,
) {
    WindowListPopup(
        show = show,
        alignment = PopupPositionProvider.Align.Start,
        onDismissRequest = onDismiss,
    ) {
        ListPopupColumn {
            DropdownImpl(
                text = stringResource(R.string.terminal_copy_command),
                optionSize = 3,
                isSelected = false,
                index = 0,
                onSelectedIndexChange = {
                    onDismiss()
                    clipboard.setText(AnnotatedString(block.command))
                },
            )
            DropdownImpl(
                text = stringResource(R.string.terminal_copy_output),
                optionSize = 3,
                isSelected = false,
                index = 1,
                enabled = block.output.isNotEmpty(),
                onSelectedIndexChange = {
                    onDismiss()
                    clipboard.setText(AnnotatedString(ansiPlainText(block.output)))
                },
            )
            DropdownImpl(
                text = stringResource(R.string.terminal_reinput),
                optionSize = 3,
                isSelected = false,
                index = 2,
                onSelectedIndexChange = {
                    onDismiss()
                    onReinput(block.command)
                },
            )
        }
    }
}

@Composable
private fun SystemBlock(block: TerminalBlockUi) {
    Text(
        text = block.output,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    )
}

/**
 * 终端状态栏（外层骨架，Movo 组件）：环境切换胶囊（选中 = Indigo 浅底 + Indigo 文字）、cwd、
 * 会话 / 守护任务 / 控制台三个 44 图标按钮，运行中显示「停止」主操作胶囊。
 */
@Composable
private fun StatusBar(
    state: UserTerminalUiState,
    onSwitchEnvironment: (TerminalEnvironment) -> Unit,
    onStop: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenConsole: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = MovoSpacing.md, end = MovoSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TerminalEnvironmentChip(
            label = "Android",
            selected = state.environment == TerminalEnvironment.ANDROID,
            onClick = { onSwitchEnvironment(TerminalEnvironment.ANDROID) },
        )
        TerminalEnvironmentChip(
            label = if (state.linuxEnvironment == TerminalEnvironment.ALPINE) "Alpine" else "Debian",
            selected = state.environment == state.linuxEnvironment,
            onClick = { onSwitchEnvironment(state.linuxEnvironment) },
        )
        Text(
            text = state.cwd,
            style = MovoTypography.labelRegular.copy(fontFamily = FontFamily.Monospace),
            color = MovoColors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = MovoSpacing.sm),
        )
        MovoIconButton(
            icon = MovoIcons.Layers,
            contentDescription = stringResource(R.string.terminal_sessions),
            onClick = onOpenSessions,
            iconSize = MovoSize.iconMedium,
            tint = MovoColors.textSecondary,
        )
        TerminalMaterialIconButton(
            icon = Icons.Rounded.Insights,
            contentDescription = stringResource(R.string.terminal_daemon_tasks),
            onClick = onOpenTasks,
        )
        if (onOpenConsole != null) {
            MovoIconButton(
                icon = MovoIcons.Terminal,
                contentDescription = stringResource(R.string.terminal_console_mode),
                onClick = onOpenConsole,
                iconSize = MovoSize.iconMedium,
                tint = MovoColors.textSecondary,
            )
        }
        if (state.running) {
            Spacer(Modifier.width(MovoSpacing.xs))
            MovoPillButton(
                label = stringResource(R.string.terminal_stop),
                onClick = onStop,
                primary = true,
            )
        }
    }
}

/** 环境切换胶囊：高 32、圆角 16；选中 Indigo 浅底 + Indigo 文字（Medium），未选中透明底 + 次要色。 */
@Composable
internal fun TerminalEnvironmentChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(MovoRadius.md)
    Box(
        modifier = Modifier
            .height(MovoSize.controlSmall)
            .movoClickable(PressKind.Solid, shape = shape, role = Role.Tab, onClick = onClick)
            .clip(shape)
            .background(if (selected) MovoColors.indigoBg else Color.Transparent)
            .padding(horizontal = MovoSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = if (selected) MovoTypography.labelMedium else MovoTypography.labelRegular,
            color = if (selected) MovoColors.indigoFg else MovoColors.textSecondary,
        )
    }
}

/**
 * 暂无对应 Lucide 图标（需要 `activity`）时的过渡：Material 图标放进 Movo 图标按钮的热区与按压态，
 * 热区 44、图标 20、次要色。
 */
@Composable
internal fun TerminalMaterialIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(MovoSize.touchTarget)
            .movoClickable(PressKind.Icon, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(MovoSize.iconMedium),
            tint = MovoColors.textSecondary,
        )
    }
}

@Composable
private fun InputRow(
    input: String,
    running: Boolean,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val canSend = input.isNotBlank()
    TextField(
        value = input,
        onValueChange = onInputChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MovoSpacing.md)
            .padding(bottom = MovoSpacing.sm),
        label = stringResource(
            if (running) R.string.terminal_input_running_hint else R.string.terminal_input_hint,
        ),
        useLabelAsPlaceholder = true,
        maxLines = 4,
        textStyle = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace),
        trailingIcon = {
            // 主按钮：空闲无内容 = 发送置灰；有内容 = 发送 ↑（规范 8「输入框主按钮」）。
            MovoCircleButton(
                icon = MovoIcons.ArrowUp,
                contentDescription = stringResource(R.string.terminal_send),
                onClick = onSubmit,
                primary = true,
                enabled = canSend,
                modifier = Modifier.padding(end = MovoSpacing.sm),
            )
        },
    )
}

@Composable
private fun LinuxGuide(onOpenEnvironment: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(MovoSpacing.section),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.terminal_linux_not_ready),
            style = MovoTypography.bodyRegular,
            color = MovoColors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(MovoSpacing.md))
        MovoPillButton(
            label = stringResource(R.string.terminal_open_environment),
            onClick = onOpenEnvironment,
            primary = true,
        )
    }
}

@Composable
private fun EmptyHint(message: String? = null) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(MovoSpacing.section),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message ?: stringResource(R.string.terminal_empty_hint),
            style = MovoTypography.bodyRegular,
            color = MovoColors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}
