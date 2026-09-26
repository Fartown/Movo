package io.github.mangi.eta.agent.model

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 模块全局 OkHttp 客户端。
 *
 * 模型流与普通 HTTP 请求共享连接池，但独立设置读取等待与重试策略。
 */
internal object AgentHttpClient {

    private const val CONNECT_TIMEOUT_MS = 15_000L
    private const val READ_TIMEOUT_MS = 60_000L
    private const val WRITE_TIMEOUT_MS = 30_000L

    const val MODEL_READ_TIMEOUT_MS = 300_000L
    private const val MODEL_POOL_MAX_IDLE = 5
    private const val MODEL_POOL_KEEP_ALIVE_S = 30L

    val modelClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(MODEL_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // 模型请求不自动重试（避免同一轮被重复计费 / 执行），所以不能复用可能已被服务端或网络静默断开的空闲连接：
            // 空闲超过 30 秒的连接直接淘汰（服务端约 60 秒回收空闲 HTTP/2 连接，复用会在发出请求后才收到断开）。
            .connectionPool(okhttp3.ConnectionPool(MODEL_POOL_MAX_IDLE, MODEL_POOL_KEEP_ALIVE_S, TimeUnit.SECONDS))
            .retryOnConnectionFailure(false)
            .eventListenerFactory { call -> call.request().tag(ModelRequestTrace::class.java) ?: okhttp3.EventListener.NONE }
            .addInterceptor(ModelRequestTrace.bodyObserver)
            .build()
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }
}
