package io.github.fartown.movo.ui.components

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.fartown.movo.R
import io.github.fartown.movo.ui.components.movo.MovoIconButton
import io.github.fartown.movo.ui.components.movo.MovoSpinner
import io.github.fartown.movo.ui.components.movo.PressKind
import io.github.fartown.movo.ui.components.movo.captureMovoBackdrop
import io.github.fartown.movo.ui.components.movo.movoClickable
import io.github.fartown.movo.ui.components.movo.movoElevation
import io.github.fartown.movo.ui.components.movo.rememberMovoBackdrop
import io.github.fartown.movo.ui.model.ConversationPaneUiState
import io.github.fartown.movo.ui.model.ConversationSummaryUi
import io.github.fartown.movo.ui.theme.LocalReducedMotion
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoElevation
import io.github.fartown.movo.ui.theme.MovoIcon
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.theme.MovoMotion
import io.github.fartown.movo.ui.theme.MovoRadius
import io.github.fartown.movo.ui.theme.MovoSize
import io.github.fartown.movo.ui.theme.MovoSpacing
import io.github.fartown.movo.ui.theme.MovoTypography
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** 侧边栏 `Drawer`（规范 8.6，Figma「16 · 侧边栏」「17 · 侧边栏 · 状态」）。 */
private object DrawerMetrics {
    val PaneMaxWidth = 340.dp
    const val PaneWidthFraction = 0.84f

    /** 露出部分左侧圆角 = 屏幕圆角；取不到系统圆角时用稿中的 36。 */
    val ForegroundCornerRadiusFallback = 36.dp
    const val SettlePositionThresholdFraction = 0.5f

    /** 首页稳定后多久预热侧边栏（见 [ConversationSidePaneScaffold] 的预热说明）。 */
    const val WarmUpDelayMillis = 1000L
    val Edge = 20.dp
    val HeaderHeight = 56.dp
    val SearchHeight = 40.dp
    val RowHeight = 44.dp
    val RowTwoLineHeight = 60.dp
    val RowPadding = 16.dp
    val FooterHeight = 60.dp
    val MenuWidth = 220.dp
}

private enum class ConversationPaneAnchor {
    Closed,
    Open,
}

