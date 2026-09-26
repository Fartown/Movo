@file:android.annotation.SuppressLint("LocalContextGetResourceValueCall")

package io.github.mangi.eta.ui.pages.providers

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import io.github.mangi.eta.R
import io.github.mangi.eta.data.auth.ChatGptAuth
import io.github.mangi.eta.data.auth.ChatGptLoginManager
import io.github.mangi.eta.ui.components.movo.MovoConfirmDialog
import io.github.mangi.eta.ui.components.movo.RowTrailing
import io.github.mangi.eta.ui.components.movo.SettingsRow
import io.github.mangi.eta.ui.theme.MovoSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.TextField

/**
 * ChatGPT 订阅登录区：替代 API Key 输入框；放在「连接配置」卡片里，行样式同 `Settings/Row`（规范 8.7），
 * 等待浏览器回跳时弹出 `Dialog/Confirm`（8.11），可粘贴回跳地址手动提交。
 *
 * 登录会话由 [ChatGptLoginManager] 持有，本组件只展示状态和转发操作；
 * 界面重组、离开页面或切到浏览器都不会中断正在进行的登录。
 */
@Composable
internal fun ChatGptAccountSection(
    scope: CoroutineScope,
    onStatus: (String) -> Unit,
) {
    val context = LocalContext.current
    val account by ChatGptAuth.accountState.collectAsState()
    val login by ChatGptLoginManager.state.collectAsState()
    var manualInput by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }

    LaunchedEffect(login) {
        when (val current = login) {
            ChatGptLoginManager.State.Succeeded -> {
                onStatus(context.getString(R.string.chatgpt_login_success))
                ChatGptLoginManager.acknowledge()
            }
            is ChatGptLoginManager.State.Failed -> {
                onStatus(context.getString(R.string.provider_error, current.message))
                ChatGptLoginManager.acknowledge()
            }
            else -> Unit
        }
    }

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
        val inProgress = login is ChatGptLoginManager.State.WaitingForBrowser ||
            login == ChatGptLoginManager.State.Exchanging
        SettingsRow(
            title = stringResource(R.string.chatgpt_login),
            subtitle = stringResource(
                if (login == ChatGptLoginManager.State.Exchanging) R.string.chatgpt_login_exchanging
                else R.string.chatgpt_login_summary,
            ),
            trailing = RowTrailing.External(),
            enabled = !working && !inProgress,
            onClick = {
                scope.launch {
                    // 回环端口在 IO 线程绑定，浏览器在绑定完成后再打开，避免回跳早于监听。
                    working = true
                    val waiting = try {
                        // 传入 Context：登录期间持有前台服务，浏览器在前台时 Eta 不会被冻结或断网。
                        withContext(Dispatchers.IO) { ChatGptLoginManager.start(context.applicationContext) }
                    } finally {
                        working = false
                    }
                    manualInput = ""
                    try {
                        openAuthorizationPage(context, waiting.authorizationUrl)
                    } catch (_: ActivityNotFoundException) {
                        ChatGptLoginManager.cancel()
                        onStatus(context.getString(R.string.provider_error, context.getString(R.string.chatgpt_open_browser_failed)))
                    }
                }
            },
        )
    }

    val waiting = login as? ChatGptLoginManager.State.WaitingForBrowser
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
        confirmEnabled = manualInput.isNotBlank(),
        onConfirm = { ChatGptLoginManager.submitManualInput(manualInput) },
        onDismissRequest = { ChatGptLoginManager.cancel() },
        extraContent = {
            Spacer(modifier = Modifier.height(MovoSpacing.md))
            TextField(
                value = manualInput,
                onValueChange = { manualInput = it },
                label = stringResource(R.string.chatgpt_login_paste_hint),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

/**
 * 用 Custom Tabs 在 Eta 之上打开授权页：优先默认浏览器，不支持时退到已安装的 Chrome；
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
