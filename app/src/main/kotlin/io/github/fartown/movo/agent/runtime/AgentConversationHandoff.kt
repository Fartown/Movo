package io.github.fartown.movo.agent.runtime

import androidx.core.content.IntentCompat
import android.os.Build
import io.github.fartown.movo.flavor.FlavorModule
import android.content.Context
import android.content.Intent
import android.os.ResultReceiver

/** Identifies an existing conversation; opening a result must never create another chat. */
internal data class AgentConversationTarget(val source: String, val key: String) {
    companion object {
        fun from(handoff: AgentRuntimeWire.EntryHandoff?): AgentConversationTarget? {
            handoff ?: return null
            val key = if (handoff.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) {
                AgentUiHandoffPayload.from(handoff.payload).conversationId
                    .takeUnless { it.startsWith("{") }
            } else {
                AgentExternalArchivePayload.from(handoff.payload)?.conversationKey
            }
            return key?.takeIf { it.isNotBlank() && handoff.source.isNotBlank() }
                ?.let { AgentConversationTarget(handoff.source, it) }
        }
    }
}

/** The result overlay stays visible until the original chat has restored and selected its history. */
internal object AgentConversationHandoff {
    const val ACTION_OPEN = "io.github.fartown.movo.agent.runtime.OPEN_CONVERSATION"
    const val RESULT_READY = 1
    const val RESULT_FAILED = 0
    private const val EXTRA_SOURCE = "conversation_source"
    private const val EXTRA_KEY = "conversation_key"
    private const val EXTRA_RUN_ID = "conversation_run_id"
    private const val EXTRA_CURRENT_CONVERSATION = "current_conversation"
    private const val EXTRA_RECEIVER = "conversation_receiver"

    data class Request(
        val target: AgentConversationTarget,
        val runId: String?,
        val receiver: ResultReceiver?,
    ) {
        fun acknowledge(opened: Boolean) {
            receiver?.send(if (opened) RESULT_READY else RESULT_FAILED, null)
        }
    }

    fun intent(context: Context, target: AgentConversationTarget, runId: String?, receiver: ResultReceiver): Intent =
        Intent(context, FlavorModule.surfaces.mainActivity)
            .setAction(ACTION_OPEN)
            .putExtra(EXTRA_SOURCE, target.source)
            .putExtra(EXTRA_KEY, target.key)
            .putExtra(EXTRA_RUN_ID, runId)
            .putExtra(EXTRA_CURRENT_CONVERSATION, runId == null)
            .putExtra(EXTRA_RECEIVER, receiver)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** 从系统通知打开 App 内某个已有对话（后台监听的通知等），不需要结果回传。 */
    fun openConversationIntent(context: Context, conversationId: String): Intent =
        Intent(context, FlavorModule.surfaces.mainActivity)
            .setAction(ACTION_OPEN)
            .putExtra(EXTRA_SOURCE, AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE)
            .putExtra(EXTRA_KEY, conversationId)
            .putExtra(EXTRA_CURRENT_CONVERSATION, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun from(intent: Intent?): Request? {
        if (intent?.action != ACTION_OPEN) return null
        val source = intent.getStringExtra(EXTRA_SOURCE)?.takeIf(String::isNotBlank) ?: return null
        val key = intent.getStringExtra(EXTRA_KEY)?.takeIf(String::isNotBlank) ?: return null
        val runId = intent.getStringExtra(EXTRA_RUN_ID)
        // A voice sheet can open the existing app conversation before any task has finished.
        // Result handoffs still require a nonblank run ID and retain history validation.
        if (runId == null) {
            if (!intent.getBooleanExtra(EXTRA_CURRENT_CONVERSATION, false) ||
                source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) return null
        } else if (runId.isBlank()) return null
        return Request(AgentConversationTarget(source, key), runId,
            IntentCompat.getParcelableExtra(intent, EXTRA_RECEIVER, ResultReceiver::class.java))
    }

    fun consume(intent: Intent) {
        if (intent.action != ACTION_OPEN) return
        intent.action = null
        listOf(EXTRA_SOURCE, EXTRA_KEY, EXTRA_RUN_ID, EXTRA_CURRENT_CONVERSATION, EXTRA_RECEIVER).forEach(intent::removeExtra)
    }

    /**
     * 当前主界面实例，仅当它的 ActivityRecord 不是 Movo 自己拉起的（桌面图标、最近任务、系统界面等）。
     *
     * 系统对这种记录不认 `windowDisablePreview`（AOSP `launchedFromSystemSurface()`）；而「展开到 App」送来的
     * OPEN_CONVERSATION 又不是 MAIN/LAUNCHER intent，不能用任务快照，于是 HyperOS 在它回到前台时整屏插一个启动画面
     * （深色模式下深灰 + 光球，约 100–200ms），盖在已推满全屏的浮层上就是一闪。换成 MAIN intent 只会改成任务快照，
     * 显示离开 App 时的旧画面（例如开着的侧边栏），同样会闪；`setSplashScreenStyle` 对这条路径无效（真机验证）。
     */
    private var systemLaunchedMain: java.lang.ref.WeakReference<android.app.Activity>? = null

    fun onMainCreated(activity: android.app.Activity) {
        // launchedFromPackage 只在拉起方是本应用（或主动共享身份）时才有值，桌面图标拉起时为 null。
        // 拉起方只能在 Android 14+ 读到；更低版本不做这项处理。
        val external = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            activity.launchedFromPackage != activity.packageName
        systemLaunchedMain = if (external) java.lang.ref.WeakReference(activity) else null
    }

    fun onMainDestroyed(activity: android.app.Activity) {
        if (systemLaunchedMain?.get() === activity) systemLaunchedMain = null
    }

    /**
     * 「展开到 App」前调用：主界面若是系统入口拉起的，先结束它所在的任务，随后的 OPEN_CONVERSATION 会新建任务、
     * 由 Movo 自己拉起主界面——这时 `windowDisablePreview` 生效、不加启动窗，浮层一直盖到主界面画好首帧。
     * 会话状态在进程级 [io.github.fartown.movo.ui.app.AgentAppSession]，重建主界面不丢会话。
     */
    fun releaseSystemLaunchedMain(context: Context, ownTaskId: Int) {
        val main = systemLaunchedMain?.get() ?: return
        systemLaunchedMain = null
        if (main.isFinishing || main.isDestroyed || main.taskId == ownTaskId) return
        val tasks = runCatching { context.getSystemService(android.app.ActivityManager::class.java).appTasks }
            .getOrDefault(emptyList())
        val task = tasks.firstOrNull { runCatching { it.taskInfo?.taskIdCompat() == main.taskId }.getOrDefault(false) }
        val removed = task != null && runCatching { task.finishAndRemoveTask() }.isSuccess
        io.github.fartown.movo.core.AndroidAgentLogger.info(
            "Conversation handoff: replaced system-launched main task=${main.taskId} removed=$removed",
        )
    }
}

private fun android.app.ActivityManager.RecentTaskInfo.taskIdCompat(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) taskId else @Suppress("DEPRECATION") id
