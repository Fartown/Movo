package io.github.fartown.movo.agent.terminal

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID

/**
 * 持久 shell 会话的状态行协议：每条命令结束后用一个随机 marker 的 printf
 * 输出退出码与执行后的 PWD。宿主侧据此切分输出、跟踪 cwd；marker 含随机 UUID，
 * 正常命令输出不会与之混淆。
 */
internal object SessionStatusProtocol {

    fun newMarker(): String = "__MOVO_STATUS_${UUID.randomUUID().toString().replace("-", "")}"

    /**
     * 单逻辑行协议：命令经 eval 执行，状态 printf 与命令在同一行，由 shell 在命令退出后自己输出。
     * 状态行不作为独立行进入 stdin——交互式命令（read、REPL 等）读 stdin 时不会吃掉标记，
     * 会话运行期间写入的用户输入也完整留给前台进程。
     */
    fun commandLine(marker: String, command: String): String =
        "eval ${shellQuote(command)}; movo_ec=\$?; printf '\\n$marker:%s:%s\\n' \"\$movo_ec\" \"\$PWD\""

    fun isStatusLine(line: String, marker: String): Boolean = line.startsWith("$marker:")

    /** 解析状态行；cwd 为空（空行段）时返回 null，由调用方回退到会话当前 cwd。 */
    fun parseStatusLine(line: String, marker: String): Status? {
        if (!isStatusLine(line, marker)) return null
        val status = line.removePrefix("$marker:")
        val separator = status.indexOf(':')
        if (separator <= 0) return Status(exitCode = -1, cwd = null)
        return Status(
            exitCode = status.take(separator).toIntOrNull() ?: -1,
            cwd = status.drop(separator + 1).ifBlank { null },
        )
    }

    data class Status(val exitCode: Int, val cwd: String?)
}

/**
 * 有界输出收集器：读取线程持续排空管道，超过上限后不再存，只留最后 [tailBytes] 字节（[overflowTail]）。
 * 会话靠输出末尾的状态行判断命令结束：单条命令输出超过上限时，状态行就在这段尾巴里。
 */
internal class ByteArrayOutputCollector(private val tailBytes: Int = DEFAULT_TAIL_BYTES) {
    private val output = ByteArrayOutputStream()
    private val tail = ByteArray(tailBytes)
    private var tailStart = 0
    private var tailLength = 0
    private var dropped = 0L

    fun readFrom(input: java.io.InputStream, maxBytes: Int = Int.MAX_VALUE) {
        runCatching {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                synchronized(this) {
                    val allowed = (maxBytes - output.size()).coerceAtLeast(0).coerceAtMost(read)
                    if (allowed > 0) output.write(buffer, 0, allowed)
                    if (read > allowed) keepTail(buffer, allowed, read - allowed)
                }
            }
        }.onFailure { throwable ->
            if (throwable !is IOException) throw throwable
        }
    }

    private fun keepTail(buffer: ByteArray, from: Int, count: Int) {
        dropped += count
        for (i in from until from + count) {
            tail[(tailStart + tailLength) % tailBytes] = buffer[i]
            if (tailLength < tailBytes) tailLength++ else tailStart = (tailStart + 1) % tailBytes
        }
    }

    fun bytes(): ByteArray = synchronized(this) { output.toByteArray() }

    fun text(): String = bytes().decodeToString()

    /** [overflowTail] 最多保留的字节数。 */
    val tailCapacity: Int get() = tailBytes

    /** 超过上限后没存下的字节数。 */
    fun droppedBytes(): Long = synchronized(this) { dropped }

    /** 超过上限后最后收到的那段（最多 [tailBytes] 字节）；没超过时为空。 */
    fun overflowTail(): String = synchronized(this) {
        ByteArray(tailLength) { tail[(tailStart + it) % tailBytes] }.decodeToString()
    }

    fun clear() {
        synchronized(this) {
            output.reset()
            tailStart = 0
            tailLength = 0
            dropped = 0
        }
    }

    private companion object {
        const val DEFAULT_TAIL_BYTES = 16 * 1024
    }
}
