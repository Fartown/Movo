package io.github.fartown.movo.agent.tools.file

import io.github.fartown.movo.agent.media.hasSupportedImageMagic
import io.github.fartown.movo.agent.tools.core.Sensitivity
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 文件领域共享的小工具：路径分区、敏感度判定、Root shell 引用、file_search 句柄编解码。
 *
 * 分区只按路径字符串判断（resolve 阶段拿不到 Context，无法做 canonical 规范化），
 * 因此 App 私有目录只有用相对路径或 `~` 时才算工作区；用绝对的 /data/user/... 路径会被当作工作区外。
 * 这是刻意的保守取舍：宁可多确认，不可漏确认（见定义清单 §29 安全）。
 */
internal object FileSupport {

    /** 路径分区：决定 file_write 的风险等级（见定义清单 §29）。 */
    internal enum class PathZone {
        /** Movo 工作区：相对路径、`~`、Root 工作区 /data/local/tmp/movo、App 私有目录。可逆写。 */
        WORKSPACE,
        /** 共享存储（/storage/emulated/0、/sdcard）：覆盖写算本机可逆；追加无法核实，按工作区外处理。 */
        SHARED,
        /** 工作区与共享存储以外：Root 才能写的系统位置，一律外发确认。 */
        EXTERNAL,
    }

    /** 仅按路径字符串分区。 */
    fun zoneOf(rawPath: String): PathZone {
        val path = rawPath.trim()
        if (path.isEmpty() || path == "~" || path.startsWith("~/") || !path.startsWith("/")) {
            return PathZone.WORKSPACE
        }
        return when {
            path == "/data/local/tmp/movo" || path.startsWith("/data/local/tmp/movo/") -> PathZone.WORKSPACE
            path.startsWith("/storage/emulated/0") || path.startsWith("/sdcard") -> PathZone.SHARED
            else -> PathZone.EXTERNAL
        }
    }

    /** 按路径判定敏感度：相册、私有数据目录、聊天目录等算个人数据。 */
    fun sensitivityOf(rawPath: String): Sensitivity {
        val path = rawPath.lowercase()
        val private = PRIVATE_MARKERS.any { path.contains(it) }
        return if (private) Sensitivity.PRIVATE else Sensitivity.NORMAL
    }

    /** Root shell 单引号转义。 */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    // ---- 按内容判定类型：没有扩展名的文件（微信/QQ 聊天图片缓存、日志、配置）靠文件开头的字节判断 ----

    /** 判定类型时读取的文件开头字节数。 */
    const val SNIFF_BYTES = 512

    /** 判定文本编码时读取的文件开头字节数。 */
    const val ENCODING_SNIFF_BYTES = 8 * 1024

    private val PDF_MAGIC = "%PDF-".toByteArray(Charsets.US_ASCII)
    private val GB18030: Charset = Charset.forName("GB18030")

    /**
     * 按文件开头的字节判定类型：图片魔数 → IMAGE；PDF 魔数 → PDF；带 BOM（UTF-8 / UTF-16）→ TEXT；
     * 不含 NUL 且是合法 UTF-8 或 GB18030（GBK 编码的配置、歌词等）→ TEXT（末尾被截断的多字节字符不算错）；其余 → UNKNOWN。
     * 空文件按文本处理。
     */
    fun sniffKind(header: ByteArray): FileKind {
        if (header.hasSupportedImageMagic()) return FileKind.IMAGE
        if (header.startsWith(PDF_MAGIC)) return FileKind.PDF
        if (bomOf(header) != null) return FileKind.TEXT
        if (header.any { it == 0.toByte() }) return FileKind.UNKNOWN
        return if (decodes(header, Charsets.UTF_8) || decodes(header, GB18030)) FileKind.TEXT else FileKind.UNKNOWN
    }

    /**
     * 扩展名或 mime 说是视频、音频、图片、PDF 的文件，开头其实是 UTF-8 文本（`.ts` 代码、`.svg`、`.m3u8` 播放列表）：
     * 按文本读。这里只认 BOM 或合法 UTF-8，不认 GB18030——二进制开头碰巧能按 GB18030 解出来的情况更多。
     */
    fun looksLikeUtf8Text(header: ByteArray): Boolean {
        if (header.isEmpty() || header.hasSupportedImageMagic() || header.startsWith(PDF_MAGIC)) return false
        if (bomOf(header) != null) return true
        return header.none { it == 0.toByte() } && decodes(header, Charsets.UTF_8)
    }

