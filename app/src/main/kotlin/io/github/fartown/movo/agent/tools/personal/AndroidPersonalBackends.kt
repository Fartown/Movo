package io.github.fartown.movo.agent.tools.personal

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import io.github.fartown.movo.agent.device.AgentNotificationHistoryService
import io.github.fartown.movo.agent.device.BoundedRootCommandExecutor
import io.github.fartown.movo.agent.device.RootAccess
import io.github.fartown.movo.agent.tool.AgentColorOsMemoryTools
import io.github.fartown.movo.agent.tool.AgentPersonalContextTools
import io.github.fartown.movo.agent.tool.AgentPrivateDatabaseTools
import io.github.fartown.movo.agent.tool.PersonalDataContentParser
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolError
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.agent.tools.core.ToolWarning
import io.github.fartown.movo.data.repository.NotificationHistoryRepository
import java.util.Base64
import java.util.Locale
import org.json.JSONObject

// ---------------------------------------------------------------------------
// 翻页游标：绑定来源 + 查询条件。条件变了（游标与当前查询不匹配）→ 失效。
// TODO(快照绑定)：目前只按偏移翻页；真正"绑定数据快照、数据变动即失效"需额外记录快照代际。
// ---------------------------------------------------------------------------
internal object PersonalCursor {
    private const val VERSION = "v1"

    fun queryKey(input: PersonalSearchInput): String =
        listOf(
            input.source.name,
            input.query.orEmpty(),
            input.sinceMillis?.toString().orEmpty(),
            input.untilMillis?.toString().orEmpty(),
            input.app.orEmpty(),
            input.limit.toString(),
        ).joinToString("|").hashCode().toString()

    fun encode(input: PersonalSearchInput, offset: Int): String {
        val raw = "$VERSION|${input.source.name}|${queryKey(input)}|$offset"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray())
    }

    /** 返回偏移；游标非法或与当前查询不匹配返回 null（工具据此报 STALE_OBSERVATION）。 */
    fun decodeOffset(cursor: String, input: PersonalSearchInput): Int? {
        val decoded = runCatching { String(Base64.getUrlDecoder().decode(cursor)) }.getOrNull() ?: return null
        val parts = decoded.split("|")
        if (parts.size != 4 || parts[0] != VERSION) return null
        if (parts[1] != input.source.name || parts[2] != queryKey(input)) return null
        return parts[3].toIntOrNull()
    }

    // 锚点游标：供双来源合并（如订单）稳定翻页用。编码「上一页末尾的 时间 + 去重 key」，
    // identity 可能含分隔符，单独 Base64 一层再拼。
    private const val ANCHOR_VERSION = "a1"

    fun encodeAnchor(input: PersonalSearchInput, anchor: PageAnchor): String {
        val identity = Base64.getUrlEncoder().withoutPadding().encodeToString(anchor.identity.toByteArray())
        val raw = "$ANCHOR_VERSION|${input.source.name}|${queryKey(input)}|${anchor.timeMillis}|$identity"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray())
    }

    /** 返回锚点；游标非法或与当前查询不匹配返回 null（工具据此报 STALE_OBSERVATION）。 */
    fun decodeAnchor(cursor: String, input: PersonalSearchInput): PageAnchor? {
        val decoded = runCatching { String(Base64.getUrlDecoder().decode(cursor)) }.getOrNull() ?: return null
        val parts = decoded.split("|")
        if (parts.size != 5 || parts[0] != ANCHOR_VERSION) return null
        if (parts[1] != input.source.name || parts[2] != queryKey(input)) return null
        val time = parts[3].toLongOrNull() ?: return null
        val identity = runCatching { String(Base64.getUrlDecoder().decode(parts[4])) }.getOrNull() ?: return null
        return PageAnchor(time, identity)
    }
}

// ---------------------------------------------------------------------------
// personal_search 真实后端
// ---------------------------------------------------------------------------

/**
 * content query 可直达的 Provider 描述。把原始列归一到统一记录结构。
 */
private data class ProviderSpec(
    val uri: String,
    val projection: List<String>,
    val searchable: List<String>,
    val fixedWhere: String?,
    val timeColumn: String?,
    val sort: String,
    val idColumn: String?,
    val titleColumn: String?,
    val textColumn: String?,
    val fromColumn: String?,
    val uriTemplate: String? = null,
)

