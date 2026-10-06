package io.github.fartown.movo.agent.monitor

import android.app.Application
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 监听注册表：真实 `sh` 子进程、假宿主。覆盖分帧交付、自然退出、到期、停止不阻塞、整棵进程树 / 整个进程组被结束、
 * 命令自己结束后不再按旧进程号结束、列表与本地记录在并发增删下一致、心跳与「已中断」记录、stdin 关闭、stderr 日志清理。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class MonitorRegistryTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var host: FakeHost
    private val notices = LinkedBlockingQueue<MonitorNotice>()
    private val cores = mutableListOf<MonitorRegistryCore>()

    @Before
    fun setUp() {
        host = FakeHost(temp.root)
    }

    @After
    fun tearDown() {
        cores.forEach { core ->
            core.stopWhere(MonitorEndReason.SESSION_END) { true }
            core.awaitKills(5_000)
            core.shutdown()
        }
    }

    private fun core(
        clock: () -> Long = System::currentTimeMillis,
        runShell: (Boolean, String) -> Unit = ::runMonitorShell,
        heartbeatMs: Long = 60_000L,
        host: FakeHost = this.host,
        endUndoMs: Long = MonitorRegistryCore.END_UNDO_MS,
    ): MonitorRegistryCore = MonitorRegistryCore(clock = clock, runShell = runShell, heartbeatMs = heartbeatMs, endUndoMs = endUndoMs).also {
        it.bind(host)
        it.sink = MonitorNoticeSink(notices::put)
        cores += it
    }

    private fun MonitorRegistryCore.startOk(
        command: String,
        name: String = "监听",
        conversationId: String = "c1",
        timeoutMs: Long? = null,
    ): MonitorRegistryCore.StartResult.Started =
        start(conversationId, name, command, root = false, requestedTimeoutMs = timeoutMs) as MonitorRegistryCore.StartResult.Started

    private inline fun <reified T : MonitorNotice> await(timeoutMs: Long = 8_000, predicate: (T) -> Boolean = { true }): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            check(left > 0) { "没有等到 ${T::class.simpleName}" }
            val next = notices.poll(left, TimeUnit.MILLISECONDS) ?: continue
            if (next is T && predicate(next)) return next
        }
    }

    /** 等某个进程号消失（被结束的进程可能短暂成为僵尸，由 init 回收）。 */
    private fun awaitGone(pid: Int, timeoutMs: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val alive = ProcessBuilder("kill", "-0", pid.toString()).redirectErrorStream(true).start().waitFor() == 0
            if (!alive) return true
            Thread.sleep(50)
        }
        return false
    }

    @Test
    fun linesAreBatchedIntoEventsAndAnExitEndsWithItsCodeWithoutKillingByPid() {
        val scripts = CopyOnWriteArrayList<String>()
        val core = core(runShell = { root, script -> scripts += script; runMonitorShell(root, script) })
        val started = core.startOk("printf 'a\\n'; sleep 0.6; printf 'b\\nc\\n'; sleep 0.6; printf 'last\\nno-newline'; exit 3")
        val id = started.info.id

        assertEquals("a", await<MonitorNotice.Event> { it.taskId == id }.text)
        // 200ms 内的两行合成一个事件。
        val second = await<MonitorNotice.Event> { it.taskId == id }
        assertEquals("b\nc", second.text)
        assertEquals(2, second.seq)
        val ended = await<MonitorNotice.Ended> { it.taskId == id }
        assertEquals(MonitorEndReason.EXIT, ended.reason)
        assertEquals(3, ended.exitCode)
        assertEquals(2, ended.eventCount)
        // 退出时还没交付的一批与没换行的尾段并进结束通知（结束行里可展开）。
        assertEquals("last\nno-newline", ended.tail)
        core.awaitKills(5_000)

        // 命令已经自己结束：进程号可能已被复用，不再按它结束任何进程。
        assertTrue(scripts.isEmpty())
        assertTrue(core.active.value.isEmpty())
        assertNull(host.record)
        assertTrue(host.leases.isEmpty())
        assertEquals(listOf(id), host.ended)
    }

    @Test
    fun stopReturnsAtOnceAndTheWholeProcessTreeIsKilled() {
        val core = core()
        val started = core.startOk("sh -c 'sleep 300 & echo child=\$!; wait' & echo ready; wait")
        val id = started.info.id
        var grandchild: Int? = null
        while (grandchild == null) {
            val event = await<MonitorNotice.Event> { it.taskId == id }
            grandchild = Regex("child=(\\d+)").find(event.text)?.groupValues?.get(1)?.toInt()
        }

        val began = System.nanoTime()
        assertTrue(core.stop(id, MonitorEndReason.STOPPED_BY_USER))
        // 结束进程在单独的线程池里：调用方（主线程、计时线程）不等。
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 300)
        assertEquals(MonitorEndReason.STOPPED_BY_USER, await<MonitorNotice.Ended> { it.taskId == id }.reason)
        core.awaitKills(5_000)

        assertTrue("孙进程 $grandchild 应被结束", awaitGone(grandchild!!))
        assertFalse(File(host.workDir, "monitor/$id.pid").exists())
        assertFalse(File(host.workDir, "monitor/$id.stderr.log").exists())
    }

    @Test
    fun withSetsidTheWholeProcessGroupIsKilledIncludingOrphans() {
        assumeTrue(File("/usr/bin/perl").canExecute())
        val shim = File(temp.root, "setsid").apply {
            writeText(
                "#!/usr/bin/perl\n" +
                    "use POSIX qw(setsid);\n" +
                    "shift @ARGV if @ARGV && \$ARGV[0] eq '-w';\n" +
                    "setsid() or die \"setsid: \$!\";\n" +
                    "exec { \$ARGV[0] } @ARGV or die \"exec: \$!\";\n",
            )
            setExecutable(true)
        }
        val groupHost = FakeHost(temp.newFolder("group"), setsid = shim.absolutePath)
        val core = core(host = groupHost)
        // 子 shell 退出后 sleep 成了孤儿（不在进程树里），但仍在监听的进程组里。
        val started = core.startOk("(sleep 300 & echo orphan=\$!); echo ready; exec sleep 300")
        val id = started.info.id
        var orphan: Int? = null
        while (orphan == null) {
            val event = await<MonitorNotice.Event> { it.taskId == id }
            orphan = Regex("orphan=(\\d+)").find(event.text)?.groupValues?.get(1)?.toInt()
        }

        core.stop(id, MonitorEndReason.STOPPED_BY_USER)
        await<MonitorNotice.Ended> { it.taskId == id }
        core.awaitKills(5_000)

        assertTrue("孤儿进程 $orphan 应随进程组被结束", awaitGone(orphan!!))
    }

    @Test
    fun slowKillingNeverBlocksStopOrTheDeadlineTimer() {
        val release = CountDownLatch(1)
        val core = core(runShell = { _, _ -> release.await(3, TimeUnit.SECONDS) })
        val first = core.startOk("exec sleep 30", name = "第一个")
        val second = core.startOk("exec sleep 30", name = "第二个", timeoutMs = 1_200)

        val began = System.nanoTime()
        core.stop(first.info.id, MonitorEndReason.STOPPED_BY_USER)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 300)
        // 第一个还卡在结束进程，第二个照样按时到期（计时线程没被占住）。
        val timedOut = await<MonitorNotice.Ended>(timeoutMs = 4_000) { it.taskId == second.info.id }
        assertEquals(MonitorEndReason.TIMEOUT, timedOut.reason)
        release.countDown()
        core.awaitKills(5_000)
    }

    @Test
    fun requestedDurationAboveTheLimitIsCappedInsteadOfRejected() {
        val capped = FakeHost(temp.newFolder("capped"), maxMs = 30 * 60_000L)
        val core = core(host = capped)
        val started = core.startOk("exec sleep 30", timeoutMs = 10 * 60 * 60_000L)
        assertEquals(30 * 60_000L, started.info.timeoutMs)
        assertEquals(10 * 60 * 60_000L, started.requestedTimeoutMs)
        assertEquals(30 * 60_000L, started.maxTimeoutMs)
        assertTrue(MonitorStartTool.startedMessage(MonitorStartOutput(started.info, started.requestedTimeoutMs, started.maxTimeoutMs))
            .contains("已按上限 30 分钟 生效"))
    }

    @Test
    fun duplicateNamesAreNormalizedAndCheckedPerConversation() {
        val core = core()
        core.startOk("exec sleep 30", name = "喝水 提醒")
        val duplicate = core.start("c1", "喝水提醒", "exec sleep 30", root = false, requestedTimeoutMs = null)
        assertEquals("DUPLICATE_NAME", (duplicate as MonitorRegistryCore.StartResult.Rejected).code)
        val fullWidth = core.start("c1", "  喝水　提醒 ", "exec sleep 30", root = false, requestedTimeoutMs = null)
        assertEquals("DUPLICATE_NAME", (fullWidth as MonitorRegistryCore.StartResult.Rejected).code)
        // 别的对话可以同名；名字里的换行收成空格。
        val other = core.startOk("exec sleep 30", name = "喝水\n提醒", conversationId = "c2")
        assertEquals("喝水 提醒", other.info.name)
    }

    @Test
    fun concurrentStartsAndStopsLeaveListAndRecordConsistent() {
        val core = core()
        val ids = ConcurrentHashMap<Int, String>()
        val workers = (0 until 16).map { index ->
            thread {
                ids[index] = core.startOk("exec sleep 30", name = "n$index", conversationId = "c${index % 4}").info.id
            }
        }
        workers.forEach { it.join(10_000) }
        val stopped = (0 until 16 step 2).map { ids.getValue(it) }.toSet()
        stopped.map { id -> thread { core.stop(id, MonitorEndReason.STOPPED_BY_USER) } }.forEach { it.join(5_000) }
        val remaining = ids.values.toSet() - stopped

        assertEquals(remaining, core.active.value.map { it.id }.toSet())
        val record = JSONArray(checkNotNull(host.record))
        assertEquals(remaining, (0 until record.length()).map { record.getJSONObject(it).getString("id") }.toSet())
        assertEquals(remaining.map { "monitor:$it" }.toSet(), host.leases.keys)
    }

    @Test
    fun heartbeatsLetTheNextProcessDateTheInterruption() {
        val now = AtomicLong(1_000L)
        val core = core(clock = now::get, heartbeatMs = 100)
        val started = core.startOk("exec sleep 30", name = "电量播报")
        now.set(61_000L)
        Thread.sleep(400)

        // 进程被杀后的下一个进程：读出留下的记录、补「已中断」，时刻是最后一次心跳，不是恢复的这一刻。
        val nextHost = FakeHost(temp.newFolder("next")).apply { record = host.record }
        val next = core(host = nextHost, clock = { 999_000L })
        val interrupted = next.takeInterrupted().single()
        assertEquals(started.info.id, interrupted.taskId)
        assertEquals("c1", interrupted.conversationId)
        assertEquals("电量播报", interrupted.name)
        assertEquals(1_000L, interrupted.startedAtMillis)
        assertEquals(61_000L, interrupted.interruptedAtMillis)
        assertNull(nextHost.record)
    }

    @Test
    fun stdinIsClosedSoCommandsReadingItDoNotHang() {
        val core = core()
        val started = core.startOk("cat; echo after-cat; exec sleep 30")
        assertEquals("after-cat", await<MonitorNotice.Event>(timeoutMs = 4_000) { it.taskId == started.info.id }.text)
    }

    @Test
    fun endingWithTheTaskStopsAfterTheUndoWindowAndDropsHeldEvents() {
        val core = core(endUndoMs = 800)
        val started = core.startOk("while true; do echo tick; sleep 0.15; done", name = "喝水提醒")
        val other = core.startOk("exec sleep 30", name = "别的对话", conversationId = "c2")
        await<MonitorNotice.Event> { it.taskId == started.info.id }

        val ending = checkNotNull(core.endLater { it.conversationId == "c1" })
        notices.clear()
        assertEquals(listOf("喝水提醒"), ending.names)
        // 结束中立刻不算运行中（悬浮球、「监听」入口、常驻通知都按已结束显示），别的对话不受影响。
        assertEquals(listOf(other.info.id), core.active.value.map { it.id })
        assertEquals(listOf(ending.id), core.endings.value.map { it.id })
        // 同一个监听不会进第二次结束。
        assertNull(core.endLater { it.conversationId == "c1" })

        val ended = await<MonitorNotice.Ended>(timeoutMs = 4_000) { it.taskId == started.info.id }
        assertEquals(MonitorEndReason.ENDED_WITH_TASK, ended.reason)
        assertFalse(ended.reason.wakesAgent)
        // 扣着的事件随期满丢弃，没有交出去。
        assertTrue(notices.none { it is MonitorNotice.Event && it.taskId == started.info.id })
        assertTrue(core.endings.value.isEmpty())
        assertFalse(core.undoEnding(ending.id))
        core.awaitKills(5_000)
        assertEquals(setOf("monitor:${other.info.id}"), host.leases.keys)
    }

    @Test
    fun undoKeepsTheMonitorRunningAndReleasesHeldEventsInOrder() {
        val core = core(endUndoMs = 5_000)
        val started = core.startOk("sleep 0.3; echo a; sleep 0.4; echo b; exec sleep 30", name = "喝水提醒")
        val ending = checkNotNull(core.endLater { true })
        Thread.sleep(1_200)
        assertTrue(notices.none { it is MonitorNotice.Event })

        assertTrue(core.undoEnding(ending.id))
        assertEquals(listOf(started.info.id), core.active.value.map { it.id })
        assertTrue(core.endings.value.isEmpty())
        val first = await<MonitorNotice.Event> { it.taskId == started.info.id }
        val second = await<MonitorNotice.Event> { it.taskId == started.info.id }
        assertEquals("a" to 1, first.text to first.seq)
        assertEquals("b" to 2, second.text to second.seq)
        // 撤销过的结束不会再到期。
        Thread.sleep(300)
        assertTrue(notices.none { it is MonitorNotice.Ended })
        assertFalse(core.undoEnding(ending.id))
    }

    @Test
    fun aDeadlineDuringTheUndoWindowEndsWithTheTaskInsteadOfWakingMovo() {
        val core = core(endUndoMs = 5_000)
        val started = core.startOk("exec sleep 30", timeoutMs = 1_200)
        val ending = checkNotNull(core.endLater { true })
        val ended = await<MonitorNotice.Ended>(timeoutMs = 4_000) { it.taskId == started.info.id }
        assertEquals(MonitorEndReason.ENDED_WITH_TASK, ended.reason)
        // 已经停了：撤销只是收掉这次结束，不会把它变回运行中。
        assertTrue(core.undoEnding(ending.id))
        assertTrue(core.active.value.isEmpty())
    }

    private class FakeHost(
        root: File,
        setsid: String? = null,
        private val maxMs: Long = 2 * 60 * 60_000L,
    ) : MonitorHost {
        override val workDir: File = File(root, "cache").apply { mkdirs() }
        override val homeDir: File = File(root, "files").apply { mkdirs() }
        override val setsidCommand: String? = setsid
        val leases = ConcurrentHashMap<String, () -> Unit>()
        val ended = CopyOnWriteArrayList<String>()
        @Volatile var record: String? = null

        override fun maxDurationMs(): Long = maxMs
        override fun acquireLease(leaseId: String, onStop: () -> Unit): Boolean {
            leases[leaseId] = onStop
            return true
        }
        override fun releaseLease(leaseId: String) {
            leases.remove(leaseId)
        }
        override fun leasesChanged() = Unit
        override fun post(block: () -> Unit) = block()
        override fun readRecord(): String? = record
        override fun writeRecord(value: String?) {
            record = value
        }
        override fun onTaskEnded(taskId: String) {
            ended += taskId
        }
    }
}
