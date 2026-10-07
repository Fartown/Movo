package io.github.fartown.movo.agent.tools.file

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.device.BoundedRootCommandExecutor
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.agent.tools.core.ToolFailure
import io.github.fartown.movo.core.AndroidAgentLogger
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 文件领域真实后端：file_read 真的把图片编码好附给模型、没有扩展名的文件按内容判定类型；
 * Root 列目录 / 查聊天图片失败时报错，不回会被当成「没有」的空列表。
 * 断言真实的图片编解码，要用 Robolectric 原生图形。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileBackendsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 没有 Root 的执行器：任何 Root 命令都失败。 */
    private val noRoot = BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = { false })

    private fun dir(): File = File(context.cacheDir, "file-backends-test").apply { mkdirs() }

    private fun png(name: String, width: Int = 40, height: Int = 30): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        return File(dir(), name).apply {
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test
    fun readImage_encodesTheImageForTheModel() {
        val backend = RealFileReadBackend(context, noRoot, rootAvailable = { false })
        val file = png("photo.png")
        val resolved = backend.resolve(file.absolutePath)!!
        assertEquals(FileKind.IMAGE, resolved.kind)
        val read = backend.readImage(resolved.path)
        assertEquals(40, read.width)
        assertEquals(30, read.height)
        assertEquals("image/jpeg", read.image.mimeType)
        assertTrue(read.image.reference.startsWith("data:image/jpeg;base64,"))
    }

    @Test
    fun filesWithoutExtension_areJudgedByContent() {
        // 微信/QQ 聊天图片缓存、日志、配置大多没有扩展名：以前一律 UNSUPPORTED。
        val backend = RealFileReadBackend(context, noRoot, rootAvailable = { false })
        val image = png("chatimage_no_ext")
        val text = File(dir(), "notes_no_ext").apply { writeText("第一行\nsecond line\n") }
        val binary = File(dir(), "blob_no_ext").apply { writeBytes(byteArrayOf(0, 1, 2, 3, 0, 5, 6, 7, 8, 9)) }

        assertEquals(FileKind.IMAGE, backend.resolve(image.absolutePath)!!.kind)
        assertEquals(30, backend.readImage(image.absolutePath).height)
        assertEquals(FileKind.TEXT, backend.resolve(text.absolutePath)!!.kind)
        assertEquals("第一行\nsecond line", backend.readText(text.absolutePath, 1, 10).content)
        assertEquals(FileKind.UNKNOWN, backend.resolve(binary.absolutePath)!!.kind)
    }

    @Test
    fun readImage_appUnreadableWithoutRoot_permissionRequired() {
        val backend = RealFileReadBackend(context, noRoot, rootAvailable = { false })
        assertFailure(ToolErrorCode.PERMISSION_REQUIRED) { backend.readImage("/data/system/not_readable.png") }
    }

    @Test
    fun rootListingFailure_isAnErrorNotAnEmptyDirectory() {
        // D3：以前 Root 目录跑了 find 却丢掉输出，永远回空列表。
        val backend = RealFileListBackend(context, noRoot, rootAvailable = { true })
        assertFailure(ToolErrorCode.ROOT_REQUIRED) { backend.list("/data/movo-no-such-dir", hidden = false, limit = 10, cursor = null) }
    }

    @Test
    fun appReadableDirectory_stillListedWithoutRoot() {
        File(dir(), "listed.txt").writeText("x")
        val backend = RealFileListBackend(context, noRoot, rootAvailable = { false })
        val output = backend.list(dir().absolutePath, hidden = false, limit = 200, cursor = null)
        assertTrue(output.entries.any { it.name == "listed.txt" && it.type == "file" })
    }

    @Test
    fun sharedStorageWithoutAllFilesAccess_isAPermissionErrorNotAnEmptyList() {
        // 真机：没有「所有文件访问」时列 /sdcard/Download、按 MediaStore 搜，都回空 ok，像是「没有文件」。
        val download = File(android.os.Environment.getExternalStorageDirectory(), "Download").absolutePath
        val list = RealFileListBackend(context, noRoot, rootAvailable = { false }, sharedStorageHidden = { true })
        assertFailure(ToolErrorCode.PERMISSION_REQUIRED) { list.list(download, hidden = false, limit = 10, cursor = null) }
        val search = RealFileSearchBackend(context, noRoot, rootAvailable = { false }, sharedStorageHidden = { true })
        assertFailure(ToolErrorCode.PERMISSION_REQUIRED) {
            search.search(FileType.ANY, FileLocation.DOWNLOADS, null, null, null, limit = 10, cursor = null)
        }
        // 有 Root 时改用 Root 列（这里的 Root 执行器总是失败：报 Root 的错，而不是缺权限的空列表）。
        val withRoot = RealFileListBackend(context, noRoot, rootAvailable = { true }, sharedStorageHidden = { true })
        assertFailure(ToolErrorCode.ROOT_REQUIRED) { withRoot.list(download, hidden = false, limit = 10, cursor = null) }
    }

    @Test
    fun sharedStorageWithAllFilesAccess_isListedNormally() {
        val dir = File(android.os.Environment.getExternalStorageDirectory(), "Download").apply { mkdirs() }
        File(dir, "shared.txt").writeText("x")
        val output = RealFileListBackend(context, noRoot, rootAvailable = { false }, sharedStorageHidden = { false })
            .list(dir.absolutePath, hidden = false, limit = 10, cursor = null)
        assertTrue(output.entries.any { it.name == "shared.txt" })
    }

    @Test
    fun chatImageScanFailure_isAnErrorNotAnEmptyResult() {
        // C14：以前微信/QQ 位置直接回空列表，模型会以为「没找到」。
        val backend = RealFileSearchBackend(context, noRoot, rootAvailable = { true })
        assertFailure(ToolErrorCode.ROOT_REQUIRED) {
            backend.search(FileType.IMAGE, FileLocation.WECHAT, null, null, null, limit = 10, cursor = null)
        }
        val withoutRoot = RealFileSearchBackend(context, noRoot, rootAvailable = { false })
        assertFailure(ToolErrorCode.ROOT_REQUIRED) {
            withoutRoot.search(FileType.IMAGE, FileLocation.QQ, null, null, null, limit = 10, cursor = null)
        }
    }

    @Test
    fun sniffKind_imageTextAndBinary() {
        val pngHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0)
        assertEquals(FileKind.IMAGE, FileSupport.sniffKind(pngHeader))
        assertEquals(FileKind.TEXT, FileSupport.sniffKind("key=value\n中文".toByteArray()))
        // 读到的开头恰好切在一个多字节字符中间，仍算文本。
        val cut = "中文".toByteArray().copyOf(5)
        assertEquals(FileKind.TEXT, FileSupport.sniffKind(cut))
        assertEquals(FileKind.TEXT, FileSupport.sniffKind(ByteArray(0)))
        assertEquals(FileKind.UNKNOWN, FileSupport.sniffKind(byteArrayOf(0x41, 0x00, 0x42)))
        assertEquals(FileKind.UNKNOWN, FileSupport.sniffKind(byteArrayOf(0xC3.toByte(), 0x28, 0x41)))
    }

    @Test
    fun parseOdHex_readsRootHeaderBytes() {
        val output = " 89 50 4e 47 0d 0a 1a 0a\n 00 ff\n"
        assertArrayEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0xFF.toByte()),
            FileSupport.parseOdHex(output),
        )
        assertTrue(FileSupport.headerCommand("/data/a b").contains("head -c ${FileSupport.SNIFF_BYTES} '/data/a b' | od -An -v -tx1"))
    }

    @Test
    fun rootImageCopy_checksExistenceAndSizeBeforeCopying() {
        val command = rootImageCopyCommand("/data/x'y.jpg", File("/cache/staged.img"))
        assertTrue(command.contains("[ -f '/data/x'\\''y.jpg' ] || exit 21"))
        assertTrue(command.contains("|| exit 22"))
        assertTrue(command.contains("cp '/data/x'\\''y.jpg' '/cache/staged.img' || exit 23"))
    }

    private fun assertFailure(code: ToolErrorCode, block: () -> Unit) {
        try {
            block()
            fail("应当报 $code")
        } catch (failure: ToolFailure) {
            assertEquals(code, failure.code)
        }
    }
}
