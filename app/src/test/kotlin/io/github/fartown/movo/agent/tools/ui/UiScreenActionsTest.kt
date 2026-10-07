package io.github.fartown.movo.agent.tools.ui

import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.tools.core.AgentTool
import io.github.fartown.movo.agent.tools.core.ApprovalDecision
import io.github.fartown.movo.agent.tools.core.ApprovalPolicy
import io.github.fartown.movo.agent.tools.core.ApprovalRequest
import io.github.fartown.movo.agent.tools.core.ContractTool
import io.github.fartown.movo.agent.tools.core.InjectionBackend
import io.github.fartown.movo.agent.tools.core.PermissionMode
import io.github.fartown.movo.agent.tools.core.ToolArgs
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 屏幕动作的回归（工具重构后丢掉或变差的能力，见 .docs/input-text-investigation/legacy-gap-audit.md）：
 * 坐标绑坐标系而不是内容代际、只在弹卡时抓树、越界报参数错误、ui_key 全局动作、until_text、hold_ms、
 * ui_wait 的 gone / duration / 命中节点、ui_observe 的 query、成功后的手势指示。
 */
@RunWith(RobolectricTestRunner::class)
class UiScreenActionsTest {

    private val env = ToolEnvironment(accessibilityAvailable = true)
    private val manualEnv = env.copy(approvalPolicy = ApprovalPolicy.MANUAL_BUILT_IN)
    private val appRuleEnv = env.copy(
        approvalPolicy = ApprovalPolicy(mode = PermissionMode.MANUAL, apps = setOf(APP)),
    )
    private val rootOnlyEnv = ToolEnvironment(rootAvailable = true)

    // ---- 坐标只绑坐标系（B1）----

    @Test
    fun coordinateTap_contentRefreshedSinceObservation_stillDispatches() {
        // 视频进度条每 0.12 秒刷一次内容：内容代际早就变了，坐标系没变，坐标照样能点。
        val fake = Fake().apply { genMap["obs1"] = 42L }
        val result = run(fake, tap(fake), """{"x":540,"y":1200}""")
        assertEquals("ok", result.status)
        assertEquals(1, fake.taps.size)
    }

    @Test
    fun coordinateSwipe_contentRefreshedSinceObservation_stillDispatches() {
        val fake = Fake().apply { genMap["obs1"] = 42L }
        val result = run(fake, swipe(fake), """{"x":540,"y":1800,"x2":540,"y2":600}""")
        assertEquals("ok", result.status)
        assertEquals(1, fake.swipes.size)
    }

    @Test
    fun coordinateTap_screenRotated_failsStale() {
        val fake = Fake().apply { nowFrame = frame.copy(width = 2400, height = 1080) }
        val result = run(fake, tap(fake), """{"x":540,"y":1000}""")
        assertEquals("STALE_OBSERVATION", result.errorCode)
        val message = result.outcome!!.error!!.message
        assertTrue(message, message.contains("屏幕方向或尺寸变了"))
        assertTrue(fake.taps.isEmpty())
    }

    @Test
    fun coordinateTap_windowOrAppChanged_failsStale() {
        val window = Fake().apply { nowFrame = frame.copy(windowGen = frame.windowGen!! + 1) }
        assertEquals("STALE_OBSERVATION", run(window, tap(window), """{"x":540,"y":1200}""").errorCode)
        val app = Fake().apply { nowFrame = frame.copy(packageName = "com.other.app") }
        val result = run(app, swipe(app), """{"x":540,"y":1800,"x2":540,"y2":600}""")
        assertEquals("STALE_OBSERVATION", result.errorCode)
        assertTrue(result.outcome!!.error!!.message.contains("前台应用变了"))
        assertTrue(window.taps.isEmpty() && app.swipes.isEmpty())
    }

    @Test
    fun coordinateSwipe_movoOverlayInFront_isNotAnAppChange() {
        val fake = Fake().apply { nowFrame = frame.copy(packageName = selfPackage) }
        assertEquals("ok", run(fake, swipe(fake), """{"x":540,"y":1800,"x2":540,"y2":600}""").status)
    }

