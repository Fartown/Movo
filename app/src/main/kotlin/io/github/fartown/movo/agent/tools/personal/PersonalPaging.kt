package io.github.fartown.movo.agent.tools.personal

/**
 * 稳定分页的纯逻辑：多来源（如系统记忆 + 通知历史）合并后去重，并用「锚点」而非纯偏移翻页。
 *
 * 游标编码的是「上一页最后一条的 (时间, 去重 key)」。翻页时从该锚点之后续，即使顶部插入了新
 * 记录，也不会错位或重复——这优于「按偏移截断」。抽成纯函数便于单测（见 PersonalPagingTest）。
 */

/**
 * 翻页锚点：上一页末尾条目的有效时间与去重标识，构成全序中的一个位置。
 * [firstPageMillis] 只有通知历史用：第一页是什么时候查的，翻页期间新来或更新的通知时间都比它晚。
 */
internal data class PageAnchor(val timeMillis: Long, val identity: String, val firstPageMillis: Long? = null)

internal object PersonalPaging {

    /** 缺时间的条目排在最后；时间与锚点统一用这个取值，避免 null 与 0 混用导致的错位。 */
    private fun effectiveTime(item: PersonalItem): Long = item.timeMillis ?: Long.MIN_VALUE

    /**
     * 去重标识：有 id 用 (时间,id) 复合键；无 id 退回 (时间,标题,正文,来源)，
     * 避免把同一时刻的不同通知误判为重复。
     */
    fun identity(item: PersonalItem): String {
        val time = effectiveTime(item)
        return item.id?.takeIf { it.isNotEmpty() }
            ?.let { "$time\u0000$it" }
            ?: listOf(time.toString(), item.title.orEmpty(), item.text.orEmpty(), item.from.orEmpty())
                .joinToString("\u0000")
    }

    /** 全序：时间倒序（缺时间最后），时间相同按 identity 倒序，保证确定、可复现。 */
    private val order: Comparator<PersonalItem> =
        compareByDescending(::effectiveTime).thenByDescending(::identity)

    /** 合并多来源 → 按去重 key 去重（先到先留）→ 稳定排序。 */
    fun mergeDedupSort(vararg sources: List<PersonalItem>): List<PersonalItem> {
        val seen = HashSet<String>()
        return sources.asSequence()
            .flatMap { it.asSequence() }
            .filter { seen.add(identity(it)) }
            .sortedWith(order)
            .toList()
    }

    /** 条目在全序里是否严格排在锚点之后（锚点被删也成立，按位置而非精确匹配判定）。 */
    private fun isAfter(item: PersonalItem, anchor: PageAnchor): Boolean {
        val time = effectiveTime(item)
        return when {
            time != anchor.timeMillis -> time < anchor.timeMillis
            else -> identity(item) < anchor.identity
        }
    }

    data class Page(val items: List<PersonalItem>, val nextAnchor: PageAnchor?)

    /**
     * 从锚点之后取一页。[sorted] 必须已 [mergeDedupSort]；[anchor] 为 null 时从头取。
     * 返回这一页条目与下一页锚点（没有更多时为 null）。
     */
    fun page(sorted: List<PersonalItem>, anchor: PageAnchor?, limit: Int): Page {
        val region = if (anchor == null) sorted else sorted.filter { isAfter(it, anchor) }
        val windowed = region.take(limit)
        val hasMore = region.size > windowed.size
        val nextAnchor = windowed.lastOrNull()
            ?.takeIf { hasMore }
            ?.let { PageAnchor(effectiveTime(it), identity(it)) }
        return Page(windowed, nextAnchor)
    }
}
