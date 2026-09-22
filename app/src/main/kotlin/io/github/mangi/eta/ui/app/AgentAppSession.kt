package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.annotation.MainThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The app and its conversation sheet are two hosts of the same live session. */
internal object AgentAppSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var state: AgentAppState? = null

    @MainThread
    fun get(context: Context): AgentAppState = state ?: AgentAppState(
        context = context.applicationContext,
        scope = scope,
    ).also { state = it }
}
