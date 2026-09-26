package io.github.fartown.movo.ui

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class KeyguardContentGateTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val keyguard = shadowOf(activity.getSystemService(KeyguardManager::class.java))
    private var cancelled = 0
    private val gate = KeyguardContentGate(activity) { cancelled++ }

    @Test fun unlockedDeviceShowsContentAndNeverDrawsOverTheKeyguard() {
        gate.check()
        assertFalse(gate.locked)
        assertFalse(shadowOf(activity).showWhenLocked)
    }

    @Test fun lockedDeviceHidesContentUntilUnlockThenStopsDrawingOverTheKeyguard() {
        keyguard.setKeyguardLocked(true)
        gate.check()
        assertTrue(gate.locked)
        assertTrue(shadowOf(activity).showWhenLocked)
        gate.check() // A second request would fail the first callback with onDismissError.
        keyguard.setKeyguardLocked(false)
        assertFalse(gate.locked)
        assertFalse(shadowOf(activity).showWhenLocked)
        assertEquals(0, cancelled)
    }

    @Test fun cancellingTheUnlockClosesTheSurfaceWithoutRevealingContent() {
        keyguard.setKeyguardLocked(true)
        gate.check()
        keyguard.setKeyguardLocked(true) // Robolectric reports a still-locked device as a cancelled dismissal.
        assertEquals(1, cancelled)
        assertTrue(gate.locked)
    }
}
