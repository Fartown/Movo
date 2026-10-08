package io.github.fartown.movo.agent.tools

import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.model.AgentToolCallValidator
import io.github.fartown.movo.agent.runtime.AgentRunController
import io.github.fartown.movo.agent.runtime.closeOnStop
import io.github.fartown.movo.agent.tools.core.AgentTool
import io.github.fartown.movo.agent.tools.core.MemoryScope
import io.github.fartown.movo.agent.tools.core.Risk
import io.github.fartown.movo.agent.tools.core.ToolArgBounds
import io.github.fartown.movo.agent.tools.core.ToolArgs
import io.github.fartown.movo.agent.tools.core.ToolAvailability
import io.github.fartown.movo.agent.tools.core.ToolContext
import io.github.fartown.movo.agent.tools.core.ToolDomain
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.agent.tools.core.ToolOutcome
import io.github.fartown.movo.agent.tools.core.ToolPipeline
import io.github.fartown.movo.agent.tools.core.ToolProvider
import io.github.fartown.movo.agent.tools.core.ToolRegistry
import io.github.fartown.movo.agent.tools.core.ToolSwitches
import io.github.fartown.movo.agent.tools.core.objectSchema
import io.github.fartown.movo.core.AndroidAgentLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 重构差异 T1：运行中关开关立刻生效（1）、按停止关掉工具（4）、上限类参数越界按边界处理（7）。
 */
@RunWith(RobolectricTestRunner::class)
class ToolPipelineGapTest {
    private class FakeTool(
        override val name: String,
        private val schema: JSONObject = objectSchema {},
        private val available: (ToolEnvironment) -> ToolAvailability = { ToolAvailability.Available },
        private val run: (ToolArgs, ToolContext) -> ToolOutcome = { _, _ -> ToolOutcome.ok() },
        override val thirdPartySchema: Boolean = false,
    ) : AgentTool {
        override val domain = ToolDomain.META
        override val description = "测试用"
        override fun parameters(env: ToolEnvironment) = schema
        override fun availability(env: ToolEnvironment) = available(env)
        override fun risk(args: ToolArgs, env: ToolEnvironment) = Risk.READ
        override fun execute(args: ToolArgs, ctx: ToolContext) = run(args, ctx)
    }

    private fun pipeline(
        vararg tools: AgentTool,
        env: ToolEnvironment = ToolEnvironment(memoryScope = MemoryScope.REAL),
        refresh: (ToolEnvironment) -> ToolEnvironment = { it },
        onClose: () -> Unit = {},
    ) = ToolPipeline(
        registry = ToolRegistry(
            listOf(
                object : ToolProvider {
                    override val tools = tools.toList()
                    override fun close() = onClose()
                },
            ),
        ),
        environment = { env },
        appContext = ApplicationProvider.getApplicationContext(),
        logger = AndroidAgentLogger,
        runId = "run1",
        cancelled = { false },
        refreshSwitches = refresh,
    ).also { it.catalog() }

    private fun call(name: String, args: String = "{}", id: String = "c1") = AgentModelClient.ToolCall(id, name, args)

    // ---- 1. 运行中关掉开关 ----

    @Test
    fun switchTurnedOffMidRun_nextCallIsBlockedWithoutWaitingForTheNextRound() {
        var sensitiveRead = true
        var runs = 0
        val p = pipeline(
            FakeTool("personal_search", run = { _, _ -> runs++; ToolOutcome.ok() }),
            refresh = { env -> env.copy(switches = env.switches.copy(sensitiveRead = sensitiveRead)) },
        )
        assertEquals("ok", p.execute(call("personal_search")).status)
        sensitiveRead = false
        val blocked = p.execute(call("personal_search", id = "c2"))
        assertEquals("error", blocked.status)
        assertEquals("DISABLED", blocked.errorCode)
        assertTrue(JSONObject(blocked.content).getString("message").contains("读取敏感信息"))
        assertEquals("关掉之后不再执行", 1, runs)
    }

    @Test
    fun switchTurnedOffMidRun_unavailableReasonNamesTheSwitch() {
        var sensitiveRead = true
        val p = pipeline(
            FakeTool("personal_search"),
            refresh = { env -> env.copy(switches = env.switches.copy(sensitiveRead = sensitiveRead)) },
        )
        assertEquals(null, p.unavailableReason("personal_search"))
        sensitiveRead = false
        val reason = p.unavailableReason("personal_search")
        assertEquals("DISABLED", reason?.first)
        assertTrue(reason!!.second.contains("读取敏感信息"))
        assertEquals("不认识的工具交给「未声明」处理", null, p.unavailableReason("no_such_tool"))
    }

    @Test
    fun memoryTurnedOffMidRun_memoryToolsStopToo() {
        var scope = MemoryScope.REAL
        val memoryTool = FakeTool(
            "memory_fake",
            available = { env ->
                if (env.memoryScope == MemoryScope.DISABLED) ToolAvailability.Unavailable(ToolErrorCode.DISABLED, "记忆未开启")
                else ToolAvailability.Available
            },
        )
        val p = pipeline(memoryTool, refresh = { env -> env.copy(memoryScope = scope) })
        assertEquals("ok", p.execute(call("memory_fake")).status)
        scope = MemoryScope.DISABLED
        assertEquals("DISABLED", p.execute(call("memory_fake", id = "c2")).errorCode)
    }

    @Test
    fun toolsSeeTheLiveSwitchesInTheirContext() {
        var terminal = true
        var seen: Boolean? = null
        val p = pipeline(
            FakeTool("checks_inside", run = { _, ctx -> seen = ctx.env.switches.terminal; ToolOutcome.ok() }),
            refresh = { env -> env.copy(switches = ToolSwitches(terminal = terminal)) },
        )
        terminal = false
        p.execute(call("checks_inside"))
        assertEquals("按来源在工具内判断开关的（file_read 等）也看到现读的值", false, seen)
    }

