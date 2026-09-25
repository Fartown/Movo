package io.github.mangi.eta.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.diagnostics.MemoryDiagnostics
import io.github.mangi.eta.ui.screens.diagnostics.DiagnosticsFormat
import io.github.mangi.eta.ui.screens.diagnostics.LocalRunLogOpener
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 任务卡住时的提示：任务超过 30 秒没有收到任何数据（自动重试也算在内，执行工具时不算），
 * 就在对话末尾说明并给出日志入口。
 * 只看本对话里的任务（按消息 ID 里的界面任务 ID 匹配）。
 */
@Composable
internal fun RunStallNotice(messageIds: List<String>, modifier: Modifier = Modifier) {
    val open = LocalRunLogOpener.current ?: return
    var stall by remember { mutableStateOf<Pair<String, Long>?>(null) }
    LaunchedEffect(messageIds) {
        while (true) {
            stall = MemoryDiagnostics.boundRuns().entries.firstNotNullOfOrNull { (wire, run) ->
                if (messageIds.none { it.contains(wire) }) return@firstNotNullOfOrNull null
                MemoryDiagnostics.silenceMs(run)?.takeIf { it >= DiagnosticsFormat.STALL_MS }?.let { run to it }
            }
            delay(1_000)
        }
    }
    val (run, silence) = stall ?: return
    Text(
        text = "已 ${silence / 1_000} 秒没有收到数据，网络可能不稳定 · 查看日志",
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = modifier
            .fillMaxWidth()
            .clickable { open(run) }
            .padding(horizontal = 20.dp, vertical = 8.dp),
    )
}
