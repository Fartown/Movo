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
import io.github.fartown.movo.agent.tools.core.ToolWarning
import io.github.fartown.movo.agent.tools.core.Verdict
import io.github.fartown.movo.agent.tools.core.fail
import io.github.fartown.movo.agent.tools.core.invalidArgs
import io.github.fartown.movo.agent.tools.core.objectSchema
import io.github.fartown.movo.data.repository.NotificationHistoryRepository.Companion.RETENTION_DAYS
import org.json.JSONArray
import org.json.JSONObject

/**
 * 个人数据来源。每个来源声明：是否支持时间过滤、是否 secret、以及在当前环境是否可用
 * （Root / ColorOS / 通知权）。supportsTimeFilter 只给后端真的按 since/until 过滤的来源标 true：
 * 系统记忆（coloros_memory）的查询（Hook 与 Root 快照共用 ColorOsMemoryDatabaseQuery）没有时间条件，不标。
 * notifications 是 Movo 记下的通知历史（含已划掉的），notification_bar 是通知栏里现在还挂着的通知（旧 recent_notifications）。
 */
internal enum class PersonalSource(
    val supportsTimeFilter: Boolean,
    val secret: Boolean = false,
) {
    NOTIFICATIONS(supportsTimeFilter = true),
    NOTIFICATION_BAR(supportsTimeFilter = true),
    CONTACTS(supportsTimeFilter = false),
    CALL_LOG(supportsTimeFilter = true),
    SMS(supportsTimeFilter = true),
    CALENDAR(supportsTimeFilter = true),
    NOTES(supportsTimeFilter = false),
    RECORDING_SUMMARIES(supportsTimeFilter = false),
    COLOROS_MEMORY(supportsTimeFilter = false),
    PLACES(supportsTimeFilter = false),
    ORDERS(supportsTimeFilter = true),
    CLIPBOARD_HISTORY(supportsTimeFilter = true, secret = true),
    ;

    /** 当前环境下该来源是否可读。clipboard 还依赖受支持输入法，运行时才知道，这里只看 Root。 */
    fun isAvailable(env: ToolEnvironment): Boolean = when (this) {
        NOTIFICATIONS -> env.rootAvailable || env.notificationAccess
        NOTIFICATION_BAR -> env.notificationAccess
        CONTACTS, CALL_LOG, SMS, CALENDAR, CLIPBOARD_HISTORY -> env.rootAvailable
        NOTES, RECORDING_SUMMARIES, COLOROS_MEMORY, PLACES -> env.rootAvailable && env.colorOs
        ORDERS -> env.notificationAccess || (env.rootAvailable && env.colorOs)
    }

    val wire: String get() = name.lowercase()

    companion object {
        fun available(env: ToolEnvironment): List<PersonalSource> = entries.filter { it.isAvailable(env) }

        fun fromWire(value: String): PersonalSource? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

internal data class PersonalSearchInput(
    val source: PersonalSource,
    val query: String?,
    val sinceMillis: Long?,
    val untilMillis: Long?,
    val app: String?,
    val limit: Int,
    val cursor: String?,
) : ToolInput

/** 统一记录结构：各来源归一到这几列，原始多子表塞进 [extra]。 */
internal data class PersonalItem(
    val id: String?,
    val timeMillis: Long?,
    val title: String?,
    val text: String?,
    val from: String?,
    val uri: String?,
    val extra: JSONObject? = null,
)

internal data class PersonalSearchOutput(
    val source: PersonalSource,
    val items: List<PersonalItem>,
    val nextCursor: String?,
    val toolWarnings: List<ToolWarning>,
    val meta: JSONObject? = null,
) : ToolOutput

/** 后端查询结果：命中条目 + 下一页游标；来源级失败走 [error]，部分失败进 [warnings]。 */
internal data class PersonalSearchResult(
    val items: List<PersonalItem> = emptyList(),
    val nextCursor: String? = null,
    val warnings: List<ToolWarning> = emptyList(),
    val error: ToolError? = null,
    /** 结果的附加说明（覆盖范围、按应用名匹配到的包名等），原样并进给模型的结果。 */
    val meta: JSONObject? = null,
)

/** 可测后端：按来源读本机数据，统一归一成 [PersonalItem]。 */
internal interface PersonalSearchBackend {
    fun search(input: PersonalSearchInput, env: ToolEnvironment): PersonalSearchResult
}

/**
 * personal_search（只读）：在授权的本机个人数据里检索，返回统一结构记录。
 * - source 必选，schema 只列当前可用来源；所有来源都不可用时整体 Unavailable（目录层隐藏）。
 * - since/until 仅对声明支持时间过滤的来源可用，否则 INVALID_ARGUMENTS。
 * - 短信/通知正文里的验证码等 secret 内容打码（绕不开 sms_code_read）。
 */
internal class PersonalSearchTool(
    private val backend: PersonalSearchBackend,
) : ToolContract<PersonalSearchInput, PersonalSearchOutput> {
    override val name = "personal_search"
    override val domain = ToolDomain.PERSONAL
    override val summary =
        "检索本机个人记录。source 必选（如 sms、contacts、calendar、notifications 通知历史、notification_bar 当前通知栏等）；query 关键词；" +
            "部分来源支持 since/until 过滤；limit 1–30；cursor 翻页。可读文件/图片/录音用 file_search。"

    override fun availability(env: ToolEnvironment): ToolAvailability =
        if (PersonalSource.available(env).isNotEmpty()) {
            ToolAvailability.Available
        } else {
            ToolAvailability.Unavailable(
                ToolErrorCode.PERMISSION_REQUIRED,
                "没有可用的个人数据来源：需要 Root、ColorOS 或通知/使用情况权限",
            )
        }

    override fun schema(env: ToolEnvironment): JSONObject {
        val sources = PersonalSource.available(env)
        return objectSchema {
            // 写明哪些来源能按时间过滤：模型不用试错，也不会以为别的来源也筛了时间。
            val timed = sources.filter { it.supportsTimeFilter }.joinToString("、") { it.wire }.ifEmpty { "无" }
            // 两个通知来源要分得清：问「现在有哪些通知」用 notification_bar，问「之前那条通知」用 notifications。
            val notes = listOfNotNull(
                "notifications 是 Movo 记下的最近 $RETENTION_DAYS 天通知历史（含已划掉的）。"
                    .takeIf { PersonalSource.NOTIFICATIONS in sources },
                "notification_bar 是通知栏里现在还挂着的通知。".takeIf { PersonalSource.NOTIFICATION_BAR in sources },
            ).joinToString("")
            string(
                "source", "数据来源，必选。since/until 仅部分来源支持。$notes",
                required = true, enum = sources.map { it.wire },
            )
            string("query", "关键词，匹配标题/正文，最长 200", maxLength = MAX_QUERY)
            string("since", "起始时间（ISO 8601 或毫秒），仅这些来源可用：$timed。不给时 notifications 查最近 24 小时、orders 查最近 7 天")
            string("until", "结束时间（ISO 8601 或毫秒），仅这些来源可用：$timed")
            string("app", "按应用过滤：包名或应用名，如 com.tencent.mm 或 微信（仅 notifications、notification_bar、orders）")
            integer("limit", "返回条数，1–30，默认 $DEFAULT_LIMIT", min = 1, max = MAX_LIMIT.toLong())
            string("cursor", "翻页游标，来自上一次返回的 next_cursor")
        }
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): PersonalSearchInput {
        val wire = args.nonBlank("source")
        val source = PersonalSource.fromWire(wire)
            ?: invalidArgs("未知来源：$wire")
        if (!source.isAvailable(env)) {
            fail(
                ToolErrorCode.SOURCE_UNAVAILABLE,
                "来源 ${source.wire} 当前不可用",
                hint = "改用当前可用的来源；该来源可能需要 Root、ColorOS 或相应权限",
            )
        }
        val query = args.stringOrNull("query")?.trim()?.takeIf { it.isNotEmpty() }
        if (query != null && query.length > MAX_QUERY) invalidArgs("query 最长 $MAX_QUERY 字")

        val since = args.stringOrNull("since")?.let {
            parseIsoOrMillis(it) ?: invalidArgs("since 不是有效的时间：$it")
        }
        val until = args.stringOrNull("until")?.let {
            parseIsoOrMillis(it) ?: invalidArgs("until 不是有效的时间：$it")
        }
        if ((since != null || until != null) && !source.supportsTimeFilter) {
            invalidArgs(
                "来源 ${source.wire} 不支持时间过滤",
                "去掉 since/until，或改用支持时间过滤的来源",
            )
        }
        val app = args.stringOrNull("app")?.trim()?.takeIf { it.isNotEmpty() }
        return PersonalSearchInput(
            source = source,
            query = query,
            sinceMillis = since,
            untilMillis = until,
            app = app,
            limit = args.int("limit", DEFAULT_LIMIT, 1..MAX_LIMIT),
            cursor = args.stringOrNull("cursor"),
        )
    }

    override fun resolve(input: PersonalSearchInput, env: ToolEnvironment): CallResolution = CallResolution(
        risk = Risk.READ,
        sensitivity = if (input.source.secret) Sensitivity.SECRET else Sensitivity.PRIVATE,
        resources = emptySet(),
    )

    override fun execute(
        input: PersonalSearchInput,
        resolution: CallResolution,
        ctx: ToolContext,
    ): Verdict<PersonalSearchOutput> {
        ctx.checkCancelled()
        val result = runCatching { backend.search(input, ctx.env) }.getOrElse {
            return Verdict.Failed(ToolError(ToolErrorCode.SOURCE_UNAVAILABLE, "个人数据源暂时不可访问"))
        }
        result.error?.let { return Verdict.Failed(it) }
        val masked = result.items.map { maskItem(it) }
        return Verdict.Read(
            PersonalSearchOutput(
                source = input.source,
                items = masked,
                nextCursor = result.nextCursor,
                toolWarnings = result.warnings,
                meta = result.meta,
            ),
        )
    }

    override fun uiTitle(input: PersonalSearchInput): String =
        "搜索${input.source.uiLabel()}" + input.query?.takeIf { it.isNotBlank() }?.let { "「${it.forTitle()}」" }.orEmpty()

    /** 只列标题和时间，不显示正文（工具可视化方案 §6 决策 2）；整步只在本次运行中显示。 */
    override fun renderForUi(input: PersonalSearchInput, output: PersonalSearchOutput): ToolUiView {
        val items = output.items.map { item ->
            val title = item.title?.takeIf { it.isNotBlank() } ?: item.from?.takeIf { it.isNotBlank() } ?: "（无标题）"
            val subtitle = listOfNotNull(
                item.from?.takeIf { it.isNotBlank() && it != title },
                item.timeMillis?.let { uiTime(it) },
            ).joinToString(" · ").ifBlank { null }
            ToolUiBlock.Item(title, subtitle)
        }
        val more = if (output.nextCursor != null) " · 还有更多" else ""
        return ToolUiView(
            summary = if (items.isEmpty()) "没找到" else "找到 ${items.size} 条$more",
            blocks = listOf(ToolUiBlock.Items(items)).filter { items.isNotEmpty() },
            transient = true,
        )
    }

    private fun PersonalSource.uiLabel(): String = when (this) {
        PersonalSource.NOTIFICATIONS -> "通知"
        PersonalSource.NOTIFICATION_BAR -> "通知栏"
        PersonalSource.CONTACTS -> "通讯录"
        PersonalSource.CALL_LOG -> "通话记录"
        PersonalSource.SMS -> "短信"
        PersonalSource.CALENDAR -> "日程"
        PersonalSource.NOTES -> "便签"
        PersonalSource.RECORDING_SUMMARIES -> "录音摘要"
        PersonalSource.COLOROS_MEMORY -> "系统记忆"
        PersonalSource.PLACES -> "地点"
        PersonalSource.ORDERS -> "订单"
        PersonalSource.CLIPBOARD_HISTORY -> "剪贴板历史"
    }

    override fun renderForModel(output: PersonalSearchOutput): ModelContent {
        val array = JSONArray()
        output.items.forEach { item ->
            val obj = JSONObject()
            item.id?.let { obj.put("id", it) }
            item.timeMillis?.let { obj.put("time", isoOf(it)) }
            item.title?.let { obj.put("title", it) }
            item.text?.let { obj.put("text", it) }
            item.from?.let { obj.put("from", it) }
            item.uri?.let { obj.put("uri", it) }
            item.extra?.let { obj.put("extra", it) }
            array.put(obj)
        }
        val json = JSONObject()
            .put("source", output.source.wire)
            .put("items", array)
            .put("count", output.items.size)
        output.meta?.let { meta -> meta.keys().forEach { key -> json.put(key, meta.get(key)) } }
        output.nextCursor?.let { json.put("next_cursor", it) }
        return ModelContent.Json(json)
    }

    override fun warnings(output: PersonalSearchOutput): List<ToolWarning> = output.toolWarnings

    /** 对 secret 正文打码：短信、通知正文里的验证码等不整段泄露。 */
    private fun maskItem(item: PersonalItem): PersonalItem = item.copy(
        title = PersonalSecretPatterns.mask(item.title),
        text = PersonalSecretPatterns.mask(item.text),
    )

    private fun sourceLabel(source: PersonalSource): String = when (source) {
        PersonalSource.SMS -> "短信"
        PersonalSource.CALL_LOG -> "通话记录"
        else -> source.wire
    }

    private companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 30
        const val MAX_QUERY = 200
    }
}
