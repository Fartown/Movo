package io.github.fartown.movo.agent.tools.skill

import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.model.AgentModelClient
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

/** skill_read / skill_install 的管线验证：只读发现、安装回读 Done、冲突当场确认、外发确认、提交不确定 Unknown。 */
@RunWith(RobolectricTestRunner::class)
class SkillToolsTest {

    private class FakeRead(private val result: SkillReadResult) : SkillReadBackend {
        override fun read(skill: String, path: String?): SkillReadResult = result
    }

    private class FakeInstall(private val outcome: SkillInstallOutcome) : SkillInstallBackend {
        var lastReplace: Boolean? = null
        override fun curated() = SkillDiscoverResult.Items(
            "o/r", "main", "sha1", listOf(SkillCatalogItem("alpha", "skills/alpha", installed = false)),
        )
        override fun inspect(repository: String, ref: String?, path: String?) = curated()
        override fun install(repository: String, ref: String?, paths: List<String>, replace: Boolean): SkillInstallOutcome {
            lastReplace = replace
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
        override fun approve(request: ApprovalRequest, timeoutMs: Long) = ApprovalDecision.Approved(false)
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
    ): ToolPipeline {
        val provider = object : ToolProvider {
            override val tools = listOf(
                ContractTool(SkillReadTool(readBackend)),
                ContractTool(SkillInstallTool(installBackend)),
            )
        }
        return ToolPipeline(
            registry = ToolRegistry(listOf(provider)),
            environment = { ToolEnvironment() },
            appContext = ApplicationProvider.getApplicationContext(),
            logger = AndroidAgentLogger,
            runId = "run1",
            cancelled = { false },
            interaction = interaction,
        ).also { it.catalog() }
    }

    private fun call(name: String, args: String) = AgentModelClient.ToolCall("c1", name, args)

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
        val r = p.execute(call("skill_install", """{"action":"install","repository":"o/r","paths":["skills/alpha"]}"""))
        val json = JSONObject(r.content)
        assertEquals("ok", json.getString("status"))
        assertTrue(json.getBoolean("effect_verified"))
        assertEquals("next_task", json.getJSONObject("data").getString("available"))
    }

    @Test
    fun install_declined_userDeclined() {
        val p = pipeline(interaction = decline)
        val r = p.execute(call("skill_install", """{"action":"install","repository":"o/r","paths":["skills/alpha"]}"""))
        assertEquals("USER_DECLINED", r.errorCode)
    }

    @Test
    fun install_conflict_confirmThenInstalls() {
        val backend = FakeInstall(
            SkillInstallOutcome.Conflict(listOf(SkillConflictView("alpha", "alpha", "user"))),
        )
        val p = pipeline(installBackend = backend, interaction = approve)
        val r = p.execute(call("skill_install", """{"action":"install","repository":"o/r","paths":["skills/alpha"]}"""))
        assertEquals("ok", JSONObject(r.content).getString("status"))
        assertEquals(true, backend.lastReplace) // 确认后以 replace 重装
    }

    @Test
    fun install_commitUncertain_unknown() {
        val p = pipeline(installBackend = FakeInstall(SkillInstallOutcome.CommitUncertain), interaction = approve)
        val r = p.execute(call("skill_install", """{"action":"install","repository":"o/r","paths":["skills/alpha"]}"""))
        assertEquals("unknown", r.status)
        assertEquals("OUTCOME_UNKNOWN", r.errorCode)
    }
}
