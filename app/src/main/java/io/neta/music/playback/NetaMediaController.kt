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
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
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
    private var lastNextTapAt = 0L
    private var lastPrevTapAt = 0L
    private var pendingNext: Runnable? = null
    private var pendingPrev: Runnable? = null

    var isMuted: Boolean? = null
        private set
    val isPausedForMute: Boolean get() = resumePending

    /** 方控双击设置，由服务从设置流同步；间隔改了只影响下一次按键，等着的单击任务照旧跑完。 */
    var doubleNextEnabled = true
        private set
    var doubleTapWindowMs = 800
        private set
    var doublePrevRepeatEnabled = true
        private set

    fun updateDoubleTap(nextEnabled: Boolean, windowMs: Int, prevRepeat: Boolean) {
        doubleNextEnabled = nextEnabled
        doubleTapWindowMs = windowMs
        doublePrevRepeatEnabled = prevRepeat
        // 关掉开关就不再等第二击，把还挂着的单击任务当场执行，切歌立刻恢复不用等
        if (!nextEnabled) flushPendingNext()
        if (!prevRepeat) flushPendingPrev()
    }

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
        // 静音键一个音频区一条（VOLUME_GROUP_MUTE/<zoneId>/<groupId>），且按用户分库：这台车主副屏分用户，
        // 主屏用户只会看到主区键 /0/0（方向盘、主屏面板静音写的），副屏用户只会看到副区键 /1/0（副屏面板
        // 静音写的），正好各管各区；主屏静音静不到副屏的声音，副屏不跟着暂停也是对的。区号多盯几个是为了
        // 不做判断：哪个键出现在自己库里都能接住（方控那条 SWC 广播只发主用户，副屏只能靠这儿）。
        MEDIA_MUTE_KEYS.forEach { key ->
            context.contentResolver.registerContentObserver(Settings.System.getUriFor(key), false, muteObserver)
        }
        val initial = MEDIA_MUTE_KEYS.mapNotNull { key -> readMute(key)?.let { "$key=$it" } }
        if (initial.isNotEmpty()) AppLog.i("NetaMedia", "静音键初值：${initial.joinToString()}")
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
        pendingNext?.let(handler::removeCallbacks)
        pendingPrev?.let(handler::removeCallbacks)
        pendingNext = null
        pendingPrev = null
        lastNextTapAt = 0
        lastPrevTapAt = 0
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
            KeyEvent.KEYCODE_MEDIA_NEXT -> if (doubleNextEnabled) tapNext() else current.seekToNextMediaItem()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (doublePrevRepeatEnabled) tapPrevious() else current.seekToPreviousMediaItem()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> current.pause()
            KeyEvent.KEYCODE_MEDIA_PLAY -> play(current)
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (current.playWhenReady) current.pause() else play(current)
        }
        AppLog.i("NetaMedia", "方控 keyCode=$key")
    }

    /**
     * 方控右键（媒体下一曲）双击判定：第一击先记账等一个间隔，间隔内来了第二击就改走双击动作；
     * 没等到第二击，到点补执行单击动作。所以单击切歌会晚一个间隔才发生，这是判定双击的必要代价。
     * 补执行时重新过 [canControl]：等的那段时间里可能已经让出了媒体。
     */
    private fun tapNext() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNextTapAt <= doubleTapWindowMs) {
            lastNextTapAt = 0
            pendingNext?.let(handler::removeCallbacks)
            pendingNext = null
            val current = player ?: return
            cancelResume()
            if (current.playWhenReady) current.pause() else play(current)
            AppLog.i("NetaMedia", "方控右键双击，切换播放/暂停")
            return
        }
        lastNextTapAt = now
        pendingNext?.let(handler::removeCallbacks)
        pendingNext = Runnable {
            pendingNext = null
            if (!canControl()) return@Runnable
            cancelResume()
            player?.seekToNextMediaItem()
        }.also { handler.postDelayed(it, doubleTapWindowMs.toLong()) }
    }

    /** 方控左键（媒体上一曲）双击，同 [tapNext]；双击动作换成循环切换循环模式，顺序和播放页的循环按钮一致。 */
    private fun tapPrevious() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPrevTapAt <= doubleTapWindowMs) {
            lastPrevTapAt = 0
            pendingPrev?.let(handler::removeCallbacks)
            pendingPrev = null
            val current = player ?: return
            cancelResume()
            current.repeatMode = when (current.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            // 开车时看不清播放页的循环按钮状态，切完给个提示
            val label = when (current.repeatMode) {
                Player.REPEAT_MODE_ALL -> "列表循环"
                Player.REPEAT_MODE_ONE -> "单曲循环"
                else -> "顺序播放"
            }
            Toast.makeText(context, "循环模式：$label", Toast.LENGTH_SHORT).show()
            AppLog.i("NetaMedia", "方控左键双击，切换循环模式")
            return
        }
        lastPrevTapAt = now
        pendingPrev?.let(handler::removeCallbacks)
        pendingPrev = Runnable {
            pendingPrev = null
            if (!canControl()) return@Runnable
            cancelResume()
            player?.seekToPreviousMediaItem()
        }.also { handler.postDelayed(it, doubleTapWindowMs.toLong()) }
    }

    private fun flushPendingNext() {
        val pending = pendingNext ?: return
        handler.removeCallbacks(pending)
        pendingNext = null
        pending.run()
    }

    private fun flushPendingPrev() {
        val pending = pendingPrev ?: return
        handler.removeCallbacks(pending)
        pendingPrev = null
        pending.run()
    }

    private fun play(player: Player) {
        when (player.playbackState) {
            Player.STATE_IDLE -> player.prepare()
            Player.STATE_ENDED -> player.seekToDefaultPosition()
        }
        player.play()
    }

    private fun syncMute() {
        // 任一区报静音就按静音算；各区的键都不存在（不是这台车机）时保持未知
        val values = MEDIA_MUTE_KEYS.map(::readMute)
        val muted = when {
            values.any { it == true } -> true
            values.any { it == false } -> false
            else -> null
        }
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

    private fun readMute(key: String): Boolean? = runCatching {
        when (Settings.System.getString(context.contentResolver, key)) {
            "1" -> true
            "0" -> false
            else -> null
        }
    }.getOrNull()

    private companion object {
        const val ACTION_MEDIA_KEY = "hozon.intent.action.SWC_MEDIA_KEY"
        const val EXTRA_KEY_CODE = "KeyEvent.keyCode"
        val MEDIA_MUTE_KEYS = (0..3).map { zone -> "android.car.VOLUME_GROUP_MUTE/$zone/0" }
        val MEDIA_KEYS = setOf(
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        )
    }
}
