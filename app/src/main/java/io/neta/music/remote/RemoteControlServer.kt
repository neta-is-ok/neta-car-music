package io.neta.music.remote

import android.content.ClipData
import android.content.ClipboardManager
import android.os.SystemClock
import fi.iki.elonen.NanoHTTPD
import io.neta.music.MyMusicApp
import io.neta.music.playback.PlaybackControl
import io.neta.music.playback.PlaybackControlRequest
import io.neta.music.widget.WidgetHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 手机远控的局域网网页服务：搜歌点歌、剪贴板传输、扫码输入共用一个固定端口。
 * 局域网内可信，不做令牌和会话校验；对播放器的操作都回到主线程执行（同 MCP 的做法）。
 */
internal class RemoteControlServer(private val app: MyMusicApp) : NanoHTTPD(null, PORT) {
    private val manager get() = app.remoteControl

    /** 最近一次搜索结果，点歌时按 id 从这里取；服务重启即清空。 */
    @Volatile
    private var lastResults: List<Pair<String, io.neta.music.data.Song>> = emptyList()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.orEmpty()
        return when {
            session.method == Method.GET && (uri == "/" || uri == "/index.html" || uri == "/input") -> asset()
            session.method == Method.GET && uri.startsWith("/transfer/") -> transferFile(uri.removePrefix("/transfer/"))
            session.method == Method.GET && uri == "/api/state" -> state()
            session.method == Method.GET && uri == "/api/search" -> search(session)
            session.method == Method.POST && uri == "/api/play" -> withBody(session, ::play)
            session.method == Method.POST && uri == "/api/request" -> withBody(session, ::request)
            session.method == Method.POST && uri == "/api/import" -> withBody(session, ::importPlaylist)
            session.method == Method.POST && uri == "/api/transport" -> transport()
            session.method == Method.GET && uri == "/api/clipboard" -> clipboardGet()
            session.method == Method.POST && uri == "/api/clipboard" -> withBody(session, ::clipboardSet)
            session.method == Method.POST && uri == "/api/input" -> withBody(session, ::input)
            else -> json(Response.Status.NOT_FOUND, JSONObject().put("success", false).put("message", "接口不存在"))
        }
    }

    /**
     * 「扫码下载」的文件出口：`GET /transfer/<文件名>`，文件来自 cache/exports（歌单导出）。
     * 只按名字取、不允许路径分隔符，扫不存在的名字给 404——不给局域网之外的任何探索空间。
     */
    private fun transferFile(name: String): Response {
        if (name.isBlank() || name.contains('/') || name.contains('\\') || name.contains("..")) {
            return json(Response.Status.NOT_FOUND, JSONObject().put("success", false).put("message", "文件不存在"))
        }
        val file = java.io.File(java.io.File(app.cacheDir, "exports"), name)
        if (!file.isFile) {
            return json(Response.Status.NOT_FOUND, JSONObject().put("success", false).put("message", "文件不存在或已清理"))
        }
        val mime = if (name.endsWith(".json")) "application/json" else "application/octet-stream"
        return newFixedLengthResponse(Response.Status.OK, mime, file.inputStream(), file.length())
            .apply {
                addHeader("Content-Disposition", "attachment; filename=\"$name\"")
                noStore()
            }
    }

    private fun asset(): Response {
        val bytes = runCatching {
            app.assets.open("remote/index.html").use { it.readBytes() }
        }.getOrNull() ?: return json(
            Response.Status.INTERNAL_ERROR,
            JSONObject().put("success", false).put("message", "页面资源缺失"),
        )
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=UTF-8", bytes.inputStream(), bytes.size.toLong())
            .apply { noStore() }
    }

    private fun state(): Response {
        val snapshot = WidgetHub.playback.value
        val song = snapshot?.song
        val cover = song?.cover.orEmpty()
        // 能解析歌单的平台列表：网页导入裸 ID 时选平台用（静态小字段，随状态一起给）
        val providers = JSONArray()
        runCatching { app.musicViewModel.providersForRemoteImport() }.getOrDefault(emptyList())
            .forEach { (id, name) -> providers.put(JSONObject().put("id", id).put("name", name)) }
        val body = JSONObject()
            .put("playing", snapshot?.playing == true)
            .put("title", song?.title.orEmpty())
            .put("artist", song?.artist.orEmpty())
            .put("position", snapshot?.positionAt(SystemClock.elapsedRealtime()) ?: 0L)
            .put("duration", snapshot?.durationMs ?: 0L)
            // 本地文件的封面路径浏览器打不开，只有网络封面才有意义
            .put("cover", if (cover.startsWith("http")) cover else "")
            .put("source", snapshot?.sourceName.orEmpty())
            .put("quality", snapshot?.quality.orEmpty())
            .put("providers", providers)
        return jsonResponse(body)
    }

    private fun search(session: IHTTPSession): Response {
        val keyword = session.parameters["q"]?.firstOrNull().orEmpty().trim()
        if (keyword.isEmpty()) {
            return json(Response.Status.BAD_REQUEST, JSONObject().put("success", false).put("message", "搜索词不能为空"))
        }
        val tracks = runCatching {
            runBlocking { withContext(Dispatchers.Main) { app.musicViewModel.searchForRemote(keyword) } }
        }.getOrElse { error ->
            return json(Response.Status.INTERNAL_ERROR, JSONObject().put("success", false)
                .put("message", error.message ?: "搜索失败，请稍后再试"))
        }
        lastResults = tracks.take(30).mapNotNull { track ->
            track.sources.firstOrNull()?.song?.let { track.id to it }
        }
        val songs = lastResults.map { it.second }
        val items = JSONArray()
        lastResults.forEachIndexed { index, (_, song) ->
            items.put(
                JSONObject()
                    .put("id", song.key)
                    .put("title", song.title)
                    .put("artist", song.artist)
                    .put("duration", song.duration)
                    .put("cover", if (song.cover.startsWith("http")) song.cover else "")
                    .put("source", song.source),
            )
        }
        return jsonResponse(JSONObject().put("success", true).put("items", items).put("queue", songs.size))
    }

    private fun play(body: JSONObject): Response {
        val id = body.optString("id")
        val song = lastResults.firstOrNull { it.second.key == id }?.second
            ?: return json(Response.Status.NOT_FOUND, JSONObject().put("success", false).put("message", "先搜索再点播，或重新搜索一次"))
        val queue = lastResults.map { it.second }
        runBlocking {
            withContext(Dispatchers.Main) {
                runCatching { app.musicViewModel.play(song, queue, openPlayer = false) }
                // 播放中点播没有暂停→播放的边沿，起播信号收不到，这里补发一次：
                // 主界面上直接进歌词页（闸在 maybeAutoLyricsView 里，播放页开着就不动）
                app.musicViewModel.maybeAutoLyricsView()
            }
        }
        return jsonResponse(JSONObject().put("success", true).put("title", song.title))
    }

    /** 直接点歌：任意文字交给外部点歌链路，播首个匹配并插播到当前歌曲之后。 */
    private fun request(body: JSONObject): Response {
        val keyword = body.optString("keyword").trim()
        if (keyword.isEmpty()) {
            return json(Response.Status.BAD_REQUEST, JSONObject().put("success", false).put("message", "点歌内容不能为空"))
        }
        val reply = runBlocking {
            withContext(Dispatchers.Main) {
                try {
                    PlaybackControl.execute(app, PlaybackControlRequest(command = "play", keyword = keyword))
                } catch (error: Exception) {
                    PlaybackControl.failure(error)
                }
            }
        }
        val success = reply.getBoolean("success")
        return jsonResponse(
            JSONObject()
                .put("success", success)
                .put("title", reply.getString("title").orEmpty())
                .put("artist", reply.getString("artist").orEmpty())
                .put(
                    "message",
                    if (success) "已点播" else reply.getString("message").orEmpty()
                        .ifBlank { "暂时放不了，稍后再试" },
                ),
        )
    }

    /** 手机远控网页的导入歌单：分享链接自动按域名定平台，裸 ID 由网页选好平台传上来。 */
    private fun importPlaylist(body: JSONObject): Response {
        val text = body.optString("text").trim()
        if (text.isEmpty()) {
            return json(Response.Status.BAD_REQUEST, JSONObject().put("success", false).put("message", "导入内容不能为空"))
        }
        val provider = body.optString("provider").trim().ifBlank { null }
        val result = runCatching {
            runBlocking { withContext(Dispatchers.Main) { app.musicViewModel.importForRemote(text, provider) } }
        }.getOrElse { error ->
            return json(
                Response.Status.INTERNAL_ERROR,
                JSONObject().put("success", false).put("message", error.message ?: "导入失败，请稍后再试"),
            )
        }
        return jsonResponse(
            JSONObject()
                .put("success", result.success)
                .put("title", result.title)
                .put("count", result.count)
                .put("message", result.message),
        )
    }

    private fun transport(): Response {
        runBlocking { withContext(Dispatchers.Main) { runCatching { app.musicViewModel.widgetTransport("play") } } }
        return jsonResponse(JSONObject().put("success", true))
    }

    private fun clipboardGet(): Response {
        // Android 10 起只有前台聚焦的应用读得到剪贴板：应用在前台时能取到，退到后台就取不到
        val text = runCatching {
            runBlocking {
                withContext(Dispatchers.Main) {
                    app.getSystemService(ClipboardManager::class.java)?.primaryClip
                        ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                }
            }
        }.getOrNull().orEmpty()
        return jsonResponse(JSONObject().put("success", true).put("text", text))
    }

    private fun clipboardSet(body: JSONObject): Response {
        val text = body.optString("text")
        if (text.isEmpty()) {
            return json(Response.Status.BAD_REQUEST, JSONObject().put("success", false).put("message", "内容不能为空"))
        }
        runBlocking {
            withContext(Dispatchers.Main) {
                app.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("吒友爱听远控", text))
            }
        }
        return jsonResponse(JSONObject().put("success", true).put("message", "已复制到车机剪贴板"))
    }

    /** 手机扫码输入提交：填进车机上正在等待的那个输入框。 */
    private fun input(body: JSONObject): Response {
        val text = body.optString("code")
        if (text.isEmpty()) {
            return json(Response.Status.BAD_REQUEST, JSONObject().put("success", false).put("message", "内容不能为空"))
        }
        val pending = manager.pendingInput()
            ?: return json(
                Response.Status.CONFLICT,
                JSONObject().put("success", false).put("message", "车机上没有等待扫码输入的对话框，请重新扫码"),
            )
        app.appScope.launch { pending.onText(text) }
        return jsonResponse(JSONObject().put("success", true).put("message", "已发送到车机"))
    }

    private fun withBody(session: IHTTPSession, handler: (JSONObject) -> Response): Response {
        val body = runCatching {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            JSONObject(files["postData"].orEmpty())
        }.getOrNull() ?: return json(
            Response.Status.BAD_REQUEST,
            JSONObject().put("success", false).put("message", "无法解析请求内容"),
        )
        return handler(body)
    }

    private fun json(status: Response.Status, body: JSONObject): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body.toString()).apply { noStore() }

    private fun jsonResponse(body: JSONObject): Response = json(Response.Status.OK, body)

    private fun Response.noStore() {
        addHeader("Cache-Control", "no-store")
    }

    companion object {
        const val PORT = RemoteControlManager.PORT
    }
}