@Composable
fun ConversationSidePaneScaffold(
    state: ConversationPaneUiState,
    visible: Boolean,
    /** 为 true 时侧边栏在本帧即按关闭绘制并直接落到关闭位（不播动画）。 */
    closeInstantly: Boolean = false,
    backHandlerEnabled: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onSearchChange: (String) -> Unit,
    onConversationSelected: (String) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onConversationExport: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onNewConversation: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    settingsAttention: String? = null,
    content: @Composable () -> Unit,
) {
    val sceneLifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val navigationEventState = rememberNavigationEventState(NavigationEventInfo.None)
    val reduced = LocalReducedMotion.current

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val paneWidth = minOf(maxWidth * DrawerMetrics.PaneWidthFraction, DrawerMetrics.PaneMaxWidth)
        val paneWidthPx = with(density) { paneWidth.toPx() }
        val anchors = remember(paneWidthPx) {
            DraggableAnchors {
                ConversationPaneAnchor.Closed at 0f
                ConversationPaneAnchor.Open at paneWidthPx
            }
        }
        val paneDragState = remember {
            AnchoredDraggableState(
                initialValue = if (visible) ConversationPaneAnchor.Open else ConversationPaneAnchor.Closed,
                anchors = anchors,
            )
        }
        // 9.3「侧边栏开合」：拖动跟手，松手按速度与是否过半吸附 spring/gentle。
        val flingBehavior = AnchoredDraggableDefaults.flingBehavior(
            state = paneDragState,
            positionalThreshold = { distance -> distance * DrawerMetrics.SettlePositionThresholdFraction },
            animationSpec = MovoMotion.gentle(),
        )
        val currentVisible by rememberUpdatedState(visible)
        val currentOnOpen by rememberUpdatedState(onOpen)
        val currentOnDismiss by rememberUpdatedState(onDismiss)
        val openProgress by remember(paneDragState, paneWidthPx, closeInstantly) {
            derivedStateOf {
                val offset = paneDragState.offset.takeUnless(Float::isNaN) ?: 0f
                if (closeInstantly || paneWidthPx <= 0f) 0f else (offset / paneWidthPx).coerceIn(0f, 1f)
            }
        }
        val systemCornerRadius = rememberNavSystemCornerRadius()
        val foregroundCornerRadius = systemCornerRadius.takeIf { it > 0.dp }
            ?: DrawerMetrics.ForegroundCornerRadiusFallback

        SideEffect {
            paneDragState.updateAnchors(anchors)
        }

        // 点菜单：主页面右移到位 slow + standard；关闭 250ms + exit；减少动画时直接到位。
        LaunchedEffect(visible, paneWidthPx, closeInstantly) {
            val target = if (visible) ConversationPaneAnchor.Open else ConversationPaneAnchor.Closed
            if (paneDragState.targetValue != target || paneDragState.settledValue != target) {
                paneDragState.animateTo(
                    target,
                    when {
                        reduced || (closeInstantly && !visible) -> snap<Float>()
                        visible -> tween<Float>(MovoMotion.SLOW, easing = MovoMotion.EasingStandard)
                        else -> tween<Float>(MovoMotion.SLOW_EXIT, easing = MovoMotion.EasingExit)
                    },
                )
            }
        }

        // 返回手势进行中：侧边栏跟手收到接近关闭，但关闭只由手势完成（onBackCompleted）决定。
        // 若在手势中途就因为「已落到关闭位」而关掉，返回处理器会被禁用，松手时这次返回会交给系统、直接退出 App。
        var backGestureActive by remember { mutableStateOf(false) }
        LaunchedEffect(paneDragState) {
            snapshotFlow { paneDragState.settledValue }.collectLatest { settledValue ->
                val settledOpen = settledValue == ConversationPaneAnchor.Open
                if (backGestureActive) return@collectLatest
                if (settledOpen != currentVisible) {
                    if (settledOpen) currentOnOpen() else currentOnDismiss()
                }
            }
        }

        // 9.3「侧边栏开合」：返回手势按进度收回侧边栏（跟手），手势取消时 spring/gentle 弹回；完成由 onBackCompleted 关闭。
        LaunchedEffect(navigationEventState, paneDragState, paneWidthPx) {
            snapshotFlow { navigationEventState.transitionState }.collectLatest { transition ->
                if (!currentVisible || paneWidthPx <= 0f || reduced) return@collectLatest
                if (transition is androidx.navigationevent.NavigationEventTransitionState.InProgress) {
                    backGestureActive = true
                    val progress = transition.latestEvent.progress.coerceIn(0f, 1f)
                    // 不拖到关闭锚点本身（留 1px），避免手势中途被判定为已关闭。
                    paneDragState.anchoredDrag { dragTo((paneWidthPx * (1f - progress)).coerceAtLeast(1f)) }
                } else {
                    backGestureActive = false
                    // 等一帧：手势完成时 visible 会随之变为 false，交给关闭动画；仍可见说明手势被取消。
                    kotlinx.coroutines.delay(MovoMotion.INSTANT.toLong() / 4)
                    val offset = paneDragState.offset.takeUnless(Float::isNaN) ?: return@collectLatest
                    if (currentVisible && offset < paneWidthPx) {
                        paneDragState.animateTo(ConversationPaneAnchor.Open, MovoMotion.gentle())
                    }
                }
            }
        }

        // NavDisplay 的退出条目在转场期间仍会保留组合；仅允许已稳定显示的首页
        // 处理侧栏返回，避免它抢先消费二级页面的第一次返回事件。
        NavigationBackHandler(
            state = navigationEventState,
            isBackEnabled = visible &&
                backHandlerEnabled &&
                sceneLifecycleState == Lifecycle.State.RESUMED,
            onBackCompleted = onDismiss,
        )

        // 侧边栏完全关闭时面板不参与绘制（A13）：面板一直保持组合，打开动画第一帧不用现组合；
        // 只在绘制阶段按「是否露出」切换图层透明度，零透明度的图层整层跳过，模糊浮层也不再每帧重算。
        // 与 openProgress 同样的 key：否则这里一直读第一次组合时的那份进度（例如从浮层接过来时 closeInstantly 为真、进度恒为 0），
        // 面板永远透明（真机：从悬浮球进来的主界面侧边栏空白）。
        val paneShowing by remember(paneDragState, paneWidthPx, closeInstantly) { derivedStateOf { openProgress > 0f } }
        // 预热：面板关着时整层跳过绘制，第一次打开的那一帧要现录整个面板、首次编译磨砂模糊的着色器、首次生成 64 的大阴影，
        // 真机上这一帧卡 57–84ms（之后再开就不卡）。首页稳定约 1 秒后把面板和阴影在主页面下面画两帧——
        // 主页面不透明且铺满屏幕，用户看不到——让这些一次性开销提前在空闲时付掉。
        var warmingUp by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(DrawerMetrics.WarmUpDelayMillis)
            warmingUp = true
            withFrameNanos { }
            withFrameNanos { }
            warmingUp = false
        }
        // 回到首页（从设置返回、切换页面）那一帧不组合会话列表：抽屉关着时推迟两帧再组合，
        // 真机 trace 里整张列表的首次组合占了返回首页那一帧的一大块。抽屉要打开时立即组合。
        var panelComposed by remember { mutableStateOf(visible) }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            withFrameNanos { }
            panelComposed = true
        }
        if (panelComposed || visible || paneShowing) ConversationPanePanel(
            state = state,
            width = paneWidth,
            paneVisible = { paneShowing },
            settingsAttention = settingsAttention,
            onSearchChange = onSearchChange,
            onConversationSelected = onConversationSelected,
            onConversationRename = onConversationRename,
            onConversationExport = onConversationExport,
            onConversationDelete = onConversationDelete,
            onNewConversation = onNewConversation,
            onOpenSettings = onOpenSettings,
            modifier = Modifier
                .zIndex(0f)
                .graphicsLayer { alpha = if (paneShowing || warmingUp) 1f else 0f }
                // 打开后在面板上横滑同样收起：与主页面共用同一个拖动状态，左滑时主页面跟手盖回来。
                // 列表是竖向滚动，横向拖动过了触摸阈值才归这里，不影响上下滚动与点按。
                .anchoredDraggable(
                    state = paneDragState,
                    reverseDirection = false,
                    orientation = Orientation.Horizontal,
                    enabled = backHandlerEnabled && visible,
                    flingBehavior = flingBehavior,
                ),
        )

        val foregroundShape = AbsoluteRoundedCornerShape(
            topLeft = foregroundCornerRadius,
            topRight = 0.dp,
            bottomRight = 0.dp,
            bottomLeft = foregroundCornerRadius,
        )
        fun foregroundOffset(): Float = if (closeInstantly) {
            0f
        } else {
            paneDragState.offset.takeUnless(Float::isNaN) ?: if (visible) paneWidthPx else 0f
        }
        // 只在「露出 / 收起」切换时重组；开合过程中的每一帧只在绘制阶段读进度，不重组、不重建修饰符。
        run {
            // 阴影层一直保持组合（关着时图层透明度为 0、整层跳过绘制）：第一次打开不用现组合、现生成阴影。
            // 主页面左侧阴影（E3，暖灰）：形状与参数固定只画一次，随打开进度只改图层透明度，
            // 不再逐帧重新光栅化 64 的模糊阴影。ModulateAlpha 不开离屏缓冲，阴影可画出边界。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(foregroundOffset().roundToInt(), 0) }
                    .graphicsLayer {
                        alpha = if (warmingUp && openProgress == 0f) 1f else openProgress
                        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
                    }
                    .dropShadow(
                        shape = foregroundShape,
                        shadow = Shadow(
                            radius = 64.dp,
                            spread = (-12).dp,
                            offset = DpOffset(0.dp, 24.dp),
                            color = MovoColors.shadow,
                            alpha = 0.18f,
                        ),
                    )
                    .dropShadow(
                        shape = foregroundShape,
                        shadow = Shadow(
                            radius = 6.dp,
                            offset = DpOffset(0.dp, 2.dp),
                            color = MovoColors.shadow,
                            alpha = 0.06f,
                        ),
                    )
                    .zIndex(0.5f),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(foregroundOffset().roundToInt(), 0) }
                .graphicsLayer {
                    // 左侧圆角随打开进度出现：硬件轮廓裁剪（圆角矩形），代价与普通图层相同。
                    // 原来用着色器实现的连续曲率裁剪，每帧都要把整页离屏渲染再蒙版，开合会掉帧。
                    val radius = foregroundCornerRadius.toPx() * openProgress
                    if (radius > 0f) {
                        shape = AbsoluteRoundedCornerShape(
                            topLeft = radius.toDp(),
                            topRight = 0.dp,
                            bottomRight = 0.dp,
                            bottomLeft = radius.toDp(),
                        )
                        clip = true
                    } else {
                        clip = false
                    }
                }
                // 保持物理左右方向，不随 RTL 镜像：会话列表始终从屏幕左侧显露。
                .anchoredDraggable(
                    state = paneDragState,
                    reverseDirection = false,
                    orientation = Orientation.Horizontal,
                    enabled = backHandlerEnabled,
                    flingBehavior = flingBehavior,
                )
                .zIndex(1f),
        ) {
            content()
            if (visible) {
                // 点露出部分关闭；不加遮罩（规范 8.6）。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss,
                        ),
                )
            }
        }
    }
}