    @Test
    fun coordinateTap_neverObserved_failsStale() {
        val fake = Fake().apply { latestRef = null }
        val result = run(fake, tap(fake), """{"x":540,"y":1200}""")
        assertEquals("STALE_OBSERVATION", result.errorCode)
        assertEquals("没有可用的屏幕观察", result.outcome!!.error!!.message)
    }

    @Test
    fun coordinateTap_windowChangesWhileWaitingForApproval_recheckFailsStale() {
        val fake = Fake()
        // 用户看确认卡的时候切走了：确认后复核用同一套坐标规则，不点下去。
        val switchingAway = Capture(ApprovalDecision.Approved) { fake.nowFrame = frame.copy(windowGen = 99L) }
        val result = run(fake, tap(fake), """{"x":540,"y":1200}""", appRuleEnv, switchingAway)
        assertEquals(1, switchingAway.seen.size)
        assertEquals("STALE_OBSERVATION", result.errorCode)
        assertTrue(fake.taps.isEmpty())
    }

    @Test
    fun elementTap_contentChanged_stillFailsStale() {
        // index 的校验不放宽：内容代际变了仍按过期处理。
        val fake = Fake().apply { genMap["obs1"] = 2L }
        val tool = UiTapTool(fake, fake)
        val resolution = tool.resolve(tool.parse(args("""{"index":0,"observation_id":"obs1"}"""), env), env)
        fake.genMap["obs1"] = 3L
        val verdict = tool.execute(tool.parse(args("""{"index":0,"observation_id":"obs1"}"""), env), resolution, ctx())
        assertEquals("STALE_OBSERVATION", (verdict as io.github.fartown.movo.agent.tools.core.Verdict.Failed).error.code.name)
        assertTrue(fake.taps.isEmpty())
    }

    @Test
    fun frameChange_comparesOnlyWhatBothSidesKnow() {
        val self = "io.github.fartown.movo"
        val known = CoordinateFrame(1080, 2400, 3L, APP)
        assertNull(frameChange(known, CoordinateFrame(0, 0, null, null), self))
        assertNull(frameChange(CoordinateFrame(0, 0, null, null), known, self))
        assertNull(frameChange(known, known.copy(packageName = self), self))
        assertTrue(frameChange(known, known.copy(width = 2400, height = 1080), self)!!.contains("1080x2400 → 2400x1080"))
        assertTrue(frameChange(known, known.copy(windowGen = 4L), self)!!.contains("前台窗口变了"))
        assertTrue(frameChange(known, known.copy(packageName = "com.b"), self)!!.contains("$APP → com.b"))
    }

    // ---- 只在要弹卡时才抓树（B2）----

    @Test
    fun coordinateTap_noCard_neverProbesTheTree() {
        for (environment in listOf(env, manualEnv)) {
            val fake = Fake(probe = UiNodeProbe(text = "转账"))
            assertEquals("ok", run(fake, tap(fake), """{"x":540,"y":1200}""", environment).status)
            assertEquals("YOLO、手动但没命中规则都不抓树", 0, fake.probeCalls)
        }
    }

    @Test
    fun coordinateTap_cardShown_probesOnceToNameTheTarget() {
        val fake = Fake(probe = UiNodeProbe(text = "转账"))
        val capture = Capture(ApprovalDecision.Declined)
        val result = run(fake, tap(fake), """{"x":540,"y":1200,"effect":"pay"}""", manualEnv, capture)
        assertEquals("USER_DECLINED", result.errorCode)
        assertEquals(1, fake.probeCalls)
        assertTrue(capture.seen.single().detail, capture.seen.single().detail.contains("点按「转账」"))
    }

    @Test
    fun swipe_neverProbesTheTree() {
        val fake = Fake(probe = UiNodeProbe(text = "列表"))
        val capture = Capture(ApprovalDecision.Approved)
        assertEquals("ok", run(fake, swipe(fake), """{"x":540,"y":1800,"x2":540,"y2":600}""", appRuleEnv, capture).status)
        assertEquals(0, fake.probeCalls)
        assertTrue(capture.seen.single().detail.contains("在屏幕上滑动"))
    }

    // ---- 越界坐标报参数错误（B6）----

