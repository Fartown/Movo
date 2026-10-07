package io.github.fartown.movo.diagnostics.runlog

import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.model.AgentProviderClient
import io.github.fartown.movo.agent.model.AssistantBlockKind
import io.github.fartown.movo.agent.model.EndpointKind
import io.github.fartown.movo.agent.model.ProviderCapabilities
import io.github.fartown.movo.agent.model.ProviderEvent
import io.github.fartown.movo.agent.model.ProviderRawPart
import io.github.fartown.movo.agent.model.ProviderRequest
import io.github.fartown.movo.agent.model.ProviderResponse
import io.github.fartown.movo.agent.model.TestToolCatalog
import io.github.fartown.movo.agent.runtime.AgentRunController
import io.github.fartown.movo.agent.runtime.AgentTokenUsage
import io.github.fartown.movo.diagnostics.DiagnosticContext
import io.github.fartown.movo.diagnostics.MemoryDiagnostics
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RunLogFlowTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var store: RunLogStore
    private val originalEnvironment = MemoryDiagnostics.environment

    @Before
    fun setUp() {
        store = RunLogStore(File(temp.root, "run-log"), elapsedClock = { MemoryDiagnostics.elapsedClock() })
        RunLog.install(store, "test-version") { true }
    }

    @After
    fun tearDown() {
        RunLog.install(null, "", { false })
        store.shutdown()
        MemoryDiagnostics.environment = originalEnvironment
    }

    @Test
    fun aRunIsRecordedInOrderFromStartToEnd() {
        val wireRun = "wire-${System.nanoTime()}"
        // 准备阶段（还没绑定运行号）就点了停止：绑定后补写。
        RunLog.stop(wireRun, "app.stop")
        var dir: String? = null
        MemoryDiagnostics.withRun(onStart = { run ->
            RunLog.open(run, linkedMapOf("operation" to "chat", "request" to "帮我写个评价")).also { dir = it }
        }) {
            MemoryDiagnostics.bindRun(wireRun, "conv-1")
            MemoryDiagnostics.record("environment", "screen.off", context = DiagnosticContext(), fields = mapOf("screen_on" to false))
            AgentModelClient.complete(
                config = config(),
                typedCatalog = TestToolCatalog::build,
                prompt = "帮我写个评价",
                provider = ScriptedProvider(),
                toolExecutor = AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("{\"ok\":true,\"text\":\"订单 3 个\"}") },
            )
            RunLog.end("completed")
        }
        assertTrue(store.awaitIdle())
        val lines = readLog(dir!!)

        assertEquals(1L, lines.first().long("seq"))
        assertEquals("run_start", lines.first()["t"])
        assertEquals("帮我写个评价", lines.first()["request"])
        assertEquals("test-version", lines.first()["app_version"])
        assertEquals("run_end", lines.last()["t"])
        assertEquals("completed", lines.last()["status"])
        assertEquals((1L..lines.size).toList(), lines.map { it.long("seq") })

        val main = lines.map { it["t"] }.filter { it != "meta" && it != "system" }
        assertEquals(
            listOf(
                "run_start", "user", "attempt_start", "attempt_end", "tool_start", "tool_end",
                "attempt_start", "attempt_end", "run_end",
            ),
            main,
        )

        val started = lines.first { it["t"] == "meta" }
        assertEquals("run.started", started["event"])
        assertEquals(dir, (started["data"] as Map<*, *>)["run_log"])
        val bound = lines.single { it["t"] == "meta" && it["event"] == "run.bound" }
        assertFalse((bound["data"] as Map<*, *>).containsKey("wire_run"))
        assertFalse((bound["data"] as Map<*, *>).containsKey("conversation"))
        val screen = lines.single { it["t"] == "system" && it["kind"] == "screen.off" }
        assertEquals("false", (screen["data"] as Map<*, *>)["screen_on"])
        assertTrue(lines.indexOfFirst { it["t"] == "meta" && it["event"] == "run.ended" } < lines.lastIndex)

        val stop = lines.single { it["t"] == "user" }
        assertEquals(listOf("stop", "app.stop"), listOf(stop["kind"], stop["source"]))

        val attempts = lines.filter { it["t"] == "attempt_end" }
        val first = attempts[0]
        assertEquals("ok", first["status"])
        assertEquals(listOf(mapOf("type" to "reasoning_content", "text" to "先查订单")), first["thinking"])
        assertEquals("好的", first["text"])
        assertEquals("tool_calls", first["finish_reason"])
        assertEquals(mapOf("id" to "call-1", "name" to "get_current_context", "args" to "{}"), (first["tool_calls"] as List<*>).single())
        assertEquals(mapOf("input" to JsonNumber("120"), "output" to JsonNumber("8")), first["usage"])
        // 第二次没有流式增量：用最终消息里的正文和思考补上。
        assertEquals("完成", attempts[1]["text"])
        assertEquals(listOf(mapOf("type" to "reasoning_content", "text" to "可以结束")), attempts[1]["thinking"])
        val starts = lines.filter { it["t"] == "attempt_start" }
        assertEquals(listOf("chat", "chat"), starts.map { it["purpose"] })
        assertEquals(listOf(first["attempt"], attempts[1]["attempt"]), starts.map { it["attempt"] })

        val toolEnd = lines.single { it["t"] == "tool_end" }
        assertEquals("{\"ok\":true,\"text\":\"订单 3 个\"}", toolEnd["result"])
        assertEquals("ok", toolEnd["status"])
        assertTrue(toolEnd.containsKey("dur_ms"))
    }

    @Test
    fun switchedOffMeansNoDirectoryAndNoRunLogField() {
        RunLog.install(store, "test-version") { false }
        var dir: String? = "unset"
        MemoryDiagnostics.withRun(onStart = { run -> RunLog.open(run, emptyMap()).also { dir = it } }) {
            MemoryDiagnostics.record("runtime", "noop")
        }
        assertTrue(store.awaitIdle())
        assertNull(dir)
        assertTrue(store.dirInfos().isEmpty())
        val started = MemoryDiagnostics.buffer.snapshot().entries.last { it.event == "run.started" }
        assertFalse(started.details.contains("run_log="))
    }

    /** 门槛 1：开着完整日志和关着时，环境采集、能力查询、工具执行的调用完全一样。 */
    @Test
    fun recordingAddsNoReads() {
        fun countReads(enabled: Boolean): List<Int> {
            RunLog.install(if (enabled) store else null, "test-version") { enabled }
            var environment = 0
            var capabilities = 0
            var tools = 0
            MemoryDiagnostics.environment = { environment++; mapOf("network" to "wifi") }
            MemoryDiagnostics.withRun(onStart = { run -> RunLog.open(run, emptyMap()) }) {
                AgentModelClient.complete(
                    config = config(),
                    typedCatalog = TestToolCatalog::build,
                    prompt = "开始",
                    provider = ScriptedProvider(),
                    capabilitiesProvider = {
                        capabilities++
                        io.github.fartown.movo.agent.tool.AgentToolCapabilities(rootAvailable = false)
                    },
                    toolExecutor = AgentModelClient.ToolExecutor { tools++; AgentModelClient.ToolResult("{\"ok\":true}") },
                )
                RunLog.end("completed")
            }
            assertTrue(store.awaitIdle())
            return listOf(environment, capabilities, tools)
        }
        assertEquals(countReads(enabled = false), countReads(enabled = true))
    }

    /** 门槛 1 的代码规则：记录侧不调用观察、无障碍、截图和设备状态接口。 */
    @Test
    fun runLogCodeTouchesNoDeviceApis() {
        val sources = File("src/main/kotlin/io/github/fartown/movo/diagnostics/runlog").listFiles()!!
            .filter { it.extension == "kt" }
        assertTrue(sources.isNotEmpty())
        val forbidden = listOf(
            "UiObservationRegistry", "AccessibilityService", "AccessibilityNodeInfo", "takeScreenshot", "DeviceScreenCapture",
            "PackageManager", "StatFs", "ClipboardManager", "ContentResolver", "LocationManager", "ApprovalSettings",
            "environmentSnapshot", "getSystemService",
        )
        sources.forEach { file ->
            val text = file.readText()
            forbidden.forEach { name -> assertFalse("${file.name} 引用了 $name", text.contains(name)) }
        }
    }

    private fun readLog(dir: String): List<Map<String, Any?>> {
        @Suppress("UNCHECKED_CAST")
        return File(store.root, "$dir/${RunLogStore.LOG_FILE}").readLines().map { RunLogJson.parse(it) as Map<String, Any?> }
    }

    private fun Map<String, Any?>.long(key: String): Long = (this[key] as JsonNumber).raw.toLong()

    private fun config() = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1",
        apiKey = "test-key",
        model = "test-model",
        systemPrompt = "",
        browserTools = false,
    )

    /** 第一次：流式思考（带原始类型）和正文、用量，给一个工具调用；第二次：不流式，直接给最终消息。 */
    private class ScriptedProvider : AgentProviderClient {
        override val id = "scripted"
        override val capabilities = ProviderCapabilities(
            endpoint = EndpointKind.CHAT_COMPLETIONS, streamingText = true, streamingToolCalls = true,
            imageInput = true, toolResultImages = false, strictTools = false, parallelToolCalls = false,
        )
        private var calls = 0

        override fun complete(
            request: ProviderRequest,
            runController: AgentRunController,
            onEvent: (ProviderEvent) -> Unit,
        ): ProviderResponse {
            calls++
            onEvent(ProviderEvent.RequestStarted)
            onEvent(ProviderEvent.ResponseHeaders(200))
            if (calls == 1) {
                onEvent(ProviderEvent.BlockStart(AssistantBlockKind.THINKING, 0))
                onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.THINKING, 0, "先查订单", listOf(ProviderRawPart("reasoning_content", "先查订单"))))
                onEvent(ProviderEvent.BlockStart(AssistantBlockKind.TEXT, 1))
                onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 1, "好的"))
                onEvent(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 120, outputTokens = 8)))
                return ProviderResponse(
                    JSONObject().put("role", "assistant").put("content", "好的").put("reasoning_content", "先查订单")
                        .put("finish_reason", "tool_calls")
                        .put("tool_calls", JSONArray().put(JSONObject().put("id", "call-1").put("type", "function")
                            .put("function", JSONObject().put("name", "get_current_context").put("arguments", "{}")))),
                )
            }
            return ProviderResponse(
                JSONObject().put("role", "assistant").put("content", "完成").put("reasoning_content", "可以结束")
                    .put("finish_reason", "stop"),
            )
        }
    }
}
