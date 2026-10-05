package io.github.fartown.movo.ui.components

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.fartown.movo.ui.components.movo.trackVisibleHeightCap
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.R
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.produceState
import io.github.fartown.movo.ui.components.movo.movoElevation
import io.github.fartown.movo.ui.components.movo.movoClickable
import io.github.fartown.movo.agent.browser.AgentBrowserSession
import io.github.fartown.movo.agent.voice.session.VoiceEntry
import io.github.fartown.movo.agent.voice.session.VoiceSessionManager
import io.github.fartown.movo.data.model.ReasoningEffort
import io.github.fartown.movo.ui.app.AgentConversationRevisionReducer
import io.github.fartown.movo.ui.app.AgentFollowUpSuggestions
import io.github.fartown.movo.ui.app.LocalBlurEnabled
import io.github.fartown.movo.ui.model.AgentChatMessageUi
import io.github.fartown.movo.ui.model.AgentContextUsageUi
import io.github.fartown.movo.ui.model.AgentMessageUi
import io.github.fartown.movo.ui.model.AgentModelPickerUiState
import io.github.fartown.movo.ui.model.MessageEditUiState
import io.github.fartown.movo.ui.model.MonitorEventMessageUi
import io.github.fartown.movo.ui.model.isTurnStart
import io.github.fartown.movo.ui.model.PendingFileReferenceUi
import io.github.fartown.movo.ui.model.PendingImageUi
import io.github.fartown.movo.ui.model.SuggestionChipsMessageUi
import io.github.fartown.movo.ui.model.ThinkingMessageUi
import io.github.fartown.movo.ui.model.ToolActivityMessageUi
import io.github.fartown.movo.ui.model.ToolSummaryMessageUi
import io.github.fartown.movo.ui.model.SystemNoticeCode
import io.github.fartown.movo.ui.model.SystemNoticeMessageUi
import io.github.fartown.movo.ui.model.UserMessageUi
import io.github.fartown.movo.ui.model.ToolActivityStatusUi
import io.github.fartown.movo.ui.model.latestContextUsage
import io.github.fartown.movo.ui.screens.chat.ChatLatestPositionRequests
import kotlin.math.exp
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** The sheet grows in place; keep its last message pinned while its viewport is resized. */
internal val LocalChatKeepLatestOnResize = staticCompositionLocalOf { false }

