package io.github.fartown.movo.ui.components

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
import io.github.fartown.movo.ui.app.LocalBlurEnabled
import io.github.fartown.movo.ui.model.AgentChatMessageUi
import io.github.fartown.movo.ui.model.AgentContextUsageUi
import io.github.fartown.movo.ui.model.AgentMessageUi
import io.github.fartown.movo.ui.model.AgentModelPickerUiState
import io.github.fartown.movo.ui.model.MessageEditUiState
import io.github.fartown.movo.ui.model.PendingFileReferenceUi
import io.github.fartown.movo.ui.model.PendingImageUi
import io.github.fartown.movo.ui.model.ThinkingMessageUi
import io.github.fartown.movo.ui.model.ToolActivityMessageUi
import io.github.fartown.movo.ui.model.ToolSummaryMessageUi
import io.github.fartown.movo.ui.model.SystemNoticeCode
import io.github.fartown.movo.ui.model.SystemNoticeMessageUi
import io.github.fartown.movo.ui.model.UserMessageUi
import io.github.fartown.movo.ui.model.latestContextUsage
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
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val isKeyboardVisible = imeBottomPx > 0
    val browserSnapshot by AgentBrowserSession.snapshots.collectAsState()
    val contextUsage = remember(messages, modelPickerState.selectedModel) {
        latestContextUsage(messages, modelPickerState.selectedModel)
    }

    val visibleMessages = remember(messages, messageEdit?.targetMessageId, messageEdit?.preserveFollowingMessages) {
        AgentConversationRevisionReducer.visibleMessagesForEdit(
            messages = messages,
            targetMessageId = messageEdit?.takeUnless { it.preserveFollowingMessages }?.targetMessageId,
        ).filterNot { message ->
            message is AgentMessageUi && message.content.isBlank()
        }
    }
    // Initial result presentation starts at the latest turn. Window resizing and onResume do
    // not restart this effect, so a reader's position remains untouched afterwards.
    LaunchedEffect(Unit) {
        if (initiallyShowLatestMessage) {
            scrollState.requestScrollToItem(visibleMessages.toTimelineEntries().size)
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
                    message.toolName == "browser_use" &&
                    message.id.startsWith("$runId-tool-") &&
                    message.id.endsWith("-$toolCallId")
            }?.id
        }
    }
    var sentFromKeyboard by remember { mutableStateOf(false) }
    var keepBottomAnchored by remember { mutableStateOf(true) }

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
        hasMessages = visibleMessages.isNotEmpty(),
        scrollState = scrollState,
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
        keepBottomAnchored = keepBottomAnchored,
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
        onSuggestionClick = onSuggestionClick,
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
    hasMessages: Boolean,
    scrollState: LazyListState,
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
    keepBottomAnchored: Boolean,
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
    val frostEnabled = hasMessages && LocalBlurEnabled.current && isRuntimeShaderSupported()
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
        Box(modifier = Modifier.fillMaxSize()) {
        if (hasMessages) {
            AgentConversationMessages(
                visibleMessages = visibleMessages,
                scrollState = scrollState,
                isStreaming = isStreaming,
                bottomInset = bottomPadding,
                keepBottomAnchored = keepBottomAnchored,
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
    ChatFlightOverlay(flight)
    }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun AgentConversationMessages(
    visibleMessages: List<AgentChatMessageUi>,
    scrollState: LazyListState,
    isStreaming: Boolean,
    bottomInset: Dp,
    keepBottomAnchored: Boolean,
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
    modifier: Modifier = Modifier,
) {
    val timelineEntries = remember(visibleMessages) { visibleMessages.toTimelineEntries() }
    val lastWorkKey = timelineEntries.lastOrNull { it is AgentTimelineEntry.WorkProcess }?.key
    val workOutcomes = remember(timelineEntries) { workOutcomes(timelineEntries) }
    // 复制按钮只出现在每轮对话的最终结果上，中间步骤的过渡文本不提供复制入口。
    // 流式进行中当前这一轮尚未收尾，此时的“最后一条正文”只是中间步骤，不标记。
    val finalResultMessageIds = remember(visibleMessages, isStreaming) {
        resolveFinalResultMessageIds(visibleMessages, isStreaming = isStreaming)
    }
    // 流式消息的渲染会话按 id 提升到列表层持有：item 滚出视口被 LazyColumn 销毁后，
    // 滑回时复用同一解析会话与打字机进度，避免整段内容重新解析并重放显现动画。
    val streamingMarkdownStates = remember { mutableStateMapOf<String, StreamingMarkdownState>() }
    val bottomItemIndex = timelineEntries.size
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
            scrollState.requestScrollToItem(bottomItemIndex)
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

    // 滚动层保持整屏，输入器作为后绘制浮层；输入器高度进入列表的
    // afterContentPadding，确保跟到底部时最后一行停在输入器上方。
    Box(modifier = modifier.clipToBounds()) {
        LazyColumn(
            state = scrollState,
            verticalArrangement = Arrangement.Top,
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical(),
            contentPadding = PaddingValues(
                top = 14.dp,
                bottom = bottomInset + 14.dp,
            ),
            overscrollEffect = null,
        ) {
            items(
                items = timelineEntries,
                key = { it.key },
            ) { entry ->
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
                        LeavingItem(leaving = message.id in LocalLeavingMessages.current, modifier = itemModifier) {
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
                                message.toolName == "browser_use" &&
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
                            onSelectReplyCandidate = onSelectReplyCandidate,
                        )
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
                        AgentWorkProcess(
                            id = entry.key,
                            messages = entry.messages,
                            // 本轮仍在进行：模型在两步之间思考时步骤都已完成，但执行卡不能当作完成收起。
                            runActive = isStreaming && entry.key == lastWorkKey,
                            outcome = workOutcomes[entry.key],
                            onOpenBrowser = onOpenBrowser,
                            currentBrowserMessageId = currentBrowserMessageId,
                            retainedStreamingStates = streamingMarkdownStates,
                            actionsEnabled = messageActionsEnabled,
                            onEditMessage = onEditMessage,
                            onDeleteMessage = onDeleteMessage,
                            modifier = itemModifier,
                        )
                    }
                }
            }
            if (isStreaming) {
                // 等待首个事件（最后一条还是用户消息）：16 小光球作为「正在处理」指示（Q6）。
                val lastEntry = timelineEntries.lastOrNull()
                if (lastEntry is AgentTimelineEntry.Message && lastEntry.message is UserMessageUi) {
                    item(key = "waiting-orb") {
                        // 只淡入不做列表退场：被移除的退场项会残留在原位（真机验收发现叠在回答文字上）。
                        Box(
                            Modifier
                                .animateItem(
                                    fadeInSpec = io.github.fartown.movo.ui.theme.MovoMotion.fast(),
                                    placementSpec = null,
                                    fadeOutSpec = null,
                                )
                                .padding(horizontal = 20.dp),
                        ) { WaitingOrb() }
                    }
                }
                item(key = "run-stall") { RunStallNotice(messageIds = visibleMessages.map { it.id }) }
            }
            item(key = ChatBottomSentinelKey) {
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp),
                )
            }
        }

        // 执行条 `Composer/TaskBar`（规范 8.2.1）：执行中的任务卡滚出屏幕时，在输入框上方 8 出现；
        // 点条身滚回任务卡，「查看」打开执行详情。只显示进度，不放停止（停止在正下方的主按钮 ■）。
        val runControls = LocalRunControls.current
        val workEntry = lastWorkKey?.let { key ->
            timelineEntries.lastOrNull { it.key == key } as? AgentTimelineEntry.WorkProcess
        }
        val workVisible by remember(lastWorkKey) {
            derivedStateOf { scrollState.layoutInfo.visibleItemsInfo.any { it.key == lastWorkKey } }
        }
        val showTaskBar = isStreaming && workEntry != null && !workVisible
        val openRunDetail = LocalOpenRunDetail.current
        AnimatedVisibility(
            visible = showTaskBar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 20.dp, end = 20.dp, bottom = bottomInset + 8.dp),
            enter = fadeIn(io.github.fartown.movo.ui.theme.MovoMotion.fast()) +
                androidx.compose.animation.slideInVertically(io.github.fartown.movo.ui.theme.MovoMotion.fast()) { it / 4 },
            exit = fadeOut(io.github.fartown.movo.ui.theme.MovoMotion.fastExit()),
        ) {
            workEntry?.let { entry ->
                TaskBar(
                    workKey = entry.key,
                    messages = entry.messages,
                    paused = runControls.isPaused,
                    onScrollToCard = {
                        val index = timelineEntries.indexOfFirst { it.key == entry.key }
                        if (index >= 0) coroutineScope.launch { scrollState.animateScrollToItem(index) }
                    },
                    onView = openRunDetail?.let { open -> { open(entry.key) } },
                )
            }
        }

        // 回到底部（9.3）：离开底部时出现，淡入 + 缩放 0.86 → 1（fast）；执行条出现时让到它上方。
        AnimatedVisibility(
            visible = !keepBottomAnchored && !isAtBottom,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomInset + if (showTaskBar) 56.dp else 12.dp),
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

