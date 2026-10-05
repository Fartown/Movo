package io.github.fartown.movo.agent.monitor

import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.tools.core.MemoryScope
import io.github.fartown.movo.agent.tools.core.ToolArgs
import io.github.fartown.movo.agent.tools.core.ToolAvailability
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** monitor_start 参数处理、名字规范化与截断、角色对话不提供监听工具。 */
@RunWith(RobolectricTestRunner::class)
class MonitorToolsTest {
    private val env = ToolEnvironment(conversationBound = true, conversationId = "c1", memoryScope = MemoryScope.REAL)

    @Test
    fun namesAreOneLineAndCutByGraphemeWithoutBreakingEmoji() {
        assertEquals("喝水 提醒 \"晚上\"", MonitorNames.sanitize("  喝水\n提醒\t\"晚上\"　 "))
        val family = "👨‍👩‍👧‍👦"
        val flag = "🇨🇳"
        val thumbs = "👍🏽"
        val name = MonitorNames.sanitize("提醒$family$flag$thumbs" + "字".repeat(30))
        assertEquals("提醒$family$flag$thumbs" + "字".repeat(MonitorNames.MAX_GRAPHEMES - 5), name)
        // 截在 emoji 中间会留下孤立的代理项：逐个检查没有。
        val cut = MonitorNames.takeGraphemes("a$family$family", 2)
        assertEquals("a$family", cut)
        assertFalse(cut.last().isHighSurrogate())
    }

    @Test
    fun duplicateKeyIgnoresWidthSpacingAndCase() {
        assertEquals(MonitorNames.key("ABC 提醒"), MonitorNames.key("ａｂｃ　提醒"))
        assertEquals(MonitorNames.key("喝水 提醒"), MonitorNames.key("喝水提醒"))
        assertFalse(MonitorNames.key("喝水提醒") == MonitorNames.key("喝水提醒2"))
    }

    @Test
    fun durationAboveTheSchemaLimitIsAcceptedAndExplainedInsteadOfRejected() {
        val tool = MonitorStartTool(ApplicationProvider.getApplicationContext())
        val schema = tool.schema(env).getJSONObject("properties").getJSONObject("timeout_ms")
        assertFalse(schema.has("maximum"))
        val input = tool.parse(
            ToolArgs(JSONObject().put("description", "喝水\n提醒").put("command", "sleep 1").put("timeout_ms", 10L * 60 * 60_000)),
            env,
        )
        assertEquals(10L * 60 * 60_000, input.timeoutMs)
        assertEquals("喝水 提醒", input.description)

        val info = MonitorInfo("m1", "c1", "喝水 提醒", "sleep 1", 0L, 2 * 60 * 60_000L, 2 * 60 * 60_000L, 0)
        val capped = MonitorStartTool.startedMessage(MonitorStartOutput(info, requestedTimeoutMs = 10L * 60 * 60_000, maxTimeoutMs = 2 * 60 * 60_000L))
        assertTrue(capped.contains("最长运行 2 小时"))
        assertTrue(capped.contains("你请求的 10 小时 超过了用户设置的最长监听时长，已按上限 2 小时 生效"))
        val plain = MonitorStartTool.startedMessage(MonitorStartOutput(info.copy(timeoutMs = 45_000L), requestedTimeoutMs = 45_000L))
        assertTrue(plain.contains("最长运行 45 秒"))
        assertFalse(plain.contains("超过"))
        assertFalse(plain.contains("0 分钟"))
    }

    @Test
    fun monitorAndNotificationToolsAreNotOfferedInRoleplay() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val roleplay = MonitorToolProvider(context, isRoleplay = { true })
        val ordinary = MonitorToolProvider(context)
        assertEquals(MonitorToolProvider.NAMES, ordinary.tools.map { it.name }.toSet())
        ordinary.tools.forEach { assertEquals(it.name, ToolAvailability.Available, it.availability(env)) }
        roleplay.tools.forEach { assertTrue(it.name, it.availability(env) is ToolAvailability.Unavailable) }
        // 记忆打开的角色会话从环境里也能看出来。
        val characterEnv = env.copy(memoryScope = MemoryScope.CHARACTER)
        ordinary.tools.forEach { assertTrue(it.name, it.availability(characterEnv) is ToolAvailability.Unavailable) }
        ordinary.tools.forEach { assertTrue(it.name, it.availability(env.copy(conversationId = null)) is ToolAvailability.Unavailable) }
    }
}
