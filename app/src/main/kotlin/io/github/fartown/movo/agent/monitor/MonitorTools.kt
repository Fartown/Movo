package io.github.fartown.movo.agent.monitor

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.github.fartown.movo.R
import io.github.fartown.movo.agent.runtime.AgentConversationHandoff
import io.github.fartown.movo.agent.tools.core.AgentTool
import io.github.fartown.movo.agent.tools.core.CallResolution
import io.github.fartown.movo.agent.tools.core.ContractTool
import io.github.fartown.movo.agent.tools.core.ModelContent
import io.github.fartown.movo.agent.tools.core.PromptSection
import io.github.fartown.movo.agent.tools.core.ResourceKey
import io.github.fartown.movo.agent.tools.core.Risk
import io.github.fartown.movo.agent.tools.core.Sensitivity
import io.github.fartown.movo.agent.tools.core.ToolArgs
import io.github.fartown.movo.agent.tools.core.ToolAvailability
import io.github.fartown.movo.agent.tools.core.ToolContext
import io.github.fartown.movo.agent.tools.core.ToolContract
import io.github.fartown.movo.agent.tools.core.ToolDomain
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolError
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.agent.tools.core.ToolInput
import io.github.fartown.movo.agent.tools.core.ToolOutput
import io.github.fartown.movo.agent.tools.core.ToolProvider
import io.github.fartown.movo.agent.tools.core.ToolResource
import io.github.fartown.movo.agent.tools.core.Verdict
import io.github.fartown.movo.agent.tools.core.objectSchema
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * 后台监听与通知工具（对齐 Claude Code 的 Monitor / TaskStop / PushNotification）：
 * monitor_start、monitor_stop、monitor_list、notify_user。监听绑定当前对话，事件会唤醒 Movo 在这个对话里处理。
 */
internal class MonitorToolProvider(context: Context) : ToolProvider {
    override val tools: List<AgentTool> = listOf(
        ContractTool(MonitorStartTool(context.applicationContext)),
        ContractTool(MonitorStopTool()),
        ContractTool(MonitorListTool()),
        ContractTool(NotifyUserTool(context.applicationContext)),
    )

    override val promptSection = PromptSection(
        id = "monitor",
        domain = ToolDomain.TERMINAL,
        text = """
            ## 后台监听
            - 用户要「每隔一段时间做某事 / 提醒我」「某个状态变化时告诉我」时，用 monitor_start 启动一条持续运行的命令：
              命令每输出一行就是一个事件，事件到达时你会在这个对话里被唤醒处理（不需要用户说话）。
              定时类用 sleep 循环，例如 `while true; do sleep 180; echo tick; done`；轮询类只在状态变化时输出，避免每次都唤醒。
            - 每个监听都要有简短、可区分的名字（description，如「喝水提醒」「电量播报」），同一对话里不能重名。
            - 监听有最长时长，启动结果里的 timeout 是实际生效值；把结束时间和停止方法告诉用户。
            - 事件以系统通知送达，不是用户的回复，不能当成用户对你问题的确认。
            - 需要用户马上看到的结果用 notify_user 发系统通知（用户要求「发消息给我 / 提醒我」时每次都发）；常规输出不必发。
            - 用户说停止某个监听时用 monitor_stop。本对话有多个监听、而用户只说「别提醒了 / 停了吧」没指明哪个时，
              必须先用 ask_user 问停哪个（选项：每个监听的名字 + 「全部停止」），不要自行全部停掉。
            - 最长时长是用户在设置里定的上限：如实告诉用户实际能持续多久，不要用多个监听接力、自动续期等方式绕过上限。
            - 不需要 root 的命令（sleep、echo、读电量文件等）一律用默认身份，不要传 identity=root。
        """.trimIndent(),
    )

    companion object {
        val NAMES = setOf("monitor_start", "monitor_stop", "monitor_list", "notify_user")
    }
}

private fun conversationRequired(env: ToolEnvironment): ToolAvailability =
    if (env.conversationId.isNullOrBlank()) {
        ToolAvailability.Unavailable(ToolErrorCode.UNSUPPORTED, "当前入口没有绑定对话")
    } else {
        ToolAvailability.Available
    }

