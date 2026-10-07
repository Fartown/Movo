package io.github.fartown.movo.agent.tools.file

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
import io.github.fartown.movo.agent.tools.core.ToolSwitches
import io.github.fartown.movo.agent.tools.core.UserAnswer
import io.github.fartown.movo.agent.tools.core.UserInteraction
import io.github.fartown.movo.agent.tools.core.UserQuestion
import io.github.fartown.movo.core.AndroidAgentLogger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 文件领域：file_search / file_read / file_write / file_list 经 ToolPipeline 的合同验证。 */
@RunWith(RobolectricTestRunner::class)
class FileToolsTest {

    // ---- 假后端 ----
    private var rootGranted = false

    private val searchBackend = object : FileSearchBackend {
        override fun rootAvailable() = rootGranted
        override fun search(
            type: FileType,
            location: FileLocation,
            query: String?,
            sinceMillis: Long?,
            untilMillis: Long?,
            limit: Int,
            cursor: String?,
        ) = FileSearchOutput(
            items = listOf(
                FileSearchItem(
                    handle = FileSupport.encodeHandle("/sdcard/a.png", 100, 1000),
                    name = "a.png", mime = "image/png", sizeBytes = 100, timeMillis = 1000, path = "/sdcard/a.png",
                ),
            ),
            nextCursor = null, total = 1,
        )
    }

    private val sampleImage = AgentModelClient.ModelImage(
        reference = "data:image/jpeg;base64,AAAA",
        mimeType = "image/jpeg",
        bytes = 3,
        width = 640,
        height = 480,
        source = "file_read",
    )

    private val readBackend = object : FileReadBackend {
        override fun resolve(file: String): ResolvedFile? = when {
            file.contains("missing") -> null
            file.endsWith(".png") -> ResolvedFile(FileKind.IMAGE, file, true, 100, "image/png")
            file.endsWith(".pdf") -> ResolvedFile(FileKind.PDF, file, true, 100, "application/pdf")
            else -> ResolvedFile(FileKind.TEXT, file, true, 50, "text/plain")
        }
        override fun readText(file: String, offsetLine: Int, limitLines: Int, column: Int) =
            TextRead(
                "line1\nline2", "utf-8", totalLines = 10, nextOffsetLine = offsetLine + 2,
                startLine = offsetLine, endLine = offsetLine + 1,
            )
        override fun readImage(file: String) = ImageRead(640, 480, sampleImage)
    }

    private var existedBefore = false
    private val writeBackend = object : FileWriteBackend {
        override fun exists(path: String) = existedBefore
        override fun write(path: String, content: String, append: Boolean) =
            FileWriteResult(bytesWritten = content.length.toLong(), sha256Hex = "deadbeef", verifiedSize = content.length.toLong())
    }

    private val listBackend = object : FileListBackend {
        override fun list(path: String?, hidden: Boolean, limit: Int, cursor: String?) =
            FileListOutput(
                path = "/workspace",
                entries = listOf(DirEntry("a.txt", "file", 12, 1000), DirEntry("sub", "dir", 0, 2000)),
                nextCursor = null,
            )
    }

