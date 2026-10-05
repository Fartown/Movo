package io.github.fartown.movo.ui.screens.terminal

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.fartown.movo.R
import io.github.fartown.movo.agent.terminal.LinuxDistribution
import io.github.fartown.movo.agent.terminal.LinuxEnvironmentPaths
import io.github.fartown.movo.agent.terminal.LinuxFileExplorer
import io.github.fartown.movo.agent.terminal.ShellProcessSupervisor
import io.github.fartown.movo.ui.components.movo.CardFooter
import io.github.fartown.movo.ui.components.movo.MovoCard
import io.github.fartown.movo.ui.components.movo.MovoListPage
import io.github.fartown.movo.ui.components.movo.RowLeading
import io.github.fartown.movo.ui.components.movo.RowTrailing
import io.github.fartown.movo.ui.components.movo.SettingsRow
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.theme.MovoSpacing
import io.github.fartown.movo.ui.theme.MovoTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text

/**
 * Linux rootfs 只读文件浏览：目录列举与文件读取都经一次性 root Shell 完成，
 * 查看文件时进入屏内查看态，页面返回键先退回列表再退出页面。
 */
@Composable
internal fun LinuxFilesScreen(
    context: Context,
    distribution: String,
    onBack: () -> Unit,
) {
    val appContext = context.applicationContext
    val linuxDistribution = remember(distribution) {
        LinuxDistribution.entries.firstOrNull { it.wireName == distribution }
    }
    val rootfsDir = remember(linuxDistribution) {
        linuxDistribution?.let { LinuxEnvironmentPaths.rootfsDir(appContext, it) }
    }
    val installed = rootfsDir != null && LinuxEnvironmentPaths.rootfsReady(rootfsDir.absolutePath)

    val shellSupervisor = remember { ShellProcessSupervisor() }
    DisposableEffect(Unit) {
        onDispose { shellSupervisor.beginClosing() }
    }

    var currentPath by remember { mutableStateOf("/") }
    var entries by remember { mutableStateOf<List<LinuxFileExplorer.Entry>?>(null) }
    var listError by remember { mutableStateOf<Int?>(null) }
    var openFilePath by remember { mutableStateOf<String?>(null) }
    var fileResult by remember { mutableStateOf<LinuxFileExplorer.ReadResult?>(null) }

    LaunchedEffect(currentPath, linuxDistribution) {
        val dir = rootfsDir ?: return@LaunchedEffect
        if (!installed) return@LaunchedEffect
        entries = null
        listError = null
        val result = withContext(Dispatchers.IO) {
            LinuxFileExplorer.list(shellSupervisor, dir, currentPath)
        }
        when (result) {
            is LinuxFileExplorer.ListResult.Success -> entries = result.entries
            LinuxFileExplorer.ListResult.NotDirectory ->
                listError = R.string.linux_files_error_not_directory
            LinuxFileExplorer.ListResult.Unreadable,
            LinuxFileExplorer.ListResult.CommandFailed,
            LinuxFileExplorer.ListResult.NotInstalled ->
                listError = R.string.linux_files_error_unreadable
        }
    }

    LaunchedEffect(openFilePath) {
        val path = openFilePath ?: return@LaunchedEffect
        val dir = rootfsDir ?: return@LaunchedEffect
        fileResult = null
        fileResult = withContext(Dispatchers.IO) {
            LinuxFileExplorer.readText(shellSupervisor, dir, path)
        }
    }

    fun closeFile() {
        openFilePath = null
        fileResult = null
    }

    // 查看文件时系统返回键先退回列表，再退出页面。
    val viewerBackState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = viewerBackState,
        isBackEnabled = openFilePath != null,
        onBackCompleted = { closeFile() },
    )

    // 列表用分段卡片（目录可能有上千项，保持惰性加载），卡片之间用 CardGap。
    MovoListPage(
        title = stringResource(R.string.linux_files_title),
        onBack = { if (openFilePath != null) closeFile() else onBack() },
        itemSpacing = 0.dp,
    ) {
        when {
            linuxDistribution == null -> {
                item(key = "invalid-distribution") {
                    MovoCard(bottomPadding = 0.dp) {
                        CardStateText(stringResource(R.string.linux_files_invalid_distribution))
                    }
                }
            }
            !installed -> {
                item(key = "not-installed") {
                    MovoCard(bottomPadding = 0.dp) {
                        CardStateText(stringResource(R.string.linux_files_not_installed))
                    }
                }
            }
            openFilePath != null -> {
                item(key = "viewer") {
                    MovoCard {
                        PathHeader(openFilePath.orEmpty())
                        when (val result = fileResult) {
                            is LinuxFileExplorer.ReadResult.Text -> {
                                SelectionContainer {
                                    Text(
                                        text = result.content,
                                        style = MovoTypography.labelRegular.copy(fontFamily = FontFamily.Monospace),
                                        color = MovoColors.textPrimary,
                                        modifier = Modifier
                                            .padding(horizontal = MovoSpacing.lg)
                                            .padding(top = MovoSpacing.sm, bottom = MovoSpacing.md),
                                    )
                                }
                                if (result.truncated) {
                                    CardFooter(listOf(stringResource(R.string.linux_files_truncated_hint)))
                                }
                            }
                            LinuxFileExplorer.ReadResult.Binary ->
                                CardStateText(stringResource(R.string.linux_files_binary_hint))
                            LinuxFileExplorer.ReadResult.NotFile ->
                                CardStateText(stringResource(R.string.linux_files_error_not_file))
                            LinuxFileExplorer.ReadResult.Unreadable,
                            LinuxFileExplorer.ReadResult.CommandFailed,
                            LinuxFileExplorer.ReadResult.NotInstalled ->
                                CardStateText(stringResource(R.string.linux_files_error_unreadable))
                            null -> Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(MovoSpacing.lg),
                                contentAlignment = Alignment.CenterStart,
                            ) { TerminalSpinner() }
                        }
                    }
                }
            }
            else -> {
                val currentEntries = entries
                val hasParent = currentPath != "/"
                val listed = listError == null && !currentEntries.isNullOrEmpty()
                val hasTail = listError != null || currentEntries != null
                item(key = "path-bar") {
                    Column(Modifier.movoCardSegment(first = true, last = !hasParent && !hasTail)) {
                        PathHeader(currentPath)
                        if (currentEntries == null && listError == null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = MovoSpacing.lg, vertical = MovoSpacing.md),
                                contentAlignment = Alignment.CenterStart,
                            ) { TerminalSpinner() }
                        }
                    }
                }
                if (hasParent) {
                    item(key = "..") {
                        FileRow(
                            name = "../",
                            isDir = true,
                            summary = null,
                            last = !hasTail,
                            onClick = {
                                currentPath = currentPath.trimEnd('/')
                                    .substringBeforeLast('/')
                                    .ifBlank { "/" }
                            },
                        )
                    }
                }
                when {
                    listError != null -> {
                        item(key = "list-error") {
                            CardStateText(
                                stringResource(listError ?: R.string.linux_files_error_unreadable),
                                modifier = Modifier.movoCardSegment(first = false, last = true),
                            )
                        }
                    }
                    currentEntries != null && currentEntries.isEmpty() -> {
                        item(key = "list-empty") {
                            CardStateText(
                                stringResource(R.string.linux_files_empty),
                                modifier = Modifier.movoCardSegment(first = false, last = true),
                            )
                        }
                    }
                    listed && currentEntries != null -> {
                        itemsIndexed(currentEntries, key = { _, entry -> entry.name }) { index, entry ->
                            FileRow(
                                name = entry.name,
                                isDir = entry.isDir,
                                summary = if (entry.isDir) {
                                    null
                                } else {
                                    Formatter.formatShortFileSize(appContext, entry.sizeBytes)
                                },
                                last = index == currentEntries.lastIndex,
                                onClick = {
                                    val target = currentPath.trimEnd('/') + "/" + entry.name
                                    if (entry.isDir) {
                                        currentPath = target
                                    } else {
                                        openFilePath = target
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 卡内路径标题：位置同 `Card/Title`（左右 16，上 16、下 4），等宽字体、可换行。 */
@Composable
private fun PathHeader(path: String) {
    Text(
        text = path,
        style = MovoTypography.labelMedium.copy(fontFamily = FontFamily.Monospace),
        color = MovoColors.textSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = MovoSpacing.lg, end = MovoSpacing.lg, top = MovoSpacing.lg, bottom = MovoSpacing.xs),
    )
}

@Composable
private fun FileRow(
    name: String,
    isDir: Boolean,
    summary: String?,
    last: Boolean,
    onClick: () -> Unit,
) {
    SettingsRow(
        title = name,
        subtitle = summary,
        modifier = Modifier.movoCardSegment(first = false, last = last),
        leading = RowLeading.Icon(if (isDir) MovoIcons.Folder else MovoIcons.File),
        trailing = if (isDir) RowTrailing.Arrow() else RowTrailing.None,
        showDivider = !last,
        onClick = onClick,
    )
}
