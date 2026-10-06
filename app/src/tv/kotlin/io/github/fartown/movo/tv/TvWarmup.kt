package io.github.fartown.movo.tv

import android.content.Context
import android.os.Looper
import androidx.annotation.MainThread
import io.github.fartown.movo.ui.app.AgentAppSession
import io.github.fartown.movo.ui.app.AgentConversationStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 电视进程靠无障碍常驻：无障碍一连上，就在后台读好对话，再趁主线程空闲建好会话。
 * 打开 App、开机后第一次唤醒都不用在主线程上等数据库（之前打开 App 要先等 2 s 多）。
 */
internal object TvWarmup {
    private var started = false

    @MainThread
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val snapshot = AgentConversationStore.preload(app, scope)
        scope.launch {
            runCatching { snapshot.await() }
            Looper.myQueue().addIdleHandler {
                AgentAppSession.get(app)
                false
            }
        }
    }
}