    @Test
    fun coordinateTap_outOfRange_invalidArgumentsWithRangeAndNoCard() {
        val fake = Fake(probe = UiNodeProbe(text = "转账"))
        val capture = Capture(ApprovalDecision.Approved)
        val result = run(fake, tap(fake), """{"x":1080,"y":1200,"effect":"pay"}""", manualEnv, capture)
        assertEquals("INVALID_ARGUMENTS", result.errorCode)
        val error = result.outcome!!.error!!
        assertTrue(error.message, error.message.contains("(1080, 1200)"))
        val hint = error.hint!!
        assertTrue(hint, hint.contains("x 0–1079") && hint.contains("y 0–2399"))
        assertTrue("参数错了不弹卡", capture.seen.isEmpty())
        assertTrue(fake.taps.isEmpty() && fake.probeCalls == 0)
    }

    @Test
    fun swipe_endPointOutOfRange_invalidArguments() {
        val fake = Fake()
        val result = run(fake, swipe(fake), """{"x":540,"y":1800,"x2":540,"y2":2400}""")
        assertEquals("INVALID_ARGUMENTS", result.errorCode)
        assertTrue(fake.swipes.isEmpty())
    }

    @Test
    fun areaTap_nodeBoundsTouchingTheScreenEdge_areFine() {
        // 节点 bounds 的右下边常常正好等于屏幕宽高：区域只按中心查越界。
        val fake = Fake()
        assertEquals("ok", run(fake, tap(fake), """{"x":0,"y":2300,"x2":1080,"y2":2400}""").status)
    }

    @Test
    fun realBackend_pointOutsideCoordSpace_isNotAnInternalError() {
        val backend = RealUiScreenBackend(ApplicationProvider.getApplicationContext(), AndroidAgentLogger, rootAvailable = { false })
        val result = backend.tap(UiTapRequest(UiTarget.Point(-5.0, 10.0), 0, InjectionBackend.ACCESSIBILITY), env)
        assertTrue(result.toString(), result is UiInjectResult.NotActionable && result.reason.contains("超出屏幕范围"))
    }

    // ---- ui_key 全局动作（#7）----

    @Test
    fun uiKey_schemaListsOnlyKeysTheDeviceCanDo() {
        val fake = Fake()
        fun keys(environment: ToolEnvironment, sdk: Int) = UiKeyTool(fake, fake, sdkInt = sdk).schema(environment)
            .getJSONObject("properties").getJSONObject("key").getJSONArray("enum").let { array ->
                (0 until array.length()).map(array::getString)
            }
        val globals = listOf("lock_screen", "screenshot", "dismiss_notifications")
        assertTrue(keys(env, 34).containsAll(globals))
        assertTrue("Android 12 以下没有收起通知栏的无障碍动作", "dismiss_notifications" !in keys(env, 30))
        assertTrue(keys(env, 30).containsAll(listOf("lock_screen", "screenshot")))
        assertTrue("有 Root 就能回退", keys(env.copy(rootAvailable = true), 30).containsAll(globals))
        assertTrue("只有 Root 也能回退", keys(rootOnlyEnv, 34).containsAll(globals))
        assertEquals(listOf("back", "home"), keys(env.copy(touchscreen = false), 34))
    }

    @Test
    fun uiKey_keyTheDeviceCannotDo_invalidArgumentsWithoutReachingBackend() {
        val fake = Fake()
        val result = run(fake, ContractTool(UiKeyTool(fake, fake, sdkInt = 30)), """{"key":"dismiss_notifications"}""")
        assertEquals("INVALID_ARGUMENTS", result.errorCode)
        assertTrue(result.outcome!!.error!!.hint!!.contains("Android 12"))
        assertTrue(fake.keys.isEmpty())
    }

    @Test
    fun uiKey_globalActionsReachTheBackend() {
        val fake = Fake()
        val tool = ContractTool(UiKeyTool(fake, fake, sdkInt = 34))
        for (key in listOf("lock_screen", "screenshot", "dismiss_notifications")) {
            assertEquals(key, "ok", run(fake, tool, """{"key":"$key"}""").status)
        }
        assertEquals(listOf(UiKeyCode.LOCK_SCREEN, UiKeyCode.SCREENSHOT, UiKeyCode.DISMISS_NOTIFICATIONS), fake.keys.map { it.key })
        assertEquals("按「收起通知栏」", tool.stepTitle(args("""{"key":"dismiss_notifications"}"""), env))
        assertEquals("按「锁屏」", tool.stepTitle(args("""{"key":"lock_screen"}"""), env))
    }

