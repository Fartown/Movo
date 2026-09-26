package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec

/**
 * 会话内容搜索：对完整消息流做大小写不敏感匹配。
 * 标题与预览的匹配由调用方负责；notice 文案由调用方按当前语言注入。
 */
internal fun AgentChatHomeUiState.contentMatches(
    query: String,
    noticeText: (SystemNoticeCode) -> String,
): Boolean {
    if (query.isBlank()) return true
    return messages.any { message -> message.matches(query, noticeText) }
}

/**
 * 侧边栏搜索结果的命中片段：取第一处命中的文字，命中词前保留少量上下文（规范 8.6「搜索」）。
 * 没有内容命中时返回 null（调用方回退到预览）。
 */
internal fun AgentChatHomeUiState.contentMatchSnippet(
    query: String,
    noticeText: (SystemNoticeCode) -> String,
): String? {
    if (query.isBlank()) return null
    for (message in messages) {
        val text = message.searchableTexts(noticeText).firstOrNull { it.contains(query, ignoreCase = true) }
        if (text != null) return matchExcerpt(text, query)
    }
    return null
}

/** 命中词前留 [lead] 个字，超出部分用「…」；空白折叠为单个空格。 */
internal fun matchExcerpt(text: String, query: String, lead: Int = 10): String {
    val flat = text.replace(Regex("\\s+"), " ").trim()
    val index = flat.indexOf(query, ignoreCase = true)
    if (index <= lead) return flat
    return "…" + flat.substring(index - lead)
}

private fun AgentChatMessageUi.matches(
    query: String,
    noticeText: (SystemNoticeCode) -> String,
): Boolean = searchableTexts(noticeText).any { it.contains(query, ignoreCase = true) }

/** 参与搜索的文字；[contentMatches] 与 [contentMatchSnippet] 共用，保证两者口径一致。 */
private fun AgentChatMessageUi.searchableTexts(
    noticeText: (SystemNoticeCode) -> String,
): List<String> = when (this) {
    is UserMessageUi -> {
        val prompt = AgentFileReferencePromptCodec.parse(content)
        listOf(prompt.request) + prompt.references.flatMap { reference ->
            listOf(reference.displayName, reference.absolutePath)
        }
    }

    is AgentMessageUi -> listOf(content)

    is ThinkingMessageUi -> listOf(content)

    is ToolActivityMessageUi -> listOfNotNull(toolName, command, argumentsSummary, resultSummary)

    is ToolSummaryMessageUi -> tools

    is SystemNoticeMessageUi -> listOfNotNull(noticeText(code), detail)

    else -> emptyList()
}
