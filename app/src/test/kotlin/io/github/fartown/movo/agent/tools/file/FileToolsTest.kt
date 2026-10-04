package io.github.fartown.movo.agent.tools.file

import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.model.AgentModelClient
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

    private val readBackend = object : FileReadBackend {
        override fun resolve(file: String): ResolvedFile? = when {
            file.contains("missing") -> null
            file.endsWith(".png") -> ResolvedFile(FileKind.IMAGE, file, true, 100, "image/png")
            else -> ResolvedFile(FileKind.TEXT, file, true, 50, "text/plain")
        }
        override fun readText(file: String, offsetLine: Int, limitLines: Int) =
            TextRead("line1\nline2", "utf-8", totalLines = 10, nextOffsetLine = offsetLine + limitLines)
        override fun readImage(file: String) = ImageRead(640, 480)
        override fun readPdf(file: String, pages: String?, mode: FileReadMode) = error("unused")
        override fun readVideo(file: String, frames: Int) = error("unused")
        override fun transcribeAudio(file: String) = error("unused")
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
    fun fileWrite_externalPath_requiresApproval_declineBlocks() {
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
            environment = { ToolEnvironment() },
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
}
