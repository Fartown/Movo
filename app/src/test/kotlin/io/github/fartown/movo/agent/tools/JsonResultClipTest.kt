package io.github.fartown.movo.agent.tools

import io.github.fartown.movo.agent.tools.core.ToolError
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.agent.tools.core.ToolOutcome
import io.github.fartown.movo.agent.tools.core.ToolProjection
import io.github.fartown.movo.agent.tools.core.ToolStatus
import io.github.fartown.movo.agent.tools.core.ToolWarning
import io.github.fartown.movo.agent.tools.core.Truncation
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JSON 结果超过 24000 字时按字段截短，不再整份降成纯文本（重构差异 T1-3）：
 * 外层结构、next_cursor、warnings、错误码都要留着，截了哪些字段要写明。
 */
class JsonResultClipTest {
    private val max = ToolProjection.MAX_MODEL_CHARS

    private fun nodes(count: Int, textChars: Int) = JSONArray().also { array ->
        repeat(count) { i -> array.put(JSONObject().put("index", i).put("text", "节点$i-" + "字".repeat(textChars))) }
    }

    private fun clipped(json: JSONObject): Map<String, JSONObject> {
        val fields = json.getJSONObject("output_clipped").getJSONArray("fields")
        return (0 until fields.length()).associate { fields.getJSONObject(it).let { f -> f.getString("path") to f } }
    }

    @Test
    fun longArray_dropsTrailingItemsAndKeepsCursorWarningsAndStructure() {
        val outcome = ToolOutcome.ok(
            data = JSONObject().put("observation_id", "obs-1").put("nodes", nodes(120, 250)),
            warnings = listOf(ToolWarning(ToolErrorCode.SOURCE_UNAVAILABLE, "截图来源暂时读不到")),
        ).copy(truncation = Truncation(shown = 120, total = 300, unit = "nodes", nextCursor = "c-120"), effectVerified = false)

        val text = ToolProjection.render(outcome)
        assertTrue("不超过上限（${text.length}）", text.length <= max)
        val json = JSONObject(text)
        assertEquals("ok", json.getString("status"))
        assertFalse("不再降成 data_text", json.has("data_text"))
        val data = json.getJSONObject("data")
        assertEquals("obs-1", data.getString("observation_id"))
        val kept = data.getJSONArray("nodes")
        assertTrue(kept.length() in 1 until 120)
        assertEquals("留下的是开头的项，结构不变", 0, kept.getJSONObject(0).getInt("index"))
        assertEquals("c-120", json.getJSONObject("truncated").getString("next_cursor"))
        assertEquals("截图来源暂时读不到", json.getJSONArray("warnings").getJSONObject(0).getString("message"))
        assertFalse(json.getBoolean("effect_verified"))
        val field = clipped(json).getValue("data.nodes")
        assertEquals(kept.length(), field.getInt("shown"))
        assertEquals(120, field.getInt("total"))
        assertEquals("items", field.getString("unit"))
        assertTrue(json.getJSONObject("output_clipped").getString("note").contains("next_cursor"))
    }

    @Test
    fun longString_keepsItsHeadAndSaysHowMuchWasCut() {
        val body = "正文" + "很".repeat(60_000)
        val text = ToolProjection.render(ToolOutcome.ok(JSONObject().put("title", "标题").put("text", body).put("length", body.length)))
        assertTrue(text.length <= max)
        val json = JSONObject(text)
        val data = json.getJSONObject("data")
        assertEquals("标题", data.getString("title"))
        assertEquals(body.length, data.getInt("length"))
        assertTrue(data.getString("text").startsWith("正文很很"))
        assertTrue(data.getString("text").contains("后面省略"))
        assertEquals(body.length, clipped(json).getValue("data.text").getInt("total"))
    }

    @Test
    fun oneHugeItem_cutsTheStringInsideInsteadOfDroppingTheItem() {
        val items = JSONArray()
            .put(JSONObject().put("id", "a").put("content", "长".repeat(50_000)))
            .put(JSONObject().put("id", "b").put("content", "短"))
        val text = ToolProjection.render(ToolOutcome.ok(JSONObject().put("items", items)))
        assertTrue(text.length <= max)
        val kept = JSONObject(text).getJSONObject("data").getJSONArray("items")
        assertEquals("小的那项不用丢", 2, kept.length())
        assertEquals("b", kept.getJSONObject(1).getString("id"))
        assertTrue(kept.getJSONObject(0).getString("content").length < 30_000)
    }

    @Test
    fun errorResult_keepsCodeMessageAndHint() {
        val outcome = ToolOutcome(
            status = ToolStatus.ERROR,
            error = ToolError(ToolErrorCode.EXTERNAL_ERROR, "服务返回错误", hint = "检查参数", detail = "upstream_400"),
            data = JSONObject().put("raw", "错".repeat(40_000)),
        )
        val json = JSONObject(ToolProjection.render(outcome))
        assertEquals("error", json.getString("status"))
        assertEquals("EXTERNAL_ERROR", json.getString("code"))
        assertEquals("服务返回错误", json.getString("message"))
        assertEquals("检查参数", json.getString("hint"))
        assertEquals("upstream_400", json.getString("detail"))
        assertTrue(json.getJSONObject("data").getString("raw").length < 30_000)
    }

    @Test
    fun manyTinyFields_fallBackToRawTextButKeepTheErrorCode() {
        val data = JSONObject()
        repeat(4_000) { i -> data.put("k$i", "v$i") }
        val outcome = ToolOutcome(
            status = ToolStatus.ERROR,
            error = ToolError(ToolErrorCode.SOURCE_UNAVAILABLE, "部分读不到"),
            data = data,
        )
        val text = ToolProjection.render(outcome)
        assertTrue(text.length <= max)
        val json = JSONObject(text)
        assertEquals("SOURCE_UNAVAILABLE", json.getString("code"))
        assertEquals("部分读不到", json.getString("message"))
        assertTrue(json.has("data_text"))
        assertTrue(json.getJSONObject("output_clipped").getInt("total") > json.getString("data_text").length)
    }

    @Test
    fun smallResult_isUntouched() {
        val text = ToolProjection.render(ToolOutcome.ok(JSONObject().put("a", 1)))
        assertFalse(text.contains("output_clipped"))
        assertEquals(1, JSONObject(text).getJSONObject("data").getInt("a"))
    }
}
