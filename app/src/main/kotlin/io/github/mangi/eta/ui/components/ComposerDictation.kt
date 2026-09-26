package io.github.mangi.eta.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.EtaWakeWordService
import io.github.mangi.eta.agent.voice.asr.EtaAsrEngine
import io.github.mangi.eta.agent.voice.asr.EtaDictationController
import io.github.mangi.eta.ui.components.movo.MovoIconButton
import io.github.mangi.eta.ui.components.movo.MovoPillButton
import io.github.mangi.eta.ui.components.movo.movoElevation
import io.github.mangi.eta.ui.components.movo.movoSurface
import io.github.mangi.eta.ui.theme.MovoColors
import io.github.mangi.eta.ui.theme.MovoElevation
import io.github.mangi.eta.ui.theme.MovoIcon
import io.github.mangi.eta.ui.theme.MovoIconData
import io.github.mangi.eta.ui.theme.MovoIcons
import io.github.mangi.eta.ui.theme.MovoRadius
import io.github.mangi.eta.ui.theme.MovoSize
import io.github.mangi.eta.ui.theme.MovoSpacing
import io.github.mangi.eta.ui.theme.MovoTypography
import top.yukonga.miuix.kmp.basic.Text

/** 打开「语音与唤醒词」设置；由 App 根提供（对话浮层里打开主界面对应页）。 */
internal val LocalOpenVoiceSettings = staticCompositionLocalOf<(() -> Unit)?> { null }

/** 输入框上方的异常提示（`Composer/Notice`，规范 8.5）。 */
internal data class ComposerNotice(
    val icon: MovoIconData,
    val title: String,
    val description: String,
    val actionLabel: String?,
    val action: (() -> Unit)?,
)

/**
 * 语音输入 `Composer/Dictation`（规范 8.2）：说的话直接写进输入框（光标跟随），可编辑后再发。
 * 停止：再点麦克风，或静默约 2 秒自动停止；正在听时直接点发送 = 先停止再发送。
 * 与唤醒词互斥：开始时暂停唤醒，结束后恢复。与语音对话互斥由调用方保证（语音对话中不显示麦克风）。
 */
@Stable
internal class ComposerDictationState(
    private val context: Context,
    private val textState: TextFieldState,
) {
    var listening by mutableStateOf(false)
        private set
    var notice by mutableStateOf<ComposerNotice?>(null)

    /** 输入框里还没被引擎确认的那一段 [start, end)（显示为三级色）；不在听写或已全部确认时为 null。 */
    var pendingRange by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    /** 刚被引擎确认的一段 [start, end)：颜色三级色 → 主色、模糊 2 → 0 过渡 `fast`（规范 9.3.2 Q2）。 */
    var confirmingRange by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    /** 每确认一段加一，界面据此重新播放确认过渡。 */
    var confirmGeneration by mutableIntStateOf(0)
        private set

    private fun markConfirmed(start: Int, end: Int) {
        if (end <= start) return
        confirmingRange = start to end
        confirmGeneration++
    }

    /** 麦克风电平 0–1（未平滑），麦克风外圈随它脉动。 */
    var level by mutableFloatStateOf(0f)
        private set

    /** 缺语音凭据时「去设置」的去处，由界面层每次组合时更新。 */
    var openVoiceSettings: (() -> Unit)? = null

    private val controller = EtaDictationController(context)
    private var baseText = ""
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val silenceStop = Runnable { if (listening) stop() }

    fun start() {
        if (listening) return
        notice = null
        baseText = textState.text.toString()
        listening = true
        EtaWakeWordService.pauseWake(context)
        controller.start(object : EtaAsrEngine.Listener {
            // 豆包听写：partial 是本次会话到目前为止的全文（在网络线程回调），final 在结束时给出全文（主线程）。
            override fun onPartial(text: String) = onPartial(text, text.length)

            override fun onPartial(text: String, confirmedLength: Int) {
                handler.post {
                    if (!listening) return@post
                    textState.setTextAndPlaceCursorAtEnd(baseText + text)
                    val start = baseText.length + confirmedLength.coerceIn(0, text.length)
                    val end = baseText.length + text.length
                    val previousStart = pendingRange?.first
                    if (previousStart != null && start > previousStart) markConfirmed(previousStart, start)
                    pendingRange = if (end > start) start to end else null
                    armSilenceStop()
                }
            }

            override fun onFinal(text: String) {
                handler.post {
                    textState.setTextAndPlaceCursorAtEnd(baseText + text)
                    pendingRange?.let { markConfirmed(it.first, (baseText + text).length) }
                    pendingRange = null
                }
            }

            override fun onLevel(level: Float) {
                if (listening) this@ComposerDictationState.level = level
            }

            override fun onError(message: String) {
                notice = errorNotice(message)
            }

            override fun onEnded() {
                finish()
            }
        })
        armSilenceStop()
    }

    /** 停止听写，已识别的文字保留在输入框里。 */
    fun stop() {
        if (!listening) return
        controller.stop(submitFinal = true)
    }

    fun cancel() {
        confirmingRange = null
        controller.cancel()
        finish()
    }

    private fun finish() {
        handler.removeCallbacks(silenceStop)
        pendingRange = null
        level = 0f
        if (!listening) return
        listening = false
        EtaWakeWordService.resumeWake(context)
    }

    private fun armSilenceStop() {
        handler.removeCallbacks(silenceStop)
        handler.postDelayed(silenceStop, SILENCE_STOP_MS)
    }

    private fun errorNotice(message: String): ComposerNotice {
        val missingCredentials = message == context.getString(R.string.voice_doubao_credentials_required)
        return if (missingCredentials) {
            ComposerNotice(
                icon = MovoIcons.Mic,
                title = context.getString(R.string.movo_notice_voice_title),
                description = context.getString(R.string.movo_notice_voice_desc),
                actionLabel = context.getString(R.string.movo_notice_settings),
                action = openVoiceSettings,
            )
        } else {
            ComposerNotice(MovoIcons.MicOff, context.getString(R.string.movo_notice_error_title), message, null, null)
        }
    }

    private companion object {
        /** 规范 8.2：静默约 2 秒自动停止（从最后一次识别到文字起算）。 */
        const val SILENCE_STOP_MS = 2_000L
    }
}

