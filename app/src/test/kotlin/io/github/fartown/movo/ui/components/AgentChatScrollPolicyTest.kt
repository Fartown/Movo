package io.github.fartown.movo.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentChatScrollPolicyTest {
    @Test
    fun networkCompletionKeepsFollowingUntilRenderedTailSettles() {
        assertTrue(
            resolveBottomFollowEnabled(
                isStreaming = false,
                keepBottomAnchored = true,
                isUserDragging = false,
                isBottomSettling = true,
            )
        )
    }

    @Test
    fun draggingInterruptsCompletionFollowing() {
        assertFalse(
            resolveBottomFollowEnabled(
                isStreaming = false,
                keepBottomAnchored = true,
                isUserDragging = true,
                isBottomSettling = true,
            )
        )
    }

    @Test
    fun completionDoesNotPullReaderBackFromHistory() {
        assertFalse(
            resolveBottomFollowEnabled(
                isStreaming = false,
                keepBottomAnchored = false,
                isUserDragging = false,
                isBottomSettling = true,
            )
        )
    }

    @Test
    fun completedContentExpansionDoesNotFollowBottom() {
        assertFalse(
            resolveBottomFollowEnabled(
                isStreaming = false,
                keepBottomAnchored = true,
                isUserDragging = false,
            )
        )
    }

    @Test
    fun streamingTailGrowthFollowsBottom() {
        assertTrue(
            resolveBottomFollowEnabled(
                isStreaming = true,
                keepBottomAnchored = true,
                isUserDragging = false,
            )
        )
    }

    @Test
    fun completedConversationDoesNotJumpToInitialBottom() {
        assertFalse(
            shouldRequestInitialBottom(
                isStreaming = false,
                keepBottomAnchored = true,
                isUserDragging = false,
            )
        )
    }

    @Test
    fun streamingConversationRequestsInitialBottom() {
        assertTrue(
            shouldRequestInitialBottom(
                isStreaming = true,
                keepBottomAnchored = true,
                isUserDragging = false,
            )
        )
    }

    @Test
    fun contentGrowthDoesNotDisableBottomFollowing() {
        assertTrue(
            resolveKeepBottomAnchored(
                current = true,
                isUserDragging = false,
                isAtBottom = false,
            )
        )
    }

    @Test
    fun draggingAwayFromBottomDisablesFollowing() {
        assertFalse(
            resolveKeepBottomAnchored(
                current = true,
                isUserDragging = true,
                isAtBottom = false,
            )
        )
    }

    @Test
    fun reachingBottomEnablesFollowingAgain() {
        assertTrue(
            resolveKeepBottomAnchored(
                current = false,
                isUserDragging = false,
                isAtBottom = true,
            )
        )
    }

    @Test
    fun growingTailOnlyScrollsByTheOverflowDistance() {
        assertEquals(
            BottomFollowDecision(scrollByPx = 24),
            resolveBottomFollowDecision(
                enabled = true,
                bottomItemIndex = 8,
                sentinelBottom = 1024,
                viewportEnd = 1000,
                lastVisibleIndex = 8,
            ),
        )
    }

    @Test
    fun largeAppendRequestsBottomOnlyWhenSentinelLeftTheViewport() {
        assertEquals(
            BottomFollowDecision(requestIndex = 8),
            resolveBottomFollowDecision(
                enabled = true,
                bottomItemIndex = 8,
                sentinelBottom = null,
                viewportEnd = 1000,
                lastVisibleIndex = 6,
            ),
        )
    }

    @Test
    fun tailItemPushingSentinelOffscreenFollowsSmoothlyInsteadOfJumping() {
        assertEquals(
            BottomFollowDecision(scrollByPx = 956),
            resolveBottomFollowDecision(
                enabled = true,
                bottomItemIndex = 8,
                sentinelBottom = null,
                viewportEnd = 1000,
                lastVisibleIndex = 7,
                lastVisibleBottom = 1956,
            ),
        )
    }

    @Test
    fun disabledFollowingNeverMovesTheList() {
        assertEquals(
            BottomFollowDecision(),
            resolveBottomFollowDecision(
                enabled = false,
                bottomItemIndex = 8,
                sentinelBottom = 1100,
                viewportEnd = 1000,
                lastVisibleIndex = 8,
            ),
        )
    }

    /** 「保持最新」时：视口变化、最后一条自己长、末尾新加一条才跟底；上面的执行卡展开把回答往下推不跟（真机：展开执行卡跳一下）。 */
    @Test
    fun latestModeFollowsOnlyResizeTailGrowthAndAppends() {
        val tail = ChatTailLayout("answer", top = 800, bottom = 1200)
        // 第一次布局：没有可比的上一帧，不跟。
        assertFalse(followsLatestOnLayoutChange(null, 1500, null, tail))
        // 键盘弹出、浮层拉低：视口变了。
        assertTrue(followsLatestOnLayoutChange(1500, 1100, tail, tail))
        // 回答里的图片加载出来：顶边不动、底边变长。
        assertTrue(followsLatestOnLayoutChange(1500, 1500, tail, tail.copy(bottom = 1400)))
        // 末尾新加了一条。
        assertTrue(followsLatestOnLayoutChange(1500, 1500, tail, ChatTailLayout("suggestions", 1200, 1300)))
        // 点开上面的执行卡：回答整体被往下推（顶边也动了），不跟。
        assertFalse(followsLatestOnLayoutChange(1500, 1500, tail, tail.copy(top = 1100, bottom = 1500)))
        // 收起上面的执行卡：回答整体上移，不跟。
        assertFalse(followsLatestOnLayoutChange(1500, 1500, tail, tail.copy(top = 600, bottom = 1000)))
        // 最后一条不在视口里：不跟。
        assertFalse(followsLatestOnLayoutChange(1500, 1500, tail, null))
    }
}
