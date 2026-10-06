package io.github.fartown.movo.data.repository

import android.content.Context
import io.github.fartown.movo.data.datastore.SettingsDataStore
import io.github.fartown.movo.data.db.MovoDatabase
import io.github.fartown.movo.data.model.AnthropicProviderSetting
import io.github.fartown.movo.data.model.CustomHeader
import io.github.fartown.movo.data.model.OpenAiCompatibleProviderSetting
import io.github.fartown.movo.data.model.ModelSource
import io.github.fartown.movo.data.model.ProviderSetting
import io.github.fartown.movo.data.model.ReasoningEffort
import io.github.fartown.movo.data.db.ProviderWithModelsSeed
import io.github.fartown.movo.data.db.toEntity
import io.github.fartown.movo.data.db.toModelEntities
import io.github.fartown.movo.data.model.CustomProviderSetting
import io.github.fartown.movo.data.model.Model
import io.github.fartown.movo.data.model.withApiKey
import io.github.fartown.movo.data.provider.BuiltinProviders
import io.github.fartown.movo.data.provider.ProviderCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProviderRepositoryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        MovoDatabase.closeForTests()
        context.deleteDatabase("movo.db")
        SettingsDataStore.init(context)
        ProviderRepository.init(context)
        // 不受本机 .env 打包的默认模型影响。
        ProviderRepository.packagedProvider = { null }
        runBlocking {
            // 仓库单例可能还拿着上一个 Robolectric Application：先用当前 Context 打开并清空测试库。
            MovoDatabase.get(context).providerDao().replaceAll(emptyList())
            SettingsDataStore.setSelection(providerId = null, modelId = null)
            SettingsDataStore.setProviderDataVersion(0)
        }
    }

    @After
    fun tearDown() {
        ProviderRepository.packagedProvider = io.github.fartown.movo.data.provider.PackagedModelDefaults::provider
    }

    @Test
    fun builtInProvidersRoundTripThroughRoomWithModels() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged(initialProvider = null)
        listOf(
            BuiltinProviders.OPENAI_ID,
            BuiltinProviders.ANTHROPIC_ID,
            BuiltinProviders.KIMI_ID,
            BuiltinProviders.BAILIAN_ID,
        ).forEach { addTemplate(it) }

        val providers = ProviderRepository.allProviders().associateBy { it.id }

        assertTrue(providers.getValue(BuiltinProviders.ANTHROPIC_ID) is AnthropicProviderSetting)
        assertEquals(
            listOf("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna", "gpt-5.5"),
            providers.getValue(BuiltinProviders.OPENAI_ID).models.map { it.modelId },
        )
        assertEquals(
            List(4) { ModelSource.CATALOG },
            providers.getValue(BuiltinProviders.OPENAI_ID).models.map { it.source },
        )
        assertEquals(
            listOf(
                ReasoningEffort.OFF,
                ReasoningEffort.DEFAULT,
                ReasoningEffort.MINIMAL,
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH,
                ReasoningEffort.XHIGH,
            ),
            providers.getValue(BuiltinProviders.OPENAI_ID)
                .models
                .first()
                .reasoningCapabilities
                ?.selectableEfforts,
        )
        assertEquals(
            listOf("claude-fable-5", "claude-opus-4-8", "claude-sonnet-5"),
            providers.getValue(BuiltinProviders.ANTHROPIC_ID).models.map { it.modelId },
        )
        assertEquals(
            listOf(
                "kimi-k3",
                "kimi-k2.7-code",
                "kimi-k2.7-code-highspeed",
                "kimi-k2.6",
                "kimi-k2.5",
            ),
            providers.getValue(BuiltinProviders.KIMI_ID).models.map { it.modelId },
        )
        assertTrue(
            providers.getValue(BuiltinProviders.BAILIAN_ID).models.none {
                it.modelId == "kimi-k3"
            }
        )
    }

    @Test
    fun providerAndModelCustomHeadersSurviveRoomRoundTrip() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged(initialProvider = null)
        addTemplate(BuiltinProviders.OPENAI_ID)
        val provider = ProviderRepository.providerById(BuiltinProviders.OPENAI_ID)!!
        val updated = provider.copyForTest(
            customHeaders = listOf(CustomHeader("x-provider", "1")),
        ).let { openAi ->
            openAi.copy(
                models = openAi.models.mapIndexed { index, model ->
                    if (index == 0) {
                        model.copy(customHeaders = listOf(CustomHeader("x-model", "2")))
                    } else {
                        model
                    }
                }
            )
        }

        ProviderRepository.updateProvider(updated)
        ModelRepository.saveModel(
            provider.id,
            updated.models.first(),
        )

        val restored = ProviderRepository.providerById(BuiltinProviders.OPENAI_ID)!!
        assertEquals(listOf("x-provider"), restored.customHeaders.map { it.name })
        assertEquals(listOf("x-model"), restored.models.first().customHeaders.map { it.name })
    }

    @Test
    fun selectedRuntimeConfigUsesUpdatedProviderApiKey() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged(initialProvider = null)
        addTemplate(BuiltinProviders.OPENAI_ID, apiKey = "sk-old-key")
        val provider = (ProviderRepository.providerById(BuiltinProviders.OPENAI_ID) as OpenAiCompatibleProviderSetting)
            .copy(apiKey = "sk-test-key")

        ProviderRepository.updateProvider(provider)
        RuntimeConfigRepository.setSelectedProviderId(provider.id)

        val config = RuntimeConfigRepository.currentRuntimeConfig()
        requireNotNull(config)
        assertEquals(provider.id, config.providerId)
        assertEquals("sk-test-key", config.apiKey)
    }

    @Test
    fun switchingProvidersRestoresEachProvidersSelectedModel() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged(initialProvider = null)
        addTemplate(BuiltinProviders.OPENAI_ID)
        addTemplate(BuiltinProviders.ANTHROPIC_ID)
        val openAi = ProviderRepository.providerById(BuiltinProviders.OPENAI_ID)!!
        val anthropic = ProviderRepository.providerById(BuiltinProviders.ANTHROPIC_ID)!!
        val openAiModel = openAi.models[1]
        val anthropicModel = anthropic.models[1]
        SettingsDataStore.clearSelectedModelIdForProvider(openAi.id)
        SettingsDataStore.clearSelectedModelIdForProvider(anthropic.id)

        RuntimeConfigRepository.setSelectedProviderId(openAi.id)
        RuntimeConfigRepository.setSelectedModelId(openAiModel.id)
        RuntimeConfigRepository.setSelectedProviderId(anthropic.id)
        RuntimeConfigRepository.setSelectedModelId(anthropicModel.id)

        RuntimeConfigRepository.setSelectedProviderId(openAi.id)
        assertEquals(openAiModel.id, SettingsDataStore.settings().selectedModelId)

        RuntimeConfigRepository.setSelectedProviderId(anthropic.id)
        assertEquals(anthropicModel.id, SettingsDataStore.settings().selectedModelId)
    }

    @Test
    fun repairSelectionMigratesLegacyActiveModelToProviderMemory() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged(initialProvider = null)
        addTemplate(BuiltinProviders.OPENAI_ID)
        val provider = ProviderRepository.providerById(BuiltinProviders.OPENAI_ID)!!
        val model = provider.models[1]
        SettingsDataStore.clearSelectedModelIdForProvider(provider.id)
        SettingsDataStore.updateSettings {
            it.copy(selectedProviderId = provider.id, selectedModelId = model.id)
        }

        ProviderRepository.repairSelection()

        assertEquals(model.id, SettingsDataStore.selectedModelIdForProvider(provider.id))
    }

    @Test
    fun firstInstallStoresOnlyChatGptRecordAndSelectsNothingWithoutPackagedProvider() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged(initialProvider = null)

        assertEquals(listOf(BuiltinProviders.CHATGPT_ID), ProviderRepository.allProviders().map { it.id })
        assertTrue(ProviderRepository.providerById(BuiltinProviders.CHATGPT_ID)!!.models.isNotEmpty())
        assertNull(SettingsDataStore.settings().selectedProviderId)
        assertEquals(ProviderRepository.PROVIDER_DATA_VERSION, SettingsDataStore.providerDataVersion())
    }

    @Test
    fun upgradePrunesKeylessPresetsExceptSelectedAndChatGptOnlyOnce() = runBlocking {
        // 旧版本：全部内置预设都在库里占位；OpenAI 填过 Key，Kimi 没 Key 但是当前选中，另有一个没 Key 的自建服务商。
        seedLegacyPresets(apiKeys = mapOf(BuiltinProviders.OPENAI_ID to "sk-openai"))
        ProviderRepository.addProvider(customProvider(id = "user-custom", apiKey = ""))
        SettingsDataStore.setSelection(BuiltinProviders.KIMI_ID, null)

        ProviderRepository.ensureBuiltInsMerged(initialProvider = null)

        assertEquals(
            setOf(BuiltinProviders.OPENAI_ID, BuiltinProviders.KIMI_ID, BuiltinProviders.CHATGPT_ID, "user-custom"),
            ProviderRepository.allProviders().map { it.id }.toSet(),
        )
        assertEquals(ProviderRepository.PROVIDER_DATA_VERSION, SettingsDataStore.providerDataVersion())
        // 保留下来的 Kimi 没有 Key、不可用：当前选择换成可用的 OpenAI。
        assertEquals(BuiltinProviders.OPENAI_ID, SettingsDataStore.settings().selectedProviderId)
        // 留下的无 Key 占位不算「我的服务商」，它的模板仍在「可以添加」里。
        val providers = ProviderRepository.allProviders()
        assertEquals(
            listOf(BuiltinProviders.OPENAI_ID, "user-custom"),
            ProviderCatalog.configuredProviders(providers).map { it.id },
        )
        assertTrue(ProviderCatalog.availableTemplates(providers).any { it.id == BuiltinProviders.KIMI_ID })

        // 迁移只做一次：之后再出现的无 Key 预设记录不再被清理。
        seedLegacyPresets(ids = listOf(BuiltinProviders.DEEPSEEK_ID))
        ProviderRepository.ensureBuiltInsMerged(initialProvider = null)
        assertNotNull(ProviderRepository.providerById(BuiltinProviders.DEEPSEEK_ID))
    }

    @Test
    fun repairSelectionKeepsUsableSelectionAndFallsBackToUsableProvider() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged(initialProvider = null)
        ProviderRepository.addProvider(customProvider(id = "keyless", apiKey = ""))
        ProviderRepository.addProvider(customProvider(id = "keyed-a", apiKey = "sk-a"))
        ProviderRepository.addProvider(customProvider(id = "keyed-b", apiKey = "sk-b"))

        SettingsDataStore.setSelection("keyed-b", null)
        assertEquals("keyed-b", ProviderRepository.repairSelection(chatGptLoggedIn = false).selectedProviderId)

        // 选中的没有 Key：不选排在前面的无 Key 服务商，换成第一个可用的。
        SettingsDataStore.setSelection("keyless", null)
        assertEquals("keyed-a", ProviderRepository.repairSelection(chatGptLoggedIn = false).selectedProviderId)

        SettingsDataStore.setSelection("missing", null)
        assertEquals("keyed-a", ProviderRepository.repairSelection(chatGptLoggedIn = false).selectedProviderId)
    }

    @Test
    fun repairSelectionPicksLoggedInChatGptOrNothing() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged(initialProvider = null)
        ProviderRepository.addProvider(customProvider(id = "keyless", apiKey = ""))

        val loggedOut = ProviderRepository.repairSelection(chatGptLoggedIn = false)
        assertNull(loggedOut.selectedProviderId)
        assertNull(loggedOut.selectedModelId)

        val loggedIn = ProviderRepository.repairSelection(chatGptLoggedIn = true)
        assertEquals(BuiltinProviders.CHATGPT_ID, loggedIn.selectedProviderId)
        assertNotNull(loggedIn.selectedModelId)

        // 退出登录后 ChatGPT 不再可用，也没有别的可用服务商：不选。
        assertNull(ProviderRepository.repairSelection(chatGptLoggedIn = false).selectedProviderId)
    }

    @Test
    fun templateIsStoredOnlyAfterAddingWithApiKey() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged(initialProvider = null)
        val draft = ProviderRepository.templateDraft(BuiltinProviders.DEEPSEEK_ID)!!

        // 打开模板只生成草稿（带官方模型目录），不写库。
        assertTrue(draft.models.isNotEmpty())
        assertNull(ProviderRepository.providerById(BuiltinProviders.DEEPSEEK_ID))
        assertNull(ProviderRepository.templateDraft(BuiltinProviders.CHATGPT_ID))
        assertTrue(runCatching { ProviderRepository.addFromTemplate(draft) }.isFailure)
        assertNull(ProviderRepository.providerById(BuiltinProviders.DEEPSEEK_ID))
        assertTrue(
            ProviderCatalog.availableTemplates(ProviderRepository.allProviders())
                .any { it.id == BuiltinProviders.DEEPSEEK_ID }
        )

        val added = ProviderRepository.addFromTemplate(draft.withApiKey("sk-deepseek"))

        val stored = ProviderRepository.providerById(BuiltinProviders.DEEPSEEK_ID)!!
        assertEquals(added, stored)
        assertEquals("sk-deepseek", stored.apiKey)
        assertEquals(draft.models.map { it.modelId }, stored.models.map { it.modelId })
        val providers = ProviderRepository.allProviders()
        assertTrue(ProviderCatalog.configuredProviders(providers).any { it.id == BuiltinProviders.DEEPSEEK_ID })
        assertTrue(ProviderCatalog.availableTemplates(providers).none { it.id == BuiltinProviders.DEEPSEEK_ID })
        // 没有别的可用服务商时，新添加的成为当前。
        assertEquals(BuiltinProviders.DEEPSEEK_ID, SettingsDataStore.settings().selectedProviderId)
    }

    @Test
    fun addedPresetCanBeDeletedButChatGptRecordCannot() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged(initialProvider = null)
        addTemplate(BuiltinProviders.KIMI_ID)

        ProviderRepository.deleteProvider(BuiltinProviders.KIMI_ID)
        ProviderRepository.deleteProvider(BuiltinProviders.CHATGPT_ID)

        assertNull(ProviderRepository.providerById(BuiltinProviders.KIMI_ID))
        assertNotNull(ProviderRepository.providerById(BuiltinProviders.CHATGPT_ID))
        assertTrue(
            ProviderCatalog.availableTemplates(ProviderRepository.allProviders())
                .any { it.id == BuiltinProviders.KIMI_ID }
        )
    }

    private suspend fun addTemplate(id: String, apiKey: String = "sk-test"): ProviderSetting =
        ProviderRepository.addFromTemplate(ProviderRepository.templateDraft(id)!!.withApiKey(apiKey))

    /** 模拟旧版本写入的内置预设占位（直接写库，不经过迁移）。 */
    private suspend fun seedLegacyPresets(
        ids: List<String> = BuiltinProviders.PROVIDERS.map { it.id },
        apiKeys: Map<String, String> = emptyMap(),
    ) {
        MovoDatabase.get(context).providerDao().insertProvidersWithModels(
            ids.map { id ->
                val preset = BuiltinProviders.providerById(id)!!.withApiKey(apiKeys[id].orEmpty())
                ProviderWithModelsSeed(provider = preset.toEntity(), models = preset.toModelEntities())
            }
        )
    }

    private fun customProvider(id: String, apiKey: String): CustomProviderSetting =
        CustomProviderSetting(
            id = id,
            name = id,
            baseUrl = "https://example.com/v1",
            apiKey = apiKey,
            models = listOf(Model(id = "$id-model", modelId = "$id-model", displayName = "$id model")),
        )
}

private fun ProviderSetting.copyForTest(
    customHeaders: List<CustomHeader>,
): OpenAiCompatibleProviderSetting =
    (this as OpenAiCompatibleProviderSetting).copy(customHeaders = customHeaders)
