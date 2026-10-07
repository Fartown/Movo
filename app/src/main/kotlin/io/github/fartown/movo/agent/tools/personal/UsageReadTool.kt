package io.github.fartown.movo.agent.tools.personal

import io.github.fartown.movo.agent.tools.core.ToolUiBlock
import io.github.fartown.movo.agent.tools.core.ToolUiView
import io.github.fartown.movo.agent.tools.core.forTitle
import io.github.fartown.movo.agent.tools.core.uiDuration
import io.github.fartown.movo.agent.tools.core.uiText
import io.github.fartown.movo.agent.tools.core.uiTime
import io.github.fartown.movo.agent.tools.core.CallResolution
import io.github.fartown.movo.agent.tools.core.ModelContent
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
import io.github.fartown.movo.agent.tools.core.Verdict
import io.github.fartown.movo.agent.tools.core.invalidArgs
import io.github.fartown.movo.agent.tools.core.objectSchema
import org.json.JSONArray
import org.json.JSONObject

internal enum class UsageView { RECENT, SUMMARY }

internal data class UsageReadInput(
    val view: UsageView,
    val sinceMillis: Long,
    val untilMillis: Long,
    val packageName: String?,
    val limit: Int,
) : ToolInput

/** 一条使用记录：recent 用 activity/resumed_at，summary 用 foreground_ms/last_used_at。 */
internal data class UsageItem(
    val packageName: String,
    val appName: String,
    val activity: String? = null,
    val resumedAtMillis: Long? = null,
    val foregroundMs: Long? = null,
    val lastUsedAtMillis: Long? = null,
)

internal data class UsageReadOutput(
    val view: UsageView,
    val items: List<UsageItem>,
    /** 实际查的时间窗（不传 since/until 时是最近 24 小时），回给模型。 */
    val sinceMillis: Long = 0,
    val untilMillis: Long = 0,
) : ToolOutput

/** 可测后端：读应用使用情况。summary 按事件累计，不用 INTERVAL_DAILY 桶（避免小时级误差）。 */
internal interface UsageReadBackend {
    fun available(env: ToolEnvironment): Boolean
    fun recent(startMillis: Long, endMillis: Long, packageName: String?, limit: Int): List<UsageItem>
    fun summary(startMillis: Long, endMillis: Long, packageName: String?, limit: Int): List<UsageItem>
}

/**
 * usage_read（只读，private）：recent 最近打开顺序，summary 按前台时长汇总。
 * since/until 取代旧的 hours；缺省看最近 24 小时。需使用情况权，缺权时目录层隐藏。
 */
