package io.github.fartown.movo.ui.components

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/** START 时立即刷新；STOP 时取消当前轮询，下一次 START 重新刷新。 */
internal suspend fun Lifecycle.pollWhileStarted(intervalMillis: () -> Long, refresh: suspend () -> Unit) {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        while (true) {
            refresh()
            delay(intervalMillis())
        }
    }
}