    /** 读文本用的编码：[name] 回给模型，[bomBytes] 是开头要跳过的 BOM 字节数。 */
    data class TextEncoding(val charset: Charset, val name: String, val bomBytes: Int = 0)

    /**
     * 按文件开头判定文本编码：BOM 优先；开头是合法 UTF-8 就按 UTF-8，否则试 GB18030（GBK、GB2312 都是它的子集）；
     * 都解不出时仍按 UTF-8 读，解不出的字节换成 �。以前只认 UTF-8，GBK 编码的文本整个读不了。
     */
    fun detectEncoding(header: ByteArray): TextEncoding {
        bomOf(header)?.let { return it }
        return when {
            decodes(header, Charsets.UTF_8) -> TextEncoding(Charsets.UTF_8, "utf-8")
            decodes(header, GB18030) -> TextEncoding(GB18030, "gb18030")
            else -> TextEncoding(Charsets.UTF_8, "utf-8")
        }
    }

    private fun bomOf(header: ByteArray): TextEncoding? = when {
        header.startsWith(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) -> TextEncoding(Charsets.UTF_8, "utf-8", 3)
        header.startsWith(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) -> TextEncoding(Charsets.UTF_16LE, "utf-16le", 2)
        header.startsWith(byteArrayOf(0xFE.toByte(), 0xFF.toByte())) -> TextEncoding(Charsets.UTF_16BE, "utf-16be", 2)
        else -> null
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    /** 整段都能按 [charset] 解出来（末尾被截断的多字节字符不算错）。 */
    private fun decodes(header: ByteArray, charset: Charset): Boolean {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val result = decoder.decode(ByteBuffer.wrap(header), CharBuffer.allocate(header.size + 1), false)
        return !result.isError
    }

    /** Root 读文件开头：toybox head + od 输出十六进制，避免二进制内容经 shell 输出被改写。 */
    fun headerCommand(path: String): String = "head -c $SNIFF_BYTES ${shellQuote(path)} | od -An -v -tx1"

    /** 解析 `od -An -v -tx1` 的输出（以空白分隔的两位十六进制）；不是两位十六进制的片段忽略。 */
    fun parseOdHex(output: String): ByteArray =
        output.split(Regex("\\s+"))
            .filter { it.length == 2 }
            .mapNotNull { it.toIntOrNull(16)?.toByte() }
            .toByteArray()

    // ---- file_search 句柄：不透明 token，file_read 可解回路径（定义清单 §0.5）----
    // 句柄用进程内随机密钥 HMAC 签名：模型无法伪造句柄去读 file_search 范围以外的任意路径（句柄是 file_read 唯一凭据）。
    // 句柄还带 size/mtime，file_read 打开文件时可比对现值，检测搜索后文件被替换（见 [FileHandle]）。
    private const val HANDLE_PREFIX = "fh2:"

    /** 进程生命周期内随机的句柄签名密钥；重启后旧句柄失效（句柄本就只在一次 run 内用）。 */
    private val handleKey: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    /** 解析出的句柄内容：路径 + 签发时的大小/修改时间（供 file_read 校验文件未被替换）。 */
    data class FileHandle(val path: String, val sizeBytes: Long, val mtimeMillis: Long)

    fun encodeHandle(path: String, sizeBytes: Long, mtimeMillis: Long): String {
        val payload = JSONObject().put("p", path).put("s", sizeBytes).put("m", mtimeMillis).toString()
        val body = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(Charsets.UTF_8))
        val mac = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(body))
        return "$HANDLE_PREFIX$body.$mac"
    }

    /** 验签并解析句柄；不是句柄、签名不符或格式错误一律返回 null（拒绝伪造/篡改）。 */
    fun decodeHandle(handle: String): FileHandle? {
        if (!handle.startsWith(HANDLE_PREFIX)) return null
        val rest = handle.removePrefix(HANDLE_PREFIX)
        val dot = rest.lastIndexOf('.')
        if (dot <= 0 || dot == rest.length - 1) return null
        val body = rest.substring(0, dot)
        val mac = runCatching { Base64.getUrlDecoder().decode(rest.substring(dot + 1)) }.getOrNull() ?: return null
        if (!MessageDigest.isEqual(mac, hmac(body))) return null // 常量时间比较
        return runCatching {
            val json = JSONObject(String(Base64.getUrlDecoder().decode(body), Charsets.UTF_8))
            FileHandle(json.getString("p"), json.getLong("s"), json.getLong("m"))
        }.getOrNull()
    }

    /** 解析句柄为路径；不是句柄或验签失败返回 null。 */
    fun decodeHandlePath(handle: String): String? = decodeHandle(handle)?.path

    private fun hmac(body: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(handleKey, "HmacSHA256")) }
            .doFinal(body.toByteArray(Charsets.UTF_8))

    private val PRIVATE_MARKERS = listOf(
        "/dcim/", "/pictures/", "/movies/", "/music/", "/recordings/",
        "/data/data/", "/data/user/", "/tencent/", "/wechat", "/mmchat", "/android/media/com.tencent",
    )
}

