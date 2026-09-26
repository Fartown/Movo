package io.github.fartown.movo.ui.screens.run

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.R
import io.github.fartown.movo.ui.components.StreamingMarkdownState
import io.github.fartown.movo.ui.components.WorkPhase
import io.github.fartown.movo.ui.components.WorkPhaseCrossfade
import io.github.fartown.movo.ui.components.WorkStatusIcon
import io.github.fartown.movo.ui.components.WorkStatusIconKind
import io.github.fartown.movo.ui.components.WorkSteps
import io.github.fartown.movo.ui.components.movo.rememberIsScrolled
import io.github.fartown.movo.ui.components.movo.CardFooter
import io.github.fartown.movo.ui.components.movo.CardTitle
import io.github.fartown.movo.ui.components.movo.MovoCard
import io.github.fartown.movo.ui.components.movo.MovoPage
import io.github.fartown.movo.ui.components.movo.MovoPillButton
import io.github.fartown.movo.ui.components.movo.MovoShimmerText
import io.github.fartown.movo.ui.components.movo.MovoDivider
import io.github.fartown.movo.ui.components.operatingAppName
import io.github.fartown.movo.ui.model.AgentChatMessageUi
import io.github.fartown.movo.ui.model.ThinkingMessageUi
import io.github.fartown.movo.ui.model.ToolActivityMessageUi
import io.github.fartown.movo.ui.model.ToolActivityStatusUi
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoMotion
import io.github.fartown.movo.ui.theme.MovoSpacing
import io.github.fartown.movo.ui.theme.MovoTypography
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Text

/**
 * 执行详情页 `Run/Detail`（规范 8.8，Figma「20 / 21 · 执行详情」）：一次任务的完整记录。
 * 概要卡（状态、计时、起止时刻；执行中操作其他 App 时加底部栏）→ 步骤卡（全部 `Work/Step`，页脚隐私说明）→
 * 执行中底部沿用对话页输入框（[composer]，整屏唯一的停止入口），结束后只读。
 */
@Composable
internal fun RunDetailScreen(
    title: String,
    steps: List<AgentChatMessageUi>?,
    outcome: io.github.fartown.movo.ui.components.WorkOutcome? = null,
    onBack: () -> Unit,
    onOpenBrowser: () -> Unit,
    onSwitchToApp: ((String) -> Unit)?,
    composer: @Composable () -> Unit,
) {
    val running = steps.orEmpty().any { message ->
        (message is ThinkingMessageUi && message.isStreaming) ||
            (message is ToolActivityMessageUi && message.status == ToolActivityStatusUi.Running)
    }
    val listState = rememberLazyListState()
    // 顶栏 Q7 按列表位置判断：执行中自动跟随是代码滚动，手指滚动检测感知不到（审查 D1）。
    val scrolled by listState.rememberIsScrolled()
    MovoPage(title = title, onBack = onBack, scrolled = scrolled) { contentPadding, sidePadding ->
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            // 执行中自动跟随最新一步（新步骤出现时滚到底部）。
            LaunchedEffect(steps?.size, running) {
                if (running && steps != null) listState.animateScrollToItem(1)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = sidePadding + MovoSpacing.pageEdge,
                    end = sidePadding + MovoSpacing.pageEdge,
                    top = contentPadding.calculateTopPadding() + MovoSpacing.lg,
                    bottom = MovoSpacing.section,
                ),
                verticalArrangement = Arrangement.spacedBy(MovoSpacing.lg),
            ) {
                if (steps == null) {
                    item(key = "missing") {
                        Text(
                            stringResource(R.string.movo_run_detail_missing),
                            style = MovoTypography.bodyRegular,
                            color = MovoColors.textSecondary,
                            modifier = Modifier.padding(start = MovoSpacing.lg),
                        )
                    }
                } else {
                    item(key = "summary") { RunSummaryCard(steps, running, onSwitchToApp, outcome) }
                    item(key = "steps") {
                        MovoCard(bottomPadding = 0.dp) {
                            // 新步骤出现时卡片高度过渡 `standard`（与执行卡一致，规范 9.4「执行卡 · 新步骤」）。
                            Column(Modifier.animateContentSize(MovoMotion.standard())) {
                                CardTitle(stringResource(R.string.movo_run_detail_steps))
                                WorkSteps(
                                    messages = steps,
                                    running = running,
                                    onOpenBrowser = onOpenBrowser,
                                    currentBrowserMessageId = null,
                                    retainedStreamingStates = remember { emptyMap<String, StreamingMarkdownState>() },
                                    modifier = Modifier.padding(bottom = 6.dp),
                                )
                                CardFooter(listOf(stringResource(R.string.movo_run_detail_privacy)))
                            }
                        }
                    }
                }
            }
            // 任务结束：输入框淡出 120ms、高度收起 `standard`，页面只读（规范 8.8「状态变化」）。
            AnimatedVisibility(
                visible = running,
                enter = fadeIn(MovoMotion.fast()) + expandVertically(MovoMotion.standard()),
                exit = fadeOut(MovoMotion.fastExit()) + shrinkVertically(MovoMotion.standard()),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(start = MovoSpacing.pageEdge, end = MovoSpacing.pageEdge, bottom = MovoSpacing.sm),
                ) { composer() }
            }
        }
    }
}

/**
 * `Run/Summary` 概要卡：状态图标 16 → 12 → 状态 Body/Strong（执行中 Q3 光带）+ 右侧计时 Numeric/Label 次要色；
 * 第二行（卡内 44）「15:02 开始」/「15:02 开始·15:03 结束」；执行中且在操作其他 App 时加底部栏「切过去看」。
 */
