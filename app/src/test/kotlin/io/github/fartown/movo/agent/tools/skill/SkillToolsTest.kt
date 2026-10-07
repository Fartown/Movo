package io.github.fartown.movo.agent.tools.skill

import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.tools.core.PermissionMode
import io.github.fartown.movo.agent.tools.core.ApprovalPolicy
import io.github.fartown.movo.agent.tools.core.ApprovalCategory
import io.github.fartown.movo.agent.tools.core.ApprovalDecision
import io.github.fartown.movo.agent.tools.core.ApprovalRequest
import io.github.fartown.movo.agent.tools.core.ContractTool
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolPipeline
import io.github.fartown.movo.agent.tools.core.ToolProvider
import io.github.fartown.movo.agent.tools.core.ToolRegistry
import io.github.fartown.movo.agent.tools.core.UserAnswer
import io.github.fartown.movo.agent.tools.core.UserInteraction
import io.github.fartown.movo.agent.tools.core.UserQuestion
import io.github.fartown.movo.core.AndroidAgentLogger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * skill_read / skill_install 的管线验证：只读发现、安装回读 Done、冲突当场确认、外发确认、提交不确定 Unknown；
 * 以及「先检查再安装」：没检查过、选的不是检查返回的目录就在确认前拒绝，安装钉住检查时的 commit。
 */
@RunWith(RobolectricTestRunner::class)
class SkillToolsTest {

    private class FakeRead(private val result: SkillReadResult) : SkillReadBackend {
        override fun read(skill: String, path: String?): SkillReadResult = result
    }

    private class FakeInstall(private val outcome: SkillInstallOutcome) : SkillInstallBackend {
        var lastReplace: Boolean? = null
        var lastRepository: String? = null
        var lastRef: String? = null
        var lastPaths: List<String>? = null
        var installs = 0
        /** 安装时运行是否已被取消（测取消有没有接到后端）。 */
        var cancelledDuringInstall: Boolean? = null
        /** 检查时返回的 commit：测「检查之后仓库又被改了」时换掉它。 */
        var sha = "sha1"
        /** install 进行中时调一下：模拟下载期间用户点了停止。 */
        var duringInstall: () -> Unit = {}

        override fun curated() = SkillDiscoverResult.Items(
            "openai/skills", "main", sha,
            listOf(SkillCatalogItem("alpha", "skills/.curated/alpha", installed = false)),
            prefix = "skills/.curated",
        )
        override fun inspect(repository: String, ref: String?, path: String?) = SkillDiscoverResult.Items(
            "o/r", ref ?: "main", sha,
            listOf(
                SkillCatalogItem("alpha", "skills/alpha", installed = false),
                SkillCatalogItem("beta", "tools/beta", installed = false),
            ),
            prefix = path,
        )
        override fun install(
            repository: String,
            ref: String?,
            paths: List<String>,
            replace: Boolean,
            cancelled: () -> Boolean,
        ): SkillInstallOutcome {
            installs++
            lastReplace = replace
            lastRepository = repository
            lastRef = ref
            lastPaths = paths
            duringInstall()
            cancelledDuringInstall = cancelled()
            return if (outcome is SkillInstallOutcome.Conflict && replace) {
                SkillInstallOutcome.Installed(listOf(InstalledSkillView("alpha", "alpha")))
            } else {
                outcome
            }
        }
    }

    private val approve = object : UserInteraction {
        override val available = true
        override fun ask(question: UserQuestion, timeoutMs: Long) = UserAnswer.Declined
        override fun approve(request: ApprovalRequest, timeoutMs: Long) = ApprovalDecision.Approved
    }
    private val decline = object : UserInteraction {
        override val available = true
        override fun ask(question: UserQuestion, timeoutMs: Long) = UserAnswer.Declined
        override fun approve(request: ApprovalRequest, timeoutMs: Long) = ApprovalDecision.Declined
    }

