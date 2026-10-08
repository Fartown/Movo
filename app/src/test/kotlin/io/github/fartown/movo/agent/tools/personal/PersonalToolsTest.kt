package io.github.fartown.movo.agent.tools.personal

import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.tools.core.ApprovalPolicy
import io.github.fartown.movo.agent.tools.core.ApprovalDecision
import io.github.fartown.movo.agent.tools.core.ApprovalRequest
import io.github.fartown.movo.agent.tools.core.ContractTool
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolPipeline
import io.github.fartown.movo.agent.tools.core.ToolProvider
import io.github.fartown.movo.agent.tools.core.ToolRegistry
import io.github.fartown.movo.agent.tools.core.UserAnswer
import io.github.fartown.movo.agent.tools.core.UserInteraction
import io.github.fartown.movo.agent.tools.core.UserQuestion
import io.github.fartown.movo.core.AndroidAgentLogger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 个人数据领域工具的合同接线验证：假后端经 ToolPipeline 跑通，断言 verdict、敏感度、打码与审批。 */
@RunWith(RobolectricTestRunner::class)
class PersonalToolsTest {

    private val fullEnv = ToolEnvironment(
        rootAvailable = true,
        colorOs = true,
        notificationAccess = true,
        usageAccess = true,
    )

    // ---- 假后端 ----

    private val searchBackend = object : PersonalSearchBackend {
        override fun search(input: PersonalSearchInput, env: ToolEnvironment): PersonalSearchResult {
            val text = when (input.source) {
                PersonalSource.SMS, PersonalSource.NOTIFICATIONS -> "你的验证码是 123456，请勿泄露"
                else -> "示例内容"
            }
            val next = if (input.query == "page") "CURSOR" else null
            return PersonalSearchResult(
                items = listOf(
                    PersonalItem(id = "1", timeMillis = 1_700_000_000_000L, title = "标题", text = text, from = "10086", uri = null),
                ),
                nextCursor = next,
            )
        }
    }

    private val smsCodeBackend = object : SmsCodeBackend {
        override fun available(env: ToolEnvironment) = env.rootAvailable
        override fun readCodes(cutoffMillis: Long, env: ToolEnvironment) =
            listOf(SmsCode(code = "123456", from = "10086", timeMillis = 1_700_000_000_000L))
    }

    private val usageBackend = object : UsageReadBackend {
        override fun available(env: ToolEnvironment) = env.usageAccess
        override fun recent(startMillis: Long, endMillis: Long, packageName: String?, limit: Int) =
            listOf(UsageItem(packageName = "com.demo", appName = "Demo", activity = "Main", resumedAtMillis = 1_700_000_000_000L))
        override fun summary(startMillis: Long, endMillis: Long, packageName: String?, limit: Int) =
            listOf(UsageItem(packageName = "com.demo", appName = "Demo", foregroundMs = 60_000L, lastUsedAtMillis = 1_700_000_000_000L))
    }

    private val healthBackend = object : HealthReadBackend {
        override fun available(env: ToolEnvironment) = env.rootAvailable
        override fun summarize(days: Int, env: ToolEnvironment) =
            HealthReadResult.Ok(summary = JSONObject().put("steps", JSONObject().put("count", 8000)), hasData = true)
    }

    private val wifiBackend = object : WifiPasswordReadBackend {
        override fun read(ssidFilter: String?, limit: Int) =
            listOf(WifiNetwork(ssid = "Home-5G", password = "secret123"), WifiNetwork(ssid = "Open", password = null))
    }

    private fun provider() = object : ToolProvider {
        override val tools = listOf(
            ContractTool(PersonalSearchTool(searchBackend)),
            ContractTool(SmsCodeReadTool(smsCodeBackend)),
            ContractTool(UsageReadTool(usageBackend)),
            ContractTool(HealthReadTool(healthBackend)),
            ContractTool(WifiPasswordReadTool(wifiBackend)),
        )
    }

    private fun pipeline(env: ToolEnvironment, interaction: UserInteraction = UserInteraction.NONE) = ToolPipeline(
        registry = ToolRegistry(listOf(provider())),
        environment = { env },
        appContext = ApplicationProvider.getApplicationContext(),
        logger = AndroidAgentLogger,
        runId = "run1",
        cancelled = { false },
        interaction = interaction,
    ).also { it.catalog() }

