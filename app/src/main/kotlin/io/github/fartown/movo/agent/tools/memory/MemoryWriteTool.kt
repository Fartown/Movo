package io.github.fartown.movo.agent.tools.memory

import io.github.fartown.movo.agent.tools.core.ApprovalCategory
import io.github.fartown.movo.agent.tools.core.CallResolution
import io.github.fartown.movo.agent.tools.core.Evidence
import io.github.fartown.movo.agent.tools.core.MemoryScope
import io.github.fartown.movo.agent.tools.core.ModelContent
import io.github.fartown.movo.agent.tools.core.RecoverySpec
import io.github.fartown.movo.agent.tools.core.Risk
import io.github.fartown.movo.agent.tools.core.Sensitivity
import io.github.fartown.movo.agent.tools.core.ToolArgs
import io.github.fartown.movo.agent.tools.core.ToolAvailability
import io.github.fartown.movo.agent.tools.core.ToolContext
import io.github.fartown.movo.agent.tools.core.ToolContract
import io.github.fartown.movo.agent.tools.core.ToolDomain
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolError
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.agent.tools.core.ToolInput
import io.github.fartown.movo.agent.tools.core.ToolOutput
import io.github.fartown.movo.agent.tools.core.Verdict
import io.github.fartown.movo.agent.tools.core.fail
import io.github.fartown.movo.agent.tools.core.objectSchema
import io.github.fartown.movo.agent.tools.core.ApprovalPreview
import java.util.UUID
import org.json.JSONObject

internal enum class MemoryWriteMode { APPEND, REPLACE, CLEAR }

internal data class MemoryWriteInput(
    val scope: MemoryScopeArg,
    val mode: MemoryWriteMode,
    val newText: String?,
    val oldText: String?,
    val revision: String?,
) : ToolInput

internal data class MemoryWriteOutput(
    val scope: MemoryScopeArg,
    val mode: MemoryWriteMode,
    val beforeRevision: String,
    val afterRevision: String,
    val mutationId: String,
    val byteSize: Int,
    val lineCount: Int,
) : ToolOutput

/**
 * memory_write（§37）：append 追加；replace 把唯一匹配的 old_text 换成 new_text；clear 清空（需 revision）。
 *
 * 风险/确认：
 * - append/replace → Risk.LOCAL，可撤销，不归类（角色记忆要频繁更新事实，每次确认不可用）。
 * - clear → 归为「删东西」，手动审批时固定会问。
 *
 * 一致性：append/replace 不要求模型传 revision，但存储层仍在同一锁内按「写入前读到的版本」做 CAS，
 * 审批前后片段被改动时返回 CONFLICT。
 */
