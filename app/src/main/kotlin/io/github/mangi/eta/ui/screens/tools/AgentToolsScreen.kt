package io.github.mangi.eta.ui.screens.tools

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.ui.app.rememberDeviceCapabilities
import io.github.mangi.eta.ui.components.movo.CardTitle
import io.github.mangi.eta.ui.components.movo.MovoCard
import io.github.mangi.eta.ui.components.movo.MovoListPage
import io.github.mangi.eta.ui.components.movo.SettingsRow
import io.github.mangi.eta.ui.model.AgentToolsAction
import io.github.mangi.eta.ui.model.AgentToolsUiState
import io.github.mangi.eta.ui.model.ToolGroupUi
import io.github.mangi.eta.ui.model.ToolItemUi
import io.github.mangi.eta.ui.model.projectToolGroups
import io.github.mangi.eta.ui.pages.providers.MovoSegmentedTabs
import io.github.mangi.eta.ui.theme.LocalReducedMotion
import io.github.mangi.eta.ui.theme.MovoMotion
import io.github.mangi.eta.ui.theme.MovoSpacing

/** 卡内网格：左右各缩 8，格子自身再缩 8，让图标底块与文字落在内容线（卡内 16）。 */
private object ToolsMetrics {
    val GridInset = MovoSpacing.sm
    val GridGap = MovoSpacing.xs
}

/**
 * 设置 · 工具 · 全部工具（只读的工具能力目录，规范 8.7 列表页）：顶部「当前设备 / 全部」分段切换（9.3.1），
 * 「Root 与系统增强」入口卡，之后每个分组一张卡片（卡内标题 = 分组名 + 数量），卡内两列格子；
 * 窄屏（< 320）或字号缩放 ≥ 1.3 时单列。页面不放顶部说明段落。
 */
@Composable
fun AgentToolsScreen(
    state: AgentToolsUiState,
    onAction: (AgentToolsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val capabilities = rememberDeviceCapabilities()
    var showAll by rememberSaveable { mutableStateOf(false) }
    val currentListState = rememberLazyListState()
    val allListState = rememberLazyListState()
    val groups = projectToolGroups(state.groups, showAll, capabilities.root.isGranted, capabilities.tools.colorOs)
    // 分段切换后下方内容淡入 `fast`，不横向滑动（9.3.1「分段 / 标签」）。
    val reduced = LocalReducedMotion.current
    val contentAlpha = remember { Animatable(1f) }
    var firstComposition by remember { mutableStateOf(true) }
    LaunchedEffect(showAll) {
        if (firstComposition || reduced) {
            firstComposition = false
            contentAlpha.snapTo(1f)
            return@LaunchedEffect
        }
        contentAlpha.snapTo(0f)
        contentAlpha.animateTo(1f, MovoMotion.fast())
    }
    MovoListPage(
        title = stringResource(R.string.ui_tool_ability_9f0f80),
        onBack = { onAction(AgentToolsAction.NavigateBack) },
        modifier = modifier,
        listState = if (showAll) allListState else currentListState,
    ) {
        item(key = "capability-view") {
            MovoSegmentedTabs(
                tabs = listOf(stringResource(R.string.capability_current_device), stringResource(R.string.capability_all)),
                selectedIndex = if (showAll) 1 else 0,
                onSelect = { showAll = it == 1 },
            )
        }
        item(key = "capability-discovery") {
            MovoCard(bottomPadding = 0.dp) {
                SettingsRow(
                    title = stringResource(R.string.capability_enhancements),
                    subtitle = stringResource(R.string.capability_enhancements_summary),
                    showDivider = false,
                    onClick = { onAction(AgentToolsAction.OpenEnhancements) },
                )
            }
        }
        groups.forEach { group ->
            item(key = group.id) {
                ToolGroupCard(
                    group = group,
                    rootGranted = capabilities.root.isGranted,
                    capabilities = capabilities.tools,
                    onAction = onAction,
                    modifier = Modifier.graphicsLayer { alpha = contentAlpha.value },
                )
            }
        }
    }
}

@Composable
private fun ToolGroupCard(
    group: ToolGroupUi,
    rootGranted: Boolean,
    capabilities: AgentToolCapabilities,
    onAction: (AgentToolsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tone = toolCategoryTone(group.id)
    MovoCard(modifier = modifier, bottomPadding = ToolsMetrics.GridInset) {
        CardTitle(
            text = group.title,
            trailing = stringResource(R.string.movo_settings_count_items, group.tools.size),
        )
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ToolsMetrics.GridInset),
        ) {
            val useSingleColumn = maxWidth < 320.dp || LocalDensity.current.fontScale >= 1.3f
            Column(verticalArrangement = Arrangement.spacedBy(ToolsMetrics.GridGap)) {
                if (useSingleColumn) {
                    group.tools.forEach { tool ->
                        ToolCard(
                            tool = tool,
                            tone = tone,
                            rootGranted = rootGranted,
                            capabilities = capabilities,
                            onAction = onAction,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    group.tools.chunked(2).forEach { row ->
                        ToolGridRow(
                            tools = row,
                            tone = tone,
                            rootGranted = rootGranted,
                            capabilities = capabilities,
                            onAction = onAction,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolGridRow(
    tools: List<ToolItemUi>,
    tone: ToolCategoryTone,
    rootGranted: Boolean,
    capabilities: AgentToolCapabilities,
    onAction: (AgentToolsAction) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(ToolsMetrics.GridGap),
    ) {
        tools.forEach { tool ->
            ToolCard(
                tool = tool,
                tone = tone,
                rootGranted = rootGranted,
                capabilities = capabilities,
                onAction = onAction,
                fillHeight = true,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
        if (tools.size == 1) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}
