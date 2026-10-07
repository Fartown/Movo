package io.github.fartown.movo.agent.tools.terminal

import io.github.fartown.movo.agent.terminal.ShellProcessSupervisor
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.agent.tools.core.ToolFailure
import io.github.fartown.movo.core.AndroidAgentLogger
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * terminal_job read 用真进程验证：不带 cursor 读尾部、带 cursor 续读（stdout / stderr 各有偏移）、
 * 长输出时缓冲区保留开头和最近的部分；tty=true 真的用伪终端启动并直接转后台。
 * 进程慢的时候会走到打警告日志的分支（满负荷全量跑时出现过），所以跑在 Robolectric 上（要有 Android 的 Log）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TerminalJobRegistryReadTest {
    private val registries = mutableListOf<TerminalJobRegistry>()

    @After
    fun close() = registries.forEach { it.close() }

    private fun registry(supervisor: ShellProcessSupervisor = ShellProcessSupervisor()) =
        TerminalJobRegistry(AndroidAgentLogger, supervisor).also { registries += it }

    private fun spec(
        command: String,
        tty: Boolean = false,
        mode: TerminalMode = TerminalMode.BACKGROUND,
        cwd: String? = null,
    ) = TerminalRunSpec(
        command = command,
        description = null,
        environment = TerminalEnv.ANDROID,
        identity = TerminalIdentity.USER,
        cwd = cwd,
        waitMs = 30_000,
        tty = tty,
        mode = mode,
    )

    /** 后台起一条命令，等到 stdout 里出现 [marker]，返回 job_id 和此时 stdout 读到哪儿的游标。 */
    private fun startedWith(registry: TerminalJobRegistry, command: String, marker: String): Pair<String, String> {
        val job = (registry.run(spec(command)) as TerminalRunResult.Backgrounded).jobId
        val deadline = System.currentTimeMillis() + 20_000
        while (true) {
            val read = registry.read(job, cursor = "0:0", stream = TerminalStream.BOTH, waitMs = 0)!!
            if (marker in read.stdout) return job to read.nextCursor!!
            assertTrue("20 秒内应输出 $marker", System.currentTimeMillis() < deadline)
            Thread.sleep(50)
        }
    }

    private fun canonical(path: String): String = File(path.trim()).canonicalPath

    /** 后台跑完一条命令，返回 job_id。 */
    private fun finished(registry: TerminalJobRegistry, command: String): String {
        val job = (registry.run(spec(command)) as TerminalRunResult.Backgrounded).jobId
        val deadline = System.currentTimeMillis() + 20_000
        while (registry.list().single { it.jobId == job }.running) {
            assertTrue("命令应在 20 秒内结束", System.currentTimeMillis() < deadline)
            Thread.sleep(50)
        }
        return job
    }

    @Test
    fun readWithoutCursor_returnsTheTail_andCursorReadsFromTheStart() {
        val registry = registry()
        // 约 4 万字 stdout，加一行 stderr。
        val job = finished(registry, "i=1; while [ \$i -le 5000 ]; do echo line\$i; i=\$((i+1)); done; echo oops >&2")

        val tail = registry.read(job, cursor = null, stream = TerminalStream.BOTH, waitMs = 0)!!
        assertTrue(tail.tail)
        assertTrue(tail.stdout.trimEnd().endsWith("line5000"))
        assertFalse(tail.stdout.contains("line1\n"))
        assertTrue("尾部之前没给的字数要报出来", tail.stdoutSkipped > 0)
        assertEquals("oops\n", tail.stderr)
        assertFalse(tail.hasMore)
        val (outEnd, errEnd) = tail.nextCursor!!.split(':').map { it.toLong() }
        assertEquals(tail.stdoutSkipped + tail.stdout.length, outEnd)
        assertEquals(5L, errEnd)

        // 0:0 从头读：每路各自从 0 开始，一次给不完就 more。
        val head = registry.read(job, cursor = "0:0", stream = TerminalStream.BOTH, waitMs = 0)!!
        assertFalse(head.tail)
        assertTrue(head.stdout.startsWith("line1\nline2\n"))
        assertEquals("oops\n", head.stderr)
        assertTrue(head.hasMore)
        assertEquals(READ_CHARS / 2, head.stdout.length)

        // 续读接着上一次的位置，stderr 已读完不重复给。
        val next = registry.read(job, cursor = head.nextCursor, stream = TerminalStream.BOTH, waitMs = 0)!!
        assertTrue(next.stdout.isNotEmpty())
        assertEquals("", next.stderr)
        val joined = head.stdout + next.stdout
        assertTrue(joined.contains("line999\nline1000\n"))
    }

    @Test
    fun longOutput_keepsTheNewestPart_andReportsWhatWasDropped() {
        val registry = registry()
        // 约 49 万字，超过缓冲区（开头 64K + 最近 192K）。
        val job = finished(registry, "i=1; while [ \$i -le 60000 ]; do echo row\$i; i=\$((i+1)); done")

        val tail = registry.read(job, cursor = null, stream = TerminalStream.STDOUT, waitMs = 0)!!
        assertTrue("尾部是最新的输出", tail.stdout.trimEnd().endsWith("row60000"))

        // 从头一段段续读：读完开头那段后，越过中间丢弃的部分时报出跳过的字数，接着给的是最近的输出。
        var cursor = "0:0"
        var reads = 0
        var read = registry.read(job, cursor = cursor, stream = TerminalStream.STDOUT, waitMs = 0)!!
        while (read.stdoutSkipped == 0L && reads++ < 20) {
            cursor = read.nextCursor!!
            read = registry.read(job, cursor = cursor, stream = TerminalStream.STDOUT, waitMs = 0)!!
        }
        assertTrue("续读越过已丢弃的部分时要报跳过的字数", read.stdoutSkipped > 0)
        assertFalse(read.tail)
        assertTrue(read.stdout.contains("row"))
    }

    // ---- 默认目录：没传 cwd 时在工作区（真机：在 `/`，ls 报 Permission denied）----

    @Test
    fun withoutCwd_commandsRunInTheWorkspace() {
        val registry = registry()
        val workspace = io.github.fartown.movo.agent.terminal.TerminalRuntime.userWorkspacePath
        val result = registry.run(spec("pwd && ls", mode = TerminalMode.WAIT)) as TerminalRunResult.Completed
        assertEquals(result.stderr, 0, result.exitCode)
        assertEquals(canonical(workspace), canonical(result.stdout.lineSequence().first()))
        assertEquals(workspace, result.cwd)
    }

    @Test
    fun relativeCwdAndTilde_areInTheWorkspace() {
        val registry = registry()
        val workspace = io.github.fartown.movo.agent.terminal.TerminalRuntime.userWorkspacePath
        File(workspace, "notes-dir").mkdirs()
        val relative = registry.run(spec("pwd", mode = TerminalMode.WAIT, cwd = "notes-dir")) as TerminalRunResult.Completed
        assertEquals(relative.stderr, 0, relative.exitCode)
        assertEquals(canonical("$workspace/notes-dir"), canonical(relative.stdout))
        val home = registry.run(spec("pwd", mode = TerminalMode.WAIT, cwd = "~")) as TerminalRunResult.Completed
        assertEquals(canonical(workspace), canonical(home.stdout))
        // 后台命令也一样：结果里带上实际目录。
        assertEquals(workspace, (registry.run(spec("sleep 5")) as TerminalRunResult.Backgrounded).cwd)
    }

    // ---- terminal_job 的 wait_ms：read 攒一段再回（约 4000 字），命令结束也返回，否则等满；write 一有回应就回（真机 t3c-bg）----

    @Test
    fun read_collectsLineByLineOutputUntilTheWaitEnds() {
        // 真机：每秒一行的命令，原来有新输出就返回，被追着读了 12 次。
        val registry = registry()
        val (job, cursor) = startedWith(registry, "echo start; for i in 1 2 3 4 5 6 7 8 9 10; do echo line\$i; sleep 0.2; done; sleep 30", "start")
        val started = System.currentTimeMillis()
        val read = registry.read(job, cursor = cursor, stream = TerminalStream.BOTH, waitMs = 1_500)!!
        val elapsed = System.currentTimeMillis() - started
        assertEquals(TerminalWake.TIMEOUT, read.wake)
        assertTrue("一行一行的输出攒到等满再一起给，实际 ${elapsed}ms", elapsed >= 1_400)
        assertTrue(read.stdout, read.stdout.contains("line3") && read.stdout.contains("line6"))
        assertFalse("游标之前的不再给", read.stdout.contains("start"))
    }

    @Test
    fun read_withPlentyOfUnreadOutput_returnsRightAway() {
        val registry = registry()
        val (job, _) = startedWith(registry, "head -c 6000 /dev/zero | tr '\\0' x; echo; echo done; sleep 30", "done")
        val started = System.currentTimeMillis()
        val read = registry.read(job, cursor = "0:0", stream = TerminalStream.BOTH, waitMs = 15_000)!!
        val elapsed = System.currentTimeMillis() - started
        assertEquals(TerminalWake.ENOUGH_OUTPUT, read.wake)
        assertTrue(read.stdout.contains("xxxx"))
        assertTrue("已经攒够一段就不等，实际 ${elapsed}ms", elapsed < 3_000)
    }

    @Test
    fun read_withoutNewOutput_waitsTheFullTime() {
        val registry = registry()
        val (job, cursor) = startedWith(registry, "echo only; sleep 30", "only")
        val started = System.currentTimeMillis()
        val read = registry.read(job, cursor = cursor, stream = TerminalStream.BOTH, waitMs = 1_500)!!
        val elapsed = System.currentTimeMillis() - started
        assertEquals(TerminalWake.TIMEOUT, read.wake)
        assertEquals("", read.stdout)
        assertTrue("没有新输出要等满 1.5 秒，实际 ${elapsed}ms", elapsed >= 1_400)
        assertTrue(read.waitedMs >= 1_400)
    }

    @Test
    fun read_withoutCursor_collectsOutputAfterTheCall() {
        val registry = registry()
        val (job, _) = startedWith(registry, "echo a; sleep 1; echo b; sleep 30", "a")
        val read = registry.read(job, cursor = null, stream = TerminalStream.BOTH, waitMs = 2_500)!!
        assertEquals(TerminalWake.TIMEOUT, read.wake)
        assertTrue("不带游标时攒这次调用之后的新输出，再给尾部", read.stdout.contains("b"))
    }

    @Test
    fun read_returnsWhenTheCommandEnds() {
        val registry = registry()
        val job = (registry.run(spec("sleep 1")) as TerminalRunResult.Backgrounded).jobId
        val started = System.currentTimeMillis()
        val read = registry.read(job, cursor = "0:0", stream = TerminalStream.BOTH, waitMs = 20_000)!!
        val elapsed = System.currentTimeMillis() - started
        assertEquals(TerminalWake.EXITED, read.wake)
        assertFalse(read.info.running)
        assertEquals(0, read.info.exitCode)
        assertTrue("命令结束就返回，不等满 20 秒，实际 ${elapsed}ms", elapsed < 10_000)
    }

    @Test
    fun read_cancelledRun_stopsWaiting() {
        val registry = registry()
        val job = (registry.run(spec("sleep 30")) as TerminalRunResult.Backgrounded).jobId
        val started = System.currentTimeMillis()
        val read = registry.read(
            job, cursor = "0:0", stream = TerminalStream.BOTH, waitMs = 20_000,
            cancelled = { System.currentTimeMillis() - started > 300 },
        )!!
        val elapsed = System.currentTimeMillis() - started
        assertEquals(TerminalWake.CANCELLED, read.wake)
        assertTrue("取消后应在几秒内返回，实际 ${elapsed}ms", elapsed < 5_000)
    }

    @Test
    fun badCursor_isRejected() {
        val registry = registry()
        val job = finished(registry, "echo hi")
        try {
            registry.read(job, cursor = "abc", stream = TerminalStream.BOTH, waitMs = 0)
            fail("非法游标应报错")
        } catch (failure: ToolFailure) {
            assertEquals(ToolErrorCode.INVALID_ARGUMENTS, failure.code)
        }
        // 旧的单个数字游标两路同用。
        assertEquals("hi\n", registry.read(job, cursor = "0", stream = TerminalStream.BOTH, waitMs = 0)!!.stdout)
    }

    @Test
    fun tty_startsInAPseudoTerminal_andReturnsAJobRightAway() {
        // 主机上没有 libmovo_pty：用一个假的 PTY 启动器（参数与真的一致：rows cols -- 命令…），先打个标记再执行命令。
        val helper = File.createTempFile("fake-pty-", ".sh").apply {
            writeText("#!/bin/sh\necho FAKE_PTY\nshift 3\nexec \"\$@\"\n")
            setExecutable(true)
            deleteOnExit()
        }
        // 机器忙时 shell 起得慢，默认 0.5 秒等不到子进程报到（满负荷全量跑时出现过）：测试里放宽，产品行为不变。
        val registry = registry(ShellProcessSupervisor(userPtyExecutable = { helper }, ownershipWaitMs = 5_000L))
        val started = System.currentTimeMillis()
        val result = registry.run(spec("read line; echo got:\$line", tty = true, mode = TerminalMode.WAIT))
        assertTrue("tty 不等 wait_ms，直接转后台", result is TerminalRunResult.Backgrounded)
        assertEquals("tty", (result as TerminalRunResult.Backgrounded).reason)
        assertTrue(System.currentTimeMillis() - started < 10_000)

        assertTrue(registry.write(result.jobId, "hello", waitMs = 0))
        var output = ""
        val deadline = System.currentTimeMillis() + 10_000
        while ("got:hello" !in output && System.currentTimeMillis() < deadline) {
            output = registry.read(result.jobId, cursor = "0:0", stream = TerminalStream.STDOUT, waitMs = 200)!!.stdout
        }
        assertTrue(output, output.contains("FAKE_PTY"))
        assertTrue(output, output.contains("got:hello"))
        assertTrue(registry.list().single { it.jobId == result.jobId }.streamsMerged)
    }
}