    private fun pipeline(
        readBackend: SkillReadBackend = FakeRead(SkillReadResult.NotFound),
        installBackend: SkillInstallBackend = FakeInstall(SkillInstallOutcome.Installed(emptyList())),
        interaction: UserInteraction = UserInteraction.NONE,
        env: ToolEnvironment = ToolEnvironment(),
        cancelled: () -> Boolean = { false },
    ): ToolPipeline {
        val provider = object : ToolProvider {
            override val tools = listOf(
                ContractTool(SkillReadTool(readBackend)),
                ContractTool(SkillInstallTool(installBackend)),
            )
        }
        return ToolPipeline(
            registry = ToolRegistry(listOf(provider)),
            environment = { env },
            appContext = ApplicationProvider.getApplicationContext(),
            logger = AndroidAgentLogger,
            runId = "run1",
            cancelled = cancelled,
            interaction = interaction,
        ).also { it.catalog() }
    }

    private fun call(name: String, args: String) = AgentModelClient.ToolCall("c1", name, args)

    /** 先检查 o/r（安装前必须的一步）。 */
    private fun ToolPipeline.inspect(args: String = """{"action":"inspect","repository":"o/r"}""") =
        execute(call("skill_install", args)).also { assertEquals("ok", it.status) }

    private fun ToolPipeline.install(args: String = """{"action":"install","repository":"o/r","paths":["skills/alpha"]}""") =
        execute(call("skill_install", args))

    @Test
    fun read_body_isReadOnly() {
        val p = pipeline(readBackend = FakeRead(SkillReadResult.Body("pdf", "/root/pdf", "# 正文", listOf("scripts/run.py"))))
        val r = p.execute(call("skill_read", """{"skill":"pdf"}"""))
        val json = JSONObject(r.content)
        assertEquals("ok", json.getString("status"))
        assertFalse(json.has("effect_verified"))
        assertFalse(r.sensitive)
        val data = json.getJSONObject("data")
        assertEquals("/root/pdf", data.getString("root_path"))
        assertEquals("scripts/run.py", data.getJSONArray("files").getString(0))
    }

    @Test
    fun read_notFound() {
        val p = pipeline(readBackend = FakeRead(SkillReadResult.NotFound))
        val r = p.execute(call("skill_read", """{"skill":"nope"}"""))
        assertEquals("NOT_FOUND", r.errorCode)
    }

    @Test
    fun read_unsupported_nextTask() {
        val p = pipeline(readBackend = FakeRead(SkillReadResult.Unsupported("下一次任务才可用")))
        val r = p.execute(call("skill_read", """{"skill":"fresh"}"""))
        assertEquals("UNSUPPORTED", r.errorCode)
    }

    @Test
    fun curated_isReadOnly_noApproval() {
        val p = pipeline() // NONE interaction：READ 动作不需确认
        val r = p.execute(call("skill_install", """{"action":"curated"}"""))
        val json = JSONObject(r.content)
        assertEquals("ok", json.getString("status"))
        assertFalse(json.has("effect_verified"))
        assertTrue(JSONObject(r.content).getJSONObject("data").getJSONArray("items").length() > 0)
    }

    @Test
    fun install_approved_returnsDone() {
        val p = pipeline(
            installBackend = FakeInstall(SkillInstallOutcome.Installed(listOf(InstalledSkillView("alpha", "alpha")))),
            interaction = approve,
        )
        p.inspect()
        val r = p.install()
        val json = JSONObject(r.content)
        assertEquals("ok", json.getString("status"))
        assertTrue(json.getBoolean("effect_verified"))
        assertEquals("next_task", json.getJSONObject("data").getString("available"))
    }

    @Test
    fun install_installRule_declined_userDeclined() {
        val rule = ToolEnvironment(
            approvalPolicy = ApprovalPolicy(mode = PermissionMode.MANUAL, categories = setOf(ApprovalCategory.INSTALL)),
        )
        val p = pipeline(interaction = decline, env = rule)
        p.inspect()
        val r = p.install()
        assertEquals("USER_DECLINED", r.errorCode)
    }

    @Test
    fun install_conflict_returnsConflictWithoutReplacing() {
        val backend = FakeInstall(
            SkillInstallOutcome.Conflict(listOf(SkillConflictView("alpha", "alpha", "user"))),
        )
        val p = pipeline(installBackend = backend, interaction = approve)
        p.inspect()
        val r = p.install()
        // 同名冲突不再弹卡当场替换：回给模型，由它确认用户意图后带 replace=true 重试。
        assertEquals("CONFLICT", r.errorCode)
        assertTrue(JSONObject(r.content).toString().contains("alpha"))
        assertEquals(false, backend.lastReplace)
    }

