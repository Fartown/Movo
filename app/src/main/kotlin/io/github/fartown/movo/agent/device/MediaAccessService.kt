package io.github.fartown.movo.agent.device

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService

/**
 * 「播放控制」授权：只用来取得系统媒体会话（读播放状态、跳转、切集），不读取、不保存通知内容。
 * 和通知历史（[AgentNotificationHistoryService]）分开授权：授权播放控制不会顺带开始记录通知。
 */
class MediaAccessService : NotificationListenerService() {
    companion object {
        fun component(context: Context) = ComponentName(context, MediaAccessService::class.java)
        fun enabled(context: Context): Boolean = context.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(component(context))
    }
}
