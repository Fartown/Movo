package io.github.fartown.movo.agent.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import io.github.fartown.movo.agent.device.AgentTouchInjection
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 屏幕动作在无障碍服务这一层：坐标系的窗口代际、锁屏 / 截屏 / 收起通知栏全局动作、先按住再拖的连续笔画。
 */
@RunWith(RobolectricTestRunner::class)
// 量路径（PathMeasure）要真的算：用原生图形。
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ScreenActionGesturesTest {

    @After
    fun tearDown() {
        AgentTouchInjection.overlayYield = null
    }

    // ---- 坐标系：前台窗口代际 ----

    @Test
    fun windowFramePolicy_onlyOtherAppsWholeWindowChangesCount() {
        val self = "io.github.fartown.movo"
        assertTrue(WindowFramePolicy.changesFrame("com.example.app", self, AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED))
        assertTrue("包名读不到也按换窗口算", WindowFramePolicy.changesFrame(null, self, 0))
        assertFalse("Movo 自己的悬浮球、展开卡、手势指示不算", WindowFramePolicy.changesFrame(self, self, 0))
        for (pane in listOf(
            AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_TITLE,
            AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED,
            AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED,
        )) {
            assertFalse("同一窗口里的面板变化不算", WindowFramePolicy.changesFrame("com.example.app", self, pane))
        }
    }

    @Test
    fun service_windowFrameGeneration_movesOnWindowSwitchesNotOnContentRefresh() {
        val service = Robolectric.setupService(AgentAccessibilityService::class.java)
        val start = service.windowFrameGeneration()
        fun send(type: Int, pkg: String) = service.onAccessibilityEvent(
            AccessibilityEvent(type).apply { packageName = pkg },
        )
        // 视频进度条、滚动、文字变化：坐标系不变。
        send(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "com.example.video")
        send(AccessibilityEvent.TYPE_VIEW_SCROLLED, "com.example.video")
        send(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, "com.example.video")
        send(AccessibilityEvent.TYPE_WINDOWS_CHANGED, "com.example.video")
        send(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, service.packageName)
        assertEquals(start, service.windowFrameGeneration())
        // 打开了新页面 / 对话框：坐标系可能不对了。
        send(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "com.example.video")
        assertEquals(start + 1, service.windowFrameGeneration())
    }

    // ---- 全局动作 ----

    @Test
    fun globalActions_lockScreenScreenshotAndDismissShade() {
        val service = Robolectric.setupService(AgentAccessibilityService::class.java)
        for (name in listOf("LOCK_SCREEN", "SCREENSHOT", "DISMISS_NOTIFICATIONS")) {
            assertTrue(name, service.globalActionResult(name).ok)
        }
        assertEquals(
            listOf(
                AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN,
                AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT,
                AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE,
            ),
            shadowOf(service).globalActionsPerformed,
        )
    }

    // ---- 先按住再拖 ----

    @Test
    fun holdThenDrag_staysAtTheStartForTheHoldThenDrags() {
        // 一段笔画：按住 600ms（手指在起点来回 2px），再用 400ms 拖到终点。
        val stroke = GestureStrokes.holdThenDrag(100f, 800f, 100f, 300f, holdMs = 600, dragMs = 400).single()
        assertEquals(1_000L, stroke.durationMs)
        val bounds = stroke.path.bounds()
        assertEquals(300f, bounds.top, 0.01f)
        assertTrue("按住阶段只在起点来回一两个像素", bounds.bottom <= 802f)
        val measure = PathMeasure(stroke.path, false)
        val pos = FloatArray(2)
        // 匀速走：第 590ms 还在起点附近，第 1000ms 到终点。
        measure.getPosTan(measure.length * 590f / 1_000f, pos, null)
        assertTrue(pos.contentToString(), kotlin.math.abs(pos[1] - 800f) <= 2.01f)
        measure.getPosTan(measure.length, pos, null)
        assertEquals(300f, pos[1], 0.01f)
        // 普通手势仍是一段、按完抬起。
        assertFalse(GestureStrokes.chain(listOf(GestureStrokes.Stroke(Path().apply { moveTo(1f, 1f) }, 50))).single().willContinue())
    }

    @Test
    fun holdAndDrag_dispatchesOneGestureAsAgentInjection() {
        val service = Robolectric.setupService(AgentAccessibilityService::class.java)
        shadowOf(service).setCanDispatchGestures(true)
        val yields = mutableListOf<Pair<Float, Float>>()
        AgentTouchInjection.overlayYield = { x, y ->
            yields += x to y
            null
        }
        val worker = Executors.newSingleThreadExecutor()
        try {
            // 手势要在后台线程同步等结果；主线程（测试线程）负责跑无障碍主线程任务和系统回调。
            val pending = worker.submit<AgentAccessibilityService.NodeActionResult> {
                service.gestureHoldAndDrag(100f, 800f, 100f, 300f, holdMs = 600, durationMs = 400)
            }
            val dispatched = shadowOf(service).gesturesDispatched
            awaitUntil { shadowOf(Looper.getMainLooper()).idle(); dispatched.size == 1 }
            assertTrue("整个手势期间按 Agent 注入标记", AgentTouchInjection.active)
            val gesture = dispatched.single()
            val stroke = gesture.description().getStroke(0)
            assertFalse("一段笔画，做完抬起", stroke.willContinue())
            assertEquals(1_000L, stroke.duration)
            gesture.callback().onCompleted(gesture.description())
            val result = pending.get(5, TimeUnit.SECONDS)
            assertTrue(result.message, result.ok)
            assertEquals("GESTURE_HOLD_DRAG", result.method)
            assertEquals("挡在起点上的浮层先让开", listOf(100f to 800f), yields)
            assertFalse(AgentTouchInjection.active)
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun holdAndDrag_cancelledMidway_isOutcomeUnknown() {
        val service = Robolectric.setupService(AgentAccessibilityService::class.java)
        shadowOf(service).setCanDispatchGestures(true)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val pending = worker.submit<AgentAccessibilityService.NodeActionResult> {
                service.gestureHoldAndDrag(100f, 800f, 100f, 300f, holdMs = 600, durationMs = 400)
            }
            val dispatched = shadowOf(service).gesturesDispatched
            awaitUntil { shadowOf(Looper.getMainLooper()).idle(); dispatched.size == 1 }
            dispatched[0].callback().onCancelled(dispatched[0].description())
            val result = pending.get(5, TimeUnit.SECONDS)
            assertFalse(result.ok)
            assertEquals("手指可能已经按下过，不能当成没执行", "ACTION_OUTCOME_UNKNOWN", result.code)
            assertEquals(1, dispatched.size)
        } finally {
            worker.shutdownNow()
        }
    }

    private fun Path.bounds(): RectF = RectF().also { @Suppress("DEPRECATION") computeBounds(it, true) }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "等待超时" }
            Thread.sleep(10)
        }
    }
}
