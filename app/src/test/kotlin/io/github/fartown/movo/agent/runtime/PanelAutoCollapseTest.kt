package io.github.fartown.movo.agent.runtime

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.fartown.movo.agent.device.AgentTouchInjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PanelAutoCollapseTest {
    @get:Rule
    val compose = createComposeRule()

    private val manager: AccessibilityManager
        get() = ApplicationProvider.getApplicationContext<Context>().getSystemService(AccessibilityManager::class.java)

    @Test
    fun staysAtFourSecondsByDefault() {
        assertEquals(4_000L, PanelAutoCollapse.timeoutMs(null))
        assertEquals(4_000L, PanelAutoCollapse.timeoutMs(manager))
    }

    @Test
    fun followsTheSystemTimeToTakeAction() {
        shadowOf(manager).setInteractiveUiTimeout(10_000)

        assertEquals(10_000L, PanelAutoCollapse.timeoutMs(manager))
    }

    @Test
    fun aFingerOnThePanelHoldsItUntilLifted() {
        val hold = PanelTouchHold { false }

        assertTrue(hold.onTouch(down = true))
        assertTrue(hold.held)
        assertTrue(hold.onTouch(down = false))
        assertFalse(hold.held)
    }

    @Test
    fun aGestureTheAgentInjectsDoesNotHoldOrRenewThePanel() {
        var injecting = true
        val hold = PanelTouchHold { injecting }

        // 例如 Agent 向下滚动时的滑动从卡片上起手：不算用户操作，抬起后也不重新计时。
        assertFalse(hold.onTouch(down = true))
        assertFalse(hold.held)
        injecting = false
        assertFalse(hold.onTouch(down = false))

        // 之后用户自己按住照常算。
        assertTrue(hold.onTouch(down = true))
        assertTrue(hold.held)
    }

    @Test
    fun touchesAreMarkedAsInjectedOnlyWhileTheAgentInjects() {
        assertFalse(AgentTouchInjection.active)
        val seen = AgentTouchInjection.during { AgentTouchInjection.active }
        assertTrue(seen)
        assertFalse(AgentTouchInjection.active)
        runCatching { AgentTouchInjection.during { error("dispatch failed") } }
        assertFalse(AgentTouchInjection.active)
    }

    @Test
    fun overlaysMakeWayBeforeTheAgentPressesAndComeBackAfter() {
        val calls = mutableListOf<String>()
        AgentTouchInjection.overlayYield = { x, y ->
            calls += "make way at $x,$y, injecting=${AgentTouchInjection.active}"
            ({ calls += "restore, injecting=${AgentTouchInjection.active}" })
        }
        try {
            AgentTouchInjection.touchAt(10f, 20f) { calls += "press, injecting=${AgentTouchInjection.active}" }
            // 按的时候出错也要恢复（例如悬浮球重新接触摸）。
            runCatching { AgentTouchInjection.touchAt(3f, 4f) { error("dispatch failed") } }
            // 让开失败（例如窗口已经没了）也照常按下。
            AgentTouchInjection.overlayYield = { _, _ -> error("window gone") }
            AgentTouchInjection.touchAt(1f, 2f) { calls += "pressed anyway" }
        } finally {
            AgentTouchInjection.overlayYield = null
        }

        assertEquals(
            listOf(
                "make way at 10.0,20.0, injecting=false", "press, injecting=true", "restore, injecting=false",
                "make way at 3.0,4.0, injecting=false", "restore, injecting=false",
                "pressed anyway",
            ),
            calls,
        )
    }

    @Test
    fun holdingThePanelIsReportedUntilTheFingerLifts() {
        val touches = mutableListOf<Boolean>()
        var clicks = 0
        compose.setContent {
            Box(Modifier.observePanelTouches { touches += it }) {
                Box(Modifier.size(80.dp).testTag("stop").clickable { clicks++ })
            }
        }

        compose.onNodeWithTag("stop").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(5_000)
        compose.runOnIdle { assertEquals(listOf(true), touches) }

        compose.onNodeWithTag("stop").performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(listOf(true, false), touches)
            // 只观察、不消费：卡片上的按钮照常收到这一下。
            assertEquals(1, clicks)
        }
    }

    @Test
    fun theTouchEndsOnlyWhenEveryFingerIsUp() {
        val touches = mutableListOf<Boolean>()
        compose.setContent {
            Row(Modifier.observePanelTouches { touches += it }) {
                Box(Modifier.size(80.dp))
                Box(Modifier.size(80.dp))
            }
        }

        compose.onRoot().performTouchInput {
            down(0, Offset(40f, 40f))
            down(1, Offset(width - 40f, 40f))
            up(0)
        }
        compose.runOnIdle { assertEquals(listOf(true), touches) }

        compose.onRoot().performTouchInput { up(1) }
        compose.runOnIdle { assertEquals(listOf(true, false), touches) }
    }

    @Test
    fun aCancelledTouchAlsoEnds() {
        val touches = mutableListOf<Boolean>()
        compose.setContent {
            Box(Modifier.size(80.dp).testTag("panel").observePanelTouches { touches += it })
        }

        compose.onNodeWithTag("panel").performTouchInput {
            down(center)
            cancel()
        }

        compose.runOnIdle { assertEquals(listOf(true, false), touches) }
    }
}
