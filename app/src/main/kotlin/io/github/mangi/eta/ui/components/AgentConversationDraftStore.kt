package io.github.mangi.eta.ui.components

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.staticCompositionLocalOf

/** Text, selection and composing state belong to the conversation, not its window. */
internal class AgentConversationDraftStore {
    private val drafts = mutableMapOf<String?, TextFieldState>()

    fun get(conversationId: String?, initialText: String = ""): TextFieldState =
        drafts.getOrPut(conversationId) { TextFieldState(initialText = initialText) }

    /** A first voice turn assigns an ID without consuming the unsent editor. */
    fun assignConversation(id: String, initialText: String = ""): TextFieldState =
        drafts.getOrPut(id) { drafts.remove(null) ?: TextFieldState(initialText = initialText) }

    /** Consume the submitted snapshot, retaining edits/transcription appended during mode switching. */
    fun consume(conversationId: String?, submittedText: String): String {
        val draft = get(conversationId)
        draft.edit {
            val current = asCharSequence().toString()
            if (submittedText.isNotEmpty() && current.startsWith(submittedText)) {
                var end = submittedText.length
                if (end < current.length && current[end] == '\n') end++
                replace(0, end, "")
            }
        }
        return draft.text.toString()
    }

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
