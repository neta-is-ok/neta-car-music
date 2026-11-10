package io.neta.music.data.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.neta.music.MyMusicApp
import io.neta.music.R
import io.neta.music.buildAppNotification
import io.neta.music.widget.WidgetLinks
import io.neta.music.widget.WidgetTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/** 下载由前台服务持有；收起弹窗、离开界面都不会取消，结束后通过通知回到更新卡片。 */
class UpdateDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var downloadJob: Job? = null
    private var started = false
    private var finished = false
    private val repository get() = (application as MyMusicApp).update

    override fun onCreate() {
        super.onCreate()
        try {
            val manager = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.update_download_channel), NotificationManager.IMPORTANCE_LOW))
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(repository.state.value, ongoing = true),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
            started = true
            scope.launch {
                repository.state.distinctUntilChangedBy { Triple(it.phase, (it.progress * 100).toInt(), it.phaseLabel) }
                    .collect { state ->
                        if (state.phase == UpdatePhase.Downloading || state.phase == UpdatePhase.Verifying) {
                            manager.notify(NOTIFICATION_ID, notification(state, ongoing = true))
                        }
                    }
            }
        } catch (exception: Exception) {
            repository.reportDownloadStartFailure("无法启动更新下载：${exception.message.orEmpty()}")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) return START_NOT_STICKY
        if (downloadJob?.isActive != true) {
            downloadJob = scope.launch {
                repository.download()
                finished = true
                ServiceCompat.stopForeground(this@UpdateDownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                val state = repository.state.value
                if (state.phase == UpdatePhase.Ready || state.phase == UpdatePhase.Error) {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, notification(state, ongoing = false))
                }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (!finished && downloadJob?.isActive == true) repository.cancelDownload()
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(state: UpdateUiState, ongoing: Boolean): android.app.Notification {
        val open = PendingIntent.getActivity(this, NOTIFICATION_ID,
            WidgetLinks.intent(this, WidgetTarget.Updates), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = when (state.phase) {
            UpdatePhase.Ready -> getString(R.string.update_download_ready)
            UpdatePhase.Error -> getString(R.string.update_download_failed)
            else -> getString(R.string.update_download_title)
        }
        val text = when (state.phase) {
            UpdatePhase.Ready -> "${state.info?.versionName.orEmpty()} · 已校验，点击查看并安装"
            UpdatePhase.Error -> state.error.ifBlank { "点击前往关于与更新重试" }
            else -> "${state.phaseLabel.ifBlank { "正在准备" }} ${(state.progress * 100).toInt()}%"
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mymusic)
            .setContentTitle(title).setContentText(text).setContentIntent(open)
            .setOngoing(ongoing).setAutoCancel(!ongoing).setOnlyAlertOnce(true).setSilent(true)
            .apply {
                if (ongoing) setProgress(100, (state.progress * 100).toInt(), state.phase != UpdatePhase.Downloading)
            }.buildAppNotification()
    }

    private companion object {
        const val CHANNEL = "app_update_download"
        const val NOTIFICATION_ID = 18767
    }
}
