package io.github.fartown.movo.ui.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.R
import io.github.fartown.movo.ui.components.ConversationSidePaneScaffold
import io.github.fartown.movo.ui.components.MovoAtmosphere
import io.github.fartown.movo.ui.components.movo.MovoIconButton
import io.github.fartown.movo.ui.components.movo.MovoTopBar
import io.github.fartown.movo.ui.components.movo.PressKind
import io.github.fartown.movo.ui.components.movo.ScrolledDetector
import io.github.fartown.movo.ui.components.movo.captureMovoBackdrop
import io.github.fartown.movo.ui.components.movo.movoClickable
import io.github.fartown.movo.ui.components.movo.movoElevation
import io.github.fartown.movo.ui.components.movo.movoTopBarHeight
import io.github.fartown.movo.ui.components.movo.rememberMovoBackdrop
import io.github.fartown.movo.ui.model.ConversationPaneUiState
import io.github.fartown.movo.ui.model.ConversationSummaryUi
import io.github.fartown.movo.ui.navigation.AppRoute
import io.github.fartown.movo.ui.theme.LocalReducedMotion
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoElevation
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.theme.MovoMotion
import io.github.fartown.movo.ui.theme.MovoRadius
import io.github.fartown.movo.ui.theme.MovoSize
import io.github.fartown.movo.ui.theme.MovoSpacing
import io.github.fartown.movo.ui.theme.MovoTypography
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur

/**
 * Agent App 统一壳层（首页 / 会话页、浏览器、终端）。
 *
 * - 首页顶栏（规范 8「顶栏」、8.0、8.1）：左菜单、右新建对话「+」；中间是会话标题，没有标题时在有后台任务时显示状态胶囊。
 *   原 ⋮ 菜单里的终端、浏览器、Kimi Web 已移到设置 ·「能力与扩展」（2026-09-25）。
 * - 其他路由：二级页顶栏（返回 + 居中标题）。
 * - Q7 顶栏滚动态：内容滚到顶栏下方时出现底色与分隔线。
 */
@Composable
fun AgentAppShell(
    currentRoute: AppRoute?,
    isCurrentRoute: Boolean,
    conversationPaneState: ConversationPaneUiState?,
    isConversationPaneOpen: Boolean,
    closeConversationPaneInstantly: Boolean = false,
    onBack: () -> Unit,
    onOpenConversationPane: () -> Unit,
    onDismissConversationPane: () -> Unit,
    onSearchConversations: (String) -> Unit,
    onNewConversation: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onConversationExport: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    settingsAttention: String? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val backdrop = rememberMovoBackdrop()
    val detector = remember(currentRoute) { ScrolledDetector() }
    val isHome = currentRoute is AppRoute.Home
    val unnamed = stringResource(R.string.conversation_unnamed)
    val selectedId = conversationPaneState?.selectedConversationId
    val conversationTitle = conversationPaneState?.conversations
        ?.firstOrNull { it.id == selectedId }
        ?.title
        ?.takeIf { it.isNotBlank() && it != unnamed }
    // 后台任务：其他会话里正在运行的任务（规范 8.0 顶栏状态胶囊）。
    val backgroundRuns = conversationPaneState?.conversations
        ?.filter { it.isActiveRun && it.id != selectedId }
        .orEmpty()
    val barHeight = movoTopBarHeight()

    val pageContent: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MovoColors.bgCanvas)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .captureMovoBackdrop(backdrop)
                    .nestedScroll(detector),
            ) {
                // 首页与会话页的背景光晕（规范 9.4：首页 → 对话时保留不动）。
                if (isHome) MovoAtmosphere()
                content(PaddingValues(top = barHeight))
            }
            if (isHome) {
                HomeTopBar(
                    scrolled = detector.scrolled,
                    backdrop = backdrop,
                    title = conversationTitle,
                    backgroundRuns = backgroundRuns,
                    onOpenConversationPane = onOpenConversationPane,
                    onNewConversation = onNewConversation,
                    onOpenBackgroundRun = onSelectConversation,
                )
            } else {
                MovoTopBar(
                    title = titleForRoute(currentRoute),
                    onBack = onBack,
                    scrolled = detector.scrolled,
                    backdrop = backdrop,
                )
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (conversationPaneState != null && isHome) {
            ConversationSidePaneScaffold(
                state = conversationPaneState,
                visible = isConversationPaneOpen,
                closeInstantly = closeConversationPaneInstantly,
                backHandlerEnabled = isCurrentRoute,
                onOpen = onOpenConversationPane,
                onDismiss = onDismissConversationPane,
                onSearchChange = onSearchConversations,
                onConversationSelected = onSelectConversation,
                onConversationRename = onConversationRename,
                onConversationExport = onConversationExport,
                onConversationDelete = onConversationDelete,
                onNewConversation = onNewConversation,
                onOpenSettings = onOpenSettings,
                settingsAttention = settingsAttention,
            ) {
                pageContent()
            }
        } else {
            pageContent()
        }
    }
}

