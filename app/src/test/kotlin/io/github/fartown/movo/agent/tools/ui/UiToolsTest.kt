package io.github.fartown.movo.agent.tools.ui

import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.tools.core.AgentTool
import io.github.fartown.movo.agent.tools.core.ApprovalDecision
import io.github.fartown.movo.agent.tools.core.ApprovalCategory
import io.github.fartown.movo.agent.tools.core.ApprovalPolicy
import io.github.fartown.movo.agent.tools.core.PermissionMode
import io.github.fartown.movo.agent.tools.core.ApprovalRequest
import io.github.fartown.movo.agent.tools.core.ContractTool
import io.github.fartown.movo.agent.tools.core.InjectionBackend
import io.github.fartown.movo.agent.tools.core.ToolArgs
import io.github.fartown.movo.agent.tools.core.ToolAvailability
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.agent.tools.core.ToolPipeline
import io.github.fartown.movo.agent.tools.core.ToolProvider
import io.github.fartown.movo.agent.tools.core.ToolRegistry
import io.github.fartown.movo.agent.tools.core.ToolSwitches
import io.github.fartown.movo.agent.tools.core.UserAnswer
import io.github.fartown.movo.agent.tools.core.UserInteraction
import io.github.fartown.movo.agent.tools.core.UserQuestion
import io.github.fartown.movo.core.AndroidAgentLogger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 屏幕 UI 领域九工具：用假后端经 ToolPipeline 跑通各 Verdict 分支与 backend-agnostic 确认。 */
@RunWith(RobolectricTestRunner::class)
class UiToolsTest {

    private val accessibilityEnv = ToolEnvironment(accessibilityAvailable = true)
    private val manualEnv = accessibilityEnv.copy(approvalPolicy = ApprovalPolicy.MANUAL_BUILT_IN)

    @Test fun nonTouchDeviceProjectsOnlyUsableUiArgumentsAndRejectsForgedInputs() {
        val backend = FakeUiBackend()
        val television = accessibilityEnv.copy(touchscreen = false, screenshotAvailable = false)
        val tap = UiTapTool(backend, backend)
        assertFalse(tap.schema(television).getJSONObject("properties").has("x"))
        assertTrue(tap.schema(accessibilityEnv).getJSONObject("properties").has("x"))
        assertFalse(UiObserveTool(backend).schema(television).getJSONObject("properties").has("screenshot"))
        assertTrue(UiSwipeTool(backend, backend).availability(television) is ToolAvailability.Unavailable)
        val p = pipeline(provider(ContractTool(tap), ContractTool(UiKeyTool(backend, backend)), ContractTool(UiObserveTool(backend))), television)
        for ((name, args) in listOf("ui_tap" to "{\"x\":1,\"y\":1}", "ui_key" to "{\"key\":\"enter\"}", "ui_observe" to "{\"screenshot\":true}")) {
            assertEquals("INVALID_ARGUMENTS", p.execute(call(name, args)).errorCode)
        }
    }

    private fun pipeline(
        provider: ToolProvider,
        env: ToolEnvironment,
        interaction: UserInteraction = UserInteraction.NONE,
    ) = ToolPipeline(
        registry = ToolRegistry(listOf(provider)),
        environment = { env },
        appContext = ApplicationProvider.getApplicationContext(),
        logger = AndroidAgentLogger,
        runId = "run1",
        cancelled = { false },
        interaction = interaction,
    ).also { it.catalog() }

    private fun call(name: String, args: String) = AgentModelClient.ToolCall("c1", name, args)

    private fun provider(vararg contracts: AgentTool) = object : ToolProvider {
        override val tools = contracts.toList()
    }

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

    // ---- ui_observe ----

