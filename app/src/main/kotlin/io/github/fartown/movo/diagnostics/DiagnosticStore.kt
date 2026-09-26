package io.github.fartown.movo.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.concurrent.Executors

/**
 * 运行日志的本地保存：最近 [maxRuns] 个任务的全部记录，加上 [looseRetentionMs] 内任务之外的记录。
 *
 * 只写 App 私有目录，内容与内存缓冲完全相同（都是元数据，见 [MemoryDiagnostics.record] 的约束）。
 * 新记录逐条追加，任务结束时按保留规则重写一次文件；进程被杀时最多丢失尚未落盘的最后几条。
 */
internal class DiagnosticStore(
    private val file: File,
    private val maxRuns: Int = MAX_RUNS,
    private val looseRetentionMs: Long = LOOSE_RETENTION_MS,
) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "movo-diagnostics").apply { isDaemon = true }
    }
    private var writer: Writer? = null

    /** 读出保存的记录；上次没有结束的任务补一条 `run.interrupted`，再按保留规则整理。 */
    fun load(now: Long): List<DiagnosticEntry> {
        val saved = runCatching {
            if (file.exists()) file.readLines(Charsets.UTF_8).mapNotNull(::decode) else emptyList()
        }.getOrDefault(emptyList())
        val kept = DiagnosticRetention.retain(DiagnosticRetention.closeInterrupted(saved), now, maxRuns, looseRetentionMs)
        executor.execute { runCatching { rewrite(kept) } }
        return kept
    }

    /** [snapshot] 在任务结束时用来重写文件，只取序号不晚于这条记录的部分，避免与排队中的追加重复。 */
    fun append(entry: DiagnosticEntry, snapshot: () -> List<DiagnosticEntry>) {
        executor.execute {
            runCatching {
                val out = writer ?: open().also { writer = it }
                out.write(encode(entry))
                out.write("\n")
                out.flush()
                if (entry.event == "run.ended") {
                    val entries = snapshot().filter { it.sequence <= entry.sequence }
                    rewrite(DiagnosticRetention.retain(entries, entry.timeMillis, maxRuns, looseRetentionMs))
                }
            }
        }
    }

    private fun open(): Writer {
        file.parentFile?.mkdirs()
        return OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8).buffered()
    }

    private fun rewrite(entries: List<DiagnosticEntry>) {
        writer?.close()
        writer = null
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        OutputStreamWriter(FileOutputStream(temp), Charsets.UTF_8).buffered().use { out ->
            entries.forEach { out.write(encode(it)); out.write("\n") }
        }
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    companion object {
        const val MAX_RUNS = 20
        const val LOOSE_RETENTION_MS = 7L * 24 * 60 * 60 * 1000

        fun encode(entry: DiagnosticEntry): String = listOf(
            entry.timeMillis.toString(), entry.elapsedMillis.toString(), entry.level.name,
            entry.category, entry.event, entry.context.run, entry.context.request, entry.details,
        ).joinToString("\t") { it.escape() }

        fun decode(line: String): DiagnosticEntry? {
            val parts = line.split('\t')
            if (parts.size != 8) return null
            val values = parts.map { it.unescape() }
            return DiagnosticEntry(
                sequence = 0,
                timeMillis = values[0].toLongOrNull() ?: return null,
                elapsedMillis = values[1].toLongOrNull() ?: return null,
                level = runCatching { DiagnosticLevel.valueOf(values[2]) }.getOrNull() ?: return null,
                category = values[3],
                event = values[4],
                context = DiagnosticContext(values[5], values[6]),
                details = values[7],
            )
        }

        private fun String.escape(): String = buildString(length) {
            for (char in this@escape) when (char) {
                '\\' -> append("\\\\")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(char)
            }
        }

        private fun String.unescape(): String = buildString(length) {
            var index = 0
            while (index < this@unescape.length) {
                val char = this@unescape[index]
                if (char == '\\' && index + 1 < this@unescape.length) {
                    when (this@unescape[index + 1]) {
                        't' -> append('\t')
                        'n' -> append('\n')
                        'r' -> append('\r')
                        else -> append(this@unescape[index + 1])
                    }
                    index += 2
                } else {
                    append(char)
                    index++
                }
            }
        }
    }
}

/** 保留规则；纯函数，便于单元测试。 */
internal object DiagnosticRetention {
    /** 保留最近 [maxRuns] 个任务（按开始时间）的全部记录，以及 [looseRetentionMs] 内任务之外的记录。 */
    fun retain(entries: List<DiagnosticEntry>, now: Long, maxRuns: Int, looseRetentionMs: Long): List<DiagnosticEntry> {
        val starts = LinkedHashMap<String, Long>()
        entries.forEach { entry ->
            val run = entry.context.run
            if (run.isNotBlank() && run !in starts) starts[run] = entry.timeMillis
        }
        val kept = starts.entries.sortedByDescending { it.value }.take(maxRuns).mapTo(HashSet()) { it.key }
        return entries.filter { entry ->
            if (entry.context.run.isNotBlank()) entry.context.run in kept else now - entry.timeMillis <= looseRetentionMs
        }
    }

    /** 上一个进程里没有结束的任务：进程已经不在了，补一条中断记录，界面才不会一直显示“进行中”。 */
    fun closeInterrupted(entries: List<DiagnosticEntry>): List<DiagnosticEntry> {
        val closed = entries.filter { it.event == "run.ended" || it.event == "run.interrupted" }.mapTo(HashSet()) { it.context.run }
        val open = entries.filter { it.context.run.isNotBlank() && it.context.run !in closed }.groupBy { it.context.run }
        if (open.isEmpty()) return entries
        return entries + open.values.map { runEntries ->
            val last = runEntries.last()
            last.copy(
                level = DiagnosticLevel.WARN,
                category = "runtime",
                event = "run.interrupted",
                context = DiagnosticContext(run = last.context.run),
                details = "",
            )
        }
    }

    fun lastNumber(entries: List<DiagnosticEntry>, prefix: Char, id: (DiagnosticEntry) -> String): Long =
        entries.maxOfOrNull { entry -> id(entry).takeIf { it.firstOrNull() == prefix }?.drop(1)?.toLongOrNull() ?: 0L } ?: 0L
}
