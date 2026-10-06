package io.github.fartown.movo.data.db

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** v23 → v24：检查点里的两大块模型历史拆成一条一行（docs/solutions/conversation-storage）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ModelLogMigrationTest {
    @Test
    fun checkpointsBecomeOneRowPerMessageAndTheSchemaMatchesVersion24() {
        val context = RuntimeEnvironment.getApplication() as Context
        val name = "model-log-migration.db"
        context.deleteDatabase(name)
        val longMessage = "{\"role\":\"tool\",\"content\":\"${"节点".repeat(10_000)}\"}"
        val chunkedHistory = "[{\"role\":\"user\",\"content\":\"看看屏幕\"},$longMessage]"
        RoomSchemaFixture.create(context, name, 23).use { db ->
            listOf("plain", "chunked", "broken", "journal").forEach { id ->
                db.execSQL("INSERT INTO conversations (id, title, thinking_enabled, reasoning_effort, history_json, " +
                    "applied_runtime_run_ids_json, roleplay_json, revisions_json, created_at, updated_at) " +
                    "VALUES (?, '', 0, 'default', '[]', '[]', '', '', 1, 1)", arrayOf<Any>(id))
            }
            db.execSQL("INSERT INTO conversation_context_checkpoints (conversation_id, history_json, journal_json) VALUES " +
                "('plain', '[{\"role\":\"user\",\"content\":\"打开哔哩哔哩\"},{\"role\":\"assistant\",\"content\":\"已打开\"}]', '')")
            // 旧版大字段按 8192 字符分块（HistoryPayloadMigration）。
            val chunks = chunkedHistory.chunked(8192)
            chunks.forEachIndexed { index, chunk ->
                db.execSQL("INSERT INTO agent_text_chunks (owner_table, owner_id, field, chunk_index, content) VALUES " +
                    "('conversation_context_checkpoints', 'chunked', 'history', ?, ?)", arrayOf<Any>(index, chunk))
            }
            db.execSQL("INSERT INTO conversation_context_checkpoints (conversation_id, history_json, journal_json) VALUES " +
                "('chunked', ?, '')", arrayOf<Any>("${ChunkedTextDao.REFERENCE_PREFIX}${chunks.size}:${chunkedHistory.length}"))
            db.execSQL("INSERT INTO conversation_context_checkpoints (conversation_id, history_json, journal_json) VALUES " +
                "('broken', 'not json', '')")
            db.execSQL("INSERT INTO conversation_context_checkpoints (conversation_id, history_json, journal_json) VALUES " +
                "('journal', '[{\"role\":\"user\",\"content\":\"摘要\",\"contextSummary\":true}]', " +
                "'[{\"role\":\"user\",\"content\":\"一\"},{\"role\":\"user\",\"content\":\"二\"}]')")
        }

        // Room 打开时按第 24 版逐表校验结构（与 MigrationTestHelper 的校验相同）。
        val room = Room.databaseBuilder(context, MovoDatabase::class.java, name)
            .addMigrations(MovoDatabase.MIGRATION_23_24)
            .build()
        runBlocking(Dispatchers.IO) { room.openHelper.writableDatabase }.let { db ->
            fun rows(id: String, log: String): List<Pair<Int, String>> =
                db.query("SELECT seq, message_json FROM conversation_model_messages WHERE conversation_id = ? AND log = ? " +
                    "ORDER BY seq", arrayOf(id, log)).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getInt(0) to cursor.getString(1)) }
                }

            assertEquals(listOf(0 to "{\"role\":\"user\",\"content\":\"打开哔哩哔哩\"}", 1 to "{\"role\":\"assistant\",\"content\":\"已打开\"}"),
                rows("plain", "history"))
            assertEquals(rows("plain", "history"), rows("plain", "journal"))

            val chunked = rows("chunked", "history")
            assertEquals("{\"role\":\"user\",\"content\":\"看看屏幕\"}", chunked[0].second)
            assertEquals(true, chunked[1].second.startsWith(ChunkedTextDao.REFERENCE_PREFIX))
            val restored = db.query("SELECT content FROM agent_text_chunks WHERE owner_table = 'conversation_model_messages' " +
                "AND owner_id = 'chunked/history/1' AND field = 'message' ORDER BY chunk_index").use { cursor ->
                buildString { while (cursor.moveToNext()) append(cursor.getString(0)) }
            }
            assertEquals(longMessage, restored)

            // 解析不了的旧检查点不搬，读取时退回旧数据。
            assertEquals(emptyList<Pair<Int, String>>(), rows("broken", "history"))
            assertEquals(1, rows("journal", "history").size)
            assertEquals(2, rows("journal", "journal").size)

            // 删对话时触发器清掉它的分块。
            db.execSQL("DELETE FROM conversations WHERE id = 'chunked'")
            assertEquals(0, db.query("SELECT COUNT(*) FROM agent_text_chunks WHERE owner_table = 'conversation_model_messages'")
                .use { it.moveToFirst(); it.getInt(0) })
        }
        room.close()
    }
}
