package io.github.fartown.movo.agent.tools.skill

import android.content.Context
import io.github.fartown.movo.agent.model.AgentHttpClient
import io.github.fartown.movo.agent.skill.GitHubSkillRepositoryParser
import io.github.fartown.movo.agent.skill.GitHubSkillSourceException
import io.github.fartown.movo.agent.skill.PublicGitHubSkillSource
import io.github.fartown.movo.agent.skill.SkillCompatibilityChecker
import io.github.fartown.movo.agent.skill.SkillIndexEntry
import io.github.fartown.movo.agent.skill.SkillIndexService
import io.github.fartown.movo.agent.skill.SkillInstallErrorCode
import io.github.fartown.movo.agent.skill.SkillInstallResult
import io.github.fartown.movo.agent.skill.SkillLoader
import io.github.fartown.movo.agent.skill.SkillParser
import io.github.fartown.movo.agent.skill.SkillResourceErrorCode
import io.github.fartown.movo.agent.skill.SkillResourceError
import io.github.fartown.movo.agent.skill.SkillResourceListResult
import io.github.fartown.movo.agent.skill.SkillResourceReadResult
import io.github.fartown.movo.agent.skill.SkillRuntime
import io.github.fartown.movo.agent.tools.core.ToolErrorCode

/**
 * 真实 skill_read 后端：复用 SkillRuntime 的索引、加载器与资源读取器。
 * 不带 path → SKILL.md 正文 + 资源清单；带 path → 技能目录内文本资源。
 */
internal class AndroidSkillReadBackend(context: Context) : SkillReadBackend {
    private val appContext = context.applicationContext
    private val indexService: SkillIndexService = SkillRuntime.createIndexService(appContext)
    private val loader: SkillLoader = SkillRuntime.createLoader(appContext)
    private val resourceReader = SkillRuntime.createResourceReader(appContext)

    override fun read(skill: String, path: String?): SkillReadResult {
        val entry = indexService.findInstalledSkill(skill) ?: return SkillReadResult.NotFound
        val compat = SkillCompatibilityChecker.evaluate(entry)
        if (!compat.available) return SkillReadResult.Unsupported(compat.reason ?: "该技能在本设备不可用")

        if (path == null) {
            val resolved = loader.load(entry, "skill_read") ?: return SkillReadResult.NotFound
            val files = when (val r = resourceReader.listResources(entry, null)) {
                is SkillResourceListResult.Success -> r.resources.map { it.relativePath }
                is SkillResourceListResult.Failure -> emptyList()
            }
            return SkillReadResult.Body(
                skill = entry.id,
                rootPath = entry.rootPath,
                content = resolved.bodyMarkdown,
                files = files,
                name = entry.name,
                description = entry.description,
                frontmatter = resolved.frontmatter,
            )
        }

        return when (val r = resourceReader.readText(entry, path)) {
            is SkillResourceReadResult.Success ->
                SkillReadResult.Resource(entry.id, r.relativePath, entry.rootPath, r.text)
            is SkillResourceReadResult.Failure -> mapResourceFailure(r.error)
        }
    }

    private fun mapResourceFailure(error: SkillResourceError): SkillReadResult = when (error.code) {
        SkillResourceErrorCode.RESOURCE_NOT_FOUND,
        SkillResourceErrorCode.INVALID_RELATIVE_PATH,
        SkillResourceErrorCode.INVALID_SKILL_ROOT,
        -> SkillReadResult.NotFound
        SkillResourceErrorCode.BINARY_RESOURCE -> SkillReadResult.Unsupported(error.message)
        SkillResourceErrorCode.RESOURCE_TOO_LARGE,
        SkillResourceErrorCode.TOO_MANY_RESOURCES,
        -> SkillReadResult.TooLarge(error.message)
        SkillResourceErrorCode.IO_ERROR -> SkillReadResult.Unsupported(error.message)
    }
}

/**
 * curated / inspect 的候选（名字, 路径）对上已安装技能：和重构前一样按规范化后的 id、名称比较，
 * 停用的也算已安装（enabled=false），免得模型当成没装再去装、撞同名冲突。
 */
