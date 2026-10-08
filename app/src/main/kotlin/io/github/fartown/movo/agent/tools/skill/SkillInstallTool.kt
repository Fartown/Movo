package io.github.fartown.movo.agent.tools.skill

import io.github.fartown.movo.agent.tools.core.ToolUiBlock
import io.github.fartown.movo.agent.tools.core.ToolUiView
import io.github.fartown.movo.agent.tools.core.forTitle
import io.github.fartown.movo.agent.tools.core.uiFields
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
import io.github.fartown.movo.agent.skill.GitHubSkillRepositoryParser
import io.github.fartown.movo.agent.skill.GitHubSkillSourceException
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal enum class SkillInstallAction { CURATED, INSPECT, INSTALL }

/** [installed] 含已停用的技能；停用的 [enabled] 为 false（要用得先在设置里打开，不必重装）。 */
internal data class SkillCatalogItem(val name: String, val path: String, val installed: Boolean, val enabled: Boolean = true)
internal data class InstalledSkillView(val id: String, val name: String)

/** 同名冲突：[existingSource] 是已装那份的来源（builtin 内置 / user 用户装的）；[replaceAllowed] 为 false 时带 replace 也装不上。 */
internal data class SkillConflictView(
    val id: String,
    val name: String,
    val existingSource: String,
    val replaceAllowed: Boolean = existingSource != "builtin",
)

/** curated/inspect 的发现结果。 */
internal sealed interface SkillDiscoverResult {
    data class Items(
        val repository: String,
        val ref: String,
        val commitSha: String,
        val items: List<SkillCatalogItem>,
        /** 检查时限定的仓库子目录（inspect 的 path、URL 里的目录、curated 的 skills/.curated）；整个仓库为空。 */
        val prefix: String? = null,
    ) : SkillDiscoverResult

    data class Failed(val code: ToolErrorCode, val message: String) : SkillDiscoverResult
}

/** install 的结果。 */
internal sealed interface SkillInstallOutcome {
    data class Installed(val items: List<InstalledSkillView>) : SkillInstallOutcome

    /** 与已安装技能同名：不替换，作为 CONFLICT 回给模型，由它确认用户意图后带 replace=true 重试。 */
    data class Conflict(val conflicts: List<SkillConflictView>) : SkillInstallOutcome

    /** 提交阶段失败、目录状态不确定 → OUTCOME_UNKNOWN。 */
    data object CommitUncertain : SkillInstallOutcome

    data class Failed(val code: ToolErrorCode, val message: String) : SkillInstallOutcome
}

internal interface SkillInstallBackend {
    fun curated(): SkillDiscoverResult
    fun inspect(repository: String, ref: String?, path: String?): SkillDiscoverResult

    /** 安装选中目录；[ref] 是 inspect 时钉住的 commit。[cancelled] 为真时不再提交文件（返回 CANCELLED）。 */
    fun install(
        repository: String,
        ref: String?,
        paths: List<String>,
        replace: Boolean,
        cancelled: () -> Boolean = { false },
    ): SkillInstallOutcome
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
 * 先检查再安装（恢复重构前 skills_install_from_github 的约束）：install 必须对应本次任务里 curated / inspect 过的同一仓库与 ref，
 * 所选目录必须是那次检查返回的候选、且在检查限定的目录内，下载固定用检查时的 commitSha——
 * 检查之后仓库被改，装上的也还是用户看过的那个版本。没检查过就在确认之前拒绝。本工具一次任务一个实例，记录随任务结束清掉。
 * 冲突：install 命中已安装技能且没传 replace 时不替换，也不弹卡，把 CONFLICT 回给模型；用户要更新时由模型带 replace=true 重试。
 * 提交失败目录状态不确定 → Verdict.Unknown（绝不冒领 ok）。运行被取消时不再提交文件。
 */
internal class SkillInstallTool(
    private val backend: SkillInstallBackend,
) : ToolContract<SkillInstallInput, SkillInstallOutput> {

    /** 本次任务里检查过的仓库：键是「仓库@ref」（见 [inspectionKey]）。 */
    private val inspections = ConcurrentHashMap<String, InspectionSnapshot>()
    override val name = "skill_install"
    override val domain = ToolDomain.SKILL
    override val summary =
        "从公开 GitHub 发现和安装技能：curated 官方精选、inspect 列仓库技能目录、install 安装选中目录" +
            "（先在本次任务里 curated/inspect 同一仓库，装的是检查时的版本）。脚本不执行，装完下一次任务才可用。"

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string("action", "动作", required = true, enum = SkillInstallAction.entries.map { it.name.lowercase() })
        string("repository", "GitHub 仓库 owner/repo 或 URL（inspect、install 必填）")
        string("ref", "分支、标签或 commit（可选；install 时不传，或传检查返回的 commit_sha）")
        string("path", "inspect 时限定的仓库子路径")
        stringArray("paths", "install 时安装的目录（本次 curated/inspect 返回的 items 里的 path，1–20）", minItems = 1, maxItems = 20)
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
            // 没先检查、或选的目录不在检查结果里：确认之前就拒绝，不让用户批一个装不了的安装。
            reject = if (input.action == SkillInstallAction.INSTALL) installPlan(input).error else null,
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
        SkillInstallAction.CURATED -> discover(input, backend.curated())
        SkillInstallAction.INSPECT -> discover(input, backend.inspect(input.repository!!, input.ref, input.path))
        SkillInstallAction.INSTALL -> install(input, ctx)
    }

