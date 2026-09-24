package io.github.mangi.eta.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.mangi.eta.data.model.DoubaoSpeechCredentials
import io.github.mangi.eta.data.model.VoiceWakeSettings
import io.github.mangi.eta.data.model.WakeListenScope
import io.github.mangi.eta.data.model.WakePhraseRules
import io.github.mangi.eta.data.model.WakeSensitivity
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

internal object VoiceSettingsRepository {
    private const val STORE_NAME = "eta_voice_settings"

    private val WAKE_ENABLED = booleanPreferencesKey("wake_enabled")
    private val WAKE_PHRASE = stringPreferencesKey("wake_phrase")
    private val WAKE_SENSITIVITY = stringPreferencesKey("wake_sensitivity")
    private val WAKE_LISTEN_SCOPE = stringPreferencesKey("wake_listen_scope")

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = STORE_NAME)

    @Volatile
    private lateinit var dataStore: DataStore<Preferences>

    @Volatile
    private var secretStore: DoubaoSpeechSecretStore? = null

    fun init(context: Context) {
        if (!::dataStore.isInitialized) {
            dataStore = context.applicationContext.dataStore
        }
        if (secretStore == null) {
            secretStore = DoubaoSpeechSecretStore(context.applicationContext)
        }
    }

    fun wakeSettingsFlow(): Flow<VoiceWakeSettings> {
        ensureInitialized()
        return dataStore.data
            .catch { cause ->
                if (cause is IOException) emit(emptyPreferences()) else throw cause
            }
            .map { it.toWakeSettings() }
    }

    suspend fun wakeSettings(): VoiceWakeSettings = wakeSettingsFlow().first()

    suspend fun setWakeEnabled(enabled: Boolean) {
        ensureInitialized()
        dataStore.edit { it[WAKE_ENABLED] = enabled }
    }

    suspend fun setWakePhrase(phrase: String): String {
        ensureInitialized()
        val effective = WakePhraseRules.normalizeOrDefault(phrase)
        dataStore.edit { prefs ->
            if (effective == WakePhraseRules.DEFAULT && phrase.trim().isEmpty()) {
                prefs.remove(WAKE_PHRASE)
            } else {
                prefs[WAKE_PHRASE] = effective
            }
        }
        return effective
    }

    suspend fun restoreDefaultWakePhrase(): String {
        ensureInitialized()
        dataStore.edit { it.remove(WAKE_PHRASE) }
        return WakePhraseRules.DEFAULT
    }

    suspend fun setWakeSensitivity(sensitivity: WakeSensitivity) {
        ensureInitialized()
        dataStore.edit { it[WAKE_SENSITIVITY] = sensitivity.name }
    }

    suspend fun setWakeListenScope(scope: WakeListenScope) {
        ensureInitialized()
        dataStore.edit { it[WAKE_LISTEN_SCOPE] = scope.name }
    }

    fun loadDoubaoCredentials(): DoubaoSpeechCredentials =
        requireSecretStore().load()

    fun saveDoubaoCredentials(credentials: DoubaoSpeechCredentials) {
        requireSecretStore().save(credentials)
    }

    fun clearDoubaoCredentials() {
        requireSecretStore().clear()
    }

    private fun Preferences.toWakeSettings(): VoiceWakeSettings {
        val phrase = this[WAKE_PHRASE]
        return VoiceWakeSettings(
            wakeEnabled = this[WAKE_ENABLED] ?: false,
            wakePhrase = WakePhraseRules.normalizeOrDefault(phrase),
            sensitivity = this[WAKE_SENSITIVITY]?.let { raw ->
                runCatching { WakeSensitivity.valueOf(raw) }.getOrNull()
            } ?: WakeSensitivity.Medium,
            listenScope = this[WAKE_LISTEN_SCOPE]?.let { raw ->
                runCatching { WakeListenScope.valueOf(raw) }.getOrNull()
            } ?: WakeListenScope.AppOpen,
        )
    }

    private fun ensureInitialized() {
        check(::dataStore.isInitialized) { "VoiceSettingsRepository not initialized" }
    }

    private fun requireSecretStore(): DoubaoSpeechSecretStore =
        checkNotNull(secretStore) { "VoiceSettingsRepository not initialized" }
}
