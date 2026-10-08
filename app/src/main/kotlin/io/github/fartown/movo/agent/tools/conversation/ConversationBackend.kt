package io.github.fartown.movo.agent.tools.conversation

/**
 * 会话历史领域（§40 conversation_read）的可测后端。
 * 真实实现读当前对话的完整记录（数据库里的 journal + 本次任务的步骤）；测试用内存假实现。
 *
 * 每条目是渲染好的「角色 + 文本」（文本里含工具调用与工具结果）；图片不在持久记录里，这里也读不到。
 */
internal data class ConversationEntryView(
    val index: Int,
    val role: String,
    val text: String,
)

internal interface ConversationBackend {
    /** 当前会话的完整历史（按时间顺序，index 为稳定序号；只会在末尾追加）。 */
    fun load(): List<ConversationEntryView>
}
