package io.github.fartown.movo.agent.tools.terminal

import io.github.fartown.movo.agent.terminal.DetachedTaskSupervisor
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.agent.tools.core.ToolFailure
import io.github.fartown.movo.core.AndroidAgentLogger
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 终端与重构前对齐（重构差异 T3 第 29–31 条），用真进程验证：
 * - keep_alive 交给 DetachedTaskSupervisor：本次任务结束后照常运行，之后的任务（新的登记表）列得出、读得到日志、停得掉；
 * - session：同名命令在同一个 shell 里接着跑，cd、export 保留；超时结束会话；
 * - Android 非 tty 命令设 TERM=dumb、NO_COLOR=1。
 * 进程慢的时候会走到打警告日志的分支，所以跑在 Robolectric 上（要有 Android 的 Log）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TerminalParityTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val registries = mutableListOf<TerminalJobRegistry>()
    private val supervisors = mutableListOf<DetachedTaskSupervisor>()

    @After
    fun tearDown() {
        registries.forEach { it.close() }
        // 兜底清理测试留下的常驻进程（正常路径 stop 已经停掉）。
        supervisors.flatMap { runCatching { it.list() }.getOrDefault(emptyList()) }.forEach { status ->
            ProcessHandle.of(status.task.pid).ifPresent { it.destroyForcibly() }
        }
    }

    /** 与 App 里一样：每次任务一个新的登记表，常驻任务的记录文件是同一个。 */
    private fun registry(): TerminalJobRegistry {
        val daemons = DetachedTaskSupervisor(
            logger = AndroidAgentLogger,
            recordsFile = File(temporaryFolder.root, "terminal-daemons.json"),
            daemonDir = File(temporaryFolder.root, "daemon").absolutePath,
            acquireUserLease = { _, _ -> true },
            releaseUserLease = {},
        )
        supervisors += daemons
        return TerminalJobRegistry(AndroidAgentLogger, daemons = daemons).also { registries += it }
    }

    private fun spec(
        command: String,
        mode: TerminalMode = TerminalMode.WAIT,
        session: String? = null,
        cwd: String? = null,
        waitMs: Long = 20_000,
    ) = TerminalRunSpec(
        command = command,
        description = null,
        environment = TerminalEnv.ANDROID,
        identity = TerminalIdentity.USER,
        cwd = cwd,
        waitMs = waitMs,
        tty = false,
        mode = mode,
        session = session,
    )

    private fun completed(registry: TerminalJobRegistry, spec: TerminalRunSpec) =
        registry.run(spec) as TerminalRunResult.Completed

    // ---- keep_alive ----

    @Test
    fun keepAlive_survivesTheTask_andALaterTaskCanReadAndStopIt() {
        val first = registry()
        val started = first.run(spec("echo started-\$\$; sleep 30", mode = TerminalMode.KEEP_ALIVE)) as TerminalRunResult.Backgrounded
        assertTrue(started.keepAlive)
        assertTrue(started.jobId, started.jobId.startsWith("dm_"))
        assertTrue("日志落盘", File(started.logPath!!).exists())
        // 本次任务结束：普通后台命令会停，常驻任务不停。
        first.close()

        val later = registry()
        val listed = later.list().single { it.jobId == started.jobId }
        assertTrue(listed.running)
        assertTrue(listed.keepAlive)
        assertEquals(started.logPath, listed.logPath)

        // 日志在任务启动后才写进去：最多等 10 秒。
        val deadline = System.currentTimeMillis() + 10_000
        var read = later.read(started.jobId, cursor = "0:0", stream = TerminalStream.BOTH, waitMs = 0)!!
        while (!read.stdout.contains("started-") && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
            read = later.read(started.jobId, cursor = "0:0", stream = TerminalStream.BOTH, waitMs = 0)!!
        }
        assertTrue(read.stdout, read.stdout.contains("started-"))
        assertTrue(read.info.streamsMerged)
        val cursor = JobCursor.parse(read.nextCursor!!)
        assertEquals(File(started.logPath!!).length(), cursor.out)

        assertEquals(TerminalStopOutcome.STOPPED, later.stop(started.jobId))
        assertFalse(later.list().any { it.jobId == started.jobId })
        assertNull(later.read(started.jobId, cursor = null, stream = TerminalStream.BOTH, waitMs = 0))
    }

    @Test
    fun keepAlive_logIsReadByCursor_andWaitReturnsWhenTheTaskEnds() {
        val registry = registry()
        val started = registry.run(
            spec("i=1; while [ \$i -le 3 ]; do echo line\$i; i=\$((i+1)); sleep 0.3; done", mode = TerminalMode.KEEP_ALIVE),
        ) as TerminalRunResult.Backgrounded

        // 带 cursor 从头读、等到任务结束：拿到全部三行。
        val read = registry.read(started.jobId, cursor = "0:0", stream = TerminalStream.BOTH, waitMs = 15_000)!!
        val all = buildString {
            append(read.stdout)
            var next = read.nextCursor
            repeat(5) {
                val more = registry.read(started.jobId, cursor = next, stream = TerminalStream.BOTH, waitMs = 3_000)!!
                append(more.stdout)
                next = more.nextCursor
            }
        }
        assertTrue(all, all.contains("line1") && all.contains("line2") && all.contains("line3"))
        registry.stop(started.jobId)
    }

    @Test
    fun keepAlive_doesNotTakeInput() {
        val registry = registry()
        val started = registry.run(spec("sleep 30", mode = TerminalMode.KEEP_ALIVE)) as TerminalRunResult.Backgrounded
        try {
            registry.write(started.jobId, "hello", waitMs = 0)
            fail("常驻任务不接收输入")
        } catch (failure: ToolFailure) {
            assertEquals(ToolErrorCode.UNSUPPORTED, failure.code)
        } finally {
            registry.stop(started.jobId)
        }
    }

    // ---- session ----

    @Test
    fun session_keepsDirectoryAndExportsAcrossCommands() {
        val registry = registry()
        val base = temporaryFolder.newFolder("ws").absolutePath
        File(base, "sub").mkdirs()

        val first = completed(registry, spec("cd sub && export MOVO_PARITY=kept && MOVO_LOCAL=yes", session = "main", cwd = base))
        assertEquals(0, first.exitCode)
        assertEquals("main", first.session)
        assertFalse(first.sessionClosed)
        assertTrue(first.cwd!!, first.cwd!!.endsWith("/sub"))

        val second = completed(registry, spec("pwd; echo \"\$MOVO_PARITY-\$MOVO_LOCAL\"", session = "main"))
        assertTrue(second.stdout, second.stdout.lines().first().endsWith("/sub"))
        assertTrue(second.stdout, second.stdout.contains("kept-yes"))

        // 别的会话、不带会话的命令互不影响。
        val other = completed(registry, spec("echo \"[\$MOVO_PARITY]\"", session = "other"))
        assertTrue(other.stdout, other.stdout.contains("[]"))
        val plain = completed(registry, spec("echo \"[\$MOVO_PARITY]\""))
        assertTrue(plain.stdout, plain.stdout.contains("[]"))
        assertNull(plain.session)
    }

    @Test
    fun session_timeoutEndsTheSession_andTheNextCallStartsFresh() {
        val registry = registry()
        completed(registry, spec("export MOVO_PARITY=before", session = "main"))

        val slow = completed(registry, spec("echo begun; sleep 20", session = "main", waitMs = 1_500))
        assertTrue(slow.timedOut)
        assertTrue(slow.sessionClosed)
        assertNotEquals(0, slow.exitCode)
        assertTrue(slow.stdout, slow.stdout.contains("begun"))

        val after = completed(registry, spec("echo \"[\$MOVO_PARITY]\"", session = "main"))
        assertFalse(after.sessionClosed)
        assertTrue("新开的会话里没有之前的变量：${after.stdout}", after.stdout.contains("[]"))
    }

    @Test
    fun session_exitClosesIt() {
        val registry = registry()
        val exited = completed(registry, spec("exit 3", session = "main"))
        assertTrue(exited.sessionClosed)
        val again = completed(registry, spec("echo alive", session = "main"))
        assertEquals(0, again.exitCode)
        assertTrue(again.stdout.contains("alive"))
    }

    @Test
    fun session_cannotSwitchEnvironmentOrIdentity() {
        val registry = registry()
        completed(registry, spec("true", session = "main"))
        try {
            registry.run(spec("true", session = "main").copy(identity = TerminalIdentity.ROOT))
            fail("同一个会话不能换身份")
        } catch (failure: ToolFailure) {
            assertEquals(ToolErrorCode.INVALID_ARGUMENTS, failure.code)
        }
    }

    @Test
    fun session_isClosedWithTheTask() {
        val registry = registry()
        completed(registry, spec("export MOVO_PARITY=x", session = "main"))
        registry.close()
        try {
            registry.run(spec("true", session = "main"))
            fail("任务结束后会话不再可用")
        } catch (failure: ToolFailure) {
            assertEquals(ToolErrorCode.CANCELLED, failure.code)
        }
    }

    // ---- TERM / NO_COLOR ----

    @Test
    fun androidCommands_runWithDumbTerminalAndNoColor() {
        val registry = registry()
        val result = completed(registry, spec("echo \"\$TERM:\$NO_COLOR\""))
        assertEquals("dumb:1", result.stdout.trim())
    }
}
