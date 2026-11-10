package io.neta.music

import android.app.Notification
import android.os.Bundle
import androidx.core.app.NotificationCompat

/** 所有通知统一携带车机 SystemUI 的隐藏标记，其他设备会忽略此 extras。 */
internal fun NotificationCompat.Builder.addAppNotificationExtras(): NotificationCompat.Builder =
    addExtras(Bundle().apply { putString("NOTIFICATION_TYPE", "RUNNING") })

internal fun NotificationCompat.Builder.buildAppNotification(): Notification =
    addAppNotificationExtras().build()
