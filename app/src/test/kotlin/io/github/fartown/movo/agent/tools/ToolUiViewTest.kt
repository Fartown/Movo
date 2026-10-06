package io.github.fartown.movo.agent.tools

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.media.ToolStepImages
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.runtime.AgentEvent
import io.github.fartown.movo.agent.runtime.AgentEventJsonCodec
import io.github.fartown.movo.agent.runtime.AgentRuntimeWire
import io.github.fartown.movo.agent.tools.core.AgentTool
import io.github.fartown.movo.agent.tools.core.CallResolution
import io.github.fartown.movo.agent.tools.core.ContractTool
import io.github.fartown.movo.agent.tools.core.ModelContent
import io.github.fartown.movo.agent.tools.core.Risk
import io.github.fartown.movo.agent.tools.core.Sensitivity
import io.github.fartown.movo.agent.tools.core.ToolArgs
import io.github.fartown.movo.agent.tools.core.ToolContext
import io.github.fartown.movo.agent.tools.core.ToolContract
import io.github.fartown.movo.agent.tools.core.ToolDomain
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolInput
import io.github.fartown.movo.agent.tools.core.ToolOutput
import io.github.fartown.movo.agent.tools.core.ToolPipeline
import io.github.fartown.movo.agent.tools.core.ToolProvider
import io.github.fartown.movo.agent.tools.core.ToolRegistry
import io.github.fartown.movo.agent.tools.core.ToolUiBlock
import io.github.fartown.movo.agent.tools.core.ToolUiView
import io.github.fartown.movo.agent.tools.core.UserInteraction
import io.github.fartown.movo.agent.tools.core.Verdict
import io.github.fartown.movo.core.AndroidAgentLogger
import java.io.ByteArrayOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 执行卡视图（工具可视化方案 §5.1）：上限、序列化、敏感度处理、只在本次运行中显示的视图不进归档。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ToolUiViewTest {

    @Test
    fun bounded_capsItemsOutputAndTotalSize() {
        val view = ToolUiView(
            summary = "很长的摘要 ".repeat(50),
            blocks = listOf(
                ToolUiBlock.Items((1..30).map { ToolUiBlock.Item("第 $it 项") }),
                ToolUiBlock.Output((1..300).joinToString("\n") { "line $it" }),
            ),
        ).bounded()
        val items = view.blocks[0] as ToolUiBlock.Items
        assertEquals(20, items.items.size)
        assertEquals(10, items.more)
        val output = view.blocks[1] as ToolUiBlock.Output
        assertEquals(200, output.text.lines().size)
        assertEquals(100, output.more)
        assertTrue(view.summary!!.length <= ToolUiView.MAX_SUMMARY_CHARS + 1)

        val huge = ToolUiView(summary = "s", blocks = List(10) { ToolUiBlock.Preview("字".repeat(1_900)) }).bounded()
        assertTrue(huge.toJson().toString().length <= ToolUiView.MAX_VIEW_CHARS)
        assertEquals("s", huge.summary)
    }

    @Test
    fun json_roundTripsEveryBlock() {
        val view = ToolUiView(
            summary = "找到 2 条",
            blocks = listOf(
                ToolUiBlock.Fields(listOf(ToolUiBlock.Field("电量", "80%"))),
                ToolUiBlock.Items(listOf(ToolUiBlock.Item("微信", "com.tencent.mm", "2 小时", icon = "com.tencent.mm")), more = 3),
                ToolUiBlock.Output("hello", label = "输出", more = 2),
                ToolUiBlock.Preview("正文", label = "记住", more = true),
                ToolUiBlock.Change("40%", "70%", label = "亮度"),
                ToolUiBlock.Images(listOf("call-1#0")),
            ),
            transient = true,
        )
        assertEquals(view, ToolUiView.fromJson(JSONObject(view.toJson().toString())))
    }

    @Test
    fun transientView_reachesTheUi_butNotTheArchive() {
        val personal = finished(ToolUiView(summary = "找到 5 条", blocks = listOf(ToolUiBlock.Items(listOf(ToolUiBlock.Item("快递"))) ), transient = true))
        val terminal = finished(ToolUiView(summary = "退出码 0", blocks = listOf(ToolUiBlock.Output("ok"))))

        // 发给界面（IPC）：带上。
        assertEquals(personal.view, (AgentRuntimeWire.eventFromBundle(AgentRuntimeWire.eventToBundle(personal)) as AgentEvent.ToolFinished).view)
        // 写进归档：临时视图不带，普通视图带。
        assertNull((AgentEventJsonCodec.decode(AgentEventJsonCodec.encode(personal)) as AgentEvent.ToolFinished).view)
        assertEquals(terminal.view, (AgentEventJsonCodec.decode(AgentEventJsonCodec.encode(terminal)) as AgentEvent.ToolFinished).view)
        // 摘要一直都在。
        assertEquals("找到 5 条", (AgentEventJsonCodec.decode(AgentEventJsonCodec.encode(personal)) as AgentEvent.ToolFinished).resultSummary)
    }

    @Test
    fun secretResult_keepsOnlyTheSummary_andOnlyToolMarkedViewsAreTransient() {
        val secret = run(Fake("fake_secret", Sensitivity.SECRET))
        assertEquals("读到 1 条", secret.summary)
        assertTrue(secret.blocks.isEmpty())
        assertTrue(secret.transient)

        // PRIVATE 不等于个人数据：终端输出、记忆、设置这类重启后仍要能展开（真机 V9）。
        val private = run(Fake("fake_private", Sensitivity.PRIVATE))
        assertEquals(1, private.blocks.size)
        assertFalse(private.transient)

        // 个人数据由工具自己标为临时。
        val personal = run(Fake("fake_personal", Sensitivity.PRIVATE, markTransient = true))
        assertTrue(personal.transient)

        val normal = run(Fake("fake_normal", Sensitivity.NORMAL))
        assertFalse(normal.transient)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun images_goToTheInMemoryStore_andMakeTheViewTransient() {
        ToolStepImages.clearForTests()
        val view = run(Fake("fake_screen", Sensitivity.NORMAL, image = pngDataUrl()), callId = "call-img")
        val images = view.blocks.first() as ToolUiBlock.Images
        assertEquals(listOf("call-img#0"), images.keys)
        assertNotNull(ToolStepImages.get("call-img#0"))
        assertTrue("截图只在本次运行中显示", view.transient)
    }

    @Test
    fun stepTitle_comesFromTheTool_andNeedsValidArguments() {
        val pipeline = pipeline(Fake("fake_normal", Sensitivity.NORMAL))
        assertEquals("读取 · 快递", pipeline.stepTitle(AgentModelClient.ToolCall("c", "fake_normal", """{"q":"快递"}""")))
        assertNull(pipeline.stepTitle(AgentModelClient.ToolCall("c", "fake_normal", "not json")))
        assertNull(pipeline.stepTitle(AgentModelClient.ToolCall("c", "no_such_tool", "{}")))
    }

    // ---- 工具与管线 ----

    private data class In(val q: String) : ToolInput
    private data class Out(val n: Int) : ToolOutput

    private class Fake(
        override val name: String,
        private val sensitivity: Sensitivity,
        private val image: String? = null,
        private val markTransient: Boolean = false,
    ) : ToolContract<In, Out> {
        override val domain = ToolDomain.DEVICE
        override val summary = "测试"
        override fun schema(env: ToolEnvironment) = JSONObject().put("type", "object")
        override fun parse(args: ToolArgs, env: ToolEnvironment) = In(args.raw.optString("q"))
        override fun resolve(input: In, env: ToolEnvironment) =
            CallResolution(risk = Risk.READ, sensitivity = sensitivity, resources = emptySet())
        override fun execute(input: In, resolution: CallResolution, ctx: ToolContext): Verdict<Out> = Verdict.Read(Out(1))
        override fun renderForModel(output: Out) = ModelContent.Json(JSONObject().put("n", output.n))
        override fun images(output: Out) = listOfNotNull(
            image?.let { AgentModelClient.ModelImage(reference = it, mimeType = "image/png", bytes = it.length, source = "test") },
        )
        override fun uiTitle(input: In) = "读取 · ${input.q}"
        override fun renderForUi(input: In, output: Out) =
            ToolUiView(
                summary = "读到 ${output.n} 条",
                blocks = listOf(ToolUiBlock.Items(listOf(ToolUiBlock.Item("条目")))),
                transient = markTransient,
            )
    }

    private fun pipeline(tool: ToolContract<In, Out>): ToolPipeline = ToolPipeline(
        registry = ToolRegistry(listOf(object : ToolProvider {
            override val tools: List<AgentTool> = listOf(ContractTool(tool))
        })),
        environment = { ToolEnvironment() },
        appContext = ApplicationProvider.getApplicationContext(),
        logger = AndroidAgentLogger,
        runId = "run1",
        cancelled = { false },
        interaction = UserInteraction.NONE,
    ).also { it.catalog() }

    private fun run(tool: Fake, callId: String = "call-1"): ToolUiView =
        pipeline(tool).execute(AgentModelClient.ToolCall(callId, tool.name, """{"q":"x"}""")).outcome?.view
            ?: error("没有视图")

    private fun finished(view: ToolUiView) = AgentEvent.ToolFinished(
        round = 1, toolCallId = "c1", name = "personal_search", resultSummary = view.summary.orEmpty(),
        imageCount = 0, imageBytes = 0, success = true, view = view,
    )

    private fun pngDataUrl(): String {
        val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val bytes = ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() }
        bitmap.recycle()
        return "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
