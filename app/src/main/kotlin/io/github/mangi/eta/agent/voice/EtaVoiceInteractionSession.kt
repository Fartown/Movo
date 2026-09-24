package io.github.mangi.eta.agent.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import io.github.mangi.eta.agent.voice.session.VoiceEntry

/**
 * 系统数字助理的入口桥接。
 *
 * 系统会为本类创建 TYPE_VOICE_INTERACTION 窗口，但它不显示任何内容（setUiEnabled(false)）。
 * 本类运行在 :voice_session 进程，只把入口转交主进程：聊天页可见就地处理，
 * 否则由 [io.github.mangi.eta.ui.AgentConversationSheetActivity] 承载界面。
 */
internal class EtaVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_HIDE_FOR_FOREGROUND_OPERATION) {
                hide()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        setUiEnabled(false)
        context.registerReceiver(
            controlReceiver,
            IntentFilter(ACTION_HIDE_FOR_FOREGROUND_OPERATION),
            Context.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onCreateContentView(): View = View(context)

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        VoiceEntry.startFromSystemEntry(context, autoListen = false)
    }

    override fun onBackPressed() {
        EtaAssistantVoiceService.dismiss(context)
        hide()
    }

    override fun onCloseSystemDialogs() {
        EtaAssistantVoiceService.dismiss(context)
        hide()
    }

    override fun onDestroy() {
        // 助手界面在主进程，生命周期独立；系统会话重建不代表用户关闭了助理。
        context.unregisterReceiver(controlReceiver)
        super.onDestroy()
    }

    internal companion object {
        private const val ACTION_HIDE_FOR_FOREGROUND_OPERATION =
            "io.github.mangi.eta.agent.voice.HIDE_FOR_FOREGROUND_OPERATION"

        fun requestHideForForegroundOperation(context: Context) {
            context.sendBroadcast(
                Intent(ACTION_HIDE_FOR_FOREGROUND_OPERATION)
                    .setPackage(context.packageName),
            )
        }
    }
}
