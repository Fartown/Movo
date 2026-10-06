package io.github.fartown.movo.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Responses 接口每一步的原始输出项（思考、调用编号与状态、回答的分段结构）。
 *
 * 一次任务内随助手消息原样回放；任务结束随历史存档（[AgentConversationCodec]），下一次任务里
 * 只要服务商、地址、接口和模型都没变（[origin] 一致）就原样回放，否则按普通助手消息重建。
 * 原样回放让跨任务的请求前缀逐字不变，提示缓存才能命中到上一次任务的工具循环之后（提示缓存方案第 3 版）。
 */
internal object ResponsesEphemeralState {
    private const val OUTPUT_ITEMS_KEY = "_movo_responses_output_items"
    private const val ORIGIN_KEY = "_movo_responses_origin"

    fun outputItems(message: JSONObject): JSONArray? =
        message.optJSONArray(OUTPUT_ITEMS_KEY)

    fun outputOrigin(message: JSONObject): String =
        message.optString(ORIGIN_KEY)

    fun attachOutputItems(message: JSONObject, items: JSONArray, origin: String = "") {
        message.put(OUTPUT_ITEMS_KEY, JSONArray(items.toString()))
        if (origin.isNotBlank()) message.put(ORIGIN_KEY, origin) else message.remove(ORIGIN_KEY)
    }

    fun setOrigin(message: JSONObject, origin: String) {
        if (outputItems(message) != null && origin.isNotBlank()) message.put(ORIGIN_KEY, origin)
    }

    fun copyOutputItems(source: JSONObject, target: JSONObject) {
        outputItems(source)?.let { attachOutputItems(target, it, outputOrigin(source)) }
    }

    /** 产出这些输出项的服务商、地址、接口与模型；任一项变了，旧输出项就不再原样回放。不含密钥。 */
    fun origin(config: AgentModelClient.ModelConfig): String =
        listOf(config.providerType, config.providerId, config.baseUrl.trimEnd('/'), config.openAiEndpointMode, config.model)
            .joinToString("|")
}
