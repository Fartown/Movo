package io.github.fartown.movo.ui.app

import android.app.Application
import android.content.Context
import android.os.Looper
import io.github.fartown.movo.R
import io.github.fartown.movo.data.db.MovoDatabase
import io.github.fartown.movo.ui.components.AgentConversationDraftStore
import io.github.fartown.movo.ui.components.ComposerNotices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 规范 8.11「不用 Toast」：附件添加失败改为输入框上方的 `Composer/Notice`，并按时自动消失。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentAppStateComposerNoticeTest {
    private lateinit var context: Context
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        MovoDatabase.closeForTests()
        context.deleteDatabase("movo.db")
        AgentConversationDraftStore.shared.clear()
        ComposerNotices.clear()
        // 测试里没有运行时服务：绑定直接失败，不在主线程回调里拿到空组件名。
        shadowOf(context as Application).declareComponentUnbindable(
            io.github.fartown.movo.agent.runtime.AgentRuntimeWire.serviceIntent().component,
        )
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    @After
    fun tearDown() {
        scope.cancel()
        ComposerNotices.clear()
        AgentConversationDraftStore.shared.clear()
        MovoDatabase.closeForTests()
    }

    @Test
    fun invalidPathShowsFailureNoticeInsteadOfToastAndAddsNothing() {
        val state = AgentAppState(context, scope)

        state.attachFilePath("relative/path.txt")
        val notice = awaitNotice(state)

        assertEquals(
            context.resources.getQuantityString(R.plurals.movo_notice_attach_failed_title, 1, 1),
            notice.title,
        )
        assertEquals(context.getString(R.string.state_ui_please_enter_a_valid_absolute_path_6afeb4), notice.description)
        assertEquals(ComposerNotices.AUTO_DISMISS_MS, notice.autoDismissMillis)
        assertTrue(state.homeState.pendingFileReferences.isEmpty())
        // 手动关闭只关这一条：关别的 id 不影响当前提示。
        ComposerNotices.dismiss(notice.id + 1)
        assertEquals(notice, state.composerNotice)

        // 停留 AUTO_DISMISS_MS 后自动消失。
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ComposerNotices.AUTO_DISMISS_MS - 100))
        assertEquals(notice, state.composerNotice)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
        assertNull(state.composerNotice)
    }

    private fun awaitNotice(state: AgentAppState): io.github.fartown.movo.ui.components.ComposerNotice {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            state.composerNotice?.let { return it }
            Thread.sleep(10)
        }
        val notice = state.composerNotice
        assertNotNull("composer notice was not set", notice)
        return notice!!
    }
}
