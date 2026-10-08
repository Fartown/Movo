package io.github.fartown.movo.agent.runtime

import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.tools.core.ToolSwitches
import org.junit.Assert.assertEquals
import org.junit.Test

/** 运行中现读「设置 → 工具」开关，和任务开始时的配置取与：只能中途关，不能中途开（重构前的行为）。 */
class LiveToolSwitchesTest {
    private val allOn = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1",
        apiKey = "test-key",
        model = "test-model",
        systemPrompt = "",
        terminalTools = true,
        browserTools = true,
        deviceDirectTools = true,
        deviceSensitiveReadTools = true,
        deviceSensitiveActionTools = true,
    )

    private fun permissions(on: Boolean) = AgentRuntimePolicy.Permissions(
        terminalTools = on,
        browserTools = on,
        deviceDirectTools = on,
        deviceSensitiveReadTools = on,
        deviceSensitiveActionTools = on,
        thinking = true,
    )

    @Test
    fun turnedOffNow_isOffEvenThoughTheRunStartedWithItOn() {
        assertEquals(
            ToolSwitches(browser = false, deviceDirect = false, terminal = false, sensitiveRead = false, sensitiveAction = false),
            liveToolSwitches(allOn, permissions(on = false)),
        )
    }

    @Test
    fun onlyTheSwitchTurnedOffChanges() {
        val now = permissions(on = true).copy(deviceSensitiveReadTools = false)
        assertEquals(ToolSwitches(sensitiveRead = false), liveToolSwitches(allOn, now))
    }

    @Test
    fun turnedOnMidRun_doesNotApplyToThisRun() {
        val startedOff = allOn.copy(terminalTools = false, browserTools = false)
        assertEquals(ToolSwitches(terminal = false, browser = false), liveToolSwitches(startedOff, permissions(on = true)))
    }
}
