package io.github.fartown.movo.diagnostics.runlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RunLogJsonTest {
    @Test
    fun writeKeepsKeyOrderAndParseKeepsNumberText() {
        val text = """{"seq":1,"t":"tool_end","price":1.50,"big":12345678901234567890,"ok":true,"none":null,"list":[1,"a"]}"""
        val parsed = RunLogJson.parse(text)
        assertEquals(text, RunLogJson.write(parsed))
        assertEquals(listOf("seq", "t", "price", "big", "ok", "none", "list"), (parsed as Map<*, *>).keys.toList())
    }

    @Test
    fun stringsEscapeOnlyWhatJsonRequires() {
        val value = "路径 a/b \"引号\" \\ 换行\n制表\t\u0001"
        val written = RunLogJson.write(mapOf("v" to value))
        assertEquals("""{"v":"路径 a/b \"引号\" \\ 换行\n制表\t\u0001"}""", written)
        assertEquals(value, (RunLogJson.parse(written) as Map<*, *>)["v"])
    }

    @Test
    fun unicodeEscapesAndSurrogatePairsDecode() {
        val parsed = RunLogJson.parse("""["你好","😀","\/"]""") as List<*>
        assertEquals(listOf("你好", "😀", "/"), parsed)
    }

    @Test
    fun malformedTextIsRejected() {
        listOf("""{"a":1""", """{"a":1}x""", """[1,]""", """{"a":"\x"}""", "", "nul").forEach { text ->
            assertThrows(IllegalArgumentException::class.java) { RunLogJson.parse(text) }
        }
    }

    @Test
    fun looksLikeJsonNeedsMatchingBrackets() {
        assertTrue(RunLogJson.looksLikeJson("  {\"a\":1}\n"))
        assertTrue(RunLogJson.looksLikeJson("[1]"))
        assertFalse(RunLogJson.looksLikeJson("[INFO] started"))
        assertFalse(RunLogJson.looksLikeJson("plain"))
    }

    @Test
    fun limitsCutAtCodePointsAndNoteTheOriginalLength() {
        val text = "a".repeat(9) + "😀"
        val capped = RunLogLimits.cap(text, 10)
        assertEquals("a".repeat(9) + "…[已截断，原长 11 字]", capped)
        assertEquals("短", RunLogLimits.cap("短", 10))
    }
}
