package io.github.fartown.movo.agent.tools.memory

import android.content.Context
import io.github.fartown.movo.data.repository.AgentMemoryException
import io.github.fartown.movo.data.repository.AgentMemorySnapshot
import io.github.fartown.movo.data.repository.AgentMemoryStore
import io.github.fartown.movo.data.repository.AgentMemoryWriteResult
import io.github.fartown.movo.data.repository.CharacterMemoryRepository

/**
 * 真实记忆后端：
 * - user 作用域 → 应用私有 filesDir 下的 MEMORY.md（AgentMemoryStore，带原子写与 revision CAS）。
 * - character 作用域 → CharacterMemoryRepository，按角色 id 存独立 MEMORY.md。
 *
 * user 这里新建了一份指向同一文件的 AgentMemoryStore 实例，以拿到 AgentMemoryRepository 未对外暴露的
 * replaceAllIfRevision（原子 CAS）。单次运行内记忆写入都经本后端，和全局单例 store 并发写的概率极低；
 * 若后续要彻底消除双实例，由主流程把 CAS 方法提升到 AgentMemoryRepository（见返回报告）。
 *
 * [characterId] 由主流程在角色会话时提供当前角色标识；非角色会话或缺标识时 character 作用域不可读写。
 */
internal class AndroidMemoryBackend(
    context: Context,
    private val characterId: () -> String?,
) : MemoryBackend {
    private val appContext = context.applicationContext
    private val userStore by lazy { AgentMemoryStore(appContext.filesDir) }

    override fun load(scope: MemoryScopeArg): MemoryLoad = when (scope) {
        MemoryScopeArg.USER -> snapshotToLoad { userStore.snapshot() }
        MemoryScopeArg.CHARACTER -> {
            val id = characterId() ?: return MemoryLoad.Unavailable
            snapshotToLoad { CharacterMemoryRepository.snapshot(appContext, id) }
        }
    }

    override fun write(scope: MemoryScopeArg, content: String, expectedRevision: String): MemoryCas = when (scope) {
        MemoryScopeArg.USER -> casToResult { userStore.replaceAllIfRevision(content, expectedRevision) }
        MemoryScopeArg.CHARACTER -> {
            val id = characterId() ?: return MemoryCas.Unavailable
            casToResult { CharacterMemoryRepository.replaceAllIfRevision(appContext, id, expectedRevision, content) }
        }
    }

    private inline fun snapshotToLoad(block: () -> AgentMemorySnapshot): MemoryLoad =
        try {
            val snapshot = block()
            MemoryLoad.Ok(snapshot.revision, snapshot.content, snapshot.lineCount, snapshot.byteSize)
        } catch (e: AgentMemoryException) {
            if (e.code == "MEMORY_TOO_LARGE") MemoryLoad.TooLarge else MemoryLoad.Unavailable
        } catch (_: Exception) {
            MemoryLoad.Unavailable
        }

    private inline fun casToResult(block: () -> AgentMemoryWriteResult): MemoryCas =
        try {
            when (val result = block()) {
                is AgentMemoryWriteResult.Success ->
                    MemoryCas.Ok(result.snapshot.revision, result.snapshot.byteSize, result.snapshot.lineCount)
                is AgentMemoryWriteResult.Conflict -> MemoryCas.Conflict(result.snapshot.revision)
            }
        } catch (e: AgentMemoryException) {
            if (e.code == "MEMORY_TOO_LARGE") MemoryCas.TooLarge else MemoryCas.Unavailable
        } catch (_: Exception) {
            MemoryCas.Unavailable
        }
}