    @Test
    fun uiObserve_withAccessibility_returnsReadWithObservationAndSensitive() {
        val backend = FakeUiBackend(
            observeResult = UiObserveResult.Observed(
                observationId = "obs1", gen = 1L, packageName = "com.example.app",
                coordWidth = 1000, coordHeight = 2000, focusedIndex = null,
                nodes = listOf(UiObservedNode(index = 0, text = "你好", bounds = listOf(0, 0, 10, 10))),
                nodesTruncated = false, screenshotAttached = false, screenshotQuality = null, screenshot = null,
            ),
        )
        val result = pipeline(provider(ContractTool(UiObserveTool(backend))), accessibilityEnv)
            .execute(call("ui_observe", "{}"))
        val json = JSONObject(result.content)
        assertEquals("ok", json.getString("status"))
        assertFalse(json.has("effect_verified")) // 只读
        assertTrue(result.sensitive) // PRIVATE
        assertEquals("obs1", json.getJSONObject("data").getString("observation_id"))
        assertEquals(1L, json.getJSONObject("data").getLong("gen"))
        // 屏幕文字和截图一样可能是别的应用的内容，只在本次运行中显示。
        val view = result.outcome!!.view!!
        assertEquals("你好", (view.blocks.single() as io.github.fartown.movo.agent.tools.core.ToolUiBlock.Items).items.single().title)
        assertTrue(view.transient)
    }

    @Test
    fun uiObserve_withoutAccessibility_unavailablePermissionRequired() {
        val availability = UiObserveTool(FakeUiBackend()).availability(ToolEnvironment())
        assertTrue(availability is ToolAvailability.Unavailable)
        assertEquals(ToolErrorCode.PERMISSION_REQUIRED, (availability as ToolAvailability.Unavailable).code)
    }

    // ---- ui_tap ----

    @Test
    fun uiTap_element_dispatchedNoApproval() {
        val backend = FakeUiBackend()
        val json = JSONObject(
            pipeline(provider(ContractTool(UiTapTool(backend, backend))), accessibilityEnv)
                .execute(call("ui_tap", """{"index":0,"observation_id":"obs1"}""")).content,
        )
        assertEquals("ok", json.getString("status"))
        assertFalse(json.getBoolean("effect_verified")) // 送达型
        assertTrue(backend.tapCalled)
    }

    @Test
    fun uiTap_blindCoordinate_noApprovalWithoutProtectedApps() {
        // 读不到坐标点上的节点（地图、画布、游戏）不再单独确认：否则这类界面每一步都弹卡（2026-10-05 用户反馈）。
        val backend = FakeUiBackend(readableProbe = null)
        val result = pipeline(
            provider(ContractTool(UiTapTool(backend, backend))), accessibilityEnv, declineAll,
        ).execute(call("ui_tap", """{"x":100,"y":200}"""))
        assertEquals("ok", result.status)
        assertTrue(backend.tapCalled)
    }

    private class Capture(private val decision: ApprovalDecision = ApprovalDecision.Declined) : UserInteraction {
        val seen = mutableListOf<ApprovalRequest>()
        override val available = true
        override fun ask(question: UserQuestion, timeoutMs: Long) = UserAnswer.Declined
        override fun approve(request: ApprovalRequest, timeoutMs: Long): ApprovalDecision {
            seen += request
            return decision
        }
    }

    @Test
    fun uiTap_declaredPayment_manualMode_asksAsPayment() {
        val backend = FakeUiBackend(observationPackageMap = mutableMapOf("obs1" to "com.example.pay"))
        val capture = Capture()
        val result = pipeline(provider(ContractTool(UiTapTool(backend, backend))), manualEnv, capture)
            .execute(call("ui_tap", """{"index":0,"observation_id":"obs1","effect":"pay"}"""))
        assertEquals("USER_DECLINED", result.errorCode)
        assertEquals(ApprovalCategory.PAYMENT, capture.seen.single().category)
        assertFalse(capture.seen.single().detail.contains("{"))
        assertFalse(backend.tapCalled)
    }

    @Test
    fun uiTap_declaredEffects_mapToHighSensitiveCategories() {
        val expected = mapOf(
            "send" to ApprovalCategory.SEND, "submit" to ApprovalCategory.SEND,
            "delete" to ApprovalCategory.DELETE, "transfer" to ApprovalCategory.PAYMENT,
        )
        for ((effect, category) in expected) {
            val backend = FakeUiBackend()
            val capture = Capture()
            pipeline(provider(ContractTool(UiTapTool(backend, backend))), manualEnv, capture)
                .execute(call("ui_tap", """{"index":0,"observation_id":"obs1","effect":"$effect"}"""))
            assertEquals(effect, category, capture.seen.single().category)
        }
    }

