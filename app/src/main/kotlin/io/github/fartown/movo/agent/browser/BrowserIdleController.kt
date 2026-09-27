package io.github.fartown.movo.agent.browser

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps the browser active while at least one caller owns a lease. */
internal class BrowserIdleController(
    private val handler: Handler,
    private val onPausedChanged: (Boolean) -> Unit,
    private val idleTimeoutMillis: Long = 5_000L,
) {
    init {
        require(idleTimeoutMillis >= 0L)
    }

    private var owners = 0
    private var pendingIdle: Runnable? = null

    @Volatile
    var isPaused: Boolean = false
        private set

    fun acquire(): AutoCloseable {
        check(Looper.myLooper() == handler.looper) { "Browser lease must be acquired on the handler thread" }
        owners++
        pendingIdle?.let(handler::removeCallbacks)
        pendingIdle = null
        if (isPaused) {
            isPaused = false
            onPausedChanged(false)
        }

        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                if (Looper.myLooper() == handler.looper) {
                    release()
                } else {
                    handler.post(::release)
                }
            }
        }
    }

    private fun release() {
        owners--
        if (owners != 0) return

        val idle = Runnable {
            pendingIdle = null
            if (owners == 0 && !isPaused) {
                isPaused = true
                onPausedChanged(true)
            }
        }
        pendingIdle = idle
        handler.postDelayed(idle, idleTimeoutMillis)
    }
}
