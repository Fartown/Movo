@file:android.annotation.SuppressLint("LocalContextGetResourceValueCall")

package io.github.fartown.movo.ui.pages.providers

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import io.github.fartown.movo.MovoApp
import io.github.fartown.movo.R
import io.github.fartown.movo.data.auth.ChatGptAuth
import io.github.fartown.movo.data.auth.ChatGptLoginManager
import io.github.fartown.movo.data.repository.RuntimeConfigRepository
import io.github.fartown.movo.ui.components.movo.MovoConfirmDialog
import io.github.fartown.movo.ui.components.movo.RowTrailing
import io.github.fartown.movo.ui.components.movo.SettingsRow
import io.github.fartown.movo.ui.theme.MovoSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.fartown.movo.ui.components.movo.TextField

/**
 * ChatGPT 订阅登录的发起与等待回跳：服务商页 ChatGPT 卡的「登录 ChatGPT」和 ChatGPT 详情的登录行共用。
 * 登录会话由 [ChatGptLoginManager] 持有，本对象只转发操作；等待浏览器回跳的对话框见 [ChatGptLoginDialog]。
 */
@Stable
internal class ChatGptLoginController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val loginState: State<ChatGptLoginManager.State>,
    private val onStatus: (String) -> Unit,
) {
    /** 正在绑定回环端口（打开浏览器之前）。 */
    var starting by mutableStateOf(false)
        private set

    /** 等待回跳时手动粘贴的回跳地址或授权码。 */
    var manualInput by mutableStateOf("")

    val login: ChatGptLoginManager.State
        get() = loginState.value

    /** 登录进行中（绑定端口、等浏览器、换取令牌）：发起按钮置灰。 */
    val busy: Boolean
        get() = starting ||
            login is ChatGptLoginManager.State.WaitingForBrowser ||
            login == ChatGptLoginManager.State.Exchanging

    fun start() {
        scope.launch {
            // 回环端口在 IO 线程绑定，浏览器在绑定完成后再打开，避免回跳早于监听。
            starting = true
            val waiting = try {
                // 传入 Context：登录期间持有前台服务，浏览器在前台时 Movo 不会被冻结或断网。
                withContext(Dispatchers.IO) { ChatGptLoginManager.start(context.applicationContext) }
            } finally {
                starting = false
            }
            manualInput = ""
            try {
                openAuthorizationPage(context, waiting.authorizationUrl)
            } catch (_: ActivityNotFoundException) {
                ChatGptLoginManager.cancel()
                onStatus(context.getString(R.string.provider_error, context.getString(R.string.chatgpt_open_browser_failed)))
            }
        }
    }
}

/**
 * 记住一个登录控制器，并在登录结束时回报结果：成功回 [onStatus]（成功文案）后调 [onSucceeded]，失败回 [onStatus]（失败文案）。
 * 同一页面只调一次，并配一个 [ChatGptLoginDialog]。
 */
@Composable
internal fun rememberChatGptLoginController(
    scope: CoroutineScope,
    onStatus: (String) -> Unit,
    onSucceeded: () -> Unit = {},
): ChatGptLoginController {
    val context = LocalContext.current
    val loginState = ChatGptLoginManager.state.collectAsState()
    val currentOnStatus by rememberUpdatedState(onStatus)
    val currentOnSucceeded by rememberUpdatedState(onSucceeded)
    val controller = remember(context, scope, loginState) {
        ChatGptLoginController(context, scope, loginState) { currentOnStatus(it) }
    }
    val login = loginState.value
    LaunchedEffect(login) {
        when (login) {
            ChatGptLoginManager.State.Succeeded -> {
                currentOnStatus(context.getString(R.string.chatgpt_login_success))
                ChatGptLoginManager.acknowledge()
                currentOnSucceeded()
            }
            is ChatGptLoginManager.State.Failed -> {
                currentOnStatus(context.getString(R.string.provider_error, login.message))
                ChatGptLoginManager.acknowledge()
            }
            else -> Unit
        }
    }
    return controller
}