    private fun discover(input: SkillInstallInput, result: SkillDiscoverResult): Verdict<SkillInstallOutput> =
        when (result) {
            is SkillDiscoverResult.Items -> {
                remember(input, result)
                Verdict.Read(SkillInstallOutput(input.action, result, emptyList()))
            }
            is SkillDiscoverResult.Failed -> Verdict.Failed(ToolError(result.code, result.message))
        }

    /** 一次检查的结果：钉住的 commit、限定的目录、候选目录。 */
    private data class InspectionSnapshot(val commitSha: String, val prefix: String?, val paths: Set<String>)

    /** install 要用的仓库与 commit；不满足「先检查再安装」时 [error] 非空。 */
    private data class InstallPlan(
        val repository: String? = null,
        val commitSha: String? = null,
        val paths: List<String> = emptyList(),
        val error: ToolError? = null,
    )

    /**
     * 记下这次检查（移植旧 rememberInspection）：按请求的 ref、返回的 ref 和 commitSha 都记一份，
     * 没指定 ref（curated 也算）的再记成默认，这样 install 不带 ref、带分支名或带 commit_sha 都能对上。
     */
    private fun remember(input: SkillInstallInput, result: SkillDiscoverResult.Items) {
        val requested = runCatching {
            when (input.action) {
                SkillInstallAction.INSPECT -> GitHubSkillRepositoryParser.resolve(input.repository!!, input.ref, input.path)
                else -> GitHubSkillRepositoryParser.parse(result.repository)
            }
        }.getOrNull() ?: return
        val slug = runCatching { GitHubSkillRepositoryParser.parse(result.repository).slug }.getOrDefault(requested.slug)
        val snapshot = InspectionSnapshot(
            commitSha = result.commitSha,
            prefix = result.prefix?.takeUnless { it == "." },
            paths = result.items.mapTo(mutableSetOf()) { it.path },
        )
        val refs = buildSet {
            add(requested.ref)
            add(result.ref)
            add(result.commitSha)
            if (requested.ref == null) add(null)
        }
        refs.forEach { ref -> inspections[inspectionKey(slug, ref)] = snapshot }
    }

    /** 核对 install 是否对应本次任务里的一次检查（移植旧 skillsInstallFromGitHub 的校验）。 */
    private fun installPlan(input: SkillInstallInput): InstallPlan {
        fun reject(message: String, hint: String) =
            InstallPlan(error = ToolError(ToolErrorCode.INVALID_ARGUMENTS, message, hint = hint))
        val requested = try {
            GitHubSkillRepositoryParser.resolve(input.repository!!, input.ref, null)
        } catch (e: GitHubSkillSourceException) {
            return reject(e.message ?: "GitHub 仓库无效", "repository 用 owner/repo 或 github.com 的仓库地址")
        }
        val selected = try {
            input.paths.map(GitHubSkillRepositoryParser::normalizeRelativePath)
        } catch (e: GitHubSkillSourceException) {
            return reject(e.message ?: "Skill 路径无效", "paths 用检查结果 items 里的 path")
        }
        // install 没带 ref、这个仓库又只检查过一个版本（比如 inspect 时指定了分支）：就是那一次，不必让模型再补 ref。
        val snapshot = inspections[inspectionKey(requested.slug, requested.ref)]
            ?: onlyInspectionOf(requested.slug).takeIf { requested.ref == null }
            ?: return reject(
                "安装前要先在这次任务里检查同一个仓库：${requested.slug}" + requested.ref?.let { "@$it" }.orEmpty(),
                "先用 skill_install action=inspect（官方精选用 action=curated）查看，再从返回的 items 里选 path 安装；ref 不传或传返回的 commit_sha",
            )
        selected.firstOrNull { it !in snapshot.paths }?.let { path ->
            return reject("所选目录不在这次检查返回的候选里：$path", "paths 只能用检查结果 items 里的 path")
        }
        // 检查限定了目录（inspect 的 path、curated 的精选目录），或安装时给的是指向某个目录的 URL：所选目录都要在里面。
        val scopes = listOfNotNull(snapshot.prefix, requested.path?.takeUnless { it == "." })
        selected.firstOrNull { path -> scopes.any { scope -> path != scope && !path.startsWith("$scope/") } }?.let { path ->
            return reject("所选目录不在检查的目录范围内：$path", "paths 只能用检查结果 items 里的 path")
        }
        return InstallPlan(repository = requested.slug, commitSha = snapshot.commitSha, paths = selected)
    }

