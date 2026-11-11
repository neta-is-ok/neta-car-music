package io.neta.music.playback

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BadParcelableException
import android.os.Bundle
import android.os.Process
import android.os.ResultReceiver
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.neta.music.MyMusicApp
import io.neta.music.AppLog
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
 * 可选 result_receiver（平台 ResultReceiver）直接回传结果；未提供时仍发普通结果广播，
 * 默认 action = io.neta.music.action.RESULT，reply_action / reply_package 可指定回传目标。
 * request_id 原样回传。每次结果含 command、success、status（completed / error / accepted）；
 * 状态含 has_song、song_id、source、title、artist、favorite、mode、repeat_mode、shuffle、playing。
 * 没有当前歌曲时 has_song=false，不带歌曲信息和 favorite。失败含 message。
 *
 * 有序广播同时以 resultExtras 和 JSON resultData 返回，resultCode=-1 成功、0 失败。
 * play 的有序结果为 accepted；联网及播放结果稍后通过相同回调或结果广播返回 completed / error。
 * 使用广播回传时应先注册结果接收器；reply_package 也可用于发往声明在清单中的接收器。
 *
 * 示例：adb shell am broadcast -a io.neta.music.action.CONTROL -p io.neta.music
 * --es command set_mode --es mode one
 */
class PlaybackBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PlaybackBroadcast.ACTION_CONTROL) return
        PlaybackBroadcast.decodeError(intent)?.let { error ->
            if (isOrderedBroadcast) {
                resultCode = Activity.RESULT_CANCELED
                setResultExtras(error)
                resultData = JSONObject(error.keySet().associateWith { error.get(it) }).toString()
            }
            return
        }
        PlaybackBroadcast.logReceived(intent)
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
    /** 只放在本次选中歌曲的 MediaItem 中，不把调用方 Intent 或回调对象传入播放器。 */
    const val EXTRA_PLAY_REQUEST_ID = "io.neta.music.playback.REQUEST_ID"
    const val EXTRA_PLAY_REQUEST_ARTIST = "io.neta.music.playback.REQUEST_ARTIST"
    private const val EXTRA_RESULT_RECEIVER = "result_receiver"
    private var awaitingPlaybackId: String? = null

    /** API 30 读取任意 extra 都会展开整个 Bundle，必须在日志、异步处理和服务转发之前接住解析异常。 */
    fun decodeError(request: Intent): Bundle? = try {
        request.getStringExtra("command")
        // 新系统可能延迟解析 Parcelable；在接收入口读出回调，不能等到回复时才发现格式不兼容。
        IntentCompat.getParcelableExtra(request, EXTRA_RESULT_RECEIVER, ResultReceiver::class.java)
        null
    } catch (error: BadParcelableException) {
        AppLog.e("PlaybackBroadcast", "无法读取外部播放控制请求", error)
        PlaybackControl.failure(IllegalArgumentException("播放控制请求格式不兼容，请更新发送请求的应用"))
    }

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
            requestId = playRequestId(intent).orEmpty(),
        ),
    )

    fun reply(context: Context, request: Intent, result: Bundle) {
        result.putString("command", request.getStringExtra("command").orEmpty())
        request.getStringExtra("request_id")?.let { result.putString("request_id", it) }
        logPlayEvent(request, result.getString("status").orEmpty(), result)
        if (result.getString("status") == "error" && request.getStringExtra("command") == "play" &&
            awaitingPlaybackId == playRequestId(request)
        ) {
            awaitingPlaybackId = null
        }
        val receiver = IntentCompat.getParcelableExtra(request, EXTRA_RESULT_RECEIVER, ResultReceiver::class.java)
        if (receiver != null) {
            // 回调 Binder 随完整请求转交播放服务；只回调用方，不依赖其用户、包名或动态接收器。
            receiver.send(
                if (result.getBoolean("success")) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle(result),
            )
            return
        }
        context.sendBroadcast(Intent(request.getStringExtra("reply_action")?.takeIf { it.isNotBlank() } ?: ACTION_RESULT)
            .setPackage(request.getStringExtra("reply_package")?.takeIf { it.isNotBlank() })
            .putExtras(result))
    }

    fun logReceived(request: Intent) = logPlayEvent(request, "received", null)

    fun observePlayback(request: Intent) {
        awaitingPlaybackId = playRequestId(request)
    }

    fun clearPlaybackObservation() {
        awaitingPlaybackId = null
    }

    /** 由服务当前生效的 ExoPlayer 事件调用，不采用 MediaController 的本地乐观状态或 completed。 */
    fun logPlaying(player: Player) {
        if (player.isPlaying) finishPlaybackEvent(player.currentMediaItem, "playing", "")
    }

    /** 换源已经耗尽等确定失败时调用；中间的可恢复播放器错误不提前结束观察。 */
    fun logPlaybackFailure(item: MediaItem?, message: String) = finishPlaybackEvent(item, "error", message)

    private fun finishPlaybackEvent(item: MediaItem?, phase: String, message: String) {
        val id = awaitingPlaybackId ?: return
        val metadata = item?.mediaMetadata ?: return
        if (metadata.extras?.getString(EXTRA_PLAY_REQUEST_ID) != id) return
        awaitingPlaybackId = null
        logPlayEvent(id, phase, Bundle().apply {
            putBoolean("success", phase == "playing")
            putString("title", metadata.title?.toString().orEmpty())
            // 通知歌词可能改写 metadata.artist；请求标记保留真实歌手。
            putString("artist", metadata.extras?.getString(EXTRA_PLAY_REQUEST_ARTIST).orEmpty())
            putString("message", message)
        })
    }

    private fun playRequestId(request: Intent): String? = request.getStringExtra("request_id")
        ?.takeIf { it.isNotBlank() && it.length <= 128 }

    /**
     * 系统 INFO 事件 v1：只观察带 request_id 的 play，不改变回执或播放行为。
     * 美式按请求和目标用户关联；没有读到日志时仍以已发送结束，不因此等待或重发。
     */
    private fun logPlayEvent(request: Intent, phase: String, result: Bundle?) {
        if (request.getStringExtra("command") != "play") return
        val requestId = playRequestId(request) ?: return
        logPlayEvent(requestId, phase, result)
    }

    private fun logPlayEvent(requestId: String, phase: String, result: Bundle?) {
        if (phase !in setOf("received", "accepted", "completed", "playing", "error")) return
        val event = JSONObject()
            .put("v", 1)
            .put("command", "play")
            .put("request_id", requestId)
            .put("user_id", Process.myUid() / 100000)
            .put("phase", phase)
        if (result != null) {
            event.put("success", result.getBoolean("success"))
            event.put("title", result.getString("title").orEmpty().take(192))
            event.put("artist", result.getString("artist").orEmpty().take(192))
            event.put("message", result.getString("message").orEmpty().take(384))
        }
        AppLog.systemInfo("NetaMusicControl", event.toString())
    }
}
