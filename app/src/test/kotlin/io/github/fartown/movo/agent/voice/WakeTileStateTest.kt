package io.github.fartown.movo.agent.voice

import io.github.fartown.movo.R
import org.junit.Assert.assertEquals
import org.junit.Test

class WakeTileStateTest {
    @Test fun missingMicPermissionShowsInactiveEvenWhenEnabled() {
        assertEquals(WakeTileState(false, R.string.wake_tile_no_mic),
            WakeTileState.of(enabled = true, micGranted = false, listening = WakeListeningState.Listening))
    }

    @Test fun disabledWakeIsInactive() {
        assertEquals(WakeTileState(false, R.string.wake_tile_off),
            WakeTileState.of(enabled = false, micGranted = true, listening = WakeListeningState.Stopped))
    }

    @Test fun enabledWakeIsActiveAndReportsTheServiceState() {
        val expected = mapOf(
            WakeListeningState.Listening to R.string.wake_tile_listening,
            WakeListeningState.ScreenOff to R.string.wake_tile_screen_off,
            WakeListeningState.AppHidden to R.string.wake_tile_app_hidden,
            WakeListeningState.Dictating to R.string.wake_tile_dictating,
            WakeListeningState.Starting to R.string.wake_tile_starting,
            WakeListeningState.Stopped to R.string.wake_tile_not_running,
            WakeListeningState.Failed("x") to R.string.wake_tile_not_running,
        )
        expected.forEach { (listening, subtitle) ->
            assertEquals(WakeTileState(true, subtitle), WakeTileState.of(true, true, listening))
        }
    }
}
