package io.github.fartown.movo.agent.tools.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 合并 + 去重 + 锚点翻页纯逻辑的单测（假数据，无 Android 依赖）。 */
class PersonalPagingTest {

    private fun item(time: Long?, id: String?, text: String? = null, from: String? = null) =
        PersonalItem(id = id, timeMillis = time, title = null, text = text, from = from, uri = null)

    @Test
    fun mergeDedupSort_dedupsByTimeIdAndSortsDesc() {
        val a = listOf(item(100, "x"))
        val b = listOf(item(100, "x"), item(200, "y"))
        val merged = PersonalPaging.mergeDedupSort(a, b)
        assertEquals(listOf(200L, 100L), merged.map { it.timeMillis })
    }

    @Test
    fun mergeDedupSort_keepsDistinctNullIdItems() {
        val merged = PersonalPaging.mergeDedupSort(
            listOf(item(100, null, text = "aaa")),
            listOf(item(100, null, text = "bbb")),
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun page_walksForwardWithAnchorAndStops() {
        val sorted = PersonalPaging.mergeDedupSort(
            listOf(item(500, "a"), item(400, "b"), item(300, "c"), item(200, "d"), item(100, "e")),
        )
        val first = PersonalPaging.page(sorted, anchor = null, limit = 2)
        assertEquals(listOf("a", "b"), first.items.map { it.id })
        assertNotNull(first.nextAnchor)

        val second = PersonalPaging.page(sorted, first.nextAnchor, limit = 2)
        assertEquals(listOf("c", "d"), second.items.map { it.id })
        assertNotNull(second.nextAnchor)

        val third = PersonalPaging.page(sorted, second.nextAnchor, limit = 2)
        assertEquals(listOf("e"), third.items.map { it.id })
        assertNull("最后一页无下一页锚点", third.nextAnchor)
    }

    @Test
    fun page_isStableWhenNewItemsArriveAtTop() {
        val base = listOf(item(500, "a"), item(400, "b"), item(300, "c"), item(200, "d"), item(100, "e"))
        val first = PersonalPaging.page(PersonalPaging.mergeDedupSort(base), anchor = null, limit = 2)
        // 翻页之间顶部插入一条新记录，重新合并后用同一锚点续页，不重复、不漏已翻过的区段。
        val grown = PersonalPaging.mergeDedupSort(base + item(600, "z"))
        val second = PersonalPaging.page(grown, first.nextAnchor, limit = 2)
        assertEquals(listOf("c", "d"), second.items.map { it.id })
        assertTrue("不得把顶部新记录混进后续页", second.items.none { it.id == "z" })
    }

    @Test
    fun page_resumesEvenIfAnchorItemWasDeleted() {
        val first = PersonalPaging.page(
            PersonalPaging.mergeDedupSort(listOf(item(500, "a"), item(400, "b"), item(300, "c"))),
            anchor = null,
            limit = 2,
        )
        // 锚点那条（b）被删后，仍从它原本的位置之后续页。
        val shrunk = PersonalPaging.mergeDedupSort(listOf(item(500, "a"), item(300, "c")))
        val second = PersonalPaging.page(shrunk, first.nextAnchor, limit = 2)
        assertEquals(listOf("c"), second.items.map { it.id })
    }

    @Test
    fun identity_prefersIdThenFallsBackToContent() {
        assertEquals(PersonalPaging.identity(item(100, "x")), PersonalPaging.identity(item(100, "x")))
        assertTrue(PersonalPaging.identity(item(100, null, text = "a")) != PersonalPaging.identity(item(100, null, text = "b")))
    }
}
