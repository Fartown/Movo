package io.github.fartown.movo.diagnostics

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

internal enum class DiagnosticLevel { INFO, WARN, ERROR }

internal data class DiagnosticContext(val run: String = "", val request: String = "")

internal data class DiagnosticEntry(
    val sequence: Long,
    val timeMillis: Long,
    val elapsedMillis: Long,
    val level: DiagnosticLevel,
    val category: String,
    val event: String,
    val context: DiagnosticContext,
    val details: String,
) {
    fun text(): String = "$timeMillis +${elapsedMillis}ms $level $category/$event " +
        "${context.run} ${context.request}\n$details"

    fun matches(query: String, warningsOnly: Boolean): Boolean =
        (!warningsOnly || level != DiagnosticLevel.INFO) && query.trim().split(Regex("\\s+")).all { term ->
            when {
                term.matches(Regex("R[0-9]+", RegexOption.IGNORE_CASE)) -> context.run.equals(term, ignoreCase = true)
                term.matches(Regex("Q[0-9]+", RegexOption.IGNORE_CASE)) -> context.request.equals(term, ignoreCase = true)
                else -> text().contains(term, ignoreCase = true)
            }
        }
}

/**
 * 进程内的诊断缓冲。主进程启动时由 [DiagnosticStore] 把上次保存的记录 [restore] 回来，
 * 之后每条新记录经 [MemoryDiagnostics.sink] 追加到本地文件；其他进程只留在内存里。
 */
internal class DiagnosticBuffer(
    val maxEntries: Int = 20_000,
    val maxBytes: Int = 8 * 1024 * 1024,
    private val maxEntryBytes: Int = 4_096,
) {
    data class Snapshot(val entries: List<DiagnosticEntry>, val bytes: Int, val dropped: Long)
    private data class Stored(val entry: DiagnosticEntry, val bytes: Int)
    private val entries = ArrayDeque<Stored>()
    private var bytes = 0
    private var dropped = 0L
    private var sequence = 0L

    init { require(maxEntries > 0 && maxBytes >= 512 && maxEntryBytes >= 512) }

    @Synchronized
    fun append(
        timeMillis: Long,
        elapsedMillis: Long,
        level: DiagnosticLevel,
        category: String,
        event: String,
        context: DiagnosticContext,
        details: String,
    ): DiagnosticEntry {
        val prefix = DiagnosticEntry(
            ++sequence, timeMillis, elapsedMillis, level, category.utf8Prefix(32), event.utf8Prefix(64),
            DiagnosticContext(context.run.utf8Prefix(24), context.request.utf8Prefix(24)), "",
        )
        val limit = minOf(maxBytes, maxEntryBytes)
        val entry = prefix.copy(details = details.utf8Prefix((limit - prefix.text().toByteArray().size).coerceAtLeast(0)))
        val size = entry.text().toByteArray(Charsets.UTF_8).size
        while (entries.isNotEmpty() && (entries.size >= maxEntries || bytes + size > maxBytes)) {
            bytes -= entries.removeFirst().bytes
            dropped++
        }
        entries.addLast(Stored(entry, size))
        bytes += size
        return entry
    }

    /** 把已保存的记录放回缓冲最前面，序号重新从 1 编起，之后的新记录接着编号。 */
    @Synchronized
    fun restore(saved: List<DiagnosticEntry>) {
        val live = entries.map { it.entry }
        entries.clear(); bytes = 0; sequence = 0
        (saved + live).forEach { entry ->
            append(entry.timeMillis, entry.elapsedMillis, entry.level, entry.category, entry.event, entry.context, entry.details)
        }
    }

    @Synchronized fun snapshot(): Snapshot = Snapshot(entries.map { it.entry }, bytes, dropped)
    @Synchronized fun clear() { entries.clear(); bytes = 0; dropped = 0 }
}

/** Truncate at a Unicode code point boundary, with an actual UTF-8 byte budget. */
private fun String.utf8Prefix(maxBytes: Int): String {
    if (length * 3 <= maxBytes) return this
    var index = 0
    var bytes = 0
    while (index < length) {
        val point = codePointAt(index)
        val count = when { point <= 0x7f -> 1; point <= 0x7ff -> 2; point <= 0xffff -> 3; else -> 4 }
        if (bytes + count > maxBytes) break
        bytes += count
        index += Character.charCount(point)
    }
    return substring(0, index)
}

