package io.github.fartown.movo.agent.tools.memory

/**
 * 记忆领域（§36 memory_read、§37 memory_write）的可测后端。
 * 读取与写入共用同一后端：真实实现装配到 MEMORY.md 原子存储；测试用内存假实现。
 *
 * 作用域 [MemoryScopeArg]：普通会话写用户记忆；角色会话写角色记忆、真实记忆只读。
 * 具体会话类型由 env.memoryScope 决定，由工具在 resolve/parse 时推导，后端只按作用域读写。
 */
internal enum class MemoryScopeArg { USER, CHARACTER }

/** 读取结果：拿到全量正文后由工具自行按行切片/检索（不在后端渲染，避免 revision 与切片耦合）。 */
internal sealed interface MemoryLoad {
    data class Ok(
        val revision: String,
        val content: String,
        val lineCount: Int,
        val byteSize: Int,
    ) : MemoryLoad

    /** 记忆文件超过 1 MiB 安全上限。 */
    data object TooLarge : MemoryLoad

    /** 存储暂不可读（IO 失败、作用域缺少角色标识等）。 */
    data object Unavailable : MemoryLoad
}

/** 写入（CAS）结果：仅当 expectedRevision 与当前版本一致时落盘。 */
internal sealed interface MemoryCas {
    data class Ok(val revision: String, val byteSize: Int, val lineCount: Int) : MemoryCas

    /** 版本不符：附当前版本，供模型重新读取后再试。 */
    data class Conflict(val currentRevision: String) : MemoryCas

    data object TooLarge : MemoryCas

    data object Unavailable : MemoryCas
}

internal interface MemoryBackend {
    /** 读取某作用域记忆的全量快照。 */
    fun load(scope: MemoryScopeArg): MemoryLoad

    /** 以 CAS 方式整体写入；expectedRevision 为写入前读到的版本。 */
    fun write(scope: MemoryScopeArg, content: String, expectedRevision: String): MemoryCas
}
