package io.github.mangi.eta.data.auth

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程级 ChatGPT 登录流程的唯一持有者。
 *
 * 登录要在浏览器里停留较久（密码重试、两步验证），期间 Eta 界面可能重组、切走或离开当前页面；
 * 会话、等待回跳、换取令牌与保存凭证都放在这里的后台线程完成，界面只订阅 [state]。
 *
 * 浏览器在前台时，Eta 是后台缓存进程：Android 15 起会被切断网络（blocked=APP_BACKGROUND），
 * HyperOS 等系统还会直接冻结进程，回环服务无法接收回跳。登录期间因此持有 [AgentExecutionService]
 * 前台服务租约；租约不可用时退回“授权码留在内存、Eta 回到前台再换取令牌”。
 */
internal object ChatGptLoginManager {
    private const val TAG = "EtaChatGptLogin"
    private const val LOGIN_TIMEOUT_MINUTES = 30L
    private const val KEEP_ALIVE_LEASE = "chatgpt-login"

    sealed interface State {
        data object Idle : State
        data class WaitingForBrowser(val authorizationUrl: String, val callbackListening: Boolean) : State
        data object Exchanging : State
        data object Succeeded : State
        data class Failed(val message: String) : State
    }

    private class PendingExchange(val session: ChatGptOAuth.LoginSession, val code: String)

    private val lock = Any()
    private val mutableState = MutableStateFlow<State>(State.Idle)
    private var session: ChatGptOAuth.LoginSession? = null
    private var pending: PendingExchange? = null
    private var exchanging = false
    private var startedActivities = 0
    private var tracksForeground = false
    private var keepAliveActive = false

    val state: StateFlow<State> = mutableState.asStateFlow()

    /** 注册前台跟踪；未注册（例如单元测试）时视为始终在前台。 */
    fun init(application: Application) {
        synchronized(lock) {
            if (tracksForeground) return
            tracksForeground = true
        }
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                val becameForeground = synchronized(lock) { ++startedActivities == 1 }
                if (becameForeground) tryExchange()
            }

            override fun onActivityStopped(activity: Activity) {
                synchronized(lock) { startedActivities = (startedActivities - 1).coerceAtLeast(0) }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /**
     * 开始新的登录；已有进行中的会话会先取消。返回授权地址，调用方负责打开浏览器。
     * 传入 [context] 时在登录期间持有前台服务，必须在 Eta 位于前台时调用。
     */
    fun start(context: Context? = null): State.WaitingForBrowser {
        val started = synchronized(lock) {
            session?.cancel()
            pending = null
            ChatGptOAuth.startLogin().also { session = it }
        }
        val keptAlive = context != null && AgentExecutionService.acquire(context, KEEP_ALIVE_LEASE) { cancel() }
        synchronized(lock) { keepAliveActive = keptAlive }
        val waiting = State.WaitingForBrowser(started.authorization.url, started.callbackListening)
        mutableState.value = waiting
        Log.i(TAG, "login started callbackListening=${started.callbackListening} keepAlive=$keptAlive")
        Thread({ awaitCode(started) }, "chatgpt-login").apply { isDaemon = true }.start()
        return waiting
    }

    fun submitManualInput(input: String) {
        synchronized(lock) { session }?.submitManualInput(input)
    }

    fun cancel() {
        val active = synchronized(lock) {
            pending = null
            session
        }
        active?.cancel()
    }

    /** 界面读取到终态后调用，回到空闲。 */
    fun acknowledge() {
        val current = mutableState.value
        if (current is State.Succeeded || current is State.Failed) mutableState.value = State.Idle
    }

    private fun awaitCode(started: ChatGptOAuth.LoginSession) {
        val code = try {
            started.awaitCode(LOGIN_TIMEOUT_MINUTES)
        } catch (failure: Exception) {
            finish(started, failure)
            return
        }
        synchronized(lock) {
            if (session !== started) return
            pending = PendingExchange(started, code)
        }
        mutableState.value = State.Exchanging
        Log.i(TAG, "authorization code received foreground=${isForeground()}")
        tryExchange()
    }

    /** 仅在前台换取令牌；后台时等待 [init] 注册的前台回调再次触发。 */
    private fun tryExchange() {
        val target = synchronized(lock) {
            val candidate = pending ?: return
            if (exchanging) return
            if (!isForegroundLocked()) {
                Log.i(TAG, "token exchange deferred until Eta returns to foreground")
                return
            }
            exchanging = true
            candidate
        }
        Thread({ exchange(target) }, "chatgpt-token-exchange").apply { isDaemon = true }.start()
    }

    private fun exchange(target: PendingExchange) {
        val result = runCatching { target.session.exchange(target.code).also(ChatGptAuth::save) }
        val retryLater = synchronized(lock) {
            exchanging = false
            if (pending !== target) return
            val failure = result.exceptionOrNull()
            // 网络请求刚发出时应用又退到后台，同样会被系统断网；授权码仍有效，回到前台后重试。
            if (failure is IOException && !isForegroundLocked()) {
                Log.i(TAG, "token exchange interrupted in background; retrying on foreground")
                true
            } else {
                pending = null
                false
            }
        }
        if (retryLater) return
        finish(target.session, result.exceptionOrNull(), result.getOrNull())
    }

    private fun finish(
        finished: ChatGptOAuth.LoginSession,
        failure: Throwable?,
        credentials: ChatGptCredentials? = null,
    ) {
        val releaseKeepAlive = synchronized(lock) {
            if (session !== finished) return
            session = null
            keepAliveActive.also { keepAliveActive = false }
        }
        if (releaseKeepAlive) AgentExecutionService.release(KEEP_ALIVE_LEASE)
        mutableState.value = if (failure == null && credentials != null) {
            Log.i(TAG, "login succeeded plan=${credentials.planType.ifBlank { "unknown" }}")
            State.Succeeded
        } else {
            Log.w(TAG, "login failed type=${failure?.javaClass?.simpleName} message=${failure?.message}")
            State.Failed(failure?.message ?: failure?.javaClass?.simpleName ?: "登录失败")
        }
    }

    private fun isForeground(): Boolean = synchronized(lock) { isForegroundLocked() }

    /** 有 Activity 在前台，或登录持有前台服务时，进程既不会被冻结也不会被断网。 */
    private fun isForegroundLocked(): Boolean = !tracksForeground || startedActivities > 0 || keepAliveActive
}
