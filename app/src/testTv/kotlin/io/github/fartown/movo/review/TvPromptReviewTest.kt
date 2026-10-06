package io.github.fartown.movo.review

import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.memory.AgentMemoryContext
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.model.AgentPromptBuilder
import io.github.fartown.movo.agent.skill.SkillContext
import io.github.fartown.movo.agent.tools.AgentToolSubsystem
import io.github.fartown.movo.agent.tools.ToolServices
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.core.AndroidAgentLogger
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TvPromptReviewTest {
    @Test fun tvPromptMustNotRequireAbsentTerminalTools() {
        AgentToolSubsystem(
            services = ToolServices(ApplicationProvider.getApplicationContext(), AndroidAgentLogger, "review", rootAvailable = { false }),
            environment = { ToolEnvironment(interactive = false) },
        ).use { subsystem ->
            assertFalse(subsystem.pipeline.registryView.tools.any { it.name.startsWith("terminal") })
            val prompt = AgentPromptBuilder.buildSystemMessages(
                AgentModelClient.loadConfig(), SkillContext.EMPTY, AgentMemoryContext.DISABLED, false,
                toolPromptSections = subsystem.pipeline.promptSections(),
            ).toString()
            assertFalse("TV prompt requires terminal despite excluding its provider", prompt.contains("必须调用 terminal"))
            assertFalse(prompt.contains("使用 browser_use"))
            assertFalse(prompt.contains("按默认参数调用 observe_screen"))
        }
    }
}