@Composable
private fun ConversationPanePanel(
    state: ConversationPaneUiState,
    width: Dp,
    /** 面板是否露出（绘制期读取）；运行中会话行的加载圈只在露出时转（A14）。 */
    paneVisible: () -> Boolean,
    settingsAttention: String?,
    onSearchChange: (String) -> Unit,
    onConversationSelected: (String) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onConversationExport: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onNewConversation: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // state.conversations 已由 AgentAppState 按标题、预览与消息内容过滤。
    val query = state.searchQuery.trim()
    val searching = query.isNotBlank()
    val groups = remember(state.conversations) { state.conversations.groupForDrawer() }
    val density = LocalDensity.current

    Box(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .background(MovoColors.bgSurface),
    ) {
        // 列表全高滚动，顶部与底部区域是 bg/surface 90% + 背景模糊的浮层；
        // 内容滚入时出现 0.5 分隔线，静止在顶 / 底时无线（规范 8.6「滚动」）。
        val backdrop = rememberMovoBackdrop()
        var headerHeightPx by remember { mutableIntStateOf(0) }
        var footerHeightPx by remember { mutableIntStateOf(0) }
        val listState = rememberLazyListState()
        val showHeaderDivider by remember { derivedStateOf { listState.canScrollBackward } }
        val showFooterDivider by remember { derivedStateOf { listState.canScrollForward } }
        // 列表增删（规范 9.3）：新增淡入 `fast`；删除淡出 120ms，其余行跟随移位 `standard`。
        val rowFadeIn = io.github.fartown.movo.ui.theme.MovoMotion.fast<Float>()
        val rowFadeOut = io.github.fartown.movo.ui.theme.MovoMotion.fastExit<Float>()
        val rowPlacement = io.github.fartown.movo.ui.theme.MovoMotion.standard<androidx.compose.ui.unit.IntOffset>()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .captureMovoBackdrop(backdrop)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = DrawerMetrics.Edge)
                .scrollEndHaptic()
                .overScrollVertical(),
            contentPadding = PaddingValues(
                top = with(density) { headerHeightPx.toDp() },
                bottom = with(density) { footerHeightPx.toDp() },
            ),
            overscrollEffect = null,
        ) {
            when {
                // 空状态与列表之间同样按列表增删过渡（B14），不硬切。
                state.conversations.isEmpty() -> item(key = if (searching) "empty-search" else "empty") {
                    Box(Modifier.animateItem(fadeInSpec = rowFadeIn, placementSpec = rowPlacement, fadeOutSpec = rowFadeOut)) {
                        EmptyConversations(isSearching = searching)
                    }
                }
                searching -> items(items = state.conversations, key = { it.id }) { conversation ->
                    Box(Modifier.animateItem(fadeInSpec = rowFadeIn, placementSpec = rowPlacement, fadeOutSpec = rowFadeOut)) {
                    ConversationRow(
                        conversation = conversation,
                        selected = conversation.id == state.selectedConversationId,
                        query = query,
                        paneVisible = paneVisible,
                        onClick = { onConversationSelected(conversation.id) },
                        onRename = { onConversationRename(conversation) },
                        onExport = { onConversationExport(conversation) },
                        onDelete = { onConversationDelete(conversation) },
                    )
                    }
                }
                else -> groups.forEachIndexed { index, group ->
                    item(key = "section-${group.section}") {
                        Box(Modifier.animateItem(fadeInSpec = rowFadeIn, placementSpec = rowPlacement, fadeOutSpec = rowFadeOut)) {
                            ConversationSectionHeader(group = group, first = index == 0)
                        }
                    }
                    items(items = group.items, key = { it.id }) { conversation ->
                        Box(Modifier.animateItem(fadeInSpec = rowFadeIn, placementSpec = rowPlacement, fadeOutSpec = rowFadeOut)) {
                        ConversationRow(
                            conversation = conversation,
                            selected = conversation.id == state.selectedConversationId,
                            query = null,
                            paneVisible = paneVisible,
                            onClick = { onConversationSelected(conversation.id) },
                            onRename = { onConversationRename(conversation) },
                            onExport = { onConversationExport(conversation) },
                            onDelete = { onConversationDelete(conversation) },
                        )
                        }
                    }
                }
            }
        }
        PaneFrostRegion(
            backdrop = backdrop,
            showDivider = showHeaderDivider,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .onSizeChanged { headerHeightPx = it.height },
        ) {
            // Drawer/Header：搜索框从边距线 20 开始，「+」字形右缘对齐 320（右内边距 5）。
            Row(
                modifier = Modifier
                    .statusBarsPadding()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .fillMaxWidth()
                    .height(DrawerMetrics.HeaderHeight)
                    .padding(start = DrawerMetrics.Edge, end = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DrawerSearchField(
                    query = state.searchQuery,
                    onQueryChange = onSearchChange,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(MovoSpacing.sm))
                MovoIconButton(
                    icon = MovoIcons.Plus,
                    contentDescription = stringResource(R.string.action_new_conversation),
                    onClick = onNewConversation,
                )
            }
        }
        PaneFrostRegion(
            backdrop = backdrop,
            showDivider = showFooterDivider,
            dividerAtTop = true,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { footerHeightPx = it.height },
        ) {
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .padding(horizontal = DrawerMetrics.Edge)
                    .padding(bottom = MovoSpacing.sm),
            ) {
                DrawerSettingsRow(attention = settingsAttention, onClick = onOpenSettings)
            }
        }
    }
}

