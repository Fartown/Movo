package io.github.fartown.movo.ui.screens.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import io.github.fartown.movo.ui.components.AgentTimelineEntry
import io.github.fartown.movo.ui.components.toTimelineEntries
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.ui.components.AgentChatBody
import io.github.fartown.movo.ui.components.AgentConversationDraftStore
import io.github.fartown.movo.ui.components.ConversationSwitchFade
import io.github.fartown.movo.ui.components.LocalConversationComposer
import io.github.fartown.movo.ui.components.LocalConversationSwitchFade
import io.github.fartown.movo.ui.model.AgentChatAction
import io.github.fartown.movo.ui.model.AgentChatUiState
import io.github.fartown.movo.ui.model.AgentModelPickerUiState
import io.github.fartown.movo.ui.share.ShareIntro
import io.github.fartown.movo.ui.theme.LocalReducedMotion
import io.github.fartown.movo.ui.theme.MovoMotion

/**
 * 独立对话页：与首页聊天主舞台共用同一套消息/输入组件，
 * 区别仅在于顶部返回由 Shell 统一提供。
 *
 * 切换会话 / 新建对话（规范 9.3.1「视图切换」）：每个会话仍是一份独立组合（滚动位置、飞行层等不串会话），
 * 换会话时旧的一份留 120ms 做离场：
 * - 切换会话：旧会话消息区淡出 120ms（`exit`），新会话消息区淡入 `standard`，不位移；
 * - 新建对话：当前对话淡出并上移 8，120ms + `exit`；首页内容按自己的「首页进场」出现，不再叠一层淡入。
 * 输入栏不参与：离场那一份的输入栏直接隐藏，新的一份原地出现。
 * 草稿就地变成会话（首页发出第一句）时组合键不变，不做过渡，Q1 / Q6 飞行照常。
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun AgentChatScreen(
    state: AgentChatUiState,
    modelPickerState: AgentModelPickerUiState,
    conversationKey: String?,
    onAction: (AgentChatAction) -> Unit,
    isDrawerOpen: Boolean = false,
    initiallyShowLatestMessage: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val compositionKeys = remember { io.github.fartown.movo.ui.components.ChatCompositionKeys() }
    val compositionKey = compositionKeys.keyFor(
        conversationKey,
        adoptedFromDraft = conversationKey != null &&
            conversationKey == io.github.fartown.movo.ui.components.AgentConversationDraftStore.shared.lastAssignedConversationId,
    )
    // 每份组合最后一次作为当前会话时的内容：离场的那一份继续显示旧会话，而不是跟着新状态变。
    val frames = remember { HashMap<String, ChatFrame>() }
    val previousKey = remember { arrayOf<String?>(null) }
    if (previousKey[0] != compositionKey) {
        frames.keys.retainAll(setOfNotNull(previousKey[0], compositionKey))
        previousKey[0] = compositionKey
    }
    frames[compositionKey] = ChatFrame(
        state = state,
        modelPickerState = modelPickerState,
        conversationKey = conversationKey,
        isDrawerOpen = isDrawerOpen,
        initiallyShowLatestMessage = initiallyShowLatestMessage || ConversationSwitchMotion.instant,
        // 分享提示只属于分享新开、还没发出消息的那条会话（规范 8.9.1）；App 与对话浮层都走这里。
        shareIntro = io.github.fartown.movo.ui.share.ShareIntake.intro
            ?.takeIf { conversationKey == null && state.messages.isEmpty() },
    )
    val toNewChat = conversationKey == null
    val reduced = LocalReducedMotion.current
    val shiftPx = with(LocalDensity.current) { 8.dp.toPx() }

    AnimatedContent(
        targetState = compositionKey,
        modifier = modifier,
        transitionSpec = {
            // 进场 / 离场都由下面的 transition.animateFloat 自己画（只作用于消息区），这里只让离场那一份留到动画结束。
            (EnterTransition.None togetherWith ExitTransition.KeepUntilTransitionsFinished).using(null)
        },
        label = "conversationSwitch",
    ) { key ->
        val frame = frames[key] ?: frames.getValue(compositionKey)
        val outgoing = key != compositionKey
        val progress = transition.animateFloat(
            transitionSpec = {
                when {
                    ConversationSwitchMotion.instant -> snap()
                    targetState == EnterExitState.PostExit -> tween(MovoMotion.FAST_EXIT, easing = MovoMotion.EasingExit)
                    // 新建对话：首页有自己的进场（逐项淡入上移），消息区不再叠一层淡入。
                    frame.conversationKey == null -> snap()
                    else -> MovoMotion.standard()
                }
            },
            label = "conversationSwitchProgress",
        ) { phase -> if (phase == EnterExitState.Visible) 1f else 0f }
        val switchFade = remember(outgoing, toNewChat, reduced, shiftPx) {
            ConversationSwitchFade(
                outgoing = outgoing,
                contentAlpha = { progress.value },
                // 新建对话：离场的当前对话同时上移 8（减少动画时只淡出，规范 9.8）。
                contentShiftY = { if (outgoing && toNewChat && !reduced) -shiftPx * (1f - progress.value) else 0f },
            )
        }
        ChatBody(frame = frame, switchFade = switchFade, onAction = onAction)
    }
}

/**
 * 对话浮层「展开到 App」接过来的会话（规范 9.5 Q4）：浮层已推满全屏、盖着 App，切过来时不能再播会话切换的淡入，
 * 也要和浮层一样停在最新消息处，否则切换那一刻内容会从会话开头淡入（真机：整体下移约 590px）。
 * [skipNext] 之后 1.5 秒内的会话切换直接到位。
 */
