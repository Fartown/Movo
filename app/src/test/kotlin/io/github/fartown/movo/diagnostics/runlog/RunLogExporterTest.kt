package io.github.fartown.movo.diagnostics.runlog

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.ZipInputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RunLogExporterTest {
    @get:Rule
    val temp = TemporaryFolder()

    private var elapsed = 0L
    private val store by lazy {
        RunLogStore(File(temp.root, "run-log"), RunLogStore.Limits(queueBytes = 3_000, runImageBytes = 8), elapsedClock = { elapsed })
    }

    @After
    fun stop() = store.shutdown()

    @Test
    fun onlyEndedRunsWithoutWriteErrorsCanBeExported() {
        val session = store.open("R1")!!
        assertTrue(store.awaitIdle())
        assertEquals("任务结束后才能导出", RunLogExporter.refusal(store.dirInfo(session.dirName)))
        store.close(session, RunLogRecord("run_end", 0, 0, mapOf("status" to "completed"), control = true))
        assertTrue(store.awaitIdle())
        assertNull(RunLogExporter.refusal(store.dirInfo(session.dirName)))
        assertEquals("这次任务没有完整日志", RunLogExporter.refusal(null))
        assertEquals(
            "完整日志不全，不能导出",
            RunLogExporter.refusal(RunLogStore.DirInfo("R2-20261006-100000", "R2", ended = true, writeFailed = true)),
        )
        assertEquals("Movo-R57-20261006-1454.zip", RunLogExporter.fileName("R57-20261006-145400"))
    }

    @Test
    fun theZipHoldsTheFilesAsTheyAreAndANoteWithWhatIsMissing() {
        val session = store.open("R3")!!
        fun image(byte: Byte) = RunLogImage("data:image/png;base64," + Base64.getEncoder().encodeToString(ByteArray(8) { byte }), "image/png", 8)
        session.record("run_start", mapOf("request" to "写评价"), control = true)
        session.record("tool_end", mapOf("result" to RunLogLimits.cap("x".repeat(20), 5), "images" to listOf(image(1), image(2))))
        session.record("attempt_delta", mapOf("text" to "长".repeat(2_000))) // 排不下：gap
        session.record("user", mapOf("kind" to "stop", "source" to "overlay"))
        store.close(session, RunLogRecord("run_end", 0, 5, mapOf("status" to "cancelled"), control = true))
        assertTrue(store.awaitIdle())

        val dir = File(store.root, session.dirName)
        val zip = ByteArrayOutputStream().also { RunLogExporter.export(dir, it) }.toByteArray()
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { input ->
            generateSequence { input.nextEntry }.forEach { entry -> entries[entry.name] = input.readBytes() }
        }
        val prefix = session.dirName + "/"
        assertTrue(entries.keys.all { it.startsWith(prefix) })
        // 文件名都是 ASCII：macOS 自带的 unzip 不认 zip 里的 UTF-8 文件名。
        assertTrue(entries.keys.toString(), entries.keys.all { name -> name.all { it.code in 0x20..0x7e } })
        assertEquals(File(dir, RunLogStore.LOG_FILE).readBytes().toList(), entries.getValue(prefix + RunLogStore.LOG_FILE).toList())
        assertEquals(1, entries.keys.count { it.startsWith(prefix + "img/") })

        val note = entries.getValue(prefix + RunLogExporter.NOTE_FILE).toString(Charsets.UTF_8)
        assertTrue(note, note.contains("结束状态：cancelled"))
        assertTrue(note, note.contains("丢掉的行（gap）：1 处，序号 3–3（queue_full）"))
        assertTrue(note, note.contains("截断的字段：1 处"))
        assertTrue(note, note.contains("没存的图片：1 张"))
        assertTrue(note, note.contains("没有遮挡"))
    }
}