    @Test
    fun uiTap_declaredSend_yolo_noCard() {
        val backend = FakeUiBackend(observationPackageMap = mutableMapOf("obs1" to "com.example.chat"))
        val capture = Capture()
        val result = pipeline(provider(ContractTool(UiTapTool(backend, backend))), accessibilityEnv, capture)
            .execute(call("ui_tap", """{"index":0,"observation_id":"obs1","effect":"send"}"""))
        assertEquals("ok", result.status)
        assertTrue(capture.seen.isEmpty())
        assertTrue(backend.tapCalled)
    }

    @Test
    fun uiTap_declaredSend_approved_isAskedEveryTime() {
        val backend = FakeUiBackend(observationPackageMap = mutableMapOf("obs1" to "com.example.chat"))
        val capture = Capture(ApprovalDecision.Approved)
        val p = pipeline(provider(ContractTool(UiTapTool(backend, backend))), manualEnv, capture)
        p.execute(call("ui_tap", """{"index":0,"observation_id":"obs1","effect":"send"}"""))
        p.execute(call("ui_tap", """{"index":0,"observation_id":"obs1","effect":"send"}"""))
        assertEquals("卡片没有「记住」，每次发送都问", 2, capture.seen.size)
    }

    @Test
    fun uiTap_appRule_asksEveryStepInThatAppOnly() {
        val env = accessibilityEnv.copy(
            approvalPolicy = ApprovalPolicy(mode = PermissionMode.MANUAL, apps = setOf("com.example.bank")),
        )
        val bank = FakeUiBackend(observationPackageMap = mutableMapOf("obs1" to "com.example.bank"))
        val capture = Capture()
        assertEquals(
            "USER_DECLINED",
            pipeline(provider(ContractTool(UiTapTool(bank, bank))), env, capture)
                .execute(call("ui_tap", """{"index":0,"observation_id":"obs1"}""")).errorCode,
        )
        assertNull("应用规则命中时类别为空", capture.seen.single().category)
        assertTrue(capture.seen.single().reason, capture.seen.single().reason.startsWith("你设了在「"))
        val other = FakeUiBackend()
        assertEquals(
            "ok",
            pipeline(provider(ContractTool(UiTapTool(other, other))), env, capture)
                .execute(call("ui_tap", """{"index":0,"observation_id":"obs1"}""")).status,
        )
        assertEquals(1, capture.seen.size)
    }

    @Test
    fun uiInput_passwordField_manualMode_asksWithoutShowingThePassword() {
        val backend = FakeUiBackend(focusedPassword = true)
        val capture = Capture()
        val result = pipeline(provider(ContractTool(UiInputTool(backend, backend))), manualEnv, capture)
            .execute(call("ui_input", """{"text":"hunter2"}"""))
        assertEquals("USER_DECLINED", result.errorCode)
        val card = capture.seen.single()
        assertEquals(ApprovalCategory.PASSWORD, card.category)
        assertFalse("确认卡不显示密码", card.detail.contains("hunter2") || card.title.contains("hunter2"))
    }

    @Test
    fun uiInput_plainField_manualMode_noCard() {
        val backend = FakeUiBackend(focusedPassword = false)
        val capture = Capture()
        val result = pipeline(provider(ContractTool(UiInputTool(backend, backend))), manualEnv, capture)
            .execute(call("ui_input", """{"text":"hello"}"""))
        assertEquals("ok", result.status)
        assertTrue(capture.seen.isEmpty())
    }

    @Test
    fun uiTap_selfPackage_rejectedPolicyDenied() {
        val backend = FakeUiBackend(selfPackage = "com.movo", observationPackageMap = mutableMapOf("obs1" to "com.movo"))
        val result = pipeline(provider(ContractTool(UiTapTool(backend, backend))), accessibilityEnv)
            .execute(call("ui_tap", """{"index":0,"observation_id":"obs1"}"""))
        assertEquals("error", result.status)
        assertEquals("POLICY_DENIED", result.errorCode)
        assertFalse(backend.tapCalled)
    }

