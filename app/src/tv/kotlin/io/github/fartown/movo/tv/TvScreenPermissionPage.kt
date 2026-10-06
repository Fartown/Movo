package io.github.fartown.movo.tv

import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.focusable
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun TvScreenPermissionPage() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(TvAssistantScreenCapture.status(context)) }
    var notice by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    val connected by TvAssistantScreenCapture.connected.collectAsState()
    LaunchedEffect(connected) { status = TvAssistantScreenCapture.status(context) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) status = TvAssistantScreenCapture.status(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    TvBody(status)
    TvHint("将 Movo 设为默认数字助理，并允许读取屏幕内容和截图。助手执行任务时据此识别当前画面。")
    TvButton("选择默认数字助理", primary = true) {
        val candidates = listOf(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        val opened = candidates.any { intent ->
            runCatching { context.startActivity(intent); true }.getOrDefault(false)
        }
        notice = if (opened) "请选择 Movo，并允许屏幕内容和截图，完成后返回。"
        else "本机没有提供默认数字助理设置页。尚未取得屏幕读取授权。"
    }
    if (TvAssistantPermission.canConfigure(context)) {
        TvButton(if (TvAssistantPermission.enabled(context)) "关闭屏幕助理与自动恢复" else "启用 Movo 屏幕助理与自动恢复") {
            preview = null
            notice = if (TvAssistantPermission.enabled(context)) TvAssistantPermission.disable(context)
                else TvAssistantPermission.enable(context)
            status = TvAssistantScreenCapture.status(context)
        }
        if (TvAssistantScreenCapture.isSelected(context) && !TvAssistantScreenCapture.screenContentAllowed(context)) {
            TvButton("允许屏幕内容和截图") {
                preview = null
                notice = TvAssistantPermission.allowScreenContent(context)
                status = TvAssistantScreenCapture.status(context)
            }
        }
    }
    TvButton(if (busy) "正在获取当前画面" else "静音验证截图", enabled = !busy) {
        preview = null
        status = TvAssistantScreenCapture.status(context)
        if (!TvAssistantScreenCapture.available) notice = TvAssistantScreenCapture.status(context)
        else scope.launch {
            busy = true
            try {
                val result = withContext(Dispatchers.IO) { TvAssistantScreenCapture.capture() }
                preview = result.bitmap
                notice = result.bitmap?.let { "已取得新截图：${it.width} × ${it.height}" }
                    ?: when (result.failure) {
                        "ASSISTANT_PERMISSION_REQUIRED" -> "尚未取得屏幕读取授权，请先启用屏幕助理。"
                        "ASSISTANT_WINDOW_STILL_VISIBLE" -> "助手页面未能收起，没有取得目标画面，请重试。"
                        "ASSIST_SCREENSHOT_TIMEOUT" -> "系统截图响应超时，没有取得新画面，请重试。"
                        "ASSISTANT_DISCONNECTED" -> "系统助理连接已断开，请重新启用屏幕助理。"
                        else -> "系统或当前应用未允许截图，没有取得新画面。"
                    }
            } finally {
                busy = false
                context.startActivity(Intent(context, TvMainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
        }
    }
    TvHint("验证时会暂时收起 Movo，获取前一个电视页面的截图，然后返回这里显示结果。")
    if (notice.isNotBlank()) TvBody(notice, secondary = true)
    preview?.let { Image(it.asImageBitmap(), "本次电视截图", Modifier.fillMaxWidth().heightIn(max = 320.dp).focusable()) }
}
