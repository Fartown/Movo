package io.github.mangi.eta.ui.screens.diagnostics

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.mangi.eta.diagnostics.DiagnosticStore
import io.github.mangi.eta.diagnostics.RunTrace
import io.github.mangi.eta.diagnostics.SystemEvent
import io.github.mangi.eta.diagnostics.TimelineItem
import io.github.mangi.eta.diagnostics.TraceStatus
import io.github.mangi.eta.ui.theme.MovoColors
import io.github.mangi.eta.ui.theme.MovoSize
import io.github.mangi.eta.ui.theme.MovoSpacing

/*
 * 运行日志：先定义功能再做界面，见 docs/research/log-page/运行日志功能定义.md，
 * 界面对应 Figma「Movo 首页」22–27。
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

    LogPage(
        title = "运行日志",
        onBack = onBack,
        action = {
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
        item(key = "description") {
            PageDescription("每次任务的模型请求、工具调用和当时的设备状态。保存最近 20 个任务，不含对话内容。")
        }
        if (live == null) return@LogPage
        if (failedCount > 0 || runningCount > 0) {
            item(key = "filters") {
                Row(
                    modifier = Modifier.padding(top = MovoSpacing.xxl),
                    horizontalArrangement = Arrangement.spacedBy(MovoSpacing.sm),
                ) {
                    LogFilterChip("全部", effective == LogFilter.ALL) { filter = LogFilter.ALL }
                    if (failedCount > 0) LogFilterChip("失败 $failedCount", effective == LogFilter.FAILED) { filter = LogFilter.FAILED }
                    if (runningCount > 0) LogFilterChip("进行中 $runningCount", effective == LogFilter.RUNNING) { filter = LogFilter.RUNNING }
                }
            }
        }
        if (visible.isEmpty()) {
            item(key = "empty") { EmptyHint("还没有任务记录。发送一条消息后，这里会记下它的请求和耗时。") }
        }
        visible.groupBy { format.day(it.startedAt) }.forEach { (day, dayRuns) ->
            item(key = "day-$day") { SectionLabel(format.dayTitle(day)) }
            item(key = "card-$day") {
                LogCard {
                    dayRuns.forEachIndexed { index, run ->
                        LogRow(
                            title = format.runTitle(run, titles.of(run)),
                            subtitle = format.listSubtitle(run, live.nowElapsed),
                            value = format.listValue(run, live.nowElapsed),
                            showDivider = index < dayRuns.lastIndex,
                            onClick = { onOpenRun(run.id) },
                        ) { StatusIcon(run.status) }
                    }
                }
            }
        }
        item(key = "system-label") { SectionLabel("任务之外") }
        item(key = "system") {
            LogCard {
                LogRow(
                    title = "系统事件",
                    subtitle = "进程启动、网络变化、后台服务",
                    value = "${live.trace.system.size} 条",
                    showDivider = false,
                    onClick = onOpenSystem,
                ) { StepIcon(Icons.Rounded.Description, MovoColors.textSecondary) }
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

    LogPage(
        title = run?.let { format.runTitle(it, titles.of(it)) } ?: "任务详情",
        onBack = onBack,
        action = run?.let { current ->
            {
                ExportAction(fileName = "movo-log-${current.id}.md") {
                    format.exportMarkdown(
                        header = exportHeader(),
                        runs = listOf(current),
                        system = emptyList(),
                        raw = current.entries,
                        generatedAt = System.currentTimeMillis(),
                        scope = "单个任务 ${current.id}",
                    )
                }
            }
        },
    ) {
        if (live == null) return@LogPage
        if (run == null) {
            item(key = "missing") { EmptyHint("这个任务的记录已经不在了。运行日志只保存最近 20 个任务。") }
            return@LogPage
        }
        val now = live.nowElapsed
        item(key = "summary") {
            val running = run.status == TraceStatus.RUNNING
            SummaryCard(
                status = run.status,
                statusText = format.statusLine(run),
                timer = format.summaryTimer(run, now),
                meta = format.summaryMeta(run),
                footer = if (running) format.runningHint(run, now, live.silenceByRun[run.id]) else null,
                footerAction = run.conversationId?.takeIf { running }?.let { id -> "回到对话" to { onOpenConversation(id) } },
            )
        }
        format.explain(run)?.let { explanation ->
            item(key = "reason-label") { SectionLabel("原因") }
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
            item(key = "breakdown-label") { SectionLabel("耗时") }
            item(key = "breakdown") { BreakdownCard(parts, format) }
        }
        item(key = "timeline-label") { SectionLabel("时间线") }
        item(key = "timeline") {
            LogCard {
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
            }
        }
        item(key = "note") {
            CardNote(
                if (run.status == TraceStatus.RUNNING) "进行中的任务每秒刷新；结束后这里会写明结果和耗时构成。"
                else "日志只记录请求阶段、工具名和设备状态，不含对话内容、工具结果和 API Key。",
            )
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
        ) { StepIcon(CompactionIcon, MovoColors.textTertiary) }
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

/** 25 · 系统事件：任务之外的设备事件，最新在前，按天分组。 */
@Composable
internal fun DiagnosticsSystemScreen(onBack: () -> Unit) {
    val live = rememberDiagnosticsLive()
    val format = rememberDiagnosticsFormat()
    LogPage(title = "系统事件", onBack = onBack) {
        item(key = "description") {
            PageDescription("设备和 App 状态的变化。任务进行中发生的，也会记在那个任务的时间线里。")
        }
        if (live == null) return@LogPage
        val events: List<SystemEvent> = live.trace.system
        if (events.isEmpty()) item(key = "empty") { EmptyHint("还没有系统事件") }
        events.groupBy { format.day(it.timeMillis) }.forEach { (day, dayEvents) ->
            item(key = "day-$day") { SectionLabel(format.dayTitle(day)) }
            item(key = "card-$day") {
                LogCard {
                    dayEvents.forEach { event ->
                        StepRow(
                            title = event.title,
                            subtitle = event.detail,
                            duration = format.clock(event.timeMillis),
                            first = true,
                            last = true,
                        ) { StepIcon(event.icon(), event.tone.color()) }
                    }
                }
            }
        }
        item(key = "note") { CardNote("系统事件保留最近 7 天。") }
    }
}
