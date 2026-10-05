package io.github.fartown.movo.agent.monitor

/**
 * 后台监听的事件管线（纯逻辑，按 Claude Code Monitor 实测语义，见 docs/research/agent-monitor.md 第 1 节）：
 * stdout 文本 → [MonitorLineFramer] 逐行分帧 → [MonitorBatcher] 200ms 固定窗口合批 → [MonitorRateLimiter] 令牌桶。
 * 不持有线程与计时器；时间由调用方传入，便于用虚拟时钟单测。
 */

/** 长度都按 UTF-16 码元计（Kotlin String.length），与 Claude 的 JS 字符串长度一致。 */
internal object MonitorLimits {
    const val MAX_LINE = 500
    const val MAX_BATCH = 3000
    const val MAX_PENDING = 1_048_576
    const val BATCH_WINDOW_MS = 200L
    const val LINE_TRUNCATED = "...(truncated)"
    const val BATCH_TRUNCATED = "\n...(truncated)"

    fun truncateLine(line: String): String =
        if (line.length > MAX_LINE) line.substring(0, MAX_LINE) + LINE_TRUNCATED else line

    fun truncateBatch(text: String): String =
        if (text.length > MAX_BATCH) text.substring(0, MAX_BATCH) + BATCH_TRUNCATED else text
}

/**
 * 逐行分帧：按 `\n` 切完整行，每行 trim，空行丢弃；没有换行的尾段留到后续数据或 [drain]。
 * 调用方负责把字节流按 UTF-8 流式解码成字符串后再喂进来（不要逐块 toString 拼接，会切坏多字节字符）。
 */
internal class MonitorLineFramer {
    private val pending = StringBuilder()

    fun accept(chunk: CharSequence): List<String> {
        if (chunk.isEmpty()) return emptyList()
        pending.append(chunk)
        val lines = mutableListOf<String>()
        var start = 0
        while (true) {
            val newline = pending.indexOf("\n", start)
            if (newline < 0) break
            pending.substring(start, newline).trim().takeIf { it.isNotEmpty() }?.let { lines += MonitorLimits.truncateLine(it) }
            start = newline + 1
        }
        if (start > 0) pending.delete(0, start)
        // 一直不换行的输出只保留尾部，避免无限增长。
        if (pending.length > MonitorLimits.MAX_PENDING) pending.delete(0, pending.length - MonitorLimits.MAX_PENDING)
        return lines
    }

    /** 进程退出时取出没有换行的尾段。 */
    fun drain(): String? {
        val rest = pending.toString().trim()
        pending.setLength(0)
        return rest.takeIf { it.isNotEmpty() }?.let(MonitorLimits::truncateLine)
    }
}

/**
 * 固定窗口合批：批次里第一条完整行到达时开窗，窗口长度 [MonitorLimits.BATCH_WINDOW_MS]；
 * 后续行只追加、不重设计时（不是 debounce）。到点用 `\n` 合并并截断。
 */
internal class MonitorBatcher {
    private val lines = mutableListOf<String>()
    private var deadline: Long? = null

    /** 加入一行；返回本批的到点时刻（首行时新开窗）。 */
    fun add(line: String, now: Long): Long {
        lines += line
        return deadline ?: (now + MonitorLimits.BATCH_WINDOW_MS).also { deadline = it }
    }

    val dueAt: Long? get() = deadline

    fun isEmpty(): Boolean = lines.isEmpty()

    /** 到点（或强制）时取出合并后的批次；未到点返回 null。 */
    fun flush(now: Long, force: Boolean = false): String? {
        val due = deadline ?: return null
        if (!force && now < due) return null
        val text = MonitorLimits.truncateBatch(lines.joinToString("\n"))
        lines.clear()
        deadline = null
        return text
    }
}

/**
 * 每个监听一个令牌桶：容量 10，每 2000ms 离散补 1 枚（补充基准按整段推进），每次合批交付消耗 1 枚。
 * 没有令牌时抑制该批并计数（不缓存重放）；之后有令牌时，交付结果带上之前被抑制的批数。
 * 自第一次抑制起持续超速超过 30s，下一次抑制时返回 [Decision.Stop]。
 *
 * 与 Claude 2.1.283 的一处有意差异：Claude 只在「交付时带出了抑制计数」才检查是否重置抑制起点，
 * 早先的一次抑制起点可以穿过很长的静默期保留下来，导致之后正常的一阵输出被立刻停掉。
 * 这里改为：任何一次交付只要距最近一次抑制已超过 6s，就重置起点（安静过就重新计时）。
 */
internal class MonitorRateLimiter(
    private val capacity: Int = 10,
    private val refillMs: Long = 2_000,
    private val floodStopMs: Long = 30_000,
    private val quietResetMs: Long = 6_000,
) {
    sealed interface Decision {
        /** 交付；[suppressedBefore] > 0 时先告诉模型「此前已抑制 N 批」。 */
        data class Deliver(val suppressedBefore: Int) : Decision
        data object Suppress : Decision
        /** 持续超速，停止该监听。 */
        data class Stop(val suppressed: Int, val spanMs: Long) : Decision
    }

    private var tokens = capacity
    private var refillBase: Long? = null
    private var suppressed = 0
    private var suppressStart: Long? = null
    private var lastSuppressAt: Long? = null

    fun tryDeliver(now: Long): Decision {
        refill(now)
        if (tokens > 0) {
            tokens--
            val before = suppressed
            suppressed = 0
            val last = lastSuppressAt
            if (last != null && now - last > quietResetMs) {
                suppressStart = null
                lastSuppressAt = null
            }
            return Decision.Deliver(before)
        }
        val start = suppressStart ?: now.also { suppressStart = it }
        if (now - start > floodStopMs) return Decision.Stop(suppressed + 1, now - start)
        suppressed++
        lastSuppressAt = now
        return Decision.Suppress
    }

    private fun refill(now: Long) {
        val base = refillBase ?: now.also { refillBase = it }
        val periods = (now - base) / refillMs
        if (periods > 0) {
            tokens = minOf(capacity.toLong(), tokens + periods).toInt()
            refillBase = base + periods * refillMs
        }
    }
}
