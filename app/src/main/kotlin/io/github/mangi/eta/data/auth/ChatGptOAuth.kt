package io.github.mangi.eta.data.auth

import io.github.mangi.eta.agent.model.AgentHttpClient
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject

/**
 * ChatGPT 账号登录（OpenAI Codex OAuth，授权码 + PKCE）。
 *
 * 流程与常量移植自 pi 的 openai-codex OAuth 实现（MIT，见 THIRD_PARTY_NOTICES.md）：
 * 浏览器授权后回跳 http://localhost:1455/auth/callback，由本进程的回环服务接收授权码；
 * 回环不可用时允许用户粘贴回跳地址或授权码。
 */
internal object ChatGptOAuth {
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val REDIRECT_URI = "http://localhost:1455/auth/callback"
    private const val AUTHORIZE_URL = "https://auth.openai.com/oauth/authorize"
    private const val TOKEN_URL = "https://auth.openai.com/oauth/token"
    private const val SCOPE = "openid profile email offline_access"
    private const val CALLBACK_PORT = 1455
    private const val CALLBACK_PATH = "/auth/callback"
    private const val JWT_AUTH_CLAIM = "https://api.openai.com/auth"
    private const val JWT_PROFILE_CLAIM = "https://api.openai.com/profile"
    const val ORIGINATOR = "eta"

    private val random = SecureRandom()

    data class Pkce(val verifier: String, val challenge: String)

    data class AuthorizationRequest(val url: String, val state: String, val pkce: Pkce)

    data class AuthorizationInput(val code: String?, val state: String?)

    fun createPkce(): Pkce {
        val bytes = ByteArray(32).also(random::nextBytes)
        val verifier = base64Url(bytes)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        return Pkce(verifier, challenge)
    }

    fun createAuthorizationRequest(pkce: Pkce = createPkce(), state: String = randomHex(16)): AuthorizationRequest {
        val url = AUTHORIZE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", CLIENT_ID)
            .addQueryParameter("redirect_uri", REDIRECT_URI)
            .addQueryParameter("scope", SCOPE)
            .addQueryParameter("code_challenge", pkce.challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", state)
            .addQueryParameter("id_token_add_organizations", "true")
            .addQueryParameter("codex_cli_simplified_flow", "true")
            .addQueryParameter("originator", ORIGINATOR)
            .build()
            .toString()
        return AuthorizationRequest(url, state, pkce)
    }

    /** 接受完整回跳地址、`code=...&state=...` 查询串、`code#state` 或裸授权码。 */
    fun parseAuthorizationInput(input: String): AuthorizationInput {
        val value = input.trim()
        if (value.isEmpty()) return AuthorizationInput(null, null)
        val query = when {
            value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true) ->
                value.substringAfter('?', "").substringBefore('#')
            value.contains("code=") -> value.removePrefix("?")
            value.contains('#') -> return AuthorizationInput(
                value.substringBefore('#').ifBlank { null },
                value.substringAfter('#').ifBlank { null },
            )
            else -> return AuthorizationInput(value, null)
        }
        val params = parseQuery(query)
        return AuthorizationInput(params["code"]?.ifBlank { null }, params["state"]?.ifBlank { null })
    }

    fun exchangeAuthorizationCode(code: String, verifier: String): ChatGptCredentials =
        requestToken(
            FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", CLIENT_ID)
                .add("code", code)
                .add("code_verifier", verifier)
                .add("redirect_uri", REDIRECT_URI)
                .build(),
            operation = "登录",
            previous = null,
        )

    fun refresh(previous: ChatGptCredentials): ChatGptCredentials =
        requestToken(
            FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", previous.refreshToken)
                .add("client_id", CLIENT_ID)
                .build(),
            operation = "刷新",
            previous = previous,
        )