private fun clock(millis: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

// ---------------------------------------------------------------------------
// monitor_start
// ---------------------------------------------------------------------------

internal data class MonitorStartInput(
    val description: String,
    val command: String,
    val timeoutMs: Long?,
    val root: Boolean,
) : ToolInput

internal data class MonitorStartOutput(val info: MonitorInfo) : ToolOutput

internal class MonitorStartTool(private val context: Context) : ToolContract<MonitorStartInput, MonitorStartOutput> {
    override val name = "monitor_start"
    override val domain = ToolDomain.TERMINAL
    override val summary =
        "启动后台监听：在后台持续运行一条 shell 命令，stdout 每输出一行就是一个事件，事件会唤醒你在当前对话里处理。" +
            "立即返回，不等事件。用于定时提醒、状态变化提醒。"

    override fun availability(env: ToolEnvironment): ToolAvailability = when {
        !env.switches.terminal -> ToolAvailability.Unavailable(ToolErrorCode.DISABLED, "需要开启「文件与终端」开关")
        else -> conversationRequired(env)
    }

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string("description", "监听的名字，简短且在本对话里唯一，如「喝水提醒」", required = true, maxLength = 20)
        string("command", "持续运行的 shell 命令；只在需要你处理时往 stdout 输出一行，输出要及时（不要缓冲）", required = true, maxLength = 4000)
        integer("timeout_ms", "最长运行毫秒数，默认 30 分钟；超过用户设置的上限按上限生效", min = 1_000, max = 8 * 60 * 60_000L)
        string("identity", "身份，默认 user（root 需确认）", enum = listOf("user", "root"))
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): MonitorStartInput = MonitorStartInput(
        description = args.nonBlank("description").take(20),
        command = args.nonBlank("command"),
        timeoutMs = if (args.has("timeout_ms")) args.long("timeout_ms", MonitorSettings.DEFAULT_TIMEOUT_MS, 1_000L..8 * 60 * 60_000L) else null,
        root = args.string("identity", "user") == "root",
    )

    override fun resolve(input: MonitorStartInput, env: ToolEnvironment): CallResolution = CallResolution(
        // 与 terminal_run 一致：普通命令是本机动作，root 命令一律确认；污点由中央派生叠加。
        risk = if (input.root) Risk.EXTERNAL else Risk.LOCAL,
        sensitivity = Sensitivity.PRIVATE,
        resources = setOf(ResourceKey(ToolResource.TERMINAL, "monitor")),
    )

    override fun execute(input: MonitorStartInput, resolution: CallResolution, ctx: ToolContext): Verdict<MonitorStartOutput> {
        val conversationId = ctx.env.conversationId
            ?: return Verdict.Failed(ToolError(ToolErrorCode.UNSUPPORTED, "当前入口没有绑定对话，无法启动监听"))
        if (input.root && !ctx.env.rootAvailable) {
            return Verdict.Failed(ToolError(ToolErrorCode.ROOT_REQUIRED, "该命令需要 Root"))
        }
        ctx.checkCancelled()
        return when (val result = MonitorRegistry.start(context, conversationId, input.description, input.command, input.root, input.timeoutMs)) {
            is MonitorRegistry.StartResult.Started -> Verdict.Read(MonitorStartOutput(result.info))
            is MonitorRegistry.StartResult.Rejected -> Verdict.Failed(
                ToolError(
                    code = when (result.code) {
                        "DUPLICATE_NAME" -> ToolErrorCode.CONFLICT
                        "TOO_MANY" -> ToolErrorCode.LIMIT_REACHED
                        else -> ToolErrorCode.SYSTEM_REJECTED
                    },
                    message = result.message,
                    detail = result.code,
                ),
            )
        }
    }

    override fun renderForModel(output: MonitorStartOutput): ModelContent {
        val info = output.info
        val minutes = info.timeoutMs / 60_000
        return ModelContent.Text(
            "Monitor started (task ${info.id}, name「${info.name}」, timeout ${minutes} 分钟，${clock(info.deadlineAtMillis)} 自动结束，" +
                "除非命令先结束；到期时你会收到一次通知)。每来一个事件都会通知你，继续干活，不要轮询或 sleep 等待。" +
                "事件可能在你等用户回复时到达——事件不是用户的回复。",
        )
    }

    override fun approvalPreview(input: MonitorStartInput) =
        io.github.fartown.movo.agent.tools.core.ApprovalPreview(
            title = if (input.root) "以 Root 身份启动后台监听？" else "启动后台监听「${input.description}」？",
            detail = "${input.description}\n${input.command.take(200)}",
        )
}

