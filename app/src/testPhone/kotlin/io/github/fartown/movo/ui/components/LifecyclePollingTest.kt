package io.github.fartown.movo.ui.components

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class LifecyclePollingTest {
    @Test
    fun stopCancelsActiveRefreshAndRestartRefreshesImmediately() = runBlocking {
        val owner = object : LifecycleOwner {
            lateinit var registry: LifecycleRegistry
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry = LifecycleRegistry(owner)
        val started = Channel<Unit>(Channel.UNLIMITED)
        val cancelled = Channel<Unit>(Channel.UNLIMITED)
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            owner.lifecycle.pollWhileStarted(intervalMillis = { 10_000L }) {
                started.send(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.trySend(Unit)
                }
            }
        }
        try {
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            assertTrue(started.tryReceive().isFailure)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            withTimeout(2_000) { started.receive() }
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            withTimeout(2_000) { cancelled.receive() }
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            withTimeout(2_000) { started.receive() }
            assertTrue(cancelled.tryReceive().isFailure)
        } finally {
            job.cancel()
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }
}