    @Test
    fun read_body_returnsNameDescriptionFrontmatter() {
        val body = SkillReadResult.Body(
            "pdf", "/root/pdf", "# 正文", emptyList(),
            name = "PDF 工具", description = "处理 PDF",
            frontmatter = mapOf("name" to "PDF 工具", "description" to "处理 PDF", "compatibility" to "需要终端"),
        )
        val data = JSONObject(pipeline(readBackend = FakeRead(body)).execute(call("skill_read", """{"skill":"pdf"}""")).content)
            .getJSONObject("data")
        assertEquals("PDF 工具", data.getString("name"))
        assertEquals("处理 PDF", data.getString("description"))
        assertEquals("需要终端", data.getJSONObject("frontmatter").getString("compatibility"))
    }

    @Test
    fun read_resource_hasNoHeader() {
        val p = pipeline(readBackend = FakeRead(SkillReadResult.Resource("pdf", "docs/a.md", "/root/pdf", "资源")))
        val data = JSONObject(p.execute(call("skill_read", """{"skill":"pdf","path":"docs/a.md"}""")).content).getJSONObject("data")
        assertFalse(data.has("frontmatter"))
    }

    @Test
    fun install_conflict_saysIdAndWhetherReplaceable() {
        val backend = FakeInstall(
            SkillInstallOutcome.Conflict(listOf(SkillConflictView("alpha", "Alpha", "builtin", replaceAllowed = false))),
        )
        val p = pipeline(installBackend = backend, interaction = approve)
        p.inspect()
        val json = JSONObject(p.install().content)
        assertEquals("CONFLICT", json.getString("code"))
        assertTrue(json.getString("message").contains("id alpha"))
        assertTrue(json.getString("message").contains("内置技能"))
        assertTrue(json.getString("message").contains("不能替换"))
        assertTrue(json.getString("hint").contains("带 replace=true 也装不上"))
    }

    @Test
    fun install_conflict_userSkill_canReplace() {
        val backend = FakeInstall(SkillInstallOutcome.Conflict(listOf(SkillConflictView("alpha", "alpha", "user", replaceAllowed = true))))
        val p = pipeline(installBackend = backend, interaction = approve)
        p.inspect()
        val json = JSONObject(p.install().content)
        assertTrue(json.getString("message").contains("可以替换"))
        assertTrue(json.getString("hint").contains("replace=true 重试"))
    }

    @Test
    fun catalogItems_disabledSkillCountsAsInstalled() {
        fun entry(id: String, name: String, enabled: Boolean, installed: Boolean = true) =
            io.github.fartown.movo.agent.skill.SkillIndexEntry(
                id = id, name = name, description = "", rootPath = "/s/$id", skillFilePath = "/s/$id/SKILL.md",
                hasScripts = false, hasReferences = false, hasAssets = false, hasEvals = false,
                enabled = enabled, installed = installed,
            )
        val items = skillCatalogItems(
            listOf("alpha" to "skills/alpha", "Beta" to "skills/beta", "gamma" to "skills/gamma", "delta" to "skills/delta"),
            listOf(
                entry("alpha", "alpha", enabled = false),
                entry("beta", "Beta", enabled = true),
                entry("delta", "delta", enabled = true, installed = false),
            ),
        )
        assertEquals(listOf(true, true, false, false), items.map { it.installed })
        assertEquals(false, items[0].enabled)
        assertEquals(true, items[1].enabled)
    }

    @Test
    fun inspect_rendersDisabledFlag() {
        val backend = object : SkillInstallBackend {
            override fun curated() = SkillDiscoverResult.Failed(io.github.fartown.movo.agent.tools.core.ToolErrorCode.NOT_FOUND, "")
            override fun inspect(repository: String, ref: String?, path: String?) = SkillDiscoverResult.Items(
                "o/r", "main", "sha1",
                listOf(SkillCatalogItem("alpha", "skills/alpha", installed = true, enabled = false)),
            )
            override fun install(repository: String, ref: String?, paths: List<String>, replace: Boolean, cancelled: () -> Boolean) =
                SkillInstallOutcome.Installed(emptyList())
        }
        val item = JSONObject(pipeline(installBackend = backend).inspect().content)
            .getJSONObject("data").getJSONArray("items").getJSONObject(0)
        assertTrue(item.getBoolean("installed"))
        assertFalse(item.getBoolean("enabled"))
    }

