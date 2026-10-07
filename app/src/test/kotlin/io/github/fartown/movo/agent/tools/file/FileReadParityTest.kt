package io.github.fartown.movo.agent.tools.file

import android.content.Context
import android.webkit.MimeTypeMap
import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.device.BoundedRootCommandExecutor
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.tools.core.ContractTool
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.agent.tools.core.ToolFailure
import io.github.fartown.movo.agent.tools.core.ToolPipeline
import io.github.fartown.movo.agent.tools.core.ToolProjection
import io.github.fartown.movo.agent.tools.core.ToolProvider
import io.github.fartown.movo.agent.tools.core.ToolRegistry
import io.github.fartown.movo.core.AndroidAgentLogger
import java.io.File
import java.io.StringReader
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * file_read / file_write / file_list 与重构前对齐（重构差异 T3 第 26–28 条）：
 * - 读大文本按字数分页、一定给续读位置，超长的一行也能接着读，整个文件不进内存；
 * - 是不是文本按内容判断（.ts 代码、.svg、.m3u8 不再被当成视频、图片），GBK 等编码能读；
 * - file_list 一页不超过给模型的上限；file_write 回绝对路径；缺「所有文件访问」时说清楚。
 */
@RunWith(RobolectricTestRunner::class)
class FileReadParityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val noRoot = BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = { false })
    private val dir = File(context.cacheDir, "file-read-parity").apply { mkdirs() }

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun backend(sharedHidden: Boolean = false) =
        RealFileReadBackend(context, noRoot, rootAvailable = { false }, sharedStorageHidden = { sharedHidden })

    private fun failure(block: () -> Unit): ToolFailure {
        try {
            block()
        } catch (failure: ToolFailure) {
            return failure
        }
        fail("应抛 ToolFailure")
        throw IllegalStateException()
    }

    // ---- 分页 ----

    private fun page(text: String, offset: Int = 1, column: Int = 0, limit: Int = 2000, max: Int = TextPager.MAX_CHARS) =
        TextPager.read(StringReader(text), "utf-8", offset, column, limit, max)

    @Test
    fun pager_stopsAtTheCharBudget_andAlwaysSaysWhereToContinue() {
        val text = (1..5000).joinToString("\n") { "line $it" }

        val first = page(text)

        assertTrue(first.content.length <= TextPager.MAX_CHARS)
        assertEquals(1, first.startLine)
        // 有整行时停在行尾：下一页从下一行开头读，不用行内位置。
        assertEquals(first.endLine + 1, first.nextOffsetLine)
        assertNull(first.nextColumn)
        assertTrue(first.content.endsWith("line ${first.endLine}"))
        assertEquals(5000, first.totalLines)
    }

    @Test
    fun pager_readsTheWholeFileBackPageByPage() {
        val text = (1..3000).joinToString("\n") { "第 $it 行 \"引号\" \\ tab\t" } + "\n" + "x".repeat(40_000) + "\nlast"
        val pieces = mutableListOf<String>()
        var offset = 1
        var column = 0
        var guard = 0
        while (true) {
            val read = page(text, offset, column)
            pieces += read.content
            val next = read.nextOffsetLine ?: break
            // 续读：同一行接着读时直接拼，换行了补上换行。
            pieces += if (read.nextColumn != null) "" else "\n"
            offset = next
            column = read.nextColumn ?: 0
            assertTrue(guard++ < 100)
        }
        assertEquals(text, pieces.joinToString(""))
    }

    @Test
    fun pager_longSingleLine_continuesInsideTheLine() {
        val line = (0 until 50_000).joinToString("") { ('a' + it % 26).toString() }

        val first = page(line)
        assertEquals(1, first.nextOffsetLine)
        assertEquals(first.content.length, first.nextColumn)
        val second = page(line, offset = 1, column = first.nextColumn!!)
        assertEquals(line.substring(first.content.length, first.content.length + second.content.length), second.content)
        assertEquals(first.content.length, second.startColumn)
    }

    @Test
    fun pager_neverSplitsASurrogatePair() {
        val line = "😀".repeat(10_000)

        val first = page(line, max = 1001)

        assertEquals(1000, first.content.length)
        assertEquals(1000, first.nextColumn)
    }

    @Test
    fun pager_lineLimitAndEndOfFile() {
        val read = page("a\nb\nc\n", limit = 2)
        assertEquals("a\nb", read.content)
        assertEquals(3, read.nextOffsetLine)
        assertEquals(3, read.totalLines)

        val rest = page("a\nb\nc\n", offset = 3)
        assertEquals("c", rest.content)
        assertNull(rest.nextOffsetLine)
        assertEquals(3, rest.endLine)

        val past = page("a\nb\n", offset = 9)
        assertEquals("", past.content)
        assertNull(past.nextOffsetLine)
        assertEquals(2, past.totalLines)
    }

    // ---- 编码与类型 ----

    @Test
    fun encoding_gbkTextIsRecognised() {
        val gbk = "歌词：月亮代表我的心\n第二行".toByteArray(charset("GBK"))

        assertEquals(FileKind.TEXT, FileSupport.sniffKind(gbk))
        assertEquals("gb18030", FileSupport.detectEncoding(gbk).name)
        assertEquals("utf-8", FileSupport.detectEncoding("普通文本".toByteArray()).name)
        assertEquals("utf-16le", FileSupport.detectEncoding(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41, 0)).name)
        assertEquals(FileKind.PDF, FileSupport.sniffKind("%PDF-1.7\n%âãÏÓ".toByteArray(Charsets.ISO_8859_1)))
    }

    @Test
    fun readText_gbkFile_isDecoded() {
        val file = File(dir, "song.lrc").apply { writeBytes("[00:01]月亮代表我的心\n[00:05]你问我爱你有多深".toByteArray(charset("GBK"))) }

        val read = backend().readText(file.absolutePath, 1, 100)

        assertEquals("gb18030", read.encoding)
        assertEquals("[00:01]月亮代表我的心\n[00:05]你问我爱你有多深", read.content)
    }

    @Test
    fun readText_noExtensionGbkFile_isText() {
        val file = File(dir, "config_no_ext").apply { writeBytes("名称=测试\n".toByteArray(charset("GBK"))) }

        assertEquals(FileKind.TEXT, backend().resolve(file.absolutePath)!!.kind)
    }

    @Test
    fun textFilesWithMediaExtensions_areReadAsText() {
        // Android 把 .ts 映射成 video/mp2t、.svg 映射成 image/svg+xml：以前按 mime 判成视频、图片，读不了。
        shadowOf(MimeTypeMap.getSingleton()).apply {
            addExtensionMimeTypeMapping("ts", "video/mp2t")
            addExtensionMimeTypeMapping("svg", "image/svg+xml")
            addExtensionMimeTypeMapping("m3u8", "audio/x-mpegurl")
        }
        val code = File(dir, "app.ts").apply { writeText("export const answer: number = 42\n") }
        val svg = File(dir, "icon.svg").apply { writeText("<svg xmlns=\"http://www.w3.org/2000/svg\"/>\n") }
        val playlist = File(dir, "live.m3u8").apply { writeText("#EXTM3U\n#EXT-X-VERSION:3\n") }
        val video = File(dir, "clip.ts").apply { writeBytes(ByteArray(188) { if (it % 188 == 0) 0x47 else 0 }) }

        val reader = backend()
        assertEquals(FileKind.TEXT, reader.resolve(code.absolutePath)!!.kind)
        assertEquals(FileKind.TEXT, reader.resolve(svg.absolutePath)!!.kind)
        assertEquals(FileKind.TEXT, reader.resolve(playlist.absolutePath)!!.kind)
        assertEquals(FileKind.VIDEO, reader.resolve(video.absolutePath)!!.kind)
    }

    // ---- 失败时说清原因 ----

    @Test
    fun directory_isReportedAsADirectory() {
        val failure = failure { backend().resolve(dir.absolutePath) }

        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, failure.code)
        assertTrue(failure.hint!!.contains("file_list"))
    }

    @Test
    fun sharedStorageWithoutAllFilesAccess_saysWhatIsMissing() {
        val failure = failure { backend(sharedHidden = true).resolve("/sdcard/Download/report.txt") }
        assertEquals(ToolErrorCode.PERMISSION_REQUIRED, failure.code)
        assertTrue(failure.message.contains("所有文件访问"))

        // 有权限时就是找不到。
        assertNull(backend(sharedHidden = false).resolve("/sdcard/Download/report.txt"))
    }

    @Test
    fun write_returnsTheAbsolutePath_andExplainsDeniedWrites() {
        val writer = RealFileWriteBackend(context, rootAvailable = { false }, sharedStorageHidden = { true })
        val result = writer.write("parity-test/note.txt", "hello", append = false)
        assertTrue(result.absolutePath!!, File(result.absolutePath!!).isAbsolute)
        assertTrue(result.absolutePath!!.endsWith("parity-test/note.txt"))
        assertEquals("hello", File(result.absolutePath!!).readText())
        File(result.absolutePath!!).parentFile!!.deleteRecursively()

        val shared = failure { writer.write("/sdcard/Movo-parity-test/a.txt", "x", append = false) }
        assertEquals(ToolErrorCode.PERMISSION_REQUIRED, shared.code)
        assertTrue(shared.message.contains("所有文件访问"))

        val system = failure { writer.write("/proc/movo-parity-test/a.txt", "x", append = false) }
        assertEquals(ToolErrorCode.PERMISSION_REQUIRED, system.code)
        assertFalse(system.message.contains("所有文件访问"))
    }

    // ---- 经管线给模型的结果 ----

    private fun pipeline(vararg tools: ContractTool<*, *>) = ToolPipeline(
        registry = ToolRegistry(listOf(object : ToolProvider { override val tools = tools.toList() })),
        environment = { ToolEnvironment() },
        appContext = context,
        logger = AndroidAgentLogger,
        runId = "run1",
        cancelled = { false },
    ).also { it.catalog() }

    private fun call(name: String, args: String) = AgentModelClient.ToolCall("c1", name, args)

    @Test
    fun fileRead_bigLog_staysStructured_andGivesNextOffset() {
        val file = File(dir, "big.log").apply { writeText((1..4000).joinToString("\n") { "2026-10-07 12:00:00 INFO request $it done \"ok\"" }) }

        val result = pipeline(ContractTool(FileReadTool(backend()))).execute(call("file_read", """{"file":"${file.absolutePath}"}"""))

        assertTrue(result.content.length <= ToolProjection.MAX_MODEL_CHARS)
        val data = JSONObject(result.content).getJSONObject("data")
        val next = data.getInt("next_offset")
        assertEquals(data.getInt("end_line") + 1, next)
        assertEquals(4000, data.getInt("total_lines"))
        assertEquals(4000, data.getJSONObject("truncated").getInt("total"))
    }

    @Test
    fun fileRead_longLine_givesColumnToContinue() {
        val file = File(dir, "min.json").apply { writeText("{" + (1..5000).joinToString(",") { "\"k$it\":$it" } + "}") }
        val p = pipeline(ContractTool(FileReadTool(backend())))

        val first = JSONObject(p.execute(call("file_read", """{"file":"${file.absolutePath}"}""")).content).getJSONObject("data")
        val column = first.getInt("next_column")
        assertEquals(1, first.getInt("next_offset"))
        val second = JSONObject(
            p.execute(call("file_read", """{"file":"${file.absolutePath}","offset":1,"column":$column}""")).content,
        ).getJSONObject("data")

        assertEquals(column, second.getInt("start_column"))
        val firstText = first.getString("content")
        val secondText = second.getString("content")
        assertEquals(file.readText().substring(firstText.length, firstText.length + secondText.length), secondText)
    }

    @Test
    fun fileList_longNames_pageStaysUnderTheLimitWithACursor() {
        val entries = (0 until 200).map { DirEntry("很长的文件名".repeat(30) + "-$it.txt", "file", 1, 1) }
        val listBackend = object : FileListBackend {
            override fun list(path: String?, hidden: Boolean, limit: Int, cursor: String?) =
                FileListOutput("/sdcard/Download", entries.drop(cursor?.toInt() ?: 0).take(limit), nextCursor = null)
        }

        val result = pipeline(ContractTool(FileListTool(listBackend))).execute(call("file_list", """{"limit":200,"cursor":"10"}"""))

        assertTrue(result.content.length <= ToolProjection.MAX_MODEL_CHARS)
        val data = JSONObject(result.content).getJSONObject("data")
        val count = data.getInt("count")
        assertTrue(count in 1 until 190)
        assertEquals((10 + count).toString(), data.getString("next_cursor"))
    }
}
