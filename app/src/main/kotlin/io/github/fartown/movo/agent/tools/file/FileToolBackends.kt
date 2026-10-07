package io.github.fartown.movo.agent.tools.file

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import io.github.fartown.movo.agent.device.BoundedFileCopy
import io.github.fartown.movo.agent.device.BoundedRootCommandExecutor
import io.github.fartown.movo.agent.device.RootAccess
import io.github.fartown.movo.agent.media.AgentModelImageEncoder
import io.github.fartown.movo.agent.media.MAX_AGENT_IMAGE_BYTES
import io.github.fartown.movo.agent.terminal.TerminalPrivateStorage
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.agent.tools.core.fail
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * 文件领域的真实后端实现。
 *
 * - App 可读写的路径（工作区、共享存储、content URI）走 java.io / ContentResolver / MediaStore。
 * - Root 才能访问的路径走 [BoundedRootCommandExecutor]（固定命令 + shell 引用，复用 AgentImageTools 的写法）。
 * - 没有实现的：Root 任意内容写入（file_write 回 UNSUPPORTED）、PDF/视频/音频读取（file_read 回 UNSUPPORTED）。
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

/**
 * 共享存储（/sdcard）里别的应用的文件看不看得到。Android 11 起要「所有文件访问」：没有时列目录只剩 Movo 自己的文件、
 * MediaStore 也只搜得到 Movo 自己的，结果是空的却像「没有」（真机：/sdcard/Download）。
 */
internal object SharedStorageAccess {
    const val MESSAGE = "Movo 没有「所有文件访问」权限，看不到共享存储里别的应用的文件"
    const val HINT = "请用户在系统设置里给 Movo 打开「所有文件访问」（设置 → 应用 → Movo → 权限）后再试"

    fun hidden(): Boolean =
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            !android.os.Environment.isExternalStorageManager()

    fun covers(file: File): Boolean {
        val path = file.absolutePath
        val external = android.os.Environment.getExternalStorageDirectory().absolutePath
        return path == external || path.startsWith("$external/") || path.startsWith("/sdcard") || path.startsWith("/storage/")
    }
}

/** [BoundedRootCommandExecutor] 在 su 被拒或 Root 不可用时给的 errorCode。 */
private const val ROOT_DENIED = "ROOT_REQUIRED"

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * Root 把图片复制到 App 预先建好的缓存文件（移植旧 AgentImageTools.imageCopyCommand）。
 * 退出码：21 源文件不存在或不可读，22 超过大小上限，23 复制失败。
 */
internal fun rootImageCopyCommand(source: String, destination: File): String {
    val src = FileSupport.shellQuote(source)
    return "[ -f $src ] || exit 21; " +
        "[ \"\$(stat -c %s $src)\" -le $MAX_AGENT_IMAGE_BYTES ] || exit 22; " +
        "cp $src ${FileSupport.shellQuote(destination.absolutePath)} || exit 23"
}

// ---------------------------------------------------------------------------
// file_search
// ---------------------------------------------------------------------------

