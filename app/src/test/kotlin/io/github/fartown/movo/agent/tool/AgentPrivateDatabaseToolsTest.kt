package io.github.fartown.movo.agent.tool

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.device.BoundedRootCommandExecutor
import io.github.fartown.movo.core.AndroidAgentLogger
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 剪贴板历史按时间窗过滤（personal_search clipboard_history 的 since/until，以前被静默忽略）。 */
@RunWith(RobolectricTestRunner::class)
class AgentPrivateDatabaseToolsTest {
    private val tools = AgentPrivateDatabaseTools(
        ApplicationProvider.getApplicationContext(),
        BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = { false }),
    )
    private val database = SQLiteDatabase.create(null).apply {
        execSQL("CREATE TABLE CLIPBOARD_ITEM (TIME INTEGER, CONTENT TEXT)")
        // 毫秒存的三条，外加一条按秒存的（换成毫秒是 1_700_000_200_000）。
        listOf(
            1_700_000_000_000L to "早上的快递单号",
            1_700_000_100_000L to "中午的会议链接",
            1_700_000_300_000L to "晚上的快递取件码",
            1_700_000_200L to "按秒存的一条",
        ).forEach { (time, content) ->
            execSQL("INSERT INTO CLIPBOARD_ITEM (TIME, CONTENT) VALUES (?, ?)", arrayOf<Any>(time, content))
        }
    }

    @After
    fun close() = database.close()

    private fun search(args: JSONObject): List<String> {
        val json = JSONObject(tools.searchClipboard(database, args))
        assertTrue(json.toString(), json.getBoolean("ok"))
        val items = json.getJSONArray("items")
        return (0 until items.length()).map { items.getJSONObject(it).getString("CONTENT") }
    }

    @Test
    fun withoutTimeWindow_returnsEverythingNewestFirst() {
        assertEquals(4, search(JSONObject().put("limit", 10)).size)
    }

    @Test
    fun sinceAndUntil_filterByTimeInMillis_evenForRowsStoredInSeconds() {
        val window = search(
            JSONObject().put("limit", 10).put("since_millis", 1_700_000_050_000L).put("until_millis", 1_700_000_250_000L),
        )
        assertEquals(listOf("按秒存的一条", "中午的会议链接"), window)
        assertEquals(listOf("晚上的快递取件码"), search(JSONObject().put("since_millis", 1_700_000_250_000L)))
    }

    @Test
    fun timeWindowCombinesWithKeyword() {
        val hits = search(JSONObject().put("query", "快递").put("until_millis", 1_700_000_050_000L))
        assertEquals(listOf("早上的快递单号"), hits)
    }
}
