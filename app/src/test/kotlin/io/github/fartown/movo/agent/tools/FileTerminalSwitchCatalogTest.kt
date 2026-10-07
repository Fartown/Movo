package io.github.fartown.movo.agent.tools

import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.device.BoundedRootCommandExecutor
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolRegistry
import io.github.fartown.movo.agent.tools.core.ToolSwitches
import io.github.fartown.movo.agent.tools.file.FileToolProvider
import io.github.fartown.movo.agent.tools.terminal.TerminalToolProvider
import io.github.fartown.movo.core.AndroidAgentLogger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 「终端与文件」开关关着时，终端和文件工具不进目录（审计 D17：以前要调用了才报 DISABLED）。
 * 用真实的 FileToolProvider、TerminalToolProvider 和登记表，看模型实际收到的目录。
 * file_read 按来源管（定义清单 §0.5）：句柄与附件看「读取敏感信息」，两个开关都关才不进目录。
 */
@RunWith(RobolectricTestRunner::class)
class FileTerminalSwitchCatalogTest {
    private val root = BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = { false })
    private val registry = ToolRegistry(
        listOf(
            FileToolProvider(ApplicationProvider.getApplicationContext(), root, rootAvailable = { false }),
            TerminalToolProvider(AndroidAgentLogger),
        ),
    )

    @After
    fun close() {
        registry.close()
        root.close()
    }

    private fun catalog(switches: ToolSwitches): Set<String> {
        val array = registry.catalog(ToolEnvironment(switches = switches), loadedDeferred = emptySet())
        return (0 until array.length()).map { array.getJSONObject(it).getJSONObject("function").getString("name") }.toSet()
    }

    @Test
    fun allOn_everyFileAndTerminalToolIsListed() {
        assertEquals(
            setOf("file_search", "file_read", "file_write", "file_list", "terminal_run", "terminal_job"),
            catalog(ToolSwitches()),
        )
    }

    @Test
    fun terminalOff_terminalAndPathToolsLeaveTheCatalog() {
        // 只剩读取敏感信息管的 file_search，以及还能读它的句柄和用户附件的 file_read。
        assertEquals(setOf("file_search", "file_read"), catalog(ToolSwitches(terminal = false)))
    }

    @Test
    fun terminalAndSensitiveReadOff_noFileOrTerminalToolIsListed() {
        assertEquals(emptySet<String>(), catalog(ToolSwitches(terminal = false, sensitiveRead = false)))
    }

    @Test
    fun sensitiveReadOff_fileReadStaysForPaths() {
        assertEquals(
            setOf("file_read", "file_write", "file_list", "terminal_run", "terminal_job"),
            catalog(ToolSwitches(sensitiveRead = false)),
        )
    }
}
