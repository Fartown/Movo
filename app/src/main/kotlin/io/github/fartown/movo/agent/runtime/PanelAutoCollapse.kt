package io.github.fartown.movo.agent.runtime

import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import io.github.fartown.movo.agent.device.AgentTouchInjection

/**
 * 悬浮球展开卡的自动收回（规范 8.1）：运行中 4 秒无操作收回。手指按在卡片上不算无操作；
 * 系统无障碍「操作时限」调长时跟着变长。
 */
internal object PanelAutoCollapse {
    const val BASE_MS = 4_000L

    /** 用户在系统无障碍里调长了「操作时限」就用那个时长；不会比 4 秒短。 */
    fun timeoutMs(manager: AccessibilityManager?): Long {
        if (manager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return BASE_MS
        val recommended = runCatching {
            manager.getRecommendedTimeoutMillis(
                BASE_MS.toInt(),
                AccessibilityManager.FLAG_CONTENT_ICONS or AccessibilityManager.FLAG_CONTENT_TEXT or
                    AccessibilityManager.FLAG_CONTENT_CONTROLS,
            )
        }.getOrDefault(BASE_MS.toInt())
        return maxOf(BASE_MS, recommended.toLong())
    }
}

/**
 * 展开卡上的触摸算不算用户在操作：用户手指按着时不自动收回，全部抬起后重新计时。
 * Agent 自己注入的手势落在卡片上不算（例如向下滚动时的滑动正好从卡片上起手）：不续期，卡片照原来的时间收起，
 * 否则 Agent 连着滚动时卡片会一直不收、滚动也一直落在卡片上。
 */
internal class PanelTouchHold(private val injectedByAgent: () -> Boolean = { AgentTouchInjection.active }) {
    /** 用户手指正按在卡片上。 */
    var held = false
        private set
    private var ignoring = false

    /** 手指按下（true）或全部抬起 / 取消（false）。返回这一下是否算用户操作。 */
    fun onTouch(down: Boolean): Boolean {
        if (down) {
            ignoring = injectedByAgent()
            held = !ignoring
            return !ignoring
        }
        val counted = !ignoring
        ignoring = false
        held = false
        return counted
    }
}

/**
 * 只观察、不消费卡片上的触摸：有手指按下时 `onTouch(true)`，全部抬起或手势被取消时 `onTouch(false)`。
 * 卡片里的按钮照常收到点击。
 */
internal fun Modifier.observePanelTouches(onTouch: (Boolean) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        onTouch(true)
        try {
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
            } while (event.changes.any { it.pressed })
        } finally {
            onTouch(false)
        }
    }
}
