package io.github.fartown.movo.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.fartown.movo.data.model.ReasoningEffort
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "conversations", indices = [Index("updated_at")])
internal data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "thinking_enabled") val thinkingEnabled: Boolean,
    @ColumnInfo(name = "reasoning_effort", defaultValue = "'default'")
    val reasoningEffort: String = ReasoningEffort.DEFAULT.wireValue,
    @ColumnInfo(name = "history_json") val historyJson: String = "[]",
    @ColumnInfo(name = "applied_runtime_run_ids_json") val appliedRuntimeRunIdsJson: String = "[]",
    @ColumnInfo(name = "roleplay_json", defaultValue = "''") val roleplayJson: String = "",
    @ColumnInfo(name = "revisions_json", defaultValue = "''") val revisionsJson: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

internal data class MessageSortKey(
    val id: String,
    @ColumnInfo(name = "sort_index") val sortIndex: Long,
)

internal data class ConversationMetadata(
    val id: String,
    val title: String,
    @ColumnInfo(name = "thinking_enabled") val thinkingEnabled: Boolean,
    @ColumnInfo(name = "reasoning_effort") val reasoningEffort: String,
    @ColumnInfo(name = "applied_runtime_run_ids_json") val appliedRuntimeRunIdsJson: String,
    @ColumnInfo(name = "roleplay_json") val roleplayJson: String = "",
    @ColumnInfo(name = "revisions_json") val revisionsJson: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Serializable
@Entity(
    tableName = "conversation_context_checkpoints",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class ConversationContextCheckpointEntity(
    @PrimaryKey
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "history_json") val historyJson: String,
    @ColumnInfo(name = "journal_json", defaultValue = "''") val journalJson: String = "",
)

@Serializable
@Entity(tableName = "conversation_state")
internal data class ConversationStateEntity(
    @PrimaryKey val id: String = SINGLETON_ID,
    @ColumnInfo(name = "selected_conversation_id") val selectedConversationId: String,
) {
    companion object {
        const val SINGLETON_ID = "main"
    }
}

@Serializable
@Entity(
    tableName = "conversation_messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("conversation_id"),
        Index(value = ["conversation_id", "sort_index"], unique = true),
    ],
)
internal data class ConversationMessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    /** 对话内的排序键（v24 起按间隔分配：追加取最大值 + 1024，中间插入取前后两行的中值）。 */
    @ColumnInfo(name = "sort_index") val sortIndex: Long,
    val type: String,
    val content: String,
    @ColumnInfo(name = "images_json") val imagesJson: String = "[]",
    @ColumnInfo(name = "is_edited", defaultValue = "0") val isEdited: Boolean = false,
    @ColumnInfo(name = "render_markdown") val renderMarkdown: Boolean? = null,
    @ColumnInfo(name = "context_tokens") val contextTokens: Int? = null,
    @ColumnInfo(name = "input_tokens") val inputTokens: Int? = null,
    @ColumnInfo(name = "output_tokens") val outputTokens: Int? = null,
    @ColumnInfo(name = "reasoning_tokens") val reasoningTokens: Int? = null,
    @ColumnInfo(name = "cached_tokens") val cachedTokens: Int? = null,
    @ColumnInfo(name = "elapsed_seconds") val elapsedSeconds: Int? = null,
    @ColumnInfo(name = "tool_name") val toolName: String? = null,
    @ColumnInfo(name = "tool_status") val toolStatus: String? = null,
    @ColumnInfo(name = "arguments_summary") val argumentsSummary: String? = null,
    @ColumnInfo(name = "result_summary") val resultSummary: String? = null,
    @ColumnInfo(name = "image_count") val imageCount: Int = 0,
    @ColumnInfo(name = "tools_json") val toolsJson: String = "[]",
    /** 工具步骤开始 / 结束时刻（毫秒，v22 起）；旧数据为 null。 */
    @ColumnInfo(name = "started_at") val startedAt: Long? = null,
    @ColumnInfo(name = "finished_at") val finishedAt: Long? = null,
    /** 工具步骤的界面视图 JSON（v23 起）；只存非临时视图（截图、个人数据不存）。 */
    @ColumnInfo(name = "tool_view_json") val toolViewJson: String? = null,
)

/**
 * 发给模型的消息（v24 起），一条一行，取代 conversation_context_checkpoints 里的两大块 JSON。
 * [LOG_HISTORY] 是发给模型的历史：每轮只追加，压缩、删轮、编辑、重新生成时只重写这一个对话的这一段；
 * [LOG_JOURNAL] 是完整记录：只追加，删轮时重写这一个对话的这一段。
 */
@Entity(
    tableName = "conversation_model_messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["conversation_id", "log", "seq"], unique = true)],
)
internal data class ConversationModelMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    val log: String,
    val seq: Int,
    @ColumnInfo(name = "run_id") val runId: String? = null,
    /** 单条 ConversationMessage（已脱敏、不含图片）；超过 16K 字符存在 agent_text_chunks。 */
    @ColumnInfo(name = "message_json") val messageJson: String,
) {
    companion object {
        const val TABLE = "conversation_model_messages"
        const val LOG_HISTORY = "history"
        const val LOG_JOURNAL = "journal"
        const val TEXT_FIELD = "message"

        /** 分块的主人：与删除触发器里拼出的 owner_id 一致。 */
        fun chunkOwner(conversationId: String, log: String, seq: Int) = "$conversationId/$log/$seq"
    }
}
