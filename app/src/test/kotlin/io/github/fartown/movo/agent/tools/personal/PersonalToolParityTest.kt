package io.github.fartown.movo.agent.tools.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.device.BoundedRootCommandExecutor
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.tools.core.ContractTool
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolPipeline
import io.github.fartown.movo.agent.tools.core.ToolProvider
import io.github.fartown.movo.agent.tools.core.ToolRegistry
import io.github.fartown.movo.core.AndroidAgentLogger
import io.github.fartown.movo.data.repository.NotificationHistoryRepository
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 个人数据工具与旧版对齐的其余几处（重构差异 T3 第 21、23、25 条）。 */
@RunWith(RobolectricTestRunner::class)
class PersonalToolParityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun pipeline(tool: ContractTool<*, *>, env: ToolEnvironment) = ToolPipeline(
        registry = ToolRegistry(listOf(object : ToolProvider { override val tools = listOf(tool) })),
        environment = { env },
        appContext = context,
        logger = AndroidAgentLogger,
        runId = "run1",
        cancelled = { false },
    ).also { it.catalog() }

    private fun call(name: String, args: String) = AgentModelClient.ToolCall("c1", name, args)

    // ---- usage_read recent ----

    private fun resumed(pkg: String, at: Long, activity: String = "$pkg.Main") = ResumedActivity(pkg, activity, at)

    @Test
    fun recent_byPackage_listsEveryOpening_newestFirst() {
        // 抖音打开三次（中间换过页面、切到过别的应用）。以前按包名筛后只剩最早那一次。
        val events = listOf(
            resumed("douyin", 1_000),
            resumed("douyin", 1_500, "douyin.Detail"),
            resumed("launcher", 2_000),
            resumed("douyin", 3_000),
            resumed("wechat", 4_000),
            resumed("douyin", 5_000),
        )

        val openings = RecentOpenings.of(events, packageName = "douyin", limit = 20)

        assertEquals(listOf(5_000L, 3_000L, 1_000L), openings.map { it.timeMillis })
        assertEquals("douyin.Main", openings.last().activity)
    }

    @Test
    fun recent_withoutPackage_mergesConsecutiveResumesOfTheSameApp() {
        val events = listOf(resumed("a", 1), resumed("a", 2), resumed("b", 3), resumed("a", 4), resumed("a", 5))

        assertEquals(listOf("a" to 4L, "b" to 3L, "a" to 1L), RecentOpenings.of(events, null, 20).map { it.packageName to it.timeMillis })
        assertEquals(listOf(4L, 3L), RecentOpenings.of(events, null, 2).map { it.timeMillis })
    }

    @Test
    fun usageRead_saysWhichWindowItCovered() {
        val backend = object : UsageReadBackend {
            override fun available(env: ToolEnvironment) = true
            override fun recent(startMillis: Long, endMillis: Long, packageName: String?, limit: Int) = emptyList<UsageItem>()
            override fun summary(startMillis: Long, endMillis: Long, packageName: String?, limit: Int) = emptyList<UsageItem>()
        }
        val result = pipeline(ContractTool(UsageReadTool(backend)), ToolEnvironment(usageAccess = true))
            .execute(call("usage_read", """{"view":"recent"}"""))
        val data = JSONObject(result.content).getJSONObject("data")

        assertEquals(24, data.getInt("window_hours"))
        assertTrue(data.has("since"))
        assertTrue(data.has("until"))
    }

    // ---- personal_search 默认条数、两个通知来源 ----

    @Test
    fun personalSearch_defaultsToTwentyItems_andNamesBothNotificationSources() {
        var seen: PersonalSearchInput? = null
        val backend = object : PersonalSearchBackend {
            override fun search(input: PersonalSearchInput, env: ToolEnvironment): PersonalSearchResult {
                seen = input
                return PersonalSearchResult(meta = JSONObject().put("retention_days", 7))
            }
        }
        val tool = ContractTool(PersonalSearchTool(backend))
        val env = ToolEnvironment(notificationAccess = true)
        val result = pipeline(tool, env).execute(call("personal_search", """{"source":"notification_bar","app":"微信"}"""))

        assertEquals("ok", result.status)
        assertEquals(20, seen!!.limit)
        assertEquals("微信", seen!!.app)
        assertEquals(PersonalSource.NOTIFICATION_BAR, seen!!.source)
        assertEquals(7, JSONObject(result.content).getJSONObject("data").getInt("retention_days"))

        val properties = tool.parameters(env).getJSONObject("properties")
        val source = properties.getJSONObject("source")
        val sources = (0 until source.getJSONArray("enum").length()).map { source.getJSONArray("enum").getString(it) }
        assertTrue(sources.containsAll(listOf("notifications", "notification_bar", "orders")))
        val description = source.getString("description")
        assertTrue(description, description.contains("含已划掉的") && description.contains("现在还挂着"))
        assertTrue(properties.getJSONObject("app").getString("description").contains("应用名"))
    }

    @Test
    fun personalSearch_rootOnly_hasNoNotificationBar() {
        val tool = ContractTool(PersonalSearchTool(object : PersonalSearchBackend {
            override fun search(input: PersonalSearchInput, env: ToolEnvironment) = PersonalSearchResult()
        }))
        val source = tool.parameters(ToolEnvironment(rootAvailable = true)).getJSONObject("properties").getJSONObject("source")
        val sources = (0 until source.getJSONArray("enum").length()).map { source.getJSONArray("enum").getString(it) }
        assertFalse(sources.contains("notification_bar"))
    }

    // ---- sms_code_read ----

    @Test
    fun smsCode_withoutRoot_readsCodesUpToADayBack_behindManyNewerNotifications() {
        context.deleteDatabase("movo_notification_history.db")
        val repository = NotificationHistoryRepository(context)
        val now = System.currentTimeMillis()
        repository.record("otp", "com.android.mms", "106xxxx", "【银行】您的验证码是 482913，5 分钟内有效", null, now - 3 * 60 * 60 * 1000L)
        repeat(80) { repository.record("chat$it", "com.example.chat", "聊天 $it", "内容", null, now - it * 1_000L) }
        val backend = AndroidSmsCodeBackend(
            root = BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = { false }),
            rootAvailable = { false },
            notificationHistory = repository,
            notificationAvailable = { true },
        )
        try {
            val codes = backend.readCodes(now - 24 * 60 * 60 * 1000L, ToolEnvironment(notificationAccess = true))!!
            assertEquals(listOf("482913"), codes.map { it.code })
            // 时间窗之外的不算。
            assertEquals(emptyList<SmsCode>(), backend.readCodes(now - 60 * 60 * 1000L, ToolEnvironment(notificationAccess = true)))
        } finally {
            context.deleteDatabase("movo_notification_history.db")
        }
    }

    @Test
    fun smsCode_acceptsUpToADay() {
        var cutoff = 0L
        val backend = object : SmsCodeBackend {
            override fun available(env: ToolEnvironment) = true
            override fun readCodes(cutoffMillis: Long, env: ToolEnvironment): List<SmsCode> {
                cutoff = cutoffMillis
                return emptyList()
            }
        }
        val tool = ContractTool(SmsCodeReadTool(backend))
        val env = ToolEnvironment(notificationAccess = true)
        val result = pipeline(tool, env).execute(call("sms_code_read", """{"max_age_minutes":1440}"""))

        assertEquals("ok", result.status)
        val ageMinutes = (System.currentTimeMillis() - cutoff) / 60_000L
        assertTrue("应查最近 24 小时，实际 $ageMinutes 分钟", ageMinutes in 1439L..1441L)
        assertEquals(1440, tool.parameters(env).getJSONObject("properties").getJSONObject("max_age_minutes").getInt("maximum"))
    }
}
