package io.github.fartown.movo.ui.screens.diagnostics

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.fartown.movo.diagnostics.RunTrace
import io.github.fartown.movo.diagnostics.TraceStatus
import io.github.fartown.movo.diagnostics.field
import io.github.fartown.movo.diagnostics.runlog.RunLog
import io.github.fartown.movo.diagnostics.runlog.RunLogExporter
import io.github.fartown.movo.ui.components.movo.MovoDoneSwapIcon
import io.github.fartown.movo.ui.components.movo.MovoFailureDialog
import io.github.fartown.movo.ui.components.movo.MovoSpinner
import io.github.fartown.movo.ui.components.movo.PressKind
import io.github.fartown.movo.ui.components.movo.movoClickable
import io.github.fartown.movo.ui.components.movo.rememberDoneFlash
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.theme.MovoMotion
import io.github.fartown.movo.ui.theme.MovoSize
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * 完整运行日志的导出（Figma 定稿 23，docs/DESIGN_SYSTEM.md 8.10「23 完整日志」）：
 * 任务详情页右上角菜单三项——导出完整日志 / 导出为 Markdown 文件 / 复制到剪贴板。
 */

/** 这次任务的完整日志：目录名，以及不能导出的原因（为空表示能导出）。 */
internal data class FullLog(val dir: String?, val refusal: String?) {
    val exportable: Boolean get() = dir != null && refusal == null
}

/** 目录名来自运行记录里的 `run.started`；能不能导出看完整日志的目录账本（只读内存）。 */
internal fun fullLogOf(run: RunTrace): FullLog {
    val dir = run.entries.lastOrNull { it.event == "run.started" }?.field("run_log")?.takeIf { it.isNotBlank() }
    if (run.status == TraceStatus.RUNNING) return FullLog(dir, "任务结束后才能导出")
    val store = RunLog.store() ?: return FullLog(null, RunLogExporter.refusal(null))
    return FullLog(dir, RunLogExporter.refusal(dir?.let(store::dirInfo)))
}

/**
 * 导出完整日志的状态，放在 Activity 作用域：转屏不打断，离开页面也会写完；Activity 真正结束时才取消。
 * 写完之前先让写线程把排队的记录写完，写的时候这次任务不被淘汰、不被清空。
 */
internal class RunLogExportViewModel(application: Application) : AndroidViewModel(application) {
    enum class State { Idle, Running, Done, Failed }

    var state by mutableStateOf(State.Idle)
        private set

    fun export(dir: String, uri: Uri) {
        if (state == State.Running) return
        state = State.Running
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val store = RunLog.store() ?: return@withContext false
                val runDir = File(store.root, dir)
                store.pin(dir)
                try {
                    store.awaitIdle()
                    if (!runDir.isDirectory) return@withContext false
                    resolver.openOutputStream(uri)?.use { RunLogExporter.export(runDir, it) } != null
                } catch (failure: Exception) {
                    false
                } finally {
                    store.unpin(dir)
                }
            }
            // 写坏的文件尽量删掉；文件提供方不支持删除时，失败弹窗里已经写明可能留下了不完整的文件。
            if (!ok) withContext(Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(resolver, uri) } }
            state = if (ok) State.Done else State.Failed
        }
    }

    fun acknowledge() {
        if (state != State.Running) state = State.Idle
    }
}

/**
 * 任务详情页顶栏「导出」：菜单三项（定稿 23-1）。导出完整日志时图标原地转圈，写完换成 ✓ 停 1.4 秒；
 * 不能导出时第一项不可用、说明写原因（23-2、23-3）；失败弹「完整日志导出失败」（23-5）。
 */
@Composable
internal fun RunExportAction(run: RunTrace, fileName: String, buildText: () -> String) {
    val context = LocalContext.current
    val owner = remember(context) { context.findActivity() }
    val exportModel: RunLogExportViewModel = if (owner != null) viewModel(viewModelStoreOwner = owner) else viewModel()
    val scope = rememberCoroutineScope()
    val menu = remember { MutableTransitionState(false) }
    val done = rememberDoneFlash()
    var markdownFailed by remember { mutableStateOf(false) }
    val fullLog = fullLogOf(run)
    val markdownLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(buildText().toByteArray(Charsets.UTF_8)) } != null
                }.getOrDefault(false)
            }
            if (ok) done.trigger() else markdownFailed = true
        }
    }
    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val dir = fullLog.dir
        if (uri != null && dir != null) exportModel.export(dir, uri)
    }
    LaunchedEffect(exportModel.state) {
        if (exportModel.state == RunLogExportViewModel.State.Done) {
            done.trigger()
            exportModel.acknowledge()
        }
    }
    MovoFailureDialog(
        show = markdownFailed,
        title = "运行日志导出失败",
        message = "无法写入所选文件，请换个位置再试。",
        onDismiss = { markdownFailed = false },
    )
    MovoFailureDialog(
        show = exportModel.state == RunLogExportViewModel.State.Failed,
        title = "完整日志导出失败",
        message = "无法写入所选位置，请换个位置再试。\n目标位置可能留下了不完整的文件。",
        onDismiss = exportModel::acknowledge,
    )
    val busy = exportModel.state == RunLogExportViewModel.State.Running
    Box {
        ExportIconButton(busy = busy, done = done.active) { if (!busy) menu.targetState = true }
        if (menu.currentState || menu.targetState) {
            ExportMenu(
                state = menu,
                onDismiss = { menu.targetState = false },
                items = listOf(
                    ExportMenuItem(
                        label = "导出完整日志",
                        icon = MovoIcons.Download,
                        caption = fullLog.refusal ?: "含对话、思考、截图和查询结果\n原样记录，没有遮挡",
                        enabled = fullLog.exportable,
                    ) {
                        menu.targetState = false
                        fullLog.dir?.let { zipLauncher.launch(RunLogExporter.fileName(it)) }
                    },
                    ExportMenuItem(label = "导出为 Markdown 文件", icon = MovoIcons.FileText, caption = "只有阶段和耗时，不含对话") {
                        menu.targetState = false
                        markdownLauncher.launch(fileName)
                    },
                    ExportMenuItem(label = "复制到剪贴板", icon = MovoIcons.Copy) {
                        menu.targetState = false
                        copyToClipboard(context, buildText())
                        done.trigger()
                    },
                ),
            )
        }
    }
}

/** 顶栏导出按钮：下载图标；导出中原地换成转圈；完成换成 ✓（9.3.1 图标状态切换）。 */
@Composable
private fun ExportIconButton(busy: Boolean, done: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(MovoSize.touchTarget)
            .movoClickable(PressKind.Icon, enabled = !busy, onClick = onClick)
            .semantics { contentDescription = if (busy) "正在导出" else if (done) "已完成" else "导出" },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = busy,
            transitionSpec = { fadeIn(MovoMotion.fast()) togetherWith fadeOut(MovoMotion.fastExit()) },
            contentAlignment = Alignment.Center,
            label = "exportBusy",
        ) { exporting ->
            if (exporting) {
                MovoSpinner(size = MovoSize.iconLarge, color = MovoColors.textPrimary)
            } else {
                MovoDoneSwapIcon(icon = MovoIcons.Download, done = done, size = MovoSize.iconLarge, tint = MovoColors.textPrimary)
            }
        }
    }
}

private fun Context.findActivity(): ComponentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    return null
}
