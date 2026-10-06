package io.github.fartown.movo.diagnostics.runlog

import io.github.fartown.movo.diagnostics.DiagnosticContext
import io.github.fartown.movo.diagnostics.DiagnosticLevel
import io.github.fartown.movo.diagnostics.MemoryDiagnostics
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 完整运行日志的入口（方案 §5）。只在主进程的手机版配置；没配置或用户关了开关时什么都不建。
 *
 * 只记执行中本来就产生的数据，原样记录：这里不调用截图、无障碍、前台、包名、存储空间或配置加载接口，
 * 也不做遮挡和推导。
 */
internal object RunLog {
    const val FORMAT_VERSION = 1
    private const val MAX_PENDING_RUNS = 16
    private const val MAX_PENDING_PER_RUN = 16

    @Volatile private var store: RunLogStore? = null
    @Volatile private var enabled: () -> Boolean = { false }
    @Volatile private var appVersion: String = ""
    private val sessions = ConcurrentHashMap<String, RunLogSession>()

    /** 界面任务号还没绑定运行号时（准备阶段）收到的用户操作，绑定后补写。 */
    private val pending = LinkedHashMap<String, MutableList<Pending>>()

    private class Pending(val at: Long, val elapsed: Long, val fields: Map<String, Any?>)

    fun configure(root: File, appVersion: String, enabled: () -> Boolean) {
        if (store != null) return
        install(
            RunLogStore(
                root = root,
                elapsedClock = { MemoryDiagnostics.elapsedClock() },
                reportFailure = { run, event, failure ->
                    MemoryDiagnostics.record(
                        "runtime", event, DiagnosticLevel.WARN, DiagnosticContext(run = run),
                        mapOf("causes" to MemoryDiagnostics.causes(failure)),
                    )
                },
            ),
            appVersion,
            enabled,
        )
    }

    /** 测试用：换一个写入器；传 null 关掉。 */
    internal fun install(store: RunLogStore?, appVersion: String, enabled: () -> Boolean) {
        this.store = store
        this.appVersion = appVersion
        this.enabled = enabled
        sessions.clear()
        synchronized(pending) { pending.clear() }
    }

    fun store(): RunLogStore? = store

    /**
     * 任务开始（在 withRun 写 run.started 之前）：建目录、写 run_start。返回目录名；不记时返回 null。
     * 开关每次任务开始时读一次。
     */
    fun open(run: String, start: Map<String, Any?>): String? = runCatching { openSession(run, start) }.getOrNull()

    private fun openSession(run: String, start: Map<String, Any?>): String? {
        val store = store ?: return null
        if (!runCatching(enabled).getOrDefault(false)) return null
        val session = store.open(run) ?: return null
        sessions[run] = session
        val fields = LinkedHashMap<String, Any?>()
        fields["v"] = FORMAT_VERSION
        fields["run"] = run
        fields.putAll(start)
        fields["app_version"] = appVersion
        session.record("run_start", fields, el = 0L, control = true)
        return session.dirName
    }

    /** 当前线程正在执行的任务（运行线程上有效）。 */
    fun current(): RunLogSession? {
        if (sessions.isEmpty()) return null
        return sessions[MemoryDiagnostics.context().run]
    }

    fun session(run: String): RunLogSession? = sessions[run]

    /** 用界面任务号找进行中的任务。 */
    fun forWireRun(wireRunId: String): RunLogSession? =
        MemoryDiagnostics.boundRun(wireRunId)?.let(sessions::get)

    /** 运行号与界面任务号、对话绑定：记下对话（删除对话时用），补写准备阶段的用户操作。 */
    fun bind(run: String, wireRunId: String, conversationId: String?) {
        runCatching {
            val session = sessions[run] ?: return
            store?.bind(session, conversationId)
            val waiting = synchronized(pending) { pending.remove(wireRunId) }.orEmpty()
            waiting.forEach { session.record("user", it.fields, it.at, it.elapsed - session.startElapsed) }
        }
    }

    /** 执行器看到的结束状态（completed / failed / cancelled、错误码、异常类名和原文）。 */
    fun end(status: String, code: String? = null, exception: String? = null, message: String? = null) {
        runCatching {
            val session = current() ?: return
            session.endFields = linkedMapOf<String, Any?>("status" to status).apply {
                code?.takeIf { it.isNotBlank() }?.let { put("code", it) }
                exception?.takeIf { it.isNotBlank() }?.let { put("exception", it) }
                message?.takeIf { it.isNotBlank() }?.let { put("message", RunLogLimits.cap(it)) }
            }
        }
    }

