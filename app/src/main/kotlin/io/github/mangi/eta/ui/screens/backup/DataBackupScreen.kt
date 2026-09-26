package io.github.mangi.eta.ui.screens.backup

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.data.repository.EtaBackupSummary
import io.github.mangi.eta.ui.components.movo.CardFooter
import io.github.mangi.eta.ui.components.movo.CardTitle
import io.github.mangi.eta.ui.components.movo.MovoCard
import io.github.mangi.eta.ui.components.movo.MovoConfirmDialog
import io.github.mangi.eta.ui.components.movo.MovoListPage
import io.github.mangi.eta.ui.components.movo.RowTrailing
import io.github.mangi.eta.ui.components.movo.SettingsRow
import io.github.mangi.eta.ui.theme.MovoSize
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator

private enum class BackupOp { Export, Import }

/**
 * 设置 · 数据备份（规范 8.7 二级页）：一张「备份」卡（导出、导入），原来的警告卡改为卡片页脚。
 * 导出 / 导入的结果和失败原因就地写在对应行的说明里（规范 8.11 不用 Toast）。
 */
@Composable
internal fun DataBackupScreen(
    context: Context,
    onBack: () -> Unit,
    onExport: suspend (OutputStream) -> EtaBackupSummary,
    onImport: suspend (InputStream) -> EtaBackupSummary,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var activeOp by remember { mutableStateOf<BackupOp?>(null) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var importMessage by remember { mutableStateOf<String?>(null) }

    fun failureMessage(throwable: Throwable): String {
        if (throwable is CancellationException) throw throwable
        return throwable.message ?: context.getString(R.string.data_backup_failed)
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            activeOp = BackupOp.Export
            exportMessage = null
            try {
                val output = context.contentResolver.openOutputStream(uri)
                    ?: error(context.getString(R.string.data_backup_file_open_failed))
                val summary = output.use { onExport(it) }
                exportMessage = context.getString(
                    R.string.data_backup_exported,
                    summary.conversationCount,
                    summary.providerCount,
                )
            } catch (throwable: Throwable) {
                exportMessage = failureMessage(throwable)
            } finally {
                busy = false
                activeOp = null
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            pendingImportUri = uri
            showImportDialog = true
        }
    }

    MovoListPage(
        title = stringResource(R.string.data_backup_title),
        onBack = onBack,
    ) {
        item(key = "actions-card") {
            MovoCard {
                CardTitle(stringResource(R.string.data_backup_actions))
                SettingsRow(
                    title = stringResource(R.string.data_backup_export),
                    subtitle = when {
                        activeOp == BackupOp.Export -> stringResource(R.string.data_backup_working)
                        exportMessage != null -> exportMessage
                        else -> stringResource(R.string.data_backup_export_summary)
                    },
                    enabled = !busy,
                    trailing = if (activeOp == BackupOp.Export) {
                        RowTrailing.Custom { InfiniteProgressIndicator(size = MovoSize.iconMedium) }
                    } else {
                        RowTrailing.Arrow()
                    },
                    onClick = {
                        exportLauncher.launch(defaultBackupFileName())
                    },
                )
                SettingsRow(
                    title = stringResource(R.string.data_backup_import),
                    subtitle = when {
                        activeOp == BackupOp.Import -> stringResource(R.string.data_backup_working)
                        importMessage != null -> importMessage
                        else -> stringResource(R.string.data_backup_import_summary)
                    },
                    enabled = !busy,
                    trailing = if (activeOp == BackupOp.Import) {
                        RowTrailing.Custom { InfiniteProgressIndicator(size = MovoSize.iconMedium) }
                    } else {
                        RowTrailing.Arrow()
                    },
                    showDivider = false,
                    onClick = {
                        importLauncher.launch(arrayOf("application/json", "text/plain"))
                    },
                )
                CardFooter(
                    listOf(
                        stringResource(R.string.movo_backup_footer_1),
                        stringResource(R.string.movo_backup_footer_2),
                    ),
                )
            }
        }
    }

    MovoConfirmDialog(
        show = showImportDialog,
        title = stringResource(R.string.data_backup_import_confirm_title),
        message = stringResource(R.string.data_backup_import_confirm_summary),
        confirmText = if (busy) {
            stringResource(R.string.data_backup_working)
        } else {
            stringResource(R.string.action_import)
        },
        destructive = true,
        cancelEnabled = !busy,
        confirmEnabled = !busy,
        onDismissRequest = {
            if (!busy) {
                showImportDialog = false
                pendingImportUri = null
            }
        },
        onConfirm = confirm@{
            val uri = pendingImportUri ?: return@confirm
            showImportDialog = false
            scope.launch {
                busy = true
                activeOp = BackupOp.Import
                importMessage = null
                try {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: error(context.getString(R.string.data_backup_file_open_failed))
                    val summary = input.use { onImport(it) }
                    importMessage = context.getString(
                        R.string.data_backup_imported,
                        summary.conversationCount,
                        summary.providerCount,
                    )
                } catch (throwable: Throwable) {
                    importMessage = failureMessage(throwable)
                } finally {
                    pendingImportUri = null
                    busy = false
                    activeOp = null
                }
            }
        },
    )
}

private fun defaultBackupFileName(): String =
    "Eta-backup-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.eta-backup.json"