/**
 * 文本分页：按行定位、按字数截断，一边读一边丢，整个文件不进内存。
 *
 * 以前 file_read 先把整个文件读进内存，默认给 2000 行、不限字数：几百行的日志就超出给模型的 24000 字上限，
 * 整份结果被截成一段纯文本，续读位置也丢了；一行很长的文件（压缩过的 JSON、不换行的日志）后半段读不到。
 * 现在每次最多给 [maxCost]（按 JSON 转义后的长度算），停在行中间时给出这一行里的续读位置（[TextRead.nextColumn]）。
 */
internal object TextPager {
    /** 一次最多给模型的正文长度（按 JSON 转义后算），与重构前 read_file 每次 16000 字节相当。 */
    const val MAX_CHARS = 16_000

    /** 读完这一页后接着数总行数时，从文件开头算最多扫这么多字；超过就不报总行数。 */
    private const val TOTAL_SCAN_CHARS = 32L * 1024 * 1024

    /** 每读这么多字查一次取消（2 的幂减 1，当掩码用）。 */
    private const val CANCEL_CHECK_MASK = 64L * 1024 - 1

    private const val NEWLINE = '\n'.code
    private const val CARRIAGE_RETURN = '\r'.code

    /**
     * 从第 [offsetLine] 行（1 起）第 [column] 个字（0 起）开始，最多读 [limitLines] 行、[maxCost] 的正文。
     * `\r\n` 和单独的 `\r` 都算一个换行（与重构前 useLines 的分法一样），内容里不留 `\r`，行号、列都按去掉 `\r` 后算。
     * [countTotal] 为 true 时，没读到文件末尾也接着往后数总行数；为 false 时只有这一页读到了末尾才给总行数。
     * 每读 64K 字调一次 [checkCancelled]。[reader] 由调用方关闭。
     */
    fun read(
        reader: java.io.Reader,
        encoding: String,
        offsetLine: Int,
        column: Int,
        limitLines: Int,
        maxCost: Int = MAX_CHARS,
        countTotal: Boolean = true,
        checkCancelled: () -> Unit = {},
    ): TextRead {
        val source = CharSource(reader, checkCancelled)

        var line = 1
        while (line < offsetLine) {
            val c = source.take()
            if (c < 0) break
            if (c == NEWLINE) line++
        }
        if (line < offsetLine) {
            // 起始行已经超出文件末尾。
            return TextRead(
                "", encoding, totalLines = countLines(source), nextOffsetLine = null,
                startLine = offsetLine, endLine = offsetLine - 1,
            )
        }
        // 跳到这一行的第 column 个字；这一行没那么长时从下一行开头读。
        var col = 0
        while (col < column) {
            val c = source.take()
            if (c < 0) break
            if (c == NEWLINE) {
                line++
                col = 0
                break
            }
            col++
        }
        val startLine = line
        val startColumn = col

        val out = StringBuilder()
        var cost = 0
        var linesDone = 0
        var nextLine: Int? = null
        var nextColumn: Int? = null
        while (true) {
            val c = source.take()
            if (c < 0) break
            if (c == NEWLINE) {
                linesDone++
                line++
                col = 0
                if (linesDone >= limitLines) {
                    // 到了行数上限：后面还有字才给续读位置。
                    val peek = source.take()
                    if (peek >= 0) {
                        source.giveBack(peek)
                        nextLine = line
                    }
                    break
                }
                out.append('\n')
                cost += 2
                continue
            }
            val charCost = jsonCost(c)
            if (cost + charCost > maxCost) {
                source.giveBack(c)
                nextLine = line
                if (linesDone > 0) {
                    // 到了字数上限、这一页已有整行：停在上一行末尾，下一页从这一行开头读。
                    out.setLength(out.lastIndexOf("\n").coerceAtLeast(0))
                } else {
                    // 一行就超过上限：停在这一行中间，下一页从这个位置接着读（不把一个字的两半拆开）。
                    if (out.isNotEmpty() && out.last().isHighSurrogate()) {
                        source.giveBack(out.last().code)
                        out.setLength(out.length - 1)
                        col--
                    }
                    nextColumn = col
                }
                break
            }
            out.append(c.toChar())
            cost += charCost
            col++
        }
        // 读到了文件末尾：最后一行后面的换行不算内容。
        if (nextLine == null && out.isNotEmpty() && out.last() == '\n') out.setLength(out.length - 1)
        val endLine = when {
            nextLine == null && linesDone == 0 && out.isEmpty() -> startLine - 1
            nextColumn != null -> line
            nextLine != null -> line - 1
            source.lastChar == NEWLINE -> line - 1
            else -> line
        }.coerceAtLeast(startLine - 1)
        // 总行数：读到了末尾就有；没读到末尾时，让数才接着数（不留内容），文件太大就不数了。
        var total: Int? = null
        if (nextLine == null) {
            total = countLines(source)
        } else if (countTotal) {
            while (source.scanned < TOTAL_SCAN_CHARS) {
                if (source.take() < 0) {
                    total = countLines(source)
                    break
                }
            }
        }
        return TextRead(
            content = out.toString(),
            encoding = encoding,
            totalLines = total,
            nextOffsetLine = nextLine,
            nextColumn = nextColumn,
            startLine = startLine,
            startColumn = startColumn,
            endLine = endLine,
        )
    }

