package io.github.fartown.movo.agent.tools.meta

import io.github.fartown.movo.agent.tools.core.AgentTool
import io.github.fartown.movo.agent.tools.core.Concurrency
import io.github.fartown.movo.agent.tools.core.PromptSection
import io.github.fartown.movo.agent.tools.core.QuestionOption
import io.github.fartown.movo.agent.tools.core.Risk
import io.github.fartown.movo.agent.tools.core.ToolArgs
import io.github.fartown.movo.agent.tools.core.ToolAvailability
import io.github.fartown.movo.agent.tools.core.ToolContext
import io.github.fartown.movo.agent.tools.core.ToolDomain
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.agent.tools.core.ToolOutcome
import io.github.fartown.movo.agent.tools.core.ToolPipeline
import io.github.fartown.movo.agent.tools.core.ToolProvider
import io.github.fartown.movo.agent.tools.core.ToolResource
import io.github.fartown.movo.agent.tools.core.UserAnswer
import io.github.fartown.movo.agent.tools.core.UserQuestion
import io.github.fartown.movo.agent.tools.core.codePointLength
import io.github.fartown.movo.agent.tools.core.invalidArgs
import io.github.fartown.movo.agent.tools.core.objectSchema
import org.json.JSONArray
import org.json.JSONObject

/**
 * 元工具：tool_search（加载按需工具）与 ask_user（向用户提问）。
 * [pipeline] 在管线构建完成后注入，用于读取按需工具清单与加载状态。
 */
internal class MetaToolProvider : ToolProvider {
    lateinit var pipeline: ToolPipeline

    override val tools: List<AgentTool> = listOf(ToolSearch(), AskUser())

    override val promptSection = PromptSection(
        id = "meta",
        domain = ToolDomain.META,
        text = """
            ## 工具结果与提问
            - 工具结果的 status：ok 表示已完成；error 表示确定没有生效；unknown 表示可能已生效但未确认，必须先观察或查询确认，不得直接重复。
            - retry：never 表示策略或能力拒绝，不要换工具绕过，应告诉用户；fix 修正参数后再试；observe 先观察再决定；later 可稍后重试一次；user 需要用户操作后才能继续。
            - 缺少必要信息、或有多个候选需要用户选择时，用 ask_user 提问，不要猜；危险动作的确认由系统自动处理，不需要用 ask_user 确认。
            - 需要的能力不在当前工具目录里时，先用 tool_search 查找并加载。
        """.trimIndent(),
    )

    private inner class ToolSearch : AgentTool {
        override val name = "tool_search"
        override val domain = ToolDomain.META
        override val description =
            "按关键词查找并加载低频工具（系统设置、诊断、健康数据、应用使用情况、技能安装、MCP 工具等）。" +
                "加载后的工具在下一次请求中可用。"

        override fun parameters(env: ToolEnvironment): JSONObject = objectSchema {
            string("query", "要找的能力，例如“系统设置”“内存占用”“安装技能”", required = true, maxLength = 100)
            stringArray("load", "要加载的工具名（来自查询结果）", maxItems = 10, maxLength = 64)
        }

        override fun availability(env: ToolEnvironment): ToolAvailability =
            if (::pipeline.isInitialized &&
                pipeline.registryView.hasDeferred(env, pipeline.loadedDeferredTools())
            ) {
                ToolAvailability.Available
            } else {
                ToolAvailability.Unavailable(ToolErrorCode.UNSUPPORTED, "当前没有可加载的按需工具")
            }

        override fun risk(args: ToolArgs, env: ToolEnvironment) = Risk.READ

        override fun execute(args: ToolArgs, ctx: ToolContext): ToolOutcome {
            val query = args.nonBlank("query").lowercase()
            val terms = query.split(' ', '，', ',', '、').filter { it.isNotBlank() }
            val loaded = pipeline.loadedDeferredTools()
            val candidates = pipeline.registryView.deferredTools()
            val matches = candidates.map { tool ->
                val haystack = (tool.name + " " + tool.description).lowercase()
                val score = terms.count { haystack.contains(it) } + if (haystack.contains(query)) 2 else 0
                tool to score
            }.filter { (_, score) -> score > 0 }
                .sortedByDescending { (_, score) -> score }
                .map { (tool, _) -> tool }
                .ifEmpty { candidates }
            val requested = args.stringList("load")
            val unknown = requested.filter { name -> candidates.none { it.name == name } }
            if (unknown.isNotEmpty()) invalidArgs("不是按需工具：${unknown.joinToString()}", "从 matches 中选择要加载的工具名")
            val newlyLoaded = pipeline.loadDeferred(requested)
            val unavailable = requested.filter { it !in newlyLoaded && it !in loaded }
            val data = JSONObject()
                .put("matches", JSONArray().also { array ->
                    matches.take(10).forEach { tool ->
                        val availability = tool.availability(ctx.env)
                        array.put(JSONObject()
                            .put("name", tool.name)
                            .put("summary", tool.description.take(160))
                            .put("loaded", tool.name in loaded || tool.name in newlyLoaded)
                            .apply {
                                if (availability is ToolAvailability.Unavailable) {
                                    put("unavailable", availability.reason)
                                }
                            })
                    }
                })
            if (requested.isNotEmpty()) data.put("loaded", JSONArray(newlyLoaded))
            if (unavailable.isNotEmpty()) data.put("not_loaded", JSONArray(unavailable))
            return ToolOutcome.ok(data)
        }
    }