    // ---- ui_scroll until_text（#8）----

    @Test
    fun scrollUntil_textAppearsAfterThreeScrolls_foundAndCounted() {
        val fake = Fake().apply { textVisibleAfter = 3 }
        val result = run(fake, scroll(fake), """{"direction":"down","until_text":"设置"}""")
        val json = JSONObject(result.content)
        assertEquals("ok", json.getString("status"))
        assertTrue(json.getBoolean("effect_verified"))
        assertTrue(json.getJSONObject("data").getBoolean("found"))
        assertEquals(3, json.getJSONObject("data").getInt("scrolls"))
        assertEquals(3, fake.scrollCalls)
        assertEquals("找到「设置」 · 滚动 3 次", result.outcome!!.view!!.summary)
    }

    @Test
    fun scrollUntil_alreadyOnScreen_doesNotScroll() {
        val fake = Fake().apply { textVisibleAfter = 0 }
        val data = JSONObject(run(fake, scroll(fake), """{"direction":"down","until_text":"设置"}""").content).getJSONObject("data")
        assertTrue(data.getBoolean("found"))
        assertEquals(0, data.getInt("scrolls"))
        assertEquals(0, fake.scrollCalls)
    }

    @Test
    fun scrollUntil_reachesTheEnd_stopsNotFound() {
        val fake = Fake().apply {
            scrollScript += UiScrollResult.Finished(true, false, APP)
            scrollScript += UiScrollResult.Finished(true, true, APP)
        }
        val result = run(fake, scroll(fake), """{"direction":"down","until_text":"不存在"}""")
        val data = JSONObject(result.content).getJSONObject("data")
        assertFalse(data.getBoolean("found"))
        assertEquals(2, data.getInt("scrolls"))
        assertTrue(data.getBoolean("at_boundary"))
        assertEquals(2, fake.scrollCalls)
        assertEquals("没找到「不存在」 · 滚动 2 次 · 到头了", result.outcome!!.view!!.summary)
    }

    @Test
    fun scrollUntil_neverFound_stopsAtTheLimit() {
        val fake = Fake()
        val data = JSONObject(run(fake, scroll(fake), """{"direction":"down","until_text":"不存在"}""").content).getJSONObject("data")
        assertFalse(data.getBoolean("found"))
        assertEquals(UiScrollTool.MAX_UNTIL_SCROLLS, data.getInt("scrolls"))
        assertEquals(UiScrollTool.MAX_UNTIL_SCROLLS, fake.scrollCalls)
    }

    @Test
    fun scrollUntil_withIndex_onlyTheFirstScrollUsesIt() {
        // 滚一下 index 就失效：之后改滚页面上最主要的可滚动区域。
        val fake = Fake().apply { textVisibleAfter = 3 }
        run(fake, scroll(fake), """{"direction":"down","index":2,"observation_id":"obs1","until_text":"设置"}""")
        assertEquals(3, fake.scrollRequests.size)
        assertEquals(2, fake.scrollRequests[0].element?.index)
        assertTrue(fake.scrollRequests.drop(1).all { it.element == null })
    }

    @Test
    fun scrollUntil_unconfirmedScrollAtTheEnd_reportsNotFoundInsteadOfUnknown() {
        // 真机：设置首页找不存在的「关于手机」，滚到底时那一下确认不了动没动，原来整次报 unknown。
        val fake = Fake().apply {
            scrollScript += UiScrollResult.Finished(true, false, APP)
            scrollScript += UiScrollResult.OutcomeUnknown
        }
        val result = run(fake, scroll(fake), """{"direction":"down","until_text":"关于手机"}""")
        val json = JSONObject(result.content)
        assertEquals("ok", json.getString("status"))
        val data = json.getJSONObject("data")
        assertFalse(data.getBoolean("found"))
        assertEquals(1, data.getInt("scrolls"))
        assertTrue(data.getString("stopped"), data.getString("stopped").contains("可能已经到头"))
    }

