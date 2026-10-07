package io.github.fartown.movo.agent.tools.terminal

import io.github.fartown.movo.agent.terminal.TerminalRuntime
import org.junit.Assert.assertEquals
import org.junit.Test

/** terminal_run 的工作目录：没传 cwd 时在工作区，`~` 和相对路径也按工作区算（与 file_* 一致）。 */
class TerminalWorkdirTest {
    @Test
    fun workspace_followsTheEnvironmentAndIdentity() {
        // 普通身份就是 file_* 的工作区（也是 HOME）；Root 是 Root 工作区；Linux 是 /workspace。
        assertEquals(TerminalRuntime.userWorkspacePath, terminalWorkspace(TerminalEnv.ANDROID, TerminalIdentity.USER))
        assertEquals("/data/local/tmp/movo", terminalWorkspace(TerminalEnv.ANDROID, TerminalIdentity.ROOT))
        assertEquals("/workspace", terminalWorkspace(TerminalEnv.LINUX, TerminalIdentity.USER))
        assertEquals("/workspace", terminalWorkspace(TerminalEnv.LINUX, TerminalIdentity.ROOT))
    }

    @Test
    fun androidCwd_defaultsToAndIsRelativeToTheWorkspace() {
        val ws = "/data/user/0/app/files/terminal-user/workspace"
        assertEquals(ws, resolveTerminalCwd(null, TerminalEnv.ANDROID, ws))
        assertEquals(ws, resolveTerminalCwd("  ", TerminalEnv.ANDROID, ws))
        assertEquals(ws, resolveTerminalCwd("~", TerminalEnv.ANDROID, ws))
        assertEquals("$ws/notes", resolveTerminalCwd("~/notes", TerminalEnv.ANDROID, ws))
        assertEquals("$ws/a/b", resolveTerminalCwd("a/b", TerminalEnv.ANDROID, ws))
        assertEquals("/sdcard/Download", resolveTerminalCwd("/sdcard/Download", TerminalEnv.ANDROID, ws))
    }

    @Test
    fun linuxCwd_defaultsToWorkspace_tildeIsRoot() {
        assertEquals("/workspace", resolveTerminalCwd(null, TerminalEnv.LINUX, "/workspace"))
        assertEquals("/root", resolveTerminalCwd("~", TerminalEnv.LINUX, "/workspace"))
        assertEquals("/root/src", resolveTerminalCwd("~/src", TerminalEnv.LINUX, "/workspace"))
        assertEquals("/workspace/proj", resolveTerminalCwd("proj", TerminalEnv.LINUX, "/workspace"))
        assertEquals("/tmp", resolveTerminalCwd("/tmp", TerminalEnv.LINUX, "/workspace"))
    }

    @Test
    fun command_entersTheDirectoryFirst_andCreatesTheAndroidWorkspace() {
        assertEquals("mkdir -p '/ws' && cd '/ws' && ls -la", commandInDirectory("ls -la", "/ws", TerminalEnv.ANDROID, "/ws"))
        assertEquals("cd '/sdcard' && ls", commandInDirectory("ls", "/sdcard", TerminalEnv.ANDROID, "/ws"))
        assertEquals("cd '/workspace' && ls", commandInDirectory("ls", "/workspace", TerminalEnv.LINUX, "/workspace"))
        assertEquals("cd '/ws/it'\\''s' && ls", commandInDirectory("ls", "/ws/it's", TerminalEnv.ANDROID, "/ws"))
    }
}
