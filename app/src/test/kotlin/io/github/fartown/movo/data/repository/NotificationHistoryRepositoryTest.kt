package io.github.fartown.movo.data.repository

import android.content.Context
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NotificationHistoryRepositoryTest {
    private lateinit var context: Context
    private lateinit var repository: NotificationHistoryRepository

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("movo_notification_history.db")
        repository = NotificationHistoryRepository(context)
    }

    @After
    fun tearDown() {
        context.deleteDatabase("movo_notification_history.db")
    }

    @Test
    fun `query and package filters only return matching notification`() {
        val now = System.currentTimeMillis()
        repository.record("one", "com.example.food", "订单配送中", "骑手即将送达", null, now)
        repository.record("two", "com.example.chat", "新消息", "今晚见", null, now - 1)

        val result = JSONObject(repository.search("骑手", "com.example.food", 24, 20))

        assertEquals(1, result.getInt("count"))
        assertEquals("com.example.food", result.getJSONArray("items").getJSONObject(0).getString("package_name"))
    }

    @Test
    fun `same notification key replaces prior content and expired records are excluded`() {
        val now = System.currentTimeMillis()
        repository.record("same", "com.example.food", "旧状态", null, null, now - 1)
        repository.record("same", "com.example.food", "已送达", null, null, now)
        repository.record("expired", "com.example.food", "很久以前", null, null, now - 8L * 24 * 60 * 60 * 1_000)

        val serialized = repository.search("", "", 168, 20)
        val result = JSONObject(serialized)

        assertEquals(1, result.getInt("count"))
        assertFalse(serialized.contains("旧状态"))
        assertFalse(serialized.contains("很久以前"))
    }

    @Test
    fun `rows filter time window apps and keywords in the database and page by offset`() {
        val now = System.currentTimeMillis()
        val hour = 60L * 60 * 1_000
        repository.record("a", "com.example.food", "订单配送中", "骑手即将送达", "副标题", now - 1 * hour)
        repository.record("b", "com.example.chat", "新消息", "今晚见", null, now - 2 * hour)
        repository.record("c", "com.example.food", "快递", "已到驿站", null, now - 3 * hour)
        repository.record("d", "com.example.shop", "上新", "看看吧", null, now - 4 * hour)

        fun keys(query: NotificationHistoryRepository.Query) = repository.rows(query).map { it.key }

        // until 在库里筛：只要 1.5–3.5 小时前的。
        assertEquals(listOf("b", "c"), keys(NotificationHistoryRepository.Query(sinceMillis = now - 7 * hour / 2, untilMillis = now - 3 * hour / 2, limit = 10)))
        // 多个包名。
        assertEquals(listOf("a", "c", "d"), keys(NotificationHistoryRepository.Query(sinceMillis = now - 5 * hour, packages = listOf("com.example.food", "com.example.shop"), limit = 10)))
        // 含任一关键词（标题、正文、副标题）。
        assertEquals(listOf("a", "c"), keys(NotificationHistoryRepository.Query(sinceMillis = now - 5 * hour, anyKeywords = listOf("骑手", "驿站"), limit = 10)))
        // 关键词与任一关键词同时给时都要满足。
        assertEquals(listOf("c"), keys(NotificationHistoryRepository.Query(sinceMillis = now - 5 * hour, text = "快递", anyKeywords = listOf("骑手", "驿站"), limit = 10)))
        // 偏移翻页。
        assertEquals(listOf("c", "d"), keys(NotificationHistoryRepository.Query(sinceMillis = now - 5 * hour, offset = 2, limit = 10)))
        val first = repository.rows(NotificationHistoryRepository.Query(sinceMillis = now - 5 * hour, limit = 1)).single()
        assertEquals("副标题", first.subText)
        assertEquals("com.example.food", first.packageName)
    }
}