    /** 行数 = 换行数，最后一行没有换行结尾时再加一行。 */
    private fun countLines(source: CharSource): Int =
        (source.newlines + if (source.lastChar >= 0 && source.lastChar != NEWLINE) 1 else 0)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /**
     * 一个字在 JSON 里占多长，按手机上 Android 自带的 org.json 算：引号、反斜杠、`/` 和常见控制字符转义成两个字，
     * 其余控制字符和 U+2028、U+2029 转义成 \uXXXX。JVM 上的 org.json 不转义 `/`，单测里看不出来。
     */
    private fun jsonCost(c: Int): Int = when {
        c == '"'.code || c == '\\'.code || c == '/'.code -> 2
        c == '\t'.code || c == '\r'.code || c == '\b'.code || c == 0x0C -> 2
        c < 0x20 || c == 0x2028 || c == 0x2029 -> 6
        else -> 1
    }

    /**
     * 按字读 [reader]（自带缓冲）：`\r\n` 和单独的 `\r` 都换成一个 `\n`。
     * 记下读过多少字、多少个换行、最后一个字；停下时没用上的字放回（[giveBack]），接着数总行数时再读到。
     */
    private class CharSource(private val reader: java.io.Reader, private val checkCancelled: () -> Unit) {
        private val buffer = CharArray(8 * 1024)
        private var position = 0
        private var length = 0
        private var afterCarriageReturn = false
        private val pending = ArrayDeque<Int>()

        var newlines = 0L
            private set
        var lastChar = -1
            private set
        var scanned = 0L
            private set

        fun take(): Int {
            val c = if (pending.isNotEmpty()) pending.removeFirst() else next()
            if (c >= 0) {
                scanned++
                lastChar = c
                if (c == NEWLINE) newlines++
                if (scanned and CANCEL_CHECK_MASK == 0L) checkCancelled()
            }
            return c
        }

        fun giveBack(c: Int) {
            pending.addFirst(c)
            scanned--
            if (c == NEWLINE) newlines--
        }

        private fun next(): Int {
            while (true) {
                if (position >= length) {
                    val n = reader.read(buffer, 0, buffer.size)
                    if (n < 0) return -1
                    position = 0
                    length = n
                    continue
                }
                val c = buffer[position++].code
                if (afterCarriageReturn) {
                    afterCarriageReturn = false
                    if (c == NEWLINE) continue
                }
                if (c == CARRIAGE_RETURN) {
                    afterCarriageReturn = true
                    return NEWLINE
                }
                return c
            }
        }
    }
}
