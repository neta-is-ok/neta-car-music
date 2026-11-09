package io.neta.music.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.provider.Settings
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import io.neta.music.AppLog

/** 服务拥有的方控和静音联动；只读系统状态，不申请额外焦点或改变系统静音。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class NetaMediaController(private val context: Context, private val handler: Handler) {
    private var player: Player? = null
    private var started = false
    private var ownsMedia = false
    private var resumePending = false
    private var lastKnownMute: Boolean? = null
    private var playbackObserver: PlaybackObserver? = null

    var isMuted: Boolean? = null
        private set
    val isPausedForMute: Boolean get() = resumePending

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_MEDIA_KEY) return
            @Suppress("DEPRECATION")
            val key = runCatching { intent.extras?.get(EXTRA_KEY_CODE) as? Int }.getOrNull() ?: return
            handleKey(key)
        }
    }
    private val muteObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = syncMute()
    }
    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) {
                ownsMedia = true
            } else if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                relinquishMedia()
            } else {
                // 暂停后音频输出可能稍晚才停止，记下本应用刚才的输出，避免把它认成其他播放器。
                playbackObserver?.rememberOwnPlayback()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                ownsMedia = true
                playbackObserver?.rememberOwnPlayback()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) cancelResume()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (timeline.isEmpty) relinquishMedia()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) cancelResume()
        }
    }

    fun start(player: Player) {
        attach(player)
        if (started) return
        started = true
        if (Build.VERSION.SDK_INT >= 26) {
            playbackObserver = PlaybackObserver(context, handler) {
                val current = this.player ?: return@PlaybackObserver
                if (current.isPlaying) {
                    playbackObserver?.rememberOwnPlayback()
                } else if (!current.playWhenReady && playbackObserver?.hasOtherMedia() == true) {
                    relinquishMedia()
                }
            }.also { it.start() }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(ACTION_MEDIA_KEY), ContextCompat.RECEIVER_EXPORTED,
        )
        context.contentResolver.registerContentObserver(Settings.System.getUriFor(MEDIA_MUTE_KEY), false, muteObserver)
        syncMute()
    }

    /** 交叉淡化更换主播放器时保留会话归属，不把正常交接当成用户换队列。 */
    fun attach(player: Player) {
        this.player?.removeListener(listener)
        this.player = player
        player.addListener(listener)
        if (player.playWhenReady) ownsMedia = true
    }

    fun cancelResume() {
        resumePending = false
    }

    fun stop() {
        if (started) {
            context.unregisterReceiver(receiver)
            context.contentResolver.unregisterContentObserver(muteObserver)
            playbackObserver?.stop()
        }
        playbackObserver = null
        started = false
        player?.removeListener(listener)
        player = null
        relinquishMedia()
    }

    private fun relinquishMedia() {
        ownsMedia = false
        cancelResume()
    }

    private fun canControl(): Boolean {
        val current = player ?: return false
        if (!current.playWhenReady && !current.isPlaying && playbackObserver?.hasOtherMedia() == true) {
            relinquishMedia()
        }
        return ownsMedia && current.mediaItemCount > 0
    }

    private fun handleKey(key: Int) {
        if (key !in MEDIA_KEYS || !canControl()) return
        val current = player ?: return
        cancelResume()
        when (key) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> current.seekToNextMediaItem()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> current.seekToPreviousMediaItem()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> current.pause()
            KeyEvent.KEYCODE_MEDIA_PLAY -> play(current)
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (current.playWhenReady) current.pause() else play(current)
        }
        AppLog.i("NetaMedia", "方控 keyCode=$key")
    }

    private fun play(player: Player) {
        when (player.playbackState) {
            Player.STATE_IDLE -> player.prepare()
            Player.STATE_ENDED -> player.seekToDefaultPosition()
        }
        player.play()
    }

    private fun syncMute() {
        val muted = runCatching {
            when (Settings.System.getString(context.contentResolver, MEDIA_MUTE_KEY)) {
                "1" -> true
                "0" -> false
                else -> null
            }
        }.getOrNull()
        isMuted = muted
        // 未知不是解除静音；保留上次有效状态，避免恢复读取后重复执行同一边沿。
        if (muted == null || muted == lastKnownMute) return
        lastKnownMute = muted
        val current = player ?: return
        if (muted) {
            if (!current.playWhenReady || current.mediaItemCount == 0 || !canControl()) return
            playbackObserver?.rememberOwnPlayback()
            resumePending = true
            current.pause()
            AppLog.i("NetaMedia", "媒体静音，自动暂停")
        } else if (resumePending) {
            val resume = canControl()
            cancelResume()
            if (resume) {
                current.play()
                AppLog.i("NetaMedia", "媒体解除静音，恢复播放")
            }
        }
    }

    /** 公开播放活动接口不需要媒体控制权限；只在本应用暂停时识别后来出现的媒体输出。 */
    @RequiresApi(26)
    private class PlaybackObserver(context: Context, private val handler: Handler, onChanged: () -> Unit) {
        private val audio = context.getSystemService(AudioManager::class.java)
        private var ownPlayback = emptySet<AudioPlaybackConfiguration>()
        private val callback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) = onChanged()
        }

        fun start() = audio?.registerAudioPlaybackCallback(callback, handler)
        fun stop() = audio?.unregisterAudioPlaybackCallback(callback)
        fun rememberOwnPlayback() {
            ownPlayback = activeMedia()
        }
        fun hasOtherMedia(): Boolean = activeMedia().any { it !in ownPlayback }

        private fun activeMedia(): Set<AudioPlaybackConfiguration> = audio?.activePlaybackConfigurations
            .orEmpty().filter {
                it.audioAttributes.usage == android.media.AudioAttributes.USAGE_MEDIA ||
                    it.audioAttributes.usage == android.media.AudioAttributes.USAGE_GAME
            }.toSet()
    }

    private companion object {
        const val ACTION_MEDIA_KEY = "hozon.intent.action.SWC_MEDIA_KEY"
        const val EXTRA_KEY_CODE = "KeyEvent.keyCode"
        const val MEDIA_MUTE_KEY = "android.car.VOLUME_GROUP_MUTE/0/0"
        val MEDIA_KEYS = setOf(
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        )
    }
}
