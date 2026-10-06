package io.github.fartown.movo.ui.app

import android.content.Context
import androidx.annotation.MainThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The app and its conversation sheet are two hosts of the same live session. */
internal object AgentAppSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var state: AgentAppState? = null

    private val createdListeners = mutableListOf<(AgentAppState) -> Unit>()

    @MainThread
    fun get(context: Context): AgentAppState = state ?: AgentAppState(
        context = context.applicationContext,
        scope = scope,
    ).also { created ->
        state = created
        createdListeners.toList().forEach { it(created) }
        createdListeners.clear()
    }

    /** 已经建好的会话；还没建时返回 null（不为了查状态去建它：建会话要读数据库）。 */
    @MainThread
    fun peek(): AgentAppState? = state

    /** 会话建好时通知（已经建好就立刻通知）。 */
    @MainThread
    fun onCreated(listener: (AgentAppState) -> Unit) {
        state?.let(listener) ?: createdListeners.add(listener)
    }
}
