package io.neta.music.playback

import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.DeviceInfo
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup

/**
 * 会话元数据协议 v1。Media3 从 MediaMetadata.extras 读取；系统 MediaController 从同名
 * MediaMetadata 键读取。数值统一用 Long，避免 Media3 的 legacy 转换丢弃 Boolean。
 *
 * VERSION=1；FAVORITE=0/1；REPEAT_MODE=0 顺序、1 单曲循环、2 列表循环；
 * SHUFFLE_ENABLED=0/1；PLAYBACK_MODE=off/all/one/shuffle。
 * 收藏同时通过标准 userRating=HeartRating 暴露，系统对应 USER_RATING / RATING_HEART。
 */
object SessionMetadata {
    const val VERSION = "io.neta.music.metadata.VERSION"
    const val FAVORITE = "io.neta.music.metadata.FAVORITE"
    const val REPEAT_MODE = "io.neta.music.metadata.REPEAT_MODE"
    const val SHUFFLE_ENABLED = "io.neta.music.metadata.SHUFFLE_ENABLED"
    const val PLAYBACK_MODE = "io.neta.music.metadata.PLAYBACK_MODE"
}

/** 只装饰会话的读取和通知，不替换音源或修改队列，HLS / DRM 播放也能即时更新。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class SessionMetadataPlayer(
    player: Player,
    private var favorites: Set<String>?,
) : ForwardingPlayer(player) {
    private val listeners = linkedMapOf<Player.Listener, Player.Listener>()

    override fun getMediaMetadata(): MediaMetadata {
        val original = super.getMediaMetadata()
        val key = currentMediaItem?.mediaId
        val favorite = key?.let { favorites?.contains(it) }
        val extras = original.extras?.let(::Bundle) ?: Bundle()
        extras.putLong(SessionMetadata.VERSION, 1L)
        if (favorite != null) extras.putLong(SessionMetadata.FAVORITE, if (favorite) 1L else 0L)
        extras.putLong(SessionMetadata.REPEAT_MODE, repeatMode.toLong())
        extras.putLong(SessionMetadata.SHUFFLE_ENABLED, if (shuffleModeEnabled) 1L else 0L)
        extras.putString(SessionMetadata.PLAYBACK_MODE, when {
            shuffleModeEnabled -> "shuffle"
            repeatMode == Player.REPEAT_MODE_ONE -> "one"
            repeatMode == Player.REPEAT_MODE_ALL -> "all"
            else -> "off"
        })
        val modeLabel = when (repeatMode) {
            Player.REPEAT_MODE_ONE -> "单曲循环"
            Player.REPEAT_MODE_ALL -> "列表循环"
            else -> if (shuffleModeEnabled) "不循环" else "顺序播放"
        } + if (shuffleModeEnabled) " · 随机播放" else ""
        // 标准描述保留原文并补充可读模式；只显示标准字段的车机也能看到模式。
        // Media3 legacy 桥接会忽略只有 extras 改变的元数据，这段真实描述同时确保原生回调及时刷新。
        val description = listOfNotNull(original.description?.toString()?.takeIf { it.isNotBlank() }, "播放模式：$modeLabel")
            .joinToString("\n")
        return original.buildUpon()
            .setUserRating(if (favorite == null) HeartRating() else HeartRating(favorite))
            .setDescription(description)
            .setExtras(extras)
            .build()
    }

    fun updateFavorites(value: Set<String>) {
        val key = currentMediaItem?.mediaId
        val previous = key?.let { favorites?.contains(it) }
        favorites = value
        if (previous == key?.let(value::contains)) return
        val metadata = mediaMetadata
        val events = Player.Events(FlagSet.Builder().add(Player.EVENT_MEDIA_METADATA_CHANGED).build())
        listeners.keys.toList().forEach { listener ->
            listener.onMediaMetadataChanged(metadata)
            listener.onEvents(this, events)
        }
    }

    override fun addListener(listener: Player.Listener) {
        if (listener in listeners) return
        val bridge = object : SessionPlayerListener(listener) {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                listener.onMediaMetadataChanged(this@SessionMetadataPlayer.mediaMetadata)
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) {
                    // MediaMetadata.equals 不比较 extras 内容，必须明确通知会话发布新的专有字段。
                    if (!events.contains(Player.EVENT_MEDIA_METADATA_CHANGED)) {
                        listener.onMediaMetadataChanged(this@SessionMetadataPlayer.mediaMetadata)
                    }
                    val flags = FlagSet.Builder().add(Player.EVENT_MEDIA_METADATA_CHANGED)
                    for (index in 0 until events.size()) flags.add(events[index])
                    listener.onEvents(this@SessionMetadataPlayer, Player.Events(flags.build()))
                } else {
                    listener.onEvents(this@SessionMetadataPlayer, events)
                }
            }
        }
        listeners[listener] = bridge
        super.addListener(bridge)
    }

    override fun removeListener(listener: Player.Listener) {
        val bridge = listeners.remove(listener) ?: return
        super.removeListener(bridge)
    }
}

/** Java default 回调不会由 Kotlin 的 by 自动转发，必须明确保留完整的播放器通知。 */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private open class SessionPlayerListener(private val target: Player.Listener) : Player.Listener {
    override fun onEvents(player: Player, events: Player.Events) = target.onEvents(player, events)
    override fun onTimelineChanged(timeline: Timeline, reason: Int) = target.onTimelineChanged(timeline, reason)
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = target.onMediaItemTransition(mediaItem, reason)
    override fun onTracksChanged(tracks: Tracks) = target.onTracksChanged(tracks)
    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = target.onMediaMetadataChanged(mediaMetadata)
    override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) = target.onPlaylistMetadataChanged(mediaMetadata)
    override fun onIsLoadingChanged(isLoading: Boolean) = target.onIsLoadingChanged(isLoading)
    override fun onLoadingChanged(isLoading: Boolean) = target.onLoadingChanged(isLoading)
    override fun onAvailableCommandsChanged(availableCommands: Player.Commands) = target.onAvailableCommandsChanged(availableCommands)
    override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) = target.onTrackSelectionParametersChanged(parameters)
    override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) = target.onPlayerStateChanged(playWhenReady, playbackState)
    override fun onPlaybackStateChanged(playbackState: Int) = target.onPlaybackStateChanged(playbackState)
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = target.onPlayWhenReadyChanged(playWhenReady, reason)
    override fun onPlaybackSuppressionReasonChanged(reason: Int) = target.onPlaybackSuppressionReasonChanged(reason)
    override fun onIsPlayingChanged(isPlaying: Boolean) = target.onIsPlayingChanged(isPlaying)
    override fun onRepeatModeChanged(repeatMode: Int) = target.onRepeatModeChanged(repeatMode)
    override fun onShuffleModeEnabledChanged(enabled: Boolean) = target.onShuffleModeEnabledChanged(enabled)
    override fun onPlayerError(error: PlaybackException) = target.onPlayerError(error)
    override fun onPlayerErrorChanged(error: PlaybackException?) = target.onPlayerErrorChanged(error)
    override fun onPositionDiscontinuity(reason: Int) = target.onPositionDiscontinuity(reason)
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) =
        target.onPositionDiscontinuity(oldPosition, newPosition, reason)
    override fun onPlaybackParametersChanged(parameters: PlaybackParameters) = target.onPlaybackParametersChanged(parameters)
    override fun onSeekBackIncrementChanged(increment: Long) = target.onSeekBackIncrementChanged(increment)
    override fun onSeekForwardIncrementChanged(increment: Long) = target.onSeekForwardIncrementChanged(increment)
    override fun onMaxSeekToPreviousPositionChanged(position: Long) = target.onMaxSeekToPreviousPositionChanged(position)
    override fun onAudioSessionIdChanged(audioSessionId: Int) = target.onAudioSessionIdChanged(audioSessionId)
    override fun onAudioAttributesChanged(attributes: AudioAttributes) = target.onAudioAttributesChanged(attributes)
    override fun onVolumeChanged(volume: Float) = target.onVolumeChanged(volume)
    override fun onSkipSilenceEnabledChanged(enabled: Boolean) = target.onSkipSilenceEnabledChanged(enabled)
    override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) = target.onDeviceInfoChanged(deviceInfo)
    override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) = target.onDeviceVolumeChanged(volume, muted)
    override fun onVideoSizeChanged(videoSize: VideoSize) = target.onVideoSizeChanged(videoSize)
    override fun onSurfaceSizeChanged(width: Int, height: Int) = target.onSurfaceSizeChanged(width, height)
    override fun onRenderedFirstFrame() = target.onRenderedFirstFrame()
    override fun onCues(cues: List<Cue>) = target.onCues(cues)
    override fun onCues(cueGroup: CueGroup) = target.onCues(cueGroup)
    override fun onMetadata(metadata: Metadata) = target.onMetadata(metadata)
}
