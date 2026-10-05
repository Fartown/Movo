package io.github.fartown.movo.agent.tools.terminal

import io.github.fartown.movo.core.AndroidAgentLogger
import org.junit.Assert.assertTrue
import org.junit.Test

/** 运行被取消时，前台等待中的命令要立刻结束（真机：sleep 20 时点停止，要等 21 秒才生效）。 */
class TerminalJobRegistryCancelTest {
    @Test
    fun cancelledRunStopsTheCommandWithoutWaitingForIt() {
        val registry = TerminalJobRegistry(AndroidAgentLogger)
        val startedAt = System.currentTimeMillis()
        var cancelAt = Long.MAX_VALUE
        val spec = TerminalRunSpec(
            command = "sleep 20",
            description = null,
            environment = TerminalEnv.ANDROID,
            identity = TerminalIdentity.USER,
            cwd = null,
            waitMs = 30_000,
            tty = false,
            mode = TerminalMode.WAIT,
            cancelled = {
                if (cancelAt == Long.MAX_VALUE) cancelAt = System.currentTimeMillis() + 300
                System.currentTimeMillis() >= cancelAt
            },
        )
        registry.run(spec)
        val elapsed = System.currentTimeMillis() - startedAt
        registry.close()
        assertTrue("取消后应在几秒内返回，实际 ${elapsed}ms", elapsed < 5_000)
    }
}
