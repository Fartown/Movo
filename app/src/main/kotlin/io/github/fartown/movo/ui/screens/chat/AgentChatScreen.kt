package io.github.fartown.movo.ui.screens.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.fartown.movo.ui.components.AgentChatBody
import io.github.fartown.movo.ui.components.AgentConversationDraftStore
import io.github.fartown.movo.ui.components.LocalConversationComposer
import io.github.fartown.movo.ui.model.AgentChatAction
import io.github.fartown.movo.ui.model.AgentChatUiState
import io.github.fartown.movo.ui.model.AgentModelPickerUiState

/**
 * 独立对话页：与首页聊天主舞台共用同一套消息/输入组件，
 * 区别仅在于顶部返回由 Shell 统一提供。
 */
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
    key(compositionKey) {
        CompositionLocalProvider(
            LocalConversationComposer provides AgentConversationDraftStore.shared.get(conversationKey, state.input),
            io.github.fartown.movo.ui.components.LocalRunControls provides io.github.fartown.movo.ui.components.RunControls(
                isPaused = state.isStreaming && state.isPaused,
                onResume = { onAction(AgentChatAction.ResumeRun) },
                onEndTask = { onAction(AgentChatAction.StopRun) },
            ),
        ) {
            AgentChatBody(
                messages = state.messages,
                modelPickerState = modelPickerState,
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
                modifier = modifier,
                isDrawerOpen = isDrawerOpen,
                initiallyShowLatestMessage = initiallyShowLatestMessage,
            )
        }
    }
}
