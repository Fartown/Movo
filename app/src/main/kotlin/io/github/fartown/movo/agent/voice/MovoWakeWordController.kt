package io.github.fartown.movo.agent.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.github.fartown.movo.data.repository.VoiceSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Starts/stops [MovoWakeWordService] when the app is usable, wake switch is on,
 * and RECORD_AUDIO is granted.
 */
internal object MovoWakeWordController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var started = false

    fun startObserving(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            VoiceSettingsRepository.wakeSettingsFlow()
                .distinctUntilChanged { a, b ->
                    a.wakeEnabled == b.wakeEnabled && a.wakePhrase == b.wakePhrase
                }
                .collectLatest { settings ->
                    val micGranted = ContextCompat.checkSelfPermission(
                        app,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    MovoWakeWordService.syncFromSettings(
                        context = app,
                        enabled = settings.wakeEnabled,
                        micGranted = micGranted,
                    )
                }
        }
    }

    fun refresh(context: Context) {
        scope.launch {
            val settings = VoiceSettingsRepository.wakeSettings()
            val micGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            MovoWakeWordService.syncFromSettings(
                context = context,
                enabled = settings.wakeEnabled,
                micGranted = micGranted,
            )
        }
    }

    fun hasMicPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
}
