package io.github.fartown.movo.agent.tools.skill

import io.github.fartown.movo.agent.tools.core.ApprovalCategory
import io.github.fartown.movo.agent.tools.core.CallResolution
import io.github.fartown.movo.agent.tools.core.Evidence
import io.github.fartown.movo.agent.tools.core.ModelContent
import io.github.fartown.movo.agent.tools.core.Risk
import io.github.fartown.movo.agent.tools.core.Sensitivity
import io.github.fartown.movo.agent.tools.core.ToolArgs
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
import org.json.JSONArray
import org.json.JSONObject

internal enum class SkillInstallAction { CURATED, INSPECT, INSTALL }

internal data class SkillCatalogItem(val name: String, val path: String, val installed: Boolean)
internal data class InstalledSkillView(val id: String, val name: String)
internal data class SkillConflictView(val id: String, val name: String, val existingSource: String)

/** curated/inspect 的发现结果。 */
internal sealed interface SkillDiscoverResult {
    data class Items(
        val repository: String,
        val ref: String,
        val commitSha: String,
        val items: List<SkillCatalogItem>,
    ) : SkillDiscoverResult

    data class Failed(val code: ToolErrorCode, val message: String) : SkillDiscoverResult
}

/** install 的结果。 */
internal sealed interface SkillInstallOutcome {
    data class Installed(val items: List<InstalledSkillView>) : SkillInstallOutcome

    /** 与已安装技能冲突，需当场确认替换。 */
    data class Conflict(val conflicts: List<SkillConflictView>) : SkillInstallOutcome

    /** 提交阶段失败、目录状态不确定 → OUTCOME_UNKNOWN。 */
    data object CommitUncertain : SkillInstallOutcome

    data class Failed(val code: ToolErrorCode, val message: String) : SkillInstallOutcome
}

internal interface SkillInstallBackend {
    fun curated(): SkillDiscoverResult
    fun inspect(repository: String, ref: String?, path: String?): SkillDiscoverResult
    fun install(repository: String, ref: String?, paths: List<String>, replace: Boolean): SkillInstallOutcome
}

internal data class SkillInstallInput(
    val action: SkillInstallAction,
    val repository: String?,
    val ref: String?,
    val path: String?,
    val paths: List<String>,
    val replace: Boolean,
) : ToolInput

internal data class SkillInstallOutput(
    val action: SkillInstallAction,
    val discover: SkillDiscoverResult.Items?,
    val installed: List<InstalledSkillView>,
) : ToolOutput

/**
 * skill_install（external，确认，§39）：从公开 GitHub 发现和安装技能。
 * - curated：官方精选列表（只读发现）。
 * - inspect：列仓库内技能目录（只读发现）。
 * - install：安装选中目录；脚本不执行，available:"next_task"。
 *
 * 风险/确认：install → Risk.EXTERNAL（中央先确认一次）；curated/inspect 只是网络发现 → Risk.READ。
 * 冲突：install 命中已安装技能时当场弹确认卡（ctx.interaction），确认后以 replace 重装。
 * 提交失败目录状态不确定 → Verdict.Unknown（绝不冒领 ok）。
 */
