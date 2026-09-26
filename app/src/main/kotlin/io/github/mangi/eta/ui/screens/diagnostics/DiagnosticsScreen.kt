package io.github.mangi.eta.ui.screens.diagnostics

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.mangi.eta.diagnostics.DiagnosticStore
import io.github.mangi.eta.diagnostics.RunTrace
import io.github.mangi.eta.diagnostics.SystemEvent
import io.github.mangi.eta.diagnostics.TimelineItem
import io.github.mangi.eta.diagnostics.TraceStatus
import io.github.mangi.eta.ui.components.movo.CardFooter
import io.github.mangi.eta.ui.components.movo.CardTitle
import io.github.mangi.eta.ui.components.movo.MovoCard
import io.github.mangi.eta.ui.components.movo.MovoListPage
import io.github.mangi.eta.ui.components.movo.RowLeading
import io.github.mangi.eta.ui.components.movo.RowTrailing
import io.github.mangi.eta.ui.components.movo.SettingsRow
import io.github.mangi.eta.ui.theme.MovoColors
import io.github.mangi.eta.ui.theme.MovoIcons
import io.github.mangi.eta.ui.theme.MovoSize
import io.github.mangi.eta.ui.theme.MovoSpacing

/*
 * 运行日志：先定义功能再做界面，见 docs/research/log-page/运行日志功能定义.md；
 * 界面按规范 8.10 与 Figma「定稿 · 设计稿」22–27：列表页骨架 MovoListPage，分组标题在卡内，说明写在卡片页脚。
 */

private enum class LogFilter { ALL, FAILED, RUNNING }

private val RunTrace.isProblem: Boolean
    get() = status == TraceStatus.FAILED || status == TraceStatus.INTERRUPTED

/** 22 · 运行日志：以任务为单位，最新在前，按天分组。 */
@Composable
internal fun DiagnosticsScreen(
    onBack: () -> Unit,
    onOpenRun: (String) -> Unit,
    onOpenSystem: () -> Unit,
) {
    val live = rememberDiagnosticsLive()
    val titles = rememberConversationTitles()
    val format = rememberDiagnosticsFormat()
    var filter by rememberSaveable { mutableStateOf(LogFilter.ALL) }
    val runs = live?.trace?.runs.orEmpty().take(DiagnosticStore.MAX_RUNS)
    val failedCount = runs.count { it.isProblem }
    val runningCount = runs.count { it.status == TraceStatus.RUNNING }
    // 计数为 0 的芯片不显示；正在看的分类清空后回到全部。
    val effective = when {
        filter == LogFilter.FAILED && failedCount == 0 -> LogFilter.ALL
        filter == LogFilter.RUNNING && runningCount == 0 -> LogFilter.ALL
        else -> filter
    }
    val visible = runs.filter {
        when (effective) {
            LogFilter.ALL -> true
            LogFilter.FAILED -> it.isProblem
            LogFilter.RUNNING -> it.status == TraceStatus.RUNNING
        }
    }

    MovoListPage(
        title = "运行日志",
        onBack = onBack,
        actions = {
            ExportAction(fileName = "movo-log-${System.currentTimeMillis() / 1000}.md") {
                val current = live ?: return@ExportAction ""
                format.exportMarkdown(
                    header = exportHeader(),
                    runs = runs,
                    system = current.trace.system,
                    raw = current.entries,
                    generatedAt = System.currentTimeMillis(),
                    scope = "全部（${runs.size} 个任务）",
                )
            }
        },
    ) {
        if (live == null) return@MovoListPage
        // 顶栏下 12 为筛选芯片（单选）；计数为 0 的芯片不显示，只剩「全部」时整行不显示。
        if (failedCount > 0 || runningCount > 0) {
            item(key = "filters") {
                Row(horizontalArrangement = Arrangement.spacedBy(MovoSpacing.sm)) {
                    LogFilterChip("全部", effective == LogFilter.ALL) { filter = LogFilter.ALL }
                    if (failedCount > 0) LogFilterChip("失败 $failedCount", effective == LogFilter.FAILED) { filter = LogFilter.FAILED }
                    if (runningCount > 0) LogFilterChip("进行中 $runningCount", effective == LogFilter.RUNNING) { filter = LogFilter.RUNNING }
                }
            }
        }
        if (visible.isEmpty()) {
            item(key = "empty") {
                MovoCard { EmptyHint("还没有任务记录。", "发送一条消息后，这里会记下它的请求和耗时。") }
            }
        }
        // 按天一张卡片，卡内标题「今天」「昨天」或日期。
        visible.groupBy { format.day(it.startedAt) }.forEach { (day, dayRuns) ->
            item(key = "card-$day") {
                MovoCard {
                    CardTitle(format.dayTitle(day))
                    dayRuns.forEachIndexed { index, run ->
                        SettingsRow(
                            title = format.runTitle(run, titles.of(run)),
                            subtitle = format.listSubtitle(run, live.nowElapsed),
                            leading = RowLeading.Custom { StatusIcon(run.status) },
                            trailing = RowTrailing.Arrow(format.listValue(run, live.nowElapsed)),
                            showDivider = index < dayRuns.lastIndex,
                            onClick = { onOpenRun(run.id) },
                        )
                    }
                }
            }
        }
        // 最后一张「任务之外」：系统事件入口 + 保存策略页脚。
        item(key = "system") {
            MovoCard {
                CardTitle("任务之外")
                SettingsRow(
                    title = "系统事件",
                    subtitle = "进程启动、网络变化、后台服务",
                    leading = RowLeading.Icon(MovoIcons.FileText),
                    trailing = RowTrailing.Arrow("${live.trace.system.size} 条"),
                    showDivider = false,
                    onClick = onOpenSystem,
                )
                CardFooter(listOf("保存最近 20 个任务，不含对话内容。"))
            }
        }
    }
}