    private val approveAll = object : UserInteraction {
        override val available = true
        override fun ask(question: UserQuestion, timeoutMs: Long) = UserAnswer.Declined
        override fun approve(request: ApprovalRequest, timeoutMs: Long) = ApprovalDecision.Approved
    }

    private val declineAll = object : UserInteraction {
        override val available = true
        override fun ask(question: UserQuestion, timeoutMs: Long) = UserAnswer.Declined
        override fun approve(request: ApprovalRequest, timeoutMs: Long) = ApprovalDecision.Declined
    }

    private fun call(name: String, args: String) = AgentModelClient.ToolCall("c1", name, args)

    // ---- personal_search ----

    @Test
    fun personalSearch_notifications_masksVerificationCode_andIsSensitive() {
        val p = pipeline(fullEnv)
        val result = p.execute(call("personal_search", """{"source":"notifications"}"""))
        val json = JSONObject(result.content)
        assertEquals("ok", json.getString("status"))
        assertTrue(result.sensitive)                      // private
        assertFalse(json.has("effect_verified"))          // 只读不带 effect_verified
        val text = json.getJSONObject("data").getJSONArray("items").getJSONObject(0).getString("text")
        assertTrue("验证码应被打码", text.contains("******"))
        assertFalse("不得泄露原始验证码", text.contains("123456"))
    }

    @Test
    fun personalSearch_masksVerificationCodesInSubText_too() {
        val subTexts = listOf("验证码 654321，5 分钟内有效", "骑手已取餐 1234")
        val backend = object : PersonalSearchBackend {
            override fun search(input: PersonalSearchInput, env: ToolEnvironment) = PersonalSearchResult(
                items = subTexts.mapIndexed { index, subText ->
                    PersonalItem(
                        id = "$index", timeMillis = 1_700_000_000_000L, title = "通知", text = "内容", from = "com.example.bank", uri = null,
                        extra = JSONObject().put("sub_text", subText).put("still_in_bar", true),
                    )
                },
            )
        }
        val p = ToolPipeline(
            registry = ToolRegistry(listOf(object : ToolProvider { override val tools = listOf(ContractTool(PersonalSearchTool(backend))) })),
            environment = { fullEnv },
            appContext = ApplicationProvider.getApplicationContext(),
            logger = AndroidAgentLogger,
            runId = "run1",
            cancelled = { false },
        ).also { it.catalog() }

        for (source in listOf("notification_bar", "notifications", "orders")) {
            val result = p.execute(call("personal_search", """{"source":"$source"}"""))
            assertFalse(source, result.content.contains("654321"))
            val items = JSONObject(result.content).getJSONObject("data").getJSONArray("items")
            val coded = items.getJSONObject(0).getJSONObject("extra")
            assertEquals("验证码 ******，5 分钟内有效", coded.getString("sub_text"))
            assertTrue(coded.getBoolean("still_in_bar"))
            // 没有验证码语境词的副标题原样给（和标题、正文的打码条件一样）。
            assertEquals("骑手已取餐 1234", items.getJSONObject(1).getJSONObject("extra").getString("sub_text"))
        }
    }

    @Test
    fun personalSearch_clipboardHistory_isSecret() {
        val p = pipeline(fullEnv)
        val result = p.execute(call("personal_search", """{"source":"clipboard_history"}"""))
        assertEquals("ok", JSONObject(result.content).getString("status"))
        assertTrue(result.sensitive)
    }

    @Test
    fun personalSearch_sinceOnUnsupportedSource_invalidArguments() {
        val p = pipeline(fullEnv)
        val result = p.execute(call("personal_search", """{"source":"contacts","since":"2026-01-01T00:00:00Z"}"""))
        assertEquals("error", result.status)
        assertEquals("INVALID_ARGUMENTS", result.errorCode)
    }

    @Test
    fun personalSearch_colorOsMemory_timeFilterRejected() {
        // #18：系统记忆的查询没有时间条件，since/until 以前被静默忽略；现在不再声称支持。
        val p = pipeline(fullEnv)
        val result = p.execute(call("personal_search", """{"source":"coloros_memory","since":"2026-01-01T00:00:00Z"}"""))
        assertEquals("error", result.status)
        assertEquals("INVALID_ARGUMENTS", result.errorCode)
    }