/** 等待浏览器回跳时的 `Dialog/Confirm`（8.11）：可粘贴回跳地址手动提交，取消即结束本次登录。 */
@Composable
internal fun ChatGptLoginDialog(controller: ChatGptLoginController) {
    val waiting = controller.login as? ChatGptLoginManager.State.WaitingForBrowser
    // 对话框退场动画期间保留最后一次的端口状态文案。
    var lastCallbackListening by remember { mutableStateOf(true) }
    if (waiting != null) lastCallbackListening = waiting.callbackListening
    MovoConfirmDialog(
        show = waiting != null,
        title = stringResource(R.string.chatgpt_login_dialog_title),
        message = stringResource(
            if (lastCallbackListening) R.string.chatgpt_login_dialog_summary
            else R.string.chatgpt_login_port_busy,
        ),
        confirmText = stringResource(R.string.chatgpt_login_submit),
        confirmEnabled = controller.manualInput.isNotBlank(),
        onConfirm = { ChatGptLoginManager.submitManualInput(controller.manualInput) },
        onDismissRequest = { ChatGptLoginManager.cancel() },
        extraContent = {
            Spacer(modifier = Modifier.height(MovoSpacing.md))
            TextField(
                value = controller.manualInput,
                onValueChange = { controller.manualInput = it },
                label = stringResource(R.string.chatgpt_login_paste_hint),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

/**
 * ChatGPT 订阅登录区：替代 API Key 输入框；放在「连接配置」卡片里，行样式同 `Settings/Row`（规范 8.7），
 * 等待浏览器回跳时弹出 [ChatGptLoginDialog]。
 *
 * 界面重组、离开页面或切到浏览器都不会中断正在进行的登录。
 */
@Composable
internal fun ChatGptAccountSection(
    scope: CoroutineScope,
    onStatus: (String) -> Unit,
) {
    val context = LocalContext.current
    val account by ChatGptAuth.accountState.collectAsState()
    val controller = rememberChatGptLoginController(
        scope = scope,
        onStatus = onStatus,
        // 登录后 ChatGPT 变为可用：没有可用的当前模型时由 repairSelection 选上它，并同步给运行时。
        onSucceeded = { scope.launch { RuntimeConfigRepository.ensureDefaults(MovoApp.serviceInstance) } },
    )
    var working by remember { mutableStateOf(false) }

    if (account.loggedIn) {
        val identity = listOf(account.email, account.planType.uppercase())
            .filter { it.isNotBlank() }
            .joinToString("·")
            .ifBlank { "ChatGPT" }
        SettingsRow(
            title = stringResource(R.string.chatgpt_account_title),
            subtitle = stringResource(R.string.chatgpt_logged_in_summary, identity),
            trailing = RowTrailing.None,
        )
        SettingsRow(
            title = stringResource(R.string.chatgpt_logout),
            trailing = RowTrailing.None,
            enabled = !working,
            onClick = {
                scope.launch {
                    working = true
                    try {
                        withContext(Dispatchers.IO) { ChatGptAuth.logout() }
                        // 退出后 ChatGPT 不再可用：当前是它时换成其他可用的服务商（都没有则不选）。
                        RuntimeConfigRepository.ensureDefaults(MovoApp.serviceInstance)
                        onStatus(context.getString(R.string.chatgpt_logged_out))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (throwable: Throwable) {
                        onStatus(context.getString(R.string.provider_error, throwable.message.orEmpty()))
                    } finally {
                        working = false
                    }
                }
            },
        )
    } else {
        SettingsRow(
            title = stringResource(R.string.chatgpt_login),
            subtitle = stringResource(
                if (controller.login == ChatGptLoginManager.State.Exchanging) R.string.chatgpt_login_exchanging
                else R.string.chatgpt_login_summary,
            ),
            trailing = RowTrailing.External(),
            enabled = !controller.busy,
            onClick = controller::start,
        )
    }

    ChatGptLoginDialog(controller)
}

/**
 * 用 Custom Tabs 在 Movo 之上打开授权页：优先默认浏览器，不支持时退到已安装的 Chrome；
 * 两者都没有 Custom Tabs 能力时交给普通浏览器。授权页必须由真实浏览器渲染，Google 登录拒绝内嵌 WebView。
 */
private fun openAuthorizationPage(context: Context, url: String) {
    val uri = url.toUri()
    val customTabsPackage = CustomTabsClient.getPackageName(context, listOf(CHROME_PACKAGE), false)
    if (customTabsPackage == null) {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return
    }
    val customTab = CustomTabsIntent.Builder()
        .setShowTitle(true)
        .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
        .build()
    customTab.intent.setPackage(customTabsPackage)
    if (context !is Activity) customTab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    customTab.launchUrl(context, uri)
}

private const val CHROME_PACKAGE = "com.android.chrome"
