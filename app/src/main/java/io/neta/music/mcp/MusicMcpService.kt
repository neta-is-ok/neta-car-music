package io.neta.music.mcp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.neta.music.MainActivity
import io.neta.music.MyMusicApp
import io.neta.music.R
import io.neta.music.buildAppNotification
import kotlinx.coroutines.launch

/** 等待奔奔 AI 的本机调用，和播放服务分开保活：没在播放时仍能点歌，不伪装成正在播放。 */
class MusicMcpService : Service() {
    private var server: MusicMcpServer? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as MyMusicApp
        try {
            val manager = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.mcp_channel), NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_mymusic)
                .setContentTitle(getString(R.string.mcp_notification_title))
                .setContentText(getString(R.string.mcp_notification_text))
                .setContentIntent(open).setOngoing(true).setSilent(true)
                .buildAppNotification()
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
            val created = MusicMcpServer(app)
            server = created
            created.start(60_000, false)
            app.musicMcp.serviceStarted()
        } catch (exception: Exception) {
            server?.stop()
            server = null
            app.musicMcp.serviceStopped(exception.message ?: "本机 MCP 服务启动失败")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        if (server != null) START_STICKY else START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        server?.stop()
        server = null
        val manager = (application as MyMusicApp).musicMcp
        // 保留启动失败的具体原因，正常停止时只更新运行状态。
        manager.serviceStopped(manager.state.value.serviceError)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "local_music_mcp"
        const val NOTIFICATION_ID = 18766
    }
}

/** 用户已开启服务时，开机解锁后恢复本机端点；不修改美式的 AI 开关或注册配置。 */
class MusicMcpBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val app = context.applicationContext as MyMusicApp
        app.appScope.launch {
            try { app.musicMcp.restoreIfEnabled() } finally { pending.finish() }
        }
    }
}
