package io.neta.music.playback

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import io.neta.music.MyMusicApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 对外广播，无自定义权限、调用方白名单、登录检查或设置开关。
 *
 * 发往包 io.neta.music（或显式组件 .playback.PlaybackBroadcastReceiver）：
 * action = io.neta.music.action.CONTROL，字符串 extra command 为：
 * - favorite / unfavorite / toggle_favorite / query_favorite：当前歌曲的收藏。
 * - set_mode：字符串 mode = off / all / one / shuffle（顺序 / 列表循环 / 单曲循环 / 随机）。
 * - cycle_mode：依次轮换上述四种模式；query_mode：只查询。
 * - play：keyword 搜索后播放首个结果，可带 source 指定平台；也可传 source + song_id，
 *   或 song_json（data.Song 的 JSON，保留平台需要的 extra 等信息）。ID 方式可带 title / artist。
 *
 * 结果广播默认 action = io.neta.music.action.RESULT；reply_action / reply_package 可指定回传目标。
 * request_id 原样回传。每次结果含 command、success、status（completed / error / accepted）；
 * 状态含 has_song、song_id、source、title、artist、favorite、mode、repeat_mode、shuffle、playing。
 * 没有当前歌曲时 has_song=false，不带歌曲信息和 favorite。失败含 message。
 *
 * 有序广播同时以 resultExtras 和 JSON resultData 返回，resultCode=-1 成功、0 失败。
 * play 的有序结果为 accepted；联网及播放结果稍后以结果广播返回 completed / error。
 * 接收方应先动态注册结果接收器；reply_package 也可用于发往声明在清单中的接收器。
 *
 * 示例：adb shell am broadcast -a io.neta.music.action.CONTROL -p io.neta.music
 * --es command set_mode --es mode one
 */
class PlaybackBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PlaybackBroadcast.ACTION_CONTROL) return
        val ordered = isOrderedBroadcast
        val pending = goAsync()
        val app = context.applicationContext as MyMusicApp
        app.appScope.launch {
            try {
                val result = try {
                    if (intent.getStringExtra("command") == "play") {
                        // 搜索 / 取流可能超过接收器的存活时间，交给现有播放服务，不在这里挂着 goAsync。
                        ContextCompat.startForegroundService(
                            app,
                            Intent(intent).setClass(app, PlaybackService::class.java),
                        )
                        PlaybackControl.success("accepted")
                    } else {
                        PlaybackBroadcast.execute(app, intent)
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    PlaybackControl.failure(error)
                }
                PlaybackBroadcast.reply(app, intent, result)
                if (ordered) {
                    pending.setResultCode(if (result.getBoolean("success")) Activity.RESULT_OK else Activity.RESULT_CANCELED)
                    pending.setResultExtras(result)
                    pending.setResultData(JSONObject(result.keySet().associateWith { result.get(it) }).toString())
                }
            } finally {
                pending.finish()
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object PlaybackBroadcast {
    const val ACTION_CONTROL = "io.neta.music.action.CONTROL"
    const val ACTION_RESULT = "io.neta.music.action.RESULT"
    suspend fun execute(app: MyMusicApp, intent: Intent): Bundle = PlaybackControl.execute(
        app,
        PlaybackControlRequest(
            command = intent.getStringExtra("command").orEmpty(),
            keyword = intent.getStringExtra("keyword").orEmpty(),
            source = intent.getStringExtra("source").orEmpty(),
            songId = intent.getStringExtra("song_id").orEmpty(),
            songJson = intent.getStringExtra("song_json").orEmpty(),
            title = intent.getStringExtra("title").orEmpty(),
            artist = intent.getStringExtra("artist").orEmpty(),
            mode = intent.getStringExtra("mode").orEmpty(),
        ),
    )

    fun reply(context: Context, request: Intent, result: Bundle) {
        result.putString("command", request.getStringExtra("command").orEmpty())
        request.getStringExtra("request_id")?.let { result.putString("request_id", it) }
        context.sendBroadcast(Intent(request.getStringExtra("reply_action")?.takeIf { it.isNotBlank() } ?: ACTION_RESULT)
            .setPackage(request.getStringExtra("reply_package")?.takeIf { it.isNotBlank() })
            .putExtras(result))
    }
}
