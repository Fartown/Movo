package io.github.fartown.movo.agent.runtime

import io.github.fartown.movo.BuildConfig
import io.github.fartown.movo.agent.accessibility.PackageWindowVisibility
import io.github.fartown.movo.core.AgentLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class EntrySurfaceGuardTest {
    @Test
    fun disabledHandoffDoesNotCreateGuard() {
        val guard = EntrySurfaceGuard.from(
            handoff = handoff(source = "breeno", dismiss = false),
            logger = NoOpLogger,
        )

        assertNull(guard)
    }

    @Test
    fun breenoGuardKeepsExclusionUntilTheFirstScreenshotConsumesIt() {
        val guard = EntrySurfaceGuard.from(
            handoff = handoff(source = "breeno", dismiss = true),
            logger = NoOpLogger,
        )

        assertNotNull(guard)
        assertEquals("com.heytap.speechassist", guard?.targetPackageName)
        assertEquals(setOf("com.heytap.speechassist"), guard?.consumeScreenshotExcludedPackages())
        assertTrue(guard?.consumeScreenshotExcludedPackages().orEmpty().isEmpty())
    }

    @Test
    fun xiaoAiGuardUsesOnlyTheXiaoAiPackage() {
        val guard = EntrySurfaceGuard.from(
            handoff = handoff(source = "xiaoai", dismiss = true),
            logger = NoOpLogger,
        )

        assertNotNull(guard)
        assertEquals("com.miui.voiceassist", guard?.targetPackageName)
        assertEquals(setOf("com.miui.voiceassist"), guard?.consumeScreenshotExcludedPackages())
    }

    @Test
    fun movoVoiceGuardExcludesMovoUntilTheVoiceWindowIsDismissed() {
        val dismissCalls = AtomicInteger()
        val guard = EntrySurfaceGuard.from(
            handoff = handoff(
                source = AgentRuntimeWire.MOVO_VOICE_HANDOFF_SOURCE,
                dismiss = true,
            ),
            logger = NoOpLogger,
            movoVoiceSurfaceDismissal = {
                dismissCalls.incrementAndGet()
                true
            },
        )

        assertNotNull(guard)
        assertEquals(BuildConfig.APPLICATION_ID, guard?.targetPackageName)
        assertTrue(guard?.dismissOnce() == true)
        assertTrue(guard?.dismissOnce() == true)
        assertEquals(1, dismissCalls.get())
        assertEquals(setOf(BuildConfig.APPLICATION_ID), guard?.consumeScreenshotExcludedPackages())
    }

    @Test
    fun movoVoiceOwnedDismissalCanRetryWithoutSendingBackToTheUnderlyingApp() {
        val dismissCalls = AtomicInteger()
        val guard = EntrySurfaceGuard.from(
            handoff = handoff(
                source = AgentRuntimeWire.MOVO_VOICE_HANDOFF_SOURCE,
                dismiss = true,
            ),
            logger = NoOpLogger,
            movoVoiceSurfaceDismissal = {
                dismissCalls.incrementAndGet() >= 2
            },
        )

        assertFalse(guard?.dismissOnce() == true)
        assertTrue(guard?.dismissOnce() == true)
        assertEquals(2, dismissCalls.get())
    }

    @Test
    fun unknownEntryStillCreatesDismissGuardWithoutGuessingAPackage() {
        val guard = EntrySurfaceGuard.from(
            handoff = handoff(source = "future_entry", dismiss = true),
            logger = NoOpLogger,
        )

        assertNotNull(guard)
        assertNull(guard?.targetPackageName)
        assertTrue(guard?.consumeScreenshotExcludedPackages().orEmpty().isEmpty())
    }

    @Test
    fun knownEntryAlreadyGoneMustNotSendBackIntoUnderlyingApp() {
        assertEquals(
            EntrySurfaceDismissPolicy.Decision.ALREADY_GONE,
            EntrySurfaceDismissPolicy.decide(
                targetPackageName = "com.heytap.speechassist",
                visibility = PackageWindowVisibility.GONE,
            ),
        )
        assertEquals(
            EntrySurfaceDismissPolicy.Decision.SEND_BACK,
            EntrySurfaceDismissPolicy.decide(
                targetPackageName = "com.heytap.speechassist",
                visibility = PackageWindowVisibility.VISIBLE,
            ),
        )
        assertEquals(
            EntrySurfaceDismissPolicy.Decision.SEND_BACK,
            EntrySurfaceDismissPolicy.decide(
                targetPackageName = null,
                visibility = null,
            ),
        )
    }

    @Test
    fun unknownVisibilityDefersWithoutClearingScreenshotExclusion() {
        assertEquals(
            EntrySurfaceDismissPolicy.Decision.DEFER,
            EntrySurfaceDismissPolicy.decide(
                targetPackageName = "com.heytap.speechassist",
                visibility = PackageWindowVisibility.UNKNOWN,
            ),
        )
    }

    @Test
    fun appVoiceTurnClosesMovoPagesItselfAndNeverSendsGlobalBack() {
        // 2026-10-05 审查：App 内语音轮次（agent_ui）没有包名也没有自有关闭方式，被判成发全局返回，返回落在被操作的 App 上。
        val surfaces = FakeSurfaces(visibility = PackageWindowVisibility.VISIBLE)
        val voicePanelHidden = AtomicInteger()
        val pagesDismissed = AtomicInteger()
        val guard = EntrySurfaceGuard.from(
            handoff = handoff(source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, dismiss = true),
            logger = NoOpLogger,
            movoVoiceSurfaceDismissal = { voicePanelHidden.incrementAndGet(); true },
            movoPagesDismissal = { pagesDismissed.incrementAndGet(); true },
            surfaces = surfaces,
        )

        assertNotNull(guard)
        assertTrue(guard!!.dismissOnce())
        assertTrue(guard.dismissOnce())
        assertEquals(0, surfaces.backCalls.get())
        assertEquals(1, voicePanelHidden.get())
        assertEquals(1, pagesDismissed.get())
        assertTrue(guard.wasTriggered)
        // 首张截图排除 Movo 自己（退场中的页面），之后不再排除。
        assertEquals(setOf(io.github.fartown.movo.BuildConfig.APPLICATION_ID), guard.consumeScreenshotExcludedPackages())
    }

    @Test
    fun appVoiceTurnDismissalFailureIsNotStuckAndRetriesLater() {
        val surfaces = FakeSurfaces(visibility = PackageWindowVisibility.VISIBLE)
        val attempts = AtomicInteger()
        val guard = EntrySurfaceGuard.from(
            handoff = handoff(source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, dismiss = true),
            logger = NoOpLogger,
            movoPagesDismissal = { attempts.incrementAndGet() >= 2 },
            surfaces = surfaces,
        )!!

        assertFalse(guard.dismissOnce())
        assertFalse(guard.wasTriggered)
        assertTrue(guard.dismissOnce())
        assertEquals(2, attempts.get())
        assertEquals(0, surfaces.backCalls.get())
    }

    @Test
    fun failedBackRechecksTheEntryInsteadOfStayingNotReadyForTheWholeRun() {
        // 返回发出但入口窗口没在时限内消失：不留「已触发」，下次先重新看入口还在不在（已消失就不再返回）。
        val surfaces = FakeSurfaces(visibility = PackageWindowVisibility.VISIBLE, gone = false)
        val guard = EntrySurfaceGuard.from(
            handoff = handoff(source = "breeno", dismiss = true),
            logger = NoOpLogger,
            surfaces = surfaces,
        )!!

        assertFalse(guard.dismissOnce())
        assertEquals(1, surfaces.backCalls.get())

        surfaces.visibility = PackageWindowVisibility.GONE
        surfaces.gone = true
        assertTrue(guard.dismissOnce())
        assertEquals("entry already gone: no second BACK into the underlying app", 1, surfaces.backCalls.get())
        assertTrue(guard.dismissOnce())
    }

    @Test
    fun unknownEntryRetriesBackOnlyWhenTheBackActionItselfFailed() {
        val surfaces = FakeSurfaces(visibility = PackageWindowVisibility.UNKNOWN, backOk = false)
        val guard = EntrySurfaceGuard.from(
            handoff = handoff(source = "future_entry", dismiss = true),
            logger = NoOpLogger,
            surfaces = surfaces,
        )!!

        assertFalse(guard.dismissOnce())
        surfaces.backOk = true
        assertTrue(guard.dismissOnce())
        assertTrue(guard.dismissOnce())
        assertEquals(2, surfaces.backCalls.get())
    }

    @Test
    fun accessibilityUnavailableDoesNotSendBackAndCanRetry() {
        val surfaces = FakeSurfaces(visibility = PackageWindowVisibility.VISIBLE, available = false)
        val guard = EntrySurfaceGuard.from(
            handoff = handoff(source = "xiaoai", dismiss = true),
            logger = NoOpLogger,
            surfaces = surfaces,
        )!!

        assertFalse(guard.dismissOnce())
        surfaces.available = true
        assertTrue(guard.dismissOnce())
        assertEquals(1, surfaces.backCalls.get())
    }

    @Test
    fun concurrentCallersWaitForTheDismissalInFlightInsteadOfFailing() {
        // 悬浮层揭开与 GUI 守卫可能同时要求关入口：后来的调用等正在进行的那次，而不是因为「正在关」被拒。
        val release = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.CountDownLatch(1)
        val attempts = AtomicInteger()
        val guard = EntrySurfaceGuard.from(
            handoff = handoff(source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, dismiss = true),
            logger = NoOpLogger,
            movoPagesDismissal = {
                attempts.incrementAndGet()
                entered.countDown()
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                true
            },
            surfaces = FakeSurfaces(),
        )!!

        val first = java.util.concurrent.Executors.newSingleThreadExecutor()
        val second = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val firstResult = first.submit<Boolean> { guard.dismissOnce() }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val secondResult = second.submit<Boolean> { guard.dismissOnce() }
            release.countDown()
            assertTrue(firstResult.get(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(secondResult.get(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(1, attempts.get())
        } finally {
            first.shutdownNow()
            second.shutdownNow()
        }
    }

    @Test
    fun movoPagesAlreadyGoneCountAsClosedWithoutTouchingAnything() {
        var hidden = 0
        var moved = 0
        val dismisser = MovoPageDismisser(
            visiblePageCount = { 0 },
            hideConversationSheet = { hidden++; true },
            moveVisiblePagesToBack = { moved++; true },
        )

        assertTrue(dismisser.dismiss())
        assertEquals(0, hidden)
        assertEquals(0, moved)
    }

    @Test
    fun movoPagesAreMovedToBackAndAwaitedUntilTheyStop() {
        var visible = 2
        var now = 0L
        var polls = 0
        val dismisser = MovoPageDismisser(
            visiblePageCount = { visible },
            // 对话浮层收成悬浮球退到后台；主界面还在。
            hideConversationSheet = { visible = 1; true },
            moveVisiblePagesToBack = { true },
            clock = { now },
            sleep = { millis ->
                now += millis
                if (++polls == 3) visible = 0
            },
        )

        assertTrue(dismisser.dismiss())
        assertEquals(3, polls)
    }

    @Test
    fun movoPagesThatNeverStopTimeOutAsFailure() {
        var now = 0L
        val dismisser = MovoPageDismisser(
            visiblePageCount = { 1 },
            hideConversationSheet = { true },
            moveVisiblePagesToBack = { true },
            clock = { now },
            sleep = { millis -> now += millis },
            timeoutMillis = 200L,
        )

        assertFalse(dismisser.dismiss())
    }

    @Test
    fun movoPagesThatCannotBeMovedFailWithoutWaiting() {
        var slept = 0
        val dismisser = MovoPageDismisser(
            visiblePageCount = { 1 },
            hideConversationSheet = { true },
            moveVisiblePagesToBack = { false },
            sleep = { slept++ },
        )

        assertFalse(dismisser.dismiss())
        assertEquals(0, slept)
    }

    private class FakeSurfaces(
        @Volatile var visibility: PackageWindowVisibility = PackageWindowVisibility.VISIBLE,
        @Volatile var gone: Boolean = true,
        @Volatile var backOk: Boolean = true,
        @Volatile override var available: Boolean = true,
    ) : EntrySurfaceActions {
        val backCalls = AtomicInteger()

        override fun visibility(packageName: String): PackageWindowVisibility = visibility

        override fun awaitGone(packageName: String): Boolean = gone

        override fun back(): EntrySurfaceActions.BackResult {
            backCalls.incrementAndGet()
            return EntrySurfaceActions.BackResult(ok = backOk, code = if (backOk) "" else "FAILED")
        }
    }

    private fun handoff(source: String, dismiss: Boolean) =
        AgentRuntimeWire.EntryHandoff(
            id = "run-1",
            source = source,
            payload = "{}",
            dismissEntrySurfaceOnForegroundOperation = dismiss,
        )

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
