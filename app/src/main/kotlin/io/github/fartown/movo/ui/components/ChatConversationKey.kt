package io.github.fartown.movo.ui.components

/**
 * 为聊天舞台生成独立的 Compose 状态边界。
 *
 * 会话切换时必须重建 LazyListState、流式 Markdown 状态和底部跟随任务，
 * 否则旧会话的列表位置可能被新会话复用。
 */
internal fun chatConversationCompositionKey(conversationId: String?): String =
    conversationId?.let { "conversation:$it" } ?: "conversation:draft"

/**
 * 草稿发出第一句时就地变成新会话：这一刻沿用草稿的组合，不重建聊天舞台，
 * 否则 Q1 文字飞成气泡 / 能力卡收成气泡 / 首页内容淡出（规范 9.4「首页 → 对话」）会随旧组合一起被丢掉。
 * 其他切换（侧边栏选会话、新建对话、离开这条会话）仍按会话 id 重建；每次回到草稿都换一个新的草稿组合。
 */
internal class ChatCompositionKeys {
    private var initialized = false
    private var lastId: String? = null
    private var draftGeneration = 0
    private var adoptedId: String? = null
    /** 每个会话只在它由草稿变成的那一次沿用草稿组合。 */
    private val adoptedOnce = HashSet<String>()

    /** [adoptedFromDraft]：这个会话是不是刚由草稿就地变成的（草稿存储登记的最近一次）。 */
    fun keyFor(conversationId: String?, adoptedFromDraft: Boolean): String {
        val key = when {
            conversationId == null -> {
                if (initialized && lastId != null) draftGeneration++
                adoptedId = null
                draftKey()
            }
            conversationId == adoptedId -> draftKey()
            initialized && lastId == null && adoptedId == null && adoptedFromDraft && adoptedOnce.add(conversationId) -> {
                adoptedId = conversationId
                draftKey()
            }
            else -> {
                adoptedId = null
                chatConversationCompositionKey(conversationId)
            }
        }
        initialized = true
        lastId = conversationId
        return key
    }

    private fun draftKey(): String = "${chatConversationCompositionKey(null)}:$draftGeneration"
}
