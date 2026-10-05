package io.github.fartown.movo.agent.runtime

import io.github.fartown.movo.agent.model.AgentModelClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用户关掉的能力要写进系统提示，避免模型打开对应应用看屏幕绕过开关（真机：关掉「读取敏感信息」后读出了通讯录）。 */
class SwitchNotesTest {
    private fun config(sensitiveRead: Boolean = true, sensitiveAction: Boolean = true, deviceDirect: Boolean = true) =
        AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid/v1",
            apiKey = "test-key",
            model = "test-model",
            systemPrompt = "",
            deviceSensitiveReadTools = sensitiveRead,
            deviceSensitiveActionTools = sensitiveAction,
            deviceDirectTools = deviceDirect,
        )

    @Test
    fun allSwitchesOn_noNotes() {
        assertEquals("", switchNotes(config()))
    }

    @Test
    fun sensitiveReadOff_forbidsReadingContactsThroughTheirApp() {
        val notes = switchNotes(config(sensitiveRead = false))
        assertTrue(notes.contains("读取敏感信息"))
        assertTrue(notes.contains("通讯录"))
        assertTrue(notes.contains("不要打开对应的应用"))
        assertFalse(notes.contains("敏感设备操作"))
    }

    @Test
    fun deviceDirectOff_stillAllowsDoingItThroughTheApp() {
        assertEquals("关掉设备直达只是不走捷径，界面操作仍可以", "", switchNotes(config(deviceDirect = false)))
    }

    @Test
    fun sensitiveActionOff_mentionsSettingsAndNetwork() {
        val notes = switchNotes(config(sensitiveAction = false))
        assertTrue(notes.contains("敏感设备操作"))
        assertTrue(notes.contains("修改系统设置"))
    }
}