/** 侧边栏顶 / 底浮层：bg/surface 90% + 背景模糊；模糊不可用时用不透明底色。 */
@Composable
private fun PaneFrostRegion(
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
    showDivider: Boolean = false,
    dividerAtTop: Boolean = false,
    content: @Composable () -> Unit,
) {
    val frostModifier = if (backdrop == null) {
        modifier.background(MovoColors.bgSurface)
    } else {
        modifier.textureBlur(
            backdrop = backdrop,
            shape = RectangleShape,
            blurRadius = 25f,
            colors = BlurColors(blendColors = listOf(BlendColorEntry(MovoColors.bgSurface.copy(alpha = 0.9f)))),
        )
    }
    Box(modifier = frostModifier) {
        content()
        AnimatedVisibility(
            visible = showDivider,
            modifier = Modifier
                .align(if (dividerAtTop) Alignment.TopCenter else Alignment.BottomCenter)
                .fillMaxWidth(),
            enter = fadeIn(MovoMotion.fast()),
            exit = fadeOut(MovoMotion.fastExit()),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(MovoSize.hairline)
                    .background(MovoColors.borderHairline),
            )
        }
    }
}

/**
 * 搜索框：高 40、圆角 20、bg/surface-muted；16 搜索图标次要色，字形对齐内容线 36；
 * 占位「搜索对话」Body/Regular 三级色；有文字时右端出现 ✕ 清空。
 */
