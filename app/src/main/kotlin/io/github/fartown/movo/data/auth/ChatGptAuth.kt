package io.github.fartown.movo.data.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class ChatGptCredentials(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
    val accountId: String,
    val email: String = "",
    val planType: String = "",
)

/** 界面只需要的登录摘要，不暴露令牌。 */
internal data class ChatGptAccountState(
    val loggedIn: Boolean,
    val email: String = "",
    val planType: String = "",
) {
    companion object {
        val LOGGED_OUT = ChatGptAccountState(loggedIn = false)
    }
}

/**
 * ChatGPT 订阅凭证的唯一持有者。
 *
 * 令牌不写进 Provider 配置、RemotePreferences 或跨进程请求；Runtime 在每次模型请求前从这里取，
 * 距离过期不足 [REFRESH_MARGIN_MS] 时先刷新并落盘。
 */
internal object ChatGptAuth {
    private const val REFRESH_MARGIN_MS = 5 * 60_000L
    private const val PREFERENCES_NAME = "movo_chatgpt_auth"
    private const val CREDENTIALS_KEY = "credentials_v1"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "movo_chatgpt_credentials_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val state = MutableStateFlow(ChatGptAccountState.LOGGED_OUT)

    @Volatile
    private var appContext: Context? = null

    val accountState: StateFlow<ChatGptAccountState> = state.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        state.value = load().toAccountState()
    }

    fun isLoggedIn(): Boolean = state.value.loggedIn

    fun save(credentials: ChatGptCredentials) = synchronized(lock) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(json.encodeToString(ChatGptCredentials.serializer(), credentials).toByteArray(Charsets.UTF_8))
        val payload = android.util.Base64.encodeToString(cipher.iv + encrypted, android.util.Base64.NO_WRAP)
        check(preferences().edit().putString(CREDENTIALS_KEY, payload).commit()) { "ChatGPT 登录信息保存失败" }
        state.value = credentials.toAccountState()
    }

    fun logout() = synchronized(lock) {
        check(preferences().edit().remove(CREDENTIALS_KEY).commit()) { "ChatGPT 登录信息删除失败" }
        state.value = ChatGptAccountState.LOGGED_OUT
    }

    /** 返回可用的凭证；需要时同步刷新。只在后台线程调用。 */
    fun requireCredentials(forceRefresh: Boolean = false): ChatGptCredentials = synchronized(lock) {
        val current = load() ?: throw ChatGptAuthException(
            "尚未登录 ChatGPT，请在模型服务商“ChatGPT”中登录。",
            requiresLogin = true,
        )
        if (!forceRefresh && current.expiresAtMillis - System.currentTimeMillis() > REFRESH_MARGIN_MS) {
            return current
        }
        val refreshed = try {
            ChatGptOAuth.refresh(current)
        } catch (failure: ChatGptAuthException) {
            if (failure.requiresLogin) {
                throw ChatGptAuthException("ChatGPT 登录已失效，请重新登录。（${failure.message}）", requiresLogin = true)
            }
            throw failure
        }
        save(refreshed)
        refreshed
    }

    private fun load(): ChatGptCredentials? = synchronized(lock) {
        val encoded = preferencesOrNull()?.getString(CREDENTIALS_KEY, null) ?: return null
        runCatching {
            val payload = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
            require(payload.size > GCM_IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, payload, 0, GCM_IV_BYTES))
            val plain = cipher.doFinal(payload, GCM_IV_BYTES, payload.size - GCM_IV_BYTES).toString(Charsets.UTF_8)
            json.decodeFromString(ChatGptCredentials.serializer(), plain)
        }.getOrNull()
    }

    private fun ChatGptCredentials?.toAccountState(): ChatGptAccountState =
        this?.let { ChatGptAccountState(loggedIn = true, email = it.email, planType = it.planType) }
            ?: ChatGptAccountState.LOGGED_OUT

    private fun preferencesOrNull() = appContext?.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun preferences() = checkNotNull(preferencesOrNull()) {
        "ChatGptAuth.init(context) must be called in Application.onCreate()"
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }
}