internal class RealFileSearchBackend(
    private val context: Context,
    private val root: BoundedRootCommandExecutor,
    rootAvailable: () -> Boolean = { RootAccess.isGranted },
    private val sharedStorageHidden: () -> Boolean = SharedStorageAccess::hidden,
) : FileSearchBackend {
    // 构造参数与接口方法同名：类里直接写 rootAvailable() 会调到方法自己（无限递归，原来查微信/QQ 就是栈溢出），这里换个名字存。
    private val rootGranted = rootAvailable

    override fun rootAvailable(): Boolean = rootGranted()

    override fun search(
        type: FileType,
        location: FileLocation,
        query: String?,
        sinceMillis: Long?,
        untilMillis: Long?,
        limit: Int,
        cursor: String?,
    ): FileSearchOutput {
        ChatImageSource.of(location)?.let { source ->
            return searchChatImages(source, query, sinceMillis, untilMillis, limit, cursor)
        }
        // 没有「所有文件访问」时 MediaStore 只回 Movo 自己的文件：说清缺权限，不回一个假的空结果。
        if (sharedStorageHidden()) {
            fail(ToolErrorCode.PERMISSION_REQUIRED, SharedStorageAccess.MESSAGE, hint = SharedStorageAccess.HINT)
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

    /**
     * 微信/QQ 聊天图片（移植旧 search_wechat_chat_images / search_qq_chat_images 的 searchPrivateChatImages）：
     * Root 扫描两家的图片缓存目录，按修改时间倒序取最近一批，再按关键词（匹配路径）和时间过滤、分页。
     * 目录不存在、find 不支持 -printf、Root 执行失败都回明确的错误，不回空列表——空列表会被当成「没找到」。
     */
    private fun searchChatImages(
        source: ChatImageSource,
        query: String?,
        sinceMillis: Long?,
        untilMillis: Long?,
        limit: Int,
        cursor: String?,
    ): FileSearchOutput {
        if (!rootAvailable()) fail(ToolErrorCode.ROOT_REQUIRED, "读取${source.label}聊天图片需要 Root")
        val result = root.execute(source.command(), timeoutMillis = CHAT_IMAGE_TIMEOUT_MS, maxOutputBytes = CHAT_IMAGE_OUTPUT_BYTES)
        when {
            result.ok -> Unit
            result.errorCode == ROOT_DENIED -> fail(ToolErrorCode.ROOT_REQUIRED, "Root 授权被拒绝或已失效，读不了${source.label}聊天图片")
            result.exitCode == ChatImageSource.EXIT_DIRECTORY_MISSING -> fail(
                ToolErrorCode.SOURCE_UNAVAILABLE,
                "找不到${source.label}聊天图片缓存目录",
                hint = "可能没装${source.label}、没登录过，或缓存已被清理",
                detail = source.directory,
            )
            result.exitCode == ChatImageSource.EXIT_PRINTF_UNSUPPORTED -> fail(
                ToolErrorCode.UNSUPPORTED,
                "这台设备的 find 不支持 -printf，无法检索聊天图片",
                detail = "find_printf_unsupported",
            )
            result.timedOut -> fail(ToolErrorCode.TIMEOUT, "检索${source.label}聊天图片超时")
            else -> fail(
                ToolErrorCode.SOURCE_UNAVAILABLE,
                "${source.label}聊天图片缓存暂时不可访问",
                detail = result.errorCode.ifBlank { "exit=${result.exitCode}" },
            )
        }
        val candidates = source.parse(result.stdout)
        val keyword = query?.lowercase()
        val matched = candidates.filter { row ->
            (keyword == null || row.path.lowercase().contains(keyword)) &&
                (sinceMillis == null || row.modifiedAtMillis >= sinceMillis) &&
                (untilMillis == null || row.modifiedAtMillis <= untilMillis)
        }
        val offset = cursor?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val page = matched.drop(offset).take(limit)
        return FileSearchOutput(
            items = page.map { row ->
                FileSearchItem(
                    handle = FileSupport.encodeHandle(row.path, row.sizeBytes, row.modifiedAtMillis),
                    name = row.path.substringAfterLast('/'),
                    mime = chatImageMime(row.path),
                    sizeBytes = row.sizeBytes,
                    timeMillis = row.modifiedAtMillis,
                    path = row.path,
                    variant = row.variant,
                )
            },
            nextCursor = if (offset + page.size < matched.size) (offset + page.size).toString() else null,
            // 扫描数到上限时更早的图片没有扫到，总数只是下限，不报。
            total = matched.size.takeIf { candidates.size < ChatImageSource.CANDIDATE_LIMIT },
        )
    }

    /** 聊天缓存里的图片大多没有扩展名：有扩展名按扩展名，没有就只说是图片（file_read 会按内容判定）。 */
    private fun chatImageMime(path: String): String {
        val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return ext.takeIf { it.isNotEmpty() }
            ?.let { android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: "image/*"
    }

    private companion object {
        const val CHAT_IMAGE_TIMEOUT_MS = 20_000L
        const val CHAT_IMAGE_OUTPUT_BYTES = 512 * 1024
    }
}

/** 一条聊天图片候选：`find -printf '%T@|%s|%p'` 的一行。 */
internal data class ChatImageRow(
    val path: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    /** QQ：original 原图、image 普通、thumbnail 缩略图；微信：thumbnail（th_ 开头）或 image。 */
    val variant: String,
)

/**
 * 微信/QQ 聊天图片缓存的位置与扫描命令（目录、路径过滤沿用重构前 AgentPersonalDataTools）。
 * 微信的聊天图片常见在 image2 目录，过滤里与旧实现的 image 一起匹配（目录结构待真机确认）。
 */
internal enum class ChatImageSource(
    val label: String,
    val directory: String,
    private val pathFilter: String,
) {
    WECHAT(
        label = "微信",
        directory = "/storage/emulated/0/Android/data/com.tencent.mm/MicroMsg",
        pathFilter = "\\( -path '*/image/*' -o -path '*/image2/*' \\)",
    ),
    QQ(
        label = "QQ",
        directory = "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic",
        pathFilter = "\\( -path '*/chatimg/*' -o -path '*/chatraw/*' -o -path '*/chatthumb/*' \\)",
    ),
    ;

    /**
     * 先确认目录存在（退出码 21）、find 支持 -printf（退出码 22），再按修改时间倒序取最近 [CANDIDATE_LIMIT] 个。
     * 只收不超过附图上限的文件，超过的 file_read 也附不了。
     */
    fun command(): String {
        val dir = FileSupport.shellQuote(directory)
        return "[ -d $dir ] || exit $EXIT_DIRECTORY_MISSING; " +
            "find $dir -maxdepth 0 -printf '' >/dev/null 2>&1 || exit $EXIT_PRINTF_UNSUPPORTED; " +
            "find $dir -type f $pathFilter -size -${MAX_AGENT_IMAGE_BYTES}c -printf '%T@|%s|%p\\n' 2>/dev/null | " +
            "sort -rn | head -n $CANDIDATE_LIMIT"
    }

    /** 解析扫描输出；不在本目录下或格式不对的行丢弃。 */
    fun parse(stdout: String): List<ChatImageRow> = stdout.lineSequence().mapNotNull { line ->
        val fields = line.split('|', limit = 3)
        if (fields.size != 3) return@mapNotNull null
        val seconds = fields[0].toDoubleOrNull() ?: return@mapNotNull null
        val size = fields[1].toLongOrNull() ?: return@mapNotNull null
        val path = fields[2]
        if (!path.startsWith("$directory/")) return@mapNotNull null
        ChatImageRow(path, size, (seconds * 1000).toLong(), variantOf(path))
    }.toList()

    private fun variantOf(path: String): String = when (this) {
        QQ -> when {
            "/chatraw/" in path -> "original"
            "/chatthumb/" in path -> "thumbnail"
            else -> "image"
        }
        WECHAT -> if (path.substringAfterLast('/').startsWith("th_")) "thumbnail" else "image"
    }

    companion object {
        const val EXIT_DIRECTORY_MISSING = 21
        const val EXIT_PRINTF_UNSUPPORTED = 22
        /** 一次最多扫多少个候选（按修改时间最新的）；每行约 150 字节，输出远小于 512KB 上限。 */
        const val CANDIDATE_LIMIT = 2_000

        fun of(location: FileLocation): ChatImageSource? = when (location) {
            FileLocation.WECHAT -> WECHAT
            FileLocation.QQ -> QQ
            else -> null
        }
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
        val kind = kindOf(mime, uri).orSniff { contentHeader(parsed) }
        return ResolvedFile(kind, uri, exists = mime != null, sizeBytes = size, mime = mime)
    }

    private fun resolvePath(rawPath: String, viaHandle: Boolean): ResolvedFile? {
        val file = resolveLocalFile(context, rawPath)
        val path = file.absolutePath
        val mime = guessMime(path)
        if (file.exists()) {
            val kind = kindOf(mime, path).orSniff { localHeader(file) }
            return ResolvedFile(kind, path, exists = true, sizeBytes = file.length(), mime = mime)
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
                val kind = kindOf(mime, path).orSniff { rootHeader(path) }
                return ResolvedFile(kind, path, exists = true, sizeBytes = size, mime = mime)
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
        val bytes = imageBytes(file)
        // 与重构前 read_image 同一编码：统一转成 JPEG、不缩放（AgentImageCodec.fromToolFile）。
        val image = AgentModelImageEncoder.toolVision(bytes, source = "file_read")
            ?: fail(ToolErrorCode.UNSUPPORTED, "文件不是可识别的图片", detail = "image_decode_failed")
        return ImageRead(width = image.width ?: 0, height = image.height ?: 0, image = image)
    }

    /** 读图片原始字节（不超过 [MAX_AGENT_IMAGE_BYTES]）：App 能读的直接读，content URI 走 ContentResolver，其余用 Root 复制到缓存再读。 */
    private fun imageBytes(file: String): ByteArray {
        if (file.startsWith("content://")) {
            val input = runCatching { context.contentResolver.openInputStream(Uri.parse(file)) }.getOrNull()
                ?: fail(ToolErrorCode.NOT_FOUND, "无法打开图片：$file")
            return input.use { boundedImageBytes(it) }
        }
        val local = resolveLocalFile(context, file)
        if (local.isFile && local.canRead()) {
            if (local.length() > MAX_AGENT_IMAGE_BYTES) imageTooLarge()
            return local.inputStream().use { boundedImageBytes(it) }
        }
        if (!rootAvailable()) fail(ToolErrorCode.PERMISSION_REQUIRED, "没有权限读取该图片")
        return rootImageBytes(local.absolutePath)
    }

    /**
     * Root 复制到 Movo 缓存再读（移植旧 AgentImageTools.readImage）：目标文件由 App 先建好，
     * cp 写入已有文件不改属主，App 随后能读；读完即删。
     */
    private fun rootImageBytes(path: String): ByteArray {
        val staged = runCatching { File.createTempFile("movo-read-image-", ".img", imageCacheDirectory()) }
            .getOrElse { fail(ToolErrorCode.SOURCE_UNAVAILABLE, "无法创建图片临时文件") }
        try {
            val result = root.execute(
                rootImageCopyCommand(path, staged),
                timeoutMillis = IMAGE_COPY_TIMEOUT_MS,
                maxOutputBytes = 8 * 1024,
            )
            when {
                result.ok -> Unit
                result.exitCode == 21 -> fail(ToolErrorCode.NOT_FOUND, "图片不存在或当前不可读：$path")
                result.exitCode == 22 -> imageTooLarge()
                else -> fail(ToolErrorCode.SOURCE_UNAVAILABLE, "Root 无法把图片复制到 Movo 缓存", detail = "exit=${result.exitCode}")
            }
            return staged.inputStream().use { boundedImageBytes(it) }
        } finally {
            staged.delete()
        }
    }

    private fun boundedImageBytes(input: InputStream): ByteArray = try {
        ByteArrayOutputStream().also { BoundedFileCopy.copy(input, it, MAX_AGENT_IMAGE_BYTES.toLong()) }.toByteArray()
    } catch (_: BoundedFileCopy.TooLargeException) {
        imageTooLarge()
    }

    private fun imageTooLarge(): Nothing =
        fail(ToolErrorCode.TOO_LARGE, "图片超过 ${MAX_AGENT_IMAGE_BYTES / (1024 * 1024)}MB，无法附给模型")

    private fun imageCacheDirectory(): File =
        context.externalCacheDir?.takeIf { it.isDirectory || it.mkdirs() } ?: context.cacheDir

    // ---- 按内容判定类型（没有扩展名或扩展名不认识时）----

    private inline fun FileKind.orSniff(header: () -> ByteArray?): FileKind =
        if (this != FileKind.UNKNOWN) this else header()?.let(FileSupport::sniffKind) ?: FileKind.UNKNOWN

    private fun localHeader(file: File): ByteArray? =
        if (!file.isFile || !file.canRead()) null else runCatching { file.inputStream().use { it.readHeader() } }.getOrNull()

    private fun contentHeader(uri: Uri): ByteArray? =
        runCatching { context.contentResolver.openInputStream(uri)?.use { it.readHeader() } }.getOrNull()

    private fun rootHeader(path: String): ByteArray? {
        if (!rootAvailable()) return null
        val result = root.execute(FileSupport.headerCommand(path), timeoutMillis = 10_000, maxOutputBytes = 8 * 1024)
        return if (result.ok) FileSupport.parseOdHex(result.stdout) else null
    }

    /** 读开头 [FileSupport.SNIFF_BYTES] 字节（电视版最低 API 28，不用 readNBytes）。 */
    private fun InputStream.readHeader(): ByteArray {
        val buffer = ByteArray(FileSupport.SNIFF_BYTES)
        var filled = 0
        while (filled < buffer.size) {
            val n = read(buffer, filled, buffer.size - filled)
            if (n < 0) break
            filled += n
        }
        return buffer.copyOf(filled)
    }

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
        const val IMAGE_COPY_TIMEOUT_MS = 15_000L
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
    private val sharedStorageHidden: () -> Boolean = SharedStorageAccess::hidden,
) : FileListBackend {

    override fun list(path: String?, hidden: Boolean, limit: Int, cursor: String?): FileListOutput {
        val dir = resolveLocalFile(context, path ?: "~")
        if (SharedStorageAccess.covers(dir) && sharedStorageHidden()) {
            // 目录本身读得了，但列出来只剩 Movo 自己的文件：有 Root 用 Root 列，没有就说清缺权限，不回一个假的空列表。
            if (rootAvailable()) return rootList(dir.absolutePath, hidden, limit, cursor)
            fail(ToolErrorCode.PERMISSION_REQUIRED, SharedStorageAccess.MESSAGE, hint = SharedStorageAccess.HINT)
        }
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
        if (rootAvailable()) return rootList(dir.absolutePath, hidden, limit, cursor)
        fail(ToolErrorCode.PERMISSION_REQUIRED, "没有权限读取该目录")
    }

    /**
     * App 读不了的目录用 Root 列：find -printf 输出名称、类型、大小、修改时间，按名字排序后只截这一页（多取一条判断有没有下一页）。
     * 目录不存在、find 不支持 -printf、执行失败都回明确的错误，不回空列表——空列表会被当成「目录是空的」。
     */
    private fun rootList(path: String, hidden: Boolean, limit: Int, cursor: String?): FileListOutput {
        val offset = cursor?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val result = root.execute(
            rootListCommand(path, hidden, fromLine = offset + 1, toLine = offset + limit + 1),
            timeoutMillis = 15_000,
            maxOutputBytes = 256 * 1024,
        )
        when {
            result.ok -> Unit
            result.errorCode == ROOT_DENIED -> fail(ToolErrorCode.ROOT_REQUIRED, "Root 授权被拒绝或已失效，列不了该目录")
            result.exitCode == ROOT_LIST_NOT_DIRECTORY -> fail(ToolErrorCode.NOT_FOUND, "目录不存在或不是目录：$path")
            result.exitCode == ROOT_LIST_PRINTF_UNSUPPORTED -> fail(
                ToolErrorCode.UNSUPPORTED,
                "这台设备的 find 不支持 -printf，无法列出该目录",
                detail = "find_printf_unsupported",
            )
            result.timedOut -> fail(ToolErrorCode.TIMEOUT, "列目录超时：$path")
            else -> fail(
                ToolErrorCode.SOURCE_UNAVAILABLE,
                "Root 列目录失败：$path",
                detail = result.errorCode.ifBlank { "exit=${result.exitCode}" },
            )
        }
        val rows = parseRootListing(result.stdout)
        val next = if (rows.size > limit) (offset + limit).toString() else null
        return FileListOutput(path, rows.take(limit), next)
    }
}

private const val ROOT_LIST_NOT_DIRECTORY = 21
private const val ROOT_LIST_PRINTF_UNSUPPORTED = 22

/**
 * Root 列目录命令：先确认是目录（退出码 21）、find 支持 -printf（退出码 22），
 * 再输出「名称\t类型\t大小\t修改时间（秒，带小数）」，按名字排序后用 sed 截出第 [fromLine]–[toLine] 行。
 */
internal fun rootListCommand(path: String, hidden: Boolean, fromLine: Int, toLine: Int): String {
    val dir = FileSupport.shellQuote(path)
    val hiddenFilter = if (hidden) "" else "! -name '.*' "
    return "[ -d $dir ] || exit $ROOT_LIST_NOT_DIRECTORY; " +
        "find $dir -maxdepth 0 -printf '' >/dev/null 2>&1 || exit $ROOT_LIST_PRINTF_UNSUPPORTED; " +
        "find $dir -mindepth 1 -maxdepth 1 $hiddenFilter-printf '%f\\t%y\\t%s\\t%T@\\n' 2>/dev/null | " +
        "sort | sed -n '$fromLine,${toLine}p'"
}

/** 解析 [rootListCommand] 的输出。名称在第一列且可能含制表符，所以从右边取后三列。 */
internal fun parseRootListing(stdout: String): List<DirEntry> = stdout.lineSequence().mapNotNull { line ->
    val fields = line.split('\t')
    if (fields.size < 4) return@mapNotNull null
    val name = fields.dropLast(3).joinToString("\t").ifEmpty { return@mapNotNull null }
    val (typeChar, size, mtime) = fields.takeLast(3)
    DirEntry(
        name = name,
        type = when (typeChar) {
            "d" -> "dir"
            "f" -> "file"
            "l" -> "link"
            else -> "other"
        },
        sizeBytes = size.toLongOrNull() ?: 0L,
        modifiedAtMillis = mtime.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 0L,
    )
}.toList()
