package io.github.fartown.movo.data.repository

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.fartown.movo.data.model.DoubaoSpeechCredentials
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Doubao speech credentials encrypted with Android Keystore (independent of LLM keys). */
internal class DoubaoSpeechSecretStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun load(): DoubaoSpeechCredentials {
        val encoded = preferences.getString(CREDENTIALS_KEY, null) ?: return DoubaoSpeechCredentials()
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.size > GCM_IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(GCM_TAG_BITS, payload, 0, GCM_IV_BYTES),
            )
            val plain = cipher.doFinal(payload, GCM_IV_BYTES, payload.size - GCM_IV_BYTES)
                .toString(Charsets.UTF_8)
            json.decodeFromString(StoredCredentials.serializer(), plain).toModel()
        }.getOrElse { DoubaoSpeechCredentials() }
    }

    @Synchronized
    fun save(credentials: DoubaoSpeechCredentials) {
        val normalized = credentials.normalized()
        if (!normalized.hasUsableAuth()) {
            clear()
            return
        }
        val plain = json.encodeToString(
            StoredCredentials.serializer(),
            StoredCredentials.from(normalized),
        )
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + encrypted
        check(
            preferences.edit()
                .putString(CREDENTIALS_KEY, Base64.encodeToString(payload, Base64.NO_WRAP))
                .commit(),
        ) { "豆包语音凭证保存失败" }
    }

    @Synchronized
    fun clear() {
        check(preferences.edit().remove(CREDENTIALS_KEY).commit()) { "豆包语音凭证删除失败" }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    @Serializable
    private data class StoredCredentials(
        val appKey: String = "",
        val accessKey: String = "",
        val apiKey: String = "",
        val resourceId: String = DoubaoSpeechCredentials.DEFAULT_RESOURCE_ID,
        val endpoint: String = DoubaoSpeechCredentials.DEFAULT_ENDPOINT,
    ) {
        fun toModel() = DoubaoSpeechCredentials(
            appKey = appKey,
            accessKey = accessKey,
            apiKey = apiKey,
            resourceId = resourceId,
            endpoint = endpoint,
        )

        companion object {
            fun from(model: DoubaoSpeechCredentials) = StoredCredentials(
                appKey = model.appKey,
                accessKey = model.accessKey,
                apiKey = model.apiKey,
                resourceId = model.resourceId,
                endpoint = model.endpoint,
            )
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "movo_doubao_speech_secrets"
        const val CREDENTIALS_KEY = "credentials_v1"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "movo_doubao_speech_credentials_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
