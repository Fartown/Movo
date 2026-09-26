package io.github.fartown.movo.data.repository

import android.content.Context
import io.github.fartown.movo.data.datastore.SettingsDataStore
import io.github.fartown.movo.data.db.MovoDatabase
import io.github.fartown.movo.data.db.ProviderWithModelsSeed
import io.github.fartown.movo.data.db.toDomain
import io.github.fartown.movo.data.db.toEntity
import io.github.fartown.movo.data.db.toModelEntities
import io.github.fartown.movo.data.model.AnthropicProviderSetting
import io.github.fartown.movo.data.model.CustomProviderSetting
import io.github.fartown.movo.data.model.Model
import io.github.fartown.movo.data.model.OpenAiCompatibleProviderSetting
import io.github.fartown.movo.data.model.ProviderSetting
import io.github.fartown.movo.data.model.Settings
import io.github.fartown.movo.data.model.selectedOrFirstModel
import io.github.fartown.movo.data.model.withApiKey
import io.github.fartown.movo.data.model.withModels
import io.github.fartown.movo.data.model.withSortOrder
import io.github.fartown.movo.data.provider.BuiltinProviders
import io.github.fartown.movo.data.provider.OfficialModelCatalog
import io.github.fartown.movo.data.provider.PackagedModelDefaults
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object ProviderRepository {
    private val defaultsMutex = Mutex()

    @Volatile
    private lateinit var applicationContext: Context

    fun init(context: Context) {
        if (!::applicationContext.isInitialized) {
            applicationContext = context.applicationContext
        }
    }

    fun providersFlow(): Flow<List<ProviderSetting>> =
        dao().providersFlow().map { providers ->
            providers
                .map { it.toDomain() }
                .sortedBy(ProviderSetting::sortOrder)
        }

    fun settingsFlow(): Flow<Settings> =
        SettingsDataStore.settingsFlow()

    suspend fun settings(): Settings =
        SettingsDataStore.settings()

    suspend fun allProviders(): List<ProviderSetting> =
        dao().providers()
            .map { it.toDomain() }
            .sortedBy(ProviderSetting::sortOrder)

    suspend fun providerById(id: String): ProviderSetting? =
        dao().providerById(id)?.toDomain()

    suspend fun providerByModelId(modelId: String): ProviderSetting? =
        dao().providerByModelId(modelId)?.toDomain()

    suspend fun addProvider(provider: ProviderSetting): ProviderSetting {
        val nextOrder = (allProviders().maxOfOrNull { it.sortOrder } ?: -1) + 1
        val added = provider.withSortOrder(nextOrder)
        replaceProvider(added)
        repairSelection()
        return added
    }

    suspend fun updateProvider(provider: ProviderSetting) {
        require(dao().updateProvider(provider.toEntity()) == 1) { "Provider 不存在" }
        repairSelection()
    }

    internal suspend fun replaceModels(providerId: String, models: List<Model>) {
        val provider = requireNotNull(providerById(providerId)) { "Provider 不存在" }
        dao().replaceModels(
            providerId = providerId,
            models = provider.withModels(models).toModelEntities(),
        )
    }

    suspend fun deleteProvider(id: String) {
        val provider = providerById(id) ?: return
        if (provider.isBuiltIn) return
        dao().deleteProvider(id)
        SettingsDataStore.clearSelectedModelIdForProvider(id)
        repairSelection()
    }

    suspend fun copyProvider(id: String): ProviderSetting? {
        val source = providerById(id) ?: return null
        val nextOrder = (allProviders().maxOfOrNull { it.sortOrder } ?: -1) + 1
        val copy = source.deepCopy(
            id = newId(),
            name = "${source.name} 副本",
            sortOrder = nextOrder,
            builtIn = false,
        )
        replaceProvider(copy)
        repairSelection()
        return copy
    }

    suspend fun resetBuiltIn(id: String) {
        val builtIn = BuiltinProviders.providerById(id) ?: return
        val current = providerById(id)
        val restored = seedOfficialModelsIfEmpty(
            current
            ?.let { builtIn.withApiKey(it.apiKey).withSortOrder(it.sortOrder) }
            ?: builtIn
        )
        replaceProvider(restored)
        repairSelection()
    }

    suspend fun ensureBuiltInsMerged(
        initialProvider: ProviderSetting? = PackagedModelDefaults.provider(),
    ): Unit = defaultsMutex.withLock {
        val current = allProviders()
        if (current.isEmpty()) {
            insertProviders(
                listOfNotNull(initialProvider) + BuiltinProviders.PROVIDERS.map(::seedOfficialModelsIfEmpty)
            )
            if (initialProvider != null) {
                SettingsDataStore.setSelection(initialProvider.id, initialProvider.models.firstOrNull()?.id)
            }
            repairSelection()
            return@withLock
        }

        val existingIds = current.mapTo(mutableSetOf()) { it.id }
        val missing = BuiltinProviders.PROVIDERS.filterNot { it.id in existingIds }
        if (missing.isNotEmpty()) {
            insertProviders(missing.map(::seedOfficialModelsIfEmpty))
            repairSelection()
        } else {
            repairSelection()
        }
    }

    suspend fun repairSelection(): Settings {
        val providers = allProviders()
        val settings = SettingsDataStore.settings()
        val selectedProvider = providers.firstOrNull { it.id == settings.selectedProviderId && it.isEnabled }
            ?: providers.firstOrNull { it.isEnabled }
        val activeModel = selectedProvider
            ?.takeIf { it.id == settings.selectedProviderId }
            ?.models
            ?.firstOrNull { it.id == settings.selectedModelId && it.isEnabled }
        val rememberedModelId = selectedProvider?.let {
            SettingsDataStore.selectedModelIdForProvider(it.id)
        }
        val selectedModel = activeModel ?: selectedProvider?.selectedOrFirstModel(rememberedModelId)
        val repaired = settings.copy(
            selectedProviderId = selectedProvider?.id,
            selectedModelId = selectedModel?.id,
        )
        SettingsDataStore.setSelection(repaired.selectedProviderId, repaired.selectedModelId)
        return repaired
    }

    fun newId(): String = UUID.randomUUID().toString()

    private fun dao() =
        MovoDatabase.get(appContext()).providerDao()

    private fun appContext(): Context {
        check(::applicationContext.isInitialized) {
            "ProviderRepository.init(context) must be called in Application.onCreate()"
        }
        return applicationContext
    }

    private suspend fun replaceProvider(provider: ProviderSetting) {
        dao().replaceProvider(
            provider = provider.toEntity(),
            models = provider.toModelEntities(),
        )
    }

    private suspend fun insertProviders(providers: List<ProviderSetting>) {
        dao().insertProvidersWithModels(
            providers.map { provider ->
                ProviderWithModelsSeed(
                    provider = provider.toEntity(),
                    models = provider.toModelEntities(),
                )
            }
        )
    }

    private fun seedOfficialModelsIfEmpty(provider: ProviderSetting): ProviderSetting {
        if (provider.models.isNotEmpty()) return provider
        val seededModels = OfficialModelCatalog.modelsForProvider(provider)
        return if (seededModels.isEmpty()) provider else provider.withModels(seededModels)
    }

    private fun ProviderSetting.deepCopy(
        id: String,
        name: String,
        sortOrder: Int,
        builtIn: Boolean,
    ): ProviderSetting {
        val copiedModels = models.mapIndexed { index, model ->
            model.copy(id = newId(), isBuiltIn = builtIn, sortOrder = index)
        }
        return when (this) {
            is OpenAiCompatibleProviderSetting -> copy(
                id = id,
                name = name,
                sortOrder = sortOrder,
                isBuiltIn = builtIn,
                models = copiedModels,
            )

            is AnthropicProviderSetting -> copy(
                id = id,
                name = name,
                sortOrder = sortOrder,
                isBuiltIn = builtIn,
                models = copiedModels,
            )

            is CustomProviderSetting -> copy(
                id = id,
                name = name,
                sortOrder = sortOrder,
                isBuiltIn = builtIn,
                models = copiedModels,
            )
        }
    }
}
