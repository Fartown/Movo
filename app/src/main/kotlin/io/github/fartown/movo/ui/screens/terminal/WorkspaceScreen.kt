package io.github.fartown.movo.ui.screens.terminal

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.R
import io.github.fartown.movo.ui.app.WorkspaceEntry
import io.github.fartown.movo.ui.app.WorkspaceFileStore
import io.github.fartown.movo.ui.components.movo.CardFooter
import io.github.fartown.movo.ui.components.movo.CardTitle
import io.github.fartown.movo.ui.components.movo.MovoCard
import io.github.fartown.movo.ui.components.movo.MovoDivider
import io.github.fartown.movo.ui.components.movo.MovoListPage
import io.github.fartown.movo.ui.components.movo.RowLeading
import io.github.fartown.movo.ui.components.movo.RowTrailing
import io.github.fartown.movo.ui.components.movo.SettingsRow
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoIcon
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.theme.MovoSize
import io.github.fartown.movo.ui.theme.MovoSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun WorkspaceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context.applicationContext) { WorkspaceFileStore(context) }
    val scope = rememberCoroutineScope()
    var path by rememberSaveable { mutableStateOf("") }
    var pendingExport by rememberSaveable { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<WorkspaceEntry>>(emptyList()) }
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var publicAccess by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    val accessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        publicAccess = Environment.isExternalStorageManager()
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            busy = true
            try {
                var succeeded = 0
                uris.forEach { if (store.importFile(it)) succeeded++ }
                message = if (succeeded == uris.size) context.getString(R.string.capability_workspace_imported)
                else context.getString(R.string.capability_workspace_partial_import, succeeded)
                if (succeeded > 0) path = "imports"
                revision++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = context.getString(R.string.capability_workspace_failed)
            } finally {
                busy = false
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val source = pendingExport
        pendingExport = null
        if (uri != null && source != null) scope.launch {
            busy = true
            try {
                store.exportFile(source, uri)
                message = context.getString(R.string.capability_workspace_exported)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = context.getString(R.string.capability_workspace_failed)
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(path, revision) {
        try {
            entries = store.list(path)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            entries = emptyList()
            message = context.getString(R.string.capability_workspace_failed)
        }
    }
    // 页面卡片：私有工作区（导入、公共目录访问 + 结果就地反馈 + 页脚说明）→ 文件列表分段卡片（长列表保持惰性）。
    MovoListPage(
        title = stringResource(R.string.capability_workspace),
        onBack = onBack,
        itemSpacing = 0.dp,
    ) {
        item(key = "actions") {
            MovoCard {
                CardTitle(stringResource(R.string.capability_workspace_private))
                SettingsRow(
                    title = stringResource(R.string.capability_workspace_import),
                    enabled = !busy,
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                )
                SettingsRow(
                    title = stringResource(R.string.capability_workspace_public),
                    subtitle = if (publicAccess) {
                        stringResource(R.string.capability_workspace_public_granted)
                    } else {
                        stringResource(R.string.capability_workspace_public_summary)
                    },
                    trailing = RowTrailing.External(),
                    showDivider = false,
                    onClick = {
                        try {
                            accessLauncher.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${context.packageName}")))
                        } catch (_: android.content.ActivityNotFoundException) {
                            message = context.getString(R.string.capability_workspace_failed)
                        }
                    },
                )
                message?.let { text ->
                    MovoDivider(start = MovoSpacing.lg)
                    CardNotice(text = text, error = text == context.getString(R.string.capability_workspace_failed))
                }
                CardFooter(listOf(stringResource(R.string.movo_workspace_footer)))
            }
        }
        item(key = "files-gap") { CardGap() }
        val hasParent = path.isNotBlank()
        val empty = entries.isEmpty()
        item(key = "path") {
            // 首段只放标题；末段一定是「暂无文件」或最后一个条目。
            Column(Modifier.movoCardSegment(first = true, last = false)) {
                CardTitle(if (path.isBlank()) stringResource(R.string.capability_workspace_files) else path)
            }
        }
        if (hasParent) {
            item(key = "parent") {
                SettingsRow(
                    title = stringResource(R.string.capability_workspace_parent),
                    modifier = Modifier.movoCardSegment(first = false, last = false),
                    onClick = { path = path.substringBeforeLast('/', "") },
                )
            }
        }
        if (empty) {
            item(key = "empty") {
                SettingsRow(
                    title = stringResource(R.string.capability_workspace_empty),
                    subtitle = stringResource(R.string.capability_workspace_empty_summary),
                    modifier = Modifier.movoCardSegment(first = false, last = true),
                    trailing = RowTrailing.None,
                    showDivider = false,
                )
            }
        }
        itemsIndexed(entries, key = { _, entry -> entry.path }) { index, entry ->
            val last = index == entries.lastIndex
            SettingsRow(
                title = entry.name,
                subtitle = if (entry.directory) stringResource(R.string.capability_workspace_directory)
                    else stringResource(
                        R.string.capability_workspace_file_export,
                        Formatter.formatShortFileSize(context, entry.size),
                    ),
                modifier = Modifier.movoCardSegment(first = false, last = last),
                leading = RowLeading.Icon(if (entry.directory) MovoIcons.Folder else MovoIcons.File),
                trailing = if (entry.directory) {
                    RowTrailing.Arrow()
                } else {
                    RowTrailing.Custom {
                        MovoIcon(MovoIcons.Download, null, size = MovoSize.iconSmall, tint = MovoColors.textTertiary)
                    }
                },
                enabled = !busy,
                showDivider = !last,
                onClick = {
                    if (entry.directory) path = entry.path else {
                        pendingExport = entry.path
                        exportLauncher.launch(entry.name)
                    }
                },
            )
        }
    }
}
