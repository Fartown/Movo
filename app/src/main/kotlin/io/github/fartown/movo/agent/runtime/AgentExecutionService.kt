package io.github.fartown.movo.agent.runtime

import androidx.core.app.ServiceCompat
import io.github.fartown.movo.flavor.FlavorModule
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.github.fartown.movo.R
import io.github.fartown.movo.core.AndroidAgentLogger
import io.github.fartown.movo.core.safeLogType
import java.util.concurrent.atomic.AtomicLong

/**
 * 只在用户任务存活期间持有前台执行生命周期；进程被系统停止后不重放任务。
 *
 * 后台监听的租约单独登记（[monitorLeases]）：常驻通知上「停止运行任务」只停普通任务，不会连监听一起停；
 * 同时有任务和监听时另给「全部停止」（规范 8.12）。
 */
internal class AgentExecutionService : Service() {
    private val stopQueue = ExecutionStopQueue { failure ->
        AndroidAgentLogger.warn("Execution task stop failed: type=${failure.safeLogType()}")
    }
    private val owner = ownerSequence.incrementAndGet()
    private var foregroundActive = false
    @Volatile private var startRejected = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        attachOwner(owner)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.execution_channel), NotificationManager.IMPORTANCE_LOW),
        )
        ensureForeground()
    }

    private fun ensureForeground() {
        if (foregroundActive || startRejected) return
        attachOwner(owner)
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            foregroundActive = true
            io.github.fartown.movo.diagnostics.DiagnosticsEnvironment.executionService = true
            io.github.fartown.movo.diagnostics.MemoryDiagnostics.record("lifecycle", "execution_service.foreground")
        } catch (failure: RuntimeException) {
            io.github.fartown.movo.diagnostics.MemoryDiagnostics.record("lifecycle", "execution_service.rejected",
                io.github.fartown.movo.diagnostics.DiagnosticLevel.ERROR,
                fields = mapOf("causes" to io.github.fartown.movo.diagnostics.MemoryDiagnostics.causes(failure)))
            startRejected = true
            AndroidAgentLogger.warn("Execution service foreground failed: type=${failure.safeLogType()}")
            stopTasks(startFailed = true, includeMonitors = true)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // 「停止运行任务」：只停普通任务，后台监听继续。
            ACTION_STOP -> stopTasks(includeMonitors = false)
            // 「全部停止」：任务与后台监听一起停。
            ACTION_STOP_ALL -> stopTasks(includeMonitors = true)
            else -> {
                ensureForeground()
                refreshNotification()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        io.github.fartown.movo.diagnostics.DiagnosticsEnvironment.executionService = false
        io.github.fartown.movo.diagnostics.MemoryDiagnostics.record("lifecycle", "execution_service.destroyed")
        if (instance === this) instance = null
        // 销毁时同样收回本服务拥有的任务。回收在独立有界工作线程上完成，不阻塞 Main。
        stopQueue.close(leases.drainOwner(owner) + monitorLeases.drainOwner(owner))
        super.onDestroy()
    }

    private fun stopTasks(startFailed: Boolean = false, includeMonitors: Boolean) {
        io.github.fartown.movo.diagnostics.MemoryDiagnostics.record("lifecycle", "execution_service.stop_requested",
            fields = mapOf("start_failed" to startFailed, "include_monitors" to includeMonitors))
        val callbacks = leases.drain(startFailed) + if (includeMonitors) monitorLeases.drain(startFailed) else emptyList()
        stopQueue.submit(callbacks) {
            mainHandler.post { if (instance === this) refreshNotification() }
        }
    }

    private fun refreshNotification() {
        if (closeOwnerIfIdle(owner)) {
            foregroundActive = false
            io.github.fartown.movo.diagnostics.DiagnosticsEnvironment.executionService = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, FlavorModule.surfaces.mainActivity),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopTasks = PendingIntent.getService(
            this, 1, Intent(this, AgentExecutionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopAll = PendingIntent.getService(
            this, 2, Intent(this, AgentExecutionService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val content = ExecutionNotificationContent.of(taskCount = leases.taskCount(), monitorCount = monitorLeases.count())
        val text = when (content.mode) {
            // 只剩后台监听：写明监听名称与结束时间，按钮是「全部停止」（规范 8.12）。
            ExecutionNotificationContent.Mode.MONITORS_ONLY ->
                io.github.fartown.movo.agent.monitor.MonitorRegistry.executionSummary(this)
                    ?: getString(R.string.monitor_execution_label)
            // 任务与监听同时存在：「任务 1 项·后台监听 2 个」。
            ExecutionNotificationContent.Mode.MIXED ->
                getString(R.string.monitor_execution_mixed, content.taskCount, content.monitorCount)
            ExecutionNotificationContent.Mode.TASKS_ONLY ->
                leases.sharedLabel()?.let(::getString) ?: getString(R.string.execution_summary, content.taskCount)
        }
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.execution_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (content.showStopTasks) {
            builder.addAction(Notification.Action.Builder(null, getString(R.string.execution_stop), stopTasks).build())
        }
        if (content.showStopAll) {
            builder.addAction(Notification.Action.Builder(null, getString(R.string.monitor_stop_all), stopAll).build())
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL = "movo_execution"
        private const val NOTIFICATION_ID = 1107
        private const val ACTION_STOP = "io.github.fartown.movo.action.STOP_USER_EXECUTION"
        private const val ACTION_STOP_ALL = "io.github.fartown.movo.action.STOP_ALL_EXECUTION"
        private val leases = ExecutionLeaseRegistry()
        /** 后台监听的租约（label = 监听）：单独登记，「停止运行任务」不动它们。 */
        private val monitorLeases = ExecutionLeaseRegistry()
        private val ownerSequence = AtomicLong()
        private val mainHandler = Handler(Looper.getMainLooper())
        @Volatile private var instance: AgentExecutionService? = null

        private fun attachOwner(owner: Long) {
            leases.attachOwner(owner)
            monitorLeases.attachOwner(owner)
        }

        /** 两类租约都空了才停服务；检查与关闭之间有新租约登记时恢复归属、继续前台。 */
        private fun closeOwnerIfIdle(owner: Long): Boolean {
            if (leases.count() > 0 || monitorLeases.count() > 0) return false
            val tasksIdle = leases.closeOwnerIfIdle(owner)
            val monitorsIdle = monitorLeases.closeOwnerIfIdle(owner)
            if (tasksIdle && monitorsIdle) return true
            attachOwner(owner)
            return false
        }

        /** 必须从有效的用户入口取得引用，再创建会话或子进程；失败时调用方不启动任务。 */
        fun acquire(
            context: Context,
            id: String,
            allowBoundFallback: Boolean = false,
            /** 不是用户任务时（登录、后台命令、终端、后台监听）通知里显示的说明，见 [ExecutionLeaseRegistry.sharedLabel]。 */
            @androidx.annotation.StringRes label: Int? = null,
            /** 用户任务的运行 id：准备与执行两段引用共用，通知只算一项。 */
            task: String = id,
            onStop: () -> Unit,
        ): Boolean {
            if (instance?.startRejected == true) return false
            val registry = if (label == R.string.monitor_execution_label) monitorLeases else leases
            if (!registry.acquire(id, allowBoundFallback, label, task, onStop)) return true
            return try {
                FlavorModule.startExecutionService(context.applicationContext, Intent(context, AgentExecutionService::class.java))
                true
            } catch (failure: RuntimeException) {
                registry.release(id)
                AndroidAgentLogger.warn("Execution service start rejected: type=${failure.safeLogType()}")
                false
            }
        }

        fun release(id: String) {
            leases.release(id)
            monitorLeases.release(id)
            mainHandler.post { instance?.refreshNotification() }
        }

        /** 任务内容变了（如后台监听增减）时刷新常驻通知。 */
        fun refresh() {
            mainHandler.post { instance?.refreshNotification() }
        }
    }
}

/** 常驻通知的文案模式与按钮（纯逻辑，便于单测）。 */
internal data class ExecutionNotificationContent(
    val mode: Mode,
    val taskCount: Int,
    val monitorCount: Int,
) {
    enum class Mode { TASKS_ONLY, MONITORS_ONLY, MIXED }

    /** 「停止运行任务」：有普通任务时才有，只停普通任务。 */
    val showStopTasks: Boolean get() = mode != Mode.MONITORS_ONLY

    /** 「全部停止」：有后台监听时才有。 */
    val showStopAll: Boolean get() = mode != Mode.TASKS_ONLY

    companion object {
        fun of(taskCount: Int, monitorCount: Int): ExecutionNotificationContent = ExecutionNotificationContent(
            mode = when {
                monitorCount > 0 && taskCount == 0 -> Mode.MONITORS_ONLY
                monitorCount > 0 -> Mode.MIXED
                else -> Mode.TASKS_ONLY
            },
            taskCount = taskCount,
            monitorCount = monitorCount,
        )
    }
}
