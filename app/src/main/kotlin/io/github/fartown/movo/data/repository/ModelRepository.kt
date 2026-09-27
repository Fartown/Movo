package io.github.fartown.movo.data.repository

import io.github.fartown.movo.data.model.Model
import io.github.fartown.movo.data.model.ModelSource
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object ModelRepository {
    private val mutationMutex = Mutex()

    fun modelsByProviderFlow(providerId: String): Flow<List<Model>> =
        allModelsByProviderFlow(providerId).map { models ->
            models.filter { it.isEnabled }.sortedBy { it.sortOrder }
        }

    fun allModelsByProviderFlow(providerId: String): Flow<List<Model>> =
        ProviderRepository.providersFlow().map { providers ->
            providers.firstOrNull { it.id == providerId }
                ?.models
                ?.sortedBy { it.sortOrder }
                .orEmpty()
        }

    suspend fun modelsByProvider(providerId: String): List<Model> =
        ProviderRepository.providerById(providerId)
            ?.models
            ?.sortedBy { it.sortOrder }
            .orEmpty()

    suspend fun saveModel(providerId: String, draft: Model): Model = mutationMutex.withLock {
        val models = currentModels(providerId)
        val modelId = draft.modelId.trim()
        val displayName = draft.displayName.trim()
        require(modelId.isNotEmpty()) { "Model ID 不能为空" }
        require(displayName.isNotEmpty()) { "展示名称不能为空" }
        require(draft.contextWindowOverride == null || draft.contextWindowOverride > 0) {
            "上下文长度必须是正整数"
        }
        require(
            models.none { existing ->
                existing.id != draft.id && existing.modelId.trim().equals(modelId, ignoreCase = true)
            }
        ) { "Model ID 已存在" }

        val existing = models.firstOrNull { it.id == draft.id }
        val saved = if (existing == null) {
            draft.copy(
                id = draft.id.ifBlank(::newId),
                modelId = modelId,
                displayName = displayName,
                isBuiltIn = false,
                sortOrder = (models.maxOfOrNull { it.sortOrder } ?: -1) + 1,
                source = ModelSource.MANUAL,
            )
        } else {
            draft.copy(
                id = existing.id,
                modelId = modelId,
                displayName = displayName,
                isBuiltIn = existing.isBuiltIn,
                sortOrder = existing.sortOrder,
                source = existing.source,
                createdAt = existing.createdAt,
            )
        }
        ProviderRepository.replaceModels(
            providerId,
            models.filterNot { it.id == saved.id } + saved,
        )
        saved
    }

    suspend fun deleteModel(providerId: String, modelId: String) = mutationMutex.withLock {
        ProviderRepository.replaceModels(
            providerId,
            currentModels(providerId).filterNot { it.id == modelId },
        )
        ProviderRepository.repairSelection()
    }

    suspend fun deleteModels(providerId: String, modelIds: Set<String>) {
        if (modelIds.isEmpty()) return
        mutationMutex.withLock {
            ProviderRepository.replaceModels(
                providerId,
                currentModels(providerId).filterNot { it.id in modelIds },
            )
            ProviderRepository.repairSelection()
        }
    }

    /**
     * [authoritative] 为 true 时远端目录就是该服务商可用模型的全集（ChatGPT 订阅）：
     * 内置目录里远端已不再提供的模型一并移除，手动添加的保留。
     */
    suspend fun syncRemoteModels(
        providerId: String,
        fetched: List<Model>,
        authoritative: Boolean = false,
    ): RemoteModelSyncResult =
        mutationMutex.withLock {
            val remoteByKey = fetched
                .asSequence()
                .filter { it.modelId.isNotBlank() }
                .distinctBy { it.modelId.normalizedModelId() }
                .associateBy { it.modelId.normalizedModelId() }
            if (remoteByKey.isEmpty()) {
                return@withLock RemoteModelSyncResult(applied = false)
            }

            val existing = currentModels(providerId)
            val consumed = mutableSetOf<String>()
            val merged = buildList {
                existing.forEach { stored ->
                    val key = stored.modelId.normalizedModelId()
                    val remote = remoteByKey[key]
                    when {
                        remote != null -> {
                            consumed += key
                            add(
                                // 远端只给了 id 的字段（多数 OpenAI 兼容平台的 /models 没有显示名、上下文、能力）
                                // 不能把已有的内置目录 / 手动填写的信息覆盖成「未知」。
                                remote.copy(
                                    id = stored.id,
                                    modelId = remote.modelId.trim(),
                                    displayName = remote.displayName.trim()
                                        .takeUnless { it.isBlank() || it.equals(remote.modelId.trim(), ignoreCase = true) }
                                        ?: stored.displayName.ifBlank { remote.modelId.trim() },
                                    ownedBy = remote.ownedBy ?: stored.ownedBy,
                                    contextWindow = remote.contextWindow ?: stored.contextWindow,
                                    inputModalities = if (remote.inputModalities == listOf(Model.TEXT_MODALITY) && remote.attachment == null) {
                                        stored.inputModalities
                                    } else {
                                        remote.inputModalities
                                    },
                                    outputModalities = remote.outputModalities.ifEmpty { stored.outputModalities },
                                    attachment = remote.attachment ?: stored.attachment,
                                    toolCall = remote.toolCall ?: stored.toolCall,
                                    reasoning = remote.reasoning ?: stored.reasoning,
                                    reasoningCapabilities = remote.reasoningCapabilities ?: stored.reasoningCapabilities,
                                    structuredOutput = remote.structuredOutput ?: stored.structuredOutput,
                                    supportsTemperature = remote.supportsTemperature ?: stored.supportsTemperature,
                                    isEnabled = stored.isEnabled,
                                    isBuiltIn = stored.isBuiltIn || remote.isBuiltIn,
                                    customHeaders = stored.customHeaders,
                                    customBody = stored.customBody,
                                    contextWindowOverride = stored.contextWindowOverride,
                                    reasoningOverride = stored.reasoningOverride,
                                    reasoningCapabilitiesOverride = stored.reasoningCapabilitiesOverride,
                                    source = stored.source,
                                    createdAt = stored.createdAt,
                                )
                            )
                        }
                        stored.source == ModelSource.MANUAL -> add(stored)
                        stored.source == ModelSource.CATALOG && !authoritative -> add(stored)
                    }
                }
                remoteByKey.forEach { (key, remote) ->
                    if (key !in consumed) {
                        add(
                            remote.copy(
                                id = remote.id.ifBlank(::newId),
                                modelId = remote.modelId.trim(),
                                displayName = remote.displayName.trim().ifBlank { remote.modelId.trim() },
                                isBuiltIn = false,
                                source = ModelSource.REMOTE,
                            )
                        )
                    }
                }
            }.mapIndexed { index, model -> model.copy(sortOrder = index) }

            ProviderRepository.replaceModels(providerId, merged)
            ProviderRepository.repairSelection()
            RemoteModelSyncResult(
                applied = true,
                fetchedCount = remoteByKey.size,
                addedCount = merged.count { model -> existing.none { it.id == model.id } },
                removedCount = existing.count { stored -> merged.none { it.id == stored.id } },
            )
        }

    suspend fun reorderModels(providerId: String, ids: List<String>) {
        mutationMutex.withLock {
            val models = currentModels(providerId)
            val byId = models.associateBy { it.id }
            val orderedIds = ids.toSet()
            val reordered = ids.mapNotNull { byId[it] } + models.filterNot { it.id in orderedIds }
            ProviderRepository.replaceModels(
                providerId,
                reordered.mapIndexed { index, model -> model.copy(sortOrder = index) },
            )
        }
    }

    fun newId(): String = UUID.randomUUID().toString()

    private suspend fun currentModels(providerId: String): List<Model> =
        requireNotNull(ProviderRepository.providerById(providerId)) { "Provider 不存在" }
            .models
            .sortedBy { it.sortOrder }

    private fun String.normalizedModelId(): String = trim().lowercase()
}

internal data class RemoteModelSyncResult(
    val applied: Boolean,
    val fetchedCount: Int = 0,
    val addedCount: Int = 0,
    val removedCount: Int = 0,
)
