package io.github.fartown.movo.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatGptLoginManagerTest {
    @Test
    fun cancelledLoginEndsInFailedStateIndependentOfUi() {
        val waiting = ChatGptLoginManager.start()
        assertTrue(waiting.authorizationUrl.startsWith("https://auth.openai.com/oauth/authorize?"))
        assertEquals(waiting, ChatGptLoginManager.state.value)

        ChatGptLoginManager.cancel()

        val deadline = System.currentTimeMillis() + 5_000
        while (ChatGptLoginManager.state.value !is ChatGptLoginManager.State.Failed &&
            System.currentTimeMillis() < deadline
        ) Thread.sleep(20)
        val failed = ChatGptLoginManager.state.value as ChatGptLoginManager.State.Failed
        assertTrue(failed.message.contains("取消"))

        ChatGptLoginManager.acknowledge()
        assertEquals(ChatGptLoginManager.State.Idle, ChatGptLoginManager.state.value)
    }
}