    @Test
    fun scrollUntil_unconfirmedScrollButTextNowVisible_found() {
        val fake = Fake().apply {
            textVisibleAfter = 1
            unknownScrollMoves = true
            scrollScript += UiScrollResult.OutcomeUnknown
        }
        val data = JSONObject(run(fake, scroll(fake), """{"direction":"down","until_text":"设置"}""").content).getJSONObject("data")
        assertTrue(data.getBoolean("found"))
    }

    @Test
    fun scrollUntil_failures_firstReportedLaterStopped() {
        val first = Fake().apply { scrollScript += UiScrollResult.NotActionable("不可滚动") }
        assertEquals("NOT_ACTIONABLE", run(first, scroll(first), """{"direction":"down","until_text":"x"}""").errorCode)

        val later = Fake().apply {
            scrollScript += UiScrollResult.Finished(true, false, APP)
            scrollScript += UiScrollResult.NotActionable("节点没了")
        }
        val data = JSONObject(run(later, scroll(later), """{"direction":"down","until_text":"x"}""").content).getJSONObject("data")
        assertEquals(1, data.getInt("scrolls"))
        assertTrue(data.getString("stopped").contains("节点没了"))
    }

    // ---- ui_swipe hold_ms（#9）----

    @Test
    fun swipe_holdMs_onlyInSchemaWithAccessibility() {
        val fake = Fake()
        assertTrue(UiSwipeTool(fake, fake).schema(env).getJSONObject("properties").has("hold_ms"))
        assertFalse(UiSwipeTool(fake, fake).schema(rootOnlyEnv).getJSONObject("properties").has("hold_ms"))
    }

    @Test
    fun swipe_holdMs_rootOnly_unsupportedWithoutSwiping() {
        val fake = Fake()
        val result = run(fake, swipe(fake), """{"x":540,"y":1800,"x2":540,"y2":600,"hold_ms":600}""", rootOnlyEnv)
        assertEquals("UNSUPPORTED", result.errorCode)
        assertTrue(fake.swipes.isEmpty())
    }

    @Test
    fun swipe_holdMs_reachesBackendAndIsTitledAsDrag() {
        val fake = Fake()
        val tool = swipe(fake)
        val drag = """{"x":540,"y":600,"x2":540,"y2":1800,"hold_ms":600}"""
        assertEquals("ok", run(fake, tool, drag).status)
        assertEquals(600, fake.swipes.single().holdMs)
        assertEquals("按住向下拖动", tool.stepTitle(args(drag), env))
    }

    // ---- ui_wait（#10、B13、B14）----

    @Test
    fun textCheck_goneNeedsReadableNodesWithoutTheText() {
        val loading = listOf(UiNodeProbe(text = "加载中…"), UiNodeProbe(desc = "返回"))
        val loaded = listOf(UiNodeProbe(text = "订单详情"))
        assertFalse(textCheck(loading, "加载中", WaitMatch.CONTAINS, gone = true).met)
        assertTrue(textCheck(loaded, "加载中", WaitMatch.CONTAINS, gone = true).met)
        assertFalse("读不到屏幕不算消失", textCheck(null, "加载中", WaitMatch.CONTAINS, gone = true).met)
        assertFalse("空树不算消失", textCheck(emptyList(), "加载中", WaitMatch.CONTAINS, gone = true).met)
        val appeared = textCheck(loading, "返回", WaitMatch.EXACT, gone = false)
        assertTrue(appeared.met)
        assertEquals("返回", appeared.node!!.desc)
        assertFalse(textCheck(loaded, "订单", WaitMatch.EXACT, gone = false).met)
        assertTrue(textCheck(loaded, "订单", WaitMatch.PREFIX, gone = false).met)
        assertTrue(textCheck(loaded, "订单.+情", WaitMatch.REGEX, gone = false).met)
    }

