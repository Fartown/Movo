package io.github.fartown.movo.ui.app

import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import io.github.fartown.movo.ui.components.LocalQueuedConversationInput
import io.github.fartown.movo.ui.components.QueuedConversationInput
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.fartown.movo.agent.voice.session.VoiceSurfaceTracker
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.fartown.movo.R
import io.github.fartown.movo.ui.model.AgentChatAction
import io.github.fartown.movo.ui.screens.chat.AgentChatScreen

/** The complete conversation, including its actions and dialogs, in either window host. */
@Composable
internal fun AgentConversationContent(
    agentState: AgentAppState,
    onOpenBrowser: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {},
    isDrawerOpen: Boolean = false,
    initiallyShowLatestMessage: Boolean = false,
    isTopRoute: Boolean = true,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, isTopRoute) {
        val owner = Any()
        val observer = LifecycleEventObserver { _, _ ->
            VoiceSurfaceTracker.setChatVisible(owner, isTopRoute && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        lifecycle.addObserver(observer)
        VoiceSurfaceTracker.setChatVisible(owner, isTopRoute && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { lifecycle.removeObserver(observer); VoiceSurfaceTracker.setChatVisible(owner, false) }
    }
    val requestNotifications = rememberExecutionNotificationRequest()
    val conversationId = agentState.conversationPaneState.selectedConversationId
    var deleteTarget by remember(conversationId) { mutableStateOf<ConversationMutationTarget?>(null) }
    var regenerateTarget by remember(conversationId) { mutableStateOf<ConversationMutationTarget?>(null) }
    // 删除 / 重新生成先播离场（淡出 120ms + 收起 `standard`），播完再改动会话；减少动画时直接改动。
    val leavingMessages = remember(conversationId) { androidx.compose.runtime.mutableStateListOf<String>() }
    val leaveScope = androidx.compose.runtime.rememberCoroutineScope()
    val reducedMotion = io.github.fartown.movo.ui.theme.LocalReducedMotion.current
    fun leaveThen(messageId: String, mutate: () -> Unit) {
        if (reducedMotion || messageId in leavingMessages) {
            if (messageId !in leavingMessages) mutate()
            return
        }
        leavingMessages += messageId
        leaveScope.launch {
            kotlinx.coroutines.delay((io.github.fartown.movo.ui.theme.MovoMotion.FAST_EXIT + io.github.fartown.movo.ui.theme.MovoMotion.STANDARD).toLong())
            try {
                mutate()
            } finally {
                leavingMessages -= messageId
            }
        }
    }
    val queued = agentState.queuedTextSubmission?.takeIf { it.conversationId == conversationId }
    CompositionLocalProvider(
        io.github.fartown.movo.ui.components.LocalLeavingMessages provides leavingMessages,
        LocalQueuedConversationInput provides QueuedConversationInput(
        text = queued?.text?.ifBlank { "待发送附件" }, busy = agentState.voiceRuntimeBusy,
        edit = { agentState.withdrawQueuedText(edit = true) },
        discard = { agentState.withdrawQueuedText(edit = false) },
    )) {
    AgentChatScreen(
        state = agentState.homeState,
        modelPickerState = agentState.modelPickerState,
        conversationKey = conversationId,
        isDrawerOpen = isDrawerOpen,
        initiallyShowLatestMessage = initiallyShowLatestMessage,
        modifier = modifier,
        onAction = { action ->
            when (action) {
                AgentChatAction.NavigateBack -> onNavigateBack()
                is AgentChatAction.ReasoningEffortChanged -> agentState.updateReasoningEffort(action.effort)
                AgentChatAction.CompactContext -> agentState.compactCurrentContext()
                is AgentChatAction.ModelSelected -> agentState.selectModel(action.modelId)
                is AgentChatAction.SubmitMessage -> {
                    requestNotifications()
                    agentState.sendCurrentMessage(action.text)
                }
                AgentChatAction.StopRun -> agentState.stopCurrentRun()
                AgentChatAction.ResumeRun -> agentState.resumeCurrentRun()
                AgentChatAction.OpenBrowser -> onOpenBrowser()
                is AgentChatAction.ImageAttached -> agentState.attachImage(action.uri)
                is AgentChatAction.RemoveImage -> agentState.removePendingImage(action.id)
                is AgentChatAction.FilesAttached -> agentState.attachFiles(action.uris)
                is AgentChatAction.FolderAttached -> agentState.attachFolder(action.uri)
                is AgentChatAction.FilePathAttached -> agentState.attachFilePath(action.path)
                is AgentChatAction.RemoveFileReference -> agentState.removePendingFileReference(action.id)
                is AgentChatAction.EditMessage -> agentState.beginMessageEdit(action.id)
                AgentChatAction.CancelMessageEdit -> agentState.cancelMessageEdit()
                is AgentChatAction.DeleteMessage -> agentState.messageRevisionImpact(action.id)?.let {
                    deleteTarget = ConversationMutationTarget(action.id, it.laterTurnCount)
                }
                is AgentChatAction.RegenerateMessage -> {
                    val impact = agentState.messageRevisionImpact(action.id)
                    if (agentState.homeState.roleplay != null || impact?.laterTurnCount == 0) {
                        leaveThen(action.id) { agentState.regenerateMessage(action.id) }
                    } else if (impact != null) {
                        regenerateTarget = ConversationMutationTarget(action.id, impact.laterTurnCount)
                    }
                }
                is AgentChatAction.SelectReplyCandidate -> agentState.selectReplyCandidate(action.id, action.index)
            }
        },
    )
    }
    // 确认对话框（规范 8.11）：删除为危险确认（bg/inverse），重新生成为普通确认；后果写在说明里。
    val deleting = deleteTarget
    io.github.fartown.movo.ui.components.movo.MovoConfirmDialog(
        show = deleting != null,
        title = stringResource(R.string.conversation_delete_message_title),
        message = deleting?.let { target ->
            if (target.laterTurnCount == 0) stringResource(R.string.conversation_delete_message_body)
            else pluralStringResource(R.plurals.conversation_delete_later_turns, target.laterTurnCount, target.laterTurnCount)
        },
        confirmText = stringResource(R.string.action_delete),
        destructive = true,
        onConfirm = {
            deleting?.let { target -> leaveThen(target.messageId) { agentState.deleteMessageTurn(target.messageId) } }
            deleteTarget = null
        },
        onDismissRequest = { deleteTarget = null },
    )
    val regenerating = regenerateTarget
    io.github.fartown.movo.ui.components.movo.MovoConfirmDialog(
        show = regenerating != null,
        title = stringResource(R.string.conversation_regenerate_title),
        message = regenerating?.let { target ->
            if (target.laterTurnCount == 0) stringResource(R.string.conversation_regenerate_current_turn)
            else pluralStringResource(R.plurals.conversation_regenerate_later_turns, target.laterTurnCount, target.laterTurnCount)
        },
        confirmText = stringResource(R.string.action_regenerate),
        onConfirm = {
            regenerating?.let { target -> leaveThen(target.messageId) { agentState.regenerateMessage(target.messageId) } }
            regenerateTarget = null
        },
        onDismissRequest = { regenerateTarget = null },
    )
}

private data class ConversationMutationTarget(val messageId: String, val laterTurnCount: Int)
