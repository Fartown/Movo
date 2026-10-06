package io.github.fartown.movo.agent.tools.ui

import io.github.fartown.movo.agent.tools.core.ToolUiBlock
import io.github.fartown.movo.agent.tools.core.ToolUiView
import io.github.fartown.movo.agent.tools.core.forTitle
import io.github.fartown.movo.agent.tools.core.CallResolution
import io.github.fartown.movo.agent.tools.core.ModelContent
import io.github.fartown.movo.agent.tools.core.ResourceKey
import io.github.fartown.movo.agent.tools.core.Risk
import io.github.fartown.movo.agent.tools.core.Sensitivity
import io.github.fartown.movo.agent.tools.core.ToolArgs
import io.github.fartown.movo.agent.tools.core.ToolContext
import io.github.fartown.movo.agent.tools.core.ToolContract
import io.github.fartown.movo.agent.tools.core.ToolDomain
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolError
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.agent.tools.core.ToolInput
import io.github.fartown.movo.agent.tools.core.ToolOutput
import io.github.fartown.movo.agent.tools.core.ToolResource
import io.github.fartown.movo.agent.tools.core.Verdict
import io.github.fartown.movo.agent.tools.core.invalidArgs
import io.github.fartown.movo.agent.tools.core.objectSchema
import org.json.JSONObject

/**
 * §15 ui_wait。等屏幕出现/消失指定文字，或指定应用到前台，或等一段时间。
 * 超时返回 ok + matched:false（不报 TIMEOUT）。只等时长时不占屏幕。
 */

internal sealed interface UiWaitCondition {
    data class Text(val text: String, val match: WaitMatch, val gone: Boolean) : UiWaitCondition
    data class Package(val packageName: String) : UiWaitCondition
    data class Duration(val durationMs: Int) : UiWaitCondition
}

internal data class UiWaitInput(
    val condition: UiWaitCondition,
    val timeoutMs: Int,
) : ToolInput

internal data class UiWaitOutput(
    val matched: Boolean,
    val elapsedMs: Long,
    val node: UiNodeProbe?,
) : ToolOutput

internal class UiWaitTool(
    private val backend: UiActionBackend,
) : ToolContract<UiWaitInput, UiWaitOutput> {
    override val name = "ui_wait"
    override val domain = ToolDomain.UI
    override val summary =
        "等条件满足：text(+match/gone)、package 到前台、或 duration_ms 等时长，四选一。" +
            "超时返回 ok 且 matched=false，不是错误。"

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string("text", "等待出现/消失的文字（四选一之一）")
        string(
            "match", "文字匹配方式：contains（默认）、exact、prefix、regex",
            enum = WaitMatch.entries.map { it.name.lowercase() },
        )
        boolean("gone", "等文字消失（配合 text）")
        string("package", "等该应用到前台（四选一之一）")
        integer("duration_ms", "只等一段时间（四选一之一）", min = 1)
        integer("timeout_ms", "超时毫秒 500–60000，默认 10000", min = 500, max = 60000)
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): UiWaitInput {
        val hasText = args.has("text")
        val hasPackage = args.has("package")
        val hasDuration = args.has("duration_ms")
        val count = listOf(hasText, hasPackage, hasDuration).count { it }
        if (count != 1) invalidArgs("text、package、duration_ms 必须且只能提供一个")
        val condition = when {
            hasText -> {
                val match = args.enum("match", WaitMatch.CONTAINS)
                val text = args.nonBlank("text")
                if (match == WaitMatch.REGEX) {
                    runCatching { Regex(text) }.getOrElse { invalidArgs("regex 无效：${it.message}") }
                }
                UiWaitCondition.Text(text, match, args.bool("gone", false))
            }
            hasPackage -> UiWaitCondition.Package(args.nonBlank("package"))
            else -> UiWaitCondition.Duration(args.int("duration_ms"))
        }
        return UiWaitInput(condition, args.int("timeout_ms", 10000, 500..60000))
    }

    override fun resolve(input: UiWaitInput, env: ToolEnvironment): CallResolution {
        // 只等时长不占屏幕；等文字/应用需反复观察屏幕 → 占 SCREEN。
        val resources = if (input.condition is UiWaitCondition.Duration) {
            emptySet()
        } else {
            setOf(ResourceKey(ToolResource.SCREEN))
        }
        return CallResolution(risk = Risk.READ, sensitivity = Sensitivity.NORMAL, resources = resources)
    }

    override fun execute(input: UiWaitInput, resolution: CallResolution, ctx: ToolContext): Verdict<UiWaitOutput> {
        ctx.checkCancelled()
        // 等文字/应用需要无障碍；只等时长不需要。
        if (input.condition !is UiWaitCondition.Duration && !ctx.env.accessibilityUsable && !ctx.env.rootAvailable) {
            return Verdict.Failed(
                ToolError(ToolErrorCode.PERMISSION_REQUIRED, "无障碍不可用，无法等待屏幕条件", hint = "只等时长可用 duration_ms"),
            )
        }
        val request = when (val c = input.condition) {
            is UiWaitCondition.Text -> UiWaitRequest(c.text, c.match, c.gone, null, null, input.timeoutMs)
            is UiWaitCondition.Package -> UiWaitRequest(null, WaitMatch.CONTAINS, false, c.packageName, null, input.timeoutMs)
            is UiWaitCondition.Duration -> UiWaitRequest(null, WaitMatch.CONTAINS, false, null, c.durationMs, input.timeoutMs)
        }
        return when (val result = backend.waitFor(request, ctx.env, ctx::checkCancelled)) {
            is UiWaitResult.Finished -> Verdict.Read(UiWaitOutput(result.matched, result.elapsedMs, result.node))
            is UiWaitResult.PermissionRequired -> Verdict.Failed(
                ToolError(ToolErrorCode.PERMISSION_REQUIRED, "无障碍不可用，无法等待屏幕条件"),
            )
        }
    }

    override fun uiTitle(input: UiWaitInput): String = when (val c = input.condition) {
        is UiWaitCondition.Text -> "等「${c.text.forTitle()}」${if (c.gone) "消失" else "出现"}"
        is UiWaitCondition.Package -> "等「${appLabel(c.packageName)}」打开"
        is UiWaitCondition.Duration -> "等 ${seconds(c.durationMs.toLong())}"
    }

    override fun renderForUi(input: UiWaitInput, output: UiWaitOutput): ToolUiView = ToolUiView(
        summary = when {
            input.condition is UiWaitCondition.Duration -> "已等 ${seconds(output.elapsedMs)}"
            output.matched -> "等到了 · ${seconds(output.elapsedMs)}"
            else -> "没等到 · ${seconds(output.elapsedMs)}后超时"
        },
    )

    private fun seconds(ms: Long): String =
        if (ms < 10_000) String.format(java.util.Locale.US, "%.1f 秒", ms / 1000.0).replace(".0 秒", " 秒") else "${ms / 1000} 秒"

    override fun renderForModel(output: UiWaitOutput): ModelContent {
        val json = JSONObject().put("matched", output.matched).put("elapsed_ms", output.elapsedMs)
        output.node?.let { json.put("node", it.toJson()) }
        return ModelContent.Json(json)
    }
}
