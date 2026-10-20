package io.github.fartown.movo.mediaprobe;

import android.service.notification.NotificationListenerService;

/** 只为取得 getActiveSessions 的资格（通知使用权），不读取任何通知。 */
public class ProbeListener extends NotificationListenerService {}