    private fun requestToken(body: FormBody, operation: String, previous: ChatGptCredentials?): ChatGptCredentials {
        val request = Request.Builder().url(TOKEN_URL).post(body).build()
        AgentHttpClient.client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                throw ChatGptAuthException(
                    "ChatGPT 令牌${operation}失败（HTTP ${response.code}）：${text.take(300)}",
                    requiresLogin = response.code in setOf(400, 401, 403),
                )
            }
            val json = JSONObject(text)
            val access = json.optString("access_token")
            val refresh = json.optString("refresh_token").ifBlank { previous?.refreshToken.orEmpty() }
            val expiresIn = json.optLong("expires_in", -1)
            if (access.isBlank() || refresh.isBlank() || expiresIn <= 0) {
                throw ChatGptAuthException("ChatGPT 令牌${operation}响应缺少字段", requiresLogin = false)
            }
            val accessClaims = decodeJwtPayload(access)
            val idClaims = json.optString("id_token").takeIf { it.isNotBlank() }?.let(::decodeJwtPayload)
            val accountId = accessClaims?.optJSONObject(JWT_AUTH_CLAIM)?.optString("chatgpt_account_id")
                ?.takeIf { it.isNotBlank() }
                ?: throw ChatGptAuthException("无法从 ChatGPT 令牌中读取账号 ID", requiresLogin = true)
            val email = idClaims?.optString("email")?.takeIf { it.isNotBlank() }
                ?: accessClaims?.optJSONObject(JWT_PROFILE_CLAIM)?.optString("email")?.takeIf { it.isNotBlank() }
                ?: previous?.email.orEmpty()
            val plan = (idClaims ?: accessClaims)?.optJSONObject(JWT_AUTH_CLAIM)?.optString("chatgpt_plan_type")
                ?.takeIf { it.isNotBlank() }
                ?: previous?.planType.orEmpty()
            return ChatGptCredentials(
                accessToken = access,
                refreshToken = refresh,
                expiresAtMillis = System.currentTimeMillis() + expiresIn * 1000,
                accountId = accountId,
                email = email,
                planType = plan,
            )
        }
    }

    fun decodeJwtPayload(token: String): JSONObject? = runCatching {
        val parts = token.split('.')
        if (parts.size != 3) return null
        JSONObject(String(Base64.getUrlDecoder().decode(parts[1].trimEnd('=')), Charsets.UTF_8))
    }.getOrNull()

    /**
     * 一次浏览器登录会话：在 127.0.0.1:1455 等待回跳；也可以用 [submitManualInput] 手动提交。
     * 端口被占用时 [callbackListening] 为 false，只能手动粘贴。
     */
    class LoginSession internal constructor(val authorization: AuthorizationRequest) {
        private val result = AtomicReference<Result<String>?>(null)
        private val done = CountDownLatch(1)
        private val server: ServerSocket? = runCatching {
            ServerSocket(CALLBACK_PORT, 4, InetAddress.getByName("127.0.0.1")).apply { reuseAddress = true }
        }.getOrNull()

        val callbackListening: Boolean get() = server != null

        init {
            server?.let { socket ->
                Thread({ serve(socket) }, "chatgpt-oauth-callback").apply { isDaemon = true }.start()
            }
        }

        fun submitManualInput(input: String) {
            val parsed = parseAuthorizationInput(input)
            when {
                parsed.state != null && parsed.state != authorization.state ->
                    finish(Result.failure(ChatGptAuthException("授权状态不匹配，请重新发起登录", requiresLogin = true)))
                parsed.code.isNullOrBlank() ->
                    finish(Result.failure(ChatGptAuthException("没有识别到授权码", requiresLogin = true)))
                else -> finish(Result.success(parsed.code))
            }
        }

        fun cancel() = finish(Result.failure(ChatGptAuthException("登录已取消", requiresLogin = true)))

        /** 阻塞等待授权码（回跳或手动提交）；在后台线程调用。 */
        fun awaitCode(timeoutMinutes: Long): String {
            try {
                if (!done.await(timeoutMinutes, TimeUnit.MINUTES)) {
                    throw ChatGptAuthException("等待浏览器登录超时（$timeoutMinutes 分钟）", requiresLogin = true)
                }
                return checkNotNull(result.get()).getOrThrow()
            } finally {
                closeServer()
            }
        }

        /** 用本会话的 PKCE verifier 换取凭证。 */
        fun exchange(code: String): ChatGptCredentials = exchangeAuthorizationCode(code, authorization.pkce.verifier)

        private fun finish(value: Result<String>) {
            if (result.compareAndSet(null, value)) {
                done.countDown()
                closeServer()
            }
        }

        private fun closeServer() {
            runCatching { server?.close() }
        }

        private fun serve(socket: ServerSocket) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: SocketException) {
                    return
                }
                client.use { connection ->
                    connection.soTimeout = 5_000
                    val requestLine = runCatching {
                        BufferedReader(InputStreamReader(connection.getInputStream(), Charsets.UTF_8)).readLine()
                    }.getOrNull().orEmpty()
                    val target = requestLine.split(' ').getOrNull(1).orEmpty()
                    val (status, message) = handleCallback(target)
                    val body = "<!doctype html><meta charset=utf-8><title>Eta</title>" +
                        "<body style=\"font-family:sans-serif;padding:32px\"><p>$message</p></body>"
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    runCatching {
                        connection.getOutputStream().apply {
                            write(
                                ("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
                                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
                            )
                            write(bytes)
                            flush()
                        }
                    }
                }
            }
        }

        private fun handleCallback(target: String): Pair<String, String> {
            if (target.substringBefore('?') != CALLBACK_PATH) return "404 Not Found" to "未找到回调地址。"
            val params = parseQuery(target.substringAfter('?', ""))
            params["error"]?.let { error ->
                finish(Result.failure(ChatGptAuthException("ChatGPT 授权失败：$error", requiresLogin = true)))
                return "400 Bad Request" to "授权失败：$error"
            }
            if (params["state"] != authorization.state) return "400 Bad Request" to "授权状态不匹配。"
            val code = params["code"]?.takeIf { it.isNotBlank() } ?: return "400 Bad Request" to "缺少授权码。"
            finish(Result.success(code))
            return "200 OK" to "已收到 ChatGPT 授权，可以关闭此页面返回 Eta。"
        }
    }

    fun startLogin(): LoginSession = LoginSession(createAuthorizationRequest())

    private fun parseQuery(query: String): Map<String, String> =
        query.split('&').mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val key = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
            val value = URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            key to value
        }.toMap()

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun randomHex(bytes: Int): String =
        ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }
}

internal class ChatGptAuthException(message: String, val requiresLogin: Boolean) : IllegalStateException(message)