internal class AndroidPersonalSearchBackend(
    private val context: Context,
    private val root: BoundedRootCommandExecutor,
    private val rootAvailable: () -> Boolean = { RootAccess.isGranted },
) : PersonalSearchBackend {

    private val colorOsMemory by lazy { AgentColorOsMemoryTools(context, root) }
    private val privateDatabase by lazy { AgentPrivateDatabaseTools(context, root) }
    private val notificationHistory by lazy { NotificationHistoryRepository(context) }

    override fun search(input: PersonalSearchInput, env: ToolEnvironment): PersonalSearchResult =
        when (input.source) {
            PersonalSource.SMS,
            PersonalSource.CONTACTS,
            PersonalSource.CALL_LOG,
            PersonalSource.NOTES,
            -> contentQuery(SPECS.getValue(input.source), input)
            PersonalSource.CALENDAR -> calendar(input)
            PersonalSource.RECORDING_SUMMARIES -> recordingSummaries(input)
            PersonalSource.NOTIFICATIONS -> notifications(input)
            PersonalSource.CLIPBOARD_HISTORY -> clipboardHistory(input)
            PersonalSource.COLOROS_MEMORY -> colorOsMemorySearch(input)
            PersonalSource.PLACES -> places(input)
            PersonalSource.ORDERS -> orders(input)
        }

    // ---- content query 直达来源 ----

    private fun contentQuery(spec: ProviderSpec, input: PersonalSearchInput): PersonalSearchResult {
        if (!rootAvailable()) {
            return PersonalSearchResult(error = ToolError(ToolErrorCode.ROOT_REQUIRED, "该来源需要 Root 授权，本次未执行"))
        }
        val offset = offsetOrStale(input) ?: return staleCursor()
        val where = combineWhere(
            spec.fixedWhere,
            input.query?.takeIf { it.isNotBlank() }?.let { spec.searchable.likeClause(it) },
            timeWhere(spec.timeColumn, input.sinceMillis, input.untilMillis),
        )
        return when (val outcome = queryRows(spec.uri, spec.projection, where, spec.sort)) {
            is RowsOutcome.Err -> PersonalSearchResult(error = outcome.error)
            is RowsOutcome.Ok -> window(outcome.rows.map { rowToItem(spec, it) }, input, offset)
        }
    }

    /** 日历：读 Instances 按时间窗展开重复事件（RRULE），而非读 events 每事件只出一条。 */
    private fun calendar(input: PersonalSearchInput): PersonalSearchResult {
        if (!rootAvailable()) {
            return PersonalSearchResult(error = ToolError(ToolErrorCode.ROOT_REQUIRED, "该来源需要 Root 授权，本次未执行"))
        }
        val offset = offsetOrStale(input) ?: return staleCursor()
        val now = System.currentTimeMillis()
        val begin = input.sinceMillis ?: (now - CALENDAR_PAST_WINDOW_MS)
        val end = (input.untilMillis ?: (now + CALENDAR_FUTURE_WINDOW_MS)).coerceAtLeast(begin + 1)
        // Instances 的时间窗写在 URI path 上（when/begin/end），不再用 dtstart 的 timeWhere。
        val uri = "content://com.android.calendar/instances/when/$begin/$end"
        val where = input.query?.takeIf { it.isNotBlank() }?.let { CALENDAR_INSTANCE_SPEC.searchable.likeClause(it) }
        return when (val outcome = queryRows(uri, CALENDAR_INSTANCE_SPEC.projection, where, CALENDAR_INSTANCE_SPEC.sort)) {
            is RowsOutcome.Err -> PersonalSearchResult(error = outcome.error)
            is RowsOutcome.Ok -> window(outcome.rows.map { rowToItem(CALENDAR_INSTANCE_SPEC, it) }, input, offset)
        }
    }

    /** 录音摘要：summary 表无时间列，关联 records 表取 date_modified 补上时间并按时间倒序。 */
    private fun recordingSummaries(input: PersonalSearchInput): PersonalSearchResult {
        if (!rootAvailable()) {
            return PersonalSearchResult(error = ToolError(ToolErrorCode.ROOT_REQUIRED, "该来源需要 Root 授权，本次未执行"))
        }
        val offset = offsetOrStale(input) ?: return staleCursor()
        val spec = SPECS.getValue(PersonalSource.RECORDING_SUMMARIES)
        val where = combineWhere(
            spec.fixedWhere,
            input.query?.takeIf { it.isNotBlank() }?.let { spec.searchable.likeClause(it) },
        )
        return when (val outcome = queryRows(spec.uri, spec.projection, where, spec.sort)) {
            is RowsOutcome.Err -> PersonalSearchResult(error = outcome.error)
            is RowsOutcome.Ok -> {
                val times = recordingTimes()
                val items = outcome.rows.map { row ->
                    val base = rowToItem(spec, row)
                    val mediaId = row.optString("media_id").takeIf(String::isNotEmpty)
                    val time = mediaId?.let { times[it] } ?: base.timeMillis
                    base.copy(timeMillis = time)
                }.sortedByDescending { it.timeMillis ?: Long.MIN_VALUE }
                window(items, input, offset)
            }
        }
    }

    /** records 表 _id → 录音时间（毫秒）。date_modified 可能是秒，统一归一到毫秒。 */
    private fun recordingTimes(): Map<String, Long> {
        val outcome = queryRows(
            uri = "content://com.coloros.soundrecorder.provider/records",
            projection = listOf("_id", "date_modified"),
            where = null,
            sort = "_id DESC",
        )
        if (outcome !is RowsOutcome.Ok) return emptyMap()
        return outcome.rows.mapNotNull { row ->
            val id = row.optString("_id").takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val raw = row.optString("date_modified").toLongOrNull() ?: return@mapNotNull null
            id to normalizeToMillis(raw)
        }.toMap()
    }

    private fun normalizeToMillis(value: Long): Long =
        if (value in 1 until SECONDS_THRESHOLD) value * 1_000 else value

    private sealed interface RowsOutcome {
        data class Ok(val rows: List<JSONObject>) : RowsOutcome
        data class Err(val error: ToolError) : RowsOutcome
    }

    private fun queryRows(uri: String, projection: List<String>, where: String?, sort: String): RowsOutcome {
        val command = buildString {
            append("content query --uri ").append(shellQuote(uri))
            append(" --projection ").append(shellQuote(projection.joinToString(":")))
            where?.let { append(" --where ").append(shellQuote(it)) }
            append(" --sort ").append(shellQuote(sort))
        }
        val result = root.execute(command, timeoutMillis = QUERY_TIMEOUT_MS, maxOutputBytes = MAX_OUTPUT_BYTES)
        if (!result.ok || PersonalDataContentParser.hasProviderFailure(result.stdout, result.stderr)) {
            val code = when {
                result.errorCode.isNotBlank() -> ToolErrorCode.SOURCE_UNAVAILABLE
                result.timedOut -> ToolErrorCode.TIMEOUT
                else -> ToolErrorCode.SOURCE_UNAVAILABLE
            }
            return RowsOutcome.Err(ToolError(code, "个人数据源暂时不可访问"))
        }
        return RowsOutcome.Ok(PersonalDataContentParser.parseRows(result.stdout, projection))
    }

    private fun offsetOrStale(input: PersonalSearchInput): Int? =
        when {
            input.cursor == null -> 0
            else -> PersonalCursor.decodeOffset(input.cursor, input)
        }

    private fun staleCursor(): PersonalSearchResult =
        PersonalSearchResult(error = ToolError(ToolErrorCode.STALE_OBSERVATION, "翻页游标已失效，请重新检索"))

    private fun rowToItem(spec: ProviderSpec, row: JSONObject): PersonalItem {
        val id = spec.idColumn?.let { row.optString(it).takeIf(String::isNotEmpty) }
        val time = spec.timeColumn?.let { row.optString(it).toLongOrNull() }
        val surfaced = setOfNotNull(spec.idColumn, spec.timeColumn, spec.titleColumn, spec.textColumn, spec.fromColumn)
        val extra = JSONObject()
        row.keys().forEach { key -> if (key !in surfaced) extra.put(key, row.get(key)) }
        return PersonalItem(
            id = id,
            timeMillis = time,
            title = spec.titleColumn?.let { row.optString(it).takeIf(String::isNotEmpty) },
            text = spec.textColumn?.let { row.optString(it).takeIf(String::isNotEmpty) },
            from = spec.fromColumn?.let { row.optString(it).takeIf(String::isNotEmpty) },
            uri = if (spec.uriTemplate != null && id != null) spec.uriTemplate.replace("{id}", id) else null,
            extra = extra.takeIf { it.length() > 0 },
        )
    }

    // ---- 通知：当前通知栏 + 最近 7 天历史（通知权）；Root `cmd notification` 路径留 TODO ----

    private fun notifications(input: PersonalSearchInput): PersonalSearchResult {
        // TODO(Root 通知)：有 Root 无通知权时可走 `cmd notification list/get`；当前只用通知监听历史。
        if (!AgentNotificationHistoryService.isEnabled(context)) {
            return PersonalSearchResult(
                error = ToolError(ToolErrorCode.PERMISSION_REQUIRED, "请先授予 Movo 通知使用权"),
            )
        }
        val maxAgeHours = ageHours(input.sinceMillis)
        val raw = runCatching {
            JSONObject(
                notificationHistory.search(
                    query = input.query.orEmpty(),
                    packageName = input.app.orEmpty(),
                    maxAgeHours = maxAgeHours,
                    limit = 50,
                ),
            )
        }.getOrElse {
            return PersonalSearchResult(error = ToolError(ToolErrorCode.SOURCE_UNAVAILABLE, "通知历史暂时读不到"))
        }
        val items = jsonItems(raw).map { row ->
            PersonalItem(
                id = row.optString("key").takeIf(String::isNotEmpty),
                timeMillis = row.optLong("posted_at").takeIf { it > 0 },
                title = row.optString("title").takeIf(String::isNotEmpty),
                text = row.optString("text").takeIf(String::isNotEmpty),
                from = row.optString("package_name").takeIf(String::isNotEmpty),
                uri = null,
                extra = row.optString("sub_text").takeIf(String::isNotEmpty)?.let { JSONObject().put("sub_text", it) },
            )
        }
        val offset = input.cursor?.let { PersonalCursor.decodeOffset(it, input) } ?: 0
        return window(items, input, offset)
    }

    // ---- 剪贴板历史：复用私有数据库快照读取 ----

    private fun clipboardHistory(input: PersonalSearchInput): PersonalSearchResult {
        val args = JSONObject().put("query", input.query.orEmpty()).put("limit", input.limit)
        val json = runCatching { JSONObject(privateDatabase.execute("search_clipboard_history", args)!!.content) }
            .getOrElse {
                return PersonalSearchResult(error = ToolError(ToolErrorCode.SOURCE_UNAVAILABLE, "剪贴板历史暂时读不到"))
            }
        if (!json.optBoolean("ok")) {
            return PersonalSearchResult(error = sourceError(json))
        }
        val items = jsonItems(json).map { row ->
            PersonalItem(
                id = null,
                timeMillis = row.optLong("TIME").takeIf { it > 0 },
                title = null,
                text = row.optString("CONTENT").takeIf(String::isNotEmpty),
                from = null,
                uri = null,
            )
        }
        val offset = input.cursor?.let { PersonalCursor.decodeOffset(it, input) } ?: 0
        return window(items, input, offset)
    }

    // ---- ColorOS 系统记忆：优先 Hook，退回 Root 快照 ----

    private fun colorOsMemorySearch(input: PersonalSearchInput): PersonalSearchResult {
        val json = runCatching {
            JSONObject(colorOsMemory.search(memoryArgs(input)).content)
        }.getOrElse {
            return PersonalSearchResult(error = ToolError(ToolErrorCode.SOURCE_UNAVAILABLE, "ColorOS 系统记忆暂时读不到"))
        }
        if (!json.optBoolean("ok")) return PersonalSearchResult(error = sourceError(json))
        val items = jsonItems(json).map { memoryItem(it) }
        val offset = input.cursor?.let { PersonalCursor.decodeOffset(it, input) } ?: 0
        return window(items, input, offset)
    }

    private fun places(input: PersonalSearchInput): PersonalSearchResult {
        val json = runCatching {
            JSONObject(colorOsMemory.searchSavedPlaces(memoryArgs(input)).content)
        }.getOrElse {
            return PersonalSearchResult(error = ToolError(ToolErrorCode.SOURCE_UNAVAILABLE, "收藏地点暂时读不到"))
        }
        if (!json.optBoolean("ok")) return PersonalSearchResult(error = sourceError(json))
        val items = jsonItems(json).map { row ->
            PersonalItem(
                id = row.optString("_id").takeIf(String::isNotEmpty),
                timeMillis = null,
                title = row.optString("name").takeIf(String::isNotEmpty),
                text = row.optString("address").takeIf(String::isNotEmpty),
                from = null,
                uri = null,
                extra = row,
            )
        }
        val offset = input.cursor?.let { PersonalCursor.decodeOffset(it, input) } ?: 0
        return window(items, input, offset)
    }

    // ---- 订单：系统记忆 + 通知历史双来源，某路失败进 warnings ----

    private fun orders(input: PersonalSearchInput): PersonalSearchResult {
        // 双来源合并翻页用锚点游标：有 cursor 但解不出锚点 → 查询已变，报失效。
        val anchor = input.cursor?.let { PersonalCursor.decodeAnchor(it, input) }
        if (input.cursor != null && anchor == null) return staleCursor()

        val memoryItems = mutableListOf<PersonalItem>()
        val notifItems = mutableListOf<PersonalItem>()
        val warnings = mutableListOf<ToolWarning>()

        if (rootAvailable()) {
            val memory = runCatching { JSONObject(colorOsMemory.searchOrders(memoryArgs(input)).content) }.getOrNull()
            if (memory != null && memory.optBoolean("ok")) {
                jsonItems(memory).forEach { memoryItems += memoryItem(it) }
            } else {
                warnings += ToolWarning(ToolErrorCode.SOURCE_UNAVAILABLE, "系统记忆订单来源读取失败")
            }
        } else {
            warnings += ToolWarning(ToolErrorCode.ROOT_REQUIRED, "系统记忆订单来源需要 Root；仅查询通知历史")
        }

        if (AgentNotificationHistoryService.isEnabled(context)) {
            val raw = runCatching {
                JSONObject(
                    notificationHistory.search(
                        query = input.query.orEmpty(),
                        packageName = input.app.orEmpty(),
                        maxAgeHours = ageHours(input.sinceMillis),
                        limit = 50,
                    ),
                )
            }.getOrNull()
            if (raw != null) {
                jsonItems(raw).forEach { row ->
                    notifItems += PersonalItem(
                        id = row.optString("key").takeIf(String::isNotEmpty),
                        timeMillis = row.optLong("posted_at").takeIf { it > 0 },
                        title = row.optString("title").takeIf(String::isNotEmpty),
                        text = row.optString("text").takeIf(String::isNotEmpty),
                        from = row.optString("package_name").takeIf(String::isNotEmpty),
                        uri = null,
                    )
                }
            } else {
                warnings += ToolWarning(ToolErrorCode.SOURCE_UNAVAILABLE, "通知历史订单来源读取失败")
            }
        } else {
            warnings += ToolWarning(ToolErrorCode.PERMISSION_REQUIRED, "未授予通知使用权，无法从通知识别订单")
        }

        if (memoryItems.isEmpty() && notifItems.isEmpty() && warnings.size >= 2) {
            return PersonalSearchResult(error = ToolError(ToolErrorCode.SOURCE_UNAVAILABLE, "订单来源都不可用"), warnings = warnings)
        }
        // 合并去重 + 稳定锚点翻页：(时间,id) 复合键去重，游标锚在上一页末尾的 (时间,id)。
        val merged = PersonalPaging.mergeDedupSort(memoryItems, notifItems)
        val page = PersonalPaging.page(merged, anchor, input.limit)
        return PersonalSearchResult(
            items = page.items,
            nextCursor = page.nextAnchor?.let { PersonalCursor.encodeAnchor(input, it) },
            warnings = warnings,
        )
    }

    // ---- 公共助手 ----

    private fun memoryItem(row: JSONObject): PersonalItem = PersonalItem(
        id = row.optString("memory_id").takeIf(String::isNotEmpty) ?: row.optString("_id").takeIf(String::isNotEmpty),
        timeMillis = sequenceOf("time", "create_time", "gmt_create", "update_time")
            .map { row.optLong(it) }.firstOrNull { it > 0 },
        title = row.optString("title").takeIf(String::isNotEmpty),
        text = row.optString("data_text").takeIf(String::isNotEmpty) ?: row.optString("content").takeIf(String::isNotEmpty),
        from = null,
        uri = null,
        extra = row,
    )

    private fun memoryArgs(input: PersonalSearchInput): JSONObject =
        JSONObject().put("query", input.query.orEmpty()).put("limit", input.limit)

    private fun jsonItems(json: JSONObject): List<JSONObject> {
        val array = json.optJSONArray("items") ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private fun sourceError(json: JSONObject): ToolError {
        val code = json.optString("code")
        val mapped = when {
            code.contains("ROOT", ignoreCase = true) -> ToolErrorCode.ROOT_REQUIRED
            code.contains("ACCESS_REQUIRED", ignoreCase = true) ||
                code.contains("PERMISSION", ignoreCase = true) -> ToolErrorCode.PERMISSION_REQUIRED
            code.contains("UNSUPPORTED", ignoreCase = true) -> ToolErrorCode.UNSUPPORTED
            code.contains("TIMEOUT", ignoreCase = true) -> ToolErrorCode.TIMEOUT
            else -> ToolErrorCode.SOURCE_UNAVAILABLE
        }
        return ToolError(mapped, json.optString("message").ifEmpty { "来源暂时不可访问" }, detail = code.ifEmpty { null })
    }

    /** 偏移窗口 + 下一页游标。 */
    private fun window(items: List<PersonalItem>, input: PersonalSearchInput, offset: Int): PersonalSearchResult {
        val windowed = items.drop(offset).take(input.limit)
        val hasMore = items.size > offset + input.limit
        return PersonalSearchResult(
            items = windowed,
            nextCursor = if (hasMore) PersonalCursor.encode(input, offset + input.limit) else null,
        )
    }

    private fun ageHours(sinceMillis: Long?): Int {
        if (sinceMillis == null) return 24
        val hours = ((System.currentTimeMillis() - sinceMillis) / (60L * 60 * 1000)).toInt() + 1
        return hours.coerceIn(1, 168)
    }

    private fun timeWhere(timeColumn: String?, since: Long?, until: Long?): String? {
        if (timeColumn == null) return null
        val clauses = buildList {
            since?.let { add("$timeColumn>=$it") }
            until?.let { add("$timeColumn<=$it") }
        }
        return clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
    }

    private fun List<String>.likeClause(keyword: String): String {
        val escaped = keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_").replace("'", "''")
        val value = "'%$escaped%'"
        return joinToString(" OR ", prefix = "(", postfix = ")") { column ->
            "LOWER($column) LIKE LOWER($value) ESCAPE '\\'"
        }
    }

    private fun combineWhere(vararg parts: String?): String? {
        val present = parts.filterNotNull().filter { it.isNotBlank() }
        return present.takeIf { it.isNotEmpty() }?.joinToString(" AND ") { "($it)" }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        const val QUERY_TIMEOUT_MS = 15_000L
        const val MAX_OUTPUT_BYTES = 512 * 1024

        // 日历 Instances 默认时间窗：未传 since/until 时查过去 30 天到未来 180 天。
        const val CALENDAR_PAST_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
        const val CALENDAR_FUTURE_WINDOW_MS = 180L * 24 * 60 * 60 * 1000

        // 小于该阈值（约 2001-09 的毫秒数）的时间戳按「秒」解释，×1000 归一到毫秒。
        const val SECONDS_THRESHOLD = 1_000_000_000_000L

        // 日历 Instances 视图：event_id 复用事件 id，begin/end 是每次重复展开后的实例时间。
        val CALENDAR_INSTANCE_SPEC = ProviderSpec(
            uri = "content://com.android.calendar/instances",
            projection = listOf("event_id", "title", "description", "eventLocation", "begin", "end", "allDay", "calendar_displayName"),
            searchable = listOf("title", "description", "eventLocation"),
            fixedWhere = null,
            timeColumn = "begin",
            sort = "begin ASC",
            idColumn = "event_id",
            titleColumn = "title",
            textColumn = "description",
            fromColumn = "eventLocation",
        )

        val SPECS: Map<PersonalSource, ProviderSpec> = mapOf(
            PersonalSource.SMS to ProviderSpec(
                uri = "content://sms",
                projection = listOf("_id", "address", "body", "date", "type", "read"),
                searchable = listOf("address", "body"),
                fixedWhere = null,
                timeColumn = "date",
                sort = "date DESC",
                idColumn = "_id",
                titleColumn = null,
                textColumn = "body",
                fromColumn = "address",
                uriTemplate = "content://sms/{id}",
            ),
            PersonalSource.CONTACTS to ProviderSpec(
                uri = "content://com.android.contacts/contacts",
                projection = listOf("_id", "display_name", "lookup", "has_phone_number", "contact_last_updated_timestamp"),
                searchable = listOf("display_name"),
                fixedWhere = null,
                timeColumn = null,
                sort = "display_name COLLATE LOCALIZED ASC",
                idColumn = "_id",
                titleColumn = "display_name",
                textColumn = null,
                fromColumn = null,
                uriTemplate = "content://com.android.contacts/contacts/{id}",
            ),
            PersonalSource.CALL_LOG to ProviderSpec(
                uri = "content://call_log/calls",
                projection = listOf("_id", "number", "name", "date", "duration", "type", "geocoded_location"),
                searchable = listOf("number", "name"),
                fixedWhere = null,
                timeColumn = "date",
                sort = "date DESC",
                idColumn = "_id",
                titleColumn = "name",
                textColumn = null,
                fromColumn = "number",
            ),
            PersonalSource.NOTES to ProviderSpec(
                uri = "content://com.nearme.note/rich_notes",
                projection = listOf("local_id", "raw_title", "raw_text", "update_time", "create_time", "folder_id", "deleted", "recycle_time"),
                searchable = listOf("raw_title", "raw_text"),
                fixedWhere = "deleted=0 AND recycle_time=0",
                timeColumn = null,
                sort = "update_time DESC",
                idColumn = "local_id",
                titleColumn = "raw_title",
                textColumn = "raw_text",
                fromColumn = null,
            ),
            // 录音摘要：summary 表无时间列，时间由 recordingSummaries() 关联 records 表补上。
            PersonalSource.RECORDING_SUMMARIES to ProviderSpec(
                uri = "content://com.coloros.soundrecorder.provider/summary",
                projection = listOf("_id", "record_uuid", "record_type", "note_content", "note_state", "media_id", "media_path", "note_id"),
                searchable = listOf("note_content", "media_path"),
                fixedWhere = null,
                timeColumn = null,
                sort = "_id DESC",
                idColumn = "_id",
                titleColumn = null,
                textColumn = "note_content",
                fromColumn = null,
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// sms_code_read 真实后端
// ---------------------------------------------------------------------------

internal class AndroidSmsCodeBackend(
    private val root: BoundedRootCommandExecutor,
    private val rootAvailable: () -> Boolean = { RootAccess.isGranted },
    // 无 Root 来源：短信通知历史。生产装配传入；单测用假后端不经过本类，默认关闭。
    private val notificationHistory: NotificationHistoryRepository? = null,
    private val notificationAvailable: () -> Boolean = { false },
) : SmsCodeBackend {

    override fun available(env: ToolEnvironment): Boolean =
        env.rootAvailable || rootAvailable() || env.notificationAccess || notificationAvailable()

    /**
     * 优先 Root 读短信正文；Root 不可用或读失败时退回短信通知历史。两条路径共用
     * [PersonalSecretPatterns.extractOtp] 抽码，保证规则一致。
     */
    override fun readCodes(cutoffMillis: Long, env: ToolEnvironment): List<SmsCode>? {
        if (env.rootAvailable || rootAvailable()) {
            readFromSms(cutoffMillis)?.let { return it }
        }
        if (env.notificationAccess || notificationAvailable()) {
            return readFromNotifications(cutoffMillis)
        }
        return null
    }

    private fun readFromSms(cutoffMillis: Long): List<SmsCode>? {
        val result = root.execute(
            "content query --uri content://sms/inbox --projection address:body:date --sort 'date DESC'",
            maxOutputBytes = 512 * 1024,
        )
        if (!result.ok) return null
        val codes = mutableListOf<SmsCode>()
        result.stdout.lineSequence().forEach { line ->
            if (codes.size >= MAX_CODES) return@forEach
            val date = SMS_DATE.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: return@forEach
            if (date < cutoffMillis) return@forEach
            val body = SMS_BODY.find(line)?.groupValues?.get(1).orEmpty()
            val code = PersonalSecretPatterns.extractOtp(body) ?: return@forEach
            codes += SmsCode(
                code = code,
                from = SMS_ADDRESS.find(line)?.groupValues?.get(1)?.takeIf(String::isNotEmpty),
                timeMillis = date,
            )
        }
        return codes
    }

    /**
     * 无 Root 时从短信通知历史抽验证码。短信通知的发送方包名各 ROM 不一，不按包名过滤，
     * 只对正文含验证码语境词的通知抽码。HyperOS 等是否对验证码通知做了隐藏/脱敏未核实，
     * 此处按通用通知处理（见报告的验证限制）。
     */
    private fun readFromNotifications(cutoffMillis: Long): List<SmsCode>? {
        val repo = notificationHistory ?: return null
        val maxAgeHours = (((System.currentTimeMillis() - cutoffMillis) / 3_600_000L) + 1)
            .toInt().coerceIn(1, 168)
        val json = runCatching {
            JSONObject(repo.search(query = "", packageName = "", maxAgeHours = maxAgeHours, limit = 50))
        }.getOrNull() ?: return null
        if (!json.optBoolean("ok")) return null
        val array = json.optJSONArray("items") ?: return emptyList()
        val codes = mutableListOf<SmsCode>()
        for (index in 0 until array.length()) {
            if (codes.size >= MAX_CODES) break
            val row = array.optJSONObject(index) ?: continue
            val postedAt = row.optLong("posted_at").takeIf { it > 0 } ?: continue
            if (postedAt < cutoffMillis) continue
            val text = listOf("title", "text", "sub_text")
                .joinToString(" ") { row.optString(it) }.trim()
            val code = PersonalSecretPatterns.extractOtp(text) ?: continue
            codes += SmsCode(
                code = code,
                from = row.optString("package_name").takeIf(String::isNotEmpty),
                timeMillis = postedAt,
            )
        }
        return codes
    }

    private companion object {
        const val MAX_CODES = 10
        val SMS_ADDRESS = Regex("""(?:^|,\s*)address=([^,]*)""")
        val SMS_BODY = Regex("""(?:^|,\s*)body=(.*?)(?:,\s*date=|$)""")
        val SMS_DATE = Regex("""(?:^|,\s*)date=(\d+)""")
    }
}

// ---------------------------------------------------------------------------
// usage_read 真实后端
// ---------------------------------------------------------------------------

@Suppress("DEPRECATION")
internal class AndroidUsageReadBackend(
    private val context: Context,
) : UsageReadBackend {

    override fun available(env: ToolEnvironment): Boolean =
        env.usageAccess || AgentPersonalContextTools.hasUsageAccess(context)

    override fun recent(startMillis: Long, endMillis: Long, packageName: String?, limit: Int): List<UsageItem> {
        val events = usageManager()?.queryEvents(startMillis, endMillis) ?: return emptyList()
        val rows = ArrayDeque<UsageItem>()
        val event = UsageEvents.Event()
        var lastPackage: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
            if (packageName != null && event.packageName != packageName) continue
            // 合并同一 App 连续记录。
            if (event.packageName == lastPackage) continue
            lastPackage = event.packageName
            rows.addFirst(
                UsageItem(
                    packageName = event.packageName,
                    appName = appName(event.packageName),
                    activity = event.className,
                    resumedAtMillis = event.timeStamp,
                ),
            )
            while (rows.size > limit) rows.removeLast()
        }
        return rows.toList()
    }

    override fun summary(startMillis: Long, endMillis: Long, packageName: String?, limit: Int): List<UsageItem> {
        val events = usageManager()?.queryEvents(startMillis, endMillis) ?: return emptyList()
        // 按事件累计前台时长（不用 INTERVAL_DAILY 桶，避免小时级误差）。
        val totals = HashMap<String, Long>()
        val lastUsed = HashMap<String, Long>()
        val resumeAt = HashMap<String, Long>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            if (packageName != null && pkg != packageName) continue
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED, UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    resumeAt[pkg] = event.timeStamp
                    lastUsed[pkg] = event.timeStamp
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    resumeAt.remove(pkg)?.let { start ->
                        totals[pkg] = (totals[pkg] ?: 0L) + (event.timeStamp - start).coerceAtLeast(0L)
                    }
                    lastUsed[pkg] = event.timeStamp
                }
            }
        }
        // 结束时仍在前台的，补到窗口末尾。
        resumeAt.forEach { (pkg, start) ->
            totals[pkg] = (totals[pkg] ?: 0L) + (endMillis - start).coerceAtLeast(0L)
        }
        return totals.entries
            .filter { it.value > 0L }
            .sortedByDescending { it.value }
            .take(limit)
            .map { (pkg, ms) ->
                UsageItem(
                    packageName = pkg,
                    appName = appName(pkg),
                    foregroundMs = ms,
                    lastUsedAtMillis = lastUsed[pkg],
                )
            }
    }

    private fun usageManager(): UsageStatsManager? =
        context.getSystemService(UsageStatsManager::class.java)

    private fun appName(packageName: String): String = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)
}

