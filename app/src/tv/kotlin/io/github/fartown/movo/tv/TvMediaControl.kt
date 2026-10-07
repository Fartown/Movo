package io.github.fartown.movo.tv

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService

/** Grants media-session access only. Notification contents are neither queried nor stored. */
class TvMediaAccessService : NotificationListenerService()

internal object TvMediaAccess {
    fun component(context: Context) = ComponentName(context, TvMediaAccessService::class.java)
    fun enabled(context: Context) = context.getSystemService(NotificationManager::class.java)
        .isNotificationListenerAccessGranted(component(context))
}
