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
import io.github.fartown.movo.agent.model.InteractionMode
import io.github.fartown.movo.agent.overlay.toolDisplayName
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
    /**
     * 重新生成 / 重试 / 换模式重做（定稿 26-1、26-2）：这一轮动过手机（有不是只读的步骤）时先弹危险确认，写清做过什么；
     * 换成 Agent 重做是第一次让它去做、换成对话不碰手机，都不弹这个。之后还有轮次时照旧问一次（会删掉后面的轮次）。
     */
    fun requestRegenerate(messageId: String, mode: InteractionMode?) {
        val impact = agentState.messageRevisionImpact(messageId)
        if (agentState.homeState.roleplay != null) {
            leaveThen(messageId) { agentState.regenerateMessage(messageId) }
            return
        }
        if (impact == null) return
        val operations = if (mode == null) agentState.turnDeviceOperations(messageId) else emptyList()
        if (operations.isEmpty() && impact.laterTurnCount == 0) {
            leaveThen(messageId) { agentState.regenerateMessage(messageId, mode) }
        } else {
            regenerateTarget = ConversationMutationTarget(messageId, impact.laterTurnCount, operations, mode)
        }
    }
    val homeState = agentState.homeState
    val redoTargets = remember(homeState.messages, homeState.roleplay) {
        if (homeState.roleplay != null) emptyMap()
        else AgentConversationRevisionReducer.redoModeTargets(homeState.messages)
    }
    CompositionLocalProvider(
        io.github.fartown.movo.ui.components.LocalRedoInOtherMode provides io.github.fartown.movo.ui.components.RedoInOtherMode(
            targets = redoTargets,
            redo = { id, mode -> requestRegenerate(id, mode) },
        ),
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
                is AgentChatAction.InteractionModeChanged -> agentState.updateInteractionMode(action.mode)
                is AgentChatAction.ChatWebSearchChanged -> agentState.updateChatWebSearch(action.enabled)
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
                is AgentChatAction.RegenerateMessage -> requestRegenerate(action.id, mode = null)
                is AgentChatAction.SelectReplyCandidate -> agentState.selectReplyCandidate(action.id, action.index)
            }
        },
    )
    }
    // 确认对话框（规范 8.11）：删除、重新执行动过手机的一轮为危险确认（bg/inverse），其余重新生成为普通确认；后果写在说明里。
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
    val redoesDevice = regenerating?.operations?.isNotEmpty() == true
    io.github.fartown.movo.ui.components.movo.MovoConfirmDialog(
        show = regenerating != null,
        title = stringResource(if (redoesDevice) R.string.conversation_redo_device_title else R.string.conversation_regenerate_title),
        message = regenerating?.let { target ->
            if (target.operations.isNotEmpty()) {
                val steps = target.operations.map { it.argumentsSummary.ifBlank { toolDisplayName(it.toolName) } }.distinct()
                // 列最后几步：发送、确认这类有后果的一下通常在最后。
                val listed = steps.takeLast(DEVICE_STEPS_LISTED).joinToString(stringResource(R.string.conversation_redo_device_separator))
                val summary = if (steps.size > DEVICE_STEPS_LISTED) {
                    stringResource(R.string.conversation_redo_device_steps_more, listed, target.operations.size)
                } else listed
                buildString {
                    append(stringResource(R.string.conversation_redo_device_message, summary))
                    if (target.laterTurnCount > 0) {
                        append(pluralStringResource(R.plurals.conversation_redo_device_later_turns, target.laterTurnCount, target.laterTurnCount))
                    }
                }
            } else if (target.laterTurnCount == 0) stringResource(R.string.conversation_regenerate_current_turn)
            else pluralStringResource(R.plurals.conversation_regenerate_later_turns, target.laterTurnCount, target.laterTurnCount)
        },
        confirmText = stringResource(if (redoesDevice) R.string.conversation_redo_device_confirm else R.string.action_regenerate),
        destructive = redoesDevice,
        onConfirm = {
            regenerating?.let { target -> leaveThen(target.messageId) { agentState.regenerateMessage(target.messageId, target.mode) } }
            regenerateTarget = null
        },
        onDismissRequest = { regenerateTarget = null },
    )
}

/** 确认框里最多列出最后几步，多了写「等 N 步」。 */
private const val DEVICE_STEPS_LISTED = 3

private data class ConversationMutationTarget(
    val messageId: String,
    val laterTurnCount: Int,
    /** 这一轮动过手机的步骤；重新执行会再做一遍（定稿 26-1）。 */
    val operations: List<io.github.fartown.movo.ui.model.ToolActivityMessageUi> = emptyList(),
    /** 换成另一种模式重做（定稿 26-2）；null 是按原模式重新生成。 */
    val mode: InteractionMode? = null,
)