    @Test
    fun personalSearch_clipboardHistory_timeFilterPassedToBackend() {
        var seen: PersonalSearchInput? = null
        val recording = object : PersonalSearchBackend {
            override fun search(input: PersonalSearchInput, env: ToolEnvironment): PersonalSearchResult {
                seen = input
                return PersonalSearchResult()
            }
        }
        val p = ToolPipeline(
            registry = ToolRegistry(listOf(object : ToolProvider {
                override val tools = listOf(ContractTool(PersonalSearchTool(recording)))
            })),
            environment = { fullEnv },
            appContext = ApplicationProvider.getApplicationContext(),
            logger = AndroidAgentLogger,
            runId = "run1",
            cancelled = { false },
        ).also { it.catalog() }
        val result = p.execute(call("personal_search", """{"source":"clipboard_history","since":"1000","until":"2000"}"""))
        assertEquals("ok", JSONObject(result.content).getString("status"))
        assertEquals(1000L, seen?.sinceMillis)
        assertEquals(2000L, seen?.untilMillis)
    }

    @Test
    fun personalSearch_schemaNamesTheSourcesThatFilterByTime() {
        val schema = ContractTool(PersonalSearchTool(searchBackend)).parameters(fullEnv).getJSONObject("properties")
        val since = schema.getJSONObject("since").getString("description")
        assertTrue(since, since.contains("clipboard_history"))
        assertTrue(since, since.contains("notifications"))
        assertFalse(since, since.contains("coloros_memory"))
        assertFalse(since, since.contains("contacts"))
    }

    @Test
    fun personalSearch_sms_noCardEvenInManualMode() {
        // 读个人数据由「个人数据与系统」开关管能不能用，不再首读确认（权限模式方案）。
        val p = pipeline(fullEnv.copy(approvalPolicy = ApprovalPolicy.MANUAL_BUILT_IN), declineAll)
        val result = p.execute(call("personal_search", """{"source":"sms","query":"快递"}"""))
        assertEquals("ok", JSONObject(result.content).getString("status"))
    }

    @Test
    fun personalSearch_sms_approvedRuns() {
        val p = pipeline(fullEnv, approveAll)
        val result = p.execute(call("personal_search", """{"source":"sms"}"""))
        assertEquals("ok", JSONObject(result.content).getString("status"))
    }

    @Test
    fun personalSearch_unavailableWhenNoSources() {
        val p = pipeline(ToolEnvironment())
        val result = p.execute(call("personal_search", """{"source":"sms"}"""))
        assertEquals("error", result.status)
        assertEquals("PERMISSION_REQUIRED", result.errorCode)
    }

    @Test
    fun personalSearch_unavailableSourceRejected() {
        // 仅 Root、非 ColorOS：notes 需要 ColorOS → 来源不可用
        val p = pipeline(ToolEnvironment(rootAvailable = true))
        val result = p.execute(call("personal_search", """{"source":"notes"}"""))
        assertEquals("error", result.status)
        assertEquals("SOURCE_UNAVAILABLE", result.errorCode)
    }

    @Test
    fun personalSearch_nextCursorSurfaced() {
        val p = pipeline(fullEnv)
        val result = p.execute(call("personal_search", """{"source":"notifications","query":"page"}"""))
        val data = JSONObject(result.content).getJSONObject("data")
        assertEquals("CURSOR", data.getString("next_cursor"))
    }

    // ---- sms_code_read ----

    @Test
    fun smsCodeRead_returnsCodes_secret() {
        val p = pipeline(fullEnv)
        val result = p.execute(call("sms_code_read", """{"max_age_minutes":5}"""))
        val json = JSONObject(result.content)
        assertEquals("ok", json.getString("status"))
        assertTrue(result.sensitive)
        assertEquals("123456", json.getJSONObject("data").getJSONArray("codes").getJSONObject(0).getString("code"))
    }