/** 23 / 24 · 任务详情：结论 → 原因与建议 → 耗时构成 → 时间线。 */
@Composable
internal fun DiagnosticsRunScreen(
    runId: String,
    onBack: () -> Unit,
    onOpenModelSettings: () -> Unit,
    onOpenConversation: (String) -> Unit,
) {
    val context = LocalContext.current
    val live = rememberDiagnosticsLive()
    val titles = rememberConversationTitles()
    val format = rememberDiagnosticsFormat()
    val run = live?.trace?.runs?.firstOrNull { it.id == runId }
    // 默认展开最值得看的那次请求：失败任务的最后一次失败、进行中任务正在进行的请求；同时只展开一行。
    var expanded by rememberSaveable(runId) { mutableStateOf<String?>(null) }
    var expandedInitialized by rememberSaveable(runId) { mutableStateOf(false) }
    LaunchedEffect(run != null) {
        if (run == null || expandedInitialized) return@LaunchedEffect
        expandedInitialized = true
        expanded = when (run.status) {
            TraceStatus.FAILED -> run.requests.lastOrNull { it.status == TraceStatus.FAILED }?.let { "request-${it.id}" }
            TraceStatus.RUNNING -> run.requests.lastOrNull { it.status == TraceStatus.RUNNING }?.let { "request-${it.id}" }
            else -> null
        }
    }

    MovoListPage(
        title = run?.let { format.runTitle(it, titles.of(it)) } ?: "任务详情",
        onBack = onBack,
        actions = {
            if (run != null) {
                ExportAction(fileName = "movo-log-${run.id}.md") {
                    format.exportMarkdown(
                        header = exportHeader(),
                        runs = listOf(run),
                        system = emptyList(),
                        raw = run.entries,
                        generatedAt = System.currentTimeMillis(),
                        scope = "单个任务 ${run.id}",
                    )
                }
            }
        },
    ) {
        if (live == null) return@MovoListPage
        if (run == null) {
            item(key = "missing") {
                MovoCard { EmptyHint("这个任务的记录已经不在了。", "运行日志只保存最近 20 个任务。") }
            }
            return@MovoListPage
        }
        val now = live.nowElapsed
        val running = run.status == TraceStatus.RUNNING
        val silence = live.silenceByRun[run.id]
        // 结论卡：进行中只有 30 秒没收到数据时才出底部栏（提示 +「回到对话」），规范 8.10。
        item(key = "summary") {
            val stall = if (running) format.stallHint(silence) else null
            SummaryCard(
                status = run.status,
                statusText = format.statusLine(run),
                timer = format.summaryTimer(run, now),
                meta = format.summaryMeta(run),
                footer = stall,
                footerAction = run.conversationId?.takeIf { stall != null }?.let { id -> "回到对话" to { onOpenConversation(id) } },
            )
        }
        format.explain(run)?.let { explanation ->
            item(key = "reason") {
                ReasonCard(explanation) { action ->
                    when (action) {
                        FailureAction.MODEL_SETTINGS -> onOpenModelSettings()
                        FailureAction.BACKGROUND_SETTINGS -> runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                }
            }
        }
        run.breakdown()?.let(format::breakdownParts)?.takeIf { it.isNotEmpty() }?.let { parts ->
            item(key = "breakdown") { BreakdownCard(parts, format) }
        }
        item(key = "timeline") {
            MovoCard {
                // 进行中且没卡住时，标题右侧写实时状态（多久前收到数据 / 正在执行的步骤与已等时长）。
                CardTitle("时间线", trailing = if (running) format.liveNote(run, now, silence) else null)
                if (run.timeline.isEmpty()) EmptyHint("还没有记录到请求")
                run.timeline.forEachIndexed { index, item ->
                    TimelineRow(
                        item = item,
                        run = run,
                        nowElapsed = now,
                        first = index == 0,
                        last = index == run.timeline.lastIndex,
                        expanded = expanded == item.key,
                        onToggle = { expanded = if (expanded == item.key) null else item.key },
                        format = format,
                    )
                }
                CardFooter(
                    if (running) {
                        listOf("进行中的任务每秒刷新。", "结束后这里会写明结果和耗时构成。")
                    } else {
                        listOf("只记录请求阶段、工具名和设备状态。", "不含对话内容、工具结果和 API Key。")
                    },
                )
            }
        }
    }
}

@Composable
private fun TimelineRow(
    item: TimelineItem,
    run: RunTrace,
    nowElapsed: Long,
    first: Boolean,
    last: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    format: DiagnosticsFormat,
) {
    when (item) {
        is TimelineItem.Request -> {
            val request = item.request
            StepRow(
                title = format.requestTitle(request),
                subtitle = format.requestSubtitle(request, item.offsetMs),
                duration = request.durationMs?.let(format::compact) ?: format.compact(nowElapsed - request.startElapsed),
                first = first,
                last = last,
                detailLabel = "网络阶段",
                detail = if (expanded) format.requestDetail(request) else null,
                onClick = onToggle,
            ) { StatusIcon(request.status, MovoSize.iconSmall) }
        }
        is TimelineItem.Tool -> {
            val tool = item.tool
            val status = when {
                tool.durationMs == null && run.status == TraceStatus.RUNNING -> TraceStatus.RUNNING
                tool.success == false -> TraceStatus.FAILED
                else -> TraceStatus.SUCCEEDED
            }
            StepRow(
                title = format.toolTitle(tool),
                subtitle = format.toolSubtitle(tool, item.offsetMs),
                duration = tool.durationMs?.let(format::compact) ?: format.compact(nowElapsed - tool.startElapsed),
                first = first,
                last = last,
            ) { StatusIcon(status, MovoSize.iconSmall) }
        }
        is TimelineItem.Compaction -> StepRow(
            title = "上下文压缩",
            subtitle = format.compactionSubtitle(item),
            duration = "",
            first = first,
            last = last,
            muted = true,
        ) { StepIcon(CompactionIcon, MovoColors.textSecondary) }
        is TimelineItem.System -> StepRow(
            title = item.event.title,
            subtitle = format.systemSubtitle(item.event, item.offsetMs),
            duration = "",
            first = first,
            last = last,
            muted = true,
        ) { StepIcon(item.event.icon(), item.event.tone.color()) }
    }
}

/** 25 · 系统事件：任务之外的设备事件，最新在前，按天一张卡片；说明写在最后一张卡片的页脚。 */
@Composable
internal fun DiagnosticsSystemScreen(onBack: () -> Unit) {
    val live = rememberDiagnosticsLive()
    val format = rememberDiagnosticsFormat()
    val footer = listOf("任务中发生的事件，也记在该任务的时间线里。", "系统事件保留最近 7 天。")
    MovoListPage(title = "系统事件", onBack = onBack) {
        if (live == null) return@MovoListPage
        val events: List<SystemEvent> = live.trace.system
        if (events.isEmpty()) {
            item(key = "empty") {
                MovoCard {
                    EmptyHint("还没有系统事件")
                    CardFooter(footer)
                }
            }
        }
        val days = events.groupBy { format.day(it.timeMillis) }.toList()
        days.forEachIndexed { dayIndex, (day, dayEvents) ->
            item(key = "card-$day") {
                MovoCard {
                    CardTitle(format.dayTitle(day))
                    dayEvents.forEach { event ->
                        StepRow(
                            title = event.title,
                            subtitle = event.detail,
                            duration = format.clock(event.timeMillis),
                            first = true,
                            last = true,
                        ) { StepIcon(event.icon(), event.tone.color()) }
                    }
                    if (dayIndex == days.lastIndex) CardFooter(footer)
                }
            }
        }
    }
}
