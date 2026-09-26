package io.github.fartown.movo.ui.screens.run

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
import io.github.fartown.movo.ui.components.WorkSteps
import io.github.fartown.movo.ui.components.movo.CardFooter
import io.github.fartown.movo.ui.components.movo.CardTitle
import io.github.fartown.movo.ui.components.movo.MovoCard
import io.github.fartown.movo.ui.components.movo.MovoOrb
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
import io.github.fartown.movo.ui.theme.MovoIcon
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.theme.MovoSize
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
    onBack: () -> Unit,
    onOpenBrowser: () -> Unit,
    onSwitchToApp: ((String) -> Unit)?,
    composer: @Composable () -> Unit,
) {
    val running = steps.orEmpty().any { message ->
        (message is ThinkingMessageUi && message.isStreaming) ||
            (message is ToolActivityMessageUi && message.status == ToolActivityStatusUi.Running)
    }
    MovoPage(title = title, onBack = onBack) { contentPadding, sidePadding ->
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            val listState = rememberLazyListState()
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
                    item(key = "summary") { RunSummaryCard(steps, running, onSwitchToApp) }
                    item(key = "steps") {
                        MovoCard(bottomPadding = 0.dp) {
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
            if (running) {
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
) {
    val tools = steps.filterIsInstance<ToolActivityMessageUi>()
    val failedIndex = tools.indexOfFirst { it.status == ToolActivityStatusUi.Failed }
    val firstStart = tools.mapNotNull { it.startedAtMillis }.minOrNull()
    val lastFinish = tools.mapNotNull { it.finishedAtMillis }.maxOrNull()
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
                Box(Modifier.size(MovoSize.iconSmall), contentAlignment = Alignment.Center) {
                    when {
                        running -> MovoOrb(size = MovoSize.iconSmall)
                        failedIndex >= 0 -> MovoIcon(MovoIcons.X, null, size = MovoSize.iconSmall, tint = MovoColors.roseFg)
                        else -> MovoIcon(MovoIcons.Check, null, size = MovoSize.iconSmall, tint = MovoColors.greenFg)
                    }
                }
                Spacer(Modifier.width(MovoSpacing.md))
                MovoShimmerText(
                    text = when {
                        running && tools.isNotEmpty() -> stringResource(R.string.movo_work_running_step, tools.size)
                        running -> stringResource(R.string.movo_work_analyzing)
                        failedIndex >= 0 -> stringResource(R.string.movo_work_failed_step, failedIndex + 1)
                        tools.isNotEmpty() -> stringResource(R.string.movo_work_done_steps, tools.size)
                        else -> stringResource(R.string.movo_work_done)
                    },
                    style = MovoTypography.bodyStrong,
                    color = MovoColors.textPrimary,
                    active = running,
                    modifier = Modifier.weight(1f),
                )
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
        if (running && app != null && onSwitchToApp != null) {
            MovoDivider(start = MovoSpacing.lg)
            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(start = MovoSpacing.lg, end = MovoSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.movo_work_operating_app, app),
                    style = MovoTypography.labelRegular,
                    color = MovoColors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                MovoPillButton(label = stringResource(R.string.movo_run_switch_to_app), onClick = { onSwitchToApp(app) })
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
