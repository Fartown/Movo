package io.github.fartown.movo.agent.tools

import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.model.AgentPromptBuilder
import io.github.fartown.movo.agent.model.ResponsesRequestBuilder
import io.github.fartown.movo.agent.skill.SkillContext
import io.github.fartown.movo.agent.tools.core.MemoryScope
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolOutcome
import io.github.fartown.movo.agent.tools.core.ToolProjection
import io.github.fartown.movo.core.AndroidAgentLogger
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 把一次普通任务里模型实际收到的 Responses 请求（系统指令、工具目录、首条用户消息）导出为 JSON，
 * 供用真实接口离线复现模型行为。非断言测试：仅在设置环境变量 MOVO_DUMP_PROMPT=<path> 时写文件。
 */
@RunWith(RobolectricTestRunner::class)
class PromptDumpTest {
    @Test
    fun dumpPromptIfRequested() {
        val out = System.getenv("MOVO_DUMP_PROMPT") ?: return
        val prompt = System.getenv("MOVO_DUMP_PROMPT_TEXT") ?: "Create file /sdcard/Download/movo-test.txt with the content first line"
        // MOVO_DUMP_SPOKEN=MOVO_VOICE / XIAOAI：导出语音轮的请求（语音段、去掉 ask_user），供 .docs/voice-brevity/eval 回归。
        val spokenReply = System.getenv("MOVO_DUMP_SPOKEN")?.let { io.github.fartown.movo.agent.model.SpokenReply.valueOf(it) }
            ?: io.github.fartown.movo.agent.model.SpokenReply.NONE
        // 小米云真机：无 Root，无障碍、通知等权限已开。
        val env = ToolEnvironment(
            rootAvailable = false, accessibilityAvailable = true, notificationAccess = true,
            usageAccess = true, locationAccess = true, linuxReady = false,
            conversationBound = true, memoryScope = MemoryScope.REAL, interactive = true,
            spokenReply = spokenReply.spoken,
        )
        val config = AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid/api/plan/v3",
            apiKey = "redacted",
            model = "deepseek-v4-1-flash-260910",
            systemPrompt = "",
            terminalTools = true,
            browserTools = true,
            deviceDirectTools = true,
            deviceSensitiveReadTools = true,
            deviceSensitiveActionTools = true,
        )
        AgentToolSubsystem(
            services = ToolServices(
                context = ApplicationProvider.getApplicationContext(),
                logger = AndroidAgentLogger,
                runId = "dump",
                rootAvailable = { false },
            ),
            environment = { env },
        ).use { sub ->
            val guide = sub.pipeline.promptSections().map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
            val messages = AgentPromptBuilder.buildInitialMessages(
                config = config,
                prompt = prompt,
                images = emptyList(),
                history = emptyList(),
                skillContext = SkillContext(installedSkills = emptyList()),
                rootAvailable = false,
                spokenReply = spokenReply,
                toolGuide = guide,
                environment = AgentPromptBuilder.environmentLine(),
            )
            val request = ResponsesRequestBuilder.build(config, messages, sub.pipeline.catalog())
            val fileWriteOk = ToolProjection.render(
                ToolOutcome.ok(
                    JSONObject()
                        .put("path", "/sdcard/Download/movo-test.txt")
                        .put("bytes_written", 10)
                        .put("created", true),
                ),
            )
            File(out).writeText(
                JSONObject()
                    .put("request", request)
                    .put("file_write_ok_output", fileWriteOk)
                    .put("system_messages", JSONArray().also { arr ->
                        for (i in 0 until messages.length() - 1) arr.put(messages.getJSONObject(i))
                    })
                    .toString(2),
            )
        }
    }
}
