package io.github.mangi.eta.agent.model

import io.github.mangi.eta.diagnostics.DiagnosticContext
import io.github.mangi.eta.diagnostics.DiagnosticLevel
import io.github.mangi.eta.diagnostics.MemoryDiagnostics
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

/** One attempt, shared by retry, HTTP and SSE. Contains metadata only, never a request/body. */
internal class ModelRequestTrace(
    request: ProviderRequest,
    provider: String,
    round: Int? = null,
    attempt: Int? = null,
) : EventListener(), AutoCloseable {
    val context = MemoryDiagnostics.context().copy(request = MemoryDiagnostics.nextRequest())
    private val started = MemoryDiagnostics.elapsedClock()
    private val metadata = mapOf(
        "provider" to provider, "purpose" to request.purpose.name,
        "round" to round, "attempt" to attempt, "messages" to request.messages.length(),
        "read_timeout_ms" to AgentHttpClient.MODEL_READ_TIMEOUT_MS,
    )
    private val finished = AtomicBoolean(false)
    private val received = AtomicLong()
    private val frames = AtomicLong()
    @Volatile private var stage = "preparing"
    @Volatile private var lastByte: Long? = null
    @Volatile private var lastFrame: Long? = null
    @Volatile private var lastEvent = "none"
    @Volatile private var status: Int? = null
    @Volatile private var requestId = "unknown"
    @Volatile private var retryAfter = "unknown"

    init {
        active[context.request] = this
        record("attempt.started")
    }

    fun snapshot(): Map<String, Any?> = state() + MemoryDiagnostics.environmentSnapshot()

    private fun state(): Map<String, Any?> {
        val now = MemoryDiagnostics.elapsedClock()
        return metadata + mapOf(
            "stage" to stage, "duration_ms" to now - started,
            "http_status" to status, "server_request_id" to requestId, "retry_after" to retryAfter,
            "received_bytes" to received.get(), "last_byte_ago_ms" to lastByte?.let { now - it },
            "sse_events" to frames.get(), "last_sse_event" to lastEvent,
            "last_sse_ago_ms" to lastFrame?.let { now - it },
        )
    }

    fun record(event: String, level: DiagnosticLevel = DiagnosticLevel.INFO, fields: Map<String, Any?> = emptyMap()) {
        // Keep event-specific diagnosis before the bounded field budget is consumed by environment metadata.
        // 设备状态只在开始、结束和出错时附带；中间的网络阶段不重复记录，变化由系统事件单独体现。
        val withEnvironment = event.startsWith("attempt.") || level != DiagnosticLevel.INFO
        MemoryDiagnostics.record("model", event, level, context, fields + if (withEnvironment) snapshot() else state())
    }

    fun success() {
        if (finished.compareAndSet(false, true)) record("attempt.completed")
    }

    fun failed(failure: Throwable, cancelled: Boolean = false, callbackFailed: Boolean = false) {
        if (!finished.compareAndSet(false, true)) return
        val classified = (failure as? Exception)?.let(AgentModelFailure::transport)
        record(if (cancelled) "attempt.cancelled" else "attempt.failed",
            if (cancelled) DiagnosticLevel.INFO else DiagnosticLevel.ERROR,
            mapOf("code" to (classified?.code ?: "UNCLASSIFIED"),
                "retryable" to (classified?.retryable == true && !callbackFailed),
                "provider_error_code" to MemoryDiagnostics.token(classified?.providerCode),
                "provider_error_type" to MemoryDiagnostics.token(classified?.providerType),
                "callback_failed" to callbackFailed, "causes" to MemoryDiagnostics.causes(failure)))
    }

    override fun close() { active.remove(context.request, this) }

    fun sseFrame(name: String, done: Boolean) {
        lastFrame = MemoryDiagnostics.elapsedClock()
        lastEvent = if (done) "DONE" else MemoryDiagnostics.token(name)
        if (frames.incrementAndGet() == 1L) record("sse.first_event")
        if (done) record("sse.done_marker")
    }

    fun sseType(type: String) {
        lastEvent = MemoryDiagnostics.token(type)
        if (type in setOf("response.completed", "response.incomplete", "response.failed", "message_stop", "error")) {
            record("sse.terminal", fields = mapOf("sse_type" to lastEvent))
        }
    }

    fun sseEof() { record("sse.eof") }

    private fun phase(name: String) { stage = name; record("http.$name") }
    override fun callStart(call: Call) = phase("starting")
    override fun dnsStart(call: Call, domainName: String) = phase("dns")
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) { record("http.dns_end") }
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) = phase("connecting")
    override fun secureConnectStart(call: Call) = phase("tls")
    override fun secureConnectEnd(call: Call, handshake: Handshake?) { record("http.tls_end") }
    override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) {
        record("http.connect_failed", DiagnosticLevel.WARN, mapOf("causes" to MemoryDiagnostics.causes(ioe)))
    }
    override fun requestHeadersStart(call: Call) = phase("sending_headers")
    override fun requestBodyStart(call: Call) = phase("sending_body")
    override fun requestBodyEnd(call: Call, byteCount: Long) {
        stage = "awaiting_headers"
        record("http.request_sent", fields = mapOf("sent_bytes" to byteCount))
    }
    override fun responseHeadersEnd(call: Call, response: Response) {
        status = response.code
        requestId = listOf("x-request-id", "request-id", "x-tt-logid", "x-amzn-requestid")
            .firstNotNullOfOrNull { response.header(it) }?.let(MemoryDiagnostics::token) ?: "unknown"
        retryAfter = response.header("Retry-After")?.take(80)?.replace('\n', ' ') ?: "unknown"
        stage = "awaiting_body"
        record("http.response_headers")
    }
    override fun callFailed(call: Call, ioe: IOException) {
        record(if (call.isCanceled()) "http.cancelled" else "http.failed",
            if (call.isCanceled()) DiagnosticLevel.INFO else DiagnosticLevel.WARN,
            mapOf("causes" to MemoryDiagnostics.causes(ioe)))
    }
    override fun callEnd(call: Call) { record("http.closed") }

    private fun bytesRead(count: Long) {
        if (count <= 0) return
        val first = received.getAndAdd(count) == 0L
        lastByte = MemoryDiagnostics.elapsedClock()
        MemoryDiagnostics.markProgress(context.run)
        stage = "reading_body"
        if (first) record("http.first_byte")
    }

    companion object {
        private val local = ThreadLocal<ModelRequestTrace>()
        private val active = ConcurrentHashMap<String, ModelRequestTrace>()
        fun current(): ModelRequestTrace? = local.get()
        fun forRequest(request: ProviderRequest, provider: String): ModelRequestTrace = current() ?: ModelRequestTrace(request, provider)
        fun bind(trace: ModelRequestTrace): AutoCloseable {
            val previous = local.get()
            local.set(trace)
            return AutoCloseable { if (previous == null) local.remove() else local.set(previous) }
        }
        fun activeSnapshots(): List<Pair<DiagnosticContext, Map<String, Any?>>> =
            active.values.sortedBy { it.started }.map { it.context to it.snapshot() }

        val bodyObserver = Interceptor { chain ->
            val response = chain.proceed(chain.request())
            val trace = chain.request().tag(ModelRequestTrace::class.java) ?: return@Interceptor response
            val body = response.body
            val source = object : ForwardingSource(body.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long = super.read(sink, byteCount).also(trace::bytesRead)
            }.buffer()
            response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source(): BufferedSource = source
            }).build()
        }
    }
}
