package io.neta.music.playback

import android.os.Bundle
import androidx.media3.common.Player
import io.neta.music.MyMusicApp
import io.neta.music.data.Song
import kotlinx.serialization.json.Json

/** 广播与本机 MCP 的同一份请求，不依赖具体传输方式。 */
internal data class PlaybackControlRequest(
    val command: String,
    val keyword: String = "",
    val source: String = "",
    val songId: String = "",
    val songJson: String = "",
    val title: String = "",
    val artist: String = "",
    val mode: String = "",
)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object PlaybackControl {
    private val json = Json { ignoreUnknownKeys = true }

    fun success(status: String = "completed") = Bundle().apply {
        putBoolean("success", true)
        putString("status", status)
    }

    fun failure(error: Exception) = Bundle().apply {
        putBoolean("success", false)
        putString("status", "error")
        putString("message", error.message ?: "操作失败")
    }

    suspend fun execute(app: MyMusicApp, request: PlaybackControlRequest): Bundle {
        val vm = app.musicViewModel
        val command = request.command
        require(command in setOf(
            "favorite", "unfavorite", "toggle_favorite", "query_favorite",
            "set_mode", "cycle_mode", "query_mode", "play",
        )) { "未知 command：$command" }
        if (command == "play") {
            val source = request.source.trim()
            val id = request.songId.trim()
            val payload = request.songJson
            val song = when {
                payload.isNotBlank() -> json.decodeFromString<Song>(payload)
                id.isNotBlank() -> {
                    require(source.isNotBlank()) { "使用 song_id 时请提供 source" }
                    Song(id = id, source = source, name = request.title,
                        artist = request.artist)
                }
                else -> null
            }
            if (song != null) require(song.id.isNotBlank() && song.source.isNotBlank()) {
                "歌曲信息需要 id 和 source"
            }
            check(vm.playFromBroadcast(song, request.keyword.trim(), source)) {
                "播放尚未开始，请稍后查询状态"
            }
        }
        val song = vm.currentSongForBroadcast()
        val player = vm.awaitController() ?: error("播放器尚未就绪")
        when (command) {
            "favorite", "unfavorite", "toggle_favorite" -> {
                check(song != null) { "当前没有播放的歌曲" }
                vm.updateFavorite(song, when (command) {
                    "favorite" -> true
                    "unfavorite" -> false
                    else -> null
                })
            }
            "set_mode", "cycle_mode" -> {
                val mode = if (command == "cycle_mode") {
                    when (mode(player)) {
                        "off" -> "all"
                        "all" -> "one"
                        "one" -> "shuffle"
                        else -> "off"
                    }
                } else request.mode
                require(mode in setOf("off", "all", "one", "shuffle")) { "mode 应为 off / all / one / shuffle" }
                player.repeatMode = when (mode) {
                    "one" -> Player.REPEAT_MODE_ONE
                    "all", "shuffle" -> Player.REPEAT_MODE_ALL
                    else -> Player.REPEAT_MODE_OFF
                }
                player.shuffleModeEnabled = mode == "shuffle"
            }
        }
        return success().apply {
            putBoolean("has_song", song != null)
            if (song != null) {
                putString("source", song.source)
                putString("song_id", song.id)
                putString("title", song.title)
                putString("artist", song.artist)
                putBoolean("favorite", app.database.dao().songsOnce("favorite").any { it.key == song.key })
            }
            putString("mode", mode(player))
            putString("repeat_mode", when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> "one"
                Player.REPEAT_MODE_ALL -> "all"
                else -> "off"
            })
            putBoolean("shuffle", player.shuffleModeEnabled)
            putBoolean("playing", player.isPlaying)
        }
    }

    private fun mode(player: Player): String = when {
        player.shuffleModeEnabled -> "shuffle"
        player.repeatMode == Player.REPEAT_MODE_ONE -> "one"
        player.repeatMode == Player.REPEAT_MODE_ALL -> "all"
        else -> "off"
    }

}
