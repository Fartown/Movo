package io.github.fartown.movo.agent.tools

import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.device.BoundedRootCommandExecutor
import io.github.fartown.movo.agent.tools.browser.BrowserToolProvider
import io.github.fartown.movo.agent.tools.core.MemoryScope
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolRegistry
import io.github.fartown.movo.agent.tools.core.ToolSwitches
import io.github.fartown.movo.agent.tools.file.FileToolProvider
import io.github.fartown.movo.agent.tools.memory.MemoryToolProvider
import io.github.fartown.movo.agent.tools.terminal.TerminalToolProvider
import io.github.fartown.movo.core.AndroidAgentLogger
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 终端、文件、浏览器、记忆的用法分节：对应工具这次可用就发（重构差异 T1-2）。
 * 以前这些写在 AgentPromptBuilder 的 toolGuide == null 分支里，线上永远发不出去。
 */
@RunWith(RobolectricTestRunner::class)
class ToolGuideSectionsTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val root = BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = { false })
    private val registry = ToolRegistry(
        listOf(
            FileToolProvider(context, root, rootAvailable = { false }),
            TerminalToolProvider(AndroidAgentLogger),
            BrowserToolProvider(context, "run1"),
            MemoryToolProvider(context),
        ),
    )

    @After
    fun close() {
        registry.close()
        root.close()
    }

    private fun sections(env: ToolEnvironment): Map<String, String> =
        registry.promptSections(env).associate { it.id to it.text }

    @Test
    fun terminalAvailable_linuxSharedFoldersApkAndBuiltInTerminalAreExplained() {
        val terminal = sections(ToolEnvironment()).getValue("terminal")
        assertTrue(terminal.contains("linux_not_ready"))
        assertTrue(terminal.contains("设置 → 工具 → Linux 工具环境"))
        assertTrue(terminal.contains("安装基础工具"))
        assertTrue(terminal.contains("/workspace/mounts/"))
        assertTrue(terminal.contains("jadx") && terminal.contains("APK 分析") && terminal.contains("不能回编译"))
        assertTrue(terminal.contains("Termux") && terminal.contains("Movo 自带终端"))
        assertTrue(terminal.contains("mode=keep_alive"))
        assertTrue("没有 Root 只能用 user 身份", terminal.contains("只能用 identity=user"))
        assertFalse(terminal.contains("identity=root"))
    }

    @Test
    fun withRoot_terminalSaysRootIdentityIsAvailable() {
        val terminal = sections(ToolEnvironment(rootAvailable = true)).getValue("terminal")
        assertTrue(terminal.contains("identity=root"))
        assertFalse(terminal.contains("只能用 identity=user"))
    }

    @Test
    fun fileReadAvailable_oneImagePerTurnRuleIsSent() {
        val file = sections(ToolEnvironment()).getValue("file")
        assertTrue(file.contains("同一轮回复最多读一张"))
        assertTrue(file.contains("下一轮再读下一张"))
    }

    @Test
    fun browserAvailable_readModesAndAppOpenBoundaryAreSent() {
        val browser = sections(ToolEnvironment()).getValue("browser")
        assertTrue(browser.contains("mode=readable") && browser.contains("mode=elements"))
        assertTrue(browser.contains("app_open 不用来读网页"))
    }

    @Test
    fun switchesOff_sectionsFollowTheirTools() {
        val noTerminal = sections(ToolEnvironment(switches = ToolSwitches(terminal = false)))
        assertNull("终端开关关着就不发终端用法", noTerminal["terminal"])
        assertTrue("file_read 还能读句柄和附件，图片规则照发", noTerminal.getValue("file").contains("最多读一张"))
        assertNull(sections(ToolEnvironment(switches = ToolSwitches(browser = false)))["browser"])
        assertNull(sections(ToolEnvironment(switches = ToolSwitches(terminal = false, sensitiveRead = false)))["file"])
    }

    @Test
    fun memorySection_inRoleplayTalksAboutStoryMemoryNotLongTermMemory() {
        val real = sections(ToolEnvironment(memoryScope = MemoryScope.REAL)).getValue("memory")
        assertTrue(real.contains("长期记忆"))

        val character = sections(ToolEnvironment(memoryScope = MemoryScope.CHARACTER)).getValue("memory")
        assertTrue(character.contains("剧情记忆"))
        assertTrue("剧情不进现实记忆", character.contains("剧情不能写进用户的现实记忆"))
        assertTrue(character.contains("不要把剧情里的动作当成已经完成的设备操作"))
        assertFalse(character.contains("长期记忆"))
        assertNull("记忆关着不发", sections(ToolEnvironment(memoryScope = MemoryScope.DISABLED))["memory"])
    }
}
