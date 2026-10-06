package io.github.fartown.movo.agent.tools

import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.tools.core.MemoryScope
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.core.AndroidAgentLogger
import io.github.fartown.movo.flavor.FlavorModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 电视版工具白名单（实施方案 §5.6）与无交互入口。 */
@RunWith(RobolectricTestRunner::class)
class TvToolWhitelistTest {

    private val capableEnv = ToolEnvironment(
        rootAvailable = true,
        accessibilityAvailable = true,
        notificationAccess = true,
        usageAccess = true,
        locationAccess = true,
        colorOs = true,
        linuxReady = true,
        conversationBound = true,
        memoryScope = MemoryScope.REAL,
        interactive = FlavorModule.interactionCards,
    )

    private fun subsystem() = AgentToolSubsystem(
        services = ToolServices(
            context = ApplicationProvider.getApplicationContext(),
            logger = AndroidAgentLogger,
            runId = "run-tv-whitelist",
            rootAvailable = { true },
        ),
        environment = { capableEnv },
    )

    @Test
    fun registersOnlyWhitelistedTools() {
        val expected = setOf(
            "device_read", "setting_read", "app_search", "app_open",
            "ui_observe", "ui_tap", "ui_focus", "ui_scroll", "ui_input", "ui_key", "ui_wait",
            "clipboard_read", "clipboard_write",
            "media_control", "volume_set", "video_search", "end_call",
            "memory_read", "memory_write", "conversation_read",
            "ask_user", "tool_search",
        )
        subsystem().use { sub ->
            assertEquals(expected, sub.pipeline.registryView.tools.map { it.name }.toSet())
        }
    }

    @Test
    fun voiceRunsGetEndCallAndItsUsageSection() {
        AgentToolSubsystem(
            services = ToolServices(
                context = ApplicationProvider.getApplicationContext(),
                logger = AndroidAgentLogger,
                runId = "run-tv-voice",
                rootAvailable = { true },
            ),
            environment = { capableEnv.copy(spokenReply = true) },
        ).use { sub ->
            val catalog = sub.pipeline.catalog()
            val names = (0 until catalog.length()).map {
                catalog.getJSONObject(it).getJSONObject("function").getString("name")
            }.toSet()
            org.junit.Assert.assertTrue("end_call" in names)
            val section = sub.pipeline.promptSections().single { it.id == "tv_end_call" }
            assertEquals(io.github.fartown.movo.tv.TvEndCallToolProvider.USAGE, section.text)
        }
    }

    @Test
    fun noInteractionCards_hidesAskUser() {
        assertFalse(FlavorModule.interactionCards)
        subsystem().use { sub ->
            val catalog = sub.pipeline.catalog()
            val names = (0 until catalog.length()).map {
                catalog.getJSONObject(it).getJSONObject("function").getString("name")
            }.toSet()
            assertFalse("电视不弹提问卡，ask_user 不应进目录", "ask_user" in names)
            assertFalse("打字对话里没有语音会话可结束", "end_call" in names)
            assertFalse(sub.pipeline.promptSections().any { it.id == "tv_end_call" })
        }
    }
}
