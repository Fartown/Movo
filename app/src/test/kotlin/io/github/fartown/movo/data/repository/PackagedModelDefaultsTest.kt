package io.github.fartown.movo.data.repository

import android.content.Context
import io.github.fartown.movo.data.datastore.SettingsDataStore
import io.github.fartown.movo.data.db.MovoDatabase
import io.github.fartown.movo.data.model.OpenAiEndpointMode
import io.github.fartown.movo.data.model.withModels
import io.github.fartown.movo.data.provider.BuiltinProviders
import io.github.fartown.movo.data.provider.PackagedModelDefaults
import io.github.fartown.movo.data.provider.ProviderCatalog
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class PackagedModelDefaultsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        MovoDatabase.closeForTests()
        context.deleteDatabase("movo.db")
        SettingsDataStore.init(context)
        ProviderRepository.init(context)
        // 读运行配置时也会对齐预置服务商：默认用本测试的包，不受本机 .env 影响。
        ProviderRepository.packagedProvider = { preset() }
        runBlocking {
            // Repository singletons can retain a prior Robolectric application Context.
            // Open the current test database explicitly before accessing those repositories.
            MovoDatabase.get(context).providerDao().replaceAll(emptyList())
            SettingsDataStore.setSelection(null, null)
            SettingsDataStore.setProviderDataVersion(0)
            assertTrue(ProviderRepository.allProviders().isEmpty())
        }
    }

    @After
    fun tearDown() {
        ProviderRepository.packagedProvider = PackagedModelDefaults::provider
    }

    @Test
    fun incompletePackagedConfigKeepsNormalProviderSetup() = runBlocking {
        ProviderRepository.packagedProvider = { null }
        assertNull(PackagedModelDefaults.createProvider("Test", "", "key", "model"))
        assertNull(PackagedModelDefaults.createProvider("Test", "https://example.com/v1", " ", "model"))
        assertNull(PackagedModelDefaults.createProvider("Test", "https://example.com/v1", "key", ""))

        ProviderRepository.ensureBuiltInsMerged(initialProvider = null)

        // 内置预设是模板、不落库：只有承载订阅登录的 ChatGPT 记录；没有可用的服务商时不选（输入框提示「未配置模型」）。
        assertEquals(listOf(BuiltinProviders.CHATGPT_ID), ProviderRepository.allProviders().map { it.id })
        assertNull(SettingsDataStore.settings().selectedProviderId)
    }

    @Test
    fun firstInstallSelectsEditablePackagedModelWithWorkingRuntimeConfig() = runBlocking {
        val preset = preset()

        ProviderRepository.ensureBuiltInsMerged(preset)

        val stored = ProviderRepository.providerById(preset.id)!!
        val config = RuntimeConfigRepository.currentRuntimeConfig()!!
        assertEquals(
            setOf(preset.id, BuiltinProviders.CHATGPT_ID),
            ProviderRepository.allProviders().map { it.id }.toSet(),
        )
        assertTrue(ProviderCatalog.isPackaged(stored))
        assertTrue(ProviderCatalog.isConfigured(stored))
        assertFalse(stored.isBuiltIn)
        assertFalse(stored.models.single().isBuiltIn)
        assertEquals(preset.id, SettingsDataStore.settings().selectedProviderId)
        assertEquals(preset.models.single().id, SettingsDataStore.settings().selectedModelId)
        assertEquals("https://example.com/v3", config.baseUrl)
        assertEquals("test-key", config.apiKey)
        assertEquals("test-model", config.model)
        assertEquals(OpenAiEndpointMode.RESPONSES, config.openAiEndpointMode)
        assertEquals(1_048_576, config.contextWindow)
        assertTrue(config.hostedWebSearchEnabled)
    }

    @Test
    fun presetFollowsANewPackageAfterUpgrade() = runBlocking {
        // 2026-10-07 定：预置的跟着包走。旧包写入的预置（旧地址 / Key / 模型）在装了新包后换成新包的值，选择不变。
        val old = preset()
        ProviderRepository.ensureBuiltInsMerged(old)
        ProviderRepository.updateProvider(old.copy(baseUrl = "https://edited.example/v1", apiKey = "edited-key"))
        MovoDatabase.closeForTests()
        MovoDatabase.get(context)

        val next = requireNotNull(
            PackagedModelDefaults.createProvider(
                name = "Ark Plan",
                baseUrl = "https://ark.example/api/plan/v3",
                apiKey = "plan-key",
                modelId = "plan-model",
                contextWindow = 1_048_576,
                hostedWebSearchEnabled = true,
            )
        )
        ProviderRepository.packagedProvider = { next }
        ProviderRepository.ensureBuiltInsMerged(next)

        val config = RuntimeConfigRepository.currentRuntimeConfig()!!
        assertEquals("https://ark.example/api/plan/v3", config.baseUrl)
        assertEquals("plan-key", config.apiKey)
        assertEquals("plan-model", config.model)
        assertEquals("Ark Plan", ProviderRepository.providerById(old.id)!!.name)
        assertEquals(old.id, SettingsDataStore.settings().selectedProviderId)
        assertEquals(old.models.single().id, SettingsDataStore.settings().selectedModelId)
    }

    @Test
    fun followingThePackageKeepsPersonalSettingsAndIsIdempotent() = runBlocking {
        val preset = preset()
        ProviderRepository.ensureBuiltInsMerged(preset)
        val stored = ProviderRepository.providerById(preset.id) as io.github.fartown.movo.data.model.OpenAiCompatibleProviderSetting
        ProviderRepository.updateProvider(stored.copy(sortOrder = 7, apiKey = "edited-key"))
        ModelRepository.saveModel(preset.id, stored.models.single().copy(reasoningOverride = false, contextWindowOverride = 65_536))

        ProviderRepository.ensureBuiltInsMerged(preset)

        val synced = ProviderRepository.providerById(preset.id)!!
        assertEquals("test-key", synced.apiKey)
        assertEquals(7, synced.sortOrder)
        assertEquals(false, synced.models.single().reasoningOverride)
        assertEquals(65_536, synced.models.single().contextWindowOverride)
        // 已经一致：不再写库。
        assertNull(PackagedModelDefaults.followPackage(synced, preset))
    }

    @Test
    fun modelsAddedToThePresetSurviveFollowingThePackage() = runBlocking {
        val preset = preset()
        ProviderRepository.ensureBuiltInsMerged(preset)
        val extra = io.github.fartown.movo.data.model.Model(id = "ark-plan-kimi-k3", modelId = "kimi-k3", displayName = "Kimi K3")
        ModelRepository.saveModel(preset.id, extra)

        ProviderRepository.ensureBuiltInsMerged(preset.copy(apiKey = "new-key"))

        val synced = ProviderRepository.providerById(preset.id)!!
        assertEquals("new-key", synced.apiKey)
        assertEquals(listOf("test-model", "kimi-k3"), synced.models.map { it.modelId })
    }

    @Test
    fun aPackageWithoutDefaultsLeavesTheStoredPresetAlone() = runBlocking {
        // CI 包不带 .env：没有包里的值可对齐，已存的预置保持原样。
        val preset = preset()
        ProviderRepository.ensureBuiltInsMerged(preset)

        ProviderRepository.packagedProvider = { null }
        ProviderRepository.updateProvider(preset.copy(apiKey = "kept-key"))
        ProviderRepository.ensureBuiltInsMerged()

        assertEquals("kept-key", ProviderRepository.providerById(preset.id)?.apiKey)
        assertEquals("https://example.com/v3", RuntimeConfigRepository.currentRuntimeConfig()!!.baseUrl)
    }

    @Test
    fun clearedKeyComesBackFromThePackageButADeletedPresetStaysDeleted() = runBlocking {
        val preset = preset()
        ProviderRepository.ensureBuiltInsMerged(preset)
        ProviderRepository.updateProvider(preset.copy(apiKey = ""))
        ProviderRepository.ensureBuiltInsMerged(preset)
        assertEquals("test-key", ProviderRepository.providerById(preset.id)?.apiKey)

        ProviderRepository.deleteProvider(preset.id)
        ProviderRepository.ensureBuiltInsMerged(preset)

        assertNull(ProviderRepository.providerById(preset.id))
        assertNull(SettingsDataStore.settings().selectedProviderId)
    }

    @Test
    fun upgradePreservesExistingProviderAndSelection() = runBlocking {
        val existing = preset().copy(id = "user-provider", name = "User", apiKey = "user-key")
            .withModels(preset().models.map { it.copy(id = "user-model") })
        val added = ProviderRepository.addProvider(existing)
        val selection = SettingsDataStore.settings()

        ProviderRepository.ensureBuiltInsMerged(preset())

        assertNull(ProviderRepository.providerById(preset().id))
        assertEquals(added, ProviderRepository.providerById(existing.id))
        assertEquals(selection.selectedProviderId, SettingsDataStore.settings().selectedProviderId)
        assertEquals(selection.selectedModelId, SettingsDataStore.settings().selectedModelId)
    }

    @Test
    fun concurrentInitializationSeedsOnlyOnePreset() = runBlocking {
        val preset = preset()

        List(4) { async { ProviderRepository.ensureBuiltInsMerged(preset) } }.awaitAll()

        assertEquals(1, ProviderRepository.allProviders().count { it.id == preset.id })
        assertEquals(1, ModelRepository.modelsByProvider(preset.id).size)
        assertEquals(preset.id, SettingsDataStore.settings().selectedProviderId)
    }

    private fun preset() = requireNotNull(
        PackagedModelDefaults.createProvider(
            name = "Test",
            baseUrl = "https://example.com/v3/",
            apiKey = "test-key",
            modelId = "test-model",
            modelName = "Test Model",
            contextWindow = 1_048_576,
            hostedWebSearchEnabled = true,
        )
    )
}
