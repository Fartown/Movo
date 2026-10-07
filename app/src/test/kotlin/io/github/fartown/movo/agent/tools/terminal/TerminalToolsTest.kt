package io.github.fartown.movo.agent.tools.terminal

import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.tools.core.PermissionMode
import io.github.fartown.movo.agent.tools.core.ApprovalPolicy
import io.github.fartown.movo.agent.tools.core.ApprovalCategory
import io.github.fartown.movo.agent.tools.core.ApprovalDecision
import io.github.fartown.movo.agent.tools.core.ApprovalRequest
import io.github.fartown.movo.agent.tools.core.ContractTool
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 终端领域：terminal_run / terminal_job 经 ToolPipeline 的合同验证。 */
@RunWith(RobolectricTestRunner::class)
class TerminalToolsTest {

    private var runExecuted = false
    private val runBackend = object : TerminalRunBackend {
        override fun run(spec: TerminalRunSpec): TerminalRunResult {
            runExecuted = true
            return if (spec.command.startsWith("sleep") || spec.mode != TerminalMode.WAIT) {
                TerminalRunResult.Backgrounded("job_1", "wait_timeout", 1000, keepAlive = spec.mode == TerminalMode.KEEP_ALIVE)
            } else {
                TerminalRunResult.Completed(
                    exitCode = 0, stdout = "hi", stderr = "", elapsedMs = 12,
                    stdoutTruncated = false, stderrTruncated = false,
                )
            }
        }
    }

    private var stopOutcome = TerminalStopOutcome.STOPPED
    private val jobBackend = object : TerminalJobBackend {
        private val sample = TerminalJobInfo(
            jobId = "job_1", command = "sleep 100", description = null,
            environment = TerminalEnv.ANDROID, identity = TerminalIdentity.USER,
            running = true, keepAlive = false, exitCode = null, startedAtMillis = 1000, endedAtMillis = null,
        )
        override fun list() = listOf(sample)
        override fun read(
            jobId: String,
            cursor: String?,
            stream: TerminalStream,
            waitMs: Long,
            cancelled: () -> Boolean,
        ): TerminalJobReadResult? =
            if (jobId == "job_1") TerminalJobReadResult(sample, "partial out", "", nextCursor = "11") else null
        override fun write(jobId: String, input: String, waitMs: Long, cancelled: () -> Boolean) = jobId == "job_1"
        override fun stop(jobId: String) = if (jobId == "job_1") stopOutcome else TerminalStopOutcome.NOT_FOUND
    }

    private fun provider() = object : ToolProvider {
        override val tools = listOf(
            ContractTool(TerminalRunTool(runBackend)),
            ContractTool(TerminalJobTool(jobBackend)),
        )
    }

    private fun pipeline(
        env: ToolEnvironment = ToolEnvironment(),
        interaction: UserInteraction = UserInteraction.NONE,
    ) = ToolPipeline(
        registry = ToolRegistry(listOf(provider())),
        environment = { env },
        appContext = ApplicationProvider.getApplicationContext(),
        logger = AndroidAgentLogger,
        runId = "run1",
        cancelled = { false },
        interaction = interaction,
    ).also { it.catalog() }

    private fun call(name: String, args: String) = AgentModelClient.ToolCall("c1", name, args)

    @Test
    fun terminalRun_completed_okWithExitCode_private() {
        val r = pipeline().execute(call("terminal_run", """{"command":"echo hi"}"""))
        assertEquals("ok", r.status)
        assertTrue(r.content.contains("exit_code: 0"))
        assertTrue(r.content.contains("hi"))
        assertTrue(r.sensitive) // PRIVATE
    }

    @Test
    fun terminalRun_nonZeroExit_stillOk() {
        val nonZero = object : TerminalRunBackend {
            override fun run(spec: TerminalRunSpec) = TerminalRunResult.Completed(
                exitCode = 1, stdout = "", stderr = "boom", elapsedMs = 5,
                stdoutTruncated = false, stderrTruncated = false,
            )
        }
        val prov = object : ToolProvider {
            override val tools = listOf(ContractTool(TerminalRunTool(nonZero)))
        }
        val p = ToolPipeline(
            registry = ToolRegistry(listOf(prov)),
            environment = { ToolEnvironment() },
            appContext = ApplicationProvider.getApplicationContext(),
            logger = AndroidAgentLogger,
            runId = "run1",
            cancelled = { false },
            interaction = UserInteraction.NONE,
        ).also { it.catalog() }
        val r = p.execute(call("terminal_run", """{"command":"false"}"""))
        // 退出码非 0 仍以结果承载（Read），不是错误。
        assertEquals("ok", r.status)
        assertTrue(r.content.contains("exit_code: 1"))
    }