    @Test
    fun uiTap_staleObservation_failsStale() {
        // gen 读不到（代际失效）但包名可信（避免落到确认分支）。
        val backend = FakeUiBackend(
            genMap = mutableMapOf(),
            observationPackageMap = mutableMapOf("obs1" to "com.example.app"),
        )
        val result = pipeline(provider(ContractTool(UiTapTool(backend, backend))), accessibilityEnv)
            .execute(call("ui_tap", """{"index":0,"observation_id":"obs1"}"""))
        assertEquals("error", result.status)
        assertEquals("STALE_OBSERVATION", result.errorCode)
    }

    // ---- ui_scroll ----

    @Test
    fun uiScroll_moved_returnsDoneVerified() {
        val backend = FakeUiBackend(scrollResult = UiScrollResult.Finished(true, false, "com.example.app"))
        val json = JSONObject(
            pipeline(provider(ContractTool(UiScrollTool(backend, backend))), accessibilityEnv)
                .execute(call("ui_scroll", """{"direction":"down"}""")).content,
        )
        assertEquals("ok", json.getString("status"))
        assertTrue(json.getBoolean("effect_verified"))
        assertTrue(json.getJSONObject("data").getBoolean("moved"))
    }

    @Test
    fun uiScroll_isNeverAsked() {
        // 滚动不归类：手动审批也不弹卡（后台监听每 15 秒下滑的真机回归）。
        val backend = FakeUiBackend(scrollResult = UiScrollResult.Finished(true, false, "com.example.app"))
        val resolution = UiScrollTool(backend, backend).let { tool ->
            tool.resolve(tool.parse(ToolArgs(JSONObject("""{"direction":"down"}""")), manualEnv), manualEnv)
        }
        assertNull(resolution.consequence())
    }

    @Test
    fun uiScroll_notMoved_returnsDispatched() {
        val backend = FakeUiBackend(scrollResult = UiScrollResult.Finished(false, true, "com.example.app"))
        val json = JSONObject(
            pipeline(provider(ContractTool(UiScrollTool(backend, backend))), accessibilityEnv)
                .execute(call("ui_scroll", """{"direction":"down"}""")).content,
        )
        assertEquals("ok", json.getString("status"))
        assertFalse(json.getBoolean("effect_verified"))
    }

    @Test
    fun uiScroll_directionMismatch_returnsUnknown() {
        val backend = FakeUiBackend(scrollResult = UiScrollResult.DirectionMismatch)
        val json = JSONObject(
            pipeline(provider(ContractTool(UiScrollTool(backend, backend))), accessibilityEnv)
                .execute(call("ui_scroll", """{"direction":"up"}""")).content,
        )
        assertEquals("unknown", json.getString("status"))
        assertEquals("OUTCOME_UNKNOWN", json.getString("code"))
    }

    // ---- ui_input ----

    @Test
    fun uiInput_readbackMatches_returnsDone() {
        val backend = FakeUiBackend(
            inputResult = UiInputResult.Written("set_text", true, 5, false, "com.example.app", false),
        )
        val json = JSONObject(
            pipeline(provider(ContractTool(UiInputTool(backend, backend))), accessibilityEnv)
                .execute(call("ui_input", """{"text":"hello"}""")).content,
        )
        assertEquals("ok", json.getString("status"))
        assertTrue(json.getBoolean("effect_verified"))
        assertTrue(json.getJSONObject("data").getBoolean("text_verified"))
    }

    @Test
    fun uiInput_readbackMismatch_returnsDispatched() {
        val backend = FakeUiBackend(
            inputResult = UiInputResult.Written("paste", false, 11, false, "com.example.app", false),
        )
        val json = JSONObject(
            pipeline(provider(ContractTool(UiInputTool(backend, backend))), accessibilityEnv)
                .execute(call("ui_input", """{"text":"138 0000 000"}""")).content,
        )
        assertEquals("ok", json.getString("status"))
        assertFalse(json.getBoolean("effect_verified"))
    }

