package io.github.mangi.eta.ui.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import io.github.mangi.eta.ui.components.LocalQueuedConversationInput
import io.github.mangi.eta.ui.components.QueuedConversationInput
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.agent.voice.session.VoiceSurfaceTracker
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.model.AgentChatAction
import io.github.mangi.eta.ui.screens.chat.AgentChatScreen
import top.yukonga.miuix.kmp.window.WindowDialog

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
    val queued = agentState.queuedTextSubmission?.takeIf { it.conversationId == conversationId }
    CompositionLocalProvider(LocalQueuedConversationInput provides QueuedConversationInput(
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
                        agentState.regenerateMessage(action.id)
                    } else if (impact != null) {
                        regenerateTarget = ConversationMutationTarget(action.id, impact.laterTurnCount)
                    }
                }
                is AgentChatAction.SelectReplyCandidate -> agentState.selectReplyCandidate(action.id, action.index)
            }
        },
    )
    }
    deleteTarget?.let { target ->
        WindowDialog(
            show = true,
            title = stringResource(R.string.conversation_delete_message_title),
            summary = if (target.laterTurnCount == 0) stringResource(R.string.conversation_delete_message_body)
                else pluralStringResource(R.plurals.conversation_delete_later_turns, target.laterTurnCount, target.laterTurnCount),
            onDismissRequest = { deleteTarget = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_delete),
                destructive = true,
                onCancel = { deleteTarget = null },
                onConfirm = { agentState.deleteMessageTurn(target.messageId); deleteTarget = null },
            )
        }
    }
    regenerateTarget?.let { target ->
        WindowDialog(
            show = true,
            title = stringResource(R.string.conversation_regenerate_title),
            summary = if (target.laterTurnCount == 0) stringResource(R.string.conversation_regenerate_current_turn)
                else pluralStringResource(R.plurals.conversation_regenerate_later_turns, target.laterTurnCount, target.laterTurnCount),
            onDismissRequest = { regenerateTarget = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_regenerate),
                destructive = true,
                onCancel = { regenerateTarget = null },
                onConfirm = { agentState.regenerateMessage(target.messageId); regenerateTarget = null },
            )
        }
    }
}

private data class ConversationMutationTarget(val messageId: String, val laterTurnCount: Int)
