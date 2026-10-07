package io.github.fartown.movo.agent.tools.personal

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import io.github.fartown.movo.agent.device.AgentNotificationHistoryService
import io.github.fartown.movo.agent.device.BoundedRootCommandExecutor
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.agent.tools.device.AppMatch
import io.github.fartown.movo.core.AndroidAgentLogger
import io.github.fartown.movo.data.repository.NotificationHistoryRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * personal_search 的通知来源与旧版对齐（重构差异 T3 第 20–24 条）：
 * - notification_bar 读通知栏现在挂着的通知（旧 recent_notifications），notifications 是历史，每条标上是否还在通知栏；
 * - 时间窗、应用、关键词都在库里筛，再分页：往前查不会被最新的 50 条挤掉，取满了给 next_cursor；
 * - 结果写明历史只保留 7 天、实际从哪儿查起；订单不给 since 时查最近 7 天，带 sub_text；
 * - app 可以传应用名，按桌面应用名匹配成包名。
 */
@RunWith(RobolectricTestRunner::class)
class PersonalNotificationsBackendTest {
    private lateinit var context: Context
    private lateinit var repository: NotificationHistoryRepository
    private val now = System.currentTimeMillis()
    private val hour = 60L * 60 * 1000

    private var bar: List<ActiveNotification>? = emptyList()
    private val apps = listOf(
        AppMatch("微信", "com.tencent.mm", isSystem = false),
        AppMatch("美团", "com.sankuai.meituan", isSystem = false),
        AppMatch("美团外卖", "com.sankuai.meituan.takeoutnew", isSystem = false),
    )

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("movo_notification_history.db")
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationListenerAccessGranted(
            ComponentName(context, AgentNotificationHistoryService::class.java), true,
        )
        repository = NotificationHistoryRepository(context)
    }

    @After
    fun tearDown() {
        context.deleteDatabase("movo_notification_history.db")
    }

    private fun backend() = AndroidPersonalSearchBackend(
        context,
        BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = { false }),
        rootAvailable = { false },
        barNotifications = { bar },
        installedApps = { apps },
        listenerConnected = { bar != null },
    )

    private fun search(
        source: PersonalSource,
        query: String? = null,
        since: Long? = null,
        until: Long? = null,
        app: String? = null,
        limit: Int = 20,
        cursor: String? = null,
    ) = backend().search(
        PersonalSearchInput(source, query, since, until, app, limit, cursor),
        ToolEnvironment(notificationAccess = true),
    )

    // ---- 通知历史 ----

    @Test
    fun history_untilIsAppliedInTheDatabase_soOlderWindowsAreNotCrowdedOut() {
        // 最近一小时 60 条新通知；要找的那条在 20 小时前。以前先取最新 50 条再按 until 筛，结果是「没找到」。
        repeat(60) { repository.record("new$it", "com.example.chat", "新消息 $it", "内容", null, now - it * 1_000L) }
        repository.record("old", "com.example.bank", "工资到账", "到账 1 元", null, now - 20 * hour)

        val result = search(PersonalSource.NOTIFICATIONS, since = now - 23 * hour, until = now - 19 * hour)

        assertNull(result.error)
        assertEquals(listOf("工资到账"), result.items.map { it.title })
        assertNull(result.nextCursor)
    }

    @Test
    fun history_pagesThroughEverythingInsteadOfStoppingAtFifty() {
        repeat(55) { repository.record("n$it", "com.example.chat", "消息 $it", "内容", null, now - it * 1_000L) }

        val pages = mutableListOf<List<String?>>()
        var cursor: String? = null
        do {
            val page = search(PersonalSource.NOTIFICATIONS, limit = 20, cursor = cursor)
            assertNull(page.error)
            pages += page.items.map { it.title }
            cursor = page.nextCursor
        } while (cursor != null)

        assertEquals(listOf(20, 20, 15), pages.map { it.size })
        assertEquals(55, pages.flatten().distinct().size)
    }

    @Test
    fun history_saysHowFarItReaches_andWarnsWhenAskedForMoreThanSevenDays() {
        repository.record("a", "com.example.chat", "消息", "内容", "副标题", now)

        val result = search(PersonalSource.NOTIFICATIONS, since = now - 10 * 24 * hour)

        assertEquals(NotificationHistoryRepository.RETENTION_DAYS, result.meta!!.getInt("retention_days"))
        assertTrue(result.meta!!.has("since"))
        assertTrue(result.warnings.any { it.message.contains("只保留最近 7 天") })
        assertEquals("副标题", result.items.single().extra!!.getString("sub_text"))
        assertEquals("a", result.items.single().id)
    }

    @Test
    fun history_defaultsToTheLastDay() {
        repository.record("today", "com.example.chat", "今天", "内容", null, now - hour)
        repository.record("twoDaysAgo", "com.example.chat", "前天", "内容", null, now - 48 * hour)

        assertEquals(listOf("今天"), search(PersonalSource.NOTIFICATIONS).items.map { it.title })
    }

    @Test
    fun history_marksWhichNotificationsAreStillInTheBar() {
        repository.record("kept", "com.example.chat", "还在", "内容", null, now)
        repository.record("gone", "com.example.chat", "划掉了", "内容", null, now - 1_000)
        bar = listOf(ActiveNotification("kept", "com.example.chat", "还在", "内容", null, now))

        val items = search(PersonalSource.NOTIFICATIONS).items.associateBy { it.title }

        assertTrue(items.getValue("还在").extra!!.getBoolean("still_in_bar"))
        assertFalse(items.getValue("划掉了").extra!!.getBoolean("still_in_bar"))
    }

    @Test
    fun history_warnsWhenTheListenerIsNotConnected() {
        repository.record("a", "com.example.chat", "消息", "内容", null, now)
        bar = null

        val result = search(PersonalSource.NOTIFICATIONS)

        assertTrue(result.warnings.any { it.message.contains("没连上") })
        assertFalse("不知道是否还在通知栏时不标", result.items.single().extra?.has("still_in_bar") == true)
    }

    // ---- 应用名 ----

    @Test
    fun appName_isResolvedToItsPackage() {
        repository.record("wx", "com.tencent.mm", "妈妈", "晚上回家吃饭吗", null, now)
        repository.record("other", "com.example.chat", "别的", "内容", null, now - 1_000)

        val result = search(PersonalSource.NOTIFICATIONS, app = "微信")

        assertEquals(listOf("妈妈"), result.items.map { it.title })
        assertEquals("com.tencent.mm", result.meta!!.getJSONArray("app_packages").getString(0))
    }

    @Test
    fun packageName_isUsedAsIs_evenIfNotInstalled() {
        repository.record("gone", "com.uninstalled.app", "卸载前的通知", "内容", null, now)

        val result = search(PersonalSource.NOTIFICATIONS, app = "com.uninstalled.app")

        assertEquals(listOf("卸载前的通知"), result.items.map { it.title })
        assertFalse(result.meta!!.has("app_packages"))
    }

    @Test
    fun unknownAppName_isReportedInsteadOfAnEmptyResult() {
        val result = search(PersonalSource.NOTIFICATIONS, app = "不存在的应用")

        assertEquals(ToolErrorCode.NOT_FOUND, result.error!!.code)
        assertTrue(result.error!!.message.contains("不存在的应用"))
    }

    @Test
    fun appFilter_prefersExactNamesThenPartialMatches() {
        assertEquals(listOf("com.sankuai.meituan"), AppFilter.resolve("美团") { apps })
        assertEquals(listOf("com.tencent.mm"), AppFilter.resolve(" 微信 ") { apps })
        assertEquals(listOf("com.sankuai.meituan.takeoutnew"), AppFilter.resolve("外卖") { apps })
        assertEquals(listOf("com.a.b"), AppFilter.resolve("com.a.b") { error("包名不用查已装应用") })
        assertEquals(emptyList<String>(), AppFilter.resolve("抖音") { apps })
        assertTrue(AppFilter.looksLikePackage("com.tencent.mm"))
        assertFalse(AppFilter.looksLikePackage("微信"))
        assertFalse(AppFilter.looksLikePackage("wechat"))
    }

    // ---- 通知栏 ----

    @Test
    fun notificationBar_listsWhatIsInTheBarNow_newestFirst() {
        bar = listOf(
            ActiveNotification("1", "com.tencent.mm", "妈妈", "晚上回家吃饭吗", null, now - 2_000),
            ActiveNotification("2", "com.example.music", "正在播放", "晴天", "周杰伦", now),
            ActiveNotification("3", "android", null, null, null, now - 1_000),
        )
        // 已经划掉的只在历史里，不在通知栏结果里。
        repository.record("old", "com.example.chat", "已划掉", "内容", null, now)

        val result = search(PersonalSource.NOTIFICATION_BAR)

        assertNull(result.error)
        assertEquals(listOf("正在播放", "妈妈"), result.items.map { it.title })
        assertEquals("周杰伦", result.items.first().extra!!.getString("sub_text"))
        assertEquals("com.example.music", result.items.first().from)
    }

    @Test
    fun notificationBar_filtersByAppNameAndQuery() {
        bar = listOf(
            ActiveNotification("1", "com.tencent.mm", "妈妈", "晚上回家吃饭吗", null, now),
            ActiveNotification("2", "com.tencent.mm", "同事", "明天开会", null, now - 1_000),
            ActiveNotification("3", "com.example.chat", "妈妈", "别的应用", null, now - 2_000),
        )

        assertEquals(listOf("同事"), search(PersonalSource.NOTIFICATION_BAR, app = "微信", query = "开会").items.map { it.title })
    }

    @Test
    fun notificationBar_listenerNotConnected_isAnError() {
        bar = null

        val result = search(PersonalSource.NOTIFICATION_BAR)

        assertEquals(ToolErrorCode.SOURCE_UNAVAILABLE, result.error!!.code)
        assertTrue(result.error!!.message.contains("通知服务"))
    }

    @Test
    fun notificationBar_needsNotificationAccess() {
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationListenerAccessGranted(
            ComponentName(context, AgentNotificationHistoryService::class.java), false,
        )

        assertEquals(ToolErrorCode.PERMISSION_REQUIRED, search(PersonalSource.NOTIFICATION_BAR).error!!.code)
    }

    @Test
    fun notificationBar_isAvailableOnlyWithNotificationAccess() {
        assertTrue(PersonalSource.NOTIFICATION_BAR.isAvailable(ToolEnvironment(notificationAccess = true)))
        assertFalse(PersonalSource.NOTIFICATION_BAR.isAvailable(ToolEnvironment(rootAvailable = true)))
    }

    // ---- 订单 ----

    @Test
    fun orders_defaultToTheLastSevenDays_andKeepSubText() {
        repository.record("parcel", "com.cainiao.wireless", "包裹动态", "您的快递已到驿站", "取件码 1234", now - 3 * 24 * hour)
        repository.record("ancient", "com.cainiao.wireless", "快递", "很久以前", null, now - 8 * 24 * hour)

        val result = search(PersonalSource.ORDERS, limit = 10)

        assertEquals(listOf("包裹动态"), result.items.map { it.title })
        assertEquals("取件码 1234", result.items.single().extra!!.getString("sub_text"))
    }

    @Test
    fun orders_areFoundEvenBehindManyNewerNotifications() {
        repeat(80) { repository.record("chat$it", "com.example.chat", "聊天 $it", "内容", null, now - it * 1_000L) }
        repository.record("food", "com.sankuai.meituan", "美团外卖", "骑手已取餐", null, now - 2 * hour)

        val result = search(PersonalSource.ORDERS, limit = 10)

        assertEquals(listOf("美团外卖"), result.items.map { it.title })
    }

    @Test
    fun orders_filterByAppName() {
        repository.record("food", "com.sankuai.meituan", "美团", "您的订单已送达", null, now)
        repository.record("parcel", "com.cainiao.wireless", "包裹动态", "您的快递已到驿站", null, now - 1_000)

        val result = search(PersonalSource.ORDERS, app = "美团", limit = 10)

        assertEquals(listOf("美团"), result.items.map { it.title })
        assertNotNull(result.meta)
    }
}