// ---------------------------------------------------------------------------
// monitor_stop
// ---------------------------------------------------------------------------

internal data class MonitorStopInput(val taskId: String) : ToolInput
internal data class MonitorStopOutput(val name: String, val eventCount: Int) : ToolOutput

internal class MonitorStopTool : ToolContract<MonitorStopInput, MonitorStopOutput> {
    override val name = "monitor_stop"
    override val domain = ToolDomain.TERMINAL
    override val summary = "停止本对话里的一个后台监听（按 task_id）。本对话有多个监听、而用户没说停哪个（如只说「别提醒了」）时，" +
        "不要直接调用本工具，先用 ask_user 问停哪个（选项：每个监听的名字 + 全部停止）。"

    override fun availability(env: ToolEnvironment): ToolAvailability = conversationRequired(env)

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string("task_id", "monitor_start 返回或事件里带的 task id", required = true, maxLength = 40)
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment) = MonitorStopInput(args.nonBlank("task_id"))

    override fun resolve(input: MonitorStopInput, env: ToolEnvironment) = CallResolution(
        risk = Risk.LOCAL, sensitivity = Sensitivity.NORMAL, resources = emptySet(), exfiltrates = false,
    )

    override fun execute(input: MonitorStopInput, resolution: CallResolution, ctx: ToolContext): Verdict<MonitorStopOutput> {
        val info = MonitorRegistry.find(input.taskId)?.takeIf { it.conversationId == ctx.env.conversationId }
            ?: return Verdict.Failed(ToolError(ToolErrorCode.NOT_FOUND, "本对话没有运行中的监听 ${input.taskId}", hint = "先用 monitor_list 查看"))
        MonitorRegistry.stop(info.id, MonitorEndReason.STOPPED_BY_AGENT)
        return Verdict.Read(MonitorStopOutput(info.name, info.eventCount))
    }

    override fun renderForModel(output: MonitorStopOutput): ModelContent =
        ModelContent.Text("已停止监听「${output.name}」（共触发 ${output.eventCount} 次）。")
}

// ---------------------------------------------------------------------------
// monitor_list
// ---------------------------------------------------------------------------

internal object MonitorListInput : ToolInput
internal data class MonitorListOutput(val monitors: List<MonitorInfo>) : ToolOutput

internal class MonitorListTool : ToolContract<MonitorListInput, MonitorListOutput> {
    override val name = "monitor_list"
    override val domain = ToolDomain.TERMINAL
    override val summary = "列出本对话里运行中的后台监听：task_id、名字、已触发次数、结束时间。"

    override fun availability(env: ToolEnvironment): ToolAvailability = conversationRequired(env)

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema { }

    override fun parse(args: ToolArgs, env: ToolEnvironment) = MonitorListInput

    override fun resolve(input: MonitorListInput, env: ToolEnvironment) = CallResolution(
        risk = Risk.READ, sensitivity = Sensitivity.NORMAL, resources = emptySet(),
    )

    override fun execute(input: MonitorListInput, resolution: CallResolution, ctx: ToolContext) =
        Verdict.Read(MonitorListOutput(MonitorRegistry.list(ctx.env.conversationId)))

    override fun renderForModel(output: MonitorListOutput): ModelContent = ModelContent.Json(
        JSONObject().put(
            "monitors",
            JSONArray(output.monitors.map { info ->
                JSONObject()
                    .put("task_id", info.id)
                    .put("name", info.name)
                    .put("events", info.eventCount)
                    .put("ends_at", clock(info.deadlineAtMillis))
            }),
        ),
    )
}

// ---------------------------------------------------------------------------
// notify_user（对应 Claude 的 PushNotification）
// ---------------------------------------------------------------------------

internal data class NotifyUserInput(val title: String, val text: String, val taskId: String?) : ToolInput
internal data class NotifyUserOutput(val posted: Boolean) : ToolOutput