/**
 * 聊天主体：消息流 + 底部输入框。
 *
 * AI 对话使用正向时间线：第一条消息从对话区顶部开始，后续回复顺序向下追加。
 * 空 assistant 占位不参与布局，避免刚发送时出现一个无内容消息节点。
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun AgentChatBody(
    messages: List<AgentChatMessageUi>,
    modelPickerState: AgentModelPickerUiState,
    isCompacting: Boolean,
    input: String,
    isStreaming: Boolean,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    pendingImages: List<PendingImageUi>,
    pendingFileReferences: List<PendingFileReferenceUi>,
    messageEdit: MessageEditUiState?,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onCompactContext: () -> Unit,
    canCompactContext: Boolean,
    onModelSelected: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onStop: () -> Unit,
    onAttachImage: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
    onEditMessage: (String) -> Unit,
    onCancelMessageEdit: () -> Unit,
    onDeleteMessage: (String) -> Unit,
    onRegenerateMessage: (String) -> Unit,
    onSelectReplyCandidate: (String, Int) -> Unit,
    onSuggestionClick: (String) -> Unit,
    onRunTraceClick: () -> Unit,
    onOpenBrowser: () -> Unit,
    characterName: String? = null,
    isDrawerOpen: Boolean = false,
    initiallyShowLatestMessage: Boolean = false,
    latestPositionRequest: ChatLatestPositionRequests.Ticket? = null,
    /** 分享进来的新会话（规范 8.9.1）：空白处显示来源提示与快捷建议，代替首页问候与能力卡。 */
    shareIntro: io.github.fartown.movo.ui.share.ShareIntro? = null,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberLazyListState()
    // 列表末尾的临时留白（见 [ChatBottomReserve]），与滚动状态同级：推荐追问到达时的滚动也要用到。
    val bottomReserve = remember { ChatBottomReserve() }
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val isKeyboardVisible = imeBottomPx > 0
    val browserSnapshot by AgentBrowserSession.snapshots.collectAsState()
    val contextUsage = remember(messages, modelPickerState.selectedModel) {
        latestContextUsage(messages, modelPickerState.selectedModel)
    }

    // 编辑某条消息时，它后面的消息在预览里被「隐藏」（提交后才真正删除）。
    // 时间线保留这些消息、只把它们收起（淡出 + 高度收起，取消编辑时反向展开，规范 9.3「列表增删」），
    // 不从列表里拿掉——拿掉会一帧消失。其余逻辑（滚动、推荐追问、浏览器入口等）仍按隐藏后的列表计算。
    val editHiddenTargetId = messageEdit?.takeUnless { it.preserveFollowingMessages }?.targetMessageId
    val timelineMessages = remember(messages, isStreaming) {
        messages.filterNot { message ->
            message is AgentMessageUi && message.content.isBlank()
        }.let { AgentFollowUpSuggestions.visible(it, isStreaming) }
    }
    val editHiddenIds = remember(messages, editHiddenTargetId) {
        val kept = AgentConversationRevisionReducer.visibleMessagesForEdit(messages, editHiddenTargetId)
        if (kept.size == messages.size) emptySet() else messages.drop(kept.size).mapTo(HashSet()) { it.id }
    }
    val visibleMessages = remember(timelineMessages, editHiddenIds) {
        if (editHiddenIds.isEmpty()) timelineMessages else timelineMessages.filterNot { it.id in editHiddenIds }
    }
    // Initial result presentation starts at the latest turn. Window resizing and onResume do
    // not restart this effect, so a reader's position remains untouched afterwards.
    // 新建的主界面接过浮层的会话时，这里第一次组合时消息可能还没读出来（真机：停在会话开头）：等到有消息再滚到最新。
    // 只在消息变化时重建时间线（流式中每次重组都重建会拖慢每一帧）。
    val entryCount = remember(visibleMessages) { visibleMessages.toTimelineEntries().size }
    val latestEntryCount by rememberUpdatedState(entryCount)
    val keepLatestOnResize = LocalChatKeepLatestOnResize.current
    var initialPositioned by remember { mutableStateOf(false) }
    LaunchedEffect(latestPositionRequest) {
        if (latestPositionRequest != null || (initiallyShowLatestMessage && !initialPositioned)) {
            initialPositioned = true
            val count = kotlinx.coroutines.withTimeoutOrNull(INITIAL_LATEST_WAIT_MS) {
                snapshotFlow { latestEntryCount }.first { it > 0 }
            } ?: latestEntryCount
            if (count > 0) scrollState.requestScrollToItem(count)
            // A long Markdown item can change size after the first measure. Keep the actual
            // sentinel at the viewport end through the initial layout, rather than counting
            // three frames and declaring the first (possibly provisional) layout finished.
            var settledFrames = 0
            repeat(45) { frame ->
                withFrameNanos { }
                if (scrollState.canScrollForward) {
                    scrollState.scroll { scrollBy(Float.MAX_VALUE / 4) }
                    settledFrames = 0
                } else if (frame >= 12) {
                    settledFrames++
                }
                if (settledFrames >= 4) {
                    latestPositionRequest?.let(ChatLatestPositionRequests::complete)
                    return@LaunchedEffect
                }
            }
            if (!scrollState.canScrollForward) latestPositionRequest?.let(ChatLatestPositionRequests::complete)
        }
    }
    val currentBrowserMessageId = remember(
        visibleMessages,
        browserSnapshot.available,
        browserSnapshot.lastAgentRunId,
        browserSnapshot.lastAgentToolCallId,
    ) {
        val runId = browserSnapshot.lastAgentRunId
        val toolCallId = browserSnapshot.lastAgentToolCallId
        if (!browserSnapshot.available || runId == null || toolCallId == null) {
            null
        } else {
            visibleMessages.lastOrNull { message ->
                message is ToolActivityMessageUi &&
                    message.toolName in BROWSER_TOOL_NAMES &&
                    message.id.startsWith("$runId-tool-") &&
                    message.id.endsWith("-$toolCallId")
            }?.id
        }
    }
    var sentFromKeyboard by remember { mutableStateOf(false) }
    var keepBottomAnchored by remember { mutableStateOf(true) }
    // 推荐追问在回答结束后才异步到达：用户仍停在底部时把它带进视野，正在上翻阅读时不打扰。
    // 按完整时间线判断：编辑预览里被收起、取消编辑后又出现的推荐追问不是「新到达」，不滚动（真机：取消编辑后列表猛滚到底）。
    val tailSuggestionId = (timelineMessages.lastOrNull() as? SuggestionChipsMessageUi)?.id
    var seenTailSuggestionId by remember { mutableStateOf(tailSuggestionId) }
    LaunchedEffect(tailSuggestionId) {
        val arrived = tailSuggestionId != null && tailSuggestionId != seenTailSuggestionId
        seenTailSuggestionId = tailSuggestionId
        if (arrived && keepBottomAnchored && !scrollState.isScrollInProgress) {
            // 平滑滚到正文末尾（底部哨兵）：不滚过末尾的临时留白，也不一帧到位（真机 reserve2：4 帧上移 466px）。
            withFrameNanos { }
            val info = scrollState.layoutInfo
            val sentinel = info.visibleItemsInfo.firstOrNull { it.key == ChatBottomSentinelKey }
            val viewportEnd = info.viewportEndOffset - info.afterContentPadding
            if (sentinel != null) {
                val overflow = (sentinel.offset + sentinel.size - viewportEnd).toFloat()
                if (overflow > 0f) scrollState.animateScrollBy(overflow, io.github.fartown.movo.ui.theme.MovoMotion.standard())
            } else {
                // 底部不在屏幕上：直接到底。先清掉末尾留白（在屏幕外），否则会滚进留白，
                // 推荐卡被顶到屏幕上方、下面一片空白（真机 reserve4：任务在后台结束后回到 Movo）。
                bottomReserve.px = 0
                scrollState.animateScrollToItem(entryCount)
            }
        }
    }

    LaunchedEffect(isStreaming) {
        if (isStreaming && sentFromKeyboard) {
            keyboard?.hide()
            sentFromKeyboard = false
        }
    }

    LaunchedEffect(isDrawerOpen) {
        if (isDrawerOpen) {
            keyboard?.hide()
        }
    }

    AgentChatScaffold(
        visibleMessages = visibleMessages,
        timelineMessages = timelineMessages,
        editHiddenIds = editHiddenIds,
        hasMessages = visibleMessages.isNotEmpty(),
        scrollState = scrollState,
        bottomReserve = bottomReserve,
        input = input,
        modelPickerState = modelPickerState,
        isCompacting = isCompacting,
        contextUsage = contextUsage,
        isStreaming = isStreaming,
        reasoningEffort = reasoningEffort,
        availableReasoningEfforts = availableReasoningEfforts,
        pendingImages = pendingImages,
        pendingFileReferences = pendingFileReferences,
        messageEdit = messageEdit,
        showEmptySuggestions = !isKeyboardVisible,
        characterName = characterName,
        shareIntro = shareIntro,
        keepBottomAnchored = keepBottomAnchored,
        keepLatestOnResize = keepLatestOnResize || initiallyShowLatestMessage,
        onBottomAnchorChanged = { keepBottomAnchored = it },
        onSubmit = { text ->
            sentFromKeyboard = true
            // 发送即重新锚定底部：用户从历史上方直接发送时，同帧内 isStreaming 与
            // 新消息一起到位，立即回到底部并恢复后续的流式平滑跟底。
            keepBottomAnchored = true
            onSubmit(text)
        },
        onReasoningEffortChange = onReasoningEffortChange,
        onCompactContext = onCompactContext,
        canCompactContext = canCompactContext,
        onModelSelected = onModelSelected,
        onStop = onStop,
        onAttachImage = onAttachImage,
        onRemoveImage = onRemoveImage,
        onAttachFiles = onAttachFiles,
        onAttachFolder = onAttachFolder,
        onAttachFilePath = onAttachFilePath,
        onRemoveFileReference = onRemoveFileReference,
        onEditMessage = onEditMessage,
        onCancelMessageEdit = onCancelMessageEdit,
        onDeleteMessage = onDeleteMessage,
        onRegenerateMessage = onRegenerateMessage,
        onSelectReplyCandidate = onSelectReplyCandidate,
        onSuggestionClick = { prompt ->
            // 点追问等同于发送，同样重新锚定底部。
            keepBottomAnchored = true
            onSuggestionClick(prompt)
        },
        onRunTraceClick = onRunTraceClick,
        onOpenBrowser = onOpenBrowser,
        currentBrowserMessageId = currentBrowserMessageId,
        modifier = modifier,
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AgentChatScaffold(
    visibleMessages: List<AgentChatMessageUi>,
    /** 时间线实际排出的消息：含编辑预览里被收起的后续消息（见 [editHiddenIds]）。 */
    timelineMessages: List<AgentChatMessageUi>,
    editHiddenIds: Set<String>,
    hasMessages: Boolean,
    scrollState: LazyListState,
    bottomReserve: ChatBottomReserve,
    input: String,
    modelPickerState: AgentModelPickerUiState,
    isCompacting: Boolean,
    contextUsage: AgentContextUsageUi,
    isStreaming: Boolean,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    pendingImages: List<PendingImageUi>,
    pendingFileReferences: List<PendingFileReferenceUi>,
    messageEdit: MessageEditUiState?,
    showEmptySuggestions: Boolean,
    characterName: String?,
    shareIntro: io.github.fartown.movo.ui.share.ShareIntro?,
    keepBottomAnchored: Boolean,
    keepLatestOnResize: Boolean,
    onBottomAnchorChanged: (Boolean) -> Unit,
    onSubmit: (String) -> Unit,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onCompactContext: () -> Unit,
    canCompactContext: Boolean,
    onModelSelected: (String) -> Unit,
    onStop: () -> Unit,
    onAttachImage: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
    onEditMessage: (String) -> Unit,
    onCancelMessageEdit: () -> Unit,
    onDeleteMessage: (String) -> Unit,
    onRegenerateMessage: (String) -> Unit,
    onSelectReplyCandidate: (String, Int) -> Unit,
    onSuggestionClick: (String) -> Unit,
    onRunTraceClick: () -> Unit,
    onOpenBrowser: () -> Unit,
    currentBrowserMessageId: String?,
    modifier: Modifier = Modifier,
) {
    val surfaceColor = MiuixTheme.colorScheme.surface
    val frostSupported = hasMessages && LocalBlurEnabled.current && isRuntimeShaderSupported()
    // 输入框上方 24 的磨砂条只在有内容滚到它下面时才需要真的采样消息列表（审查 A13）：
    // 列表停在底部时最后一行离磨砂条还有 14，条里只有页面底色，磨砂与透明看起来一样，这时不给整条列表挂 backdrop。
    // 有内容进入下方（可以继续向下滚）立即启用；回到底部后稍等再关，流式跟底时不来回切换。
    val contentUnderComposer by remember(scrollState) { derivedStateOf { scrollState.canScrollForward } }
    var frostActive by remember { mutableStateOf(false) }
    LaunchedEffect(frostSupported, contentUnderComposer) {
        when {
            !frostSupported -> frostActive = false
            contentUnderComposer -> frostActive = true
            frostActive -> {
                kotlinx.coroutines.delay(FROST_RELEASE_DELAY_MILLIS)
                frostActive = false
            }
        }
    }
    val frostEnabled = frostSupported && frostActive
    // 会话切换过渡（规范 9.3.1「视图切换」）：只有消息区（和首页内容）淡入淡出，输入栏不参与。
    val switchFade = LocalConversationSwitchFade.current
    val messageBackdrop = rememberLayerBackdrop {
        // Backdrop 必须包含不透明底色，否则文字边缘模糊到透明区域时会出现黑边。
        drawRect(surfaceColor)
        drawContent()
    }
    // Q1 文字飞成气泡 / Q6 光球延续的飞行层（规范 9.4「首页 → 对话」「后续发送」）。
    val reducedMotion = io.github.fartown.movo.ui.theme.LocalReducedMotion.current
    val flight = remember(reducedMotion) { ChatFlightController(reducedMotion) }
    val homeExitShift = with(LocalDensity.current) { 8.dp.roundToPx() }

    androidx.compose.runtime.CompositionLocalProvider(LocalChatFlight provides flight) {
    Box(modifier = modifier.fillMaxSize()) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(
            left = 0.dp,
            top = 0.dp,
            right = 0.dp,
            bottom = 0.dp,
        ),
        bottomBar = {
            AgentChatBottomBar(
                messageBackdrop = messageBackdrop.takeIf { frostEnabled },
                reserveFrost = frostSupported,
                hidden = switchFade?.outgoing == true,
                input = input,
                modelPickerState = modelPickerState,
                isCompacting = isCompacting,
                contextUsage = contextUsage,
                showContextUsage = hasMessages,
                isStreaming = isStreaming,
                reasoningEffort = reasoningEffort,
                availableReasoningEfforts = availableReasoningEfforts,
                pendingImages = pendingImages,
                pendingFileReferences = pendingFileReferences,
                messageEdit = messageEdit,
                onSubmit = onSubmit,
                onReasoningEffortChange = onReasoningEffortChange,
                onCompactContext = onCompactContext,
                canCompactContext = canCompactContext,
                onModelSelected = onModelSelected,
                onStop = onStop,
                onAttachImage = onAttachImage,
                onRemoveImage = onRemoveImage,
                onAttachFiles = onAttachFiles,
                onAttachFolder = onAttachFolder,
                onAttachFilePath = onAttachFilePath,
                onRemoveFileReference = onRemoveFileReference,
                onCancelMessageEdit = onCancelMessageEdit,
            )
        },
    ) { innerPadding ->
        val bottomPadding = innerPadding.calculateBottomPadding()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (switchFade != null) {
                        alpha = switchFade.contentAlpha()
                        translationY = switchFade.contentShiftY()
                    }
                },
        ) {
        if (hasMessages) {
            AgentConversationMessages(
                visibleMessages = timelineMessages,
                editHiddenIds = editHiddenIds,
                scrollState = scrollState,
                bottomReserve = bottomReserve,
                isStreaming = isStreaming,
                bottomInset = bottomPadding,
                keepBottomAnchored = keepBottomAnchored,
                keepLatestOnResize = keepLatestOnResize,
                onBottomAnchorChanged = onBottomAnchorChanged,
                onSuggestionClick = onSuggestionClick,
                onRunTraceClick = onRunTraceClick,
                onOpenBrowser = onOpenBrowser,
                onEditMessage = onEditMessage,
                onDeleteMessage = onDeleteMessage,
                onRegenerateMessage = onRegenerateMessage,
                onSelectReplyCandidate = onSelectReplyCandidate,
                messageActionsEnabled = !isStreaming && messageEdit == null,
                editTargetMessageId = messageEdit?.targetMessageId,
                currentBrowserMessageId = currentBrowserMessageId,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (frostEnabled) Modifier.layerBackdrop(messageBackdrop) else Modifier),
            )
        }
        // 首页 → 对话：问候、能力卡整体淡出 + 上移 8，120ms + `exit`（光球由飞行层接走，规范 9.4 ②）。
        AnimatedVisibility(
            visible = !hasMessages,
            enter = fadeIn(io.github.fartown.movo.ui.theme.MovoMotion.standard()),
            exit = fadeOut(io.github.fartown.movo.ui.theme.MovoMotion.fastExit()) +
                androidx.compose.animation.slideOutVertically(io.github.fartown.movo.ui.theme.MovoMotion.fastExit()) {
                    if (reducedMotion) 0 else -homeExitShift
                },
        ) {
            val composer = LocalConversationComposer.current
            if (shareIntro != null) {
                io.github.fartown.movo.ui.share.ShareIntroContent(
                    intro = shareIntro,
                    composerText = composer?.text?.toString() ?: input,
                    onChip = { prompt ->
                        // 快捷建议 = 以芯片文字为指令连同预填内容发送：先把草稿换成要发的内容，发送后草稿才能清空。
                        composer?.setTextAndPlaceCursorAtEnd(prompt)
                        onSubmit(prompt)
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = bottomPadding),
                )
            } else {
                MovoHomeContent(
                    characterName = characterName,
                    showCapabilities = showEmptySuggestions,
                    onSend = onSuggestionClick,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = bottomPadding),
                )
            }
        }
        }
    }
    ChatFlightOverlay(flight)
    }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun AgentConversationMessages(
    visibleMessages: List<AgentChatMessageUi>,
    scrollState: LazyListState,
    bottomReserve: ChatBottomReserve,
    isStreaming: Boolean,
    bottomInset: Dp,
    keepBottomAnchored: Boolean,
    keepLatestOnResize: Boolean = false,
    onBottomAnchorChanged: (Boolean) -> Unit,
    onSuggestionClick: (String) -> Unit = {},
    onRunTraceClick: () -> Unit = {},
    onOpenBrowser: () -> Unit = {},
    onEditMessage: (String) -> Unit = {},
    onDeleteMessage: (String) -> Unit = {},
    onRegenerateMessage: (String) -> Unit = {},
    onSelectReplyCandidate: (String, Int) -> Unit = { _, _ -> },
    messageActionsEnabled: Boolean = false,
    editTargetMessageId: String? = null,
    currentBrowserMessageId: String? = null,
    /** 编辑预览里被收起的消息（仍在时间线上，收起显示；取消编辑时展开回来）。 */
    editHiddenIds: Set<String> = emptySet(),
    modifier: Modifier = Modifier,
) {
    val timelineEntries = remember(visibleMessages) { visibleMessages.toTimelineEntries() }
    // 任务进行中有条目被移除（如「模型请求重试」提示在重试成功后去掉）：按它上一帧的高度在末尾补留白，
    // 列表停在底部时不会往回退、露出上一轮（真机 reserve：一次性下跳 183px 再滚回）。只在组合里写留白，排版阶段才读。
    val lastEntryKeys = remember { arrayOf<Set<Any>>(emptySet()) }
    remember(timelineEntries) {
        val keys = timelineEntries.mapTo(HashSet<Any>()) { it.key }
        if (isStreaming) {
            val removed = lastEntryKeys[0] - keys
            if (removed.isNotEmpty()) {
                val removedHeight = scrollState.layoutInfo.visibleItemsInfo.filter { it.key in removed }.sumOf { it.size }
                if (removedHeight > 0) bottomReserve.px += removedHeight
            }
        }
        lastEntryKeys[0] = keys
        keys
    }
    val lastWorkKey = timelineEntries.lastOrNull { it is AgentTimelineEntry.WorkProcess }?.key
    val workOutcomes = remember(timelineEntries) { workOutcomes(timelineEntries) }
    val workTurnSpans = remember(timelineEntries) { workTurnSpans(timelineEntries) }
    // 执行卡后面紧接着出现了有正文的回答：这张卡的步骤已经结束，收成摘要条（方案 B，只收一次）。
    val answeredWorkKeys = remember(timelineEntries) { answeredWorkKeys(timelineEntries) }
    val turnFinalWorkKeys = remember(timelineEntries) { lastWorkKeysPerTurn(timelineEntries) }
    val workStepOffsets = remember(timelineEntries) { workStepOffsets(timelineEntries) }
    // 后台监听行：叠放间距 4、与执行卡间距 8（规范 8.12）。
    val monitorRowSpacings = remember(timelineEntries) { monitorRowSpacings(timelineEntries) }
    // 事件轮（后台监听唤醒、没有用户原话）里的回答和失败卡：不提供「重新生成」「重试」。
    val nonRegenerableIds = remember(visibleMessages) {
        io.github.fartown.movo.ui.app.AgentConversationRevisionReducer.nonRegenerableMessageIds(visibleMessages)
    }
    // 暂停时本来就不会有数据：不提示「已 N 秒没有收到数据」。
    val runPaused = LocalRunControls.current.isPaused
    val stoppedWithoutWork = remember(timelineEntries) { stoppedNoticesWithoutWork(timelineEntries) }
    // 仍在进行的这一轮最后一条：模型重试提示在这里才是「正在重试」（恢复后的提示已从时间线去掉）。
    val activeNoticeId = if (isStreaming) {
        (timelineEntries.lastOrNull() as? AgentTimelineEntry.Message)?.message?.id
    } else null
    // 复制按钮只出现在每轮对话的最终结果上，中间步骤的过渡文本不提供复制入口。
    // 流式进行中当前这一轮尚未收尾，此时的“最后一条正文”只是中间步骤，不标记。
    val finalResultMessageIds = remember(visibleMessages, isStreaming) {
        resolveFinalResultMessageIds(visibleMessages, isStreaming = isStreaming)
    }
    // 流式消息的渲染会话按 id 提升到列表层持有：item 滚出视口被 LazyColumn 销毁后，
    // 滑回时复用同一解析会话与打字机进度，避免整段内容重新解析并重放显现动画。
    val streamingMarkdownStates = remember { mutableStateMapOf<String, StreamingMarkdownState>() }
    val bottomItemIndex = timelineEntries.size
    // 等待首个事件的小光球（Q6）挂在最后一条用户消息之后；首个事件到达（或本轮结束）时它自己淡出 120ms，
    // 播完才从列表移除（审查 B14：不用列表的退场动画，被移除的退场项会残留在原位）。
    val waitingAnchor = if (isStreaming) {
        (timelineEntries.lastOrNull() as? AgentTimelineEntry.Message)?.takeIf { it.message is UserMessageUi }?.key
    } else {
        null
    }
    var lingeringOrbAnchor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(waitingAnchor) {
        if (waitingAnchor != null) {
            lingeringOrbAnchor = waitingAnchor
        } else if (lingeringOrbAnchor != null) {
            kotlinx.coroutines.delay(io.github.fartown.movo.ui.theme.MovoMotion.FAST_EXIT.toLong())
            lingeringOrbAnchor = null
        }
    }
    val orbAfterIndex = (waitingAnchor ?: lingeringOrbAnchor)
        ?.let { key -> timelineEntries.indexOfFirst { it.key == key } }
        ?.takeIf { it >= 0 }
    // 光球离场期间插在列表中间，它之后的条目在 LazyColumn 里的下标后移一位。
    fun listIndexOf(entryIndex: Int): Int =
        if (orbAfterIndex != null && entryIndex > orbAfterIndex) entryIndex + 1 else entryIndex
    val isUserDragging by scrollState.interactionSource.collectIsDraggedAsState()
    val isAtBottom by remember(scrollState) {
        derivedStateOf { !scrollState.canScrollForward }
    }
    val densityScale = LocalDensity.current.density
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(
        isUserDragging,
        isAtBottom,
        keepBottomAnchored,
    ) {
        val next = resolveKeepBottomAnchored(
            current = keepBottomAnchored,
            isUserDragging = isUserDragging,
            isAtBottom = isAtBottom,
        )
        if (next != keepBottomAnchored) {
            onBottomAnchorChanged(next)
        }
    }

    val tailMessage = visibleMessages.lastOrNull() as? AgentMessageUi
    val isTailRendering = tailMessage?.let { message ->
        streamingMarkdownStates[message.id]?.let { state ->
            state.revealedContent != message.content
        }
    } == true
    var isBottomSettling by remember { mutableStateOf(isStreaming) }

    LaunchedEffect(isStreaming, isTailRendering, keepBottomAnchored, isUserDragging) {
        if (!keepBottomAnchored || isUserDragging) {
            isBottomSettling = false
        } else if (isStreaming || isTailRendering) {
            isBottomSettling = true
        } else if (isBottomSettling) {
            // 显现完成后还会切换稳定排版并插入操作行，等其完成测量再收口跟底。
            withFrameNanos { }
            withFrameNanos { }
            snapshotFlow { !scrollState.canScrollForward }.first { it }
            isBottomSettling = false
        }
    }

    val shouldFollowBottom by rememberUpdatedState(
        resolveBottomFollowEnabled(
            isStreaming = isStreaming || keepLatestOnResize,
            keepBottomAnchored = keepBottomAnchored,
            isUserDragging = isUserDragging,
            isBottomSettling = isBottomSettling,
        )
    )
    val currentBottomItemIndex by rememberUpdatedState(bottomItemIndex)
    val bottomFollowDecisions = remember(scrollState) {
        Channel<BottomFollowDecision>(Channel.CONFLATED)
    }

    LaunchedEffect(
        bottomItemIndex,
        keepBottomAnchored,
        isUserDragging,
        isStreaming,
    ) {
        if (shouldRequestInitialBottom(
                isStreaming = isStreaming,
                keepBottomAnchored = keepBottomAnchored,
                isUserDragging = isUserDragging,
            )
        ) {
            // 底部（哨兵）还在屏幕上时交给平滑跟随，不一帧跳到底：新条目（如回答开始）出现时直接跳会连末尾留白
            // 一起滚过去，内容一帧上跳 200 多 px（真机 reserve3）。底部已不在屏幕上才直接跳，跳之前清掉留白。
            val bottomVisible = scrollState.layoutInfo.visibleItemsInfo.any { it.key == ChatBottomSentinelKey }
            if (!bottomVisible) {
                bottomReserve.px = 0
                scrollState.requestScrollToItem(bottomItemIndex)
            }
        }
    }

    // 流式输出及渲染收尾期间发布最新的跟底距离。历史消息中的步骤/思考展开同样会改变
    // 列表高度，但那是用户主动查看内容，不能被误判成尾部文字增长。
    LaunchedEffect(scrollState) {
        snapshotFlow {
            val layoutInfo = scrollState.layoutInfo
            val sentinel = layoutInfo.visibleItemsInfo.firstOrNull { item ->
                item.key == ChatBottomSentinelKey
            }
            BottomFollowLayout(
                enabled = shouldFollowBottom,
                bottomItemIndex = currentBottomItemIndex,
                sentinelBottom = sentinel?.let { it.offset + it.size },
                // 输入器高度属于滚动内容的 bottom inset，而不是滚动容器高度。
                // 跟底目标应是 afterContentPadding 之前的正文边界。
                viewportEnd = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding,
                lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index,
                lastVisibleBottom = layoutInfo.visibleItemsInfo.lastOrNull()?.let { it.offset + it.size },
            )
        }
            .distinctUntilChanged()
            .collect { layout ->
                val decision = resolveBottomFollowDecision(
                    enabled = layout.enabled,
                    bottomItemIndex = layout.bottomItemIndex,
                    sentinelBottom = layout.sentinelBottom,
                    viewportEnd = layout.viewportEnd,
                    lastVisibleIndex = layout.lastVisibleIndex,
                    lastVisibleBottom = layout.lastVisibleBottom,
                )
                bottomFollowDecisions.trySend(decision)
            }
    }

    // 一个持续存在的帧时钟从当前屏幕位置追向最新目标。新字符继续到达时只更新目标，
    // 不取消并重启动画，因此速度连续；用户开始拖动后，enabled=false 会立即停止跟随。
    LaunchedEffect(scrollState, bottomFollowDecisions) {
        var remainingDistancePx = 0f
        var requestIndex: Int? = null
        var previousFrameNanos = 0L

        fun accept(decision: BottomFollowDecision) {
            remainingDistancePx = decision.scrollByPx.toFloat()
            requestIndex = decision.requestIndex
        }

        while (currentCoroutineContext().isActive) {
            if (remainingDistancePx <= 0f && requestIndex == null) {
                accept(bottomFollowDecisions.receive())
                previousFrameNanos = 0L
            }
            while (true) {
                val latest = bottomFollowDecisions.tryReceive().getOrNull() ?: break
                accept(latest)
            }

            if (!shouldFollowBottom) {
                remainingDistancePx = 0f
                requestIndex = null
                continue
            }

            requestIndex?.let { targetIndex ->
                // 底部已不在屏幕上才会走到这里：先清掉末尾留白（在屏幕外），直接到底时不滚进留白。
                bottomReserve.px = 0
                scrollState.requestScrollToItem(targetIndex)
                requestIndex = null
                remainingDistancePx = 0f
                return@let
            }
            if (remainingDistancePx <= 0f) continue

            val frameNanos = withFrameNanos { it }
            val elapsedSeconds = if (previousFrameNanos == 0L) {
                1f / 60f
            } else {
                ((frameNanos - previousFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            }
            previousFrameNanos = frameNanos

            while (true) {
                val latest = bottomFollowDecisions.tryReceive().getOrNull() ?: break
                accept(latest)
            }
            if (!shouldFollowBottom || requestIndex != null || remainingDistancePx <= 0f) continue

            val step = smoothBottomFollowStep(
                distancePx = remainingDistancePx,
                elapsedSeconds = elapsedSeconds,
                density = densityScale,
            )
            var consumedStep = 0f
            try {
                scrollState.scroll {
                    scrollBy(step)
                    consumedStep = step
                }
                remainingDistancePx = if (consumedStep > 0f) {
                    (remainingDistancePx - consumedStep).coerceAtLeast(0f)
                } else {
                    0f
                }
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                remainingDistancePx = 0f
            }
        }
    }

    // 可见区底边（窗口坐标）= 不透明输入框的上沿（列表底边 − 输入栏 + 输入栏顶部的磨砂渐隐区）：可能超过可见区的
    // 展开 / 收起按它封顶过渡高度。曲线正好走满看得见的部分（含磨砂区），结束时补齐到全高的那一下藏在不透明输入框后面。
    // 封到磨砂区上沿时，补齐落在磨砂区里看得见一跳；封到列表底边时，曲线最快的前段就走完了可见部分，只露出 58ms（真机 verify4 / verify5）。
    val listBottomPx = remember { intArrayOf(-1) }
    val density = LocalDensity.current
    val hiddenBottomPx = with(density) { (bottomInset - ChatBottomFrostHeight).coerceAtLeast(0.dp).roundToPx() }
    val currentHiddenBottomPx by rememberUpdatedState(hiddenBottomPx)
    val visibleBottom: () -> Int? = remember {
        { listBottomPx[0].takeIf { it >= 0 }?.let { it - currentHiddenBottomPx } }
    }
    // 滚动层保持整屏，输入器作为后绘制浮层；输入器高度进入列表的
    // afterContentPadding，确保跟到底部时最后一行停在输入器上方。
    Box(modifier = modifier.clipToBounds()) {
        // 从执行卡末尾点「收起」：卡片逐帧收起时把列表往回滚同样的距离，卡片底边与下面的回答不动（Figma 候选「执行卡长记录」方案 1）。
        val chatListScroll: (Float) -> Float = remember(scrollState) { { delta -> scrollState.dispatchRawDelta(delta) } }
        // 列表末尾的临时留白（见 [ChatBottomReserve]）：看不见的部分在每次排版后收掉。
        LaunchedEffect(scrollState, bottomReserve) {
            snapshotFlow {
                val info = scrollState.layoutInfo
                val item = info.visibleItemsInfo.firstOrNull { it.key == ChatBottomReserveKey }
                val visibleEnd = info.viewportEndOffset - info.afterContentPadding
                if (item == null) 0 else (visibleEnd - item.offset).coerceAtLeast(0)
            }.collect { visible ->
                if (bottomReserve.px > visible) bottomReserve.px = visible
            }
        }
        androidx.compose.runtime.CompositionLocalProvider(
            io.github.fartown.movo.ui.components.movo.LocalVisibleViewportBottom provides visibleBottom,
            LocalChatListScroll provides chatListScroll,
            LocalChatBottomReserve provides bottomReserve,
            LocalMonitorRowSpacings provides monitorRowSpacings,
        ) {
        LazyColumn(
            state = scrollState,
            verticalArrangement = Arrangement.Top,
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { listBottomPx[0] = it.boundsInWindow().bottom.toInt() }
                .scrollEndHaptic()
                .overScrollVertical(),
            contentPadding = PaddingValues(
                top = 14.dp,
                bottom = bottomInset + 14.dp,
            ),
            overscrollEffect = null,
        ) {
            val entryItem: @Composable androidx.compose.foundation.lazy.LazyItemScope.(AgentTimelineEntry) -> Unit = { entry ->
                val itemModifier = Modifier.animateItem(
                    fadeInSpec = io.github.fartown.movo.ui.theme.MovoMotion.fast(),
                    placementSpec = null,
                    // 历史轮次被编辑、删除或重新生成时必须立即退出；退出动画会让已从
                    // 状态中裁掉的旧消息继续绘制，并与同位置的新流式消息短暂重叠。
                    fadeOutSpec = null,
                )
                when (entry) {
                    is AgentTimelineEntry.Message -> {
                        val message = entry.message
                        // 删除 / 重新生成：内容先淡出 120ms，随后高度收起 `standard`，下方各行跟随上移（规范 9.3「列表增删」、9.4），
                        // 播完才真正改动列表，避免旧消息的退场与同位置的新流式消息重叠。
                        EditHiddenItem(hidden = message.id in editHiddenIds, modifier = itemModifier) {
                            LeavingItem(leaving = message.id in LocalLeavingMessages.current) {
                                ChatMessageItem(
                                    message = message,
                                    retainedStreamingState = (message as? AgentMessageUi)
                                        ?.takeIf { it.isStreaming || streamingMarkdownStates.containsKey(it.id) }
                                        ?.let { agentMessage ->
                                            streamingMarkdownStates.getOrPut(agentMessage.id) {
                                                StreamingMarkdownState()
                                            }
                                        },
                                    onSuggestionClick = onSuggestionClick,
                                    onRunTraceClick = onRunTraceClick,
                                    onOpenBrowser = onOpenBrowser,
                                    showBrowserShortcut = message is ToolActivityMessageUi &&
                                        message.toolName in BROWSER_TOOL_NAMES &&
                                        message.id == currentBrowserMessageId,
                                    showCopyAction = message !is AgentMessageUi ||
                                        message.characterEditable || message.id in finalResultMessageIds,
                                    showMessageActions = message.id in finalResultMessageIds ||
                                        (message is AgentMessageUi && message.characterEditable),
                                    messageActionsEnabled = messageActionsEnabled,
                                    isEditing = message.id == editTargetMessageId,
                                    onEditMessage = onEditMessage,
                                    onDeleteMessage = onDeleteMessage,
                                    onRegenerateMessage = onRegenerateMessage,
                                    canRegenerate = message.id !in nonRegenerableIds,
                                    onSelectReplyCandidate = onSelectReplyCandidate,
                                    noticeActive = message.id == activeNoticeId,
                                    stoppedWithoutWork = message.id in stoppedWithoutWork,
                                )
                            }
                        }
                    }

                    is AgentTimelineEntry.WorkProcess -> {
                        entry.messages.forEach { message ->
                            if (message is ThinkingMessageUi && message.isStreaming) {
                                streamingMarkdownStates.getOrPut(message.id) {
                                    StreamingMarkdownState()
                                }
                            }
                        }
                        EditHiddenItem(
                            hidden = editHiddenIds.isNotEmpty() && entry.messages.all { it.id in editHiddenIds },
                            modifier = itemModifier,
                        ) {
                            AgentWorkProcess(
                                id = entry.key,
                                messages = entry.messages,
                                // 本轮仍在进行：模型在两步之间思考时步骤都已完成，但执行卡不能当作完成收起。
                                runActive = isStreaming && entry.key == lastWorkKey,
                                answerStarted = entry.key in answeredWorkKeys,
                                lastCardOfTurn = entry.key in turnFinalWorkKeys,
                                stepOffset = workStepOffsets[entry.key] ?: 0,
                                outcome = workOutcomes[entry.key],
                                turnSpan = workTurnSpans[entry.key],
                                onOpenBrowser = onOpenBrowser,
                                currentBrowserMessageId = currentBrowserMessageId,
                                retainedStreamingStates = streamingMarkdownStates,
                                actionsEnabled = messageActionsEnabled,
                                onEditMessage = onEditMessage,
                                onDeleteMessage = onDeleteMessage,
                            )
                        }
                    }
                }
            }
            if (orbAfterIndex == null) {
                items(items = timelineEntries, key = { it.key }, contentType = ::timelineContentType) { entry -> entryItem(entry) }
            } else {
                items(items = timelineEntries.subList(0, orbAfterIndex + 1), key = { it.key }, contentType = ::timelineContentType) { entry -> entryItem(entry) }
                item(key = "waiting-orb") {
                    // 只淡入，不用列表退场：离场由 WaitingOrb 自己淡出，播完再移除。
                    Box(
                        Modifier
                            .animateItem(
                                fadeInSpec = io.github.fartown.movo.ui.theme.MovoMotion.fast(),
                                placementSpec = null,
                                fadeOutSpec = null,
                            )
                            .padding(horizontal = 20.dp),
                    ) { WaitingOrb(leaving = waitingAnchor == null) }
                }
                items(
                    items = timelineEntries.subList(orbAfterIndex + 1, timelineEntries.size),
                    key = { it.key },
                    contentType = ::timelineContentType,
                ) { entry -> entryItem(entry) }
            }
            if (isStreaming && !runPaused) {
                item(key = "run-stall") { RunStallNotice(messageIds = visibleMessages.map { it.id }) }
            }
            item(key = ChatBottomSentinelKey) {
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp),
                )
            }
            // 放在哨兵之后：跟底只追到正文末尾（哨兵），不追留白。高度只在排版阶段读取。
            item(key = ChatBottomReserveKey) {
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .layout { measurable, constraints ->
                            val height = bottomReserve.px
                            val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
                            layout(placeable.width, height) { placeable.place(0, 0) }
                        },
                )
            }
        }
        }

        // 回到底部（9.3）：离开底部时出现，淡入 + 缩放 0.86 → 1（fast）。
        AnimatedVisibility(
            visible = !keepBottomAnchored && !isAtBottom,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomInset + 12.dp),
            enter = fadeIn(io.github.fartown.movo.ui.theme.MovoMotion.fast()) +
                scaleIn(io.github.fartown.movo.ui.theme.MovoMotion.fast(), initialScale = 0.86f),
            exit = fadeOut(io.github.fartown.movo.ui.theme.MovoMotion.fastExit()),
        ) {
            val shape = androidx.compose.foundation.shape.CircleShape
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .movoElevationCard(shape)
                    .movoClickable(io.github.fartown.movo.ui.components.movo.PressKind.Solid, shape = shape) {
                        onBottomAnchorChanged(true)
                        coroutineScope.launch { scrollState.animateScrollToItem(bottomItemIndex) }
                    }
                    .clip(shape)
                    .background(io.github.fartown.movo.ui.theme.MovoColors.bgSurface)
                    .border(io.github.fartown.movo.ui.theme.MovoSize.hairline, io.github.fartown.movo.ui.theme.MovoColors.borderHairline, shape),
                contentAlignment = Alignment.Center,
            ) {
                io.github.fartown.movo.ui.theme.MovoIcon(
                    io.github.fartown.movo.ui.theme.MovoIcons.ArrowDown,
                    contentDescription = stringResource(R.string.ui_back_to_bottom_32282e),
                    size = 20.dp,
                )
            }
        }
    }
}

