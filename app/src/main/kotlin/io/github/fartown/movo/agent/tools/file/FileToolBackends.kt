package io.github.fartown.movo.agent.tools.file

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import io.github.fartown.movo.agent.device.BoundedRootCommandExecutor
import io.github.fartown.movo.agent.device.RootAccess
import io.github.fartown.movo.agent.terminal.TerminalPrivateStorage
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.agent.tools.core.fail
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 文件领域的真实后端实现。
 *
 * - App 可读写的路径（工作区、共享存储、content URI）走 java.io / ContentResolver / MediaStore。
 * - Root 才能访问的路径走 [BoundedRootCommandExecutor]（固定命令 + shell 引用，复用 AgentImageTools 的写法）。
 * - 标 TODO 的大块：Root 任意内容写入、content URI / Root 图片暂存、分页游标、微信/QQ 聊天图片、PDF/视频/音频。
 */

/** 解析工作区内的本地文件；相对路径与 `~` 指向 Movo 终端工作区。 */
private fun resolveLocalFile(context: Context, rawPath: String): File {
    val workspace = TerminalPrivateStorage.workspace(context.filesDir)
    val path = rawPath.trim()
    return when {
        path.isEmpty() || path == "~" -> workspace
        path.startsWith("~/") -> File(workspace, path.removePrefix("~/"))
        path.startsWith("/") -> File(path)
        else -> File(workspace, path)
    }
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

// ---------------------------------------------------------------------------
// file_search
// ---------------------------------------------------------------------------

internal class RealFileSearchBackend(
    private val context: Context,
    private val rootAvailable: () -> Boolean = { RootAccess.isGranted },
) : FileSearchBackend {
    override fun rootAvailable(): Boolean = rootAvailable()

    override fun search(
        type: FileType,
        location: FileLocation,
        query: String?,
        sinceMillis: Long?,
        untilMillis: Long?,
        limit: Int,
        cursor: String?,
    ): FileSearchOutput {
        // TODO：微信/QQ 聊天图片走 Root 私有目录扫描（现码 searchPrivateChatImages），这里先返回空。
        if (location == FileLocation.WECHAT || location == FileLocation.QQ) {
            return FileSearchOutput(emptyList(), nextCursor = null, total = 0)
        }
        val collection = when (type) {
            FileType.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            FileType.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            FileType.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            FileType.DOCUMENT, FileType.ANY ->
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        }
        val selection = StringBuilder()
        val argsList = mutableListOf<String>()
        fun and(clause: String) {
            if (selection.isNotEmpty()) selection.append(" AND ")
            selection.append(clause)
        }
        query?.let {
            and("${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?")
            argsList += "%$it%"
        }
        sinceMillis?.let {
            and("${MediaStore.MediaColumns.DATE_MODIFIED} >= ?")
            argsList += (it / 1000).toString()
        }
        untilMillis?.let {
            and("${MediaStore.MediaColumns.DATE_MODIFIED} <= ?")
            argsList += (it / 1000).toString()
        }
        when (location) {
            FileLocation.RECORDINGS -> {
                and("${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?")
                argsList += "%Record%"
            }
            FileLocation.DOWNLOADS -> {
                and("${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?")
                argsList += "%Download%"
            }
            else -> Unit
        }
        // TODO：cursor 分页先用 offset，未做稳定游标。
        val offset = cursor?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATA,
        )
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        val items = mutableListOf<FileSearchItem>()
        var hasMore = false
        context.contentResolver.query(
            collection,
            projection,
            selection.toString().ifEmpty { null },
            argsList.toTypedArray().takeIf { it.isNotEmpty() },
            sortOrder,
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val dataCol = c.getColumnIndex(MediaStore.MediaColumns.DATA)
            if (offset > 0) c.moveToPosition(offset - 1)
            while (c.moveToNext()) {
                if (items.size >= limit) {
                    hasMore = true
                    break
                }
                val id = c.getLong(idCol)
                val name = c.getString(nameCol) ?: id.toString()
                val size = c.getLong(sizeCol)
                val mtime = c.getLong(dateCol) * 1000
                val path = dataCol.takeIf { it >= 0 }?.let { c.getString(it) }
                    ?: Uri.withAppendedPath(collection, id.toString()).toString()
                items += FileSearchItem(
                    handle = FileSupport.encodeHandle(path, size, mtime),
                    name = name,
                    mime = c.getString(mimeCol) ?: "application/octet-stream",
                    sizeBytes = size,
                    timeMillis = mtime,
                    path = path,
                )
            }
        }
        return FileSearchOutput(
            items = items,
            nextCursor = if (hasMore) (offset + items.size).toString() else null,
            total = null,
        )
    }
}

