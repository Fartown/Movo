package io.github.fartown.movo.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.fartown.movo.ui.theme.MovoIconData

/**
 * 输入框上方的提示（`Composer/Notice`，规范 8.5、8.11）：语音异常，以及取代 Toast 的输入相关反馈（发送被拦下、
 * 附件没能添加、模型没切换成功等）。
 *
 * @param autoDismissMillis 自动消失时长；null = 一直显示到处理完成或手动关闭（语音异常）。
 * @param id 每条提示唯一，用于只关掉「这一条」而不误关之后出现的新提示。
 */
internal data class ComposerNotice(
    val icon: MovoIconData,
    val title: String,
    val description: String,
    val actionLabel: String?,
    val action: (() -> Unit)?,
    val autoDismissMillis: Long? = null,
    val id: Long = ComposerNotices.nextId(),
)

/**
 * App 层交给输入框的「当前提示」（取代 Toast，规范 8.11「轻提示」）。由 `AgentAppState` 设置与到时清除，
 * 所有输入框（App 内对话、对话浮层、执行详情页）渲染同一条；同时最多一条，新的替换旧的。
 */
internal object ComposerNotices {
    /** 取代 Toast 的提示默认停留时长。 */
    const val AUTO_DISMISS_MS = 4_000L

    private val ids = java.util.concurrent.atomic.AtomicLong()

    var current by mutableStateOf<ComposerNotice?>(null)
        private set

    fun nextId(): Long = ids.incrementAndGet()

    fun show(notice: ComposerNotice) {
        current = notice
    }

    /** 只关掉 [id] 这一条；之后已经换成别的提示时不动。 */
    fun dismiss(id: Long) {
        if (current?.id == id) current = null
    }

    fun clear() {
        current = null
    }
}