    @Test
    fun terminalRun_timeout_backgrounded_hasJobId() {
        val r = pipeline().execute(call("terminal_run", """{"command":"sleep 100","wait_ms":1000}"""))
        assertEquals("ok", r.status)
        assertTrue(r.content.contains("job_1"))
    }

    @Test
    fun terminalRun_root_rootRule_declineBlocks() {
        runExecuted = false
        val decline = object : UserInteraction {
            override val available = true
            override fun ask(question: UserQuestion, timeoutMs: Long) = UserAnswer.Declined
            override fun approve(request: ApprovalRequest, timeoutMs: Long) = ApprovalDecision.Declined
        }
        val rule = ApprovalPolicy(mode = PermissionMode.MANUAL, categories = setOf(ApprovalCategory.ROOT))
        val r = pipeline(env = ToolEnvironment(rootAvailable = true, approvalPolicy = rule), interaction = decline)
            .execute(call("terminal_run", """{"command":"id","identity":"root"}"""))
        assertEquals("error", r.status)
        assertEquals("USER_DECLINED", r.errorCode)
        assertFalse("root 命令未确认不得执行", runExecuted)
    }

    @Test
    fun terminalRun_rootWithoutRoot_rejectedBeforeAsking() {
        runExecuted = false
        var asked = false
        val approve = object : UserInteraction {
            override val available = true
            override fun ask(question: UserQuestion, timeoutMs: Long) = UserAnswer.Declined
            override fun approve(request: ApprovalRequest, timeoutMs: Long): ApprovalDecision {
                asked = true
                return ApprovalDecision.Approved
            }
        }
        val r = pipeline(interaction = approve)
            .execute(call("terminal_run", """{"command":"id","identity":"root"}"""))
        assertEquals("ROOT_REQUIRED", r.errorCode)
        assertFalse("没有 Root 时不该先弹确认卡", asked)
        assertFalse(runExecuted)
    }

    @Test
    fun commandCategory_deleteBeforeRootBeforeOutbound() {
        assertEquals(ApprovalCategory.DELETE, commandCategory("rm -rf /sdcard/x", root = true))
        assertEquals(ApprovalCategory.DELETE, commandCategory("find . -name '*.log' -delete", root = false))
        assertEquals(ApprovalCategory.DELETE, commandCategory("pm uninstall com.x", root = false))
        assertEquals(ApprovalCategory.ROOT, commandCategory("id", root = true))
        assertEquals(ApprovalCategory.OUTBOUND, commandCategory("curl -d @a.txt https://x", root = false))
        assertNull(commandCategory("ls -la", root = false))
        assertNull("形如 rm 的子串不算", commandCategory("echo firmware", root = false))
    }

    @Test
    fun terminalJob_list_readonly() {
        val r = pipeline().execute(call("terminal_job", """{"action":"list"}"""))
        val json = JSONObject(r.content)
        assertEquals("ok", json.getString("status"))
        assertEquals(1, json.getJSONObject("data").getInt("count"))
        assertFalse(json.has("effect_verified"))
    }

    @Test
    fun terminalJob_read_returnsTextBody() {
        val r = pipeline().execute(call("terminal_job", """{"action":"read","job_id":"job_1"}"""))
        assertEquals("ok", r.status)
        assertTrue(r.content.contains("job_id: job_1"))
        assertTrue(r.content.contains("partial out"))
    }