private fun Modifier.movoElevationCard(shape: androidx.compose.ui.graphics.Shape): Modifier =
    movoElevation(io.github.fartown.movo.ui.theme.MovoElevation.Card, shape)

private data class BottomFollowLayout(
    val enabled: Boolean,
    val bottomItemIndex: Int,
    val sentinelBottom: Int?,
    val viewportEnd: Int,
    val lastVisibleIndex: Int?,
    val lastVisibleBottom: Int?,
)

internal data class BottomFollowDecision(
    val scrollByPx: Int = 0,
    val requestIndex: Int? = null,
)

internal fun resolveBottomFollowDecision(
    enabled: Boolean,
    bottomItemIndex: Int,
    sentinelBottom: Int?,
    viewportEnd: Int,
    lastVisibleIndex: Int?,
    lastVisibleBottom: Int? = null,
): BottomFollowDecision {
    if (!enabled) return BottomFollowDecision()
    val overflow = sentinelBottom?.minus(viewportEnd)
    return when {
        overflow != null && overflow > 0 -> BottomFollowDecision(scrollByPx = overflow)
        // 哨兵只是被刚长出来的最后一项（回答）挤出了屏幕：按最后一项的底边平滑追，不一帧跳到底
        // （真机 reserve4：回答出现时一帧上跳 956px）。离底部还差几项时才直接跳。
        sentinelBottom == null &&
            lastVisibleIndex != null &&
            lastVisibleIndex >= bottomItemIndex - 1 &&
            lastVisibleBottom != null &&
            lastVisibleBottom > viewportEnd -> BottomFollowDecision(scrollByPx = lastVisibleBottom - viewportEnd)
        sentinelBottom == null &&
            lastVisibleIndex != null &&
            lastVisibleIndex < bottomItemIndex -> BottomFollowDecision(requestIndex = bottomItemIndex)
        else -> BottomFollowDecision()
    }
}

