package io.github.fartown.movo.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONArray

/**
 * v23 → v24：把 conversation_context_checkpoints 里每个对话的 history_json / journal_json（大的已分块）拆成
 * conversation_model_messages 的一条一行。journal 为空时与旧读法一致，取 history。
 * 按 JSON 数组逐个元素搬，不依赖模型消息的类型定义；解析不了的那一段跳过（读取时退回按显示内容重建历史）。
 */
internal object ModelLogMigration {
    private const val CHECKPOINTS = "conversation_context_checkpoints"
    private const val CHUNK_CHARS = 16_384

    fun migrate(db: SupportSQLiteDatabase) {
        val owners = db.query("SELECT conversation_id FROM $CHECKPOINTS").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        owners.forEach { conversationId ->
            val history = elements(read(db, conversationId, "history_json", "history"))
            val journalRaw = read(db, conversationId, "journal_json", "journal")
            val journal = if (journalRaw.isNullOrBlank()) history else elements(journalRaw) ?: history
            history?.let { insert(db, conversationId, ConversationModelMessageEntity.LOG_HISTORY, it) }
            journal?.let { insert(db, conversationId, ConversationModelMessageEntity.LOG_JOURNAL, it) }
        }
    }

    private fun read(db: SupportSQLiteDatabase, conversationId: String, column: String, field: String): String? {
        val stored = db.query("SELECT $column FROM $CHECKPOINTS WHERE conversation_id = ?", arrayOf(conversationId))
            .use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null }
            ?: return null
        if (!stored.startsWith(ChunkedTextDao.REFERENCE_PREFIX)) return stored
        return db.query(
            "SELECT content FROM agent_text_chunks WHERE owner_table = ? AND owner_id = ? AND field = ? ORDER BY chunk_index",
            arrayOf(CHECKPOINTS, conversationId, field),
        ).use { cursor -> buildString { while (cursor.moveToNext()) append(cursor.getString(0)) } }
    }

    private fun elements(raw: String?): List<String>? {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return null
        // 每个元素按合法 JSON 存：对象 / 数组原样，字符串要带引号（旧数据里有裸字符串）。
        return (0 until array.length()).map { index ->
            when (val element = array.get(index)) {
                is String -> org.json.JSONObject.quote(element)
                else -> element.toString()
            }
        }
    }

    private fun insert(db: SupportSQLiteDatabase, conversationId: String, log: String, messages: List<String>) {
        messages.forEachIndexed { seq, message ->
            val stored = if (message.length <= CHUNK_CHARS) message else {
                val owner = ConversationModelMessageEntity.chunkOwner(conversationId, log, seq)
                var count = 0
                var offset = 0
                while (offset < message.length) {
                    // 不把 UTF-16 代理对拆到两段里（与 ChunkedTextDao.storeText 一致）。
                    var end = minOf(offset + CHUNK_CHARS, message.length)
                    if (end < message.length && message[end - 1].isHighSurrogate()) end--
                    db.execSQL(
                        "INSERT INTO agent_text_chunks (owner_table, owner_id, field, chunk_index, content) VALUES (?, ?, ?, ?, ?)",
                        arrayOf<Any>(ConversationModelMessageEntity.TABLE, owner, ConversationModelMessageEntity.TEXT_FIELD, count++,
                            message.substring(offset, end)),
                    )
                    offset = end
                }
                "${ChunkedTextDao.REFERENCE_PREFIX}$count:${message.length}"
            }
            db.execSQL(
                "INSERT INTO conversation_model_messages (conversation_id, log, seq, run_id, message_json) VALUES (?, ?, ?, NULL, ?)",
                arrayOf<Any>(conversationId, log, seq, stored),
            )
        }
    }
}
