package io.neta.music.mcp

import fi.iki.elonen.NanoHTTPD
import io.neta.music.BuildConfig
import io.neta.music.MyMusicApp
import io.neta.music.playback.PlaybackControl
import io.neta.music.playback.PlaybackControlRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** 本机无认证、无会话的 Streamable HTTP MCP；所有响应均结束，不建立无限 SSE。 */
internal class MusicMcpServer(private val app: MyMusicApp) : NanoHTTPD("127.0.0.1", PORT) {
    override fun serve(session: IHTTPSession): Response {
        if (session.uri == "/health" && session.method == Method.GET) {
            return json(JSONObject().put("name", NAME).put("endpoint", ENDPOINT).put("running", true))
        }
        if (session.uri != "/mcp") return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        if (session.method != Method.POST) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Use POST")
                .apply { addHeader("Allow", "POST") }
        }
        val payload = try {
            val body = mutableMapOf<String, String>()
            session.parseBody(body)
            JSONTokener(body["postData"].orEmpty()).nextValue()
        } catch (_: Exception) {
            return json(error(JSONObject.NULL, -32700, "无法解析 JSON"))
        }
        return when (payload) {
            is JSONObject -> rpc(payload)?.let(::json) ?: accepted()
            is JSONArray -> {
                if (payload.length() == 0) return json(error(JSONObject.NULL, -32600, "请求数组不能为空"))
                val replies = JSONArray()
                for (index in 0 until payload.length()) {
                    val item = payload.optJSONObject(index)
                    val reply = if (item == null) error(JSONObject.NULL, -32600, "请求应为 JSON 对象") else rpc(item)
                    if (reply != null) replies.put(reply)
                }
                if (replies.length() == 0) accepted() else json(replies)
            }
            else -> json(error(JSONObject.NULL, -32600, "请求应为 JSON 对象"))
        }
    }

    private fun rpc(request: JSONObject): JSONObject? {
        val id = request.opt("id") ?: JSONObject.NULL
        val method = request.optString("method")
        if (request.optString("jsonrpc") != "2.0" || method.isBlank()) {
            return error(id, -32600, "无效的 JSON-RPC 2.0 请求")
        }
        if (!request.has("id")) return null // initialized 等通知无需响应体，也不执行点歌。
        val params = request.optJSONObject("params") ?: JSONObject()
        return try {
            val result = when (method) {
                "initialize" -> JSONObject()
                    .put("protocolVersion", PROTOCOL_VERSION)
                    .put("capabilities", JSONObject().put("tools", JSONObject()))
                    .put("serverInfo", JSONObject().put("name", NAME).put("version", BuildConfig.VERSION_NAME))
                    .put("instructions", "通过吒友爱听点歌。用户提供歌名或歌手时调用 play_song；get_playback_state 查询当前歌曲和播放状态。")
                "ping" -> JSONObject()
                "tools/list" -> JSONObject().put("tools", tools())
                "tools/call" -> call(params)
                else -> return error(id, -32601, "不支持的方法：$method")
            }
            JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
        } catch (exception: IllegalArgumentException) {
            error(id, -32602, exception.message ?: "参数不正确")
        } catch (exception: Exception) {
            error(id, -32603, exception.message ?: "MCP 请求失败")
        }
    }

    private fun call(params: JSONObject): JSONObject {
        val args = params.optJSONObject("arguments") ?: JSONObject()
        val request = when (params.optString("name")) {
            "play_song" -> PlaybackControlRequest(
                command = "play",
                keyword = string(args, "keyword"),
                source = string(args, "source"),
                songId = string(args, "song_id"),
                songJson = string(args, "song_json"),
                title = string(args, "title"),
                artist = string(args, "artist"),
            )
            "get_playback_state" -> PlaybackControlRequest(command = "query_mode")
            else -> throw IllegalArgumentException("未知工具：${params.optString("name")}")
        }
        // HTTP 工作线程等待结果，Media3 操作仍在主线程。与广播直接调用同一个执行器。
        val result = runBlocking {
            withContext(Dispatchers.Main) {
                try {
                    PlaybackControl.execute(app, request)
                } catch (exception: Exception) {
                    PlaybackControl.failure(exception)
                }
            }
        }
        val data = JSONObject()
        @Suppress("DEPRECATION")
        for (key in result.keySet()) data.put(key, result.get(key))
        data.put("command", request.command)
        return JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", data.toString())))
            .put("isError", !result.getBoolean("success"))
    }

    private fun string(args: JSONObject, key: String): String {
        if (!args.has(key)) return ""
        return args.get(key) as? String ?: throw IllegalArgumentException("$key 应为字符串")
    }

    private fun tools(): JSONArray {
        val properties = JSONObject()
        mapOf(
            "keyword" to "歌名、歌手或两者组合，通常只需填写此项；搜索首个结果后立即播放。",
            "source" to "可选平台 ID，如 netease、qq、kugou、kuwo、migu、jamendo；省略则使用应用已启用的音乐源。",
            "song_id" to "已知歌曲 ID 时与 source 一起使用，不需要 keyword。",
            "title" to "按歌曲 ID 点歌时可选的歌名。",
            "artist" to "按歌曲 ID 点歌时可选的歌手。",
            "song_json" to "完整 Song 的 JSON 字符串，包含 id、source 及平台需要的 extra；优先于歌曲 ID 和关键词。",
        ).forEach { (key, description) ->
            properties.put(key, JSONObject().put("type", "string").put("description", description))
        }
        return JSONArray()
            .put(JSONObject()
                .put("name", "play_song")
                .put("description", "让吒友爱听立即播放用户想听的歌曲。通常传 keyword（歌名或歌名加歌手）；也支持平台歌曲 ID 或完整歌曲 JSON。复用应用与广播的搜索、音源解析和播放逻辑，不打开界面。已有播放队列时插播到当前歌曲之后，唱完接着放原列表。")
                .put("inputSchema", JSONObject().put("type", "object").put("properties", properties)))
            .put(JSONObject()
                .put("name", "get_playback_state")
                .put("description", "查询吒友爱听当前歌曲、是否正在播放、收藏状态和循环模式。")
                .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject())))
    }

    private fun error(id: Any, code: Int, message: String) = JSONObject()
        .put("jsonrpc", "2.0").put("id", id)
        .put("error", JSONObject().put("code", code).put("message", message))

    private fun json(value: Any) = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", value.toString())
    private fun accepted() = newFixedLengthResponse(Response.Status.ACCEPTED, MIME_PLAINTEXT, "")

    companion object {
        const val PORT = 18766
        const val ENDPOINT = "http://127.0.0.1:$PORT/mcp"
        const val NAME = "吒友爱听"
        const val SERVICE_ID = "io.neta.music.playback"
        const val PROTOCOL_VERSION = "2025-03-26"

        fun registration() = JSONObject()
            .put("serviceId", SERVICE_ID).put("name", NAME).put("endpoint", ENDPOINT)
    }
}