internal fun smoothBottomFollowStep(
    distancePx: Float,
    elapsedSeconds: Float,
    density: Float,
): Float {
    if (distancePx <= 0f || elapsedSeconds <= 0f) return 0f
    if (distancePx <= BOTTOM_FOLLOW_SNAP_DISTANCE_PX) return distancePx

    val frameSeconds = elapsedSeconds.coerceAtMost(BOTTOM_FOLLOW_MAX_FRAME_SECONDS)
    val easedStep = distancePx * (1f - exp(-frameSeconds / BOTTOM_FOLLOW_RESPONSE_SECONDS))
    val speedLimitedStep = BOTTOM_FOLLOW_MAX_SPEED_DP_PER_SECOND * density * frameSeconds
    return min(distancePx, min(easedStep.coerceAtLeast(BOTTOM_FOLLOW_MIN_STEP_PX), speedLimitedStep))
}

/**
 * 执行卡所在这一轮没有正常完成的原因与这一轮总共执行的步数（一轮里的回答会把执行卡分成几张，
 * 摘要写整轮的步数）；正常完成的执行卡不在结果里。
 */
/** 同一轮里排在这张执行卡前面的工具步骤数（回答把一轮分成几张卡时，「第 N 步」接着数）。 */
internal fun workStepOffsets(entries: List<AgentTimelineEntry>): Map<String, Int> {
    val offsets = mutableMapOf<String, Int>()
    var steps = 0
    entries.forEach { entry ->
        when (entry) {
            is AgentTimelineEntry.Message -> if (entry.message is UserMessageUi || entry.message.isTurnStart()) steps = 0
            is AgentTimelineEntry.WorkProcess -> {
                offsets[entry.key] = steps
                steps += entry.messages.count { it is ToolActivityMessageUi }
            }
        }
    }
    return offsets
}

