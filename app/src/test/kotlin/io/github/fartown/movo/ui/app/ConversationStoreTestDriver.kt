package io.github.fartown.movo.ui.app

import android.content.Context
import io.github.fartown.movo.data.db.ConversationModelMessageEntity
import io.github.fartown.movo.ui.model.AgentChatHomeUiState

/**
 * 测试用：按原 AgentConversationStore 的 save / load 口径存取整份快照，底下走 [ConversationRepository]，
 * 让原来的“存进去再读出来不走样”的测试继续验证新存储。
 */
internal object ConversationStoreTestDriver {
    data class Snapshot(
        val selectedConversationId: String?,
        val conversationsById: Map<String, AgentChatHomeUiState>,
        val titles: Map<String, String>,
        val updatedAt: Map<String, Long>,
    )

    suspend fun save(
        context: Context,
        selectedConversationId: String?,
        conversationsById: Map<String, AgentChatHomeUiState>,
        titles: Map<String, String>,
        updatedAt: Map<String, Long>,
    ) {
        val repository = ConversationRepository.get(context)
        repository.summaries(Int.MAX_VALUE).map { it.id }.filterNot { it in conversationsById }
            .forEach { repository.deleteConversation(it) }
        conversationsById.forEach { (id, state) ->
            val stored = repository.load(id)?.state
            repository.saveConversation(id, state, titles[id].orEmpty(), updatedAt[id] ?: 0L)
            repository.syncMessages(id, stored?.messages.orEmpty(), state.messages)
            repository.syncModelLog(id, ConversationModelMessageEntity.LOG_HISTORY, stored?.history.orEmpty(), state.history)
            repository.syncModelLog(id, ConversationModelMessageEntity.LOG_JOURNAL,
                stored?.let { it.journal.ifEmpty { it.history } }.orEmpty(), state.journal.ifEmpty { state.history })
        }
        repository.select(selectedConversationId?.takeIf { it in conversationsById })
        repository.flush()
    }

    fun load(context: Context): Snapshot = kotlinx.coroutines.runBlocking {
        val repository = ConversationRepository.get(context)
        val summaries = repository.summaries(Int.MAX_VALUE)
        val loaded = summaries.mapNotNull { repository.load(it.id) }
        Snapshot(
            selectedConversationId = repository.selectedConversationId(),
            conversationsById = loaded.associate { it.id to it.state },
            titles = loaded.associate { it.id to it.title },
            updatedAt = loaded.associate { it.id to it.updatedAt },
        )
    }
}
