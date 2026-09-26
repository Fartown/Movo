package io.github.mangi.eta.ui.screens.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
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
import io.github.mangi.eta.ui.components.movo.MovoIconButton
import io.github.mangi.eta.ui.components.movo.PressKind
import io.github.mangi.eta.ui.components.movo.movoClickable
import io.github.mangi.eta.ui.components.movo.movoSurface
import io.github.mangi.eta.ui.theme.LocalReducedMotion
import io.github.mangi.eta.ui.theme.MovoColors
import io.github.mangi.eta.ui.theme.MovoElevation
import io.github.mangi.eta.ui.theme.MovoIcon
import io.github.mangi.eta.ui.theme.MovoIconData
import io.github.mangi.eta.ui.theme.MovoIcons
import io.github.mangi.eta.ui.theme.MovoMotion
import io.github.mangi.eta.ui.theme.MovoRadius
import io.github.mangi.eta.ui.theme.MovoSize
import io.github.mangi.eta.ui.theme.MovoSpacing
import io.github.mangi.eta.ui.theme.MovoTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

/** 对话中失败任务卡（Figma 26）要用的数据：诊断任务号（给「查看日志」）+ 原因文字。 */
internal data class RunFailureLink(val runId: String, val failure: ChatFailure)

/**
 * 按消息找到这次失败任务的原因，给对话里的失败卡（26）用；找不到任务或任务没有失败时为 null。
 * 只在组合时算一次，不轮询。
 */
@Composable
internal fun rememberRunFailure(messageId: String): RunFailureLink? {
    val format = rememberDiagnosticsFormat()
    return produceState<RunFailureLink?>(null, messageId, format) {
        value = withContext(Dispatchers.Default) {
            val runId = DiagnosticsLinks.runForMessage(messageId) ?: return@withContext null
            val run = DiagnosticTraceBuilder.build(MemoryDiagnostics.buffer.snapshot().entries).runs
                .firstOrNull { it.id == runId } ?: return@withContext null
            format.chatFailure(run)?.let { RunFailureLink(runId, it) }
        }
    }.value
}

internal fun exportHeader(): ExportHeader = ExportHeader(
    device = "${Build.MANUFACTURER} ${Build.MODEL}",
    android = "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）",
    app = "${BuildConfig.VERSION_NAME}（${BuildConfig.BUILD_TYPE}）",
)

/** 顶栏「导出」（download 24）：弹出 `Popover/Menu`，导出为 Markdown 文件或复制到剪贴板（Figma 27）。 */
@Composable
internal fun ExportAction(fileName: String, buildText: () -> String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val menu = remember { MutableTransitionState(false) }
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
        MovoIconButton(icon = MovoIcons.Download, contentDescription = "导出", onClick = { menu.targetState = true })
        if (menu.currentState || menu.targetState) {
            ExportMenu(
                state = menu,
                onDismiss = { menu.targetState = false },
                onExportFile = {
                    menu.targetState = false
                    launcher.launch(fileName)
                },
                onCopy = {
                    menu.targetState = false
                    copyToClipboard(context, buildText())
                },
            )
        }
    }
}

/** 菜单右缘对齐页面边距线 20：顶栏右内边距是 6，所以相对按钮右缘再内缩 14。 */
private val MenuEndInset = MovoSpacing.pageEdge - 6.dp

/** Popup 四周给 E3 阴影留的透明区域，否则阴影会被窗口裁掉。 */
private val MenuShadowRoom = MovoSpacing.section + MovoSpacing.lg

/** Figma 27 的菜单宽。 */
private val MenuMinWidth = 220.dp

/** 锚在导出按钮下方：顶部贴顶栏底（按钮下 6），右缘对齐边距线。 */
private class ExportMenuPosition(
    private val endInset: Int,
    private val gap: Int,
    private val room: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(
        x = anchorBounds.right - endInset - popupContentSize.width + room,
        y = anchorBounds.bottom + gap - room,
    )
}

/** `Popover/Menu`：圆角 20、内边距 8、项高 44 圆角 12、E3；从锚点缩放 0.96 → 1 淡入 `fast`，退场淡出 120ms（规范 9.3）。 */
@Composable
private fun ExportMenu(
    state: MutableTransitionState<Boolean>,
    onDismiss: () -> Unit,
    onExportFile: () -> Unit,
    onCopy: () -> Unit,
) {
    val density = LocalDensity.current
    val reduced = LocalReducedMotion.current
    val position = remember(density) {
        with(density) {
            ExportMenuPosition(
                endInset = MenuEndInset.roundToPx(),
                gap = (MovoSize.topBar - MovoSize.touchTarget).roundToPx() / 2,
                room = MenuShadowRoom.roundToPx(),
            )
        }
    }
    Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, clippingEnabled = false),
    ) {
        Box(
            modifier = Modifier
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                .padding(MenuShadowRoom),
        ) {
            AnimatedVisibility(
                visibleState = state,
                enter = if (reduced) {
                    fadeIn(MovoMotion.fast(MovoMotion.EasingEnter))
                } else {
                    fadeIn(MovoMotion.fast(MovoMotion.EasingEnter)) +
                        scaleIn(MovoMotion.fast(MovoMotion.EasingEnter), initialScale = 0.96f, transformOrigin = TransformOrigin(1f, 0f))
                },
                exit = fadeOut(MovoMotion.fastExit()),
            ) {
                Column(
                    modifier = Modifier
                        .width(IntrinsicSize.Max)
                        .widthIn(min = MenuMinWidth)
                        .movoSurface(RoundedCornerShape(MovoRadius.lg), MovoElevation.Overlay)
                        .padding(MovoSpacing.sm),
                ) {
                    MenuItem("导出为 Markdown 文件", MovoIcons.Download, onExportFile)
                    MenuItem("复制到剪贴板", MovoIcons.Copy, onCopy)
                }
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, icon: MovoIconData, onClick: () -> Unit) {
    val shape = RoundedCornerShape(MovoRadius.sm)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(MovoSize.touchTarget)
            .clip(shape)
            .movoClickable(PressKind.Row, shape = shape, onClick = onClick)
            .padding(horizontal = MovoSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MovoIcon(icon, contentDescription = null, size = MovoSize.iconMedium, tint = MovoColors.textPrimary)
        Spacer(Modifier.width(MovoSpacing.md))
        Text(label, style = MovoTypography.bodyRegular, color = MovoColors.textPrimary, maxLines = 1)
    }
}

private fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Movo 运行日志", text))
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}
