package io.github.fartown.movo.agent.voice

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import io.github.fartown.movo.R
import io.github.fartown.movo.data.repository.VoiceSettingsRepository
import io.github.fartown.movo.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Quick Settings tile: tap toggles wake listening, long press opens the voice settings. */
internal class MovoWakeTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var rendering: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        rendering?.cancel()
        rendering = scope.launch {
            combine(VoiceSettingsRepository.wakeSettingsFlow(), MovoWakeWordService.listeningState) { settings, state ->
                WakeTileState.of(settings.wakeEnabled, MovoWakeWordController.hasMicPermission(this@MovoWakeTileService), state)
            }.collect(::render)
        }
    }

    override fun onStopListening() {
        rendering?.cancel()
        rendering = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            when {
                !MovoWakeWordController.hasMicPermission(this@MovoWakeTileService) -> open(
                    Intent(this@MovoWakeTileService, MainActivity::class.java).setAction(ACTION_QS_TILE_PREFERENCES),
                )
                // MovoWakeWordController observes the setting and stops the running service.
                VoiceSettingsRepository.wakeSettings().wakeEnabled -> VoiceSettingsRepository.setWakeEnabled(false)
                else -> open(Intent(this@MovoWakeTileService, WakeTileBridgeActivity::class.java))
            }
        }
    }

    private fun open(intent: Intent) {
        startActivityAndCollapse(
            PendingIntent.getActivity(
                this,
                0,
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }

    private fun render(state: WakeTileState) {
        val tile = qsTile ?: return
        tile.state = if (state.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = getString(state.subtitle)
        tile.updateTile()
    }
}

internal data class WakeTileState(val active: Boolean, @param:StringRes val subtitle: Int) {
    companion object {
        fun of(enabled: Boolean, micGranted: Boolean, listening: WakeListeningState): WakeTileState = when {
            !micGranted -> WakeTileState(false, R.string.wake_tile_no_mic)
            !enabled -> WakeTileState(false, R.string.wake_tile_off)
            else -> WakeTileState(
                true,
                when (listening) {
                    WakeListeningState.Listening -> R.string.wake_tile_listening
                    WakeListeningState.ScreenOff -> R.string.wake_tile_screen_off
                    WakeListeningState.AppHidden -> R.string.wake_tile_app_hidden
                    WakeListeningState.Dictating -> R.string.wake_tile_dictating
                    WakeListeningState.Starting -> R.string.wake_tile_starting
                    WakeListeningState.Stopped, is WakeListeningState.Failed -> R.string.wake_tile_not_running
                },
            )
        }
    }
}

/**
 * Transparent hop for turning wake on from the tile. The microphone foreground service may
 * only start while an Movo activity is visible, so this stays resumed until the service has
 * promoted itself, then finishes.
 */
internal class WakeTileBridgeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            VoiceSettingsRepository.setWakeEnabled(true)
            withResumed { MovoWakeWordService.start(this@WakeTileBridgeActivity) }
            withTimeoutOrNull(3_000) {
                MovoWakeWordService.listeningState.first {
                    it != WakeListeningState.Stopped && it !is WakeListeningState.Failed
                }
            }
            finish()
        }
    }
}