    /** 当前尝试真正发出去的请求体（服务商生成最终字符串之后）。没有任务或尝试时不记。 */
    fun request(attempt: String, body: String) {
        runCatching {
            val session = current() ?: return
            val store = store ?: return
            val send = session.sends.merge(attempt, 1, Int::plus) ?: 1
            val record = RunLogRecord(
                "request", session.wall(), session.elapsed(),
                linkedMapOf("attempt" to attempt, "send" to send),
            )
            store.submitRequest(session, record, body, "$attempt-$send.json.gz")
        }
    }

    /** withRun 收尾：在 run.ended 之后写 run_end，作为最后一行。 */
    fun close(run: String, thrown: Throwable?) {
        runCatching {
            val session = sessions.remove(run) ?: return
            val fields = session.endFields
                ?: thrown?.let { mapOf("status" to "failed", "exception" to MemoryDiagnostics.causes(it)) }
                ?: mapOf("status" to "not_provided")
            store?.close(session, RunLogRecord("run_end", session.wall(), session.elapsed(), fields, control = true))
        }
    }

    /**
     * 用户对某次任务的操作：停止、暂停、继续、补充。任务还在准备、没绑定运行号时先暂存。
     * 调用方紧接着要真正停止或暂停任务，这里任何异常都吞掉，不能挡住后面的操作。
     */
    fun user(wireRunId: String, fields: Map<String, Any?>) {
        runCatching {
            if (store == null || wireRunId.isBlank()) return
            val at = System.currentTimeMillis()
            val elapsed = MemoryDiagnostics.elapsedClock()
            val run = MemoryDiagnostics.boundRun(wireRunId)
            if (run != null) {
                val session = sessions[run] ?: return
                session.record("user", fields, at, elapsed - session.startElapsed)
                return
            }
            synchronized(pending) {
                val list = pending.getOrPut(wireRunId) { ArrayList() }
                if (list.size < MAX_PENDING_PER_RUN) list += Pending(at, elapsed, fields)
                while (pending.size > MAX_PENDING_RUNS) pending.remove(pending.keys.first())
            }
        }
    }

    fun stop(wireRunId: String, source: String) = user(wireRunId, linkedMapOf("kind" to "stop", "source" to source))

    fun pause(wireRunId: String) = user(wireRunId, linkedMapOf("kind" to "pause"))

    fun resume(wireRunId: String) = user(wireRunId, linkedMapOf("kind" to "resume"))

    fun supplement(wireRunId: String, text: String) =
        user(wireRunId, linkedMapOf("kind" to "supplement", "text" to RunLogLimits.cap(text)))

    /**
     * 现有运行日志的每条记录同步一份：带运行号的进对应任务（`meta`，去掉两个本机内部 ID），
     * 不带运行号的环境事件写进当时所有进行中的任务（`system`）。字段与现有记录一致。
     */
    fun mirror(
        category: String,
        event: String,
        level: DiagnosticLevel,
        context: DiagnosticContext,
        fields: List<Pair<String, String>>,
        at: Long,
        elapsed: Long,
    ) {
        if (sessions.isEmpty()) return
        if (context.run.isBlank()) {
            val data = LinkedHashMap<String, Any?>().apply { fields.forEach { (key, value) -> put(key, value) } }
            sessions.values.forEach { session ->
                session.record(
                    "system",
                    linkedMapOf("kind" to event, "category" to category, "level" to level.name, "data" to data),
                    at, elapsed - session.startElapsed,
                )
            }
            return
        }
        val session = sessions[context.run] ?: return
        val data = LinkedHashMap<String, Any?>()
        fields.forEach { (key, value) -> if (key != "wire_run" && key != "conversation") data[key] = value }
        val record = linkedMapOf<String, Any?>("category" to category, "event" to event, "level" to level.name)
        if (context.request.isNotBlank()) record["request"] = context.request
        record["data"] = data
        session.record("meta", record, at, elapsed - session.startElapsed)
    }

    /** 删除对话时一并删除它的任务日志。 */
    fun deleteConversation(conversationId: String) {
        runCatching { store?.deleteConversation(conversationId) }
    }
}
