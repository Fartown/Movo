package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.withModels
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.provider.PackagedModelDefaults
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        SettingsDataStore.init(context)
        ProviderRepository.init(context)
        runBlocking {
            // Repository singletons can retain a prior Robolectric application Context.
            // Open the current test database explicitly before accessing those repositories.
            EtaDatabase.get(context).providerDao().replaceAll(emptyList())
            SettingsDataStore.setSelection(null, null)
            assertTrue(ProviderRepository.allProviders().isEmpty())
        }
    }

    @Test
    fun incompletePackagedConfigKeepsNormalProviderSetup() = runBlocking {
        assertNull(PackagedModelDefaults.createProvider("Test", "", "key", "model"))
        assertNull(PackagedModelDefaults.createProvider("Test", "https://example.com/v1", " ", "model"))
        assertNull(PackagedModelDefaults.createProvider("Test", "https://example.com/v1", "key", ""))

        ProviderRepository.ensureBuiltInsMerged(initialProvider = null)

        assertEquals(BuiltinProviders.PROVIDERS.size, ProviderRepository.allProviders().size)
        assertEquals(BuiltinProviders.OPENAI_ID, SettingsDataStore.settings().selectedProviderId)
    }

    @Test
    fun firstInstallSelectsEditablePackagedModelWithWorkingRuntimeConfig() = runBlocking {
        val preset = preset()

        ProviderRepository.ensureBuiltInsMerged(preset)

        val stored = ProviderRepository.providerById(preset.id)!!
        val config = RuntimeConfigRepository.currentRuntimeConfig()!!
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
    fun editedKeyUrlAndModelSurviveRestartAndNewPackagedDefaults() = runBlocking {
        val preset = preset()
        ProviderRepository.ensureBuiltInsMerged(preset)
        ProviderRepository.updateProvider(
            preset.copy(baseUrl = "https://edited.example/v1", apiKey = "edited-key")
        )
        ModelRepository.saveModel(
            preset.id,
            preset.models.single().copy(modelId = "edited-model", displayName = "Edited"),
        )
        EtaDatabase.closeForTests()
        EtaDatabase.get(context)

        ProviderRepository.ensureBuiltInsMerged(preset.copy(apiKey = "new-build-key"))

        val config = RuntimeConfigRepository.currentRuntimeConfig()!!
        assertEquals("https://edited.example/v1", config.baseUrl)
        assertEquals("edited-key", config.apiKey)
        assertEquals("edited-model", config.model)
    }

    @Test
    fun deletedPresetAndClearedKeyAreNotRestoredOnStartup() = runBlocking {
        val preset = preset()
        ProviderRepository.ensureBuiltInsMerged(preset)
        ProviderRepository.updateProvider(preset.copy(apiKey = ""))
        ProviderRepository.ensureBuiltInsMerged(preset)
        assertEquals("", ProviderRepository.providerById(preset.id)?.apiKey)

        ProviderRepository.deleteProvider(preset.id)
        ProviderRepository.ensureBuiltInsMerged(preset)

        assertNull(ProviderRepository.providerById(preset.id))
        assertEquals(BuiltinProviders.OPENAI_ID, SettingsDataStore.settings().selectedProviderId)
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
