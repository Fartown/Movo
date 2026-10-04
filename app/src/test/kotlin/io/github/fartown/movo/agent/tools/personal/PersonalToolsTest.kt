package io.github.fartown.movo.agent.tools.personal

import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.model.AgentModelClient
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
        override fun approve(request: ApprovalRequest, timeoutMs: Long) = ApprovalDecision.Approved(remember = false)
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
    fun personalSearch_sms_requiresApproval_declinedBlocks() {
        val p = pipeline(fullEnv, declineAll)
        val result = p.execute(call("personal_search", """{"source":"sms","query":"快递"}"""))
        assertEquals("error", result.status)
        assertEquals("USER_DECLINED", result.errorCode)
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
}