// ---------------------------------------------------------------------------
// health_read 真实后端（Root 读 healthconnect.db，复用私有数据库聚合逻辑）
// ---------------------------------------------------------------------------

internal class AndroidHealthReadBackend(
    private val context: Context,
    private val root: BoundedRootCommandExecutor,
    private val rootAvailable: () -> Boolean = { RootAccess.isGranted },
) : HealthReadBackend {

    private val privateDatabase by lazy { AgentPrivateDatabaseTools(context, root) }

    // 免 Root：定义清单建议用 Health Connect aggregate（声明健康权限）免 Root；当前只支持 Root 读 db。
    // 接入 Health Connect 需引新依赖（androidx.health.connect），按任务要求不在本次引入，交主流程定（见报告）。
    override fun available(env: ToolEnvironment): Boolean = env.rootAvailable || rootAvailable()

    override fun summarize(days: Int, env: ToolEnvironment): HealthReadResult {
        val json = runCatching {
            JSONObject(privateDatabase.execute("get_health_summary", JSONObject().put("days", days))!!.content)
        }.getOrElse {
            return HealthReadResult.Unavailable(ToolError(ToolErrorCode.SOURCE_UNAVAILABLE, "健康数据暂时读不到"))
        }
        if (!json.optBoolean("ok")) {
            val code = json.optString("code")
            return HealthReadResult.Unavailable(
                ToolError(ToolErrorCode.SOURCE_UNAVAILABLE, json.optString("message").ifEmpty { "健康数据不可用" }, detail = code.ifEmpty { null }),
            )
        }
        val summary = json.optJSONObject("summary") ?: JSONObject()
        return HealthReadResult.Ok(summary = dedupeBySource(summary), hasData = summary.length() > 0)
    }

    /**
     * 多来源去重：手机 + 手表会把同一度量重复记录，直接 SUM 翻倍。若数据层给出按来源拆分的
     * 明细（summary.by_source = [{metric, source, value, records}]），用 [HealthSourceAggregator]
     * 每度量取单一主来源覆盖掉求和值；没有明细则原样返回。
     *
     * 去重纯逻辑已实现并单测（HealthSourceAggregatorTest）。但当前 AgentPrivateDatabaseTools 的
     * get_health_summary 直接跨来源 SUM、不吐 by_source；要让端到端去重生效，需要数据层按
     * package/device 暴露每来源明细——那是 personal 域之外的改动，按「只改 personal 文件」的约束
     * 不在此处改，交主流程决定（见报告的验证限制）。
     */
    private fun dedupeBySource(summary: JSONObject): JSONObject {
        val bySource = summary.optJSONArray("by_source") ?: return summary
        val samples = (0 until bySource.length()).mapNotNull { index ->
            val row = bySource.optJSONObject(index) ?: return@mapNotNull null
            val metric = row.optString("metric").takeIf(String::isNotEmpty) ?: return@mapNotNull null
            HealthSourceSample(
                metric = metric,
                source = row.optString("source"),
                value = row.optLong("value"),
                records = row.optInt("records", 1),
            )
        }
        if (samples.isEmpty()) return summary
        HealthSourceAggregator.dedupe(samples).forEach { total ->
            val metric = summary.optJSONObject(total.metric) ?: JSONObject().also { summary.put(total.metric, it) }
            metric.put("count", total.value)
            metric.put("source", total.source)
            metric.put("deduped", true)
        }
        return summary
    }
}

