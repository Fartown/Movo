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
    private var apps = listOf(
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

    // 翻完所有页：返回每页的标题和最后一页的提醒。
    private fun allPages(limit: Int, between: (page: Int) -> Unit = {}): Pair<List<List<String?>>, List<String>> {
        val pages = mutableListOf<List<String?>>()
        val warnings = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = search(PersonalSource.NOTIFICATIONS, limit = limit, cursor = cursor)
            assertNull(page.error)
            pages += page.items.map { it.title }
            warnings += page.warnings.map { it.message }
            cursor = page.nextCursor
            if (cursor != null) between(pages.size)
        } while (cursor != null)
        return pages to warnings
    }

    @Test
    fun history_newNotificationsWhilePaging_doNotRepeatTheLastPage() {
        repeat(30) { repository.record("n$it", "com.example.chat", "消息 $it", "内容", null, now - (it + 1) * 1_000L) }

        // 翻完第一页后来了 5 条新通知：按偏移翻页时第二页开头会重复第一页末尾的 5 条。
        val (pages, warnings) = allPages(limit = 10) { page ->
            if (page == 1) repeat(5) { repository.record("new$it", "com.example.chat", "新消息 $it", "内容", null, System.currentTimeMillis() + 1_000 + it) }
        }

        assertEquals((0 until 30).map { "消息 $it" }, pages.flatten())
        assertTrue(warnings.toString(), warnings.any { it.contains("翻页期间有 5 条通知") })
    }

    @Test
    fun history_notificationUpdatedWhilePaging_isNotRepeated_andIsMentioned() {
        repeat(30) { repository.record("n$it", "com.example.chat", "消息 $it", "内容", null, now - (it + 1) * 1_000L) }

        // 翻完第一页后，第 15 条（还没看到）和第 3 条（已经看过）都更新了，排到了最前面。
        val (pages, warnings) = allPages(limit = 10) { page ->
            if (page == 1) {
                repository.record("n15", "com.example.chat", "消息 15（已更新）", "内容", null, System.currentTimeMillis() + 1_000)
                repository.record("n3", "com.example.chat", "消息 3（已更新）", "内容", null, System.currentTimeMillis() + 1_001)
            }
        }

        val seen = pages.flatten()
        assertEquals("不重复", seen.size, seen.distinct().size)
        assertEquals((0 until 30).filter { it != 15 }.map { "消息 $it" }, seen)
        // 没看到的那条不悄悄漏掉：提醒里说了有更新的，要看就重新查。
        assertTrue(warnings.toString(), warnings.any { it.contains("翻页期间有 2 条通知") && it.contains("去掉 cursor") })
    }

    @Test
    fun history_sameTimestampAcrossPages_isNeitherRepeatedNorSkipped() {
        repeat(25) { repository.record("k${it.toString().padStart(2, '0')}", "com.example.chat", "同一刻 $it", "内容", null, now - 1_000) }

        val (pages, warnings) = allPages(limit = 10)

        assertEquals(listOf(10, 10, 5), pages.map { it.size })
        assertEquals((0 until 25).map { "同一刻 $it" }.toSet(), pages.flatten().toSet())
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun history_cursorFromAnotherQuery_isStale() {
        repeat(5) { repository.record("n$it", "com.example.chat", "消息 $it", "内容", null, now - it * 1_000L) }
        val cursor = search(PersonalSource.NOTIFICATIONS, limit = 2).nextCursor!!

        assertEquals(ToolErrorCode.STALE_OBSERVATION, search(PersonalSource.NOTIFICATIONS, query = "别的", limit = 2, cursor = cursor).error!!.code)
        // 以前的偏移游标也不再认。
        val offsetCursor = PersonalCursor.encode(PersonalSearchInput(PersonalSource.NOTIFICATIONS, null, null, null, null, 2, null), 2)
        assertEquals(ToolErrorCode.STALE_OBSERVATION, search(PersonalSource.NOTIFICATIONS, limit = 2, cursor = offsetCursor).error!!.code)
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
        assertTrue(result.meta!!.getString("app_note").contains("按包名 com.uninstalled.app 查"))
    }

    @Test
    fun installedPackageName_isUsedDirectly() {
        repository.record("wx", "com.tencent.mm", "妈妈", "晚上回家吃饭吗", null, now)

        val result = search(PersonalSource.NOTIFICATIONS, app = "com.tencent.mm")

        assertEquals(listOf("妈妈"), result.items.map { it.title })
        assertFalse(result.meta!!.has("app_packages"))
        assertFalse(result.meta!!.has("app_note"))
    }

    @Test
    fun appNamesThatLookLikePackages_areMatchedByName() {
        apps = apps + listOf(
            AppMatch("Booking.com", "com.booking", isSystem = false),
            AppMatch("Trip.com: 机票酒店", "ctrip.english", isSystem = false),
        )
        repository.record("hotel", "com.booking", "预订确认", "您的酒店已确认", null, now)
        repository.record("flight", "ctrip.english", "航班提醒", "明天 8:00 起飞", null, now - 1_000)

        val booking = search(PersonalSource.NOTIFICATIONS, app = "Booking.com")
        assertEquals(listOf("预订确认"), booking.items.map { it.title })
        assertEquals("com.booking", booking.meta!!.getJSONArray("app_packages").getString(0))

        val trip = search(PersonalSource.NOTIFICATIONS, app = "Trip.com")
        assertEquals(listOf("航班提醒"), trip.items.map { it.title })
        assertEquals("ctrip.english", trip.meta!!.getJSONArray("app_packages").getString(0))
    }

    private val banks = (1..12).map { AppMatch("第${it}银行", "com.bank$it", isSystem = false) }

    @Test
    fun appNameMatchingManyApps_saysWhichWereLeftOut() {
        apps = banks
        repository.record("b1", "com.bank1", "第1银行", "工资到账", null, now)
        repository.record("b12", "com.bank12", "第12银行", "信用卡账单", null, now - 1_000)

        val result = search(PersonalSource.NOTIFICATIONS, app = "银行")

        val packages = result.meta!!.getJSONArray("app_packages")
        assertEquals(AppFilter.MAX_PARTIAL, packages.length())
        assertEquals(listOf("第1银行"), result.items.map { it.title })
        val warning = result.warnings.single { it.code == ToolErrorCode.AMBIGUOUS }.message
        assertTrue(warning, warning.contains("有 12 个") && warning.contains("第11银行（com.bank11）") && warning.contains("第12银行（com.bank12）"))
    }

    @Test
    fun orders_manyMatchingApps_isAWarningNotAnError() {
        apps = banks

        val result = search(PersonalSource.ORDERS, app = "银行", limit = 10)

        assertNull(result.error)
        assertTrue(result.warnings.any { it.code == ToolErrorCode.AMBIGUOUS })
    }

    @Test
    fun unknownAppName_isReportedInsteadOfAnEmptyResult() {
        val result = search(PersonalSource.NOTIFICATIONS, app = "不存在的应用")

        assertEquals(ToolErrorCode.NOT_FOUND, result.error!!.code)
        assertTrue(result.error!!.message.contains("不存在的应用"))
    }

    @Test
    fun appFilter_installedPackageThenNamesThenRawPackage() {
        fun resolve(app: String) = AppFilter.resolve(app) { apps }

        assertEquals(AppFilter.Match(listOf("com.tencent.mm"), AppFilter.By.INSTALLED_PACKAGE), resolve("com.tencent.mm"))
        assertEquals(AppFilter.Match(listOf("com.sankuai.meituan"), AppFilter.By.NAME), resolve("美团"))
        assertEquals(AppFilter.Match(listOf("com.tencent.mm"), AppFilter.By.NAME), resolve(" 微信 "))
        assertEquals(AppFilter.Match(listOf("com.sankuai.meituan.takeoutnew"), AppFilter.By.NAME), resolve("外卖"))
        assertEquals(AppFilter.Match(listOf("com.a.b"), AppFilter.By.PACKAGE_NOT_INSTALLED), resolve("com.a.b"))
        assertEquals(AppFilter.By.NONE, resolve("抖音").by)
        // 读不到已装应用时，像包名的仍按包名查。
        assertEquals(listOf("com.a.b"), AppFilter.resolve("com.a.b") { error("读不到") }.packages)

        val many = AppFilter.resolve("银行") { banks }
        assertEquals(banks.take(AppFilter.MAX_PARTIAL).map { it.packageName }, many.packages)
        assertEquals(banks.drop(AppFilter.MAX_PARTIAL), many.skipped)

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