    @Test
    fun uiWait_goneAndMatchedNodeReachTheModel() {
        val node = UiNodeProbe(text = "完成", bounds = listOf(0, 0, 100, 50))
        val fake = Fake().apply { waitResult = UiWaitResult.Finished(true, 120, node) }
        val tool = ContractTool(UiWaitTool(fake))
        val json = JSONObject(run(fake, tool, """{"text":"完成"}""").content)
        assertEquals("完成", json.getJSONObject("data").getJSONObject("node").getString("text"))
        val gone = run(fake, tool, """{"text":"加载中","gone":true}""")
        assertTrue(fake.waits.last().gone)
        assertEquals("已消失 · 0.1 秒", gone.outcome!!.view!!.summary)
    }

    @Test
    fun uiWait_durationOverAMinute_invalidArguments() {
        val fake = Fake()
        assertEquals("INVALID_ARGUMENTS", run(fake, ContractTool(UiWaitTool(fake)), """{"duration_ms":60001}""").errorCode)
    }

    @Test
    fun realBackend_durationWaitsInFullPastTheTextTimeout() {
        val backend = RealUiScreenBackend(ApplicationProvider.getApplicationContext(), AndroidAgentLogger, rootAvailable = { false })
        val result = backend.waitFor(UiWaitRequest(null, WaitMatch.CONTAINS, false, null, durationMs = 1_200, timeoutMs = 500), env)
        result as UiWaitResult.Finished
        assertTrue(result.matched)
        assertTrue("等满 1.2 秒，实际 ${result.elapsedMs}", result.elapsedMs >= 1_200)
    }

    @Test
    fun realBackend_goneOnAnUnreadableScreen_timesOutUnmatched() {
        // 无障碍说可用但服务没连上：读不到屏幕，不能当成文字已经消失。
        val backend = RealUiScreenBackend(ApplicationProvider.getApplicationContext(), AndroidAgentLogger, rootAvailable = { false })
        val result = backend.waitFor(UiWaitRequest("加载中", WaitMatch.CONTAINS, true, null, null, timeoutMs = 500), env)
        result as UiWaitResult.Finished
        assertFalse(result.matched)
        assertTrue(result.elapsedMs >= 500)
    }

    // ---- ui_observe query（#11）----

    private fun observed(vararg nodes: UiObservedNode, truncated: Boolean = false) = UiObserveResult.Observed(
        observationId = "obs9", gen = 1L, packageName = APP, coordWidth = 1080, coordHeight = 2400, focusedIndex = null,
        nodes = nodes.toList(), nodesTruncated = truncated, screenshotAttached = false, screenshotQuality = null, screenshot = null,
    )

    @Test
    fun observeQuery_keepsOnlyMatchingNodesWithTheirIndex() {
        val fake = Fake().apply {
            observeResult = observed(
                UiObservedNode(index = 0, text = "返回"),
                UiObservedNode(index = 1, text = "WLAN 设置"),
                UiObservedNode(index = 2, desc = "蓝牙设置"),
                UiObservedNode(index = 3, text = "关于手机"),
            )
        }
        val data = JSONObject(run(fake, ContractTool(UiObserveTool(fake)), """{"query":"设置"}""").content).getJSONObject("data")
        val nodes = data.getJSONArray("nodes")
        assertEquals(listOf(1, 2), (0 until nodes.length()).map { nodes.getJSONObject(it).getInt("index") })
        assertEquals(2, data.getJSONObject("query").getInt("matched"))
        assertEquals(4, data.getJSONObject("query").getInt("total"))
        assertFalse(data.getJSONObject("query").has("note"))
        assertEquals("带 query 时按上限抓，再过滤", UiObserveTool.MAX_NODES, fake.observeRequests.single().maxNodes)
    }

    @Test
    fun observeQuery_noMatch_saysSo() {
        val fake = Fake().apply { observeResult = observed(UiObservedNode(index = 0, text = "返回")) }
        val result = run(fake, ContractTool(UiObserveTool(fake)), """{"query":"转账"}""")
        val data = JSONObject(result.content).getJSONObject("data")
        assertFalse(data.has("nodes"))
        assertEquals(0, data.getJSONObject("query").getInt("matched"))
        assertTrue(data.getJSONObject("query").getString("note").contains("没有文字或描述含「转账」"))
        assertTrue(result.outcome!!.view!!.summary!!.contains("没找到「转账」"))
    }