    @Test
    fun uiInput_appendCannotInsert_notActionable_noReplaceDowngrade() {
        val backend = FakeUiBackend(inputResult = UiInputResult.NotActionable("append 无法插入"))
        val result = pipeline(provider(ContractTool(UiInputTool(backend, backend))), accessibilityEnv)
            .execute(call("ui_input", """{"text":"x","mode":"append"}"""))
        assertEquals("error", result.status)
        assertEquals("NOT_ACTIONABLE", result.errorCode)
    }

    @Test
    fun uiInput_appendEmptyText_invalidArgs() {
        val failure = runCatching {
            UiInputTool(FakeUiBackend(), FakeUiBackend())
                .parse(ToolArgs.parse("""{"text":"","mode":"append"}"""), accessibilityEnv)
        }.exceptionOrNull()
        assertTrue(failure is io.github.fartown.movo.agent.tools.core.ToolFailure)
        assertEquals(
            ToolErrorCode.INVALID_ARGUMENTS,
            (failure as io.github.fartown.movo.agent.tools.core.ToolFailure).code,
        )
    }

    // ---- ui_key ----

    @Test
    fun uiKey_back_dispatched() {
        val backend = FakeUiBackend(keyResult = UiInjectResult.Dispatched("global", "com.example.app", true))
        val json = JSONObject(
            pipeline(provider(ContractTool(UiKeyTool(backend, backend))), accessibilityEnv)
                .execute(call("ui_key", """{"key":"back"}""")).content,
        )
        assertEquals("ok", json.getString("status"))
        assertFalse(json.getBoolean("effect_verified"))
        assertEquals("back", json.getJSONObject("data").getString("key"))
    }

    // ---- ui_swipe ----

    @Test
    fun uiSwipe_blindCoordinate_approved_dispatched() {
        val backend = FakeUiBackend(swipeResult = UiInjectResult.Dispatched("gesture", "com.example.app", false))
        val json = JSONObject(
            pipeline(provider(ContractTool(UiSwipeTool(backend, backend))), accessibilityEnv, approveAll)
                .execute(call("ui_swipe", """{"x":10,"y":20,"x2":10,"y2":200}""")).content,
        )
        assertEquals("ok", json.getString("status"))
        assertFalse(json.getBoolean("effect_verified"))
        assertTrue(backend.swipeCalled)
    }

    // ---- ui_wait ----

    @Test
    fun uiWait_duration_returnsReadMatched() {
        val backend = FakeUiBackend(waitResult = UiWaitResult.Finished(true, 100, null))
        val json = JSONObject(
            pipeline(provider(ContractTool(UiWaitTool(backend))), ToolEnvironment())
                .execute(call("ui_wait", """{"duration_ms":100}""")).content,
        )
        assertEquals("ok", json.getString("status"))
        assertTrue(json.getJSONObject("data").getBoolean("matched"))
    }

    @Test
    fun uiWait_textTimeout_returnsOkMatchedFalse() {
        val backend = FakeUiBackend(waitResult = UiWaitResult.Finished(false, 10000, null))
        val json = JSONObject(
            pipeline(provider(ContractTool(UiWaitTool(backend))), accessibilityEnv)
                .execute(call("ui_wait", """{"text":"完成","timeout_ms":500}""")).content,
        )
        assertEquals("ok", json.getString("status")) // 超时不是错误
        assertFalse(json.getJSONObject("data").getBoolean("matched"))
    }

    // ---- clipboard_read ----

    @Test
    fun clipboardRead_text_returnsReadSensitive() {
        val backend = object : ClipboardReadBackend {
            override fun read() = ClipboardReadResult.Text("秘密", truncated = false, sensitive = false)
        }
        val result = pipeline(provider(ContractTool(ClipboardReadTool(backend))), ToolEnvironment())
            .execute(call("clipboard_read", "{}"))
        val json = JSONObject(result.content)
        assertEquals("ok", json.getString("status"))
        assertFalse(json.has("effect_verified"))
        assertTrue(result.sensitive) // PRIVATE
        assertEquals("秘密", json.getJSONObject("data").getString("text"))
    }

