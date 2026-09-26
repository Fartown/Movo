package io.github.fartown.movo.ui.components

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

    /**
     * 用户主动切换 / 新建会话时调用：之后再打开那个会话不是「草稿就地变成会话」，要换一份新组合并停在最新消息。
     * 不清的话，从侧边栏打开最近一次由草稿建出的会话（尤其是在对话浮层或上一个主界面里建的）会沿用草稿的组合，
     * 「打开即停在最新」不会重跑，停在会话开头。
     */
    fun clearAssignment() {
        lastAssignedConversationId = null
    }

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
    /** 本轮已完成的步骤数，暂停提示条「已完成 N 步」用。 */
    val completedSteps: Int = 0,
    val onResume: () -> Unit = {},
    val onEndTask: () -> Unit = {},
)

internal val LocalRunControls = staticCompositionLocalOf { RunControls() }

/** 请求当前输入框获焦并弹出键盘（例如分享到 Movo 打开浮层时）。请求 3 秒内有效、只被消费一次。 */
internal object ComposerFocusRequest {
    var generation by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    private var requestedAt = 0L

    fun request() {
        requestedAt = android.os.SystemClock.uptimeMillis()
        generation++
    }

    fun consume(): Boolean {
        val pending = requestedAt > 0L && android.os.SystemClock.uptimeMillis() - requestedAt < 3_000L
        requestedAt = 0L
        return pending
    }
}