/**
 * 最近一轮（最后一条用户消息之后）已做完的工具步骤数：暂停提示条「已完成 N 步」。成功和失败都算做完，
 * 进行中 / 被暂停打断（Running、Unknown）的不算，与头部「第 N 步」同一口径（暂停在第 41 步 = 已完成 40 步）。
 */
internal fun currentTurnCompletedSteps(entries: List<AgentTimelineEntry>): Int {
    val start = entries.indexOfLast { it is AgentTimelineEntry.Message && (it.message is UserMessageUi || it.message.isTurnStart()) }
    return entries.drop(start + 1).sumOf { entry ->
        (entry as? AgentTimelineEntry.WorkProcess)?.messages?.count {
            it is ToolActivityMessageUi && (it.status == ToolActivityStatusUi.Success || it.status == ToolActivityStatusUi.Failed)
        } ?: 0
    }
}

/** 列表项复用分组：同一种消息的组合可以互相复用，滚动时少建节点。 */
internal fun timelineContentType(entry: AgentTimelineEntry): Any = when (entry) {
    is AgentTimelineEntry.Message -> entry.message::class
    is AgentTimelineEntry.WorkProcess -> AgentTimelineEntry.WorkProcess::class
}

/** 每一轮（用户消息或唤醒事件之后）的最后一张执行卡。 */
internal fun lastWorkKeysPerTurn(entries: List<AgentTimelineEntry>): Set<String> {
    val keys = mutableSetOf<String>()
    var last: String? = null
    entries.forEach { entry ->
        when (entry) {
            is AgentTimelineEntry.Message -> if (entry.message is UserMessageUi || entry.message.isTurnStart()) {
                last?.let(keys::add)
                last = null
            }
            is AgentTimelineEntry.WorkProcess -> last = entry.key
        }
    }
    last?.let(keys::add)
    return keys
}

