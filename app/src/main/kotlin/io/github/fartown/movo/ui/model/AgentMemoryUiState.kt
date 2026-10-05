package io.github.fartown.movo.ui.model

import androidx.compose.runtime.Immutable
import io.github.fartown.movo.data.repository.AgentMemoryStore

@Immutable
data class AgentMemoryUiState(
    val enabled: Boolean = true,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val draft: String = "",
    val savedContent: String = "",
    val draftBytes: Int = 0,
    val maxBytes: Int = AgentMemoryStore.MAX_FILE_BYTES,
    val coreBudgetChars: Int = 8_000,
    val notice: String? = null,
) {
    val hasUnsavedChanges: Boolean get() = draft != savedContent
    val canSave: Boolean
        get() = !isLoading && !isSaving && hasUnsavedChanges &&
            draftBytes <= maxBytes

    /** A repository refresh must not replace text the user has not saved yet. */
    fun withLoadedSnapshot(content: String, enabled: Boolean, coreBudgetChars: Int): AgentMemoryUiState {
        val visibleDraft = if (hasUnsavedChanges) draft else content
        return copy(
            enabled = enabled,
            isLoading = false,
            draft = visibleDraft,
            savedContent = content,
            draftBytes = visibleDraft.toByteArray(Charsets.UTF_8).size,
            coreBudgetChars = coreBudgetChars,
            notice = null,
        )
    }
}
