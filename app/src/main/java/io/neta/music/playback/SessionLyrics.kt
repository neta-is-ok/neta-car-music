package io.neta.music.playback

import android.os.Bundle
import androidx.media3.common.MediaMetadata
import java.util.Locale

/** 歌词正文与取得它的 Song.key 始终一起传递；不依赖播放页是否正在订阅 UI 状态。 */
internal data class SessionLyricsSnapshot(
    val mediaId: String = "",
    val lyrics: String? = null,
    val artist: String = "",
)

/**
 * 歌词会话协议 v1，字段位于 MediaSession.sessionExtras，不只写 MediaMetadata.extras。
 * Media3 的 legacy metadata 去重不比较 extras 正文，无法可靠通知同曲晚到的歌词。
 * MEDIA_ID 必须与系统 metadata 的 MEDIA_ID 一致；仅 ready 携带完整 LRC，不按行截取。
 */
internal object SessionLyricsProtocol {
    const val VERSION = "io.neta.music.lyrics.VERSION"
    const val MEDIA_ID = "io.neta.music.lyrics.MEDIA_ID"
    const val STATE = "io.neta.music.lyrics.STATE"
    const val ARTIST = "io.neta.music.lyrics.ARTIST"
    const val LYRIC = "android.media.metadata.LYRIC"
    const val LOADING = "loading"
    const val READY = "ready"
    const val UNAVAILABLE = "unavailable"

    // UTF-16 正文最多约 256 KiB，为同一 Binder 事务的其他字段留出空间；超限不截断歌词。
    private const val MAX_LRC_CHARACTERS = 128 * 1024
    private val timedLine = Regex("(?m)^\\s*\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?]")

    fun publication(
        mediaId: String,
        metadata: MediaMetadata,
        snapshot: SessionLyricsSnapshot,
    ): SessionLyricsPublication {
        if (mediaId.isBlank()) return SessionLyricsPublication(mediaId, UNAVAILABLE)
        if (snapshot.mediaId != mediaId) return SessionLyricsPublication(mediaId, LOADING)
        val raw = snapshot.lyrics?.trim()?.removePrefix("\uFEFF")
            ?: return SessionLyricsPublication(mediaId, LOADING, artist = snapshot.artist)
        if (raw.length > MAX_LRC_CHARACTERS || !timedLine.containsMatchIn(raw)) {
            return SessionLyricsPublication(mediaId, UNAVAILABLE, artist = snapshot.artist)
        }
        // 仅补缺失的身份标签，原有不匹配标签留给消费端拒绝，不能强行改名掩盖串歌。
        val content = buildString {
            fun header(name: String, value: String?) {
                if (!value.isNullOrBlank() && !Regex("(?im)^\\[$name\\s*:").containsMatchIn(raw)) {
                    append('[').append(name).append(':')
                        .append(value.replace('\n', ' ').replace('\r', ' ')).append("]\n")
                }
            }
            header("ti", metadata.title?.toString())
            // “通知栏歌词”会把播放器 artist 换成当前歌词；这里始终使用真实歌曲歌手。
            header("ar", snapshot.artist)
            header("al", metadata.albumTitle?.toString())
            metadata.durationMs?.takeIf { it > 0L }?.let { duration ->
                header("length", String.format(Locale.ROOT, "%02d:%02d.%03d", duration / 60_000L,
                    duration / 1_000L % 60, duration % 1_000L))
            }
            append(raw)
        }
        return if (content.length <= MAX_LRC_CHARACTERS) {
            SessionLyricsPublication(mediaId, READY, content, snapshot.artist)
        } else {
            SessionLyricsPublication(mediaId, UNAVAILABLE, artist = snapshot.artist)
        }
    }
}

internal data class SessionLyricsPublication(
    val mediaId: String,
    val state: String,
    val lyrics: String? = null,
    val artist: String? = null,
) {
    fun extras(previous: Bundle): Bundle = Bundle(previous).apply {
        putLong(SessionLyricsProtocol.VERSION, 1L)
        putString(SessionLyricsProtocol.MEDIA_ID, mediaId)
        putString(SessionLyricsProtocol.STATE, state)
        remove(SessionLyricsProtocol.LYRIC)
        lyrics?.let { putString(SessionLyricsProtocol.LYRIC, it) }
        remove(SessionLyricsProtocol.ARTIST)
        artist?.let { putString(SessionLyricsProtocol.ARTIST, it) }
    }
}
