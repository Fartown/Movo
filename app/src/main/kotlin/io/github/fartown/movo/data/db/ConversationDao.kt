package io.github.fartown.movo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
internal interface ConversationDao : ChunkedTextDao {
    @Query(
        "SELECT id, title, thinking_enabled, reasoning_effort, " +
            "applied_runtime_run_ids_json, roleplay_json, revisions_json, created_at, updated_at " +
            "FROM conversations ORDER BY updated_at DESC"
    )
    suspend fun conversationMetadataRows(): List<ConversationMetadata>

    @Transaction
    suspend fun conversations(): List<ConversationMetadata> = conversationMetadataRows().map { restoreMetadata(it) }

    @Query(
        "SELECT id, title, thinking_enabled, reasoning_effort, " +
            "applied_runtime_run_ids_json, roleplay_json, revisions_json, created_at, updated_at " +
            "FROM conversations ORDER BY updated_at DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun conversationMetadataPage(limit: Int, offset: Int): List<ConversationMetadata>

    @Transaction
    suspend fun conversationsPage(limit: Int, offset: Int): List<ConversationMetadata> =
        conversationMetadataPage(limit, offset).map { restoreMetadata(it) }

    suspend fun restoreMetadata(row: ConversationMetadata) = row.copy(
        appliedRuntimeRunIdsJson = restoreText("conversations", row.id, "runs", row.appliedRuntimeRunIdsJson),
        roleplayJson = restoreText("conversations", row.id, "roleplay", row.roleplayJson),
        revisionsJson = restoreText("conversations", row.id, "revisions", row.revisionsJson),
    )

    @Query("SELECT roleplay_json FROM conversations WHERE id = :conversationId")
    suspend fun roleplayJsonRow(conversationId: String): String?

    @Transaction
    suspend fun roleplayJson(conversationId: String): String? = roleplayJsonRow(conversationId)?.let {
        restoreText("conversations", conversationId, "roleplay", it)
    }

    @Query("SELECT * FROM conversation_messages ORDER BY conversation_id ASC, sort_index ASC")
    suspend fun messageRows(): List<ConversationMessageEntity>

    @Transaction
    suspend fun messages(): List<ConversationMessageEntity> = messageRows().map { restoreMessage(it) }

    @Query("SELECT * FROM conversations ORDER BY updated_at ASC")
    suspend fun conversationEntityRows(): List<ConversationEntity>

    @Transaction
    suspend fun conversationEntities(): List<ConversationEntity> = conversationEntityRows().map { row ->
        row.copy(
            appliedRuntimeRunIdsJson = restoreText("conversations", row.id, "runs", row.appliedRuntimeRunIdsJson),
            roleplayJson = restoreText("conversations", row.id, "roleplay", row.roleplayJson),
            revisionsJson = restoreText("conversations", row.id, "revisions", row.revisionsJson),
        )
    }

    @Query("SELECT * FROM conversation_context_checkpoints ORDER BY conversation_id ASC")
    suspend fun contextCheckpointRows(): List<ConversationContextCheckpointEntity>

    @Transaction
    suspend fun contextCheckpoints(): List<ConversationContextCheckpointEntity> = contextCheckpointRows().map { restoreCheckpoint(it) }

    @Query("SELECT * FROM conversation_messages WHERE conversation_id = :conversationId ORDER BY sort_index ASC LIMIT :limit OFFSET :offset")
    suspend fun messageRowsPage(conversationId: String, limit: Int, offset: Int): List<ConversationMessageEntity>

    @Transaction
    suspend fun messagesPage(conversationId: String, limit: Int, offset: Int): List<ConversationMessageEntity> =
        messageRowsPage(conversationId, limit, offset).map { restoreMessage(it) }

    @Query("SELECT COUNT(*) FROM conversation_messages WHERE conversation_id = :conversationId")
    suspend fun messageCount(conversationId: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM conversation_messages WHERE conversation_id = :conversationId AND id = :messageId AND type = 'assistant')")
    suspend fun hasAssistantMessage(conversationId: String, messageId: String): Boolean

    @Query("SELECT * FROM conversation_context_checkpoints WHERE conversation_id = :conversationId")
    suspend fun contextCheckpointRow(conversationId: String): ConversationContextCheckpointEntity?

    @Transaction
    suspend fun contextCheckpoint(conversationId: String): ConversationContextCheckpointEntity? =
        contextCheckpointRow(conversationId)?.let { restoreCheckpoint(it) }

    suspend fun restoreCheckpoint(row: ConversationContextCheckpointEntity) = row.copy(
        historyJson = restoreText("conversation_context_checkpoints", row.conversationId, "history", row.historyJson),
        journalJson = restoreText("conversation_context_checkpoints", row.conversationId, "journal", row.journalJson),
    )

    @Query("SELECT * FROM conversation_state WHERE id = :id")
    suspend fun state(id: String = ConversationStateEntity.SINGLETON_ID): ConversationStateEntity?

    @Upsert
    suspend fun insertConversationRow(conversation: ConversationEntity)

    @Transaction
    suspend fun insertConversations(conversations: List<ConversationEntity>) {
        conversations.forEach { row ->
            insertConversationRow(row.copy(
                appliedRuntimeRunIdsJson = storeText("conversations", row.id, "runs", row.appliedRuntimeRunIdsJson),
                roleplayJson = storeText("conversations", row.id, "roleplay", row.roleplayJson),
                revisionsJson = storeText("conversations", row.id, "revisions", row.revisionsJson),
            ))
        }
    }

    @Upsert
    suspend fun insertMessageRow(message: ConversationMessageEntity)

    @Transaction
    suspend fun insertMessages(messages: List<ConversationMessageEntity>) {
        messages.forEach { row ->
            insertMessageRow(row.copy(
                content = storeText("conversation_messages", row.id, "content", row.content),
                imagesJson = storeText("conversation_messages", row.id, "images", row.imagesJson),
            ))
        }
    }

    suspend fun restoreMessage(row: ConversationMessageEntity) = row.copy(
        content = restoreText("conversation_messages", row.id, "content", row.content),
        imagesJson = restoreText("conversation_messages", row.id, "images", row.imagesJson),
    )

    @Upsert
    suspend fun insertContextCheckpointRow(checkpoint: ConversationContextCheckpointEntity)

    @Transaction
    suspend fun insertContextCheckpoints(checkpoints: List<ConversationContextCheckpointEntity>) {
        checkpoints.forEach { row ->
            insertContextCheckpointRow(row.copy(
                historyJson = storeText("conversation_context_checkpoints", row.conversationId, "history", row.historyJson),
                journalJson = storeText("conversation_context_checkpoints", row.conversationId, "journal", row.journalJson),
            ))
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertState(state: ConversationStateEntity)

    @Query("DELETE FROM conversations")
    suspend fun deleteConversations()

    @Query("DELETE FROM conversation_messages")
    suspend fun deleteMessages()

    @Query("DELETE FROM conversation_context_checkpoints")
    suspend fun deleteContextCheckpoints()

    @Query("DELETE FROM conversation_state")
    suspend fun deleteState()

    // —— 按对话、按行读写（v24 起，docs/solutions/conversation-storage）——

    /** 一个对话的目录行（不读已废弃、可能很大的 history_json 列）。 */
    @Query(
        "SELECT id, title, thinking_enabled, reasoning_effort, " +
            "applied_runtime_run_ids_json, roleplay_json, revisions_json, created_at, updated_at " +
            "FROM conversations WHERE id = :conversationId"
    )
    suspend fun conversationMetadataRow(conversationId: String): ConversationMetadata?

    @Transaction
    suspend fun conversationMetadata(conversationId: String): ConversationMetadata? =
        conversationMetadataRow(conversationId)?.let { restoreMetadata(it) }

    @Query("SELECT created_at FROM conversations WHERE id = :conversationId")
    suspend fun createdAt(conversationId: String): Long?

    @Query("SELECT EXISTS(SELECT 1 FROM conversations WHERE id = :conversationId)")
    suspend fun conversationExists(conversationId: String): Boolean

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteConversationRow(conversationId: String)

    @Query("SELECT * FROM conversation_messages WHERE conversation_id = :conversationId ORDER BY sort_index ASC")
    suspend fun messageRowsOf(conversationId: String): List<ConversationMessageEntity>

    @Transaction
    suspend fun messagesOf(conversationId: String): List<ConversationMessageEntity> =
        messageRowsOf(conversationId).map { restoreMessage(it) }

    @Query("SELECT id, sort_index FROM conversation_messages WHERE conversation_id = :conversationId")
    suspend fun messageSortKeys(conversationId: String): List<MessageSortKey>

    @Query("DELETE FROM conversation_messages WHERE id IN (:ids)")
    suspend fun deleteMessageRows(ids: List<String>)

    @Query("UPDATE conversation_messages SET sort_index = :sortIndex WHERE id = :id")
    suspend fun updateMessageSortIndex(id: String, sortIndex: Long)

    /** 侧栏预览：每个对话排在最后的那一行。 */
    @Query(
        "SELECT m.* FROM conversation_messages m WHERE m.conversation_id IN (:conversationIds) AND m.sort_index = " +
            "(SELECT MAX(sort_index) FROM conversation_messages WHERE conversation_id = m.conversation_id)"
    )
    suspend fun lastMessageRows(conversationIds: List<String>): List<ConversationMessageEntity>

    @Transaction
    suspend fun lastMessages(conversationIds: List<String>): List<ConversationMessageEntity> =
        lastMessageRows(conversationIds).map { restoreMessage(it) }

    /** 搜索：标题、正文（含分块的长正文）、工具名与摘要里出现 [pattern]（LIKE，已转义）。 */
    @Query(
        "SELECT id FROM conversations WHERE title LIKE :pattern ESCAPE '\\' " +
            "UNION SELECT conversation_id FROM conversation_messages WHERE content LIKE :pattern ESCAPE '\\' " +
            "OR tool_name LIKE :pattern ESCAPE '\\' OR arguments_summary LIKE :pattern ESCAPE '\\' " +
            "OR result_summary LIKE :pattern ESCAPE '\\' " +
            "UNION SELECT m.conversation_id FROM agent_text_chunks t JOIN conversation_messages m ON m.id = t.owner_id " +
            "WHERE t.owner_table = 'conversation_messages' AND t.field = 'content' AND t.content LIKE :pattern ESCAPE '\\'"
    )
    suspend fun searchConversationIds(pattern: String): List<String>

    /** 搜索命中的消息行（正文含分块的长正文、工具名与摘要），给侧栏搜索算命中片段。 */
    @Query(
        "SELECT * FROM conversation_messages WHERE content LIKE :pattern ESCAPE '\\' " +
            "OR tool_name LIKE :pattern ESCAPE '\\' OR arguments_summary LIKE :pattern ESCAPE '\\' " +
            "OR result_summary LIKE :pattern ESCAPE '\\' " +
            "OR id IN (SELECT owner_id FROM agent_text_chunks WHERE owner_table = 'conversation_messages' " +
            "AND field = 'content' AND content LIKE :pattern ESCAPE '\\') " +
            "ORDER BY conversation_id, sort_index LIMIT :limit"
    )
    suspend fun searchMessageRows(pattern: String, limit: Int): List<ConversationMessageEntity>

    @Transaction
    suspend fun searchMessages(pattern: String, limit: Int): List<ConversationMessageEntity> =
        searchMessageRows(pattern, limit).map { restoreMessage(it) }

    @Query("UPDATE conversations SET title = :title, updated_at = :updatedAt WHERE id = :conversationId")
    suspend fun updateTitle(conversationId: String, title: String, updatedAt: Long)

    /** 角色对话里还挂着“重新生成”的（启动恢复要核对它们的运行是否还在）；分块存的保守算进来。 */
    @Query(
        "SELECT id FROM conversations WHERE revisions_json LIKE '%\"pendingRewrites\":{\"%' " +
            "OR revisions_json LIKE '@movo:chunks:%'"
    )
    suspend fun pendingRewriteConversationIds(): List<String>

    @Insert
    suspend fun insertModelMessageRow(row: ConversationModelMessageEntity): Long

    @Query("SELECT * FROM conversation_model_messages WHERE conversation_id = :conversationId AND log = :log ORDER BY seq ASC")
    suspend fun modelMessageRows(conversationId: String, log: String): List<ConversationModelMessageEntity>

    @Query("SELECT COUNT(*) FROM conversation_model_messages WHERE conversation_id = :conversationId AND log = :log")
    suspend fun modelMessageCount(conversationId: String, log: String): Int

    @Query("DELETE FROM conversation_model_messages WHERE conversation_id = :conversationId AND log = :log")
    suspend fun deleteModelLog(conversationId: String, log: String)

    /** 一轮执行的进行中记录（不管属于哪个对话）。 */
    @Query("DELETE FROM conversation_model_messages WHERE log IN (:logs)")
    suspend fun deleteLogs(logs: List<String>)

    @Query("SELECT DISTINCT conversation_id FROM conversation_model_messages WHERE log = :log")
    suspend fun conversationsWithLog(log: String): List<String>

    /** 一段模型消息（[ConversationModelMessageEntity.LOG_HISTORY] 或 LOG_JOURNAL），按顺序还原成单条 JSON。 */
    @Transaction
    suspend fun modelLog(conversationId: String, log: String): List<String> =
        modelMessageRows(conversationId, log).map { row ->
            restoreText(ConversationModelMessageEntity.TABLE, ConversationModelMessageEntity.chunkOwner(conversationId, log, row.seq),
                ConversationModelMessageEntity.TEXT_FIELD, row.messageJson)
        }

    @Transaction
    suspend fun appendModelMessages(conversationId: String, log: String, firstSeq: Int, runId: String?, messages: List<String>) {
        messages.forEachIndexed { offset, message ->
            val seq = firstSeq + offset
            insertModelMessageRow(ConversationModelMessageEntity(
                conversationId = conversationId, log = log, seq = seq, runId = runId,
                messageJson = storeText(ConversationModelMessageEntity.TABLE,
                    ConversationModelMessageEntity.chunkOwner(conversationId, log, seq), ConversationModelMessageEntity.TEXT_FIELD, message),
            ))
        }
    }

    @Transaction
    suspend fun replaceModelLog(conversationId: String, log: String, messages: List<String>) {
        deleteModelLog(conversationId, log)
        appendModelMessages(conversationId, log, 0, null, messages)
    }

    /** 整库替换：只剩备份导入在用（对话的日常保存按行写，见上）。 */
    @Transaction
    suspend fun replaceAll(
        conversations: List<ConversationEntity>,
        messages: List<ConversationMessageEntity>,
        contextCheckpoints: List<ConversationContextCheckpointEntity> = emptyList(),
        state: ConversationStateEntity?,
    ) {
        deleteMessages()
        deleteContextCheckpoints()
        deleteConversations()
        deleteState()
        insertConversations(conversations)
        insertContextCheckpoints(contextCheckpoints)
        insertMessages(messages)
        state?.let { insertState(it) }
    }
}