internal class UsageReadTool(
    private val backend: UsageReadBackend,
) : ToolContract<UsageReadInput, UsageReadOutput> {
    override val name = "usage_read"
    override val domain = ToolDomain.PERSONAL
    override val summary =
        "应用使用情况统计：哪些 App 最近打开过、各 App 用了多久/哪个最常用。view=recent 最近打开顺序，" +
            "view=summary 按前台时长汇总。可选 since/until、package；limit 默认 20。"

    override fun availability(env: ToolEnvironment): ToolAvailability =
        if (env.usageAccess) {
            ToolAvailability.Available
        } else {
            ToolAvailability.Unavailable(
                ToolErrorCode.PERMISSION_REQUIRED,
                "读取应用使用情况需要「使用情况访问」权限",
            )
        }

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string("view", "recent 或 summary", required = true, enum = UsageView.entries.map { it.name.lowercase() })
        string("since", "起始时间（ISO 8601 或毫秒），默认 24 小时前")
        string("until", "结束时间（ISO 8601 或毫秒），默认现在")
        string("package", "只看某个应用的包名")
        integer("limit", "返回条数，1–50，默认 20", min = 1, max = MAX_LIMIT.toLong())
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): UsageReadInput {
        val now = System.currentTimeMillis()
        val since = args.stringOrNull("since")?.let {
            parseIsoOrMillis(it) ?: invalidArgs("since 不是有效的时间：$it")
        } ?: (now - DEFAULT_WINDOW_MS)
        val until = args.stringOrNull("until")?.let {
            parseIsoOrMillis(it) ?: invalidArgs("until 不是有效的时间：$it")
        } ?: now
        if (since >= until) invalidArgs("since 必须早于 until")
        return UsageReadInput(
            view = args.enum<UsageView>("view"),
            sinceMillis = since,
            untilMillis = until,
            packageName = args.stringOrNull("package")?.trim()?.takeIf { it.isNotEmpty() },
            limit = args.int("limit", DEFAULT_LIMIT, 1..MAX_LIMIT),
        )
    }

    override fun resolve(input: UsageReadInput, env: ToolEnvironment): CallResolution =
        CallResolution(risk = Risk.READ, sensitivity = Sensitivity.PRIVATE, resources = emptySet())

    override fun execute(
        input: UsageReadInput,
        resolution: CallResolution,
        ctx: ToolContext,
    ): Verdict<UsageReadOutput> {
        if (!backend.available(ctx.env)) {
            return Verdict.Failed(
                ToolError(ToolErrorCode.PERMISSION_REQUIRED, "缺少使用情况访问权，无法读取"),
            )
        }
        ctx.checkCancelled()
        val items = runCatching {
            when (input.view) {
                UsageView.RECENT -> backend.recent(input.sinceMillis, input.untilMillis, input.packageName, input.limit)
                UsageView.SUMMARY -> backend.summary(input.sinceMillis, input.untilMillis, input.packageName, input.limit)
            }
        }.getOrElse {
            return Verdict.Failed(ToolError(ToolErrorCode.SOURCE_UNAVAILABLE, "系统未返回应用使用记录"))
        }
        return Verdict.Read(UsageReadOutput(input.view, items, input.sinceMillis, input.untilMillis))
    }

    override fun uiTitle(input: UsageReadInput): String {
        val app = input.packageName?.let { "「${io.github.fartown.movo.agent.tools.ui.appLabel(it)}」" }.orEmpty()
        return when (input.view) {
            UsageView.SUMMARY -> "查看${app}使用时长"
            UsageView.RECENT -> "查看最近用过的应用".takeIf { app.isEmpty() } ?: "查看最近用${app}的记录"
        }
    }

    override fun renderForUi(input: UsageReadInput, output: UsageReadOutput): ToolUiView {
        val items = output.items.map { item ->
            when (input.view) {
                UsageView.SUMMARY -> ToolUiBlock.Item(
                    title = item.appName,
                    trailing = item.foregroundMs?.let { uiDuration(it) },
                    icon = item.packageName,
                )
                UsageView.RECENT -> ToolUiBlock.Item(
                    title = item.appName,
                    trailing = item.resumedAtMillis?.let { uiTime(it) },
                    icon = item.packageName,
                )
            }
        }
        val total = output.items.mapNotNull { it.foregroundMs }.sum()
        return ToolUiView(
            summary = when {
                items.isEmpty() -> "没有记录"
                input.view == UsageView.SUMMARY && total > 0 -> "共 ${uiDuration(total)}"
                else -> "${items.size} 条"
            },
            blocks = listOf(ToolUiBlock.Items(items)).filter { items.isNotEmpty() },
            transient = true,
        )
    }

    override fun renderForModel(output: UsageReadOutput): ModelContent {
        val array = JSONArray()
        output.items.forEach { item ->
            val obj = JSONObject()
                .put("package", item.packageName)
                .put("app_name", item.appName)
            when (output.view) {
                UsageView.RECENT -> {
                    item.activity?.let { obj.put("activity", it) }
                    item.resumedAtMillis?.let { obj.put("resumed_at", isoOf(it)) }
                }
                UsageView.SUMMARY -> {
                    item.foregroundMs?.let { obj.put("foreground_ms", it) }
                    item.lastUsedAtMillis?.let { obj.put("last_used_at", isoOf(it)) }
                }
            }
            array.put(obj)
        }
        val json = JSONObject()
            .put("view", output.view.name.lowercase())
            .put("items", array)
            .put("count", output.items.size)
        // 写明查的是哪段时间（旧版回 window_hours）：模型不会把「最近 24 小时没打开」说成「今天没用过」。
        if (output.untilMillis > output.sinceMillis) {
            json.put("since", isoOf(output.sinceMillis))
                .put("until", isoOf(output.untilMillis))
                .put("window_hours", (output.untilMillis - output.sinceMillis + HOUR_MS / 2) / HOUR_MS)
        }
        return ModelContent.Json(json)
    }

    private companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 50
        const val HOUR_MS = 60L * 60 * 1_000
        const val DEFAULT_WINDOW_MS = 24 * HOUR_MS
    }
}