@Composable
private fun DrawerSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = MovoTypography.bodyRegular.copy(color = MovoColors.textPrimary),
        cursorBrush = SolidColor(MovoColors.indigoFg),
        modifier = modifier.height(DrawerMetrics.SearchHeight),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(MovoRadius.lg))
                    .background(MovoColors.bgSurfaceMuted)
                    .padding(start = 14.dp, end = MovoSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MovoIcon(MovoIcons.Search, null, size = MovoSize.iconSmall, tint = MovoColors.textSecondary)
                Spacer(Modifier.width(MovoSpacing.sm))
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            stringResource(R.string.conversation_search_hint),
                            style = MovoTypography.bodyRegular,
                            color = MovoColors.textTertiary,
                            maxLines = 1,
                        )
                    }
                    innerTextField()
                }
                // ✕ 清空：出现 / 消失淡入淡出 + 缩放（9.3.1「图标状态切换」0.72 ↔ 1，`fast`）；
                // 热区保持图标按钮的 44（D5），视觉用 16 图标控制，不再用外层 size 压小热区。
                AnimatedVisibility(
                    visible = query.isNotEmpty(),
                    enter = fadeIn(MovoMotion.fast()) + scaleIn(MovoMotion.fast(), initialScale = 0.72f),
                    exit = fadeOut(MovoMotion.fastExit()) + scaleOut(MovoMotion.fastExit(), targetScale = 0.72f),
                ) {
                    MovoIconButton(
                        icon = MovoIcons.X,
                        contentDescription = stringResource(R.string.movo_drawer_clear_search),
                        onClick = { onQueryChange("") },
                        iconSize = MovoSize.iconSmall,
                        tint = MovoColors.textSecondary,
                    )
                }
            }
        },
    )
}