    private fun provider() = object : ToolProvider {
        override val tools = listOf(
            ContractTool(FileSearchTool(searchBackend)),
            ContractTool(FileReadTool(readBackend)),
            ContractTool(FileWriteTool(writeBackend)),
            ContractTool(FileListTool(listBackend)),
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
    fun fileSearch_readonly_private_noEffectVerified() {
        val r = pipeline().execute(call("file_search", """{"type":"image"}"""))
        val json = JSONObject(r.content)
        assertEquals("ok", json.getString("status"))
        assertEquals(1, json.getJSONObject("data").getInt("count"))
        assertFalse(json.has("effect_verified"))
        assertTrue(r.sensitive) // PRIVATE
    }

    @Test
    fun fileSearch_chatImages_withoutRoot_rootRequired() {
        rootGranted = false
        val r = pipeline().execute(call("file_search", """{"type":"image","location":"wechat"}"""))
        assertEquals("error", r.status)
        assertEquals("ROOT_REQUIRED", r.errorCode)
    }

    @Test
    fun fileSearch_chatImages_onlyImages() {
        // 聊天缓存目录里只有图片：查视频不静默回空，直接说明。
        rootGranted = true
        val r = pipeline(ToolEnvironment(rootAvailable = true))
            .execute(call("file_search", """{"type":"video","location":"qq"}"""))
        assertEquals("INVALID_ARGUMENTS", r.errorCode)
    }

    @Test
    fun fileSearch_chatLocationsListedOnlyWithRoot() {
        val tool = ContractTool(FileSearchTool(searchBackend))
        fun locations(env: ToolEnvironment): Set<String> {
            val array = tool.parameters(env).getJSONObject("properties").getJSONObject("location").getJSONArray("enum")
            return (0 until array.length()).map { array.getString(it) }.toSet()
        }
        assertFalse("wechat" in locations(ToolEnvironment(rootAvailable = false)))
        assertFalse("qq" in locations(ToolEnvironment(rootAvailable = false)))
        assertTrue(locations(ToolEnvironment(rootAvailable = true)).containsAll(setOf("wechat", "qq")))
    }

    @Test
    fun chatImageSource_parsesScanRows_andChecksPrintfBeforeScanning() {
        val dir = ChatImageSource.QQ.directory
        val stdout = listOf(
            "1700000000.5|2048|$dir/chatraw/a/1.jpg",
            "1699999999.0|512|$dir/chatthumb/a/2",
            "1699999998.0|1024|/elsewhere/3.jpg",
            "garbage line",
        ).joinToString("\n")
        val rows = ChatImageSource.QQ.parse(stdout)
        assertEquals(2, rows.size)
        assertEquals(1_700_000_000_500L, rows[0].modifiedAtMillis)
        assertEquals("original", rows[0].variant)
        assertEquals("thumbnail", rows[1].variant)
        assertEquals("thumbnail", ChatImageSource.WECHAT.parse("1.0|1|${ChatImageSource.WECHAT.directory}/x/image2/ab/th_abc").single().variant)

        val command = ChatImageSource.WECHAT.command()
        assertTrue(command.contains("exit ${ChatImageSource.EXIT_DIRECTORY_MISSING}"))
        assertTrue(command.contains("-printf '' >/dev/null 2>&1 || exit ${ChatImageSource.EXIT_PRINTF_UNSUPPORTED}"))
        assertTrue(command.contains("*/image2/*"))
    }

    @Test
    fun rootListing_parsesFindOutput() {
        val stdout = "a b.txt\tf\t12\t1700000000.250\nsub\td\t4096\t1700000001\nlink\tl\t7\t1\nodd\tname\tf\t3\t2\n\n"
        val entries = parseRootListing(stdout)
        assertEquals(listOf("a b.txt", "sub", "link", "odd\tname"), entries.map { it.name })
        assertEquals(listOf("file", "dir", "link", "file"), entries.map { it.type })
        assertEquals(1_700_000_000_250L, entries[0].modifiedAtMillis)
        assertEquals(12L, entries[0].sizeBytes)

        val command = rootListCommand("/data/x", hidden = false, fromLine = 81, toLine = 161)
        assertTrue(command.contains("! -name '.*'"))
        assertTrue(command.contains("sed -n '81,161p'"))
        assertFalse(rootListCommand("/data/x", hidden = true, fromLine = 1, toLine = 2).contains("! -name"))
    }

    @Test
    fun fileRead_text_returnsContentAndNextOffset() {
        val r = pipeline().execute(call("file_read", """{"file":"/sdcard/note.txt"}"""))
        val json = JSONObject(r.content)
        assertEquals("ok", json.getString("status"))
        val data = json.getJSONObject("data")
        assertEquals("text", data.getString("kind"))
        assertTrue(data.has("next_offset"))
        assertFalse(json.has("effect_verified"))
    }

    @Test
    fun fileRead_image_marksImageAttached() {
        val r = pipeline().execute(call("file_read", """{"file":"/sdcard/a.png"}"""))
        val data = JSONObject(r.content).getJSONObject("data")
        assertEquals("image", data.getString("kind"))
        assertTrue(data.getBoolean("image_attached"))
    }

    @Test
    fun fileRead_image_isActuallyAttachedToTheModel() {
        // D1：以前只回 image_attached:true，图片并没有附给模型。
        val r = pipeline().execute(call("file_read", """{"file":"/sdcard/a.png"}"""))
        assertEquals(listOf(sampleImage), r.images)
        val json = JSONObject(r.content)
        assertEquals(1, json.getInt("images_attached"))
        val data = json.getJSONObject("data")
        assertTrue(data.getBoolean("image_attached"))
        assertEquals(640, data.getInt("width"))
    }

    @Test
    fun fileRead_text_attachesNoImage() {
        val r = pipeline().execute(call("file_read", """{"file":"/sdcard/note.txt"}"""))
        assertTrue(r.images.isEmpty())
        assertFalse(JSONObject(r.content).has("images_attached"))
    }

    @Test
    fun fileRead_schemaOffersOnlyImplementedParams() {
        // #22：pages / mode / frames 一律 UNSUPPORTED，不再出现在 schema 里；说明里不再说「默认关闭」。
        val tool = ContractTool(FileReadTool(readBackend))
        val props = tool.parameters(ToolEnvironment()).getJSONObject("properties")
        assertEquals(setOf("file", "offset", "column", "limit"), props.keys().asSequence().toSet())
        assertTrue(tool.description.contains("暂不支持"))
        assertFalse(tool.description.contains("默认关闭"))
    }

    @Test
    fun fileRead_pdf_unsupportedWithoutCallingBackend() {
        val r = pipeline().execute(call("file_read", """{"file":"/sdcard/a.pdf"}"""))
        assertEquals("error", r.status)
        assertEquals("UNSUPPORTED", r.errorCode)
    }

    @Test
    fun fileRead_inapplicableParam_invalidArguments() {
        // 对图片给 text 专用的 offset → INVALID_ARGUMENTS（不静默忽略）。
        val r = pipeline().execute(call("file_read", """{"file":"/sdcard/a.png","offset":2}"""))
        assertEquals("error", r.status)
        assertEquals("INVALID_ARGUMENTS", r.errorCode)
    }

    @Test
    fun fileRead_switchOff_disabled() {
        val env = ToolEnvironment(switches = ToolSwitches(terminal = false, sensitiveRead = false))
        val r = pipeline(env).execute(call("file_read", """{"file":"/sdcard/note.txt"}"""))
        assertEquals("error", r.status)
        assertEquals("DISABLED", r.errorCode)
    }

    @Test
    fun fileWrite_workspace_overwrite_doneWithEvidence() {
        existedBefore = true
        val r = pipeline().execute(call("file_write", """{"path":"notes/a.txt","content":"hello"}"""))
        val json = JSONObject(r.content)
        assertEquals("ok", json.getString("status"))
        assertTrue(json.getBoolean("effect_verified"))
        assertFalse(json.getJSONObject("data").getBoolean("created"))
    }

    @Test
    fun fileWrite_outsideWorkspace_filesRule_declineBlocks() {
        var executed = false
        val watchingBackend = object : FileWriteBackend {
            override fun exists(path: String) = false
            override fun write(path: String, content: String, append: Boolean): FileWriteResult {
                executed = true
                return FileWriteResult(5, "x", 5)
            }
        }
        val prov = object : ToolProvider {
            override val tools = listOf(ContractTool(FileWriteTool(watchingBackend)))
        }
        val decline = object : UserInteraction {
            override val available = true
            override fun ask(question: UserQuestion, timeoutMs: Long) = UserAnswer.Declined
            override fun approve(request: ApprovalRequest, timeoutMs: Long) = ApprovalDecision.Declined
        }
        val p = ToolPipeline(
            registry = ToolRegistry(listOf(prov)),
            environment = {
                ToolEnvironment(
                    approvalPolicy = ApprovalPolicy(mode = PermissionMode.MANUAL, categories = setOf(ApprovalCategory.FILES)),
                )
            },
            appContext = ApplicationProvider.getApplicationContext(),
            logger = AndroidAgentLogger,
            runId = "run1",
            cancelled = { false },
            interaction = decline,
        ).also { it.catalog() }
        val r = p.execute(call("file_write", """{"path":"/system/boot.rc","content":"x"}"""))
        assertEquals("error", r.status)
        assertEquals("USER_DECLINED", r.errorCode)
        assertFalse("写系统位置未确认不得执行", executed)
    }

    @Test
    fun fileList_readonly_entries() {
        val r = pipeline().execute(call("file_list", "{}"))
        val json = JSONObject(r.content)
        assertEquals("ok", json.getString("status"))
        assertEquals(2, json.getJSONObject("data").getInt("count"))
        assertFalse(json.has("effect_verified"))
    }

    @Test
    fun fileHandle_hmacBound_rejectsForgedOrTamperedHandles() {
        // 正常签发的句柄可验签解析（路径 + 大小/修改时间）。
        val handle = FileSupport.encodeHandle("/sdcard/DCIM/a.jpg", sizeBytes = 2048, mtimeMillis = 1_700_000_000_000)
        val decoded = FileSupport.decodeHandle(handle)
        assertEquals("/sdcard/DCIM/a.jpg", decoded?.path)
        assertEquals(2048L, decoded?.sizeBytes)
        assertEquals(1_700_000_000_000L, decoded?.mtimeMillis)

        // 伪造：模型自造一个指向任意路径的句柄（无有效签名）→ 拒绝，读不到。
        val forged = "fh2:" + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"p":"/data/data/com.bank/secret","s":1,"m":1}""".toByteArray()) + ".AAAA"
        assertNull(FileSupport.decodeHandle(forged))
        assertNull(FileSupport.decodeHandlePath(forged))

        // 篡改：改动签过名的 body（换路径）→ 签名不符 → 拒绝。
        val dot = handle.lastIndexOf('.')
        val tampered = "fh2:" + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"p":"/etc/hosts","s":2048,"m":1700000000000}""".toByteArray()) +
            handle.substring(dot)
        assertNull(FileSupport.decodeHandle(tampered))

        // 旧格式（无签名段）不再被接受。
        assertNull(FileSupport.decodeHandlePath("fh1:" + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"p":"/sdcard/x","s":1,"m":1}""".toByteArray())))
    }
}