@Composable
private fun RunSummaryCard(
    steps: List<AgentChatMessageUi>,
    running: Boolean,
    onSwitchToApp: ((String) -> Unit)?,
    outcome: io.github.fartown.movo.ui.components.WorkOutcome? = null,
) {
    val tools = steps.filterIsInstance<ToolActivityMessageUi>()
    val failedIndex = io.github.fartown.movo.ui.components.unrecoveredFailedStep(tools)
    // 失败 / 停止的一轮用时与对话里的摘要条一致，按整轮算。
    val firstStart = outcome?.startedAt ?: tools.mapNotNull { it.startedAtMillis }.minOrNull()
    val lastFinish = outcome?.finishedAt ?: tools.mapNotNull { it.finishedAtMillis }.maxOrNull()
    val now by produceState(System.currentTimeMillis(), running) {
        while (running) {
            value = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000)
        }
        value = System.currentTimeMillis()
    }
    val locale: Locale = LocalConfiguration.current.locales[0]
    val clock = remember(locale) { SimpleDateFormat("HH:mm", locale) }
    MovoCard(bottomPadding = 0.dp) {
        Column(modifier = Modifier.padding(MovoSpacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 任务结束时概要卡状态交叉淡化（规范 8.8「状态变化」、9.4）：图标淡化 + 缩放 0.72 ↔ 1，文字淡入淡出，`fast`。
                val stopped = outcome?.kind == io.github.fartown.movo.ui.components.WorkOutcome.Kind.Stopped
                val unfinished = outcome?.kind == io.github.fartown.movo.ui.components.WorkOutcome.Kind.Unfinished
                WorkStatusIcon(
                    when {
                        running -> WorkStatusIconKind.Running
                        stopped -> WorkStatusIconKind.Stopped
                        failedIndex >= 0 || unfinished -> WorkStatusIconKind.Failed
                        else -> WorkStatusIconKind.Done
                    },
                )
                Spacer(Modifier.width(MovoSpacing.md))
                val stepsInRun = outcome?.steps ?: 0
                WorkPhaseCrossfade(
                    phase = when {
                        running -> WorkPhase.Running
                        stopped -> WorkPhase.Stopped
                        failedIndex >= 0 -> WorkPhase.Failed
                        unfinished -> WorkPhase.Unfinished
                        else -> WorkPhase.Done
                    },
                    modifier = Modifier.weight(1f),
                ) { phase ->
                    MovoShimmerText(
                        text = when (phase) {
                            WorkPhase.Running, WorkPhase.Paused -> if (tools.isNotEmpty()) {
                                stringResource(R.string.movo_work_running_step, tools.size)
                            } else {
                                stringResource(R.string.movo_work_analyzing)
                            }
                            WorkPhase.Stopped -> stringResource(R.string.movo_work_stopped_steps, stepsInRun)
                            WorkPhase.Failed -> stringResource(R.string.movo_work_failed_step, failedIndex + 1)
                            WorkPhase.Unfinished -> stringResource(R.string.movo_work_unfinished_steps, stepsInRun)
                            WorkPhase.Done -> if (tools.isNotEmpty()) {
                                stringResource(R.string.movo_work_done_steps, tools.size)
                            } else {
                                stringResource(R.string.movo_work_done)
                            }
                        },
                        style = MovoTypography.bodyStrong,
                        color = MovoColors.textPrimary,
                        active = phase == WorkPhase.Running,
                    )
                }
                if (firstStart != null) {
                    val elapsed = (if (running) now else lastFinish ?: now) - firstStart
                    Text(
                        text = if (running) clockText(elapsed) else elapsedText(elapsed),
                        style = MovoTypography.numericLabel,
                        color = MovoColors.textSecondary,
                    )
                }
            }
            if (firstStart != null) {
                val started = clock.format(Date(firstStart))
                Text(
                    text = if (!running && lastFinish != null) {
                        stringResource(R.string.movo_run_detail_span, started, clock.format(Date(lastFinish)))
                    } else {
                        stringResource(R.string.movo_run_detail_started, started)
                    },
                    style = MovoTypography.labelRegular,
                    color = MovoColors.textSecondary,
                    modifier = Modifier.padding(start = 28.dp, top = MovoSpacing.xs),
                )
            }
        }
        val app = steps.operatingAppName()
        // 任务结束：底部栏淡出 120ms、高度收起 `standard`（规范 8.8「状态变化」）。离场期间沿用最后的 App 名。
        val shownApp = remember { arrayOf<String?>(null) }
        if (app != null) shownApp[0] = app
        AnimatedVisibility(
            visible = running && app != null && onSwitchToApp != null,
            enter = fadeIn(MovoMotion.fast()) + expandVertically(MovoMotion.standard()),
            exit = fadeOut(MovoMotion.fastExit()) + shrinkVertically(MovoMotion.standard()),
        ) {
            val label = shownApp[0].orEmpty()
            Column {
                MovoDivider(start = MovoSpacing.lg)
                Row(
                    modifier = Modifier.fillMaxWidth().height(56.dp).padding(start = MovoSpacing.lg, end = MovoSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.movo_work_operating_app, label),
                        style = MovoTypography.labelRegular,
                        color = MovoColors.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    MovoPillButton(label = stringResource(R.string.movo_run_switch_to_app), onClick = { onSwitchToApp?.invoke(label) })
                }
            }
        }
    }
}

private fun clockText(elapsedMillis: Long): String {
    val seconds = (elapsedMillis / 1000).coerceAtLeast(0)
    return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}

@Composable
private fun elapsedText(elapsedMillis: Long): String {
    val seconds = ((elapsedMillis + 500) / 1000).coerceAtLeast(1).toInt()
    return if (seconds < 60) {
        stringResource(R.string.movo_work_elapsed_seconds, seconds)
    } else {
        stringResource(R.string.movo_work_elapsed_minutes, seconds / 60, seconds % 60)
    }
}
