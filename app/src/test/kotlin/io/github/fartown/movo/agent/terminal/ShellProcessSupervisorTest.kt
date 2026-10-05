package io.github.fartown.movo.agent.terminal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellProcessSupervisorTest {
    @Test
    fun missingSetsidFailsClosedWhenTreeFallbackIsDisabled() {
        val supervisor = ShellProcessSupervisor(
            allowTreeFallback = false,
            setsidCommand = "movo-test-missing-setsid",
        )

        val process = supervisor.startShellProcess(
            identity = "user",
            command = "echo should-not-run",
            mergeStderr = false,
        )

        assertNull(process)
    }

    /**
     * 改名（Eta → Movo）时 `\neta_status` 没被替换，命令的退出码被随后的 wait 覆盖：失败的命令一律报退出码 0。
     */
    @Test
    fun failingCommandKeepsItsExitCode() {
        val supervisor = ShellProcessSupervisor()
        val process = requireNotNull(
            // 子 Shell 失败、外层继续往下走（`exit 3` 会直接结束外层，测不出包装脚本的问题）。
            supervisor.startShellProcess(identity = "user", command = "sh -c 'exit 3'", mergeStderr = true),
        )
        try {
            assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(3, process.exitValue())
        } finally {
            supervisor.terminateAndReap(process)
            supervisor.unregisterProcess(process)
        }
    }

    @Test
    fun rootAndroidPayloadUsesDiscoveredBusyBoxWithoutChangingUserShell() {
        val supervisor = ShellProcessSupervisor()

        val rootPayload = supervisor.buildAndroidPayload("root", "command -v xz")
        val userPayload = supervisor.buildAndroidPayload("user", "id")

        assertTrue(rootPayload.contains("/data/adb/magisk/busybox"))
        assertTrue(rootPayload.contains("ASH_STANDALONE=1"))
        assertEquals("sh -c 'id'", userPayload)
    }

    @Test
    fun linuxPayloadKeepsShellQuotesAndMountsPrivateExchangeDirectory() {
        val supervisor = ShellProcessSupervisor()

        val payload = supervisor.buildLinuxPayload(
            rootfsPath = "/data/user/0/io.github.fartown.movo/files/terminal/alpine/rootfs",
            command = "printf '%s' \"hello\"",
        )

        assertFalse(payload.contains("\\\""))
        assertTrue(payload.contains("unshare -m --propagation private"))
        assertTrue(payload.contains("mount -t proc"))
        assertTrue(payload.contains("movo_mount_required /data/local/tmp"))
        assertTrue(payload.contains("movo_mount_required /data/local/tmp/movo"))
        assertTrue(payload.contains("movo_rootfs/workspace"))
        assertTrue(payload.contains("chroot"))
        assertTrue(payload.contains(AlpineEnvironmentPaths.READY_MARKER))
        assertTrue(payload.contains("/bin/busybox env -i"))
        // Alpine 的 /bin/sh 是绝对符号链接，Android 侧就绪检查必须放行符号链接。
        assertTrue(payload.contains("[ -h \"\$movo_rootfs/bin/sh\" ]"))

        val debianPayload = supervisor.buildLinuxPayload(
            rootfsPath = "/data/user/0/io.github.fartown.movo/files/terminal/debian/rootfs",
            command = "python3 --version",
        )
        assertTrue(debianPayload.contains("/usr/bin/env -i"))
    }

    @Test
    fun ptyLauncherWrapsPayloadWithScriptAndSetsSize() {
        val supervisor = ShellProcessSupervisor()
        val launcher = supervisor.buildTrackedShellLauncher(
            ownershipFile = File(System.getProperty("java.io.tmpdir"), "movo-pty-test.owner"),
            ownershipToken = "token123",
            command = null,
            identity = "user",
            environment = TerminalEnvironment.ANDROID,
            linuxRootfsPath = null,
            pty = true,
            ptyCols = 120,
            ptyRows = 40,
        )

        assertTrue(launcher.contains("script -qfc"))
        assertTrue(launcher.contains("stty rows 40 cols 120"))
        assertTrue(launcher.contains("TERM=xterm-256color"))
        assertTrue(launcher.contains("/dev/null"))
    }
}
