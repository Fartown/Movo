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
        override fun read(jobId: String, cursor: String?, stream: TerminalStream, waitMs: Long): TerminalJobReadResult? =
            if (jobId == "job_1") TerminalJobReadResult(sample, "partial out", "", nextCursor = "11") else null
        override fun write(jobId: String, input: String, waitMs: Long) = jobId == "job_1"
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
}