// ---------------------------------------------------------------------------
// file_read
// ---------------------------------------------------------------------------

internal class RealFileReadBackend(
    private val context: Context,
    private val root: BoundedRootCommandExecutor,
    private val rootAvailable: () -> Boolean = { RootAccess.isGranted },
) : FileReadBackend {

    override fun resolve(file: String): ResolvedFile? {
        // 句柄 → 路径。
        val handlePath = FileSupport.decodeHandlePath(file)
        if (handlePath != null) return resolvePath(handlePath, viaHandle = true)
        if (file.startsWith("content://")) return resolveContent(file)
        val path = file.removePrefix("file://")
        return resolvePath(path, viaHandle = false)
    }

    private fun resolveContent(uri: String): ResolvedFile? {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return null
        val mime = context.contentResolver.getType(parsed)
        var size = 0L
        runCatching {
            context.contentResolver.query(parsed, null, null, null, null)?.use { c ->
                val sizeCol = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (c.moveToFirst() && sizeCol >= 0) size = c.getLong(sizeCol)
            }
        }
        return ResolvedFile(kindOf(mime, uri), uri, exists = mime != null, sizeBytes = size, mime = mime)
    }

    private fun resolvePath(rawPath: String, viaHandle: Boolean): ResolvedFile? {
        val file = resolveLocalFile(context, rawPath)
        val path = file.absolutePath
        val mime = guessMime(path)
        if (file.exists()) {
            return ResolvedFile(kindOf(mime, path), path, exists = true, sizeBytes = file.length(), mime = mime)
        }
        // App 读不到时，用 Root 探测存在性。
        if (rootAvailable()) {
            val probe = root.execute(
                "[ -e ${FileSupport.shellQuote(path)} ] && stat -c %s ${FileSupport.shellQuote(path)} || echo MISSING",
                timeoutMillis = 10_000,
                maxOutputBytes = 1024,
            )
            if (probe.ok && !probe.stdout.contains("MISSING")) {
                val size = probe.stdout.trim().toLongOrNull() ?: 0L
                return ResolvedFile(kindOf(mime, path), path, exists = true, sizeBytes = size, mime = mime)
            }
        }
        return null
    }

    override fun readText(file: String, offsetLine: Int, limitLines: Int): TextRead {
        val local = resolveLocalFile(context, file)
        val start = offsetLine.coerceAtLeast(1)
        if (local.isFile && local.canRead()) {
            val allLines = local.bufferedReader(Charsets.UTF_8).useLines { it.toList() }
            val total = allLines.size
            val slice = allLines.drop(start - 1).take(limitLines)
            val next = if (start - 1 + slice.size < total) start + slice.size else null
            return TextRead(slice.joinToString("\n"), encoding = "utf-8", totalLines = total, nextOffsetLine = next)
        }
        if (file.startsWith("content://")) {
            val parsed = Uri.parse(file)
            val allLines = context.contentResolver.openInputStream(parsed)?.bufferedReader(Charsets.UTF_8)
                ?.useLines { it.toList() }
                ?: fail(ToolErrorCode.NOT_FOUND, "无法打开 content URI：$file")
            val total = allLines.size
            val slice = allLines.drop(start - 1).take(limitLines)
            val next = if (start - 1 + slice.size < total) start + slice.size else null
            return TextRead(slice.joinToString("\n"), encoding = "utf-8", totalLines = total, nextOffsetLine = next)
        }
        // Root 路径：用 sed 取行，不用 dd（dd bs=1 会切坏 UTF-8，RS:779）。
        if (!rootAvailable()) fail(ToolErrorCode.PERMISSION_REQUIRED, "没有权限读取该文件")
        val end = start + limitLines - 1
        val quoted = FileSupport.shellQuote(local.absolutePath)
        val result = root.execute(
            "sed -n '${start},${end}p' $quoted",
            timeoutMillis = 20_000,
            maxOutputBytes = 256 * 1024,
        )
        if (!result.ok && result.stdout.isEmpty()) fail(ToolErrorCode.SOURCE_UNAVAILABLE, "Root 读取失败", detail = "exit=${result.exitCode}")
        val content = result.stdout.take(16_000)
        val lineCount = if (content.isEmpty()) 0 else content.count { it == '\n' } + 1
        // TODO：Root 文本分页为近似（未取 wc -l 全量行数）。
        val next = if (lineCount >= limitLines) end + 1 else null
        return TextRead(content, encoding = "utf-8", totalLines = start - 1 + lineCount, nextOffsetLine = next)
    }

    override fun readImage(file: String): ImageRead {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        when {
            file.startsWith("content://") -> {
                context.contentResolver.openInputStream(Uri.parse(file))?.use {
                    BitmapFactory.decodeStream(it, null, options)
                } ?: fail(ToolErrorCode.NOT_FOUND, "无法打开图片：$file")
            }
            else -> {
                val local = resolveLocalFile(context, file)
                if (local.isFile && local.canRead()) {
                    BitmapFactory.decodeFile(local.absolutePath, options)
                } else {
                    // TODO：Root 图片先复制到缓存再解码（参照 AgentImageTools.imageCopyCommand）。
                    fail(ToolErrorCode.UNSUPPORTED, "暂不支持读取该图片", detail = "root_image_staging_todo")
                }
            }
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            fail(ToolErrorCode.UNSUPPORTED, "文件不是可识别的图片", detail = "image_decode_failed")
        }
        return ImageRead(options.outWidth, options.outHeight)
    }

    // 默认关闭 / 尚未实现。
    override fun readPdf(file: String, pages: String?, mode: FileReadMode): PdfRead =
        fail(ToolErrorCode.UNSUPPORTED, "PDF 读取默认关闭", detail = "pdf_default_off_api34_render_degrade_todo")

    override fun readVideo(file: String, frames: Int): VideoRead =
        fail(ToolErrorCode.UNSUPPORTED, "视频抽帧默认关闭", detail = "video_default_off_todo")

    override fun transcribeAudio(file: String): AudioTranscript =
        fail(ToolErrorCode.UNSUPPORTED, "音频转写默认关闭，开启后属外发第三方", detail = "audio_transcription_default_off")

    private fun kindOf(mime: String?, path: String): FileKind {
        if (mime != null) {
            when {
                mime.startsWith("text/") || mime in TEXT_MIMES -> return FileKind.TEXT
                mime.startsWith("image/") -> return FileKind.IMAGE
                mime == "application/pdf" -> return FileKind.PDF
                mime.startsWith("video/") -> return FileKind.VIDEO
                mime.startsWith("audio/") -> return FileKind.AUDIO
            }
        }
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            in TEXT_EXT -> FileKind.TEXT
            in IMAGE_EXT -> FileKind.IMAGE
            "pdf" -> FileKind.PDF
            in VIDEO_EXT -> FileKind.VIDEO
            in AUDIO_EXT -> FileKind.AUDIO
            else -> FileKind.UNKNOWN
        }
    }

    private fun guessMime(path: String): String? {
        val ext = path.substringAfterLast('.', "").lowercase().ifEmpty { return null }
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    private companion object {
        val TEXT_MIMES = setOf("application/json", "application/xml", "application/javascript")
        val TEXT_EXT = setOf("txt", "md", "json", "xml", "csv", "log", "kt", "java", "py", "js", "ts", "html", "css", "sh", "yaml", "yml", "ini", "toml", "properties", "gradle")
        val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif")
        val VIDEO_EXT = setOf("mp4", "mkv", "webm", "mov", "avi", "3gp")
        val AUDIO_EXT = setOf("mp3", "wav", "m4a", "aac", "ogg", "flac", "amr")
    }
}