// ---------------------------------------------------------------------------
// wifi_password_read 真实后端（Root 读 WifiConfigStore.xml）
// ---------------------------------------------------------------------------

internal class AndroidWifiPasswordReadBackend(
    private val root: BoundedRootCommandExecutor,
) : WifiPasswordReadBackend {

    override fun read(ssidFilter: String?, limit: Int): List<WifiNetwork>? {
        val result = root.execute(
            "cat /data/misc/apexdata/com.android.wifi/WifiConfigStore.xml",
            maxOutputBytes = 2 * 1024 * 1024,
        )
        if (!result.ok) return null
        return NETWORK_BLOCK.findAll(result.stdout).mapNotNull { match ->
            val block = match.value
            val ssid = XML_SSID.find(block)?.groupValues?.get(1)?.decodeXml()?.trim('"') ?: return@mapNotNull null
            val password = XML_PSK.find(block)?.groupValues?.get(1)?.decodeXml()?.trim('"')?.takeUnless { it == "null" }
            WifiNetwork(ssid = ssid, password = password)
        }.filter {
            ssidFilter.isNullOrBlank() || it.ssid.equals(ssidFilter, ignoreCase = true)
        }.distinctBy {
            it.ssid.lowercase(Locale.ROOT)
        }.take(limit).toList()
    }

    private fun String.decodeXml(): String =
        replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    private companion object {
        val NETWORK_BLOCK = Regex("<Network>.*?</Network>", setOf(RegexOption.DOT_MATCHES_ALL))
        val XML_SSID = Regex("""<string name="SSID">(.*?)</string>""")
        val XML_PSK = Regex("""<string name="PreSharedKey">(.*?)</string>""")
    }
}