    @Test
    fun install_commitUncertain_unknown() {
        val p = pipeline(installBackend = FakeInstall(SkillInstallOutcome.CommitUncertain), interaction = approve)
        p.inspect()
        val r = p.install()
        assertEquals("unknown", r.status)
        assertEquals("OUTCOME_UNKNOWN", r.errorCode)
    }

    // ---- 先检查再安装（审计 D11，恢复旧 skills_install_from_github 的约束）----

    @Test
    fun install_withoutInspectionThisRun_rejectedBeforeAsking() {
        val backend = FakeInstall(SkillInstallOutcome.Installed(listOf(InstalledSkillView("alpha", "alpha"))))
        var asked = false
        val asking = object : UserInteraction {
            override val available = true
            override fun ask(question: UserQuestion, timeoutMs: Long) = UserAnswer.Declined
            override fun approve(request: ApprovalRequest, timeoutMs: Long): ApprovalDecision {
                asked = true
                return ApprovalDecision.Approved
            }
        }
        val manual = ToolEnvironment(approvalPolicy = ApprovalPolicy(mode = PermissionMode.MANUAL, categories = setOf(ApprovalCategory.INSTALL)))
        val r = pipeline(installBackend = backend, interaction = asking, env = manual).install()
        assertEquals("INVALID_ARGUMENTS", r.errorCode)
        assertTrue(r.content, r.content.contains("inspect"))
        assertFalse("没检查过的安装不该先弹确认卡", asked)
        assertEquals(0, backend.installs)
    }

    @Test
    fun install_pinsTheCommitSeenAtInspection() {
        val backend = FakeInstall(SkillInstallOutcome.Installed(listOf(InstalledSkillView("alpha", "alpha"))))
        val p = pipeline(installBackend = backend, interaction = approve)
        p.inspect()
        // 检查之后仓库被推了新提交：装的仍是检查时看到的那个版本。
        backend.sha = "sha2"
        assertEquals("ok", p.install().status)
        assertEquals("sha1", backend.lastRef)
        assertEquals("o/r", backend.lastRepository)
        assertEquals(listOf("skills/alpha"), backend.lastPaths)

        // 带检查返回的 commit_sha 或分支名也对得上同一次检查。
        assertEquals("ok", p.install("""{"action":"install","repository":"o/r","ref":"sha1","paths":["skills/alpha"]}""").status)
        assertEquals("ok", p.install("""{"action":"install","repository":"O/R","ref":"main","paths":["skills/alpha"]}""").status)
        assertEquals("sha1", backend.lastRef)
    }

    @Test
    fun install_otherRepositoryOrRef_needsItsOwnInspection() {
        val backend = FakeInstall(SkillInstallOutcome.Installed(emptyList()))
        val p = pipeline(installBackend = backend, interaction = approve)
        p.inspect()
        assertEquals("INVALID_ARGUMENTS", p.install("""{"action":"install","repository":"o/other","paths":["skills/alpha"]}""").errorCode)
        assertEquals("INVALID_ARGUMENTS", p.install("""{"action":"install","repository":"o/r","ref":"dev","paths":["skills/alpha"]}""").errorCode)
        assertEquals(0, backend.installs)
    }

    @Test
    fun install_withoutRef_usesTheOnlyInspectedVersionOfThatRepository() {
        val backend = FakeInstall(SkillInstallOutcome.Installed(emptyList()))
        val p = pipeline(installBackend = backend, interaction = approve)
        p.inspect("""{"action":"inspect","repository":"o/r","ref":"release"}""")
        assertEquals("ok", p.install().status)
        assertEquals("sha1", backend.lastRef)

        // 同一仓库检查过两个版本：不带 ref 分不清装哪个，要带 commit_sha。
        backend.sha = "sha2"
        p.inspect("""{"action":"inspect","repository":"o/r","ref":"dev"}""")
        assertEquals("INVALID_ARGUMENTS", p.install().errorCode)
        assertEquals("ok", p.install("""{"action":"install","repository":"o/r","ref":"sha2","paths":["skills/alpha"]}""").status)
        assertEquals("sha2", backend.lastRef)
    }

