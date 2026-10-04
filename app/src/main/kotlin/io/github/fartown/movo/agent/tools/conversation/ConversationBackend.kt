package io.github.fartown.movo.agent.tools.conversation

/**
 * 会话历史领域（§40 conversation_read）的可测后端。
 * 真实实现绑定当前运行的持久会话脱敏历史；测试用内存假实现。
 *
 * 每条目已是脱敏后的「角色 + 文本」：敏感工具原文与图片不在持久历史里，这里也读不到。
 */
internal data class ConversationEntryView(
    val index: Int,
    val role: String,
    val text: String,
)

internal interface ConversationBackend {
    /** 当前会话的完整脱敏历史（按时间顺序，index 为稳定序号）。 */
    fun load(): List<ConversationEntryView>
}