// ---------------------------------------------------------------------------
// file_write
// ---------------------------------------------------------------------------

internal class RealFileWriteBackend(
    private val context: Context,
    private val rootAvailable: () -> Boolean = { RootAccess.isGranted },
) : FileWriteBackend {

    override fun exists(path: String): Boolean {
        val file = resolveLocalFile(context, path)
        return file.exists()
        // TODO：Root 路径存在性探测（rootAvailable 时 stat）。
    }

    override fun write(path: String, content: String, append: Boolean): FileWriteResult {
        val file = resolveLocalFile(context, path)
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            // App 建不出父目录：多半是 Root 路径。
            // TODO：Root 任意内容写入（现码 RootShellTerminalController.writeFile 走 su + stdin）。
            fail(ToolErrorCode.UNSUPPORTED, "无法写入该路径", detail = "root_write_todo")
        }
        if (file.exists() && !file.isFile) {
            fail(ToolErrorCode.CONFLICT, "目标不是普通文件")
        }
        val bytes = content.toByteArray(Charsets.UTF_8)
        runCatching {
            FileOutputStream(file, append).use { it.write(bytes) }
        }.getOrElse {
            fail(ToolErrorCode.UNSUPPORTED, "无法写入该路径", detail = "root_write_todo")
        }
        val verifiedSize = file.length()
        // 覆盖写：回读整文件内容哈希作为证据；追加：只回读 size。
        val hash = if (!append) {
            runCatching { sha256Hex(file.readBytes()) }.getOrNull()
        } else {
            null
        }
        return FileWriteResult(bytesWritten = bytes.size.toLong(), sha256Hex = hash, verifiedSize = verifiedSize)
    }
}