@Composable
internal fun rememberComposerDictation(textState: TextFieldState): ComposerDictationState {
    val context = LocalContext.current
    val state = remember(textState) { ComposerDictationState(context.applicationContext, textState) }
    DisposableEffect(state) { onDispose { state.cancel() } }
    return state
}

/**
 * 麦克风按钮的点击逻辑：有权限直接开始 / 停止；没有权限先申请，被拒绝后在输入框上方提示「去开启」。
 */
@Composable
internal fun rememberDictationToggle(state: ComposerDictationState): () -> Unit {
    val context = LocalContext.current
    val openVoiceSettings = LocalOpenVoiceSettings.current
    androidx.compose.runtime.SideEffect { state.openVoiceSettings = openVoiceSettings }
    val micDeniedNotice = ComposerNotice(
        icon = MovoIcons.MicOff,
        title = stringResource(R.string.movo_notice_mic_title),
        description = stringResource(R.string.movo_notice_mic_desc),
        actionLabel = stringResource(R.string.movo_notice_enable),
        action = { openAppDetails(context) },
    )
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) state.start() else state.notice = micDeniedNotice
    }
    return {
        when {
            state.listening -> state.stop()
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ->
                state.start()
            else -> launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

private fun openAppDetails(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * `Composer/Notice` 提示条：输入框上方 8；图标底 32 + 标题 + 说明 +「去开启 / 去设置」，同时最多一条。
 */
@Composable
internal fun ComposerNoticeBar(
    notice: ComposerNotice,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(MovoRadius.lg)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .movoElevation(MovoElevation.Card, shape)
            .movoSurface(shape)
            .padding(start = MovoSpacing.sm, end = MovoSpacing.xs, top = MovoSpacing.sm, bottom = MovoSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(MovoSize.controlSmall).clip(RoundedCornerShape(MovoRadius.xs)).background(MovoColors.roseBg),
            contentAlignment = Alignment.Center,
        ) {
            MovoIcon(notice.icon, null, size = MovoSize.iconSmall, tint = MovoColors.roseFg)
        }
        Spacer(Modifier.width(MovoSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(notice.title, style = MovoTypography.labelMedium, color = MovoColors.textPrimary, maxLines = 1)
            Text(notice.description, style = MovoTypography.labelRegular, color = MovoColors.textSecondary, maxLines = 2)
        }
        val action = notice.action
        if (notice.actionLabel != null && action != null) {
            Spacer(Modifier.width(MovoSpacing.sm))
            MovoPillButton(label = notice.actionLabel, onClick = { action(); onDismiss() })
        }
        MovoIconButton(
            icon = MovoIcons.X,
            contentDescription = stringResource(R.string.action_close),
            onClick = onDismiss,
            iconSize = MovoSize.iconSmall,
            tint = MovoColors.textSecondary,
        )
    }
}