internal object MemoryDiagnostics {
    val buffer = DiagnosticBuffer()
    @Volatile var elapsedClock: () -> Long = { System.nanoTime() / 1_000_000 }
    private val runSequence = AtomicLong()
    private val requestSequence = AtomicLong()
    private val localContext = ThreadLocal<DiagnosticContext>()
    @Volatile var environment: () -> Map<String, Any?> = { emptyMap() }
    /** 主进程把每条新记录追加到本地文件；为空时只留在内存里。 */
    @Volatile var sink: ((DiagnosticEntry) -> Unit)? = null
    /** 界面里的任务 ID（AgentRuntimeWire.runId）→ 诊断任务号，只记本进程内的任务。 */
    private val boundRuns = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 恢复保存的记录后，任务号和请求号接着上次的编号，避免与历史记录重名。 */
    fun continueNumbering(lastRun: Long, lastRequest: Long) {
        runSequence.updateAndGet { maxOf(it, lastRun) }
        requestSequence.updateAndGet { maxOf(it, lastRequest) }
    }

    /**
     * 把当前诊断任务与界面任务、会话关联起来，运行日志据此显示会话标题、从对话直达。
     * 两个 ID 都是本机内部标识，导出时会去掉。
     */
    fun bindRun(wireRunId: String, conversationId: String?) {
        val run = context().run.takeIf { it.isNotBlank() } ?: return
        boundRuns[wireRunId] = run
        record("runtime", "run.bound", fields = mapOf("wire_run" to wireRunId.take(96), "conversation" to conversationId?.take(96)))
    }

    fun boundRuns(): Map<String, String> = HashMap(boundRuns)

    /** 进行中任务最近一次有进展（收到模型数据、工具开始或结束）的时刻，按诊断任务号记。 */
    private val progress = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val openTools = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun markProgress(run: String) {
        if (run.isNotBlank() && progress.containsKey(run)) progress[run] = elapsedClock()
    }

    /**
     * 距上次有进展多久；跨越自动重试累计，工具执行期间不算“没有数据”。
     * 任务不在本进程里进行时为 null。
     */
    fun silenceMs(run: String): Long? {
        if ((openTools[run] ?: 0) > 0) return null
        return progress[run]?.let { elapsedClock() - it }
    }

    private fun trackProgress(run: String, event: String) {
        if (run.isBlank()) return
        when (event) {
            "run.started" -> progress[run] = elapsedClock()
            "run.ended" -> { progress.remove(run); openTools.remove(run) }
            "tool.started", "hosted_tool.started" -> { openTools.merge(run, 1, Int::plus); markProgress(run) }
            "tool.finished", "hosted_tool.finished" -> {
                openTools.computeIfPresent(run) { _, count -> (count - 1).takeIf { it > 0 } }
                markProgress(run)
            }
            "attempt.completed", "http.first_byte", "sse.first_event" -> markProgress(run)
        }
    }

    fun context(): DiagnosticContext = localContext.get() ?: DiagnosticContext()
    fun nextRequest(): String = "Q${requestSequence.incrementAndGet()}"
    fun environmentSnapshot(): Map<String, Any?> = runCatching { environment() }.getOrDefault(emptyMap())

    fun <T> withRun(block: () -> T): T {
        val previous = localContext.get()
        localContext.set(DiagnosticContext(run = "R${runSequence.incrementAndGet()}"))
        val started = elapsedClock()
        record("runtime", "run.started", fields = environmentSnapshot())
        try { return block() } finally {
            record("runtime", "run.ended", fields = mapOf("duration_ms" to elapsedClock() - started))
            if (previous == null) localContext.remove() else localContext.set(previous)
        }
    }

    // Call sites supply metadata only. Never pass prompts, messages, request/response bodies,
    // tool arguments/results, exception messages, URLs, or configuration/header collections.
    fun record(
        category: String,
        event: String,
        level: DiagnosticLevel = DiagnosticLevel.INFO,
        context: DiagnosticContext = context(),
        fields: Map<String, Any?> = emptyMap(),
    ) {
        val details = fields.entries.asSequence().take(48).joinToString("\n") { (key, value) ->
            "${key.take(48)}=${value?.toString()?.replace('\n', ' ')?.replace('\r', ' ')?.take(256) ?: "unknown"}"
        }
        val entry = buffer.append(System.currentTimeMillis(), elapsedClock(), level, category, event, context, details)
        trackProgress(context.run, event)
        sink?.let { runCatching { it(entry) } }
    }

    fun causes(failure: Throwable): String {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        val names = mutableListOf<String>()
        var cursor: Throwable? = failure
        while (cursor != null && names.size < 8 && seen.add(cursor)) {
            names += cursor.javaClass.simpleName
            cursor = cursor.cause
        }
        return names.joinToString(" > ")
    }

    /** Opaque server IDs and SSE types only, never arbitrary server text. */
    fun token(value: String?): String = value?.takeIf {
        it.length in 1..160 && it.all { char -> char.isLetterOrDigit() || char in "_.-" }
    } ?: "unknown"
}