internal fun skillCatalogItems(candidates: List<Pair<String, String>>, entries: List<SkillIndexEntry>): List<SkillCatalogItem> {
    val installed = entries.filter { it.installed }
    return candidates.map { (name, path) ->
        val key = SkillParser.normalizeSkillLookup(name)
        val match = installed.firstOrNull { SkillParser.normalizeSkillLookup(it.id) == key }
            ?: installed.firstOrNull { SkillParser.normalizeSkillLookup(it.name) == key }
        SkillCatalogItem(name, path, installed = match != null, enabled = match?.enabled ?: true)
    }
}

/**
 * 真实 skill_install 后端：curated/inspect 走 GitHub 只读发现，install 下载归档后交 SkillPackageInstaller。
 *
 * 注意：
 * - PublicGitHubSkillSource 用共享的 AgentHttpClient.client（连接 15 秒、读 60 秒，和重构前一样）；
 *   Provider 关闭时只取消本次任务里进行中的请求，不关共享客户端。
 * - 列表里的 installed 按「已安装」判断，不看是否启用：停用的技能也算装了（另标 enabled=false）。
 * - replace=true 时 installRepositoryZip 要求 expectedReplacementIds 等于所选全部技能 id；这里从归档
 *   二次 inspect 推导 id，与所选路径匹配后传入。
 * - install 的 ref 是 SkillInstallTool 核对过的、检查时的 commitSha（下载时 GitHub 返回的 commit 不一致会报错）。
 */
internal class AndroidSkillInstallBackend(context: Context) : SkillInstallBackend, AutoCloseable {
    private val appContext = context.applicationContext
    private val source = PublicGitHubSkillSource(appContext.cacheDir, AgentHttpClient.client)
    private val installer = SkillRuntime.createPackageInstaller(appContext)
    private val indexService = SkillRuntime.createIndexService(appContext)

    override fun curated(): SkillDiscoverResult = runCatching {
        val insp = source.listCurated()
        SkillDiscoverResult.Items(
            repository = insp.repository,
            ref = insp.ref,
            commitSha = insp.commitSha,
            items = catalogItems(insp.candidates.map { it.name to it.path }),
            prefix = insp.prefix,
        )
    }.getOrElse { mapGithubFailure(it) }

    private fun catalogItems(candidates: List<Pair<String, String>>): List<SkillCatalogItem> =
        skillCatalogItems(candidates, indexService.listSkillsForManagement())

    override fun inspect(repository: String, ref: String?, path: String?): SkillDiscoverResult = runCatching {
        val repo = GitHubSkillRepositoryParser.resolve(repository, ref, path)
        val insp = source.inspect(repo)
        SkillDiscoverResult.Items(
            repository = insp.repository,
            ref = insp.ref,
            commitSha = insp.commitSha,
            items = catalogItems(insp.candidates.map { it.name to it.path }),
            prefix = insp.prefix,
        )
    }.getOrElse { mapGithubFailure(it) }

    override fun install(
        repository: String,
        ref: String?,
        paths: List<String>,
        replace: Boolean,
        cancelled: () -> Boolean,
    ): SkillInstallOutcome = runCatching {
        val repo = GitHubSkillRepositoryParser.resolve(repository, ref, null)
        source.downloadArchive(repo).use { archive ->
            // 下载期间任务被取消：不再解包提交（旧实现同样在下载后、提交前检查）。
            if (cancelled()) return@runCatching SkillInstallOutcome.Failed(ToolErrorCode.CANCELLED, "技能安装已取消，没有写入文件")
            val open = { archive.file.inputStream() }
            val expectedIds: Set<String> = if (replace) {
                when (val insp = installer.inspectRepositoryZip(open, isCancelled = cancelled)) {
                    is io.github.fartown.movo.agent.skill.SkillArchiveInspectionResult.Success ->
                        insp.candidates.filter { it.relativePath in paths }.mapTo(mutableSetOf()) { it.id }
                    is io.github.fartown.movo.agent.skill.SkillArchiveInspectionResult.Failure -> emptySet()
                }
            } else {
                emptySet()
            }
            val result = installer.installRepositoryZip(
                openStream = open,
                selectedPaths = paths,
                replaceUserSkills = replace,
                expectedReplacementIds = expectedIds,
                isCancelled = cancelled,
            )
            mapInstallResult(result)
        }
    }.getOrElse { throwable ->
        if (throwable is GitHubSkillSourceException) mapGithubFailureToInstall(throwable)
        else SkillInstallOutcome.Failed(ToolErrorCode.EXTERNAL_ERROR, throwable.message ?: "安装失败")
    }