internal object ConversationSwitchMotion {
    private val instantUntil = androidx.compose.runtime.mutableLongStateOf(0L)

    fun skipNext() {
        instantUntil.longValue = android.os.SystemClock.uptimeMillis() + 1_500L
    }

    val instant: Boolean get() = android.os.SystemClock.uptimeMillis() < instantUntil.longValue
}

/** 一份会话组合要显示的内容（见 [AgentChatScreen]）。 */
private class ChatFrame(
    val state: AgentChatUiState,
    val modelPickerState: AgentModelPickerUiState,
    val conversationKey: String?,
    val isDrawerOpen: Boolean,
    val initiallyShowLatestMessage: Boolean,
    val shareIntro: ShareIntro?,
)

@Composable
private fun ChatBody(
    frame: ChatFrame,
    switchFade: ConversationSwitchFade,
    onAction: (AgentChatAction) -> Unit,
) {
    val state = frame.state
    val paused = state.isStreaming && state.isPaused
    // 暂停提示条「已完成 N 步」：本轮最后一张执行卡里已成功的步骤（只在暂停时算）。
    val completedSteps = remember(state.messages, paused) {
        if (!paused) 0 else {
            (state.messages.toTimelineEntries().lastOrNull { it is AgentTimelineEntry.WorkProcess } as? AgentTimelineEntry.WorkProcess)
                ?.messages
                ?.count { it is io.github.fartown.movo.ui.model.ToolActivityMessageUi && it.status == io.github.fartown.movo.ui.model.ToolActivityStatusUi.Success }
                ?: 0
        }
    }
    CompositionLocalProvider(
        LocalConversationComposer provides AgentConversationDraftStore.shared.get(frame.conversationKey, state.input),
        io.github.fartown.movo.ui.components.LocalRunControls provides io.github.fartown.movo.ui.components.RunControls(
            isPaused = paused,
            completedSteps = completedSteps,
            onResume = { onAction(AgentChatAction.ResumeRun) },
            onEndTask = { onAction(AgentChatAction.StopRun) },
        ),
        LocalConversationSwitchFade provides switchFade,
    ) {
        AgentChatBody(
            messages = state.messages,
            modelPickerState = frame.modelPickerState,
            isCompacting = state.isCompacting,
            input = state.input,
            isStreaming = state.isStreaming,
            reasoningEffort = state.reasoningEffort,
            availableReasoningEfforts = state.availableReasoningEfforts,
            pendingImages = state.pendingImages,
            pendingFileReferences = state.pendingFileReferences,
            messageEdit = state.messageEdit,
            characterName = state.roleplay?.characterName,
            onReasoningEffortChange = { onAction(AgentChatAction.ReasoningEffortChanged(it)) },
            onCompactContext = { onAction(AgentChatAction.CompactContext) },
            canCompactContext = state.canCompactContext,
            onModelSelected = { onAction(AgentChatAction.ModelSelected(it)) },
            onSubmit = { text -> onAction(AgentChatAction.SubmitMessage(text)) },
            onStop = { onAction(AgentChatAction.StopRun) },
            onAttachImage = { uri -> onAction(AgentChatAction.ImageAttached(uri)) },
            onRemoveImage = { id -> onAction(AgentChatAction.RemoveImage(id)) },
            onAttachFiles = { uris -> onAction(AgentChatAction.FilesAttached(uris)) },
            onAttachFolder = { uri -> onAction(AgentChatAction.FolderAttached(uri)) },
            onAttachFilePath = { path -> onAction(AgentChatAction.FilePathAttached(path)) },
            onRemoveFileReference = { id -> onAction(AgentChatAction.RemoveFileReference(id)) },
            onEditMessage = { id -> onAction(AgentChatAction.EditMessage(id)) },
            onCancelMessageEdit = { onAction(AgentChatAction.CancelMessageEdit) },
            onDeleteMessage = { id -> onAction(AgentChatAction.DeleteMessage(id)) },
            onRegenerateMessage = { id -> onAction(AgentChatAction.RegenerateMessage(id)) },
            onSelectReplyCandidate = { id, index -> onAction(AgentChatAction.SelectReplyCandidate(id, index)) },
            onSuggestionClick = { prompt ->
                onAction(AgentChatAction.SubmitMessage(prompt))
            },
            onRunTraceClick = { /* 对话页暂不做 Run trace 展开 */ },
            onOpenBrowser = { onAction(AgentChatAction.OpenBrowser) },
            modifier = Modifier,
            isDrawerOpen = frame.isDrawerOpen,
            initiallyShowLatestMessage = frame.initiallyShowLatestMessage,
            shareIntro = frame.shareIntro,
        )
    }
}