// ---------------------------------------------------------------------------
// file_list
// ---------------------------------------------------------------------------

internal class RealFileListBackend(
    private val context: Context,
    private val root: BoundedRootCommandExecutor,
    private val rootAvailable: () -> Boolean = { RootAccess.isGranted },
) : FileListBackend {

    override fun list(path: String?, hidden: Boolean, limit: Int, cursor: String?): FileListOutput {
        val dir = resolveLocalFile(context, path ?: "~")
        if (dir.isDirectory && dir.canRead()) {
            val all = (dir.listFiles() ?: emptyArray())
                .filter { hidden || !it.name.startsWith('.') }
                .sortedBy { it.name }
            val offset = cursor?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val page = all.drop(offset).take(limit)
            val entries = page.map {
                DirEntry(
                    name = it.name,
                    type = if (it.isDirectory) "dir" else if (it.isFile) "file" else "other",
                    sizeBytes = if (it.isFile) it.length() else 0L,
                    modifiedAtMillis = it.lastModified(),
                )
            }
            val next = if (offset + page.size < all.size) (offset + page.size).toString() else null
            return FileListOutput(dir.absolutePath, entries, next)
        }
        // Root 目录：find -printf 支持大小/时间/分页。
        // TODO：Root find 分页与类型解析（现码 ls -l | head，RS:841）。这里返回空占位。
        if (rootAvailable()) {
            root.execute(
                "find ${FileSupport.shellQuote(dir.absolutePath)} -maxdepth 1 -printf '%f\\t%y\\t%s\\t%T@\\n' 2>/dev/null | head -n ${limit}",
                timeoutMillis = 15_000,
                maxOutputBytes = 64 * 1024,
            )
            return FileListOutput(dir.absolutePath, emptyList(), nextCursor = null)
        }
        fail(ToolErrorCode.PERMISSION_REQUIRED, "没有权限读取该目录")
    }
}
