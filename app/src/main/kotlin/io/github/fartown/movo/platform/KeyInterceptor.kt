package io.github.fartown.movo.platform

import android.view.KeyEvent

/** 无障碍按键过滤的处理者（电视用返回键取消本轮，见实施方案 §5.8）。 */
internal fun interface KeyInterceptor {
    /** 返回 true 表示消费该按键，不再派发给前台应用。 */
    fun onKeyEvent(event: KeyEvent): Boolean
}
