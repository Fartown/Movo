package io.github.fartown.movo.data.provider

import io.github.fartown.movo.BuildConfig
import io.github.fartown.movo.data.model.Model
import io.github.fartown.movo.data.model.OpenAiCompatibleProviderSetting
import io.github.fartown.movo.data.model.OpenAiEndpointMode
import io.github.fartown.movo.data.model.ProviderSetting

/**
 * .env 打包进来的默认模型。首次安装时作为唯一的服务商写入；之后每次启动按包里的值对齐预置那条（[followPackage]，
 * 2026-10-07 定：预置的跟着包走——换了 Key / 地址 / 模型的新包覆盖安装后直接生效，不用清数据）。
 */
internal object PackagedModelDefaults {
    /** .env 打包进来的默认服务商 id：服务商页上挂「预置」标签，不能长按删除。 */
    const val PROVIDER_ID = "packaged-default-provider"

    fun provider(): OpenAiCompatibleProviderSetting? = createProvider(
        name = BuildConfig.MOVO_DEFAULT_PROVIDER_NAME,
        baseUrl = BuildConfig.MOVO_DEFAULT_BASE_URL,
        apiKey = BuildConfig.MOVO_DEFAULT_API_KEY,
        modelId = BuildConfig.MOVO_DEFAULT_MODEL_ID,
        modelName = BuildConfig.MOVO_DEFAULT_MODEL_NAME,
        endpointMode = BuildConfig.MOVO_DEFAULT_ENDPOINT_MODE,
        contextWindow = BuildConfig.MOVO_DEFAULT_CONTEXT_WINDOW.toIntOrNull(),
        hostedWebSearchEnabled = BuildConfig.MOVO_DEFAULT_HOSTED_WEB_SEARCH.toBoolean(),
    )

    /**
     * 已存的预置服务商对齐到包里的 [packaged]：名称、地址、Key、接口、联网搜索、系统提示、包里那个模型都换成包里的
     * （在 App 里对预置那条改过的这些值会被覆盖）；排序、启用开关、创建时间，以及包里那个模型的启用、思考与上下文覆盖保留。
     * 你在预置那条里另外加的模型（例如「拉取模型」加的 Agent Plan 其他模型）保留在后面。已经一致时返回 null（不写库）。
     */
    fun followPackage(stored: ProviderSetting, packaged: OpenAiCompatibleProviderSetting): ProviderSetting? {
        val storedModels = stored.models.associateBy(Model::id)
        val packagedIds = packaged.models.map(Model::id).toSet()
        val synced = packaged.copy(
            sortOrder = stored.sortOrder,
            isEnabled = stored.isEnabled,
            createdAt = stored.createdAt,
            models = packaged.models.map { model ->
                val kept = storedModels[model.id] ?: return@map model
                model.copy(
                    isEnabled = kept.isEnabled,
                    sortOrder = kept.sortOrder,
                    contextWindowOverride = kept.contextWindowOverride,
                    reasoningOverride = kept.reasoningOverride,
                    reasoningCapabilitiesOverride = kept.reasoningCapabilitiesOverride,
                    createdAt = kept.createdAt,
                )
            } + stored.models.filter { it.id !in packagedIds },
        )
        return synced.takeIf { it != stored }
    }

    internal fun createProvider(
        name: String,
        baseUrl: String,
        apiKey: String,
        modelId: String,
        modelName: String = modelId,
        endpointMode: String = OpenAiEndpointMode.RESPONSES,
        contextWindow: Int? = null,
        hostedWebSearchEnabled: Boolean = false,
    ): OpenAiCompatibleProviderSetting? {
        if (baseUrl.isBlank() || apiKey.isBlank() || modelId.isBlank()) return null
        return OpenAiCompatibleProviderSetting(
            id = PROVIDER_ID,
            name = name.trim().ifBlank { "默认模型" },
            baseUrl = baseUrl.trim().trimEnd('/'),
            apiKey = apiKey.trim(),
            sortOrder = -1,
            systemPrompt = BuiltinProviders.DEFAULT_SYSTEM_PROMPT,
            endpointMode = endpointMode.takeIf {
                it == OpenAiEndpointMode.RESPONSES || it == OpenAiEndpointMode.CHAT_COMPLETIONS
            } ?: OpenAiEndpointMode.RESPONSES,
            hostedWebSearchEnabled = hostedWebSearchEnabled,
            models = listOf(
                Model(
                    id = "packaged-default-model",
                    modelId = modelId.trim(),
                    displayName = modelName.trim().ifBlank { modelId.trim() },
                    contextWindow = contextWindow?.takeIf { it > 0 },
                    toolCall = true,
                )
            ),
        )
    }
}
