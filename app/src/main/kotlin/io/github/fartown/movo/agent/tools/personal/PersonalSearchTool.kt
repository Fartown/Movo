package io.github.fartown.movo.agent.tools.personal

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
import org.json.JSONArray
import org.json.JSONObject

/**
 * 个人数据来源。每个来源声明：是否支持时间过滤、是否 secret、以及在当前环境是否可用
 * （Root / ColorOS / 通知权）。
 */
internal enum class PersonalSource(
    val supportsTimeFilter: Boolean,
    val secret: Boolean = false,
) {
    NOTIFICATIONS(supportsTimeFilter = true),
    CONTACTS(supportsTimeFilter = false),
    CALL_LOG(supportsTimeFilter = true),
    SMS(supportsTimeFilter = true),
    CALENDAR(supportsTimeFilter = true),
    NOTES(supportsTimeFilter = false),
    RECORDING_SUMMARIES(supportsTimeFilter = false),
    COLOROS_MEMORY(supportsTimeFilter = true),
    PLACES(supportsTimeFilter = false),
    ORDERS(supportsTimeFilter = true),
    CLIPBOARD_HISTORY(supportsTimeFilter = true, secret = true),
    ;

    /** 当前环境下该来源是否可读。clipboard 还依赖受支持输入法，运行时才知道，这里只看 Root。 */
    fun isAvailable(env: ToolEnvironment): Boolean = when (this) {
        NOTIFICATIONS -> env.rootAvailable || env.notificationAccess
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
) : ToolOutput

/** 后端查询结果：命中条目 + 下一页游标；来源级失败走 [error]，部分失败进 [warnings]。 */
internal data class PersonalSearchResult(
    val items: List<PersonalItem> = emptyList(),
    val nextCursor: String? = null,
    val warnings: List<ToolWarning> = emptyList(),
    val error: ToolError? = null,
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
        "检索本机个人记录。source 必选（如 sms、contacts、calendar、notifications 等）；query 关键词；" +
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
            string(
                "source", "数据来源，必选。since/until 仅部分来源支持。",
                required = true, enum = sources.map { it.wire },
            )
            string("query", "关键词，匹配标题/正文，最长 200", maxLength = MAX_QUERY)
            string("since", "起始时间（ISO 8601 或毫秒），仅支持时间过滤的来源可用")
            string("until", "结束时间（ISO 8601 或毫秒），仅支持时间过滤的来源可用")
            string("app", "按应用过滤（仅 notifications、orders）")
            integer("limit", "返回条数，1–30，默认 10", min = 1, max = MAX_LIMIT.toLong())
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
            ),
        )
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
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 30
        const val MAX_QUERY = 200
    }
}