    private inner class AskUser : AgentTool {
        override val name = "ask_user"
        override val domain = ToolDomain.META
        override val description =
            "向用户提一个问题并等待回答，用于缺少必要信息或有多个候选需要用户选择时。" +
                "不要用它确认危险动作，确认由系统自动处理。"

        override fun parameters(env: ToolEnvironment): JSONObject = objectSchema {
            string("question", "问题，简短明确", required = true, maxLength = 200)
            raw(
                "options",
                JSONObject()
                    .put("type", "array")
                    .put("description", "可选的候选（2–6 个）")
                    .put("minItems", 2)
                    .put("maxItems", 6)
                    .put("items", objectSchema {
                        string("label", "候选文字", required = true, maxLength = 60)
                        string("detail", "补充说明", maxLength = 120)
                    }),
            )
            boolean("allow_free_text", "是否允许用户自由回答，默认 true")
            integer("timeout_seconds", "等待时长，默认 120", min = 10, max = 600)
        }

        override fun availability(env: ToolEnvironment): ToolAvailability =
            if (env.interactive) ToolAvailability.Available
            else ToolAvailability.Unavailable(ToolErrorCode.UNSUPPORTED, "当前入口无法向用户提问")

        override fun risk(args: ToolArgs, env: ToolEnvironment) = Risk.READ
        override fun concurrency(args: ToolArgs, env: ToolEnvironment) = Concurrency.Exclusive(ToolResource.CONVERSATION)

        override fun execute(args: ToolArgs, ctx: ToolContext): ToolOutcome {
            val question = args.nonBlank("question")
            if (question.codePointLength() > 200) invalidArgs("question 最多 200 字")
            val options = args.array("options")?.let { array ->
                (0 until array.length()).map { index ->
                    val option = array.optJSONObject(index) ?: invalidArgs("options[$index] 应为对象")
                    QuestionOption(
                        label = option.optString("label").trim().ifEmpty { invalidArgs("options[$index].label 不能为空") },
                        detail = option.optString("detail").trim().ifEmpty { null },
                    )
                }
            }.orEmpty()
            val allowFreeText = args.bool("allow_free_text", true)
            if (options.isEmpty() && !allowFreeText) invalidArgs("没有候选时必须允许自由回答")
            val timeoutMs = args.int("timeout_seconds", 120, 10..600) * 1_000L
            if (!ctx.interaction.available) {
                return ToolOutcome.error(
                    ToolErrorCode.UNSUPPORTED,
                    "当前无法询问用户",
                    hint = "在最终回复中说明需要用户补充的信息",
                    detail = "no_interactive_surface",
                )
            }
            return when (val answer = ctx.interaction.ask(UserQuestion(question, options, allowFreeText), timeoutMs)) {
                is UserAnswer.Answered -> ToolOutcome.ok(
                    JSONObject().put("answer", answer.text).apply {
                        answer.optionIndex?.let { put("option_index", it) }
                    },
                )
                UserAnswer.Declined -> ToolOutcome.error(
                    ToolErrorCode.USER_DECLINED,
                    "用户取消了提问",
                    hint = "不要继续这一步；按已有信息结束或说明",
                )
                UserAnswer.TimedOut -> ToolOutcome.error(
                    ToolErrorCode.APPROVAL_TIMEOUT,
                    "用户没有回答",
                    hint = "结束本轮并说明需要什么信息",
                )
                UserAnswer.Unavailable -> ToolOutcome.error(
                    ToolErrorCode.UNSUPPORTED,
                    "当前无法询问用户",
                    hint = "在最终回复中说明需要用户补充的信息",
                    detail = "no_interactive_surface",
                )
            }
        }
    }

    companion object {
        val NAMES = setOf("tool_search", "ask_user")
    }
}