    @Test
    fun observeQuery_moreMatchesThanMaxNodes_truncated() {
        val fake = Fake().apply {
            observeResult = observed(UiObservedNode(index = 0, text = "设置 A"), UiObservedNode(index = 1, text = "设置 B"))
        }
        val data = JSONObject(run(fake, ContractTool(UiObserveTool(fake)), """{"query":"设置","max_nodes":1}""").content)
            .getJSONObject("data")
        assertEquals(1, data.getJSONArray("nodes").length())
        assertTrue(data.getBoolean("nodes_truncated"))
        assertEquals(2, data.getJSONObject("query").getInt("matched"))
    }

    @Test
    fun observeQuery_withNodesFalse_invalidArguments() {
        val fake = Fake()
        assertEquals(
            "INVALID_ARGUMENTS",
            run(fake, ContractTool(UiObserveTool(fake)), """{"query":"设置","nodes":false}""").errorCode,
        )
    }

    // ---- 成功后才显示手势指示（#13 / B7）----

    @Test
    fun tap_showsTouchOnlyAfterItWasDispatched() {
        val fake = Fake()
        run(fake, tap(fake), """{"x":540,"y":1200,"hold_ms":800}""")
        assertEquals(listOf<UiTouch>(UiTouch.Press(540, 1200, 800)), fake.touches)
        for (failure in listOf(UiInjectResult.OutcomeUnknown, UiInjectResult.NotActionable("挡住了"), UiInjectResult.SystemRejected)) {
            fake.tapResult = failure
            run(fake, tap(fake), """{"x":540,"y":1200}""")
        }
        assertEquals("没送达或不确定时不画指示", 1, fake.touches.size)
    }

    @Test
    fun swipe_showsDragAfterItWasDispatched_nonTouchDevicesShowNothing() {
        val fake = Fake()
        run(fake, swipe(fake), """{"x":540,"y":1800,"x2":540,"y2":600}""")
        assertTrue(fake.touches.single() is UiTouch.Drag)
        val television = Fake()
        run(television, tap(television), """{"index":0,"observation_id":"obs1"}""", env.copy(touchscreen = false))
        assertEquals(1, television.taps.size)
        assertTrue("没有触屏的设备不画手势", television.touches.isEmpty())
    }

    // ---- 工具 ----

    private fun tap(fake: Fake) = ContractTool(UiTapTool(fake, fake))
    private fun swipe(fake: Fake) = ContractTool(UiSwipeTool(fake, fake))
    private fun scroll(fake: Fake) = ContractTool(UiScrollTool(fake, fake))

    private fun args(json: String) = ToolArgs(JSONObject(json))

    private fun ctx(environment: ToolEnvironment = env) = io.github.fartown.movo.agent.tools.core.ToolContext(
        appContext = ApplicationProvider.getApplicationContext(),
        logger = AndroidAgentLogger,
        runId = "run1",
        toolCallId = "c1",
        env = environment,
        interaction = UserInteraction.NONE,
        cancelled = { false },
    )

    private fun run(
        fake: Fake,
        tool: AgentTool,
        argsJson: String,
        environment: ToolEnvironment = env,
        interaction: UserInteraction = UserInteraction.NONE,
    ): AgentModelClient.ToolResult = ToolPipeline(
        registry = ToolRegistry(listOf(object : ToolProvider { override val tools = listOf(tool) })),
        environment = { environment },
        appContext = ApplicationProvider.getApplicationContext(),
        logger = AndroidAgentLogger,
        runId = "run1",
        cancelled = { false },
        interaction = interaction,
    ).also { it.catalog() }.execute(AgentModelClient.ToolCall("c1", tool.name, argsJson))

    /** 记下弹过的卡；[onAsk] 模拟用户看卡期间屏幕发生的变化。 */
    private class Capture(
        private val decision: ApprovalDecision,
        private val onAsk: () -> Unit = {},
    ) : UserInteraction {
        val seen = mutableListOf<ApprovalRequest>()
        override val available = true
        override fun ask(question: UserQuestion, timeoutMs: Long) = UserAnswer.Declined
        override fun approve(request: ApprovalRequest, timeoutMs: Long): ApprovalDecision {
            seen += request
            onAsk()
            return decision
        }
    }

