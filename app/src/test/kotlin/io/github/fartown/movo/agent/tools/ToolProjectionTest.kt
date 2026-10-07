package io.github.fartown.movo.agent.tools

import io.github.fartown.movo.agent.tools.core.ToolOutcome
import io.github.fartown.movo.agent.tools.core.ToolProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D7：终端正文给模型时有上限（16000 字），超了每段留开头和结尾，并说明省略了多少、怎么看到省略的部分。 */
class ToolProjectionTest {
    private fun terminalBody(stdout: String, stderr: String) =
        "exit_code: 0\nelapsed_ms: 12\n--- stdout ---\n$stdout\n--- stderr ---\n$stderr"

    @Test
    fun shortBody_isUntouched() {
        val body = terminalBody("hello", "")
        val text = ToolProjection.render(ToolOutcome.ok(textBody = body))
        assertTrue(text.endsWith(body))
        assertFalse(text.contains("truncated:"))
    }

    @Test
    fun longStdout_keepsHeadTailAndTheWholeShortStderr() {
        val stdout = (1..30_000).joinToString("\n") { "out$it" }
        val body = terminalBody(stdout, "fatal: boom")
        val text = ToolProjection.render(ToolOutcome.ok(textBody = body))

        assertTrue("给模型的正文不超过上限太多（${text.length}）", text.length < ToolProjection.MAX_TEXT_BODY_CHARS + 600)
        assertTrue(text.contains("exit_code: 0"))
        assertTrue("开头保留", text.contains("--- stdout ---\nout1\nout2\n"))
        assertTrue("结尾保留", text.contains("out30000\n--- stderr ---\nfatal: boom"))
        assertTrue("中间注明省略量", text.contains("省略"))
        assertTrue(text.contains("truncated: 正文共 ${body.length} 字"))
        assertTrue("说明怎么看省略的部分", text.contains("terminal_job read") && text.contains("file_read"))
    }

    @Test
    fun bothStreamsLong_splitTheBudget() {
        val clipped = ToolProjection.clipTextBody(terminalBody("a".repeat(40_000), "b".repeat(40_000)))
        val stdoutPart = clipped.text.substringAfter("--- stdout ---\n").substringBefore("--- stderr ---")
        val stderrPart = clipped.text.substringAfter("--- stderr ---\n")
        assertTrue(stdoutPart.count { it == 'a' } in 7_000..8_000)
        assertTrue(stderrPart.count { it == 'b' } in 7_000..8_000)
        assertEquals(80_000 - stdoutPart.count { it == 'a' } - stderrPart.count { it == 'b' }, clipped.omitted)
    }

    @Test
    fun nonTerminalBody_isClippedAsAWhole() {
        val clipped = ToolProjection.clipTextBody("x".repeat(20_000) + "END")
        assertTrue(clipped.text.endsWith("END"))
        assertTrue(clipped.omitted > 0)
        assertTrue(clipped.text.length < ToolProjection.MAX_TEXT_BODY_CHARS + 100)
    }
}
