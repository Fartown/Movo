package io.github.mangi.eta.ui.components

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.staticCompositionLocalOf

/** Text, selection and composing state belong to the conversation, not its window. */
internal class AgentConversationDraftStore {
    private val drafts = mutableMapOf<String?, TextFieldState>()

    fun get(conversationId: String?, initialText: String = ""): TextFieldState =
        drafts.getOrPut(conversationId) { TextFieldState(initialText = initialText) }

    /** A first voice turn assigns an ID without consuming the unsent editor. */
    fun assignConversation(id: String, initialText: String = ""): TextFieldState {
        lastAssignedConversationId = id
        return drafts.getOrPut(id) { drafts.remove(null) ?: TextFieldState(initialText = initialText) }
    }

    /** 最近一次由草稿就地变成的会话（发出第一句时新建），聊天舞台据此沿用草稿的组合。 */
    @Volatile
    var lastAssignedConversationId: String? = null
        private set

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

/**
 * 当前这一轮的运行级控制（规范 8.2 主按钮状态机、8.1 执行卡底部栏）：是否在悬浮球里被暂停、继续、结束任务。
 * 由对话页提供；没有提供时（如执行详情页外的宿主）视为未暂停。
 */
internal data class RunControls(
    val isPaused: Boolean = false,
    val onResume: () -> Unit = {},
    val onEndTask: () -> Unit = {},
)

internal val LocalRunControls = staticCompositionLocalOf { RunControls() }
