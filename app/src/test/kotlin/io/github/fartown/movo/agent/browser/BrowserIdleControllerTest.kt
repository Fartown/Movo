package io.github.fartown.movo.agent.browser

import android.app.Application
import android.os.Handler
import android.os.Looper
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserIdleControllerTest {
    private val mainLooper get() = shadowOf(Looper.getMainLooper())

    @Test
    fun overlappingLeasesPauseOnlyAfterLastLeaseAndGracePeriod() {
        val changes = mutableListOf<Boolean>()
        val controller = BrowserIdleController(Handler(Looper.getMainLooper()), changes::add)
        val first = controller.acquire()
        val second = controller.acquire()

        first.close()
        mainLooper.idleFor(Duration.ofSeconds(10))
        assertFalse(controller.isPaused)
        assertTrue(changes.isEmpty())

        second.close()
        mainLooper.idleFor(Duration.ofMillis(4_999))
        assertFalse(controller.isPaused)
        mainLooper.idleFor(Duration.ofMillis(1))
        assertTrue(controller.isPaused)
        assertEquals(listOf(true), changes)
    }

    @Test
    fun repeatedCloseDoesNotReleaseAnotherLease() {
        val changes = mutableListOf<Boolean>()
        val controller = BrowserIdleController(Handler(Looper.getMainLooper()), changes::add)
        val first = controller.acquire()
        val second = controller.acquire()

        first.close()
        first.close()
        mainLooper.idleFor(Duration.ofSeconds(10))
        assertFalse(controller.isPaused)

        second.close()
        mainLooper.idleFor(Duration.ofSeconds(5))
        assertEquals(listOf(true), changes)
    }

    @Test
    fun newLeaseDuringGracePeriodCancelsPendingPause() {
        val changes = mutableListOf<Boolean>()
        val controller = BrowserIdleController(Handler(Looper.getMainLooper()), changes::add)
        controller.acquire().close()
        mainLooper.idleFor(Duration.ofSeconds(3))

        val second = controller.acquire()
        mainLooper.idleFor(Duration.ofSeconds(10))
        assertFalse(controller.isPaused)
        assertTrue(changes.isEmpty())

        second.close()
        mainLooper.idleFor(Duration.ofSeconds(5))
        assertEquals(listOf(true), changes)
    }

    @Test
    fun newLeaseResumesImmediatelyAfterPause() {
        val changes = mutableListOf<Boolean>()
        val controller = BrowserIdleController(Handler(Looper.getMainLooper()), changes::add)
        controller.acquire().close()
        mainLooper.idleFor(Duration.ofSeconds(5))
        assertTrue(controller.isPaused)

        val lease = controller.acquire()
        assertFalse(controller.isPaused)
        assertEquals(listOf(true, false), changes)
        lease.close()
    }

    @Test
    fun backgroundCloseReleasesLeaseOnHandlerThread() {
        val changes = mutableListOf<Boolean>()
        val controller = BrowserIdleController(Handler(Looper.getMainLooper()), changes::add)
        val lease = controller.acquire()

        val thread = Thread { lease.close() }
        thread.start()
        thread.join()
        assertFalse(controller.isPaused)

        mainLooper.idle()
        mainLooper.idleFor(Duration.ofSeconds(5))
        assertTrue(controller.isPaused)
        assertEquals(listOf(true), changes)
    }
}