internal class MemoryWriteTool(
    private val backend: MemoryBackend,
) : ToolContract<MemoryWriteInput, MemoryWriteOutput> {
    override val name = "memory_write"
    override val domain = ToolDomain.MEMORY
    override val summary =
        "写长期记忆（记住/更新/删除跨会话有用的稳定信息）：append 追加；replace 把唯一匹配的 old_text 换成 new_text" +
            "（可空表删除该段）；clear 清空（需 revision）。用户说“记住/以后/别忘了”用它；读取已有记忆用 memory_read。new_text ≤3500 字。"

    override fun availability(env: ToolEnvironment): ToolAvailability =
        if (env.memoryScope == MemoryScope.DISABLED) {
            ToolAvailability.Unavailable(ToolErrorCode.DISABLED, "记忆未开启")
        } else {
            ToolAvailability.Available
        }

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string("mode", "写入方式", required = true, enum = MemoryWriteMode.entries.map { it.name.lowercase() })
        string("new_text", "append/replace 的新内容（≤3500；replace 可为空表删除该段）", maxLength = MAX_WRITE_CHARS)
        string("old_text", "replace 时要被替换的原文，必须在记忆中唯一出现")
        string("revision", "clear 时必填：memory_read 返回的 revision")
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): MemoryWriteInput {
        val scope = if (env.memoryScope == MemoryScope.CHARACTER) MemoryScopeArg.CHARACTER else MemoryScopeArg.USER
        val mode = args.enum<MemoryWriteMode>("mode")
        val newText = args.stringOrNull("new_text")
        val oldText = args.stringOrNull("old_text")
        val revision = args.stringOrNull("revision")?.trim()?.takeIf { it.isNotEmpty() }
        when (mode) {
            MemoryWriteMode.APPEND -> {
                if (newText.isNullOrEmpty()) fail(ToolErrorCode.INVALID_ARGUMENTS, "append 需要非空 new_text")
            }
            MemoryWriteMode.REPLACE -> {
                if (oldText.isNullOrEmpty()) fail(ToolErrorCode.INVALID_ARGUMENTS, "replace 需要 old_text")
                if (newText == null) fail(ToolErrorCode.INVALID_ARGUMENTS, "replace 需要 new_text（可为空串表删除）")
            }
            MemoryWriteMode.CLEAR -> {
                if (revision == null) fail(ToolErrorCode.INVALID_ARGUMENTS, "clear 需要 revision；请先 memory_read")
            }
        }
        if (newText != null && newText.length > MAX_WRITE_CHARS) {
            fail(ToolErrorCode.TOO_LARGE, "new_text 不能超过 $MAX_WRITE_CHARS 字")
        }
        return MemoryWriteInput(scope, mode, newText, oldText, revision)
    }

    override fun resolve(input: MemoryWriteInput, env: ToolEnvironment): CallResolution =
        CallResolution(
            risk = if (input.mode == MemoryWriteMode.CLEAR) Risk.EXTERNAL else Risk.LOCAL,
            sensitivity = Sensitivity.PRIVATE,
            category = if (input.mode == MemoryWriteMode.CLEAR) ApprovalCategory.DELETE else null,
            resources = emptySet(), // 应独占 MEMORY 资源；串行基线下暂不声明（见返回报告）
            // mutation_id 支持中断查询与撤销：在 resolve 生成，execute 原样读出写进输出。
            recovery = RecoverySpec.Queryable(
                mutationId = UUID.randomUUID().toString(),
                queryHint = "用 memory_read 回读 revision 核对本次写入是否生效",
                retentionMs = RETENTION_MS,
            ),
        )

    override fun approvalPreview(input: MemoryWriteInput): ApprovalPreview = when (input.mode) {
        MemoryWriteMode.CLEAR -> ApprovalPreview("清空长期记忆？", "清空全部长期记忆，之后 Movo 不再记得你让它记住的内容")
        MemoryWriteMode.APPEND -> ApprovalPreview("写进长期记忆？", "记住：${input.newText.orEmpty().take(120)}")
        MemoryWriteMode.REPLACE -> ApprovalPreview(
            "修改长期记忆？",
            if (input.newText.isNullOrEmpty()) "删掉：${input.oldText.orEmpty().take(120)}"
            else "改为：${input.newText.take(120)}",
        )
    }

    override fun execute(
        input: MemoryWriteInput,
        resolution: CallResolution,
        ctx: ToolContext,
    ): Verdict<MemoryWriteOutput> {
        val mutationId = (resolution.recovery as? RecoverySpec.Queryable)?.mutationId
            ?: UUID.randomUUID().toString()
        val load = when (val l = backend.load(input.scope)) {
            is MemoryLoad.Ok -> l
            MemoryLoad.TooLarge ->
                return Verdict.Failed(ToolError(ToolErrorCode.TOO_LARGE, "记忆文件超过 1 MiB 安全上限"))
            MemoryLoad.Unavailable ->
                return Verdict.Failed(ToolError(ToolErrorCode.SOURCE_UNAVAILABLE, "记忆暂时读不到"))
        }

        // clear 用用户传入的 revision 做 CAS；append/replace 用写入前读到的版本做 CAS。
        val expectedRevision: String
        val newContent: String
        when (input.mode) {
            MemoryWriteMode.APPEND -> {
                expectedRevision = load.revision
                newContent = append(load.content, input.newText!!)
            }
            MemoryWriteMode.REPLACE -> {
                expectedRevision = load.revision
                val old = input.oldText!!
                val count = occurrences(load.content, old)
                when {
                    count == 0 -> return Verdict.Failed(
                        ToolError(ToolErrorCode.NOT_FOUND, "记忆里没有找到要替换的 old_text"),
                    )
                    count > 1 -> return Verdict.Failed(
                        ToolError(
                            ToolErrorCode.AMBIGUOUS,
                            "old_text 在记忆里出现 $count 处",
                            hint = "补充更多上下文让 old_text 唯一匹配",
                        ),
                    )
                }
                newContent = load.content.replaceFirst(old, input.newText!!)
            }
            MemoryWriteMode.CLEAR -> {
                expectedRevision = input.revision!!
                newContent = ""
            }
        }

        ctx.checkCancelled()
        return when (val cas = backend.write(input.scope, newContent, expectedRevision)) {
            is MemoryCas.Ok -> Verdict.Done(
                MemoryWriteOutput(
                    scope = input.scope,
                    mode = input.mode,
                    beforeRevision = expectedRevision,
                    afterRevision = cas.revision,
                    mutationId = mutationId,
                    byteSize = cas.byteSize,
                    lineCount = cas.lineCount,
                ),
                // revision 即内容 SHA-256：写入后版本就是对新内容的内容哈希回读证据。
                Evidence.ContentHash("sha-256", cas.revision),
            )
            is MemoryCas.Conflict -> Verdict.Failed(
                ToolError(
                    ToolErrorCode.CONFLICT,
                    "记忆已被改动，版本不符",
                    hint = "用 memory_read 重新读取后再写",
                    detail = "current_revision=${cas.currentRevision}",
                ),
            )
            MemoryCas.TooLarge -> Verdict.Failed(ToolError(ToolErrorCode.TOO_LARGE, "写入后记忆会超过 1 MiB 上限"))
            MemoryCas.Unavailable -> Verdict.Unknown(
                reason = "已提交写入，但存储回执不可读，无法确认",
                next = "用 memory_read 回读 revision 确认",
            )
        }
    }

    override fun renderForModel(output: MemoryWriteOutput): ModelContent = ModelContent.Json(
        JSONObject()
            .put("scope", output.scope.name.lowercase())
            .put("mode", output.mode.name.lowercase())
            .put("before_revision", output.beforeRevision)
            .put("after_revision", output.afterRevision)
            .put("mutation_id", output.mutationId)
            .put("bytes", output.byteSize)
            .put("line_count", output.lineCount),
    )

    private fun append(current: String, text: String): String = when {
        text.isEmpty() -> current
        current.isEmpty() -> text
        else -> current.trimEnd('\n') + "\n" + text
    }

    private fun occurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) break
            count++
            from = at + needle.length
        }
        return count
    }

    private companion object {
        const val MAX_WRITE_CHARS = 3_500
        const val RETENTION_MS = 10 * 60 * 1000L
    }
}
