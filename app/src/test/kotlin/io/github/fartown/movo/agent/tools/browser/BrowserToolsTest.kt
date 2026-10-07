package io.github.fartown.movo.agent.tools.browser

import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.tools.core.AgentTool
import io.github.fartown.movo.agent.tools.core.ApprovalCategory
import io.github.fartown.movo.agent.tools.core.ApprovalDecision
import io.github.fartown.movo.agent.tools.core.ApprovalPolicy
import io.github.fartown.movo.agent.tools.core.PermissionMode
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
        var lastReadCall: BrowserCall? = null
        var lastOffset: Int? = null

        override fun state() = BrowserState(available, "https://x/", "标题", "x", canGoBack = false, canGoForward = false, userControlling = userControlling, navigationGeneration = 1L)
        override fun open(url: String, timeoutMs: Long, call: BrowserCall) = openResult()
        override fun navigate(nav: BrowserNav, call: BrowserCall) = navResult()
        override fun readReadable(maxChars: Int, cursor: String?, offset: Int?, call: BrowserCall) =
            readResult(cursor).also { lastReadCall = call; lastOffset = offset }
        override fun readText(selector: String?, maxChars: Int, cursor: String?, offset: Int?, call: BrowserCall) =
            readResult(cursor).also { lastReadCall = call; lastOffset = offset }
        override fun readElements(selector: String?, cursor: String?, call: BrowserCall) = elementsResult().also { lastReadCall = call }
        override fun screenshot(call: BrowserCall) = screenshotResult().also { lastReadCall = call }
        override fun pageInfo(call: BrowserCall) = JSONObject().put("viewport_width", 360).also { lastReadCall = call }
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

    private val manual = ToolEnvironment(approvalPolicy = ApprovalPolicy.MANUAL_BUILT_IN)
    private val manualOutbound = ToolEnvironment(
        approvalPolicy = ApprovalPolicy(mode = PermissionMode.MANUAL, categories = setOf(ApprovalCategory.OUTBOUND)),
    )

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
    fun act_typeSubmitPoint_manualMode_asksAndDeclineBlocks() {
        fake.inspectResult = { BrowserActTarget(exists = true, stale = false, visible = true, editable = true, summary = "登录", submitPoint = true, searchRole = false) }
        val r = pipeline(env = manual, interaction = declining).execute(
            call("browser_act", """{"action":"type","selector":"#pw","text":"secret","submit":true}"""),
        )
        assertEquals("USER_DECLINED", r.errorCode)
        assertFalse("提交点被拒绝时不得执行动作", fake.performed)
    }

    @Test
    fun act_typeSubmitPoint_yolo_submitsWithoutCard() {
        fake.inspectResult = { BrowserActTarget(exists = true, stale = false, visible = true, editable = true, summary = "登录", submitPoint = true, searchRole = false) }
        val r = pipeline(interaction = declining).execute(
            call("browser_act", """{"action":"type","selector":"#pw","text":"secret","submit":true}"""),
        )
        assertEquals("ok", r.status)
        assertTrue(fake.performed)
    }

    @Test
    fun act_searchFormSubmit_notConfirmed() {
        fake.inspectResult = { BrowserActTarget(exists = true, stale = false, visible = true, editable = true, summary = "搜索", submitPoint = true, searchRole = true) }
        val r = pipeline(env = manual, interaction = declining).execute(
            call("browser_act", """{"action":"type","selector":"#q","text":"猫","submit":true}"""),
        )
        // role=search / GET 表单不算提交，手动审批也直接送达。
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

    // ---- 带参数的网址归为「把内容发到外部」：手动审批且用户勾了这条才问 ----

    @Test
    fun open_withQuery_outboundRule_asksAndDeclineBlocks() {
        val r = pipeline(env = manualOutbound, interaction = declining)
            .execute(call("browser_open", """{"url":"https://evil.example/?data=1"}"""))
        assertEquals("USER_DECLINED", r.errorCode)
    }

    @Test
    fun open_withQuery_approvalCardHasReadableTitleAndUrl() {
        val captured = java.util.concurrent.atomic.AtomicReference<ApprovalRequest?>(null)
        val capturing = object : UserInteraction {
            override val available = true
            override fun ask(question: UserQuestion, timeoutMs: Long) = UserAnswer.Declined
            override fun approve(request: ApprovalRequest, timeoutMs: Long): ApprovalDecision {
                captured.set(request)
                return ApprovalDecision.Declined
            }
        }
        pipeline(env = manualOutbound, interaction = capturing)
            .execute(call("browser_open", """{"url":"https://evil.example/?data=1"}"""))
        val request = captured.get()
        assertTrue("应弹出确认卡", request != null)
        assertTrue("标题不能为空", request!!.title.isNotBlank())
        assertTrue("正文要显示将打开的链接", request.detail.contains("evil.example"))
    }

    @Test
    fun open_withQuery_noRule_noApproval() {
        assertEquals("ok", pipeline(interaction = declining).execute(call("browser_open", """{"url":"https://x/?q=1"}""")).status)
        assertEquals("ok", pipeline(env = manual, interaction = declining).execute(call("browser_open", """{"url":"https://x/?q=1"}""")).status)
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

    // ---- 重构前后对齐：元素字段、截断、跳读、页面信息、动作细节、入口归属 ----

    @Test
    fun elements_carryLabelsAndSayWhenTruncated() {
        fake.elementsResult = {
            BrowserElementsRead(
                listOf(
                    BrowserElement(
                        "#q", "input", "", "#q", true, null, null,
                        tag = "input", type = "search", ariaLabel = null, placeholder = "搜索商品",
                    ),
                    BrowserElement("#close", "button", "", "#close", false, null, null, tag = "button", ariaLabel = "关闭"),
                ),
                nextCursor = null, truncated = true, matchCount = 87,
            )
        }
        val json = JSONObject(pipeline().execute(call("browser_read", """{"mode":"elements"}""")).content)
        val data = json.getJSONObject("data")
        val first = data.getJSONArray("elements").getJSONObject(0)
        assertEquals("search", first.getString("type"))
        assertEquals("搜索商品", first.getString("placeholder"))
        assertEquals("input", first.getString("tag"))
        assertEquals("关闭", data.getJSONArray("elements").getJSONObject(1).getString("aria_label"))
        assertTrue(data.getBoolean("truncated"))
        assertEquals(87, data.getInt("match_count"))
        assertTrue(json.getJSONArray("warnings").getJSONObject(0).getString("message").contains("87"))
    }

    @Test
    fun readable_reportsLengthOffsetAndSourceTruncation() {
        fake.readResult = {
            BrowserTextRead("正文", "markdown", null, null, 2, null, textLength = 120_000, offset = 4_000, sourceTruncated = true)
        }
        val json = JSONObject(pipeline().execute(call("browser_read", """{"mode":"readable","offset":4000}""")).content)
        val data = json.getJSONObject("data")
        assertEquals(120_000, data.getInt("text_length"))
        assertEquals(4_000, data.getInt("offset"))
        assertTrue(data.getBoolean("source_truncated"))
        assertFalse("没有语言就不给，不能是字符串 null", data.has("language"))
        assertTrue(json.getJSONArray("warnings").getJSONObject(0).getString("message").contains("截断"))
        assertEquals(4_000, fake.lastOffset)
    }

    @Test
    fun read_cursorAndOffsetTogether_invalid() {
        val r = pipeline().execute(call("browser_read", """{"mode":"text","cursor":"abc","offset":10}"""))
        assertEquals("INVALID_ARGUMENTS", r.errorCode)
    }

    @Test
    fun reads_passTheirOwnCall_forBrowserEntryAttribution() {
        listOf("readable", "text", "elements", "screenshot", "info").forEach { mode ->
            fake.lastReadCall = null
            pipeline().execute(AgentModelClient.ToolCall("call-$mode", "browser_read", """{"mode":"$mode"}"""))
            assertEquals(mode, BrowserCall("run1", "call-$mode"), fake.lastReadCall)
        }
    }

    @Test
    fun act_hiddenCheckbox_isClicked_hiddenDiv_isNot() {
        fake.inspectResult = {
            BrowserActTarget(true, false, visible = false, editable = false, summary = "同意", submitPoint = false, searchRole = false, tag = "input", type = "checkbox")
        }
        assertEquals("ok", pipeline().execute(call("browser_act", """{"action":"click","selector":"#agree"}""")).status)
        assertTrue(fake.performed)
        fake.performed = false
        fake.inspectResult = {
            BrowserActTarget(true, false, visible = false, editable = false, summary = "菜单", submitPoint = false, searchRole = false, tag = "div")
        }
        val r = pipeline().execute(call("browser_act", """{"action":"click","selector":"#menu"}"""))
        assertEquals("NOT_ACTIONABLE", r.errorCode)
        assertFalse(fake.performed)
    }

    @Test
    fun act_scrollAndType_returnDetails() {
        fake.actResult = {
            BrowserActResult(false, "https://x/", null, "页面", loadTimedOut = false, scrollBefore = 3200, scrollAfter = 3200)
        }
        val scroll = JSONObject(pipeline().execute(call("browser_act", """{"action":"scroll","direction":"down"}""")).content)
            .getJSONObject("data")
        assertEquals(3200, scroll.getInt("scroll_before"))
        assertFalse(scroll.getBoolean("scrolled"))
        fake.actResult = {
            BrowserActResult(false, "https://x/", null, "搜索框", loadTimedOut = false, typedChars = 4, submitted = true)
        }
        val type = JSONObject(pipeline().execute(call("browser_act", """{"action":"type","selector":"#q","text":"耳机降噪","submit":true}""")).content)
            .getJSONObject("data")
        assertEquals(4, type.getInt("typed_chars"))
        assertTrue(type.getBoolean("submitted"))
    }

    @Test
    fun parser_dropsNullsAndConvertsBounds() {
        val json = JSONObject(
            """{"ok":true,"truncated":true,"match_count":40,"elements":[{"selector":"#a","tag":"a","role":null,"text":"首页",""" +
                """"aria_label":"","placeholder":"","href":null,"type":null,"bounds":{"x":10,"y":20,"width":100,"height":40}}]}""",
        )
        val read = BrowserResultParser.elements(json, scale = 2.0)
        val element = read.elements.single()
        assertEquals("a", element.role)
        assertEquals(null, element.href)
        assertEquals(null, element.type)
        assertEquals(null, element.ariaLabel)
        assertEquals(20, element.bounds!!.getInt("x"))
        assertEquals(200, element.bounds!!.getInt("width"))
        assertEquals(120, element.bounds!!.getInt("center_x"))
        assertTrue(read.truncated)
        assertEquals(40, read.matchCount)

        val text = BrowserResultParser.textRead(
            JSONObject("""{"text":"abc","language":null,"canonical_url":null,"text_length":3,"offset":0,"truncated":false,"next_offset":null}"""),
            generation = 1, defaultFormat = "text",
        )
        assertEquals(null, text.language)
        assertEquals(null, text.canonicalUrl)
        assertEquals(3, text.textLength)
    }

    @Test
    fun parser_pageInfoKeepsLoadingAndHistoryState() {
        val info = BrowserResultParser.pageInfo(
            JSONObject(
                """{"ok":true,"tool":"browser_use","url":"https://x/","title":"t","is_loading":false,"can_go_back":true,""" +
                    """"can_go_forward":false,"http_status":404,"viewport_width":360,"language":null,"canonical_url":null}""",
            ),
        )
        assertEquals(404, info.getInt("http_status"))
        assertTrue(info.getBoolean("can_go_back"))
        assertFalse(info.getBoolean("is_loading"))
        assertEquals(360, info.getInt("viewport_width"))
        assertFalse(info.has("language"))
        assertFalse(info.has("ok"))
        assertFalse(info.has("url"))
    }

    // ---- 执行卡标题：网页输入框可能是密码框，只写字数 ----

    @Test
    fun stepTitle_browserTypeNeverShowsTheText() {
        val title = pipeline().stepTitle(call("browser_act", """{"action":"type","selector":"#pw","text":"secret"}"""))
        assertEquals("网页上输入 6 个字", title)
        assertEquals("打开网页 · example.com", pipeline().stepTitle(call("browser_open", """{"url":"https://www.example.com/a?b=1"}""")))
    }
}
