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
import kotlin.math.roundToInt
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
    // 回答写完后先照「回答完成」收尾——显现完、操作行出来、列表跟到底——再把上面的过程收成摘要条（定稿 24）：一次只动一件事。
    // 写完立刻收的话，收起期间不跟底，回答最后几行和操作行还压在输入框后面，收完再补滚一大段（真机 10-07 P2：276px）。
    var isBottomSettling by remember { mutableStateOf(isStreaming) }
    val turnEndHold = remember { TurnEndHold(isStreaming) }
    val turnLive = turnEndHold.live(isStreaming, isBottomSettling, keepBottomAnchored)
    val timelineEntries = remember(visibleMessages, turnLive) { visibleMessages.toTimelineEntries(turnLive) }
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
    // 一轮结束后，回答上方的过程收成摘要条（定稿 24）。点开过的轮次按摘要条 key 记，同一会话里保持。
    val expandedTurns = androidx.compose.runtime.saveable.rememberSaveable(
        saver = androidx.compose.runtime.saveable.listSaver(
            save = { it.toList() },
            restore = { androidx.compose.runtime.mutableStateListOf<String>().apply { addAll(it) } },
        ),
    ) { androidx.compose.runtime.mutableStateListOf<String>() }
    // 用户点过摘要条的轮次：之后再收起以摘要条为锚（往上收），不再做结束那一下「以回答为锚」的收起。
    val userToggledTurns = remember { HashSet<String>() }
    // 这次界面里新出现的摘要条（同一会话里刚结束的一轮）：等上面的内容淡完再淡入。
    // 载入历史、切换会话时整批出现的不算，直接显示。
    val seenEntryKeys = remember { HashSet<Any>() }
    val freshSummaryKeys = remember(timelineEntries) {
        val keys = timelineEntries.map { it.key }
        val sameConversation = seenEntryKeys.isNotEmpty() && keys.any { it in seenEntryKeys }
        val fresh: Set<String> = if (!sameConversation) emptySet() else timelineEntries
            .filter { it is AgentTimelineEntry.TurnSummary && it.key !in seenEntryKeys }
            .mapTo(HashSet()) { it.key }
        seenEntryKeys.clear()
        seenEntryKeys.addAll(keys)
        fresh
    }
    // 仍在进行的这一轮里的执行卡：卡尾的起止时间与「日志」「收起」一行等这一轮结束再出现，执行中不在卡尾插一行把下面的正文推下去。
    val activeTurnWorkKeys = remember(timelineEntries, turnLive) {
        if (turnLive) currentTurnWorkKeys(timelineEntries) else emptySet()
    }
    val viewport = remember { ChatViewport() }
    val foldActivity = remember { ChatFoldActivity() }
    // 看着一轮结束：列表层统一驱动这一轮的收起（[TurnFoldMotion]），不跟着某一条的组合走。
    val turnFolds = remember { ChatTurnFolds() }
    val startedFolds = remember { HashSet<String>() }
    val foldScope = rememberCoroutineScope()
    val foldReducedMotion = io.github.fartown.movo.ui.theme.LocalReducedMotion.current
    androidx.compose.runtime.SideEffect {
        if (foldReducedMotion) return@SideEffect
        freshSummaryKeys.forEach { key ->
            val answerKey = (timelineEntries.firstOrNull { it.key == key } as? AgentTimelineEntry.TurnSummary)?.anchorKey ?: return@forEach
            // 钉住的那条下面也要收起的条目（停止时最后一段卡在留下的那句话下面）。
            val anchorIndex = timelineEntries.indexOfFirst { it.key == answerKey }
            val belowKeys = timelineEntries.drop(anchorIndex + 1).filter { it.foldGroup == key }.mapTo(HashSet<Any>()) { it.key }
            val motion = turnFolds.begin(key, startedFolds) ?: return@forEach
            foldScope.launch {
                try {
                    foldActivity.during { motion.play(scrollState, answerKey, belowKeys, bottomReserve) }
                } finally {
                    turnFolds.end(key)
                }
            }
        }
    }
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
    LaunchedEffect(isStreaming, isTailRendering, keepBottomAnchored, isUserDragging) {
        if (!keepBottomAnchored || isUserDragging) {
            isBottomSettling = false
        } else if (isStreaming || isTailRendering) {
            isBottomSettling = true
        } else if (isBottomSettling) {
            // 显现完成后还会切换稳定排版并插入操作行，等其完成测量再收口跟底。
            withFrameNanos { }
            withFrameNanos { }
            // 一轮的收起等着它：万一到不了底（不该发生），最多等这么久，不能让过程一直不收。
            kotlinx.coroutines.withTimeoutOrNull(BOTTOM_SETTLE_MAX_MS) {
                snapshotFlow { !scrollState.canScrollForward }.first { it }
            }
            isBottomSettling = false
        }
    }

    // 一轮结束、过程收成摘要条的那 240ms 里不跟底：位置由收起的逐帧滚动补偿负责，跟底再滚会把回答带走（本地逐帧：多滚 106px）。
    val shouldFollowBottom by rememberUpdatedState(
        resolveBottomFollowEnabled(
            isStreaming = isStreaming || keepLatestOnResize,
            keepBottomAnchored = keepBottomAnchored,
            isUserDragging = isUserDragging,
            isBottomSettling = isBottomSettling,
        ) && foldActivity.active == 0
    )
    // 流式输出与收尾期间：内容怎么变都跟底。
    val followsAnyGrowth by rememberUpdatedState(
        resolveBottomFollowEnabled(
            isStreaming = isStreaming,
            keepBottomAnchored = keepBottomAnchored,
            isUserDragging = isUserDragging,
            isBottomSettling = isBottomSettling,
        )
    )
    val currentBottomItemIndex by rememberUpdatedState(bottomItemIndex)
    val bottomFollowDecisions = remember(scrollState) {
        Channel<BottomFollowDecision>(Channel.CONFLATED)
    }
    // 正在往底部滑（还没到）：期间列表因为这次滑动本身发生的变化，不能当成「不该跟」把剩下的距离取消。
    val followGliding = remember(scrollState) { booleanArrayOf(false) }

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
        var previousFollowLayout: BottomFollowLayout? = null
        snapshotFlow {
            val layoutInfo = scrollState.layoutInfo
            val sentinel = layoutInfo.visibleItemsInfo.firstOrNull { item ->
                item.key == ChatBottomSentinelKey
            }
            val tail = layoutInfo.visibleItemsInfo.firstOrNull { it.index == currentBottomItemIndex - 1 }
            BottomFollowLayout(
                enabled = shouldFollowBottom,
                followsAnyGrowth = followsAnyGrowth,
                bottomItemIndex = currentBottomItemIndex,
                sentinelBottom = sentinel?.let { it.offset + it.size },
                // 输入器高度属于滚动内容的 bottom inset，而不是滚动容器高度。
                // 跟底目标应是 afterContentPadding 之前的正文边界。
                viewportEnd = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding,
                lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index,
                lastVisibleBottom = layoutInfo.visibleItemsInfo.lastOrNull()?.let { it.offset + it.size },
                tail = tail?.let { ChatTailLayout(it.key, it.offset, it.offset + it.size) },
            )
        }
            .distinctUntilChanged()
            .collect { layout ->
                // 只为「保持最新」（对话浮层、打开已有对话）跟底时，不是什么变化都跟：用户点开上面的执行卡、思考，
                // 回答被整体往下推，跟底会把刚展开的内容滚到顶栏后面（真机：展开执行卡跳一下、卡片上半截被裁掉）。
                // 正在滑的这一段也照常更新目标：「保持最新」时末尾新加一条（如推荐问题）开始滑，下一帧最后一条只是被滑上去
                // （没变高），不算「该跟」的变化，原来会把剩下的距离清零——推荐问题一帧跳 36px 就停、下半截压在输入框后面
                // （真机 10-07 第 2 轮 I2）。
                // 跟底刚从暂停里恢复（一轮结束合并的那 240ms 不跟底）：重新看一次。暂停期间末尾新加的条目（如推荐问题）
                // 那次跟底已经被丢掉，不补的话它就一直压在输入框后面。
                val resumed = layout.enabled && previousFollowLayout?.enabled == false
                val enabled = layout.enabled && (
                    layout.followsAnyGrowth || followGliding[0] || resumed ||
                        followsLatestOnLayoutChange(previousFollowLayout?.viewportEnd, layout.viewportEnd, previousFollowLayout?.tail, layout.tail)
                    )
                previousFollowLayout = layout
                val decision = resolveBottomFollowDecision(
                    enabled = enabled,
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
            followGliding[0] = remainingDistancePx > 0f
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
                followGliding[0] = false
                continue
            }

            requestIndex?.let { targetIndex ->
                // 底部已不在屏幕上才会走到这里：先清掉末尾留白（在屏幕外），直接到底时不滚进留白。
                bottomReserve.px = 0
                scrollState.requestScrollToItem(targetIndex)
                requestIndex = null
                remainingDistancePx = 0f
                followGliding[0] = false
                return@let
            }
            if (remainingDistancePx <= 0f) continue

            val frameNanos = withFrameNanos { it }
            // 起步那一帧只记时间，从下一帧起按实际帧间隔走：按 1/60 秒估的话，120Hz 屏上第一步是后面每步的两倍
            // （真机 10-07 第 3 轮：推荐问题滑上来时先跳 40px）。
            if (previousFrameNanos == 0L) {
                previousFrameNanos = frameNanos
                continue
            }
            val elapsedSeconds = ((frameNanos - previousFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
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
            followGliding[0] = remainingDistancePx > 0f
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
            LocalChatViewport provides viewport,
            LocalChatTurnFolds provides turnFolds,
        ) {
        LazyColumn(
            state = scrollState,
            verticalArrangement = Arrangement.Top,
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned {
                    val bounds = it.boundsInWindow()
                    listBottomPx[0] = bounds.bottom.toInt()
                    viewport.top = bounds.top
                    viewport.bottom = bounds.bottom
                }
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
                // 属于已结束一轮的过程（定稿 24）：跟着本轮摘要条收起 / 展开。
                val folded = entry.foldGroup != null && entry.foldGroup !in expandedTurns
                val foldAnchoredBelow = entry.foldGroup != null && entry.foldGroup !in userToggledTurns
                when (entry) {
                    is AgentTimelineEntry.Message -> {
                        val message = entry.message
                        // 删除 / 重新生成：内容先淡出 120ms，随后高度收起 `standard`，下方各行跟随上移（规范 9.3「列表增删」、9.4），
                        // 播完才真正改动列表，避免旧消息的退场与同位置的新流式消息重叠。
                        EditHiddenItem(hidden = message.id in editHiddenIds, modifier = itemModifier) {
                          FoldableEntry(entryKey = entry.key, folded = folded, anchoredBelow = foldAnchoredBelow, foldGroup = entry.foldGroup) {
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
                          FoldableEntry(entryKey = entry.key, folded = folded, anchoredBelow = foldAnchoredBelow, foldGroup = entry.foldGroup) {
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
                                turnActive = entry.key in activeTurnWorkKeys,
                                inFoldedTurn = folded,
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

                    is AgentTimelineEntry.TurnSummary -> {
                        TurnSummaryBar(
                            summary = entry,
                            expanded = entry.key in expandedTurns,
                            appearAfterFold = entry.key in freshSummaryKeys,
                            onToggle = {
                                userToggledTurns += entry.key
                                if (entry.key in expandedTurns) expandedTurns.remove(entry.key) else expandedTurns.add(entry.key)
                            },
                            modifier = itemModifier,
                        )
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
    /** 流式输出与收尾期间：内容怎么变都跟底；否则只按 [followsLatestOnLayoutChange] 判断。 */
    val followsAnyGrowth: Boolean,
    val bottomItemIndex: Int,
    val sentinelBottom: Int?,
    val viewportEnd: Int,
    val lastVisibleIndex: Int?,
    val lastVisibleBottom: Int?,
    val tail: ChatTailLayout?,
)

/** 列表最后一条内容（底部哨兵前一项）在视口里的位置。 */
internal data class ChatTailLayout(val key: Any, val top: Int, val bottom: Int)

/**
 * 「保持最新」时哪些布局变化要跟底：视口变了（键盘、浮层拉高拉低），最后一条自己往下长（顶边不动、底边变长，
 * 如回答里的图片加载出来），或末尾新加了一条。上面的条目展开把最后一条整体往下推（顶边也动了）不算：
 * 那是用户在看历史内容，跟底会把刚展开的部分滚走。
 */
internal fun followsLatestOnLayoutChange(
    previousViewportEnd: Int?,
    viewportEnd: Int,
    previousTail: ChatTailLayout?,
    tail: ChatTailLayout?,
): Boolean {
    if (previousViewportEnd == null) return false
    if (previousViewportEnd != viewportEnd) return true
    if (tail == null || previousTail == null) return false
    if (tail.key != previousTail.key) return true
    return tail.top == previousTail.top && tail.bottom > previousTail.bottom
}

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

/**
 * 一轮还算不算在进行（决定收不收成摘要条，定稿 24）：运行中算；运行结束后，等用户在底部（跟着最新内容）、照「回答完成」
 * 收尾（跟底、显现）完才收起——看着它结束才合并。用户往上滑开时结束，先不合并：这时回答不在眼前，收起只会把正在看的
 * 内容推来推去（真机 10-07 第 3c 轮 N5：先往下、再往上），等回到底部再合并。
 * 只等这一次：收起之后，除非开始新一轮运行，什么都不让它再展开（第 3 轮 N1：收起后又闪回展开、再收一次）。
 * 在组合里按输入推导，同样的输入结果相同。
 */
internal class TurnEndHold(streaming: Boolean) {
    private var holding = streaming

    fun live(isStreaming: Boolean, isSettling: Boolean, isAnchored: Boolean): Boolean {
        if (isStreaming) holding = true else if (isAnchored && !isSettling) holding = false
        return isStreaming || holding
    }
}

/** 回答写完后跟底收尾最多等多久（之后本轮过程收成摘要条）。 */
private const val BOTTOM_SETTLE_MAX_MS = 1_500L

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
            is AgentTimelineEntry.TurnSummary -> Unit
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
    is AgentTimelineEntry.TurnSummary -> AgentTimelineEntry.TurnSummary::class
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
            is AgentTimelineEntry.TurnSummary -> Unit
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
            is AgentTimelineEntry.TurnSummary -> Unit
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
            is AgentTimelineEntry.TurnSummary -> Unit
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
            is AgentTimelineEntry.TurnSummary -> Unit
        }
    }
    return result
}

internal sealed interface AgentTimelineEntry {
    val key: String

    /** 属于一轮已结束的过程（最终回答上方的卡片、中间说的话、补充、提示）：本轮摘要条的 key，跟着它收起 / 展开（定稿 24）。 */
    val foldGroup: String? get() = null

    data class Message(
        val message: AgentChatMessageUi,
        override val foldGroup: String? = null,
    ) : AgentTimelineEntry {
        override val key: String = message.id
    }

    data class WorkProcess(
        override val key: String,
        val messages: List<AgentChatMessageUi>,
        override val foldGroup: String? = null,
    ) : AgentTimelineEntry

    /**
     * 一轮结束后，最终回答上方的过程收成的一行（定稿 24），排在这一轮开头那条消息后面。
     * [toolCount] 为 0 时写「已思考 N 秒」，否则「已完成 N 个步骤 · 用时」。
     */
    data class TurnSummary(
        override val key: String,
        val toolCount: Int,
        /** 收起的过程里有思考：没有工具时摘要条写「已思考（N 秒）」，与收起前卡头一致。 */
        val hasThinking: Boolean,
        val thinkingSeconds: Int,
        val span: WorkTurnSpan?,
        val toolStartedAt: Long?,
        val toolFinishedAt: Long?,
        /** 这一轮怎么结束的：停止 / 没完成；正常完成为 null。 */
        val ending: WorkOutcome.Kind? = null,
        /** 收起时钉住不动的那一条（留下的那段正文，或收起部分后面的第一条）。 */
        val anchorKey: Any? = null,
    ) : AgentTimelineEntry
}

/**
 * 时间线（定稿 24）：思考与工具按出现顺序连成一段执行卡，模型说的话各自一条、一律在卡外；执行中只往后追加，
 * 已有条目的 key 与所属都不变。一轮结束、最后一条是正文时，它就是最终回答，上方的过程跟着本轮摘要条收起
 * （仍在时间线上，只标 [AgentTimelineEntry.foldGroup]）。[isStreaming] 时最后一轮还没结束，不收。
 */
internal fun List<AgentChatMessageUi>.toTimelineEntries(isStreaming: Boolean = false): List<AgentTimelineEntry> =
    foldFinishedTurns(groupTimelineEntries(), isStreaming)

private fun List<AgentChatMessageUi>.groupTimelineEntries(): List<AgentTimelineEntry> = buildList {
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

    arrangeTurnsForTimeline(this@groupTimelineEntries).forEach { message ->
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

private fun AgentTimelineEntry.isTurnStartEntry(): Boolean =
    this is AgentTimelineEntry.Message && message.isTurnStart()

/**
 * 每轮结束后把最终回答上方的过程标成收起，并在这一轮开头那条消息后面放摘要条。
 * 最终回答 = 这一轮最后一条正文，它后面只能跟推荐追问。停止、失败、没有正文收尾时不收。
 * 过程里至少要有一张执行卡或一段话，才值得收。
 */
internal fun foldFinishedTurns(entries: List<AgentTimelineEntry>, isStreaming: Boolean): List<AgentTimelineEntry> {
    val starts = entries.indices.filter { entries[it].isTurnStartEntry() }
    if (starts.isEmpty()) return entries
    val result = ArrayList<AgentTimelineEntry>(entries.size + starts.size)
    result.addAll(entries.subList(0, starts.first()))
    starts.forEachIndexed { n, startIndex ->
        val endIndex = if (n + 1 < starts.size) starts[n + 1] else entries.size
        val start = entries[startIndex]
        val body = entries.subList(startIndex + 1, endIndex)
        result += start
        if (isStreaming && n == starts.lastIndex) {
            result.addAll(body)
            return@forEachIndexed
        }
        // 结束了（完成、停止、失败都算）：只留最后一段正文，其余过程（前后各段卡、中间说的话、补充、重试提示）都收进摘要条。
        // 结束提示（已停止、未完成的原因与「重试」）和推荐问题照常留在外面。
        val keptIndex = body.indexOfLast { entry ->
            val message = (entry as? AgentTimelineEntry.Message)?.message
            message is AgentMessageUi && message.content.isNotBlank()
        }
        val folds = body.indices.filter { it != keptIndex && body[it].foldsAtTurnEnd() }
        val worthFolding = folds.any { body[it] is AgentTimelineEntry.WorkProcess || (body[it] as? AgentTimelineEntry.Message)?.message is AgentMessageUi }
        if (!worthFolding) {
            result.addAll(body)
            return@forEachIndexed
        }
        val process = folds.map { body[it] }
        val summaryKey = "summary-${start.key}"
        val tools = process.flatMap { (it as? AgentTimelineEntry.WorkProcess)?.messages.orEmpty() }.filterIsInstance<ToolActivityMessageUi>()
        val thinking = process.flatMap { (it as? AgentTimelineEntry.WorkProcess)?.messages.orEmpty() }.filterIsInstance<ThinkingMessageUi>()
        val thinkingSeconds = thinking.sumOf { it.elapsedSeconds ?: 0 }
        val startMessage = (start as AgentTimelineEntry.Message).message
        val span = when (startMessage) {
            is UserMessageUi -> startMessage.runStartedAtMillis?.let { WorkTurnSpan(it, startMessage.runFinishedAtMillis) }
            is MonitorEventMessageUi -> startMessage.runStartedAtMillis?.let { WorkTurnSpan(it, startMessage.runFinishedAtMillis) }
            else -> null
        }
        val ending = body.firstNotNullOfOrNull { entry ->
            when (((entry as? AgentTimelineEntry.Message)?.message as? SystemNoticeMessageUi)?.code) {
                SystemNoticeCode.Stopped -> WorkOutcome.Kind.Stopped
                SystemNoticeCode.RuntimeFailed, SystemNoticeCode.Interrupted -> WorkOutcome.Kind.Unfinished
                else -> null
            }
        }
        // 收起时钉住不动的那一条：留下的那段正文；没有正文时是收起部分后面的第一条（如「已停止」）。
        val anchorKey = body.getOrNull(keptIndex)?.key
            ?: body.drop(folds.last() + 1).firstOrNull { (it as? AgentTimelineEntry.Message)?.message !is SuggestionChipsMessageUi }?.key
        result += AgentTimelineEntry.TurnSummary(
            key = summaryKey,
            toolCount = tools.size,
            hasThinking = thinking.isNotEmpty(),
            thinkingSeconds = thinkingSeconds,
            span = span,
            toolStartedAt = tools.mapNotNull { it.startedAtMillis }.minOrNull(),
            toolFinishedAt = tools.mapNotNull { it.finishedAtMillis }.maxOrNull(),
            ending = ending,
            anchorKey = anchorKey,
        )
        body.forEachIndexed { index, entry ->
            result += if (index in folds) {
                when (entry) {
                    is AgentTimelineEntry.Message -> entry.copy(foldGroup = summaryKey)
                    is AgentTimelineEntry.WorkProcess -> entry.copy(foldGroup = summaryKey)
                    is AgentTimelineEntry.TurnSummary -> entry
                }
            } else {
                entry
            }
        }
    }
    return result
}

/** 一轮结束时跟着摘要条收起的条目：结束提示（停止、失败的原因与「重试」）和推荐问题不收。 */
private fun AgentTimelineEntry.foldsAtTurnEnd(): Boolean = when (this) {
    is AgentTimelineEntry.WorkProcess -> true
    is AgentTimelineEntry.TurnSummary -> false
    is AgentTimelineEntry.Message -> when (val message = message) {
        is SuggestionChipsMessageUi -> false
        is SystemNoticeMessageUi -> message.code !in TURN_ENDING_NOTICES
        else -> true
    }
}

private val TURN_ENDING_NOTICES = setOf(
    SystemNoticeCode.Stopped,
    SystemNoticeCode.RuntimeFailed,
    SystemNoticeCode.Interrupted,
    SystemNoticeCode.EmptyResult,
)


/** 最后一轮（最后一条用户消息或唤醒事件之后）里的执行卡。 */
internal fun currentTurnWorkKeys(entries: List<AgentTimelineEntry>): Set<String> {
    val start = entries.indexOfLast { it.isTurnStartEntry() }
    return entries.drop(start + 1).filterIsInstance<AgentTimelineEntry.WorkProcess>().mapTo(HashSet()) { it.key }
}

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
                turn.forEach { if (it is AgentMessageUi && !it.narration && it.id.startsWith(failedRoundText)) dropped += it.id }
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

/** 对话列表在窗口里的上下沿（排版后更新，不是状态）：判断一条是否在屏幕上。 */
internal class ChatViewport {
    var top = Float.NaN
    var bottom = Float.NaN
}

internal val LocalChatViewport = androidx.compose.runtime.staticCompositionLocalOf<ChatViewport?> { null }

/** 正在播「一轮结束收起」的个数（定稿 24）：大于 0 时列表不跟底，回答由 [TurnFoldMotion] 钉住。 */
@androidx.compose.runtime.Stable
internal class ChatFoldActivity {
    var active by androidx.compose.runtime.mutableIntStateOf(0)

    suspend fun <T> during(block: suspend () -> T): T {
        active++
        try {
            return block()
        } finally {
            active--
        }
    }
}

/**
 * 跟着本轮摘要条收起的过程条目（定稿 24）。首次组合就是收起的（载入历史、切会话回来）直接不显示，不播动画。
 * - 看着这一轮结束（[anchoredBelow]）：由本轮的 [TurnFoldMotion] 统一驱动——以底边为锚收起，高度 `standard` 收到 0，
 *   上沿往下走，收掉的部分裁掉；透明度跟着高度走，收掉六成时淡完（摘要条从长到四成开始显现，两边交叠一小段）。
 *   不按时间单独淡出：`fastExit` 前慢后快，高度却前快后慢，卡片收成一条时还没淡完，露出几帧没有字的白条（真机 10-07 P5）。
 *   回答不动由 [TurnFoldMotion] 负责（每帧把回答钉在原位置），这里只管自己的高度和透明度。
 * - 点摘要条收起 / 展开：以摘要条为锚，从上往下收 / 长，不补偿。
 * - 收起时不在屏幕上的条目一次到位：列表保持看得见的内容不动。
 */
@Composable
private fun FoldableEntry(entryKey: Any, folded: Boolean, anchoredBelow: Boolean, foldGroup: String?, content: @Composable () -> Unit) {
    val reduced = io.github.fartown.movo.ui.theme.LocalReducedMotion.current
    val shown = remember { androidx.compose.animation.core.Animatable(if (folded) 0f else 1f) }
    val alpha = remember { androidx.compose.animation.core.Animatable(if (folded) 0f else 1f) }
    val viewport = LocalChatViewport.current
    val turnFolds = LocalChatTurnFolds.current
    val bounds = remember { floatArrayOf(Float.NaN, Float.NaN) }
    val fullHeight = remember { intArrayOf(0) }
    // 正在跟着这一轮的结束收起：高度、透明度按它的进度算。
    var driven by remember { mutableStateOf<TurnFoldMotion?>(null) }
    LaunchedEffect(folded) {
        driven?.let { motion ->
            // 收到一半被点开：从当前高度接着长回来。
            shown.snapTo(1f - motion.progress.value)
            alpha.snapTo(foldContentAlpha(shown.value))
            driven = null
        }
        val target = if (folded) 0f else 1f
        if (shown.value == target && !shown.isRunning) return@LaunchedEffect
        if (reduced) {
            shown.snapTo(target)
            alpha.snapTo(target)
            return@LaunchedEffect
        }
        if (folded) {
            val onScreen = viewport != null && !bounds[0].isNaN() && bounds[1] > viewport.top && bounds[0] < viewport.bottom
            val motion = foldGroup?.takeIf { anchoredBelow }?.let { turnFolds?.playing(it) }
            if (!onScreen || anchoredBelow && motion == null) {
                shown.snapTo(0f)
                alpha.snapTo(0f)
                return@LaunchedEffect
            }
            if (motion != null) {
                motion.heights[entryKey] = fullHeight[0]
                driven = motion
                snapshotFlow { motion.progress.value >= 1f }.first { it }
                shown.snapTo(0f)
                alpha.snapTo(0f)
                driven = null
                return@LaunchedEffect
            }
            kotlinx.coroutines.coroutineScope {
                launch { alpha.animateTo(0f, io.github.fartown.movo.ui.theme.MovoMotion.fastExit()) }
                shown.animateTo(0f, io.github.fartown.movo.ui.theme.MovoMotion.standard())
            }
        } else {
            kotlinx.coroutines.coroutineScope {
                launch { alpha.animateTo(1f, io.github.fartown.movo.ui.theme.MovoMotion.fast()) }
                shown.animateTo(1f, io.github.fartown.movo.ui.theme.MovoMotion.standard())
            }
        }
    }
    val hidden by remember { derivedStateOf { driven == null && shown.value == 0f } }
    if (folded && hidden) return
    Box(
        Modifier
            .onGloballyPositioned { coordinates ->
                val rect = coordinates.boundsInWindow()
                bounds[0] = rect.top
                bounds[1] = rect.bottom
            }
            .graphicsLayer {
                val motion = driven
                clip = motion != null || shown.value < 1f
                this.alpha = if (motion != null) foldContentAlpha(1f - motion.progress.value) else alpha.value
            }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                fullHeight[0] = placeable.height
                val motion = driven
                if (motion != null) {
                    motion.heights[entryKey] = placeable.height
                    val height = foldingHeight(placeable.height, motion.progress.value)
                    // 贴底：上沿往下走，收掉的是上面那部分。
                    layout(placeable.width, height) { placeable.place(0, height - placeable.height) }
                } else {
                    val height = (placeable.height * shown.value).roundToInt()
                    layout(placeable.width, height) { placeable.place(0, 0) }
                }
            },
    ) { content() }
}

/** 结束收起进度为 [progress] 时过程条目的排版高度（整数，补偿按同一取整算）。 */
internal fun foldingHeight(full: Int, progress: Float): Int = (full * (1f - progress)).roundToInt()

/** 结束收起进度为 [progress] 时摘要条的排版高度。 */
internal fun summaryGrowHeight(full: Int, progress: Float): Int = (full * progress).roundToInt()

/**
 * 一轮结束时「过程收成摘要条」的那一下（定稿 24）：摘要条长高、上面各条收起都按同一个进度走（`standard`），
 * 由列表层一个协程驱动——摘要条可能已滚出屏幕、不在组合里，不能靠它驱动。
 * 每帧请求列表把最终回答放在上一帧的位置（`requestScrollToItem(回答, −偏移)`），列表从回答往上按新的高度排：
 * 上面还有内容可以往下补（对话长、已经滚动过）时回答一像素不动；补到第一条、到顶了，列表照常从顶部排，回答跟着
 * 收起往上走，不在上方留空白（定稿 24-11 修订：内容短时用户消息留在顶部，回答跟着上移）。
 * 不用逐帧滚动补偿：补偿时列表按旧高度重排、摘要条却在之后才长高，以上面的条目为锚多出来的高度会把回答往下推
 * （本地逐帧：+41px）。
 */
@androidx.compose.runtime.Stable
internal class TurnFoldMotion {
    val progress = androidx.compose.animation.core.Animatable(0f)
    /** 跟着这一轮收起的条目的完整高度（按条目 key，排版时写入）。 */
    val heights = HashMap<Any, Int>()

    /**
     * [belowKeys]：钉住的那条下面也要收起的条目（停止时最后一段卡在留下的那句话下面）。列表停在底部时它们变矮，
     * 列表只能整体往下补，钉住的那句话会被带下去（真机 10-07 第 4 轮：先往下掉 130～264px 再往上收）；
     * 每帧把它们收掉的高度补到列表末尾的留白（[ChatBottomReserve]），那句话不动，下面的「已停止」往上靠。
     */
    suspend fun play(list: LazyListState, answerKey: Any, belowKeys: Set<Any>, bottomReserve: ChatBottomReserve) {
        var previous = 0f
        progress.animateTo(1f, io.github.fartown.movo.ui.theme.MovoMotion.standard()) {
            val now = value
            var shrunkBelow = 0
            belowKeys.forEach { key -> heights[key]?.let { full -> shrunkBelow += foldingHeight(full, previous) - foldingHeight(full, now) } }
            previous = now
            if (shrunkBelow > 0) bottomReserve.px += shrunkBelow
            val answer = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == answerKey }
            if (answer != null && !list.isScrollInProgress) list.requestScrollToItem(answer.index, -answer.offset)
        }
    }
}

/** 本次界面里正在播的「一轮结束收起」，按摘要条 key 记。 */
internal class ChatTurnFolds {
    private val motions = HashMap<String, TurnFoldMotion>()

    fun playing(key: String): TurnFoldMotion? = motions[key]

    /** 第一次看到这一轮结束时登记；已登记过（正在播或播过）返回 null。 */
    fun begin(key: String, started: MutableSet<String>): TurnFoldMotion? {
        if (!started.add(key)) return null
        return TurnFoldMotion().also { motions[key] = it }
    }

    fun end(key: String) {
        motions.remove(key)
    }
}

internal val LocalChatTurnFolds = androidx.compose.runtime.staticCompositionLocalOf<ChatTurnFolds?> { null }

/** 结束收起时过程条目的透明度：随高度走，收掉六成时淡完。 */
internal fun foldContentAlpha(shown: Float): Float = ((shown - 0.4f) / 0.6f).coerceIn(0f, 1f)

/**
 * 结束收起时摘要条的透明度：长到四成开始显现，和上面内容淡完交叠一小段——正好在一半处交接的话那一帧两边都透明，
 * 中间空一下（本地逐帧）。两者位置不重合，不会叠字。
 */
internal fun summaryAppearAlpha(grown: Float): Float = ((grown - 0.4f) / 0.6f).coerceIn(0f, 1f)

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