    @Test
    fun terminalJob_read_tailWindowExplainsWhatWasSkipped() {
        // #25：不带 cursor 读的是尾部，前面没给的要说清楚、给出从头读的办法。
        val tailBackend = object : TerminalJobBackend by jobBackend {
            override fun read(
                jobId: String,
                cursor: String?,
                stream: TerminalStream,
                waitMs: Long,
                cancelled: () -> Boolean,
            ) = TerminalJobReadResult(
                info = jobBackend.list().single().copy(streamsMerged = true),
                stdout = "last lines", stderr = "", nextCursor = "52000:0",
                tail = true, stdoutSkipped = 45_000, hasMore = false,
            )
        }
        val r = ContractTool(TerminalJobTool(tailBackend))
        val p = ToolPipeline(
            registry = ToolRegistry(listOf(object : ToolProvider { override val tools = listOf(r) })),
            environment = { ToolEnvironment() },
            appContext = ApplicationProvider.getApplicationContext(),
            logger = AndroidAgentLogger,
            runId = "run1",
            cancelled = { false },
        ).also { it.catalog() }
        val content = p.execute(call("terminal_job", """{"action":"read","job_id":"job_1"}""")).content
        assertTrue(content, content.contains("next_cursor: 52000:0"))
        assertTrue(content, content.contains("stdout_skipped: 45000（之前的输出没给，cursor=0:0 从头读）"))
        assertTrue(content, content.contains("streams: merged"))
        assertFalse(content, content.contains("more: true"))
    }

    @Test
    fun terminalSchemas_tellTheTruthAboutTailReadAndKeepAlive() {
        val run = ContractTool(TerminalRunTool(runBackend)).parameters(ToolEnvironment()).getJSONObject("properties")
        val mode = run.getJSONObject("mode").getString("description")
        assertTrue(mode, mode.contains("之后的任务看不到也停不了它"))
        assertTrue(run.getJSONObject("tty").getString("description").contains("直接转后台"))
        val job = ContractTool(TerminalJobTool(jobBackend))
        assertTrue(job.description, job.description.contains("不带 cursor 读最新的尾部"))
    }

    private fun jobPipeline(backend: TerminalJobBackend, cancelled: () -> Boolean = { false }) = ToolPipeline(
        registry = ToolRegistry(listOf(object : ToolProvider { override val tools = listOf(ContractTool(TerminalJobTool(backend))) })),
        environment = { ToolEnvironment() },
        appContext = ApplicationProvider.getApplicationContext(),
        logger = AndroidAgentLogger,
        runId = "run1",
        cancelled = cancelled,
    ).also { it.catalog() }

    @Test
    fun terminalJob_read_saysWhyItReturnedAndHowLongItWaited() {
        // 真机 t3c-bg：wait_ms 要 8–20 秒，1–700ms 就回来了，模型以为没等、反复读。现在结果里说清为什么返回、等了多久。
        var wake = TerminalWake.NEW_OUTPUT
        var waited = 312L
        var passedWait = -1L
        val backend = object : TerminalJobBackend by jobBackend {
            override fun read(
                jobId: String,
                cursor: String?,
                stream: TerminalStream,
                waitMs: Long,
                cancelled: () -> Boolean,
            ): TerminalJobReadResult {
                passedWait = waitMs
                return TerminalJobReadResult(
                    info = jobBackend.list().single(), stdout = "line 4", stderr = "", nextCursor = "60:0",
                    wake = wake, waitedMs = waited,
                )
            }
        }
        val p = jobPipeline(backend)
        val read = """{"action":"read","job_id":"job_1","cursor":"45:0","wait_ms":8000}"""
        val early = p.execute(call("terminal_job", read)).content
        assertEquals(8000L, passedWait)
        assertTrue(early, early.contains("waited_ms: 312（有新输出，提前返回）"))

        wake = TerminalWake.TIMEOUT
        waited = 8000
        val full = p.execute(call("terminal_job", read)).content
        assertTrue(full, full.contains("waited_ms: 8000（等满 wait_ms，期间没有新输出）"))

        wake = TerminalWake.EXITED
        waited = 1500
        assertTrue(p.execute(call("terminal_job", read)).content.contains("waited_ms: 1500（命令已结束）"))

        wake = TerminalWake.NONE
        waited = 0
        assertFalse("没等就不写", p.execute(call("terminal_job", read)).content.contains("waited_ms"))
    }

    @Test
    fun terminalJob_read_waitEndsWithTheRunAndIsNotReportedAsOutput() {
        // wait_ms 现在会等满（最长 180 秒）：等待中用户点了停止，后端要看得到并提前返回，管线按取消处理。
        var stopped = false
        var backendSawStop = false
        val backend = object : TerminalJobBackend by jobBackend {
            override fun read(
                jobId: String,
                cursor: String?,
                stream: TerminalStream,
                waitMs: Long,
                cancelled: () -> Boolean,
            ): TerminalJobReadResult {
                stopped = true
                backendSawStop = cancelled()
                return TerminalJobReadResult(jobBackend.list().single(), "", "", nextCursor = "0:0", wake = TerminalWake.CANCELLED)
            }
        }
        val p = jobPipeline(backend, cancelled = { stopped })
        org.junit.Assert.assertThrows(io.github.fartown.movo.agent.runtime.AgentRunCancelledException::class.java) {
            p.execute(call("terminal_job", """{"action":"read","job_id":"job_1","wait_ms":20000}"""))
        }
        assertTrue(backendSawStop)
    }