internal class SkillInstallTool(
    private val backend: SkillInstallBackend,
) : ToolContract<SkillInstallInput, SkillInstallOutput> {
    override val name = "skill_install"
    override val domain = ToolDomain.SKILL
    override val summary =
        "从公开 GitHub 发现和安装技能：curated 官方精选、inspect 列仓库技能目录、install 安装选中目录。" +
            "脚本不执行，装完下一次任务才可用。"

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string("action", "动作", required = true, enum = SkillInstallAction.entries.map { it.name.lowercase() })
        string("repository", "GitHub 仓库 owner/repo 或 URL（inspect、install 必填）")
        string("ref", "分支、标签或 commit（可选）")
        string("path", "inspect 时限定的仓库子路径")
        stringArray("paths", "install 时安装的目录（来自 inspect，1–20）", minItems = 1, maxItems = 20)
        boolean("replace", "install 时允许替换同名已安装技能")
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): SkillInstallInput {
        val action = args.enum<SkillInstallAction>("action")
        val repository = args.stringOrNull("repository")?.trim()?.takeIf { it.isNotEmpty() }
        val paths = args.stringList("paths")
        when (action) {
            SkillInstallAction.CURATED -> Unit
            SkillInstallAction.INSPECT ->
                if (repository == null) fail(ToolErrorCode.INVALID_ARGUMENTS, "inspect 需要 repository")
            SkillInstallAction.INSTALL -> {
                if (repository == null) fail(ToolErrorCode.INVALID_ARGUMENTS, "install 需要 repository")
                if (paths.isEmpty()) fail(ToolErrorCode.INVALID_ARGUMENTS, "install 需要 paths（来自 inspect）")
                if (paths.size > 20) fail(ToolErrorCode.INVALID_ARGUMENTS, "paths 最多 20 个")
            }
        }
        return SkillInstallInput(
            action = action,
            repository = repository,
            ref = args.stringOrNull("ref")?.trim()?.takeIf { it.isNotEmpty() },
            path = args.stringOrNull("path")?.trim()?.takeIf { it.isNotEmpty() },
            paths = paths,
            replace = args.bool("replace", default = false),
        )
    }

    override fun resolve(input: SkillInstallInput, env: ToolEnvironment): CallResolution =
        CallResolution(
            // 只有 install 真正落盘，归为「安装技能」；curated/inspect 只是网络发现。
            risk = if (input.action == SkillInstallAction.INSTALL) Risk.EXTERNAL else Risk.READ,
            sensitivity = Sensitivity.NORMAL,
            resources = emptySet(),
            category = if (input.action == SkillInstallAction.INSTALL) ApprovalCategory.INSTALL else null,
        )

    override fun approvalPreview(input: SkillInstallInput): ApprovalPreview = ApprovalPreview(
        title = "安装这个技能？",
        detail = buildString {
            append(input.repository.orEmpty().take(120))
            if (input.paths.isNotEmpty()) append("：").append(input.paths.joinToString("、").take(120))
        },
    )

    override fun execute(
        input: SkillInstallInput,
        resolution: CallResolution,
        ctx: ToolContext,
    ): Verdict<SkillInstallOutput> = when (input.action) {
        SkillInstallAction.CURATED -> discover(input.action, backend.curated())
        SkillInstallAction.INSPECT -> discover(input.action, backend.inspect(input.repository!!, input.ref, input.path))
        SkillInstallAction.INSTALL -> install(input)
    }

    private fun discover(action: SkillInstallAction, result: SkillDiscoverResult): Verdict<SkillInstallOutput> =
        when (result) {
            is SkillDiscoverResult.Items -> Verdict.Read(SkillInstallOutput(action, result, emptyList()))
            is SkillDiscoverResult.Failed -> Verdict.Failed(ToolError(result.code, result.message))
        }

    private fun install(input: SkillInstallInput): Verdict<SkillInstallOutput> {
        // 与已安装技能同名且没传 replace：不替换，回给模型冲突，由它确认用户意图后带 replace=true 重试。
        return when (val o = backend.install(input.repository!!, input.ref, input.paths, input.replace)) {
            is SkillInstallOutcome.Installed -> Verdict.Done(
                SkillInstallOutput(SkillInstallAction.INSTALL, null, o.items),
                Evidence.ReadBack("installed=${o.items.joinToString(",") { it.id }}"),
            )
            is SkillInstallOutcome.Conflict -> Verdict.Failed(
                ToolError(
                    ToolErrorCode.CONFLICT,
                    "与已安装技能同名：${o.conflicts.joinToString("、") { it.name }}",
                    hint = "用户要更新它时带 replace=true 重试；不清楚就先问用户",
                ),
            )
            SkillInstallOutcome.CommitUncertain -> Verdict.Unknown(
                reason = "提交阶段失败，技能目录状态不确定",
                next = "用 skill_read 或技能索引核对是否已安装，不要直接重装",
            )
            is SkillInstallOutcome.Failed -> Verdict.Failed(ToolError(o.code, o.message))
        }
    }

    override fun renderForModel(output: SkillInstallOutput): ModelContent {
        val json = JSONObject().put("action", output.action.name.lowercase())
        output.discover?.let { d ->
            json.put("repository", d.repository).put("ref", d.ref).put("commit_sha", d.commitSha)
            json.put(
                "items",
                JSONArray().apply {
                    d.items.forEach {
                        put(JSONObject().put("name", it.name).put("path", it.path).put("installed", it.installed))
                    }
                },
            )
        }
        if (output.action == SkillInstallAction.INSTALL) {
            json.put(
                "installed",
                JSONArray().apply {
                    output.installed.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
                },
            )
            json.put("available", "next_task").put("scripts_executed", false)
        }
        return ModelContent.Json(json)
    }
}