    @Test
    fun smsCodeRead_unavailableWithoutRoot() {
        val p = pipeline(ToolEnvironment())
        val result = p.execute(call("sms_code_read", "{}"))
        assertEquals("error", result.status)
        assertEquals("PERMISSION_REQUIRED", result.errorCode)
    }

    // ---- usage_read ----

    @Test
    fun usageRead_recent_ok() {
        val p = pipeline(fullEnv)
        val result = p.execute(call("usage_read", """{"view":"recent"}"""))
        val json = JSONObject(result.content)
        assertEquals("ok", json.getString("status"))
        assertEquals("com.demo", json.getJSONObject("data").getJSONArray("items").getJSONObject(0).getString("package"))
    }

    @Test
    fun usageRead_unavailableWithoutUsageAccess() {
        val p = pipeline(ToolEnvironment(rootAvailable = true))
        val result = p.execute(call("usage_read", """{"view":"summary"}"""))
        assertEquals("error", result.status)
        assertEquals("PERMISSION_REQUIRED", result.errorCode)
    }

    @Test
    fun usageRead_invalidWindow() {
        val p = pipeline(fullEnv)
        val result = p.execute(call("usage_read", """{"view":"recent","since":"2000","until":"1000"}"""))
        assertEquals("error", result.status)
        assertEquals("INVALID_ARGUMENTS", result.errorCode)
    }

    // ---- health_read ----

    @Test
    fun healthRead_ok_hasData() {
        val p = pipeline(fullEnv)
        val result = p.execute(call("health_read", """{"days":14}"""))
        val json = JSONObject(result.content)
        assertEquals("ok", json.getString("status"))
        assertTrue(result.sensitive)
        assertTrue(json.getJSONObject("data").getBoolean("has_data"))
    }

    @Test
    fun healthRead_unavailableMessageNamesOnlyRoot() {
        // #19：后端只支持 Root 读系统健康数据库，不再说「Health Connect 健康权限或 Root」。
        val result = pipeline(ToolEnvironment(notificationAccess = true)).execute(call("health_read", "{}"))
        assertEquals("error", result.status)
        assertEquals("ROOT_REQUIRED", result.errorCode)
        val message = JSONObject(result.content).getString("message")
        assertTrue(message, message.contains("Root"))
        assertFalse(message, message.contains("权限或"))
    }

    // ---- wifi_password_read ----

    @Test
    fun wifiPasswordRead_rootRequiredWithoutRoot() {
        val p = pipeline(ToolEnvironment())
        val result = p.execute(call("wifi_password_read", "{}"))
        assertEquals("error", result.status)
        assertEquals("ROOT_REQUIRED", result.errorCode)
    }

    @Test
    fun wifiPasswordRead_ok_openNetworkNullPassword() {
        val p = pipeline(ToolEnvironment(rootAvailable = true))
        val result = p.execute(call("wifi_password_read", "{}"))
        val json = JSONObject(result.content)
        assertEquals("ok", json.getString("status"))
        assertTrue(result.sensitive)
        val items = json.getJSONObject("data").getJSONArray("items")
        assertTrue(items.getJSONObject(1).isNull("password"))
    }

    // ---- 执行卡视图（工具可视化方案 §6 决策 2：只看标题和时间，不显示正文）----

    @Test
    fun view_personalSearchListsTitlesAndTimesButNoBody() {
        val view = pipeline(fullEnv).execute(call("personal_search", """{"source":"sms","query":"快递"}""")).outcome!!.view!!
        assertEquals("找到 1 条", view.summary)
        val item = (view.blocks.single() as io.github.fartown.movo.agent.tools.core.ToolUiBlock.Items).items.single()
        assertEquals("标题", item.title)
        assertTrue(item.subtitle!!.startsWith("10086"))
        assertFalse("正文不显示", view.toJson().toString().contains("123456"))
        assertTrue("个人数据只在本次运行中显示", view.transient)
    }

    @Test
    fun view_smsCodeNeverShowsTheCode() {
        val view = pipeline(fullEnv).execute(call("sms_code_read", """{"max_age_minutes":5}""")).outcome!!.view!!
        assertFalse(view.toJson().toString().contains("123456"))
        assertTrue(view.summary!!.startsWith("找到 1 个"))
        assertTrue(view.blocks.isEmpty())
    }
}
