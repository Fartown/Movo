package io.github.mangi.eta.diagnostics

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

/** No Context, file, database or logcat sink: the buffer belongs to this process only. */
internal class DiagnosticBuffer(
    val maxEntries: Int = 2_000,
    val maxBytes: Int = 4 * 1024 * 1024,
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
    ) {
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
        buffer.append(System.currentTimeMillis(), elapsedClock(), level, category, event, context, details)
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