    @Test
    fun clipboardRead_sensitiveReadOff_disabled() {
        val backend = object : ClipboardReadBackend {
            override fun read() = ClipboardReadResult.Empty
        }
        val env = ToolEnvironment(switches = ToolSwitches(sensitiveRead = false))
        val result = pipeline(provider(ContractTool(ClipboardReadTool(backend))), env)
            .execute(call("clipboard_read", "{}"))
        assertEquals("error", result.status)
        assertEquals("DISABLED", result.errorCode)
    }

    @Test
    fun clipboardRead_notForeground_systemRejected() {
        val backend = object : ClipboardReadBackend {
            override fun read() = ClipboardReadResult.Rejected
        }
        val result = pipeline(provider(ContractTool(ClipboardReadTool(backend))), ToolEnvironment())
            .execute(call("clipboard_read", "{}"))
        assertEquals("error", result.status)
        assertEquals("SYSTEM_REJECTED", result.errorCode)
    }

    // ---- clipboard_write ----

    @Test
    fun clipboardWrite_readBack_returnsDone() {
        val backend = object : ClipboardWriteBackend {
            override fun write(text: String, sensitive: Boolean) = ClipboardWriteResult.ReadBackOk
        }
        val json = JSONObject(
            pipeline(provider(ContractTool(ClipboardWriteTool(backend))), ToolEnvironment())
                .execute(call("clipboard_write", """{"text":"hi"}""")).content,
        )
        assertEquals("ok", json.getString("status"))
        assertTrue(json.getBoolean("effect_verified"))
        assertEquals(2, json.getJSONObject("data").getInt("chars"))
    }

    @Test
    fun clipboardWrite_backgroundLimited_returnsDispatched() {
        val backend = object : ClipboardWriteBackend {
            override fun write(text: String, sensitive: Boolean) = ClipboardWriteResult.ReadBackLimited
        }
        val json = JSONObject(
            pipeline(provider(ContractTool(ClipboardWriteTool(backend))), ToolEnvironment())
                .execute(call("clipboard_write", """{"text":"hi"}""")).content,
        )
        assertEquals("ok", json.getString("status"))
        assertFalse(json.getBoolean("effect_verified"))
    }

    // ---- 假后端 ----

    // ---- 执行卡标题与视图（工具可视化方案）----

    private fun title(tool: io.github.fartown.movo.agent.tools.core.AgentTool, args: String) =
        tool.stepTitle(ToolArgs(JSONObject(args)), accessibilityEnv)

    @Test
    fun stepTitle_tapNamesTheObservedNode() {
        val backend = FakeUiBackend(observedNodes = mapOf(3 to UiNodeProbe(text = "搜索系统设置项")))
        val tap = ContractTool(UiTapTool(backend, backend))
        assertEquals("点按「搜索系统设置项」", title(tap, """{"index":3,"observation_id":"obs1"}"""))
        assertEquals("长按「搜索系统设置项」", title(tap, """{"index":3,"observation_id":"obs1","hold_ms":600}"""))
        assertEquals("点按屏幕 (100, 200)", title(tap, """{"x":100,"y":200}"""))
    }

    @Test
    fun stepTitle_inputNeverShowsAPassword() {
        val plain = FakeUiBackend(focusedPassword = false)
        assertEquals("输入「蓝牙」", title(ContractTool(UiInputTool(plain, plain)), """{"text":"蓝牙"}"""))
        val password = FakeUiBackend(focusedPassword = true)
        assertEquals("在密码框输入 7 个字符", title(ContractTool(UiInputTool(password, password)), """{"text":"hunter2"}"""))
        // 判断不了是不是密码框：只写字数。
        val unknown = FakeUiBackend(focusedPassword = null)
        val masked = title(ContractTool(UiInputTool(unknown, unknown)), """{"text":"hunter2"}""")
        assertEquals("输入 7 个字", masked)
        val node = FakeUiBackend(observedNodes = mapOf(0 to UiNodeProbe(text = null, password = true)))
        assertFalse(title(ContractTool(UiInputTool(node, node)), """{"text":"hunter2","index":0,"observation_id":"obs1"}""")!!.contains("hunter2"))
    }

