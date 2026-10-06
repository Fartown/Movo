package io.github.fartown.movo.ui.screens.diagnostics

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.fartown.movo.diagnostics.DiagnosticContext
import io.github.fartown.movo.diagnostics.DiagnosticEntry
import io.github.fartown.movo.diagnostics.DiagnosticLevel
import io.github.fartown.movo.diagnostics.DiagnosticTraceBuilder
import io.github.fartown.movo.diagnostics.RunTrace
import io.github.fartown.movo.diagnostics.runlog.RunLog
import io.github.fartown.movo.diagnostics.runlog.RunLogRecord
import io.github.fartown.movo.diagnostics.runlog.RunLogStore
import io.github.fartown.movo.ui.theme.MovoIcons
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "zh-rCN-w360dp-h800dp-xxhdpi")
class RunLogExportUiTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private var store: RunLogStore? = null

    @After
    fun tearDown() {
        RunLog.install(null, "", { false })
        store?.shutdown()
    }

    private fun entry(seq: Long, run: String, event: String, details: String = "") = DiagnosticEntry(
        sequence = seq, timeMillis = 1_000L + seq, elapsedMillis = seq, level = DiagnosticLevel.INFO,
        category = "runtime", event = event, context = DiagnosticContext(run = run), details = details,
    )

    private fun run(id: String, dir: String?, ended: Boolean): RunTrace {
        val entries = buildList {
            add(entry(1, id, "run.started", dir?.let { "run_log=$it" }.orEmpty()))
            if (ended) {
                add(entry(2, id, "run.completed"))
                add(entry(3, id, "run.ended", "duration_ms=2"))
            }
        }
        return DiagnosticTraceBuilder.build(entries).runs.single { it.id == id }
    }

    @Test
    fun sizesReadLikeTheDesign() {
        assertEquals("0 KB", formatLogSize(0))
        assertEquals("2 KB", formatLogSize(1_500))
        assertEquals("18.6 MB", formatLogSize(19_500_000))
    }

    @Test
    fun onlyEndedRunsWithAFullLogCanBeExported() {
        val store = RunLogStore(File(temp.root, "run-log"), elapsedClock = { 0L }).also { store = it }
        RunLog.install(store, "test") { true }
        val session = store.open("R5")!!
        store.close(session, RunLogRecord("run_end", 0, 0, mapOf("status" to "completed"), control = true))
        assertTrue(store.awaitIdle())

        val ended = fullLogOf(run("R5", session.dirName, ended = true))
        assertTrue(ended.exportable)
        assertNull(ended.refusal)
        assertEquals("任务结束后才能导出", fullLogOf(run("R6", "R6-20261006-100000", ended = false)).refusal)
        assertEquals("这次任务没有完整日志", fullLogOf(run("R7", null, ended = true)).refusal)
    }

    @Test
    fun withoutAStoreThereIsNoFullLog() {
        assertEquals("这次任务没有完整日志", fullLogOf(run("R8", "R8-20261006-100000", ended = true)).refusal)
    }

    @Test
    fun aDisabledItemShowsItsReasonAndCannotBeClicked() {
        var exported = false
        var copied = false
        compose.setContent {
            ExportMenu(
                state = MutableTransitionState(true),
                onDismiss = {},
                items = listOf(
                    ExportMenuItem("导出完整日志", MovoIcons.Download, caption = "任务结束后才能导出", enabled = false) { exported = true },
                    ExportMenuItem("复制到剪贴板", MovoIcons.Copy) { copied = true },
                ),
            )
        }
        compose.onNodeWithText("任务结束后才能导出").assertIsDisplayed()
        compose.onNodeWithText("导出完整日志").performClick()
        compose.onNodeWithText("复制到剪贴板").performClick()
        assertFalse(exported)
        assertTrue(copied)
    }
}