/**
 * 首页 / 会话页顶栏：高 56，左内边距 7、右内边距 6（菜单与「+」字形分别落在 20 / 392）；
 * 中间会话标题 Body/Strong 最大宽 220 一行省略，生成后淡入 `fast`；没有标题时，有后台任务显示状态胶囊。
 */
@Composable
private fun HomeTopBar(
    scrolled: Boolean,
    backdrop: LayerBackdrop?,
    title: String?,
    backgroundRuns: List<ConversationSummaryUi>,
    onOpenConversationPane: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenBackgroundRun: (String) -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val chrome by animateFloatAsState(
        targetValue = if (scrolled) 1f else 0f,
        animationSpec = when {
            reduced -> snap()
            scrolled -> MovoMotion.fast()
            else -> MovoMotion.fastExit()
        },
        label = "homeTopBarChrome",
    )
    Box(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.matchParentSize().graphicsLayer { alpha = chrome }) {
            if (backdrop != null) {
                Box(
                    Modifier.matchParentSize().progressiveTextureBlur(
                        backdrop = backdrop,
                        shape = RectangleShape,
                        gradient = ProgressiveBlur.Top,
                        blurRadius = 16f,
                        colors = BlurColors(blendColors = listOf(BlendColorEntry(MovoColors.bgCanvas.copy(alpha = 0.9f)))),
                    ),
                )
            } else {
                Box(Modifier.matchParentSize().background(MovoColors.bgCanvas.copy(alpha = 0.9f)))
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(MovoSize.topBar)
                .padding(start = 7.dp, end = 6.dp),
        ) {
            MovoIconButton(
                icon = MovoIcons.Menu,
                contentDescription = stringResource(R.string.action_conversation_history),
                onClick = onOpenConversationPane,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            AnimatedContent(
                targetState = title,
                transitionSpec = { fadeIn(MovoMotion.fast()) togetherWith fadeOut(MovoMotion.fastExit()) },
                // 居中对齐 + 两侧让出菜单与「+」（热区 44 + 边距）：长标题只省略、不贴图标（真机验收）。
                contentAlignment = Alignment.Center,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 52.dp),
                label = "homeTopBarTitle",
            ) { currentTitle ->
                when {
                    currentTitle != null -> Text(
                        text = currentTitle,
                        style = MovoTypography.bodyStrong,
                        color = MovoColors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 220.dp).semantics { heading() },
                    )
                    backgroundRuns.isNotEmpty() -> BackgroundTaskPill(
                        count = backgroundRuns.size,
                        onClick = { onOpenBackgroundRun(backgroundRuns.first().id) },
                    )
                    else -> Spacer(Modifier.size(0.dp))
                }
            }
            MovoIconButton(
                icon = MovoIcons.Plus,
                contentDescription = stringResource(R.string.action_new_conversation),
                onClick = onNewConversation,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(MovoSize.hairline)
                .graphicsLayer { alpha = chrome }
                .background(MovoColors.borderHairline),
        )
    }
}

/**
 * 状态胶囊（规范 8 组件表）：高 32、圆角 16、bg/surface + 描边 + E1；状态点 7 绿色，外圈光晕 14 在
 * 1 → 1.8、35% → 0 间 1600ms 循环（9.3），减少动画时不显示光晕。点击进入对应会话。
 */