/** 分组标题：Label/Medium 三级色，对齐 36；上 16、下 4（第一组上 8）；不放图标和数量。 */
@Composable
private fun ConversationSectionHeader(group: ConversationDrawerGroup, first: Boolean) {
    Text(
        text = group.localizedLabel(),
        style = MovoTypography.labelMedium,
        color = MovoColors.textTertiary,
        modifier = Modifier.padding(
            start = DrawerMetrics.RowPadding,
            top = if (first) MovoSpacing.sm else MovoSpacing.lg,
            bottom = MovoSpacing.xs,
        ),
    )
}

/**
 * `Drawer/Row` 会话行：宽 300、高 44、圆角 12、左右 16；当前会话 bg/surface-muted + Medium（不用 Indigo）；
 * 运行中右侧 16 Indigo 加载圈；角色扮演两行高 60；搜索结果两行（标题 + 命中片段，右上时间）。
 * 长按：触感 + 行保持按压态 + 行下方 4 弹出菜单（重命名、导出、删除）。
 */
@Composable
private fun ConversationRow(
    conversation: ConversationSummaryUi,
    selected: Boolean,
    query: String?,
    paneVisible: () -> Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var showActionMenu by remember { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current
    val title = conversation.title.ifBlank { conversation.preview }
    val roleplay = conversation.characterName
    val secondLine: AnnotatedString? = when {
        query != null -> conversation.matchSnippet?.let { highlight(it, query) }
        roleplay != null -> AnnotatedString(stringResource(R.string.movo_drawer_roleplay, roleplay))
        else -> null
    }
    val shape = RoundedCornerShape(MovoRadius.sm)
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (secondLine != null) DrawerMetrics.RowTwoLineHeight else DrawerMetrics.RowHeight)
                .clip(shape)
                .background(
                    when {
                        showActionMenu -> MovoColors.overlayPressed
                        selected -> MovoColors.bgSurfaceMuted
                        else -> androidx.compose.ui.graphics.Color.Transparent
                    },
                )
                .movoClickable(
                    kind = PressKind.Row,
                    shape = shape,
                    onLongClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        showActionMenu = true
                    },
                    onClick = onClick,
                )
                .padding(horizontal = DrawerMetrics.RowPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (query != null) highlight(title, query) else AnnotatedString(title),
                        style = if (selected) MovoTypography.bodyStrong else MovoTypography.bodyRegular,
                        color = MovoColors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (query != null) {
                        Spacer(Modifier.weight(1f))
                        Spacer(Modifier.width(MovoSpacing.sm))
                        Text(conversation.timeLabel, style = MovoTypography.labelRegular, color = MovoColors.textTertiary, maxLines = 1)
                    }
                }
                if (secondLine != null) {
                    Text(
                        text = secondLine,
                        style = MovoTypography.labelRegular,
                        color = MovoColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (conversation.isActiveRun) {
                Spacer(Modifier.width(MovoSpacing.sm))
                RunningRowSpinner(paneVisible)
            }
        }
        ConversationActionMenu(
            show = showActionMenu,
            onDismiss = { showActionMenu = false },
            onRename = onRename,
            onExport = onExport,
            onDelete = onDelete,
        )
    }
}

/**
 * 运行中会话行的 16 Indigo 加载圈：侧边栏关着时不组合加载圈（占位保持宽度），不在看不见时每帧请求重组（A14）。
 * 读取 [paneVisible] 放在这个独立作用域里，开合时只重组这一小块，不牵动整行。
 */
@Composable
private fun RunningRowSpinner(paneVisible: () -> Boolean) {
    if (paneVisible()) MovoSpinner() else Spacer(Modifier.size(MovoSize.iconSmall))
}

/** 命中词：text/primary Medium，其余沿用所在文字的颜色。 */
private fun highlight(text: String, query: String): AnnotatedString = buildAnnotatedString {
    append(text)
    if (query.isBlank()) return@buildAnnotatedString
    var index = text.indexOf(query, ignoreCase = true)
    while (index >= 0) {
        addStyle(SpanStyle(color = MovoColors.textPrimary, fontWeight = FontWeight.Medium), index, index + query.length)
        index = text.indexOf(query, startIndex = index + query.length, ignoreCase = true)
    }
}

