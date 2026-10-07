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
import kotlinx.coroutines.launch
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
    fun editedPresetIsKeptOverANewPackage() = runBlocking {
        // 2026-10-07 定：已有的预置不动，改过的以改过的为准。
        val preset = preset()
        ProviderRepository.ensureBuiltInsMerged(preset)
        ProviderRepository.updateProvider(preset.copy(baseUrl = "https://edited.example/v1", apiKey = "edited-key"))
        MovoDatabase.closeForTests()
        MovoDatabase.get(context)

        val next = preset.copy(baseUrl = "https://ark.example/api/plan/v3", apiKey = "new-build-key")
        ProviderRepository.packagedProvider = { next }
        ProviderRepository.ensureBuiltInsMerged(next)

        val config = RuntimeConfigRepository.currentRuntimeConfig()!!
        assertEquals("https://edited.example/v1", config.baseUrl)
        assertEquals("edited-key", config.apiKey)
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
    fun aDeletedPresetComesBackButAClearedKeyStaysCleared() = runBlocking {
        val preset = preset()
        ProviderRepository.ensureBuiltInsMerged(preset)
        ProviderRepository.updateProvider(preset.copy(apiKey = ""))
        ProviderRepository.ensureBuiltInsMerged(preset)
        assertEquals("", ProviderRepository.providerById(preset.id)?.apiKey)

        ProviderRepository.deleteProvider(preset.id)
        ProviderRepository.ensureBuiltInsMerged(preset)

        assertEquals("test-key", ProviderRepository.providerById(preset.id)?.apiKey)
    }

    @Test
    fun aDeviceWithoutThePresetGetsItAndKeepsItsSelection() = runBlocking {
        // 用户手机（10-07）：只有自建的旧服务商。装了带默认模型的包：预置那条加上，自建的和当前选择不动。
        val existing = preset().copy(id = "user-provider", name = "User", apiKey = "user-key")
            .withModels(preset().models.map { it.copy(id = "user-model") })
        val added = ProviderRepository.addProvider(existing)
        SettingsDataStore.setSelection(existing.id, "user-model")

        ProviderRepository.ensureBuiltInsMerged(preset())

        assertEquals("test-key", ProviderRepository.providerById(preset().id)?.apiKey)
        assertEquals(added, ProviderRepository.providerById(existing.id))
        assertEquals(existing.id, SettingsDataStore.settings().selectedProviderId)
    }

    @Test
    fun anUnchangedPresetIsNotWrittenAgain() = runBlocking {
        // 读模型配置的地方在监听服务商表：已有预置时不能再写，否则写库 → 表变化 → 再读配置 → 再写，一直循环。
        val plan = requireNotNull(
            PackagedModelDefaults.createProvider(
                name = "Ark",
                baseUrl = "https://ark.cn-beijing.volces.com/api/plan/v3",
                apiKey = "plan-key",
                modelId = "deepseek-v4-1-flash-260910",
            )
        )
        ProviderRepository.packagedProvider = { plan }
        ProviderRepository.ensureBuiltInsMerged()
        assertEquals(plan.models.map { it.modelId }, ProviderRepository.providerById(plan.id)!!.models.map { it.modelId })

        val emissions = java.util.concurrent.atomic.AtomicInteger()
        val watcher = launch(kotlinx.coroutines.Dispatchers.IO) {
            ProviderRepository.providersFlow().collect { emissions.incrementAndGet() }
        }
        kotlinx.coroutines.delay(300)
        val before = emissions.get()
        repeat(3) { RuntimeConfigRepository.currentRuntimeConfig() }
        kotlinx.coroutines.delay(300)
        watcher.cancel()
        assertEquals(before, emissions.get())
    }

    @Test
    fun agentPlanPresetComesWithThePlanModels() {
        val plan = requireNotNull(
            PackagedModelDefaults.createProvider(
                name = "Ark",
                baseUrl = "https://ark.cn-beijing.volces.com/api/plan/v3",
                apiKey = "plan-key",
                modelId = "deepseek-v4-1-flash-260910",
            )
        )
        assertEquals("deepseek-v4-1-flash-260910", plan.models.first().modelId)
        assertTrue(plan.models.any { it.modelId == "kimi-k3" })
        assertEquals(plan.models.map { it.modelId }.distinct(), plan.models.map { it.modelId })
        assertEquals(1, preset().models.size)
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