    private fun mapInstallResult(result: SkillInstallResult): SkillInstallOutcome = when (result) {
        is SkillInstallResult.Success -> SkillInstallOutcome.Installed(
            result.installed.map { InstalledSkillView(it.id, it.name) },
        )
        is SkillInstallResult.Conflict -> SkillInstallOutcome.Conflict(
            result.conflicts.map { SkillConflictView(it.id, it.name, it.existingSource, it.replaceAllowed) },
        )
        is SkillInstallResult.Failure ->
            if (result.error.code == SkillInstallErrorCode.COMMIT_FAILED) {
                SkillInstallOutcome.CommitUncertain
            } else {
                SkillInstallOutcome.Failed(mapInstallCode(result.error.code), result.error.message)
            }
    }

    private fun mapInstallCode(code: SkillInstallErrorCode): ToolErrorCode = when (code) {
        SkillInstallErrorCode.NO_SKILL_FOUND, SkillInstallErrorCode.MULTIPLE_SKILLS_FOUND -> ToolErrorCode.NOT_FOUND
        SkillInstallErrorCode.INVALID_SELECTION, SkillInstallErrorCode.DUPLICATE_ENTRY,
        SkillInstallErrorCode.DUPLICATE_SKILL_ID, SkillInstallErrorCode.INVALID_SKILL,
        SkillInstallErrorCode.UNSAFE_ENTRY_PATH, SkillInstallErrorCode.ENTRY_PATH_TOO_DEEP,
        -> ToolErrorCode.INVALID_ARGUMENTS
        SkillInstallErrorCode.TARGET_NOT_REPLACEABLE -> ToolErrorCode.CONFLICT
        SkillInstallErrorCode.ARCHIVE_TOO_LARGE, SkillInstallErrorCode.ENTRY_TOO_LARGE,
        SkillInstallErrorCode.EXTRACTED_CONTENT_TOO_LARGE, SkillInstallErrorCode.TOO_MANY_ENTRIES,
        -> ToolErrorCode.TOO_LARGE
        SkillInstallErrorCode.CANCELLED -> ToolErrorCode.CANCELLED
        SkillInstallErrorCode.INVALID_ARCHIVE, SkillInstallErrorCode.IO_ERROR,
        SkillInstallErrorCode.COMMIT_FAILED,
        -> ToolErrorCode.EXTERNAL_ERROR
    }

    private fun mapGithubFailure(throwable: Throwable): SkillDiscoverResult {
        val code = (throwable as? GitHubSkillSourceException)?.code.orEmpty()
        return SkillDiscoverResult.Failed(mapGithubCode(code), throwable.message ?: "GitHub 访问失败")
    }

    private fun mapGithubFailureToInstall(e: GitHubSkillSourceException): SkillInstallOutcome =
        SkillInstallOutcome.Failed(mapGithubCode(e.code), e.message ?: "GitHub 访问失败")

    private fun mapGithubCode(code: String): ToolErrorCode = when {
        code.contains("NOT_FOUND") -> ToolErrorCode.NOT_FOUND
        code.contains("TOO_LARGE") || code.contains("TOO_MANY") -> ToolErrorCode.TOO_LARGE
        code.contains("NETWORK") || code.contains("TIMEOUT") -> ToolErrorCode.NETWORK_ERROR
        else -> ToolErrorCode.EXTERNAL_ERROR
    }

    override fun close() {
        runCatching { source.close() }
    }
}
