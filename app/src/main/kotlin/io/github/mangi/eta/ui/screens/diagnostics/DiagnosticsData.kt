package io.github.mangi.eta.ui.screens.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.BuildConfig
import io.github.mangi.eta.agent.overlay.toolDisplayNameResource
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.diagnostics.DiagnosticEntry
import io.github.mangi.eta.diagnostics.DiagnosticTrace
import io.github.mangi.eta.diagnostics.DiagnosticTraceBuilder
import io.github.mangi.eta.diagnostics.MemoryDiagnostics
import io.github.mangi.eta.diagnostics.RunTrace
import io.github.mangi.eta.diagnostics.TraceStatus
import io.github.mangi.eta.diagnostics.field
import io.github.mangi.eta.ui.theme.MovoColors
import io.github.mangi.eta.ui.theme.MovoTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

/** 页面每秒刷新一次的数据：整理好的任务视图 + 进行中请求的实时状态。 */
internal data class DiagnosticsLive(
    val trace: DiagnosticTrace,
    val entries: List<DiagnosticEntry>,
    val nowElapsed: Long,
    /** 诊断任务号 → 距上次有进展多久（跨越自动重试累计；执行工具时不算）。 */
    val silenceByRun: Map<String, Long>,
)

/** 只有页面可见时轮询；记录没变就不重建任务视图。 */
@Composable
internal fun rememberDiagnosticsLive(): DiagnosticsLive? = produceState<DiagnosticsLive?>(null) {
    var key: Pair<Int, Long?>? = null
    var trace: DiagnosticTrace? = null
    var entries: List<DiagnosticEntry> = emptyList()
    while (true) {
        val snapshot = MemoryDiagnostics.buffer.snapshot()
        val nextKey = snapshot.entries.size to snapshot.entries.lastOrNull()?.sequence
        if (nextKey != key || trace == null) {
            entries = snapshot.entries
            trace = withContext(Dispatchers.Default) { DiagnosticTraceBuilder.build(entries) }
            key = nextKey
        }
        val silence = trace!!.runs.filter { it.status == TraceStatus.RUNNING }
            .mapNotNull { run -> MemoryDiagnostics.silenceMs(run.id)?.let { run.id to it } }
            .toMap()
        value = DiagnosticsLive(trace!!, entries, MemoryDiagnostics.elapsedClock(), silence)
        delay(1_000)
    }
}.value

@Composable
internal fun rememberDiagnosticsFormat(): DiagnosticsFormat {
    val context = LocalContext.current
    return remember(context) {
        DiagnosticsFormat(toolName = { name -> toolDisplayNameResource(name)?.let(context::getString) ?: name })
    }
}

/** 会话标题只在本机界面显示（决策 D2），导出不带。 */
internal class ConversationTitles(
    private val byId: Map<String, String>,
    private val byRuns: List<Pair<String, String>>,
) {
    fun of(run: RunTrace): String? = run.conversationId?.let(byId::get)
        ?: run.wireRunId?.let { wire -> byRuns.firstOrNull { (runs, _) -> runs.contains("\"$wire\"") }?.second }

    companion object {
        val Empty = ConversationTitles(emptyMap(), emptyList())
    }
}

@Composable
internal fun rememberConversationTitles(): ConversationTitles {
    val context = LocalContext.current
    var titles by remember { mutableStateOf(ConversationTitles.Empty) }
    LaunchedEffect(Unit) {
        titles = withContext(Dispatchers.IO) {
            runCatching {
                val rows = EtaDatabase.get(context).conversationDao().conversations()
                ConversationTitles(
                    rows.associate { it.id to it.title },
                    rows.map { it.appliedRuntimeRunIdsJson to it.title },
                )
            }.getOrDefault(ConversationTitles.Empty)
        }
    }
    return titles
}

/**
 * 从对话打开运行日志（决策 D3）：参数是诊断任务号，找不到对应任务时为 null，打开列表。
 * 只有主界面提供；对话浮层里为 null，不显示入口。
 */
internal val LocalRunLogOpener = androidx.compose.runtime.staticCompositionLocalOf<((String?) -> Unit)?> { null }

/** 按消息 ID 里的界面任务 ID 找到对应的诊断任务号。 */
internal object DiagnosticsLinks {
    fun runForMessage(messageId: String): String? {
        MemoryDiagnostics.boundRuns().entries.firstOrNull { messageId.contains(it.key) }?.let { return it.value }
        return MemoryDiagnostics.buffer.snapshot().entries.lastOrNull { entry ->
            entry.event == "run.bound" && entry.field("wire_run")?.let { it.isNotBlank() && messageId.contains(it) } == true
        }?.context?.run
    }
}

internal fun exportHeader(): ExportHeader = ExportHeader(
    device = "${Build.MANUFACTURER} ${Build.MODEL}",
    android = "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）",
    app = "${BuildConfig.VERSION_NAME}（${BuildConfig.BUILD_TYPE}）",
)

/** 顶栏「导出」：弹出菜单，导出为 Markdown 文件或复制到剪贴板。 */
@Composable
internal fun ExportAction(fileName: String, buildText: () -> String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(buildText().toByteArray(Charsets.UTF_8)) } != null
                }.getOrDefault(false)
            }
            Toast.makeText(context, if (ok) "已导出" else "导出失败", Toast.LENGTH_SHORT).show()
        }
    }
    Box {
        TopBarIcon(Icons.Rounded.FileDownload, "导出", onClick = { open = true })
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = MovoColors.bgSurface,
        ) {
            MenuItem("导出为 Markdown 文件", Icons.Rounded.FileDownload) {
                open = false
                launcher.launch(fileName)
            }
            MenuItem("复制到剪贴板", Icons.Rounded.ContentCopy) {
                open = false
                copyToClipboard(context, buildText())
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = MovoTypography.bodyRegular, color = MovoColors.textPrimary) },
        leadingIcon = { Icon(icon, null, Modifier.size(20.dp), tint = MovoColors.textPrimary) },
        onClick = onClick,
    )
}

private fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Movo 运行日志", text))
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}
