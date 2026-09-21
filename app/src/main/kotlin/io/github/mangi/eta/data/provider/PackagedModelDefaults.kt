package io.github.mangi.eta.data.provider

import io.github.mangi.eta.BuildConfig
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.OpenAiEndpointMode

/** A first-install seed. Once saved, the normal editable database owns this configuration. */
internal object PackagedModelDefaults {
    fun provider(): OpenAiCompatibleProviderSetting? = createProvider(
        name = BuildConfig.ETA_DEFAULT_PROVIDER_NAME,
        baseUrl = BuildConfig.ETA_DEFAULT_BASE_URL,
        apiKey = BuildConfig.ETA_DEFAULT_API_KEY,
        modelId = BuildConfig.ETA_DEFAULT_MODEL_ID,
        modelName = BuildConfig.ETA_DEFAULT_MODEL_NAME,
        endpointMode = BuildConfig.ETA_DEFAULT_ENDPOINT_MODE,
        contextWindow = BuildConfig.ETA_DEFAULT_CONTEXT_WINDOW.toIntOrNull(),
        hostedWebSearchEnabled = BuildConfig.ETA_DEFAULT_HOSTED_WEB_SEARCH.toBoolean(),
    )

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
            id = "packaged-default-provider",
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
