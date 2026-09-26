package io.github.fartown.movo.ui

import android.app.Activity
import android.app.KeyguardManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 锁屏上不展示会话内容。
 *
 * 助手入口可能在锁屏时被唤起（唤醒词、锁屏助手手势）。此时只在锁屏上方停留到解锁框出现，
 * 解锁成功之前不加载历史、不开始收音；取消解锁就关闭界面。解锁后撤掉 showWhenLocked，
 * 之后再锁屏时界面会正常退到锁屏后面，而不是继续盖在锁屏上。
 */
internal class KeyguardContentGate(
    private val activity: Activity,
    private val onCancelled: () -> Unit,
) {
    var locked by mutableStateOf(false)
        private set

    /** 每次助手入口打开或复用界面时调用；已在等待解锁时不重复请求。 */
    fun check() {
        if (locked) return
        val keyguard = activity.getSystemService(KeyguardManager::class.java) ?: return
        if (!keyguard.isKeyguardLocked) return
        locked = true
        activity.setShowWhenLocked(true)
        keyguard.requestDismissKeyguard(activity, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                locked = false
                activity.setShowWhenLocked(false)
            }

            override fun onDismissCancelled() = onCancelled()

            override fun onDismissError() = onCancelled()
        })
    }
}