/** 后面紧接着一条有正文的回答的执行卡。 */
internal fun answeredWorkKeys(entries: List<AgentTimelineEntry>): Set<String> =
    entries.zipWithNext().mapNotNullTo(mutableSetOf()) { (entry, next) ->
        entry.key.takeIf {
            entry is AgentTimelineEntry.WorkProcess &&
                ((next as? AgentTimelineEntry.Message)?.message as? AgentMessageUi)?.content?.isNotBlank() == true
        }
    }

internal data class WorkOutcome(
    val kind: Kind,
    val steps: Int,
    /** 整轮第一步开始 / 最后一步结束的时刻，摘要条用时按整轮算。 */
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
) {
    enum class Kind { Unfinished, Stopped }
}

/**
 * 执行卡之后、下一条用户消息之前出现了失败 / 中断卡（[WorkOutcome.Unfinished]）或「已停止」（[WorkOutcome.Stopped]）。
 * 这类执行卡即使每一步都成功，摘要条也不能显示「✓ 已完成」。
 */
internal fun workOutcomes(entries: List<AgentTimelineEntry>): Map<String, WorkOutcome> {
    val outcomes = mutableMapOf<String, WorkOutcome>()
    var pending: String? = null
    var turnSteps = 0
    var turnStart: Long? = null
    var turnEnd: Long? = null
    for (entry in entries) {
        when (entry) {
            is AgentTimelineEntry.WorkProcess -> {
                pending = entry.key
                val tools = entry.messages.filterIsInstance<ToolActivityMessageUi>()
                turnSteps += tools.size
                tools.mapNotNull { it.startedAtMillis }.minOrNull()?.let { turnStart = minOf(turnStart ?: it, it) }
                tools.mapNotNull { it.finishedAtMillis }.maxOrNull()?.let { turnEnd = maxOf(turnEnd ?: it, it) }
            }
            is AgentTimelineEntry.Message -> when (val message = entry.message) {
                is UserMessageUi, is MonitorEventMessageUi -> if (message.isTurnStart()) {
                    pending = null
                    turnSteps = 0
                    turnStart = null
                    turnEnd = null
                }
                is SystemNoticeMessageUi -> {
                    val kind = when (message.code) {
                        SystemNoticeCode.RuntimeFailed, SystemNoticeCode.Interrupted -> WorkOutcome.Kind.Unfinished
                        SystemNoticeCode.Stopped -> WorkOutcome.Kind.Stopped
                        else -> null
                    }
                    if (kind != null) {
                        pending?.let { outcomes[it] = WorkOutcome(kind, turnSteps, turnStart, turnEnd) }
                        pending = null
                    }
                }
                else -> Unit
            }
        }
    }
    return outcomes
}

/** 一轮任务的起止时刻（发起它的用户消息上记的；结束前 [finishedAt] 为 null）。 */
internal data class WorkTurnSpan(val startedAt: Long, val finishedAt: Long?)

/**
 * 每张执行卡所在这一轮任务的起止时刻：执行卡的计时、用时、起止时间按整轮算，与运行日志一致（2026-09-28 定）。
 * 一轮被回答分成几张卡时，每张都用整轮的时刻。旧数据没有记录时不在结果里，执行卡退回按步骤时间算。
 */