@Composable
private fun BackgroundTaskPill(count: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(MovoRadius.md)
    val reduced = LocalReducedMotion.current
    Row(
        modifier = Modifier
            .height(MovoSize.controlSmall)
            .movoElevation(MovoElevation.Card, shape)
            .movoClickable(PressKind.Solid, shape = shape, onClick = onClick)
            .clip(shape)
            .background(MovoColors.bgSurface)
            .padding(horizontal = MovoSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            if (!reduced) {
                val transition = rememberInfiniteTransition(label = "statusHalo")
                val halo by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(MovoMotion.STATUS_HALO_PERIOD, easing = MovoMotion.EasingLinear)),
                    label = "statusHaloProgress",
                )
                Box(
                    Modifier
                        .size(7.dp)
                        .graphicsLayer {
                            val scale = 1f + 0.8f * halo
                            scaleX = scale
                            scaleY = scale
                            alpha = 0.35f * (1f - halo)
                        }
                        .clip(CircleShape)
                        .background(MovoColors.statusOnline),
                )
            }
            Box(Modifier.size(7.dp).clip(CircleShape).background(MovoColors.statusOnline))
        }
        Spacer(Modifier.width(6.dp))
        Text(
            pluralStringResource(R.plurals.movo_background_tasks, count, count),
            style = MovoTypography.labelMedium,
            color = MovoColors.textPrimary,
            maxLines = 1,
        )
    }
}

@Composable
private fun titleForRoute(route: AppRoute?): String = when (route) {
    is AppRoute.Home -> ""
    is AppRoute.Chat -> stringResource(R.string.route_chat)
    is AppRoute.Browser -> stringResource(R.string.route_browser)
    is AppRoute.Terminal -> stringResource(R.string.route_terminal)
    is AppRoute.Tools -> stringResource(R.string.route_tools)
    is AppRoute.Skills -> stringResource(R.string.route_skills)
    is AppRoute.Characters -> "角色"
    is AppRoute.CharacterDetail -> "角色详情"
    is AppRoute.CharacterEditor -> "编辑角色"
    is AppRoute.CharacterPersona -> "我的人设"
    is AppRoute.CharacterMemory -> "剧情记忆"
    is AppRoute.Permissions -> stringResource(R.string.route_permissions)
    is AppRoute.SystemEnhance -> stringResource(R.string.route_system_enhancements)
    is AppRoute.Settings -> stringResource(R.string.route_settings)
    is AppRoute.ToolSettings -> stringResource(R.string.movo_settings_tools)
    is AppRoute.SystemAssistant -> stringResource(R.string.movo_settings_system_assistant)
    is AppRoute.RunDetail -> stringResource(R.string.movo_run_detail_title)
    is AppRoute.VoiceSettings -> stringResource(R.string.voice_settings_title)
    is AppRoute.Diagnostics -> "运行日志"
    is AppRoute.DiagnosticsRun -> "任务详情"
    is AppRoute.DiagnosticsSystem -> "系统事件"
    is AppRoute.AppearanceSettings -> stringResource(R.string.appearance_title)
    is AppRoute.DataBackup -> stringResource(R.string.data_backup_title)
    is AppRoute.Memory -> stringResource(R.string.route_memory)
    is AppRoute.LinuxEnvironment -> stringResource(R.string.route_linux_environment)
    is AppRoute.Workspace -> stringResource(R.string.capability_workspace)
    is AppRoute.SharedFolders -> stringResource(R.string.route_shared_folders)
    is AppRoute.LinuxFiles -> stringResource(R.string.route_linux_files)
    is AppRoute.ModelProviders -> stringResource(R.string.route_model_providers)
    is AppRoute.McpServers -> stringResource(R.string.route_mcp_servers)
    is AppRoute.McpServerDetail -> stringResource(R.string.route_mcp_server_detail)
    is AppRoute.ModelProviderDetail -> stringResource(R.string.route_provider_details)
    is AppRoute.ModelProviderNew -> stringResource(R.string.route_new_provider)
    null -> stringResource(R.string.app_name)
}