/**
 * `Popover/Menu`：圆角 20、内边距 8、项高 44 圆角 12、E3；出现在行下方 4、左缘对齐行（下方放不下时放到行上方）。
 * 从锚点缩放 0.96 → 1 并淡入 `fast` + `enter`，退场淡出 120ms。删除为 Rose。
 */
@Composable
private fun ConversationActionMenu(
    show: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val visibleState = remember { MutableTransitionState(false) }
    visibleState.targetState = show
    if (!visibleState.currentState && !visibleState.targetState) return
    val density = LocalDensity.current
    val gapPx = with(density) { MovoSpacing.xs.roundToPx() }
    val shadowPadPx = with(density) { MovoSpacing.xxl.roundToPx() }
    val positionProvider = remember(gapPx, shadowPadPx) { BelowAnchorPositionProvider(gapPx, shadowPadPx) }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        AnimatedVisibility(
            visibleState = visibleState,
            enter = fadeIn(MovoMotion.fast(MovoMotion.EasingEnter)) +
                scaleIn(MovoMotion.fast(MovoMotion.EasingEnter), initialScale = 0.96f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)),
            exit = fadeOut(MovoMotion.fastExit()),
        ) {
            val shape = RoundedCornerShape(MovoRadius.lg)
            Column(
                modifier = Modifier
                    .padding(MovoSpacing.xxl)
                    .widthIn(min = DrawerMetrics.MenuWidth)
                    .movoElevation(MovoElevation.Overlay, shape)
                    .clip(shape)
                    .background(MovoColors.bgSurface)
                    .padding(MovoSpacing.sm),
            ) {
                MenuItem(MovoIcons.PenLine, stringResource(R.string.action_rename)) { onDismiss(); onRename() }
                MenuItem(MovoIcons.Download, stringResource(R.string.action_export)) { onDismiss(); onExport() }
                MenuItem(MovoIcons.Trash2, stringResource(R.string.action_delete), destructive = true) { onDismiss(); onDelete() }
            }
        }
    }
}

@Composable
private fun MenuItem(
    icon: io.github.fartown.movo.ui.theme.MovoIconData,
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(MovoRadius.sm)
    val tint = if (destructive) MovoColors.roseFg else MovoColors.textPrimary
    Row(
        modifier = Modifier
            .widthIn(min = DrawerMetrics.MenuWidth - MovoSpacing.lg)
            .height(MovoSize.touchTarget)
            .clip(shape)
            .movoClickable(PressKind.Row, shape = shape, onClick = onClick)
            .padding(horizontal = MovoSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MovoIcon(icon, null, size = MovoSize.iconMedium, tint = tint)
        Spacer(Modifier.width(MovoSpacing.md))
        Text(label, style = MovoTypography.bodyRegular, color = tint)
    }
}

/**
 * 菜单放在锚点下方 [gapPx]、左缘对齐；弹层四周有 [shadowPadPx] 的阴影留白，这里抵消掉。
 * 下方放不下时放到锚点上方。
 */
private class BelowAnchorPositionProvider(
    private val gapPx: Int,
    private val shadowPadPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (anchorBounds.left - shadowPadPx)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val below = anchorBounds.bottom + gapPx - shadowPadPx
        val y = if (below + popupContentSize.height <= windowSize.height) {
            below
        } else {
            (anchorBounds.top - gapPx - popupContentSize.height + shadowPadPx).coerceAtLeast(0)
        }
        return IntOffset(x, y)
    }
}

/** 空状态：「还没有对话」Body/Strong + 一句说明；无结果：「没有匹配的对话」Body/Regular 次要色；对齐 36。 */
@Composable
private fun EmptyConversations(isSearching: Boolean) {
    Column(modifier = Modifier.padding(start = DrawerMetrics.RowPadding, top = MovoSpacing.md)) {
        if (isSearching) {
            Text(stringResource(R.string.conversation_no_results), style = MovoTypography.bodyRegular, color = MovoColors.textSecondary)
        } else {
            Text(stringResource(R.string.conversation_empty), style = MovoTypography.bodyStrong, color = MovoColors.textPrimary)
            Text(stringResource(R.string.movo_drawer_empty_hint), style = MovoTypography.labelRegular, color = MovoColors.textSecondary)
        }
    }
}