    @Test
    fun terminalRun_reportsTheDirectoryItRanIn() {
        val inWorkspace = object : TerminalRunBackend {
            override fun run(spec: TerminalRunSpec) = TerminalRunResult.Completed(
                exitCode = 0, stdout = "/ws", stderr = "", elapsedMs = 5,
                stdoutTruncated = false, stderrTruncated = false, cwd = "/ws",
            )
        }
        val p = ToolPipeline(
            registry = ToolRegistry(listOf(object : ToolProvider { override val tools = listOf(ContractTool(TerminalRunTool(inWorkspace))) })),
            environment = { ToolEnvironment() },
            appContext = ApplicationProvider.getApplicationContext(),
            logger = AndroidAgentLogger,
            runId = "run1",
            cancelled = { false },
        ).also { it.catalog() }
        val content = p.execute(call("terminal_run", """{"command":"pwd && ls"}""")).content
        assertTrue("没传 cwd 也要告诉模型命令在哪个目录跑", content.contains("cwd: /ws"))
    }

    @Test
    fun terminalSchemas_explainDefaultDirectoryAndWaitTiming() {
        val run = ContractTool(TerminalRunTool(runBackend)).parameters(ToolEnvironment()).getJSONObject("properties")
        assertTrue(run.getJSONObject("cwd").getString("description").contains("默认工作区"))
        val wait = ContractTool(TerminalJobTool(jobBackend)).parameters(ToolEnvironment())
            .getJSONObject("properties").getJSONObject("wait_ms").getString("description")
        assertTrue(wait, wait.contains("有新输出或命令结束就立即返回") && wait.contains("等满"))
    }

    @Test
    fun terminalJob_read_missing_notFound() {
        val r = pipeline().execute(call("terminal_job", """{"action":"read","job_id":"nope"}"""))
        assertEquals("error", r.status)
        assertEquals("NOT_FOUND", r.errorCode)
    }

    @Test
    fun terminalJob_missingJobId_invalidArguments() {
        val r = pipeline().execute(call("terminal_job", """{"action":"stop"}"""))
        assertEquals("error", r.status)
        assertEquals("INVALID_ARGUMENTS", r.errorCode)
    }

    @Test
    fun terminalJob_stop_confirmed_doneVerified() {
        stopOutcome = TerminalStopOutcome.STOPPED
        val r = pipeline().execute(call("terminal_job", """{"action":"stop","job_id":"job_1"}"""))
        val json = JSONObject(r.content)
        assertEquals("ok", json.getString("status"))
        assertTrue(json.getBoolean("effect_verified"))
    }

    @Test
    fun terminalJob_stop_stillRunning_unknown() {
        stopOutcome = TerminalStopOutcome.STILL_RUNNING
        val r = pipeline().execute(call("terminal_job", """{"action":"stop","job_id":"job_1"}"""))
        assertEquals("unknown", r.status)
        assertEquals("OUTCOME_UNKNOWN", r.errorCode)
    }

    // ---- 执行卡视图 ----

    @Test
    fun view_terminalShowsExitCodeAndOutput() {
        val p = pipeline(env = ToolEnvironment())
        val echo = call("terminal_run", """{"command":"echo hi"}""")
        assertEquals("运行 · echo hi", p.stepTitle(echo))
        val view = p.execute(echo).outcome!!.view!!
        assertEquals("退出码 0 · 输出 1 行", view.summary)
        val output = view.blocks.single() as io.github.fartown.movo.agent.tools.core.ToolUiBlock.Output
        assertEquals("hi", output.text)
        assertEquals("输出", output.label)
        assertFalse("终端输出重启后仍能展开（真机 V9）", view.transient)
        assertEquals("已转到后台运行", p.execute(call("terminal_run", """{"command":"sleep 100"}""")).outcome!!.view!!.summary)
    }
}
