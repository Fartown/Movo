package io.github.fartown.movo.agent.model

import io.github.fartown.movo.data.auth.ChatGptCredentials
import io.github.fartown.movo.data.auth.ChatGptOAuth
import io.github.fartown.movo.data.model.ProviderSourceTypes
import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject

/**
 * ChatGPT 订阅（Codex 后端）与标准 Responses API 的协议差异。
 *
 * 端点、请求头和请求体约束参考 pi 的 openai-codex-responses 实现：
 * 后端只接受 `store:false`，必须带 `instructions`，并依赖加密推理内容在工具回合之间回放。
 */
internal object ChatGptCodexRequest {
    private const val ENCRYPTED_REASONING = "reasoning.encrypted_content"
    private const val DEFAULT_INSTRUCTIONS = "You are a helpful assistant."

    fun isChatGpt(config: AgentModelClient.ModelConfig): Boolean =
        config.providerSourceType == ProviderSourceTypes.CHATGPT

    fun responsesUrl(baseUrl: String): String {
        val normalized = ProviderUrls.normalizeBaseUrl(baseUrl)
        return when {
            normalized.endsWith("/codex/responses") -> normalized
            normalized.endsWith("/codex") -> "$normalized/responses"
            else -> "$normalized/codex/responses"
        }
    }

    fun applyBody(body: JSONObject, sessionId: String) {
        body.put("store", false)
        body.put("stream", true)
        if (body.optString("instructions").isBlank()) body.put("instructions", DEFAULT_INSTRUCTIONS)
        val include = body.optJSONArray("include") ?: JSONArray()
        if ((0 until include.length()).none { include.optString(it) == ENCRYPTED_REASONING }) {
            include.put(ENCRYPTED_REASONING)
        }
        body.put("include", include)
        body.put("prompt_cache_key", sessionId)
    }

    fun applyHeaders(builder: Headers.Builder, credentials: ChatGptCredentials, sessionId: String) {
        builder.set("Authorization", "Bearer ${credentials.accessToken}")
        builder.set("chatgpt-account-id", credentials.accountId)
        builder.set("originator", ChatGptOAuth.ORIGINATOR)
        builder.set("OpenAI-Beta", "responses=experimental")
        builder.set("session-id", sessionId)
        builder.set("x-client-request-id", sessionId)
    }

    /** 订阅用量用尽时给出可读说明；其他错误交回通用分类。 */
    fun usageLimitFailure(status: Int, body: String): AgentModelFailure? {
        val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull() ?: return null
        val code = error.optString("code").ifBlank { error.optString("type") }
        if (!Regex("usage_limit_reached|usage_not_included|rate_limit_exceeded", RegexOption.IGNORE_CASE).containsMatchIn(code) &&
            status != 429
        ) return null
        val plan = error.optString("plan_type").takeIf { it.isNotBlank() }?.let { "（${it.lowercase()} 套餐）" }.orEmpty()
        val resetsAt = error.optLong("resets_at", 0L)
        val wait = if (resetsAt > 0) {
            val minutes = ((resetsAt * 1000 - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
            "约 $minutes 分钟后可再次使用。"
        } else ""
        return AgentModelFailure(
            code = "CHATGPT_USAGE_LIMIT",
            retryable = false,
            message = "ChatGPT 订阅用量已达上限$plan。$wait",
            providerCode = error.optString("code"),
            providerType = error.optString("type"),
        )
    }
}