    @Test
    fun install_onlyPathsReturnedByTheInspection() {
        val backend = FakeInstall(SkillInstallOutcome.Installed(emptyList()))
        val p = pipeline(installBackend = backend, interaction = approve)
        p.inspect()
        val r = p.install("""{"action":"install","repository":"o/r","paths":["skills/alpha","skills/gamma"]}""")
        assertEquals("INVALID_ARGUMENTS", r.errorCode)
        assertTrue(r.content, r.content.contains("gamma"))
        assertEquals(0, backend.installs)
    }

    @Test
    fun install_staysInsideTheInspectedDirectory() {
        val backend = FakeInstall(SkillInstallOutcome.Installed(emptyList()))
        val p = pipeline(installBackend = backend, interaction = approve)
        // 检查限定在 skills/ 下：即使候选里混进了别处的目录，也不能装到范围外去。
        p.inspect("""{"action":"inspect","repository":"o/r","path":"skills"}""")
        assertEquals("INVALID_ARGUMENTS", p.install("""{"action":"install","repository":"o/r","paths":["tools/beta"]}""").errorCode)
        assertEquals("ok", p.install().status)
        assertEquals(1, backend.installs)
    }

    @Test
    fun install_fromATreeUrl_matchesThatInspectionAndPassesTheSlug() {
        val backend = FakeInstall(SkillInstallOutcome.Installed(emptyList()))
        val p = pipeline(installBackend = backend, interaction = approve)
        p.inspect("""{"action":"inspect","repository":"https://github.com/o/r/tree/main/skills"}""")
        val r = p.install("""{"action":"install","repository":"https://github.com/o/r/tree/main/skills","paths":["skills/alpha"]}""")
        assertEquals("ok", r.status)
        // URL 里的分支由检查时的 commit 代替，仓库按 owner/repo 交给后端（否则 URL 的 ref 与 commit 对不上会报错）。
        assertEquals("o/r", backend.lastRepository)
        assertEquals("sha1", backend.lastRef)
    }

    @Test
    fun install_afterCurated_usesTheCuratedCommit() {
        val backend = FakeInstall(SkillInstallOutcome.Installed(emptyList()))
        val p = pipeline(installBackend = backend, interaction = approve)
        p.execute(call("skill_install", """{"action":"curated"}"""))
        val r = p.install("""{"action":"install","repository":"openai/skills","paths":["skills/.curated/alpha"]}""")
        assertEquals("ok", r.status)
        assertEquals("openai/skills", backend.lastRepository)
        assertEquals("sha1", backend.lastRef)
    }

    @Test
    fun install_runCancelledDuringDownload_reachesTheBackend() {
        var cancelled = false
        val backend = FakeInstall(SkillInstallOutcome.Installed(emptyList())).apply { duringInstall = { cancelled = true } }
        val p = pipeline(installBackend = backend, interaction = approve, cancelled = { cancelled })
        p.inspect()
        p.install()
        assertEquals("后端在提交前要能看到取消", true, backend.cancelledDuringInstall)
    }

    @Test
    fun installerSkill_teachesTheCurrentToolNames() {
        // 内置 skill-installer 说明里不能再出现已经不存在的旧工具名。
        val workingDirectory = java.io.File(requireNotNull(System.getProperty("user.dir")))
        val skillFile = listOf(
            java.io.File(workingDirectory, "app/src/main/assets/builtin_skills/skill-installer"),
            java.io.File(workingDirectory, "src/main/assets/builtin_skills/skill-installer"),
        ).first { it.isDirectory }
        val text = skillFile.walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
        for (old in listOf("skills_list_curated", "skills_inspect_github", "skills_install_from_github", "replaceExisting", "expectedReplacementId", "SKILL_CONFLICT")) {
            assertFalse("skill-installer 还提到旧的 $old", text.contains(old))
        }
        for (now in listOf("skill_install", "action=curated", "action=inspect", "action=install", "commit_sha", "replace=true")) {
            assertTrue("skill-installer 应说明 $now", text.contains(now))
        }
    }
}
