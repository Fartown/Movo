package io.github.fartown.movo.agent.tool

import io.github.fartown.movo.agent.device.BoundedRootCommandExecutor
import io.github.fartown.movo.agent.device.RootShellDeviceController
import io.github.fartown.movo.core.AgentLogger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RootlessDeviceToolsTest {
    @Test
    fun disconnectedGuiFailsBeforeShellAndDoesNotPublishObservation() {
        val controller = RootShellDeviceController(NoOpLogger, rootAvailable = { false })
        val observation = controller.observe(true, true, 60)
        assertEquals("ACCESSIBILITY_UNAVAILABLE", JSONObject(observation.content).getString("code"))
        assertNull(observation.elementObservation)
        assertNull(observation.coordinateSpace)
        assertNull(observation.image)
        listOf(
            controller.tap(10, 10),
            controller.swipe(10, 10, 20, 20, 100),
            controller.holdAndDrag(10, 10, 20, 20, 600, 300),
            controller.scroll("down"),
            controller.waitForPackage("example.app", 60_000),
            controller.pressKey("LOCK_SCREEN"),
        ).forEach { result ->
            assertEquals("ACCESSIBILITY_UNAVAILABLE", JSONObject(result).getString("code"))
        }
        // 读不到屏幕是 null，不是空屏：ui_wait 等文字消失不能把它当成「没有了」。
        assertNull(controller.currentNodes(120))
        assertEquals("ROOT_REQUIRED", JSONObject(controller.pressKey("PASTE")).getString("code"))
    }

    @Test
    fun rootExecutorRejectsBeforeStartingAProcess() {
        BoundedRootCommandExecutor(NoOpLogger, rootAvailable = { false }).use { executor ->
            assertEquals("ROOT_REQUIRED", executor.execute("id").errorCode)
        }
    }

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