    private fun inspectionKey(slug: String, ref: String?): String = "${slug.lowercase(Locale.ROOT)}@${ref.orEmpty()}"

    /** 这个仓库本次任务里只检查过一个版本时返回它；没检查过或检查过多个版本返回 null。 */
    private fun onlyInspectionOf(slug: String): InspectionSnapshot? {
        val prefix = inspectionKey(slug, null)
        return inspections.filterKeys { it.startsWith(prefix) }.values.distinct().singleOrNull()
    }

    private fun install(input: SkillInstallInput, ctx: ToolContext): Verdict<SkillInstallOutput> {
        // resolve 已经拒过没检查的；这里复核一次（两步之间记录不会变少，只防直接调用）。
        val plan = installPlan(input)
        plan.error?.let { return Verdict.Failed(it) }
        ctx.checkCancelled()
        // 下载固定用检查时的 commit；仓库按 owner/repo 传（URL 里的 ref 已由检查记录代替）。
        val outcome = backend.install(
            repository = plan.repository!!,
            ref = plan.commitSha,
            paths = plan.paths,
            replace = input.replace,
            cancelled = { ctx.isCancelled },
        )
        // 与已安装技能同名且没传 replace：不替换，回给模型冲突，由它确认用户意图后带 replace=true 重试。
        return when (val o = outcome) {
            is SkillInstallOutcome.Installed -> Verdict.Done(
                SkillInstallOutput(SkillInstallAction.INSTALL, null, o.items),
                Evidence.ReadBack("installed=${o.items.joinToString(",") { it.id }}"),
            )
            is SkillInstallOutcome.Conflict -> Verdict.Failed(conflictError(o.conflicts))
            SkillInstallOutcome.CommitUncertain -> Verdict.Unknown(
                reason = "提交阶段失败，技能目录状态不确定",
                next = "用 skill_read 或技能索引核对是否已安装，不要直接重装",
            )
            is SkillInstallOutcome.Failed -> Verdict.Failed(ToolError(o.code, o.message))
        }
    }

    /** 冲突写明每个技能的 id、来源和能不能替换：内置技能不能替换，带 replace=true 重试也没用。 */
    private fun conflictError(conflicts: List<SkillConflictView>): ToolError {
        val listed = conflicts.joinToString("、") { c ->
            val source = when (c.existingSource) {
                "builtin" -> "内置技能"
                "user" -> "用户安装的"
                else -> "来源不明"
            }
            "${c.name}（id ${c.id}，$source，${if (c.replaceAllowed) "可以替换" else "不能替换"}）"
        }
        val hint = when {
            conflicts.all { it.replaceAllowed } -> "用户要更新它时带 replace=true 重试；不清楚就先问用户"
            conflicts.none { it.replaceAllowed } -> "这些不能替换，带 replace=true 也装不上；告诉用户已经有同名技能"
            else -> "可以替换的那些，用户要更新时 paths 只选它们、带 replace=true 重试；不能替换的告诉用户已经有同名技能"
        }
        return ToolError(ToolErrorCode.CONFLICT, "与已安装技能同名：$listed", hint = hint)
    }

    override fun uiTitle(input: SkillInstallInput): String = when (input.action) {
        SkillInstallAction.CURATED -> "浏览精选技能"
        SkillInstallAction.INSPECT -> "查看技能仓库 · ${input.repository.orEmpty().forTitle(30)}"
        SkillInstallAction.INSTALL -> "安装技能 · ${input.repository.orEmpty().forTitle(30)}"
    }

    override fun renderForUi(input: SkillInstallInput, output: SkillInstallOutput): ToolUiView {
        if (output.action == SkillInstallAction.INSTALL) {
            return ToolUiView(
                summary = "已装 ${output.installed.size} 个",
                blocks = listOf(ToolUiBlock.Items(output.installed.map { ToolUiBlock.Item(it.name) })).filter { output.installed.isNotEmpty() },
            )
        }
        val items = output.discover?.items.orEmpty()
        return ToolUiView(
            summary = if (items.isEmpty()) "没有技能" else "${items.size} 个技能",
            blocks = listOf(
                ToolUiBlock.Items(items.map { ToolUiBlock.Item(it.name, it.path, trailing = if (!it.installed) null else if (it.enabled) "已装" else "已装 · 停用") }),
            ).filter { items.isNotEmpty() },
        )
    }

    override fun renderForModel(output: SkillInstallOutput): ModelContent {
        val json = JSONObject().put("action", output.action.name.lowercase())
        output.discover?.let { d ->
            json.put("repository", d.repository).put("ref", d.ref).put("commit_sha", d.commitSha)
            json.put(
                "items",
                JSONArray().apply {
                    d.items.forEach {
                        put(JSONObject().put("name", it.name).put("path", it.path).put("installed", it.installed)
                            .apply { if (it.installed && !it.enabled) put("enabled", false) })
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