/**
 * `Drawer/Footer`：一行「设置」，高 60、内边距 16；图标底块 40（bg/surface-muted，设置图标 20）→ 12 →
 * 「设置」+ 说明 → 16 箭头三级色。有权限缺失时说明改为具体问题，箭头前加 8 的 Rose 状态点。
 */
@Composable
private fun DrawerSettingsRow(attention: String?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(MovoRadius.sm)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(DrawerMetrics.FooterHeight)
            .clip(shape)
            .movoClickable(PressKind.Row, shape = shape, onClick = onClick)
            .padding(horizontal = DrawerMetrics.RowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(MovoSize.iconTile)
                .clip(RoundedCornerShape(MovoRadius.sm))
                .background(MovoColors.bgSurfaceMuted),
            contentAlignment = Alignment.Center,
        ) {
            MovoIcon(MovoIcons.Settings, null, size = MovoSize.iconMedium)
        }
        Spacer(Modifier.width(MovoSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.drawer_settings), style = MovoTypography.bodyStrong, color = MovoColors.textPrimary)
            Text(
                attention ?: stringResource(R.string.movo_drawer_settings_summary),
                style = MovoTypography.labelRegular,
                color = MovoColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (attention != null) {
            Box(Modifier.size(MovoSpacing.sm).clip(CircleShape).background(MovoColors.roseFg))
            Spacer(Modifier.width(MovoSpacing.sm))
        }
        MovoIcon(MovoIcons.ChevronRight, null, size = MovoSize.iconSmall, tint = MovoColors.textTertiary)
    }
}

private data class ConversationDrawerGroup(
    val section: ConversationDrawerSection,
    val items: List<ConversationSummaryUi>,
)

private sealed interface ConversationDrawerSection {
    data object Pinned : ConversationDrawerSection
    data object Today : ConversationDrawerSection
    data object Yesterday : ConversationDrawerSection

    /** 更早：按天分组，[dayStartMillis] 为当天 0 点。 */
    data class Day(val dayStartMillis: Long, val sameYear: Boolean) : ConversationDrawerSection
}

/** 分组标题：今天 / 昨天 / 具体日期（「9月20日」，跨年带年份），按系统语言格式化。 */
@Composable
private fun ConversationDrawerGroup.localizedLabel(): String {
    val locale: Locale = LocalConfiguration.current.locales[0]
    return when (val value = section) {
        ConversationDrawerSection.Pinned -> stringResource(R.string.conversation_section_pinned)
        ConversationDrawerSection.Today -> stringResource(R.string.conversation_section_today)
        ConversationDrawerSection.Yesterday -> stringResource(R.string.time_yesterday)
        is ConversationDrawerSection.Day -> {
            val pattern = DateFormat.getBestDateTimePattern(locale, if (value.sameYear) "MMMd" else "yMMMd")
            java.text.SimpleDateFormat(pattern, locale).format(java.util.Date(value.dayStartMillis))
        }
    }
}

private fun List<ConversationSummaryUi>.groupForDrawer(): List<ConversationDrawerGroup> {
    if (isEmpty()) return emptyList()
    val now = Calendar.getInstance()
    val groups = mutableListOf<ConversationDrawerGroup>()
    for (conversation in this) {
        val section = conversation.drawerSection(now)
        val last = groups.lastOrNull()
        if (last?.section == section) {
            groups[groups.lastIndex] = last.copy(items = last.items + conversation)
        } else {
            groups += ConversationDrawerGroup(section = section, items = listOf(conversation))
        }
    }
    return groups
}

private fun ConversationSummaryUi.drawerSection(now: Calendar): ConversationDrawerSection {
    if (isPinned) return ConversationDrawerSection.Pinned
    if (isActiveRun || updatedAtMillis <= 0L) return ConversationDrawerSection.Today
    val target = Calendar.getInstance().apply { timeInMillis = updatedAtMillis }
    val dayStart = (target.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val todayStart = (now.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val yesterdayStart = (todayStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when {
        !dayStart.before(todayStart) -> ConversationDrawerSection.Today
        !dayStart.before(yesterdayStart) -> ConversationDrawerSection.Yesterday
        else -> ConversationDrawerSection.Day(
            dayStartMillis = dayStart.timeInMillis,
            sameYear = target.get(Calendar.YEAR) == now.get(Calendar.YEAR) && target.get(Calendar.ERA) == now.get(Calendar.ERA),
        )
    }
}