internal fun workTurnSpans(entries: List<AgentTimelineEntry>): Map<String, WorkTurnSpan> {
    val spans = mutableMapOf<String, WorkTurnSpan>()
    var span: WorkTurnSpan? = null
    for (entry in entries) {
        when (entry) {
            is AgentTimelineEntry.WorkProcess -> span?.let { spans[entry.key] = it }
            is AgentTimelineEntry.Message -> {
                val message = entry.message
                if (message is UserMessageUi && !message.isRunSupplement()) {
                    span = message.runStartedAtMillis?.let { WorkTurnSpan(it, message.runFinishedAtMillis) }
                } else if (message is MonitorEventMessageUi && message.startsTurn) {
                    span = message.runStartedAtMillis?.let { WorkTurnSpan(it, message.runFinishedAtMillis) }
                }
            }
        }
    }
    return spans
}

/** 所在这一轮（两条用户消息之间）没有执行卡的「已停止」提示：摘要条上没有停止方块，提示自己带（5.7）。 */
internal fun stoppedNoticesWithoutWork(entries: List<AgentTimelineEntry>): Set<String> {
    val result = mutableSetOf<String>()
    var turnHasWork = false
    for (entry in entries) {
        when (entry) {
            is AgentTimelineEntry.WorkProcess -> turnHasWork = true
            is AgentTimelineEntry.Message -> when (val message = entry.message) {
                is UserMessageUi, is MonitorEventMessageUi -> if (message.isTurnStart()) turnHasWork = false
                is SystemNoticeMessageUi -> if (message.code == SystemNoticeCode.Stopped && !turnHasWork) result += message.id
                else -> Unit
            }
        }
    }
    return result
}

internal sealed interface AgentTimelineEntry {
    val key: String

    data class Message(
        val message: AgentChatMessageUi,
    ) : AgentTimelineEntry {
        override val key: String = message.id
    }

    data class WorkProcess(
        override val key: String,
        val messages: List<AgentChatMessageUi>,
    ) : AgentTimelineEntry
}

internal fun List<AgentChatMessageUi>.toTimelineEntries(): List<AgentTimelineEntry> = buildList {
    val workMessages = mutableListOf<AgentChatMessageUi>()

    fun flushWorkProcess() {
        if (workMessages.isEmpty()) return
        add(
            AgentTimelineEntry.WorkProcess(
                key = "work-${workMessages.first().id}",
                messages = workMessages.toList(),
            )
        )
        workMessages.clear()
    }

    arrangeTurnsForTimeline(this@toTimelineEntries).forEach { message ->
        // 执行中的补充紧跟在工作过程之后时，作为「你的补充」步骤留在同一张执行卡里（规范 8.1、8.4）。
        if (message.isWorkProcessMessage() || (message.isRunSupplement() && workMessages.isNotEmpty())) {
            workMessages += message
        } else {
            flushWorkProcess()
            add(AgentTimelineEntry.Message(message))
        }
    }
    flushWorkProcess()
}

/** 运行时补充以 `user-<runId>-supplement-<index>` 的用户消息投影进来（见 AgentRunMessageProjector）。 */
internal fun AgentChatMessageUi.isRunSupplement(): Boolean =
    this is UserMessageUi && id.startsWith("user-") && id.contains("-supplement-")

private val RETRY_NOTICE_ID = Regex("^assistant-(.+)-retry-(\\d+)$")

/**
 * 只影响显示，不改消息本身（导出、重放仍用原始消息）。按轮（两条用户消息之间）整理：
 * - 模型自动重试后恢复了（后面还有步骤或回答）：去掉重试提示，以及失败那一轮已经输出的半截回答（它不进上下文，
 *   重试会重新生成），执行卡与回答都不被切开；仍在重试或最终失败时照常显示。
 * - 「已停止」「运行失败」「已中断」是这一轮的结尾：停止后才到的步骤排在它们前面。
 */
internal fun arrangeTurnsForTimeline(messages: List<AgentChatMessageUi>): List<AgentChatMessageUi> {
    val result = ArrayList<AgentChatMessageUi>(messages.size)
    var turn = ArrayList<AgentChatMessageUi>()
    fun flushTurn() {
        if (turn.isEmpty()) return
        val dropped = HashSet<String>()
        turn.forEachIndexed { index, message ->
            if (message !is SystemNoticeMessageUi || message.code != SystemNoticeCode.ModelRetry) return@forEachIndexed
            val recovered = turn.subList(index + 1, turn.size).any { it.isWorkProcessMessage() || it is AgentMessageUi }
            if (!recovered) return@forEachIndexed
            dropped += message.id
            RETRY_NOTICE_ID.find(message.id)?.let { match ->
                val failedRoundText = "assistant-${match.groupValues[1]}-${match.groupValues[2]}-"
                turn.forEach { if (it is AgentMessageUi && it.id.startsWith(failedRoundText)) dropped += it.id }
            }
        }
        val kept = turn.filterNot { it.id in dropped }
        val (endings, body) = kept.partition { it.isTurnEnding() }
        result += body
        result += endings
        turn = ArrayList()
    }
    for (message in messages) {
        if (message.isTurnStart()) {
            flushTurn()
            result += message
        } else {
            turn += message
        }
    }
    flushTurn()
    return result
}

private fun AgentChatMessageUi.isTurnEnding(): Boolean =
    this is SystemNoticeMessageUi && (
        code == SystemNoticeCode.Stopped || code == SystemNoticeCode.RuntimeFailed || code == SystemNoticeCode.Interrupted
    )

private fun AgentChatMessageUi.isWorkProcessMessage(): Boolean =
    this is ThinkingMessageUi || this is ToolActivityMessageUi || this is ToolSummaryMessageUi

/**
 * 一轮对话（两条用户消息之间）里最后一条 Agent 正文视为最终结果，其余为中间步骤。
 * 流式传输期间当前轮次尚未结束，最后一轮不标记，等传输结束后复制按钮才出现；
 * 之前已结束轮次的最终结果不受影响。
 */
internal fun resolveFinalResultMessageIds(
    messages: List<AgentChatMessageUi>,
    isStreaming: Boolean = false,
): Set<String> {
    val ids = LinkedHashSet<String>()
    var lastAgentMessageId: String? = null
    messages.forEach { message ->
        when (message) {
            is UserMessageUi -> {
                lastAgentMessageId?.let(ids::add)
                lastAgentMessageId = null
            }
            is MonitorEventMessageUi -> if (message.startsTurn) {
                lastAgentMessageId?.let(ids::add)
                lastAgentMessageId = null
            }
            is AgentMessageUi -> lastAgentMessageId = message.id
            else -> Unit
        }
    }
    if (!isStreaming) {
        lastAgentMessageId?.let(ids::add)
    }
    return ids
}