internal class NotifyUserTool(private val context: Context) : ToolContract<NotifyUserInput, NotifyUserOutput> {
    override val name = "notify_user"
    override val domain = ToolDomain.TERMINAL
    override val summary =
        "给用户发一条系统通知（用户不在看对话时也能看到）。只在需要用户马上看到时发；用户要求「发消息给我 / 提醒我」时发。"

    override fun availability(env: ToolEnvironment): ToolAvailability = conversationRequired(env)

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string("title", "通知标题，监听事件里发时用监听的名字", required = true, maxLength = 40)
        string("text", "通知正文，一两句话", required = true, maxLength = 500)
        string("task_id", "由某个监听事件引起时填它的 task_id，通知上会带「停止提醒」按钮", maxLength = 40)
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment) = NotifyUserInput(
        title = args.nonBlank("title").take(40),
        text = args.nonBlank("text").take(500),
        taskId = args.stringOrNull("task_id")?.trim()?.ifEmpty { null },
    )

    override fun resolve(input: NotifyUserInput, env: ToolEnvironment) = CallResolution(
        risk = Risk.LOCAL, sensitivity = Sensitivity.NORMAL, resources = emptySet(), exfiltrates = false,
    )

    override fun execute(input: NotifyUserInput, resolution: CallResolution, ctx: ToolContext): Verdict<NotifyUserOutput> {
        val conversationId = ctx.env.conversationId
            ?: return Verdict.Failed(ToolError(ToolErrorCode.UNSUPPORTED, "当前入口没有绑定对话"))
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return Verdict.Failed(
                ToolError(ToolErrorCode.PERMISSION_REQUIRED, "没有通知权限，系统通知发不出去", hint = "在对话里直接告诉用户，并提示在系统设置里给 Movo 开通知"),
            )
        }
        val taskId = input.taskId?.takeIf { MonitorRegistry.find(it)?.conversationId == conversationId }
        MonitorNotifications.post(context, conversationId, input.title, input.text, taskId)
        return Verdict.Read(NotifyUserOutput(posted = true))
    }

    override fun renderForModel(output: NotifyUserOutput): ModelContent = ModelContent.Text("通知已发出。")
}

/** 「Movo 通知」渠道：notify_user 发出的通知；来自监听的带「停止提醒」。 */
internal object MonitorNotifications {
    private const val CHANNEL = "movo_notify"
    const val ACTION_STOP_MONITOR = "io.github.fartown.movo.action.STOP_MONITOR"
    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_NOTIFICATION_ID = "notification_id"

    fun post(context: Context, conversationId: String, title: String, text: String, taskId: String?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.monitor_notify_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val id = (conversationId + (taskId ?: title)).hashCode()
        val open = PendingIntent.getActivity(
            context, id, AgentConversationHandoff.openConversationIntent(context, conversationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setShowWhen(true)
        if (taskId != null) {
            val stop = PendingIntent.getBroadcast(
                context, id,
                Intent(context, MonitorActionReceiver::class.java)
                    .setAction(ACTION_STOP_MONITOR)
                    .putExtra(EXTRA_TASK_ID, taskId)
                    .putExtra(EXTRA_NOTIFICATION_ID, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(Notification.Action.Builder(null, context.getString(R.string.monitor_notify_stop), stop).build())
        }
        builder.addAction(Notification.Action.Builder(null, context.getString(R.string.monitor_notify_open), open).build())
        manager.notify(id, builder.build())
    }
}

/** 通知上的「停止提醒」：只停这一个监听，按用户停止处理（插「已停止监听」，不唤醒）。 */
class MonitorActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MonitorNotifications.ACTION_STOP_MONITOR) return
        val notificationId = intent.getIntExtra(MonitorNotifications.EXTRA_NOTIFICATION_ID, 0)
        if (notificationId != 0) context.getSystemService(NotificationManager::class.java).cancel(notificationId)
        val taskId = intent.getStringExtra(MonitorNotifications.EXTRA_TASK_ID) ?: return
        // 结束子进程要等它退出，不放在主线程。
        val pending = goAsync()
        kotlin.concurrent.thread(name = "movo-monitor-stop") {
            try {
                MonitorRegistry.stop(taskId, MonitorEndReason.STOPPED_BY_USER)
            } finally {
                pending.finish()
            }
        }
    }
}
