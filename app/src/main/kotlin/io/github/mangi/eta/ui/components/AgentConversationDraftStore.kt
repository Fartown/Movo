package io.github.mangi.eta.ui.components

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.staticCompositionLocalOf

/** Text, selection and composing state belong to the conversation, not its window. */
internal class AgentConversationDraftStore {
    private val drafts = mutableMapOf<String?, TextFieldState>()

    fun get(conversationId: String?, initialText: String = ""): TextFieldState =
        drafts.getOrPut(conversationId) { TextFieldState(initialText = initialText) }

    fun remove(conversationId: String?) {
        // A still-mounted empty conversation must observe "new chat" immediately as well.
        drafts.remove(conversationId)?.edit { replace(0, length, "") }
    }

    fun clear() {
        drafts.keys.toList().forEach(::remove)
    }

    companion object {
        val shared = AgentConversationDraftStore()
    }
}

internal val LocalConversationComposer = staticCompositionLocalOf<TextFieldState?> { null }
