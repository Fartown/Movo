package io.github.fartown.movo.diagnostics

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import io.github.fartown.movo.BuildConfig

/** Lightweight process-owned observers and the local log store; no polling or battery policy changes. */
internal object DiagnosticsEnvironment {
    @Volatile private var foreground = false
    @Volatile var executionService = false
    private var initialized = false
    @Volatile private var store: DiagnosticStore? = null

    /**
     * 运行日志页的「清空运行日志」：删除已结束任务的运行记录和完整日志；进行中的任务、任务之外的系统事件保留
     * （与确认框写的一致）。[done] 在两边都处理完后调用（后台线程）。
     */
    fun clearLogs(done: () -> Unit = {}) {
        val keep = MemoryDiagnostics.activeRuns()
        MemoryDiagnostics.buffer.retain { it.context.run.isBlank() || it.context.run in keep }
        val pending = java.util.concurrent.atomic.AtomicInteger(2)
        val finish = { if (pending.decrementAndGet() == 0) runCatching(done) }
        store?.clear(keep, { MemoryDiagnostics.buffer.snapshot().entries }) { finish() } ?: finish()
        io.github.fartown.movo.diagnostics.runlog.RunLog.store()?.clear { finish() } ?: finish()
    }

    fun initialize(app: Application) {
        if (initialized) return
        initialized = true
        MemoryDiagnostics.elapsedClock = SystemClock::elapsedRealtime
        // 先恢复上次保存的记录并接上编号，再记录本次进程的第一条事件。
        val store = DiagnosticStore(java.io.File(app.filesDir, "diagnostics/events.log"))
        this.store = store
        val saved = store.load(System.currentTimeMillis())
        MemoryDiagnostics.buffer.restore(saved)
        MemoryDiagnostics.continueNumbering(
            DiagnosticRetention.lastNumber(saved, 'R') { it.context.run },
            DiagnosticRetention.lastNumber(saved, 'Q') { it.context.request },
        )
        MemoryDiagnostics.sink = { entry -> store.append(entry) { MemoryDiagnostics.buffer.snapshot().entries } }
        // 完整运行日志：只在有运行日志页的版本记；启动时写线程先删掉上次被杀的任务目录。
        if (io.github.fartown.movo.flavor.FlavorModule.fullRunLog) {
            io.github.fartown.movo.diagnostics.runlog.RunLog.configure(
                java.io.File(app.filesDir, "run-log"), BuildConfig.VERSION_NAME,
            ) { io.github.fartown.movo.config.Prefs.isRunLogEnabled() }
        }
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        val power = app.getSystemService(PowerManager::class.java)
        MemoryDiagnostics.environment = {
            val network = runCatching { connectivity.getNetworkCapabilities(connectivity.activeNetwork) }.getOrNull()
            val runtime = Runtime.getRuntime()
            mapOf(
                "foreground" to foreground,
                "execution_service" to executionService,
                "network" to when {
                    network == null -> "none"
                    network.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                    network.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    network.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    network.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    else -> "other"
                },
                "network_validated" to (network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true),
                "screen_on" to power.isInteractive,
                "device_idle" to power.isDeviceIdleMode,
                "power_save" to power.isPowerSaveMode,
                "battery_exempt" to power.isIgnoringBatteryOptimizations(app.packageName),
                "heap_used_mib" to (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024),
                "heap_max_mib" to runtime.maxMemory() / (1024 * 1024),
            )
        }
        fun observe(event: String) = MemoryDiagnostics.record("environment", event, fields = MemoryDiagnostics.environment())
        MemoryDiagnostics.record("environment", "process.started", fields = MemoryDiagnostics.environment() + mapOf(
            "version" to BuildConfig.VERSION_NAME, "build" to BuildConfig.BUILD_TYPE,
        ))
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: Activity) {
                started++
                if (!foreground) { foreground = true; observe("app.foreground") }
            }
            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0 && foreground) { foreground = false; observe("app.background") }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        runCatching {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { observe("network.available") }
                override fun onLost(network: Network) { observe("network.lost") }
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    observe("network.changed")
                }
            })
            app.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    observe(when (intent.action) {
                        Intent.ACTION_SCREEN_OFF -> "screen.off"
                        Intent.ACTION_SCREEN_ON -> "screen.on"
                        PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> "device_idle.changed"
                        else -> "power_save.changed"
                    })
                }
            }, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }, Context.RECEIVER_NOT_EXPORTED)
        }.onFailure {
            MemoryDiagnostics.record("environment", "observer.failed", DiagnosticLevel.WARN,
                fields = mapOf("causes" to MemoryDiagnostics.causes(it)))
        }
    }
}
