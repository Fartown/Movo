package io.github.fartown.movo.diagnostics.runlog

import java.io.File
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RunLogStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private var elapsed = 10_000L
    private var wall = 1_790_000_000_000L
    private val stores = mutableListOf<RunLogStore>()
    private val failures = mutableListOf<Pair<String, String>>()

    @After
    fun stopWriters() {
        stores.forEach { it.shutdown() }
    }

    private fun store(limits: RunLogStore.Limits = RunLogStore.Limits(), root: File = File(temp.root, "run-log")) =
        RunLogStore(root, limits, elapsedClock = { elapsed }, wallClock = { wall }, reportFailure = { run, event, _ ->
            failures += run to event
        }).also { stores += it }

    private fun RunLogStore.lines(session: RunLogSession): List<Map<String, Any?>> {
        assertTrue(awaitIdle())
        @Suppress("UNCHECKED_CAST")
        return File(root, "${session.dirName}/${RunLogStore.LOG_FILE}").readLines()
            .map { RunLogJson.parse(it) as Map<String, Any?> }
    }

    private fun Map<String, Any?>.long(key: String): Long = (this[key] as JsonNumber).raw.toLong()

    private fun RunLogStore.end(session: RunLogSession, status: String = "completed") =
        close(session, RunLogRecord("run_end", session.wall(), session.elapsed(), mapOf("status" to status), control = true))

    @Test
    fun linesAreNumberedInWriteOrderAndRunEndIsLast() {
        val store = store()
        val session = store.open("R7")!!
        session.record("run_start", mapOf("run" to "R7"), el = 0, control = true)
        elapsed += 5
        session.record("tool_start", mapOf("id" to "c1"))
        session.record("tool_end", mapOf("id" to "c1", "result" to "{\"ok\":true}"))
        store.end(session)
        session.record("user", mapOf("kind" to "stop")) // 关闭之后的记录丢弃

        val lines = store.lines(session)
        assertEquals(listOf("run_start", "tool_start", "tool_end", "run_end"), lines.map { it["t"] })
        assertEquals(listOf(1L, 2L, 3L, 4L), lines.map { it.long("seq") })
        assertEquals(5L, lines[1].long("el"))
        assertEquals(wall, lines[1].long("at"))
        assertEquals("{\"ok\":true}", lines[2]["result"])
        assertTrue(session.dirName.matches(Regex("""R7-\d{8}-\d{6}""")))
    }

    @Test
    fun droppedLinesKeepTheirNumbersAndLeaveOneGap() {
        val store = store(RunLogStore.Limits(queueBytes = 2_000))
        val session = store.open("R8")!!
        session.record("run_start", mapOf("run" to "R8"), control = true)
        session.record("user", mapOf("kind" to "pause"))
        // 单条超过普通队列上限：排不下，丢掉但占号。
        session.record("attempt_delta", mapOf("text" to "长".repeat(2_000)))
        session.record("attempt_delta", mapOf("text" to "长".repeat(2_000)))
        session.record("user", mapOf("kind" to "resume"))
        store.end(session)

        val lines = store.lines(session)
        assertEquals(listOf("run_start", "user", "gap", "user", "run_end"), lines.map { it["t"] })
        val gap = lines[2]
        assertEquals(listOf(3L, 4L, 5L), listOf(gap.long("from_seq"), gap.long("to_seq"), gap.long("seq")))
        assertEquals("queue_full", gap["reason"])
        assertEquals(listOf(1L, 2L, 5L, 6L, 7L), lines.map { it.long("seq") })
    }

    @Test
    fun contentStopsAtTheRunCapButControlLinesAreStillWritten() {
        val store = store(RunLogStore.Limits(runContentBytes = 300))
        val session = store.open("R9")!!
        session.record("run_start", mapOf("run" to "R9"), control = true)
        repeat(5) { session.record("attempt_delta", mapOf("text" to "x".repeat(120))) }
        store.end(session, "failed")

        val lines = store.lines(session)
        assertEquals("run_end", lines.last()["t"])
        assertEquals("failed", lines.last()["status"])
        val gap = lines.single { it["t"] == "gap" }
        assertEquals("size_cap", gap["reason"])
        val written = lines.count { it["t"] == "attempt_delta" }
        assertEquals(5L, written + gap.long("to_seq") - gap.long("from_seq") + 1)
    }

    @Test
    fun imagesAreStoredOnceByContentBeforeTheLineThatUsesThem() {
        val store = store()
        val session = store.open("R10")!!
        val png = "data:image/png;base64," + Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
        session.record(
            "tool_end",
            mapOf(
                "images" to listOf(
                    RunLogImage(png, "image/png", 4, 10, 20, "screenshot"),
                    RunLogImage(png, "image/png", 4),
                    RunLogImage("https://example.com/a.png", "image/png", 99),
                    RunLogImage("/sdcard/a.png", "image/png", 5),
                ),
            ),
        )
        store.end(session)

        val images = store.lines(session).first()["images"] as List<*>
        val first = images[0] as Map<*, *>
        val file = first["file"] as String
        assertEquals(file, (images[1] as Map<*, *>)["file"])
        assertEquals("https://example.com/a.png", (images[2] as Map<*, *>)["url"])
        assertEquals("/sdcard/a.png", (images[3] as Map<*, *>)["ref"])
        assertEquals("screenshot", first["source"])
        val stored = File(store.root, "${session.dirName}/$file")
        assertTrue(stored.isFile)
        assertEquals(listOf<Byte>(1, 2, 3, 4), stored.readBytes().toList())
        assertEquals(1, File(store.root, "${session.dirName}/img").listFiles()!!.size)
    }

    @Test
    fun imagesStopAtTheImageCapButTheLineStays() {
        val store = store(RunLogStore.Limits(runImageBytes = 3))
        val session = store.open("R11")!!
        fun image(byte: Byte) = RunLogImage(
            "data:image/png;base64," + Base64.getEncoder().encodeToString(ByteArray(4) { byte }), "image/png", 4,
        )
        session.record("tool_end", mapOf("images" to listOf(image(1))))
        session.record("tool_end", mapOf("images" to listOf(image(2))))
        store.end(session)

        val lines = store.lines(session)
        assertNotNull(((lines[0]["images"] as List<*>)[0] as Map<*, *>)["file"])
        assertEquals("size_cap", ((lines[1]["images"] as List<*>)[0] as Map<*, *>)["dropped"])
    }

    @Test
    fun imagesOverTheReferenceBudgetAreDroppedButTheLineIsKept() {
        val store = store(RunLogStore.Limits(referenceChars = 10))
        val session = store.open("R12")!!
        val png = "data:image/png;base64," + Base64.getEncoder().encodeToString(ByteArray(8))
        session.record("tool_end", mapOf("result" to "ok", "images" to listOf(RunLogImage(png, "image/png", 8))))
        store.end(session)

        val line = store.lines(session).first()
        assertEquals("ok", line["result"])
        assertEquals("queue_full", ((line["images"] as List<*>)[0] as Map<*, *>)["dropped"])
        assertFalse(File(store.root, "${session.dirName}/img").exists())
    }

    @Test
    fun startupDeletesKilledRunsAndTemporaryFiles() {
        val root = File(temp.root, "run-log")
        val killed = File(root, "R1-20261006-100000").apply { mkdirs() }
        File(killed, RunLogStore.LOG_FILE).writeText("""{"seq":1,"t":"run_start"}""" + "\n")
        val ended = File(root, "R2-20261006-100100").apply { mkdirs() }
        File(ended, RunLogStore.LOG_FILE).writeText(
            """{"seq":1,"t":"run_start"}""" + "\n" + """{"seq":2,"t":"run_end","status":"completed"}""" + "\n",
        )
        val leftover = File(ended, "img/abc.png.tmp").apply { parentFile!!.mkdirs(); writeText("x") }

        val store = store(root = root)
        assertTrue(store.awaitIdle())

        assertFalse(killed.exists())
        assertTrue(ended.isDirectory)
        assertFalse(leftover.exists())
        assertEquals(listOf("R2-20261006-100100"), store.dirInfos().map { it.dir })
    }

    @Test
    fun retentionKeepsTheNewestRunsAndOpenRefusesWhenActiveRunsAloneAreOverTheTotal() {
        val store = store(RunLogStore.Limits(maxRuns = 2, totalBytes = 1_000))
        val runs = (1..3).map { number ->
            wall += 60_000
            store.open("R$number")!!.also { session ->
                session.record("run_start", mapOf("run" to "R$number"), control = true)
                store.end(session)
                assertTrue(store.awaitIdle())
            }
        }
        assertTrue(store.awaitIdle())
        assertFalse(File(store.root, runs[0].dirName).exists())
        assertEquals(runs.drop(1).map { it.dirName }, store.dirInfos().map { it.dir })

        wall += 60_000
        val big = store.open("R4")!!
        big.record("tool_end", mapOf("result" to "x".repeat(1_500)))
        assertTrue(store.awaitIdle())
        wall += 60_000
        // 已结束的都能淘汰，但进行中的 R4 一个就超过总量：这一次只记元数据。
        assertNull(store.open("R5"))
    }

    @Test
    fun deletingAConversationRemovesEndedRunsAndActiveOnesWhenTheyEnd() {
        val store = store()
        val ended = store.open("R20")!!
        store.bind(ended, "conv-a")
        store.end(ended)
        wall += 60_000
        val active = store.open("R21")!!
        store.bind(active, "conv-a")
        wall += 60_000
        val other = store.open("R22")!!
        store.bind(other, "conv-b")
        store.end(other)
        assertTrue(store.awaitIdle())

        store.deleteConversation("conv-a")
        assertTrue(store.awaitIdle())
        assertFalse(File(store.root, ended.dirName).exists())
        assertTrue(File(store.root, active.dirName).exists())

        active.record("user", mapOf("kind" to "stop"))
        store.end(active, "cancelled")
        assertTrue(store.awaitIdle())
        assertFalse(File(store.root, active.dirName).exists())
        assertTrue(File(store.root, other.dirName).exists())
        assertTrue(File(store.root, RunLogStore.INDEX_FILE).readText().contains("conv-b"))
    }

    @Test
    fun deletingAConversationWaitsForAnExportInProgress() {
        val store = store()
        val session = store.open("R25")!!
        store.bind(session, "conv-x")
        store.end(session)
        assertTrue(store.awaitIdle())
        store.pin(session.dirName)

        store.deleteConversation("conv-x")
        assertTrue(store.awaitIdle())
        assertTrue(File(store.root, session.dirName).exists())

        store.unpin(session.dirName)
        assertTrue(store.awaitIdle())
        assertFalse(File(store.root, session.dirName).exists())
    }

    @Test
    fun clearKeepsActiveAndPinnedRuns() {
        val store = store()
        val done = store.open("R30")!!
        store.end(done)
        wall += 60_000
        val pinned = store.open("R31")!!
        store.end(pinned)
        wall += 60_000
        val active = store.open("R32")!!
        assertTrue(store.awaitIdle())
        store.pin(pinned.dirName)

        var removed = -1
        store.clear { removed = it }
        assertTrue(store.awaitIdle())
        assertEquals(1, removed)
        assertFalse(File(store.root, done.dirName).exists())
        assertTrue(File(store.root, pinned.dirName).exists())
        assertTrue(File(store.root, active.dirName).exists())
    }

    @Test
    fun writeFailureStopsContentAndIsReportedInTheExistingLog() {
        val store = store()
        val session = store.open("R40")!!
        assertTrue(store.awaitIdle())
        // 目录被换成文件：第一行就写不进去。
        val dir = File(store.root, session.dirName)
        dir.deleteRecursively()
        dir.writeText("not a directory")
        session.record("run_start", mapOf("run" to "R40"), control = true)
        assertTrue(store.awaitIdle())
        assertTrue(session.writeFailed)
        assertEquals(listOf("R40" to "run_log.write_failed"), failures)

        session.record("tool_end", mapOf("result" to "丢弃"))
        store.end(session)
        assertTrue(store.awaitIdle())
        assertTrue(store.dirInfo(session.dirName)!!.writeFailed)
        assertTrue(store.dirInfo(session.dirName)!!.ended)
    }
}
