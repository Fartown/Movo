package io.github.fartown.movo.data.repository

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/** 仅在用户授予通知访问后保存有限期通知，内容不进入 Agent 会话持久记录。 */
internal class NotificationHistoryRepository(context: Context) {
    private val database = Database(context.applicationContext)

    fun record(
        key: String,
        packageName: String,
        title: String?,
        text: String?,
        subText: String?,
        postedAt: Long,
    ) {
        if (title.isNullOrBlank() && text.isNullOrBlank() && subText.isNullOrBlank()) return
        val values = ContentValues().apply {
            put("notification_key", key.take(MAX_KEY_CHARS))
            put("package_name", packageName.take(MAX_PACKAGE_CHARS))
            put("title", title.bounded())
            put("text", text.bounded())
            put("sub_text", subText.bounded())
            put("posted_at", postedAt)
        }
        database.writableDatabase.transaction {
            insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            delete(TABLE, "posted_at<?", arrayOf((System.currentTimeMillis() - RETENTION_MS).toString()))
            execSQL(
                "DELETE FROM $TABLE WHERE notification_key NOT IN " +
                    "(SELECT notification_key FROM $TABLE ORDER BY posted_at DESC LIMIT $MAX_RECORDS)",
            )
        }
    }

    /** 一条记下的通知。[key] 是系统的通知 key（同一条通知更新时 key 不变，库里只留最新内容）。 */
    data class Row(
        val key: String,
        val packageName: String,
        val title: String?,
        val text: String?,
        val subText: String?,
        val postedAt: Long,
    )

    /**
     * 查询条件。时间窗、应用、关键词都在 SQL 里筛（不在取回的一页里再筛，否则较早的时间窗会被误报成「没找到」）；
     * [anyKeywords] 是「标题、正文、副标题里含其中任一个词」，[text] 是用户给的关键词，两者同时给时都要满足。
     * [after] 是翻页位置：只取排在它后面的。
     */
    data class Query(
        val sinceMillis: Long,
        val untilMillis: Long? = null,
        val text: String = "",
        val packages: List<String> = emptyList(),
        val anyKeywords: List<String> = emptyList(),
        val after: Position? = null,
        val limit: Int,
    )

    /** 排序（时间倒序，同一时间按 key）里的一个位置：上一页最后一条的时间和 key。 */
    data class Position(val postedAt: Long, val key: String)

    /** 按 [Query] 查，最新的在前。 */
    fun rows(query: Query): List<Row> {
        val (selection, args) = whereClause(query)
        val rows = mutableListOf<Row>()
        database.readableDatabase.query(
            TABLE,
            arrayOf("notification_key", "package_name", "title", "text", "sub_text", "posted_at"),
            selection,
            args,
            null,
            null,
            "posted_at DESC, notification_key ASC",
            query.limit.coerceAtLeast(1).toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += Row(
                    key = cursor.getString(0),
                    packageName = cursor.getString(1),
                    title = cursor.getString(2),
                    text = cursor.getString(3),
                    subText = cursor.getString(4),
                    postedAt = cursor.getLong(5),
                )
            }
        }
        return rows
    }

    /** 符合 [Query] 的有几条（不看 limit）。 */
    fun count(query: Query): Int {
        val (selection, args) = whereClause(query)
        return database.readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE WHERE $selection", args).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun whereClause(query: Query): Pair<String, Array<String>> {
        val clauses = mutableListOf("posted_at>=?")
        val args = mutableListOf(query.sinceMillis.toString())
        query.untilMillis?.let {
            clauses += "posted_at<=?"
            args += it.toString()
        }
        val packages = query.packages.filter { it.isNotBlank() }
        if (packages.isNotEmpty()) {
            clauses += "package_name IN (${packages.joinToString(",") { "?" }})"
            args += packages
        }
        if (query.text.isNotBlank()) {
            clauses += LIKE_ANY_FIELD
            val pattern = "%${query.text.escapeLike()}%"
            repeat(3) { args += pattern }
        }
        val keywords = query.anyKeywords.filter { it.isNotBlank() }
        if (keywords.isNotEmpty()) {
            clauses += keywords.joinToString(" OR ", prefix = "(", postfix = ")") { LIKE_ANY_FIELD }
            keywords.forEach { keyword ->
                val pattern = "%${keyword.escapeLike()}%"
                repeat(3) { args += pattern }
            }
        }
        // 排序是 posted_at DESC, notification_key ASC：排在位置后面 = 更早，或同一时间 key 更大。
        query.after?.let {
            clauses += "(posted_at<? OR (posted_at=? AND notification_key>?))"
            args += listOf(it.postedAt.toString(), it.postedAt.toString(), it.key)
        }
        return clauses.joinToString(" AND ") to args.toTypedArray()
    }

    fun search(query: String, packageName: String, maxAgeHours: Int, limit: Int): String {
        val found = rows(
            Query(
                sinceMillis = System.currentTimeMillis() - maxAgeHours * HOUR_MS,
                text = query,
                packages = listOf(packageName),
                limit = limit,
            ),
        )
        val items = JSONArray()
        found.forEach { row ->
            items.put(
                JSONObject()
                    .put("package_name", row.packageName)
                    .put("title", row.title)
                    .put("text", row.text)
                    .put("sub_text", row.subText)
                    .put("posted_at", row.postedAt),
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("tool", "search_notification_history")
            .put("items", items)
            .put("count", items.length())
            .put("retention_days", RETENTION_DAYS)
            .put("truncated", items.length() == limit)
            .toString()
    }

    private fun SQLiteDatabase.transaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    private fun String?.bounded(): String? = this?.take(MAX_FIELD_CHARS)

    private fun String.escapeLike(): String =
        replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private class Database(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE (" +
                    "notification_key TEXT PRIMARY KEY NOT NULL," +
                    "package_name TEXT NOT NULL," +
                    "title TEXT,text TEXT,sub_text TEXT,posted_at INTEGER NOT NULL)",
            )
            db.execSQL("CREATE INDEX notification_history_posted_at ON $TABLE(posted_at DESC)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    companion object {
        /** 只保留最近这么多天的通知（授权通知使用权之后开始记）。 */
        const val RETENTION_DAYS = 7

        /** 库里最多留这么多条，更早的删掉。 */
        const val MAX_RECORDS = 1_000

        private const val DATABASE_NAME = "movo_notification_history.db"
        private const val TABLE = "notification_history"
        private const val HOUR_MS = 60L * 60 * 1_000
        private const val LIKE_ANY_FIELD =
            "(title LIKE ? ESCAPE '\\' OR text LIKE ? ESCAPE '\\' OR sub_text LIKE ? ESCAPE '\\')"
        private const val RETENTION_MS = RETENTION_DAYS * 24L * HOUR_MS
        private const val MAX_FIELD_CHARS = 4_000
        private const val MAX_KEY_CHARS = 1_000
        private const val MAX_PACKAGE_CHARS = 255
    }
}
