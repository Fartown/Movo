package io.github.fartown.movo.agent.tools.device

import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.device.BoundedRootCommandExecutor
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.tools.core.ContractTool
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolPipeline
import io.github.fartown.movo.agent.tools.core.ToolProvider
import io.github.fartown.movo.agent.tools.core.ToolRegistry
import io.github.fartown.movo.core.AndroidAgentLogger
import java.time.ZoneId
import java.time.ZonedDateTime
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 任务中随时取准确时间：device_read 的 time 到秒、带时区（重构前 get_current_context 随时可调）。 */
@RunWith(RobolectricTestRunner::class)
class DeviceReadTimeTest {
    private val root = BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = { false })

    @After
    fun close() = root.close()

    @Test
    fun sectionsSayWhatEnvironmentHolds() {
        // 重构前 get_device_environment 写明「锁屏、勿扰、铃声、音频输出和外接显示器」；只写「环境」时模型问「声音从哪出」会绕远路。
        val tool = DeviceReadTool(AndroidDeviceReadBackend(ApplicationProvider.getApplicationContext(), root) { false })
        val sections = tool.schema(ToolEnvironment()).getJSONObject("properties").getJSONObject("sections").getString("description")
        listOf("锁屏", "勿扰", "铃声", "音频输出", "外接显示器").forEach { assertTrue(it, sections.contains(it)) }
    }

    @Test
    fun timeSection_isToTheSecondWithZoneAndWeekday() {
        val now = ZonedDateTime.of(2026, 10, 7, 14, 3, 27, 600_000_000, ZoneId.of("Asia/Shanghai"))
        val time = deviceTime(now)
        assertEquals("2026-10-07T14:03:27+08:00", time.getString("datetime"))
        assertEquals("Asia/Shanghai", time.getString("timezone"))
        assertEquals("星期三", time.getString("weekday"))
    }

    @Test
    fun deviceRead_time_readsTheClockAtCallTimeAndIsNotPrivate() {
        val tool = DeviceReadTool(AndroidDeviceReadBackend(ApplicationProvider.getApplicationContext(), root) { false })
        assertTrue(tool.schema(ToolEnvironment()).toString().contains("\"time\""))
        val pipeline = ToolPipeline(
            registry = ToolRegistry(listOf(object : ToolProvider { override val tools = listOf(ContractTool(tool)) })),
            environment = { ToolEnvironment() },
            appContext = ApplicationProvider.getApplicationContext(),
            logger = AndroidAgentLogger,
            runId = "run1",
            cancelled = { false },
        ).also { it.catalog() }
        val result = pipeline.execute(AgentModelClient.ToolCall("c1", "device_read", """{"sections":["time"]}"""))
        val json = JSONObject(result.content)
        assertEquals("ok", json.getString("status"))
        val datetime = json.getJSONObject("data").getJSONObject("time").getString("datetime")
        assertTrue("到秒、带时区：$datetime", Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d([+-]\d\d:\d\d|Z)""").matches(datetime))
        assertFalse("时间不是个人数据", result.sensitive)
        assertEquals("看当前时间", tool.uiTitle(DeviceReadInput(listOf(DeviceSection.TIME))))
    }
}
