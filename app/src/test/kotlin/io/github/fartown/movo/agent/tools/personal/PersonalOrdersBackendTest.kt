package io.github.fartown.movo.agent.tools.personal

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import io.github.fartown.movo.agent.device.AgentNotificationHistoryService
import io.github.fartown.movo.agent.device.BoundedRootCommandExecutor
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.core.AndroidAgentLogger
import io.github.fartown.movo.data.repository.NotificationHistoryRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * personal_search(orders) 从通知里认订单（审计 C9）：不带关键词时只给像订单的通知
 * （恢复旧 ORDER_KEYWORDS 过滤，以前 24 小时内的所有通知都被当成订单），带关键词时按关键词查、不再二次过滤。
 */
@RunWith(RobolectricTestRunner::class)
class PersonalOrdersBackendTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("movo_notification_history.db")
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationListenerAccessGranted(
            ComponentName(context, AgentNotificationHistoryService::class.java), true,
        )
        val now = System.currentTimeMillis()
        NotificationHistoryRepository(context).apply {
            record("food", "com.sankuai.meituan", "美团外卖", "骑手已取餐，预计 12:30 送达", null, now)
            record("parcel", "com.cainiao.wireless", "包裹动态", "您的快递已到驿站", null, now - 1_000)
            record("ticket", "com.example.trip", "出行提醒", "行程即将开始", "车票 G102 08:00 发车", now - 2_000)
            record("chat", "com.tencent.mm", "妈妈", "晚上回家吃饭吗", null, now - 3_000)
            record("system", "android", "系统更新", "新版本可用", null, now - 4_000)
        }
    }

    @After
    fun tearDown() {
        context.deleteDatabase("movo_notification_history.db")
    }

    private fun search(query: String?): PersonalSearchResult {
        val noRoot = BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = { false })
        return AndroidPersonalSearchBackend(context, noRoot, rootAvailable = { false }).search(
            PersonalSearchInput(
                source = PersonalSource.ORDERS, query = query, sinceMillis = null, untilMillis = null,
                app = null, limit = 10, cursor = null,
            ),
            ToolEnvironment(notificationAccess = true),
        )
    }

    @Test
    fun withoutQuery_onlyOrderLikeNotifications() {
        val result = search(query = null)
        assertNull(result.error)
        val titles = result.items.map { it.title }
        assertEquals(listOf("美团外卖", "包裹动态", "出行提醒"), titles)
        assertFalse("聊天、系统通知不是订单", titles.contains("妈妈") || titles.contains("系统更新"))
        // 没有 Root 时系统记忆来源照常报警告，通知来源照常给结果。
        assertTrue(result.warnings.any { it.code == ToolErrorCode.ROOT_REQUIRED })
    }

    @Test
    fun blankQuery_isTheSameAsNoQuery() {
        assertEquals(3, search(query = "  ").items.size)
    }

    @Test
    fun withQuery_matchesTheQueryWithoutTheKeywordFilter() {
        // 给了关键词就按关键词查：用户点名要找的通知，即使不含订单字眼也要给。
        assertEquals(listOf("妈妈"), search(query = "回家").items.map { it.title })
    }

    @Test
    fun orderKeywords_lookAtTitleTextAndSubText() {
        assertTrue(OrderNotifications.looksLikeOrder("美团", "您的订单已送达", null))
        assertTrue(OrderNotifications.looksLikeOrder(null, null, "电影票已出票"))
        assertFalse(OrderNotifications.looksLikeOrder("妈妈", "晚上回家吃饭吗", null))
        assertFalse(OrderNotifications.looksLikeOrder(null, null, null))
    }
}
