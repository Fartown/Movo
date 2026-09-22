package io.github.mangi.eta.ui.screens.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.model.ModelRequestTrace
import io.github.mangi.eta.agent.runtime.modelFailureHint
import io.github.mangi.eta.diagnostics.DiagnosticEntry
import io.github.mangi.eta.diagnostics.DiagnosticLevel
import io.github.mangi.eta.diagnostics.MemoryDiagnostics
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf(MemoryDiagnostics.buffer.snapshot()) }
    var active by remember { mutableStateOf(ModelRequestTrace.activeSnapshots()) }
    var paused by remember { mutableStateOf(false) }
    var warningsOnly by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<Long?>(null) }
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault()) }
    // Only the visible viewer polls. Producers never build a whole list for each token/read.
    LaunchedEffect(paused) {
        while (!paused) {
            snapshot = MemoryDiagnostics.buffer.snapshot()
            active = ModelRequestTrace.activeSnapshots()
            delay(1_000)
        }
    }
    val visible = remember(snapshot, warningsOnly, query) {
        snapshot.entries.asReversed().filter {
            it.matches(query, warningsOnly)
        }
    }
    val copy: (String) -> Unit = { text ->
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Movo 运行日志", text))
        Toast.makeText(context, "日志已复制", Toast.LENGTH_SHORT).show()
    }

    MiuixScaffoldPage(title = "运行日志", onBack = onBack) {
        item(key = "info") {
            Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("仅保存在内存 · 进程重启后清空", style = MiuixTheme.textStyles.body1)
                    Text("${snapshot.entries.size} / ${MemoryDiagnostics.buffer.maxEntries} 条 · 日志文本 ${snapshot.bytes / 1024} KiB / 4 MiB · 已淘汰 ${snapshot.dropped} 条",
                        style = MiuixTheme.textStyles.footnote1)
                    Text("从错误记录的请求编号查看完整链路。包含网络、后台状态和模型阶段，不记录对话、工具内容或 API Key。",
                        style = MiuixTheme.textStyles.footnote1)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(text = if (paused) "继续刷新" else "暂停刷新", onClick = { paused = !paused }, modifier = Modifier.weight(1f))
                        TextButton(text = "清空", onClick = {
                            MemoryDiagnostics.buffer.clear()
                            snapshot = MemoryDiagnostics.buffer.snapshot()
                            expanded = null
                        }, modifier = Modifier.weight(1f))
                    }
                    if (paused) Text("已暂停画面刷新，后台仍在记录。", style = MiuixTheme.textStyles.footnote1)
                }
            }
        }
        if (active.isNotEmpty()) item(key = "active") {
            Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("进行中的模型请求", style = MiuixTheme.textStyles.body1)
                    active.forEach { (ids, fields) ->
                        Text("${ids.run} ${ids.request} · ${fields["purpose"]}\n${fields["stage"]} · 已用 ${((fields["duration_ms"] as? Long) ?: 0) / 1000}s · 距最后数据 ${fields["last_byte_ago_ms"] ?: "尚未收到"} ms",
                            style = MiuixTheme.textStyles.footnote1,
                            modifier = Modifier.clickable { query = ids.request; warningsOnly = false })
                    }
                }
            }
        }
        item(key = "filter") {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(value = query, onValueChange = { query = it }, label = "搜索错误码、R1 / Q1 或阶段", singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(text = if (warningsOnly) "显示全部" else "只看异常", onClick = { warningsOnly = !warningsOnly }, modifier = Modifier.weight(1f))
                    TextButton(text = "复制最近 ${visible.size.coerceAtMost(80)} 条", enabled = visible.isNotEmpty(),
                        onClick = { copy(visible.take(80).joinToString("\n\n") { it.text() }) }, modifier = Modifier.weight(1f))
                }
                Text("${visible.size} 条匹配 · 最新记录在前 · 点开查看详情", style = MiuixTheme.textStyles.footnote1)
            }
        }
        if (visible.isEmpty()) item(key = "empty") {
            Text(if (snapshot.entries.isEmpty()) "暂无日志。执行任务后，这里会显示请求和运行状态。" else "没有匹配的日志，请调整搜索或异常筛选。",
                modifier = Modifier.padding(20.dp), style = MiuixTheme.textStyles.body2)
        }
        items(visible, key = { it.sequence }) { entry ->
            Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Column(Modifier.fillMaxWidth().clickable { expanded = if (expanded == entry.sequence) null else entry.sequence }
                    .padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${formatter.format(Instant.ofEpochMilli(entry.timeMillis))} · ${entry.level} · ${entry.context.run} ${entry.context.request}",
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (entry.level == DiagnosticLevel.ERROR) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Text(entry.title(), style = MiuixTheme.textStyles.body1)
                    val code = entry.field("code")
                    if (code != null && code != "unknown") Text("${modelFailureHint(code)} · $code", style = MiuixTheme.textStyles.body2)
                    Text(listOfNotNull(entry.field("stage"), entry.field("purpose"), entry.field("duration_ms")?.let { "${it}ms" }).joinToString(" · "),
                        style = MiuixTheme.textStyles.footnote1)
                    if (expanded == entry.sequence) {
                        SelectionContainer {
                            Text(entry.details, style = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(text = "复制这条", onClick = { copy(entry.text()) }, modifier = Modifier.weight(1f))
                            if (entry.context.request.isNotBlank()) TextButton(text = "查看该请求", onClick = {
                                query = entry.context.request
                                warningsOnly = false
                            }, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

private fun DiagnosticEntry.field(key: String): String? = details.lineSequence().firstOrNull { it.startsWith("$key=") }?.substringAfter('=')

private fun DiagnosticEntry.title(): String = when (event) {
    "attempt.failed" -> "模型请求失败"
    "retry.scheduled" -> "即将重试模型请求"
    "attempt.started" -> "模型请求开始"
    "attempt.completed" -> "模型请求完成"
    "attempt.cancelled" -> "模型请求已取消"
    "http.response_headers" -> "已收到 HTTP 响应头"
    "http.first_byte" -> "已收到首批响应数据"
    "sse.done_marker" -> "收到 [DONE] 标记"
    "sse.terminal" -> "收到模型终态事件"
    "response.output" -> "模型响应内容检查"
    "run.started" -> "任务开始"
    "run.completed" -> "任务完成"
    "run.failed" -> "任务失败"
    "run.cancelled" -> "任务已停止"
    "app.background" -> "App 已进入后台"
    "app.foreground" -> "App 已回到前台"
    "network.lost" -> "网络连接已丢失"
    else -> event
}