    // ---- 4. 按停止关掉工具 ----

    @Test
    fun stop_closesTheToolsSoABlockedCallReturnsAtOnce() {
        val released = CountDownLatch(1)
        val p = pipeline(
            FakeTool("slow_network", run = { _, _ ->
                // 假装是一次 MCP 请求 / 网页加载：工具被关掉时才返回。
                if (released.await(10, TimeUnit.SECONDS)) ToolOutcome.error(ToolErrorCode.NETWORK_ERROR, "已中断")
                else ToolOutcome.ok()
            }),
            onClose = { released.countDown() },
        )
        val controller = AgentRunController()
        val binding = controller.closeOnStop(p)
        var status: String? = null
        val worker = thread { status = p.execute(call("slow_network")).status }
        Thread.sleep(100)
        val started = System.nanoTime()
        controller.cancel()
        assertTrue("停止在主线程上调：关工具不能卡住调用方", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 200)
        worker.join(5_000)
        assertFalse("停止后调用应当马上返回", worker.isAlive)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3_000)
        assertEquals("error", status)
        binding.close()
    }

    @Test
    fun stop_slowCloseDoesNotBlockTheCaller() {
        val closed = CountDownLatch(1)
        val controller = AgentRunController()
        controller.closeOnStop(AutoCloseable { Thread.sleep(1_500); closed.countDown() })
        val started = System.nanoTime()
        controller.cancel()
        assertTrue("关终端要等进程退出，不能让主线程等", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 200)
        assertTrue("关闭仍会做完", closed.await(5, TimeUnit.SECONDS))
    }

    // ---- 7. 上限类参数越界按边界处理 ----

    private val listSchema = objectSchema {
        integer("limit", "条数，1–30", min = 1, max = 30)
        integer("hour", "小时 0–23", min = 0, max = 23)
        integer("wait_ms", "等待毫秒", min = 1000, max = 10_000)
    }

    @Test
    fun clamp_onlyBudgetParamsAndOnlyWhenOutOfRange() {
        val args = JSONObject().put("limit", 100).put("hour", 25).put("wait_ms", 10)
        val notes = ToolArgBounds.clamp(args, listSchema)
        assertEquals(30, args.getInt("limit"))
        assertEquals(1000, args.getInt("wait_ms"))
        assertEquals("时刻不夹：夹到 23 点会设错闹钟", 25, args.getInt("hour"))
        assertEquals(2, notes.size)
        assertTrue(notes.any { it.message == "limit=100 超出范围 1–30，已按 30 处理" })

        val inRange = JSONObject().put("limit", 5)
        assertTrue(ToolArgBounds.clamp(inRange, listSchema).isEmpty())
        val fraction = JSONObject().put("limit", 2.5)
        assertTrue("非整数交给校验报错", ToolArgBounds.clamp(fraction, listSchema).isEmpty())
        assertEquals(2.5, fraction.getDouble("limit"), 0.0)
    }

    @Test
    fun outOfRangeLimit_isClampedBeforeValidationAndTheResultSaysSo() {
        var seenLimit = 0
        val p = pipeline(
            FakeTool("fake_list", schema = listSchema, run = { args, _ ->
                seenLimit = args.int("limit", 10, 1..30)
                ToolOutcome.ok(JSONObject().put("count", seenLimit))
            }),
        )
        val validator = AgentToolCallValidator(p.catalog())
        val modelCall = call("fake_list", """{"limit":100}""")
        assertNotNull("不夹的话合同校验会直接拒绝", validator.validate(modelCall))

        val normalized = p.normalize(modelCall)
        assertNull(validator.validate(normalized))
        val result = JSONObject(p.execute(normalized).content)
        assertEquals("ok", result.getString("status"))
        assertEquals(30, seenLimit)
        assertTrue(result.getJSONArray("warnings").getJSONObject(0).getString("message").contains("已按 30 处理"))

        // 下一次调用不会带上一次的说明。
        val next = JSONObject(p.execute(p.normalize(call("fake_list", """{"limit":3}""", id = "c2"))).content)
        assertFalse(next.has("warnings"))
    }

    @Test
    fun clampNote_ofARejectedCall_doesNotLeakIntoTheNextCallWithTheSameId() {
        val p = pipeline(FakeTool("fake_list", schema = listSchema), FakeTool("other"))
        // 第 1 轮 tool_call_0 被夹紧后因别的原因被拒，没执行；第 2 轮同 id 换成了别的工具。
        p.normalize(call("fake_list", """{"limit":100}""", id = "tool_call_0"))
        val next = JSONObject(p.execute(p.normalize(call("other", "{}", id = "tool_call_0"))).content)
        assertFalse(next.has("warnings"))
    }

    @Test
    fun thirdPartySchema_isNotClamped() {
        val transfer = objectSchema { integer("amount", "金额", min = 1, max = 1000) }
        val p = pipeline(FakeTool("mcp_bank_transfer", schema = transfer, thirdPartySchema = true))
        val modelCall = call("mcp_bank_transfer", """{"amount":5000}""")
        assertEquals("第三方工具的同名参数不改，越界交给校验拒绝", modelCall, p.normalize(modelCall))
    }

    @Test
    fun outOfRangeHour_isLeftForValidationToReject() {
        val p = pipeline(FakeTool("fake_list", schema = listSchema))
        val normalized = p.normalize(call("fake_list", """{"hour":25}"""))
        assertEquals("""{"hour":25}""", normalized.argumentsJson)
        assertNotNull(AgentToolCallValidator(p.catalog()).validate(normalized))
    }
}
