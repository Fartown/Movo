package io.github.fartown.movo.agent.tools.memory

import android.content.Context
import io.github.fartown.movo.data.repository.AgentMemoryException
import io.github.fartown.movo.data.repository.AgentMemoryRepository
import io.github.fartown.movo.data.repository.AgentMemorySnapshot
import io.github.fartown.movo.data.repository.AgentMemoryWriteResult
import io.github.fartown.movo.data.repository.CharacterMemoryRepository

/**
 * 真实记忆后端：
 * - user 作用域 → AgentMemoryRepository（应用私有 filesDir 下的 MEMORY.md，原子写 + revision CAS）。
 *   和设置页编辑记忆走同一个存储实例、同一把锁；工具按版本写入，设置页先存过的内容工具不会悄悄覆盖
 *   （设置页保存不比版本，和重构前一样）。
 * - character 作用域 → CharacterMemoryRepository，按角色 id 存独立 MEMORY.md。
 *
 * [characterId] 由主流程在角色会话时提供当前角色标识；非角色会话或缺标识时 character 作用域不可读写。
 */
internal class AndroidMemoryBackend(
    context: Context,
    private val characterId: () -> String?,
) : MemoryBackend {
    private val appContext = context.applicationContext.also(AgentMemoryRepository::init)

    override fun load(scope: MemoryScopeArg): MemoryLoad = when (scope) {
        MemoryScopeArg.USER -> snapshotToLoad { AgentMemoryRepository.snapshot() }
        MemoryScopeArg.CHARACTER -> {
            val id = characterId() ?: return MemoryLoad.Unavailable
            snapshotToLoad { CharacterMemoryRepository.snapshot(appContext, id) }
        }
    }

    override fun write(scope: MemoryScopeArg, content: String, expectedRevision: String): MemoryCas = when (scope) {
        MemoryScopeArg.USER -> casToResult { AgentMemoryRepository.replaceAllIfRevision(content, expectedRevision) }
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
