package io.github.fartown.movo.data.provider

import io.github.fartown.movo.data.model.Model
import io.github.fartown.movo.data.model.ModelReasoningCapabilities
import io.github.fartown.movo.data.model.ModelSource
import io.github.fartown.movo.data.model.ReasoningEffort
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 火山方舟 Agent Plan（`https://ark.cn-beijing.volces.com/api/plan/v3`）的模型清单。
 *
 * Agent Plan 没有可用 API Key 访问的模型列表接口：`{baseUrl}/models` 返回 404，按量地址 `/api/v3/models` 不认 Plan 的 Key（401）。
 * 「拉取模型」时直接给出这份清单（[io.github.fartown.movo.data.repository.RemoteModelFetcher]）。
 * 来源：`arkcli plans model-list --plan agent-plan`（2026-10-07），逐个用 Responses + 函数工具实测可用；
 * 去掉 `auto`（Responses 报 UnsupportedModel）和 `doubao-seed-2-0-lite-260215`（同上）。套餐换模型时同步更新这里。
 * 模型 id 用接口返回的名字（`output_name`，与请求时的 `model_id` 都能用）。
 */
internal object ArkAgentPlanModels {
    fun matches(baseUrl: String): Boolean {
        val url = baseUrl.trim().toHttpUrlOrNull() ?: return false
        return url.host.endsWith("volces.com") && url.encodedPath.startsWith("/api/plan/")
    }

    /**
     * 套餐模型的思考档位（2026-10-07 接口实测）：都接受低 / 中 / 高；GLM-5.3、Kimi K2.7 Code 不接受关闭。
     * 默认档位不传，由模型自己决定。按地址和模型名现算，已存的服务商不用改；用户在模型设置里改过思考的以用户为准。
     */
    fun reasoning(baseUrl: String, model: Model): ModelReasoningCapabilities? {
        if (!matches(baseUrl) || model.reasoningOverride != null) return null
        return ModelReasoningCapabilities(
            supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
            canDisable = model.modelId.trim() !in CANNOT_DISABLE,
        )
    }

    private val CANNOT_DISABLE = setOf("glm-5.3", "glm-5-3-260801", "kimi-k2.7-code", "kimi-k2-7-code-260601")

    fun models(): List<Model> = CATALOG.mapIndexed { index, (modelId, displayName, contextWindow) ->
        Model(
            id = "ark-plan-$modelId",
            modelId = modelId,
            displayName = displayName,
            ownedBy = "volcengine",
            sortOrder = index,
            contextWindow = contextWindow,
            toolCall = true,
            reasoning = true,
            source = ModelSource.CATALOG,
        )
    }

    private val CATALOG: List<Triple<String, String, Int?>> = listOf(
        Triple("doubao-seed-evolving", "Doubao Seed Evolving", null),
        Triple("doubao-seed-2-1-pro-260915", "Doubao Seed 2.1 Pro", null),
        Triple("doubao-seed-2-1-lite-260915", "Doubao Seed 2.1 Lite", null),
        Triple("doubao-seed-2-1-turbo-260628", "Doubao Seed 2.1 Turbo", null),
        Triple("doubao-seed-2-0-mini-260428", "Doubao Seed 2.0 Mini", null),
        Triple("kimi-k2-8-preview", "Kimi K2.8 Preview", null),
        Triple("deepseek-v4-1-flash-260910", "DeepSeek V4.1 Flash", 1_048_576),
        Triple("glm-5-3-flash-260828", "GLM-5.3 Flash", null),
        Triple("glm-5.3", "GLM-5.3", 1_048_576),
        Triple("deepseek-v4-pro-ga-260813", "DeepSeek V4 Pro", null),
        Triple("deepseek-v4-flash-ga-260731", "DeepSeek V4 Flash", null),
        Triple("kimi-k3", "Kimi K3", null),
        Triple("kimi-k2.7-code", "Kimi K2.7 Code", null),
        Triple("minimax-m3", "MiniMax M3", null),
    )
}