/**
 * `Composer/TaskBar`：宽 372、高 40、圆角 20，bg/surface + 描边 + E1；16 小光球 +「正在执行·第 N 步」（Q3）+ 计时 +
 * `Button/Pill`「查看」；左内边距 12，「查看」距右 4，上下 4。
 */
@Composable
private fun TaskBar(
    workKey: String,
    messages: List<AgentChatMessageUi>,
    paused: Boolean,
    onScrollToCard: () -> Unit,
    onView: (() -> Unit)?,
) {
    val tools = messages.filterIsInstance<ToolActivityMessageUi>()
    val firstStart = tools.mapNotNull { it.startedAtMillis }.minOrNull()
    val now by produceState(System.currentTimeMillis(), paused) {
        while (!paused) {
            value = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000)
        }
    }
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Q4：执行卡不在屏幕上时，从执行条打开的详情页从执行条的位置长出来。
            .onGloballyPositioned { io.github.fartown.movo.ui.components.movo.RunDetailMorph.report(workKey, it.windowRect()) }
            .height(40.dp)
            .movoElevationCard(shape)
            .movoClickable(io.github.fartown.movo.ui.components.movo.PressKind.Card, shape = shape, onClick = onScrollToCard)
            .clip(shape)
            .background(io.github.fartown.movo.ui.theme.MovoColors.bgSurface)
            .border(io.github.fartown.movo.ui.theme.MovoSize.hairline, io.github.fartown.movo.ui.theme.MovoColors.borderHairline, shape)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (paused) {
            io.github.fartown.movo.ui.theme.MovoIcon(io.github.fartown.movo.ui.theme.MovoIcons.Pause, null, size = 16.dp, tint = io.github.fartown.movo.ui.theme.MovoColors.textSecondary)
        } else {
            io.github.fartown.movo.ui.components.movo.MovoOrb(size = 16.dp)
        }
        Spacer(Modifier.width(8.dp))
        io.github.fartown.movo.ui.components.movo.MovoShimmerText(
            text = when {
                paused -> stringResource(R.string.movo_work_paused_step, tools.size)
                tools.isNotEmpty() -> stringResource(R.string.movo_work_running_step, tools.size)
                else -> stringResource(R.string.movo_work_analyzing)
            },
            style = io.github.fartown.movo.ui.theme.MovoTypography.labelMedium,
            color = io.github.fartown.movo.ui.theme.MovoColors.textPrimary,
            active = !paused,
            modifier = Modifier.weight(1f),
        )
        if (firstStart != null) {
            val seconds = ((now - firstStart) / 1000).coerceAtLeast(0)
            Text(
                String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60),
                style = io.github.fartown.movo.ui.theme.MovoTypography.numericLabel,
                color = io.github.fartown.movo.ui.theme.MovoColors.textSecondary,
            )
            Spacer(Modifier.width(8.dp))
        }
        if (onView != null) {
            io.github.fartown.movo.ui.components.movo.MovoPillButton(label = stringResource(R.string.movo_work_view), onClick = onView)
        }
    }
}

private data class BottomFollowLayout(
    val enabled: Boolean,
    val bottomItemIndex: Int,
    val sentinelBottom: Int?,
    val viewportEnd: Int,
    val lastVisibleIndex: Int?,
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
): BottomFollowDecision {
    if (!enabled) return BottomFollowDecision()
    val overflow = sentinelBottom?.minus(viewportEnd)
    return when {
        overflow != null && overflow > 0 -> BottomFollowDecision(scrollByPx = overflow)
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
                is UserMessageUi -> if (!message.isRunSupplement()) {
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
        if (message is UserMessageUi && !message.isRunSupplement()) {
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

private const val ChatBottomSentinelKey = "agent-chat-bottom-sentinel"
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
