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
 * 后台监听的租约单独登记（[monitorLeases]）。常驻通知只有一个按钮「结束任务」（规范 8.12「22」）：
 * 任务马上停，后台监听一起结束、5 秒内可撤销（通知原位变成「已结束任务·撤销」）。
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
            stopTasks(startFailed = true, includeMonitors = true, source = "execution_service.start_failed")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // 「结束任务」：任务马上停，后台监听一起结束（5 秒内可撤销）。
            ACTION_END -> {
                AndroidAgentLogger.info("Execution notification: end task")
                stopTasks(includeMonitors = false, source = "notification.end")
                io.github.fartown.movo.agent.monitor.MonitorRegistry.endLater { true }
            }
            ACTION_UNDO -> {
                AndroidAgentLogger.info("Monitor ending undo: source=notification")
                io.github.fartown.movo.agent.monitor.MonitorRegistry.endings.value
                    .forEach { io.github.fartown.movo.agent.monitor.MonitorRegistry.undoEnding(it.id) }
            }
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
        val tasks = leases.drainOwnerTasks(owner)
        recordStops(tasks, "execution_service.destroyed")
        stopQueue.close(tasks.map { it.second } + monitorLeases.drainOwner(owner))
        super.onDestroy()
    }

    private fun stopTasks(startFailed: Boolean = false, includeMonitors: Boolean, source: String) {
        io.github.fartown.movo.diagnostics.MemoryDiagnostics.record("lifecycle", "execution_service.stop_requested",
            fields = mapOf("start_failed" to startFailed, "include_monitors" to includeMonitors))
        val tasks = leases.drainTasks(startFailed)
        recordStops(tasks, source)
        val callbacks = tasks.map { it.second } + if (includeMonitors) monitorLeases.drain(startFailed) else emptyList()
        stopQueue.submit(callbacks) {
            mainHandler.post { if (instance === this) refreshNotification() }
        }
    }

    /** 运行日志：每个被停的用户任务记一条停止和来源（任务键就是界面任务号）。 */
    private fun recordStops(tasks: List<Pair<String, () -> Unit>>, source: String) {
        tasks.map { it.first }.distinct().forEach { task ->
            runCatching { io.github.fartown.movo.diagnostics.runlog.RunLog.stop(task, source) }
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
        val registry = io.github.fartown.movo.agent.monitor.MonitorRegistry
        val monitors = registry.active.value
        val endings = registry.endings.value
        val content = ExecutionNotificationContent.of(
            taskCount = leases.taskCount(), monitorCount = monitors.size, endingCount = endings.size,
        )
        val separator = getString(R.string.monitor_names_separator)
        val title: String
        val text: String
        when (content.mode) {
            // 刚结束、还能撤销：「已结束任务 / 喝水提醒也停了」+「撤销」。
            ExecutionNotificationContent.Mode.ENDED -> {
                title = getString(R.string.monitor_execution_ended_title)
                text = getString(R.string.monitor_execution_ended_text, endings.flatMap { it.names }.joinToString(separator))
            }
            // 只剩后台监听：「监听中·喝水提醒 / 17:00 自动结束」，两个及以上「监听中·2 个 / 喝水提醒、电量播报·17:00 自动结束」。
            ExecutionNotificationContent.Mode.MONITORS_ONLY -> {
                val end = io.github.fartown.movo.agent.monitor.MonitorTime.clock(this, monitors.maxOf { it.deadlineAtMillis })
                if (monitors.size == 1) {
                    title = getString(R.string.monitor_overlay_title, monitors.single().name)
                    text = getString(R.string.monitor_execution_until, end)
                } else {
                    title = getString(R.string.monitor_overlay_title_count, monitors.size)
                    text = getString(R.string.monitor_execution_names_until, monitors.joinToString(separator) { it.name }, end)
                }
            }
            // 任务与监听同时存在：「任务 1 项·后台监听 2 个」。
            ExecutionNotificationContent.Mode.MIXED -> {
                title = getString(R.string.execution_title)
                text = getString(R.string.monitor_execution_mixed, content.taskCount, content.monitorCount)
            }
            ExecutionNotificationContent.Mode.TASKS_ONLY -> {
                title = getString(R.string.execution_title)
                text = leases.sharedLabel()?.let(::getString) ?: getString(R.string.execution_summary, content.taskCount)
            }
        }
        val action = when (content.action) {
            ExecutionNotificationContent.Action.UNDO -> ACTION_UNDO to R.string.monitor_undo
            ExecutionNotificationContent.Action.END_TASK -> ACTION_END to R.string.monitor_execution_end_task
        }
        val button = PendingIntent.getService(
            this, 1, Intent(this, AgentExecutionService::class.java).setAction(action.first),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(action.second), button).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "movo_execution"
        private const val NOTIFICATION_ID = 1107
        private const val ACTION_END = "io.github.fartown.movo.action.END_EXECUTION"
        private const val ACTION_UNDO = "io.github.fartown.movo.action.UNDO_END_EXECUTION"
        private val leases = ExecutionLeaseRegistry()
        /** 后台监听的租约（label = 监听）：单独登记，「结束任务」不直接收回它们（监听晚 5 秒才停，可撤销）。 */
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
    /** [ENDED]：任务和监听都结束了，监听还在等待撤销期满。 */
    enum class Mode { TASKS_ONLY, MONITORS_ONLY, MIXED, ENDED }

    enum class Action { END_TASK, UNDO }

    /** 只有一个按钮（规范 8.12「22」）：结束中是「撤销」，其余都是「结束任务」（任务与监听一起结束）。 */
    val action: Action get() = if (mode == Mode.ENDED) Action.UNDO else Action.END_TASK

    companion object {
        /** [monitorCount] 只算运行中的监听；[endingCount] 是等待撤销期满的结束次数。 */
        fun of(taskCount: Int, monitorCount: Int, endingCount: Int = 0): ExecutionNotificationContent = ExecutionNotificationContent(
            mode = when {
                taskCount == 0 && monitorCount == 0 && endingCount > 0 -> Mode.ENDED
                monitorCount > 0 && taskCount == 0 -> Mode.MONITORS_ONLY
                monitorCount > 0 -> Mode.MIXED
                else -> Mode.TASKS_ONLY
            },
            taskCount = taskCount,
            monitorCount = monitorCount,
        )
    }
}