    private companion object {
        const val APP = "com.example.app"
        val frame = CoordinateFrame(1080, 2400, 7L, APP)
    }

    /** 假后端：最近一次观察 obs1（coord_space 1080x2400），坐标系可调，记录每次调用。 */
    private class Fake(private val probe: UiNodeProbe? = null) : UiObserveBackend, UiActionBackend {
        override val selfPackage = "io.github.fartown.movo"
        var latestRef: ObservationRef? = ObservationRef("obs1", 1L, 1080, 2400)
        val genMap = mutableMapOf("obs1" to 1L)
        var nowFrame: CoordinateFrame? = frame
        var probeCalls = 0
        var textVisibleAfter: Int? = null
        var scrollCalls = 0
        var unknownScrollMoves = false
        val scrollRequests = mutableListOf<UiScrollRequest>()
        private var moves = 0
        val scrollScript = ArrayDeque<UiScrollResult>()
        var tapResult: UiInjectResult = UiInjectResult.Dispatched("gesture", APP, false, touch = UiTouch.Press(540, 1200, 800))
        var waitResult: UiWaitResult = UiWaitResult.Finished(true, 0, null)
        var observeResult: UiObserveResult = UiObserveResult.PermissionRequired
        val taps = mutableListOf<UiTapRequest>()
        val swipes = mutableListOf<UiSwipeRequest>()
        val keys = mutableListOf<UiKeyRequest>()
        val waits = mutableListOf<UiWaitRequest>()
        val touches = mutableListOf<UiTouch>()
        val observeRequests = mutableListOf<UiObserveRequest>()

        override fun genOf(observationId: String): Long? = genMap[observationId]
        override fun latest(): ObservationRef? = latestRef
        override fun observationFrame(observationId: String): CoordinateFrame? =
            frame.takeIf { observationId == latestRef?.observationId }
        override fun currentFrame(): CoordinateFrame? = nowFrame
        override fun foregroundPackage(): String? = APP
        override fun observationPackage(observationId: String): String? = APP
        override fun observe(request: UiObserveRequest, env: ToolEnvironment): UiObserveResult {
            observeRequests += request
            return observeResult
        }

        override fun backend(env: ToolEnvironment): InjectionBackend = when {
            env.accessibilityUsable -> InjectionBackend.ACCESSIBILITY
            env.rootAvailable -> InjectionBackend.ROOT_INPUT
            else -> InjectionBackend.NONE
        }

        override fun readableNodeAtPoint(x: Double, y: Double): UiNodeProbe? {
            probeCalls++
            return probe
        }

        override fun findText(text: String): UiNodeProbe? =
            textVisibleAfter?.takeIf { moves >= it }?.let { UiNodeProbe(text = text) }

        override fun showTouch(touch: UiTouch) {
            touches += touch
        }

        override fun tap(request: UiTapRequest, env: ToolEnvironment): UiInjectResult {
            taps += request
            return tapResult
        }

        override fun swipe(request: UiSwipeRequest, env: ToolEnvironment): UiInjectResult {
            swipes += request
            return UiInjectResult.Dispatched("gesture", APP, false, touch = UiTouch.Drag(540, 1800, 540, 600, 300))
        }

        override fun scroll(request: UiScrollRequest, env: ToolEnvironment): UiScrollResult {
            scrollCalls++
            scrollRequests += request
            val result = scrollScript.removeFirstOrNull() ?: UiScrollResult.Finished(true, false, APP)
            if (result is UiScrollResult.Finished && result.moved) moves++
            // 确认不了动没动的一下，实际上可能动了。
            if (result is UiScrollResult.OutcomeUnknown && unknownScrollMoves) moves++
            return result
        }

        override fun input(request: UiInputRequest, env: ToolEnvironment): UiInputResult = UiInputResult.OutcomeUnknown

        override fun key(request: UiKeyRequest, env: ToolEnvironment): UiInjectResult {
            keys += request
            return UiInjectResult.Dispatched("global", APP, false)
        }

        override fun waitFor(request: UiWaitRequest, env: ToolEnvironment, checkCancelled: () -> Unit): UiWaitResult {
            waits += request
            return waitResult
        }
    }
}
