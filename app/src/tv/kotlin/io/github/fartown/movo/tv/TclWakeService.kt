package io.github.fartown.movo.tv

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import io.github.fartown.movo.agent.voice.session.VoiceEntry
import io.github.fartown.movo.agent.voice.session.VoiceSessionManager
import io.github.fartown.movo.data.repository.VoiceSettingsRepository
import io.github.fartown.movo.diagnostics.MemoryDiagnostics
import org.json.JSONObject

/** Keeps the OEM wake detector; cancels its ASR and hands the user's session to Movo. */
internal class TclWakeService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var remote: IBinder? = null
    private var bound = false
    private var registered = false
    private var stopping = false
    private var lastWake = ""
    private var lastTakeover = -5000L
    private val property by lazy { Class.forName("android.os.SystemProperties").getMethod("get", String::class.java) }
    private val poll = object : Runnable {
        override fun run() {
            if (stopping || remote == null) return
            runCatching {
                val value = property.invoke(null, PROPERTY) as String
                if (value != lastWake) {
                    lastWake = value
                    val age = value.toLongOrNull()?.let { SystemClock.uptimeMillis() - it }
                    if (age != null && age in 0..5000) takeover()
                }
                main.postDelayed(this, 100)
            }.onFailure { failed("无法读取本机唤醒状态，接管已停止") }
        }
    }
    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(CALLBACK); return true }
            if (code != 1) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(CALLBACK)
            val action = data.readString(); data.readString()
            val result = if (action == "requestScene") JSONObject().put("sceneid", packageName).put("_objhash", 1)
                .put("sceneinfo", JSONObject().put("_scene", packageName).put("_commands", JSONObject())).toString() else ""
            if (action == "showVoiceIcon") main.post { takeover() }
            reply?.writeNoException(); reply?.writeString(result)
            return true
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (stopping) return
            remote = binder
            runCatching {
                transaction(1) { request, reply ->
                    request.writeStrongBinder(callback); request.writeString(packageName)
                    check(binder.transact(1, request, reply, 0)); reply.readException(); reply.readInt()
                }
                registered = true
                call("sceneEnable", JSONObject().put("isOpen", true).put("type", "FLOAT_WINDOW").put("sceneId", packageName).toString())
                lastWake = property.invoke(null, PROPERTY) as String // Ignore old wakes after reconnect.
                status(this@TclWakeService, "唤醒接管已连接")
                main.removeCallbacks(poll); main.post(poll)
                MemoryDiagnostics.record("tv.voice", "wake.ready")
            }.onFailure { failed("小T接口连接失败，接管已停止") }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            remote = null; registered = false; main.removeCallbacks(poll)
            status(this@TclWakeService, "小T连接中断，等待恢复")
        }
        override fun onBindingDied(name: ComponentName) { failed("小T服务已更新，请重新开启接管") }
    }
    override fun onCreate() {
        super.onCreate()
        alive = true
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("tv_wake", "电视唤醒接管", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, TvMainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(710, Notification.Builder(this, "tv_wake").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Movo 唤醒接管").setContentText("说小T小T进入 Movo；可在设置中关闭")
            .setContentIntent(open).setOngoing(true).build())
        if (!enabled(this) || !TclPcmInput.supported(this)) { stopSelf(); return }
        bound = bindService(Intent().setComponent(ComponentName("com.tcl.walleve", "com.tcl.thirdapi.api.ThirdApiService")), connection, BIND_AUTO_CREATE)
        if (!bound) failed("无法连接小T服务，接管已停止")
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = if (enabled(this)) START_STICKY else START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    private fun takeover() {
        if (stopping || remote == null) return
        runCatching {
            call("cancelFarFieldAsr", "")
            val now = SystemClock.elapsedRealtime()
            if (now - lastTakeover < 3000) return
            lastTakeover = now
            MemoryDiagnostics.record("tv.voice", "wake.takeover")
            if (VoiceSessionManager.active) VoiceSessionManager.stopSpeaking()
            else VoiceEntry.startInPlace(this)
            VoiceEntry.startFromSystemEntry(this, autoListen = true)
        }.onFailure { failed("无法接管小T会话，接管已停止") }
    }
    private fun call(action: String, value: String) = transaction(3) { request, reply ->
        request.writeString(action); request.writeString(value)
        check(remote?.transact(3, request, reply, 0) == true)
        reply.readException(); reply.readString()
    }
    private fun <T> transaction(code: Int, action: (Parcel, Parcel) -> T): T {
        val request = Parcel.obtain(); val reply = Parcel.obtain()
        try { request.writeInterfaceToken(API); return action(request, reply) }
        finally { request.recycle(); reply.recycle() }
    }
    private fun failed(message: String) {
        prefs(this).edit().putBoolean("enabled", false).apply()
        status(this, message)
        MemoryDiagnostics.record("tv.voice", "wake.failed")
        stopSelf()
    }
    override fun onDestroy() {
        alive = false
        stopping = true; main.removeCallbacksAndMessages(null)
        runCatching { call("sceneEnable", JSONObject().put("isOpen", false).put("sceneId", packageName).toString()) }
        if (registered) runCatching { transaction(2) { request, reply ->
            request.writeStrongBinder(callback); remote?.transact(2, request, reply, 0); reply.readException()
        } }
        if (bound) unbindService(connection)
        remote = null
        stopForeground(true)
        MemoryDiagnostics.record("tv.voice", "wake.stopped")
        super.onDestroy()
    }
    companion object {
        private const val API = "com.tcl.walleve.thirdapi.IThirdApiV3"
        private const val CALLBACK = "com.tcl.walleve.thirdapi.IThirdApiCallbackV3"
        private const val PROPERTY = "sys.tcl.voice.active_uptime"
        private fun prefs(context: Context) = context.getSharedPreferences("tv_wake", MODE_PRIVATE)
        /** 电视以语音为主：支持的 TCL 电视默认接管「小T小T」；只有用户手动关掉才不接管。 */
        fun enabled(context: Context) = prefs(context).getBoolean("enabled", TclPcmInput.supported(context))
        fun status(context: Context) = prefs(context).getString("status", "唤醒接管未开启").orEmpty()
        @Volatile private var alive = false

        /**
         * 换包、重启或进程被杀后服务不会自己回来，设置里却还留着上一个进程写的「已连接」，小T照常抢答（2026-10-06 真机）。
         * 进程起来、无障碍重新连上、开机 / 更新广播时调用：开着接管就把服务拉起来，状态先改成「正在连接」。
         */
        fun restoreIfEnabled(context: Context) {
            // 无条件接管：缺录音权限时，喊「小T小T」会打开 Movo 并当场请求授权（TvMainActivity 自动听）。
            if (alive || !enabled(context) || !TclPcmInput.supported(context)) return
            status(context, "正在连接小T唤醒服务")
            val intent = Intent(context, TclWakeService::class.java)
            // TCL Android 9 拒绝后台进程的 startForegroundService；系统绑定的无障碍服务可以启动普通服务，onCreate 里再转前台。
            val started = runCatching {
                if (io.github.fartown.movo.agent.accessibility.AgentAccessibilityService.current() != null) context.startService(intent)
                else context.startForegroundService(intent)
            }.isSuccess
            MemoryDiagnostics.record("tv.voice", "wake.restore", fields = mapOf("started" to started))
            if (!started) status(context, "接管没能自动恢复，请在设置里重新打开")
        }
        private fun status(context: Context, message: String) { prefs(context).edit().putString("status", message).apply() }
        fun toggle(context: Context): String {
            if (enabled(context)) {
                prefs(context).edit().putBoolean("enabled", false).apply()
                context.stopService(Intent(context, TclWakeService::class.java))
                status(context, "接管已关闭，小T恢复响应")
                return status(context)
            }
            if (!TclPcmInput.supported(context)) return "这台电视不支持接管「小T小T」"
            prefs(context).edit().putBoolean("enabled", true).apply()
            return runCatching {
                context.startForegroundService(Intent(context, TclWakeService::class.java))
                status(context, "正在连接小T唤醒服务"); status(context)
            }.getOrElse {
                prefs(context).edit().putBoolean("enabled", false).apply()
                "无法启动接管服务，请重试"
            }
        }
    }
}