@Composable
private fun AgentChatBottomBar(
    messageBackdrop: LayerBackdrop?,
    /** 会话中且支持磨砂：磨砂条暂时不采样（列表停在底部）时也保留 24 的位置，布局不跳。 */
    reserveFrost: Boolean,
    /** 会话切换时离场的那一份输入栏：不显示（新会话的输入栏原地接上，不闪）。 */
    hidden: Boolean,
    input: String,
    modelPickerState: AgentModelPickerUiState,
    isCompacting: Boolean,
    contextUsage: AgentContextUsageUi,
    showContextUsage: Boolean,
    isStreaming: Boolean,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    pendingImages: List<PendingImageUi>,
    pendingFileReferences: List<PendingFileReferenceUi>,
    messageEdit: MessageEditUiState?,
    onSubmit: (String) -> Unit,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onCompactContext: () -> Unit,
    canCompactContext: Boolean,
    onModelSelected: (String) -> Unit,
    onStop: () -> Unit,
    onAttachImage: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
    onCancelMessageEdit: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 语音是进程内唯一的会话状态，和浏览器快照一样直接观察，不逐层透传。
    val voice by VoiceSessionManager.state.collectAsState()
    // 语音就地开始：不开新窗口，也不需要悬浮窗权限。
    fun startVoiceConversation() = VoiceEntry.startInPlace(context)
    val micPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) startVoiceConversation() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (hidden) 0f else 1f }
            .imePadding(),
    ) {
        if (messageBackdrop != null) {
            val blurColors = BlurDefaults.blurColors(
                blendColors = listOf(
                    BlendColorEntry(io.github.fartown.movo.ui.theme.MovoColors.bgCanvas.copy(alpha = 0.72f))
                ),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // Measure the composer before this decorative fade. Editing hints and
                    // attachment chips still need room for a readable line above the IME.
                    .weight(1f, fill = false)
                    .height(ChatBottomFrostHeight)
                    // DstIn 让真实磨砂在顶部透明、靠近输入框时逐渐变实，消除硬裁切线。
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black),
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                    .textureBlur(
                        backdrop = messageBackdrop,
                        shape = RectangleShape,
                        blurRadius = 20f,
                        colors = blurColors,
                    ),
            )
        } else if (reserveFrost) {
            // 磨砂条下面只有页面底色时，磨砂的结果就是底色：留出同样的 24，不采样。
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .height(ChatBottomFrostHeight),
            )
        } else {
            // 空白主页沿用原来的轻微渐隐，不改变主页视觉。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .height(16.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                io.github.fartown.movo.ui.theme.MovoColors.bgCanvas,
                            ),
                        )
                    ),
            )
        }
        // 输入框外边距 20，距手势条 8（规范 2.2、8.2）。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(io.github.fartown.movo.ui.theme.MovoColors.bgCanvas)
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        ) {
            AgentChatInputBar(
                input = input,
                modelPickerState = modelPickerState,
                isCompacting = isCompacting,
                contextUsage = contextUsage,
                showContextUsage = showContextUsage,
                isStreaming = isStreaming,
                reasoningEffort = reasoningEffort,
                availableReasoningEfforts = availableReasoningEfforts,
                pendingImages = pendingImages,
                pendingFileReferences = pendingFileReferences,
                isEditingMessage = messageEdit != null,
                editHasLaterTurns = messageEdit?.hasLaterTurns == true,
                preserveFollowingMessages = messageEdit?.preserveFollowingMessages == true,
                onSubmit = { text ->
                    // 手动发送说明用户改用手了：本轮不播报，模态降回文字。
                    if (voice.active) VoiceSessionManager.switchToText()
                    onSubmit(text)
                },
                onReasoningEffortChange = onReasoningEffortChange,
                onCompactContext = onCompactContext,
                canCompactContext = canCompactContext,
                onModelSelected = onModelSelected,
                onStop = onStop,
                onAttachImage = onAttachImage,
                onRemoveImage = onRemoveImage,
                onAttachFiles = onAttachFiles,
                onAttachFolder = onAttachFolder,
                onAttachFilePath = onAttachFilePath,
                onRemoveFileReference = onRemoveFileReference,
                onCancelMessageEdit = onCancelMessageEdit,
                isListening = voice.active,
                onToggleListen = {
                    // 任务在跑也允许开语音：说的话会排队等当前任务结束，不再拒绝。
                    if (voice.active) {
                        VoiceSessionManager.switchToText()
                    } else {
                        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                            context, android.Manifest.permission.RECORD_AUDIO,
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (granted) startVoiceConversation()
                        else micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                },
                voice = voice,
                onStopSpeaking = VoiceSessionManager::stopSpeaking,
                onEndVoice = VoiceSessionManager::switchToText,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private val ChatBottomFrostHeight = 24.dp

/** 列表回到底部后多久停用磨砂采样（流式跟底时「离开底部 / 回到底部」会连续交替，不跟着切）。 */
private const val FROST_RELEASE_DELAY_MILLIS = 1_000L

private const val ChatBottomSentinelKey = "agent-chat-bottom-sentinel"
private const val ChatBottomReserveKey = "agent-chat-bottom-reserve"
private const val BOTTOM_FOLLOW_RESPONSE_SECONDS = 0.085f
private const val BOTTOM_FOLLOW_MAX_FRAME_SECONDS = 0.05f
private const val BOTTOM_FOLLOW_MAX_SPEED_DP_PER_SECOND = 720f
private const val BOTTOM_FOLLOW_MIN_STEP_PX = 0.5f
private const val BOTTOM_FOLLOW_SNAP_DISTANCE_PX = 0.75f

internal fun resolveKeepBottomAnchored(
    current: Boolean,
    isUserDragging: Boolean,
    isAtBottom: Boolean,
): Boolean = when {
    isUserDragging -> isAtBottom
    isAtBottom -> true
    else -> current
}

internal fun resolveBottomFollowEnabled(
    isStreaming: Boolean,
    keepBottomAnchored: Boolean,
    isUserDragging: Boolean,
    isBottomSettling: Boolean = false,
): Boolean = (isStreaming || isBottomSettling) && keepBottomAnchored && !isUserDragging

internal fun shouldRequestInitialBottom(
    isStreaming: Boolean,
    keepBottomAnchored: Boolean,
    isUserDragging: Boolean,
): Boolean = isStreaming && keepBottomAnchored && !isUserDragging

/**
 * 会话切换过渡（规范 9.3.1「视图切换」），由 [io.github.fartown.movo.ui.screens.chat.AgentChatScreen] 提供：
 * [contentAlpha] / [contentShiftY]（px）只作用于消息区与首页内容；[outgoing] 为离场的那一份，它的输入栏直接隐藏。
 * 两个值在绘制阶段读取。
 */
@androidx.compose.runtime.Stable
internal class ConversationSwitchFade(
    val outgoing: Boolean,
    val contentAlpha: () -> Float,
    val contentShiftY: () -> Float,
)

internal val LocalConversationSwitchFade = androidx.compose.runtime.staticCompositionLocalOf<ConversationSwitchFade?> { null }

/** 正在离场的消息（删除、重新生成确认后），由对话容器提供。 */
internal val LocalLeavingMessages = androidx.compose.runtime.compositionLocalOf<Collection<String>> { emptyList() }

/** 离场：内容淡出 120ms（`exit`），随后高度收起 `standard`。 */
@androidx.compose.runtime.Composable
private fun LeavingItem(
    leaving: Boolean,
    modifier: Modifier = Modifier,
    content: @androidx.compose.runtime.Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = !leaving,
        modifier = modifier,
        enter = androidx.compose.animation.EnterTransition.None,
        exit = fadeOut(io.github.fartown.movo.ui.theme.MovoMotion.fastExit()) +
            androidx.compose.animation.shrinkVertically(
                tween(
                    io.github.fartown.movo.ui.theme.MovoMotion.STANDARD,
                    delayMillis = io.github.fartown.movo.ui.theme.MovoMotion.FAST_EXIT,
                    easing = io.github.fartown.movo.ui.theme.MovoMotion.EasingStandard,
                ),
            ),
    ) {
        content()
    }
}

/**
 * 对话列表末尾的临时留白（像素）。回答开始、本轮执行卡自动收起时，卡片每帧收起多少就在末尾补多少：
 * 列表停在底部时内容总高不变，不会先退回露出上一轮、再随回答往下滚（真机 turntime case4）。
 * 回答往下长会把留白推出可见区，看不见的部分每次排版后收掉。
 */
@androidx.compose.runtime.Stable
internal class ChatBottomReserve {
    var px by androidx.compose.runtime.mutableIntStateOf(0)
}

internal val LocalChatBottomReserve = androidx.compose.runtime.staticCompositionLocalOf<ChatBottomReserve?> { null }

/** 直接滚动对话列表（像素，正数向后）：执行卡从末尾收起时逐帧补偿高度变化，见 [AgentWorkProcess]。 */
internal val LocalChatListScroll = androidx.compose.runtime.staticCompositionLocalOf<((Float) -> Float)?> { null }

/**
 * 编辑预览里被收起的消息（规范 9.3「列表增删」）：进入编辑时内容先淡出 120ms，随后高度收起 `standard`，
 * 列表跟随；取消编辑时高度直接恢复、内容整体淡入 `standard`——被收起的是被编辑消息之后的全部内容，下面没有别的
 * 行会被推动；逐项展开反而让「已思考」行和长回答各自长高、互相推挤（真机：标题晚出、正文被往下推）。
 * 首次组合就处于收起状态时不播放。提交编辑后这些消息才被真正删除，那时它们已经收起，删除本身不可见
 * （真正删除仍立即退出，见列表项注释）。
 */
@androidx.compose.runtime.Composable
private fun EditHiddenItem(
    hidden: Boolean,
    modifier: Modifier = Modifier,
    content: @androidx.compose.runtime.Composable () -> Unit,
) {
    val cap = io.github.fartown.movo.ui.components.movo.rememberVisibleHeightCap()
    AnimatedVisibility(
        visible = !hidden,
        modifier = modifier.trackVisibleHeightCap(cap),
        enter = fadeIn(io.github.fartown.movo.ui.theme.MovoMotion.standard()),
        exit = fadeOut(io.github.fartown.movo.ui.theme.MovoMotion.fastExit()) +
            androidx.compose.animation.shrinkVertically(
                // 被收起的可能是很长的回答：只按屏幕以内的部分过渡。
                io.github.fartown.movo.ui.components.movo.rememberViewportCappedStandard(
                    cap,
                    delayMillis = io.github.fartown.movo.ui.theme.MovoMotion.FAST_EXIT,
                ),
                shrinkTowards = androidx.compose.ui.Alignment.Top,
            ),
    ) {
        content()
    }
}

/** 「一打开就停在最新消息」最多等消息读出来的时长。 */
private const val INITIAL_LATEST_WAIT_MS = 1_500L

/** 用过 Agent 浏览器的步骤（类型化工具 browser_open/read/act；browser_use 是重构前的旧名，旧会话里还有）。 */
private val BROWSER_TOOL_NAMES = setOf("browser_open", "browser_read", "browser_act", "browser_use")