    @Test
    fun view_inputAndScrollSummaries() {
        val backend = FakeUiBackend(
            inputResult = UiInputResult.Written("set_text", true, 2, false, "com.example.app", false),
            scrollResult = UiScrollResult.Finished(true, true, "com.example.app"),
        )
        val input = pipeline(provider(ContractTool(UiInputTool(backend, backend))), accessibilityEnv)
            .execute(call("ui_input", """{"text":"蓝牙"}""")).outcome!!.view!!
        assertEquals("已输入", input.summary)
        val scroll = pipeline(provider(ContractTool(UiScrollTool(backend, backend))), accessibilityEnv)
            .execute(call("ui_scroll", """{"direction":"down"}""")).outcome!!.view!!
        assertEquals("已滚动 · 到头了", scroll.summary)
        assertEquals("向下滚动", title(ContractTool(UiScrollTool(backend, backend)), """{"direction":"down"}"""))
        assertEquals("按「返回」", title(ContractTool(UiKeyTool(backend, backend)), """{"key":"back"}"""))
    }

    private class FakeUiBackend(
        override val selfPackage: String = "io.github.fartown.movo",
        private val foreground: String? = "com.example.app",
        private val latestRef: ObservationRef? = ObservationRef("obs1", 1L),
        private val genMap: MutableMap<String, Long> = mutableMapOf("obs1" to 1L),
        private val observationPackageMap: MutableMap<String, String?> = mutableMapOf("obs1" to "com.example.app"),
        private val observeResult: UiObserveResult = UiObserveResult.PermissionRequired,
        private val readableProbe: UiNodeProbe? = null,
        private val tapResult: UiInjectResult = UiInjectResult.Dispatched("gesture", "com.example.app", false),
        private val swipeResult: UiInjectResult = UiInjectResult.Dispatched("gesture", "com.example.app", false),
        private val scrollResult: UiScrollResult = UiScrollResult.Finished(true, false, "com.example.app"),
        private val inputResult: UiInputResult = UiInputResult.Written("set_text", true, 0, false, "com.example.app", false),
        private val keyResult: UiInjectResult = UiInjectResult.Dispatched("global", "com.example.app", false),
        private val waitResult: UiWaitResult = UiWaitResult.Finished(true, 0, null),
        private val focusedPassword: Boolean? = null,
        private val observedNodes: Map<Int, UiNodeProbe> = emptyMap(),
    ) : UiObserveBackend, UiActionBackend {
        var tapCalled = false
        var swipeCalled = false

        override fun genOf(observationId: String): Long? = genMap[observationId]
        override fun latest(): ObservationRef? = latestRef
        override fun foregroundPackage(): String? = foreground
        override fun observationPackage(observationId: String): String? = observationPackageMap[observationId]
        override fun observe(request: UiObserveRequest, env: ToolEnvironment) = observeResult
        override fun focusedInputIsPassword(): Boolean? = focusedPassword
        override fun observedNode(observationId: String, index: Int): UiNodeProbe? = observedNodes[index]

        override fun backend(env: ToolEnvironment): InjectionBackend = when {
            env.accessibilityUsable -> InjectionBackend.ACCESSIBILITY
            env.rootAvailable -> InjectionBackend.ROOT_INPUT
            else -> InjectionBackend.NONE
        }

        override fun readableNodeAtPoint(x: Double, y: Double): UiNodeProbe? = readableProbe
        override fun tap(request: UiTapRequest, env: ToolEnvironment): UiInjectResult {
            tapCalled = true; return tapResult
        }
        override fun swipe(request: UiSwipeRequest, env: ToolEnvironment): UiInjectResult {
            swipeCalled = true; return swipeResult
        }
        override fun scroll(request: UiScrollRequest, env: ToolEnvironment) = scrollResult
        override fun input(request: UiInputRequest, env: ToolEnvironment) = inputResult
        override fun key(request: UiKeyRequest, env: ToolEnvironment) = keyResult
        override fun waitFor(request: UiWaitRequest, env: ToolEnvironment, checkCancelled: () -> Unit) = waitResult
    }
}
