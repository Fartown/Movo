package io.github.fartown.movo.data.repository

import android.content.Context
import io.github.fartown.movo.data.auth.ChatGptAuth
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
import io.github.fartown.movo.data.provider.ProviderCatalog
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object ProviderRepository {
    /**
     * 服务商数据版本：1 = 内置预设改为模板（2026-09-27，Figma「15」），清掉没填 Key 的预设占位记录。
     * 迁移只在版本落后时跑一次；备份恢复另走 [prunePresetPlaceholders]。
     */
    internal const val PROVIDER_DATA_VERSION = 1

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

    /** 删除服务商；ChatGPT 记录承载订阅登录，不能删。内置预设删掉后回到「可以添加」。 */
    suspend fun deleteProvider(id: String) {
        val provider = providerById(id) ?: return
        if (ProviderCatalog.isChatGpt(provider)) return
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
        // 只重置已添加的：模板没有落库时什么也不做，不凭空写入一条没有 Key 的记录。
        val current = providerById(id) ?: return
        val restored = seedOfficialModelsIfEmpty(
            builtIn.withApiKey(current.apiKey).withSortOrder(current.sortOrder)
        )
        replaceProvider(restored)
        repairSelection()
    }

    /** 从模板打开详情时的草稿：预填名称 / Base URL / 类型 / 官方模型目录，不写数据库。 */
    fun templateDraft(id: String): ProviderSetting? =
        BuiltinProviders.TEMPLATES
            .firstOrNull { it.id == id }
            ?.let(::seedOfficialModelsIfEmpty)

    /**
     * 「添加」预设：填了 API Key 才写入数据库，id 沿用模板 id（据此从「可以添加」里去掉）。
     * 已有同 id 的占位记录（旧版本留下的当前选中项）时覆盖它并保留排序。
     */
    suspend fun addFromTemplate(provider: ProviderSetting): ProviderSetting {
        require(BuiltinProviders.TEMPLATES.any { it.id == provider.id }) { "不是内置服务商模板" }
        require(provider.apiKey.isNotBlank()) { "请填写 API Key" }
        val sortOrder = providerById(provider.id)?.sortOrder
            ?: ((allProviders().maxOfOrNull { it.sortOrder } ?: -1) + 1)
        val added = seedOfficialModelsIfEmpty(provider).withSortOrder(sortOrder)
        replaceProvider(added)
        repairSelection()
        return added
    }

    /**
     * 启动 / 打开设置时补齐默认数据：
     * - 首次安装（库为空）：只写入 .env 打包的服务商并选中它；内置预设是模板，不落库；
     * - 升级：数据版本落后时清一次没填 Key 的预设占位（[ProviderCatalog.isPresetPlaceholder]）；
     * - 每次：确保 ChatGPT 记录存在（承载登录后的模型），再修复当前选择。
     */
    suspend fun ensureBuiltInsMerged(
        initialProvider: ProviderSetting? = PackagedModelDefaults.provider(),
    ): Unit = defaultsMutex.withLock {
        val current = allProviders()
        if (current.isEmpty()) {
            if (initialProvider != null) {
                insertProviders(listOf(initialProvider))
                SettingsDataStore.setSelection(initialProvider.id, initialProvider.models.firstOrNull()?.id)
            }
            SettingsDataStore.setProviderDataVersion(PROVIDER_DATA_VERSION)
        } else if (SettingsDataStore.providerDataVersion() < PROVIDER_DATA_VERSION) {
            prunePlaceholders(current)
            SettingsDataStore.setProviderDataVersion(PROVIDER_DATA_VERSION)
        }
        ensureChatGptRecord()
        repairSelection()
    }

    /** 备份恢复后调用：旧备份里带着全部内置预设，同样清掉没填 Key 的占位（幂等）。 */
    suspend fun prunePresetPlaceholders(): Unit = defaultsMutex.withLock {
        prunePlaceholders(allProviders())
    }

    /**
     * 修复当前选择：选中的服务商仍可用（已启用，且有 Key 或是已登录的 ChatGPT）就保留；
     * 否则按排序换成可用的（有 Key 的、.env 打包的、已登录的 ChatGPT），都没有时不选，输入框提示「未配置模型」。
     */
    suspend fun repairSelection(chatGptLoggedIn: Boolean = ChatGptAuth.isLoggedIn()): Settings {
        val providers = allProviders()
        val settings = SettingsDataStore.settings()
        val selectedProvider = providers.firstOrNull {
            it.id == settings.selectedProviderId && ProviderCatalog.isUsable(it, chatGptLoggedIn)
        } ?: ProviderCatalog.fallbackSelection(providers, chatGptLoggedIn)
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

    private suspend fun prunePlaceholders(providers: List<ProviderSetting>) {
        val selectedProviderId = SettingsDataStore.settings().selectedProviderId
        providers
            .filter { ProviderCatalog.isPresetPlaceholder(it, selectedProviderId) }
            .forEach { placeholder ->
                dao().deleteProvider(placeholder.id)
                SettingsDataStore.clearSelectedModelIdForProvider(placeholder.id)
            }
    }

    private suspend fun ensureChatGptRecord() {
        if (dao().providerById(BuiltinProviders.CHATGPT_ID) != null) return
        val chatGpt = BuiltinProviders.providerById(BuiltinProviders.CHATGPT_ID) ?: return
        insertProviders(listOf(seedOfficialModelsIfEmpty(chatGpt)))
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
