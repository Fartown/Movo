package io.github.fartown.movo.data.provider

import io.github.fartown.movo.data.model.CustomProviderSetting
import io.github.fartown.movo.data.model.Model
import io.github.fartown.movo.data.model.ProviderSetting
import io.github.fartown.movo.data.model.withApiKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCatalogTest {
    private val chatGpt = preset(BuiltinProviders.CHATGPT_ID)
    private val packaged = requireNotNull(
        PackagedModelDefaults.createProvider(
            name = "火山方舟",
            baseUrl = "https://example.com/v3",
            apiKey = "packaged-key",
            modelId = "packaged-model",
        )
    )

    @Test
    fun templatesFollowDesignOrderAndExcludeChatGpt() {
        assertEquals(
            listOf(
                BuiltinProviders.OPENAI_ID,
                BuiltinProviders.ANTHROPIC_ID,
                BuiltinProviders.DEEPSEEK_ID,
                BuiltinProviders.KIMI_ID,
                BuiltinProviders.BAILIAN_ID,
                BuiltinProviders.MINIMAX_ID,
                BuiltinProviders.SILICONFLOW_ID,
                BuiltinProviders.OPENROUTER_ID,
                BuiltinProviders.STEPFUN_ID,
                BuiltinProviders.MIMO_ID,
            ),
            ProviderCatalog.availableTemplates(emptyList()).map { it.id },
        )
    }

    @Test
    fun configuredProvidersAreOwnOrPackagedOrPresetsWithKey() {
        val keyedPreset = preset(BuiltinProviders.DEEPSEEK_ID).withApiKey("sk-deepseek")
        val keylessPreset = preset(BuiltinProviders.KIMI_ID)
        val ownKeyless = custom("own", apiKey = "")

        val configured = ProviderCatalog.configuredProviders(
            listOf(packaged, chatGpt.withApiKey("ignored"), keyedPreset, keylessPreset, ownKeyless)
        )

        assertEquals(listOf(packaged.id, keyedPreset.id, ownKeyless.id), configured.map { it.id })
        assertTrue(ProviderCatalog.isPackaged(packaged))
        assertFalse(ProviderCatalog.isPackaged(keyedPreset))
    }

    @Test
    fun addedTemplatesDisappearFromAvailableButKeylessPlaceholdersStay() {
        val available = ProviderCatalog.availableTemplates(
            listOf(
                chatGpt,
                preset(BuiltinProviders.DEEPSEEK_ID).withApiKey("sk-deepseek"),
                preset(BuiltinProviders.KIMI_ID),
                // 自建服务商即使指向同一家（官方地址），也不影响模板。
                custom("own-openai", apiKey = "sk", baseUrl = "https://api.openai.com/v1"),
            )
        ).map { it.id }

        assertFalse(BuiltinProviders.DEEPSEEK_ID in available)
        assertTrue(BuiltinProviders.KIMI_ID in available)
        assertTrue(BuiltinProviders.OPENAI_ID in available)
        assertFalse(BuiltinProviders.CHATGPT_ID in available)
        assertEquals(BuiltinProviders.TEMPLATES.size - 1, available.size)
    }

    @Test
    fun migrationPrunesOnlyKeylessUnselectedPresets() {
        val selected = BuiltinProviders.KIMI_ID

        assertTrue(ProviderCatalog.isPresetPlaceholder(preset(BuiltinProviders.OPENAI_ID), selected))
        assertFalse(ProviderCatalog.isPresetPlaceholder(preset(BuiltinProviders.KIMI_ID), selected))
        assertFalse(ProviderCatalog.isPresetPlaceholder(preset(BuiltinProviders.OPENAI_ID).withApiKey("sk"), selected))
        assertFalse(ProviderCatalog.isPresetPlaceholder(chatGpt, selected))
        assertFalse(ProviderCatalog.isPresetPlaceholder(custom("own", apiKey = ""), selected))
        assertFalse(ProviderCatalog.isPresetPlaceholder(packaged, selected))
    }

    @Test
    fun fallbackSelectionPrefersUsableProvidersWithModels() {
        val keyless = custom("keyless", apiKey = "", sortOrder = 0)
        val keyedWithoutModels = custom("empty", apiKey = "sk", sortOrder = 1, models = emptyList())
        val keyed = custom("keyed", apiKey = "sk", sortOrder = 2)
        val disabled = custom("disabled", apiKey = "sk", sortOrder = -5).copy(isEnabled = false)

        assertEquals("keyed", ProviderCatalog.fallbackSelection(listOf(keyless, keyedWithoutModels, keyed, disabled), false)?.id)
        assertEquals("empty", ProviderCatalog.fallbackSelection(listOf(keyless, keyedWithoutModels), false)?.id)
        assertNull(ProviderCatalog.fallbackSelection(listOf(keyless, disabled, chatGpt), chatGptLoggedIn = false))
        assertEquals(
            BuiltinProviders.CHATGPT_ID,
            ProviderCatalog.fallbackSelection(listOf(keyless, chatGpt), chatGptLoggedIn = true)?.id,
        )
    }

    private fun preset(id: String): ProviderSetting =
        BuiltinProviders.providerById(id)!!.let { preset ->
            // 带一个模型，和落库后（官方模型目录）一致。
            when (preset) {
                is io.github.fartown.movo.data.model.OpenAiCompatibleProviderSetting -> preset.copy(models = listOf(model(id)))
                is io.github.fartown.movo.data.model.AnthropicProviderSetting -> preset.copy(models = listOf(model(id)))
                is CustomProviderSetting -> preset.copy(models = listOf(model(id)))
            }
        }

    private fun custom(
        id: String,
        apiKey: String,
        sortOrder: Int = 20,
        baseUrl: String = "https://example.com/v1",
        models: List<Model> = listOf(model(id)),
    ) = CustomProviderSetting(
        id = id,
        name = id,
        baseUrl = baseUrl,
        apiKey = apiKey,
        sortOrder = sortOrder,
        models = models,
    )

    private fun model(owner: String) = Model(id = "$owner-model", modelId = "$owner-model", displayName = owner)
}
