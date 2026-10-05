package io.github.fartown.movo.agent.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.Keep
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Same-process instrumentation boundary for the real Runtime overlay.
 *
 * It adds no exported Android entry point: tests bind through the existing Runtime binder and
 * can enable the fixture only in a debuggable APK. The fixture injects Runtime events, while the
 * production Service, WindowManager, Compose tree, voice session, and execution lease stay real.
 */
@Keep
internal object AgentRuntimeInstrumentationAccess {
    data class Snapshot(
        val available: Boolean = false,
        val taskActive: Boolean = false,
        val voiceActive: Boolean = false,
        val overlayAttached: Boolean = false,
        val expanded: Boolean = false,
        val typing: Boolean = false,
        val phase: String = "unavailable",
        val stopObserved: Boolean = false,
    )

    private val lock = Any()
    private var appContext: Context? = null
    private var connection: ServiceConnection? = null

    fun begin(context: Context): Boolean {
        val app = context.applicationContext
        check(app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            "Runtime instrumentation fixture requires a debuggable APK"
        }
        finish()
        val connected = CountDownLatch(1)
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        synchronized(lock) {
            appContext = app
            connection = serviceConnection
        }
        val bound = app.bindService(
            Intent(app, AgentRuntimeService::class.java).setAction(AgentRuntimeWire.ACTION_BIND),
            serviceConnection,
            Context.BIND_AUTO_CREATE,
        )
        if (!bound || !connected.await(5, TimeUnit.SECONDS)) {
            finish()
            return false
        }
        val started = onMain { AgentRuntimeService.beginInstrumentationFixture() }
        if (!started) finish()
        return started
    }

    fun expand(): Boolean = onMain { AgentRuntimeService.expandInstrumentationFixture() }

    fun snapshot(): Snapshot = onMain { AgentRuntimeService.instrumentationFixtureSnapshot() }

    fun finish() {
        onMain { AgentRuntimeService.finishInstrumentationFixture() }
        val (app, serviceConnection) = synchronized(lock) {
            val pair = appContext to connection
            appContext = null
            connection = null
            pair
        }
        if (app != null && serviceConnection != null) {
            runCatching { app.unbindService(serviceConnection) }
        }
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val value = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        val finished = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            runCatching(block)
                .onSuccess(value::set)
                .onFailure(failure::set)
            finished.countDown()
        }
        check(finished.await(5, TimeUnit.SECONDS)) { "Timed out waiting for Runtime fixture main thread" }
        failure.get()?.let { throw it }
        return value.get()
    }
}
