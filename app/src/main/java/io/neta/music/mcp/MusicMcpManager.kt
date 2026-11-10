package io.neta.music.mcp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import io.neta.music.MyMusicApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID

internal data class McpBinding(
    val bound: Boolean,
    val enabled: Boolean = false,
    val toolCount: Int = 0,
    val allowedToolCount: Int = 0,
    val checkedAt: Long = System.currentTimeMillis(),
) {
    fun toJson() = JSONObject().put("bound", bound).put("enabled", enabled)
        .put("toolCount", toolCount).put("allowedToolCount", allowedToolCount).put("checkedAt", checkedAt).toString()

    companion object {
        fun parse(raw: String): McpBinding? = runCatching {
            val value = JSONObject(raw)
            McpBinding(value.getBoolean("bound"), value.optBoolean("enabled"), value.optInt("toolCount"),
                value.optInt("allowedToolCount"), value.getLong("checkedAt"))
        }.getOrNull()
    }
}

internal data class MusicMcpState(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val serviceError: String = "",
    val binding: McpBinding? = null,
    val confirmed: Boolean = false,
    val pending: String = "",
    val message: String = "",
) {
    val bindingSummary: String get() = when {
        !confirmed -> if (message.isBlank()) "注册状态待查询" else "注册状态待确认"
        binding?.bound != true -> "未绑定美式"
        !binding.enabled -> "已绑定 · 美式中已停用"
        else -> "已绑定 · 可用工具 ${binding.allowedToolCount}/${binding.toolCount}"
    }
}

/** 美式自动注册协议 v1。绑定状态只由回执确认，发送成功和等待超时都不代表业务结果。 */
internal class MusicMcpManager(private val app: MyMusicApp) {
    private val mutableState = MutableStateFlow(MusicMcpState())
    val state = mutableState.asStateFlow()
    private val loaded = app.appScope.async {
        val saved = app.settings.state.first()
        mutableState.update { it.copy(enabled = saved.mcpEnabled, binding = McpBinding.parse(saved.mcpBinding)) }
    }
    private var pendingId = ""
    private var timeout: Job? = null

    fun setEnabled(enabled: Boolean) = app.appScope.launch {
        loaded.await()
        try {
            app.settings.mcpEnabled(enabled)
            mutableState.update { it.copy(enabled = enabled, serviceError = "") }
            if (enabled) startService() else app.stopService(Intent(app, MusicMcpService::class.java))
        } catch (exception: Exception) {
            mutableState.update { it.copy(serviceError = exception.message ?: "本机服务启动失败") }
        }
    }

    suspend fun restoreIfEnabled() {
        loaded.await()
        if (state.value.enabled && !state.value.running) {
            try {
                startService()
            } catch (exception: Exception) {
                serviceStopped(exception.message ?: "本机服务启动失败")
            }
        }
    }

    private fun startService() {
        ContextCompat.startForegroundService(app, Intent(app, MusicMcpService::class.java))
    }

    fun serviceStarted() = mutableState.update { it.copy(running = true, serviceError = "") }
    fun serviceStopped(error: String = "") = mutableState.update { it.copy(running = false, serviceError = error) }

    fun request(op: String) = app.appScope.launch {
        loaded.await()
        if (state.value.pending.isNotEmpty()) return@launch
        val id = UUID.randomUUID().toString()
        pendingId = id
        mutableState.update { it.copy(pending = op, message = "") }
        var callback: PendingIntent? = null
        try {
            if (op == "REGISTER") {
                app.settings.mcpEnabled(true)
                mutableState.update { it.copy(enabled = true, serviceError = "") }
                if (!state.value.running) {
                    startService()
                    val service = withTimeoutOrNull(5_000) { state.first { it.running || it.serviceError.isNotBlank() } }
                        ?: error("本机 MCP 服务启动超时，请稍后重试")
                    check(service.running) { service.serviceError }
                }
            }
            val action = when (op) {
                "REGISTER" -> "io.github.netamade.action.REGISTER_MCP_SERVER"
                "UNREGISTER" -> "io.github.netamade.action.UNREGISTER_MCP_SERVER"
                "QUERY" -> "io.github.netamade.action.QUERY_MCP_SERVER"
                else -> error("未知注册操作")
            }
            val reply = Intent(app, McpRegistrationResultReceiver::class.java)
                .setAction("io.neta.music.MCP_REGISTRATION_RESULT")
                .setData(Uri.parse("neta-music-mcp:$id"))
            callback = PendingIntent.getBroadcast(app, 0, reply,
                PendingIntent.FLAG_ONE_SHOT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
            val request = Intent(action).setPackage("io.github.netamade")
                .putExtra("protocol_version", 1).putExtra("request_id", id).putExtra("reply_to", callback)
            if (op == "REGISTER") request.putExtra("server", MusicMcpServer.registration().toString())
            else request.putExtra("service_id", MusicMcpServer.SERVICE_ID)
            app.sendBroadcast(request)
            timeout = app.appScope.launch {
                delay(30_000)
                if (pendingId == id) {
                    pendingId = ""
                    mutableState.update { it.copy(pending = "", confirmed = false,
                        message = "未收到美式回执，注册状态待确认。请启动美式核心服务并启用奔奔 AI，再查询状态。") }
                }
            }
        } catch (exception: Exception) {
            callback?.cancel()
            pendingId = ""
            mutableState.update { it.copy(pending = "", confirmed = false, message = exception.message ?: "请求未能发出") }
        }
    }

    suspend fun receive(raw: String) {
        loaded.await()
        val result = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (result.optString("serviceId") != MusicMcpServer.SERVICE_ID) return
        if (result.optString("requestId") == pendingId) {
            timeout?.cancel()
            pendingId = ""
            mutableState.update { it.copy(pending = "") }
        }
        val code = result.optString("code")
        val binding = when (result.optString("op", "REGISTER")) {
            "REGISTER" -> if (code == "OK" || code == "ALREADY_REGISTERED") {
                McpBinding(true, result.optBoolean("enabled"), result.optInt("toolCount"), result.optInt("allowedToolCount"))
            } else null
            "UNREGISTER" -> if (code == "OK" || code == "NOT_FOUND") McpBinding(false) else null
            "QUERY" -> if ((code == "OK" || code == "NOT_FOUND") && result.has("bound")) {
                val server = result.optJSONArray("servers")?.optJSONObject(0)
                McpBinding(result.optBoolean("bound"), server?.optBoolean("enabled") ?: false,
                    server?.optInt("toolCount") ?: 0, server?.optInt("allowedToolCount") ?: 0)
            } else null
            else -> null
        }
        if (binding != null) app.settings.mcpBinding(binding.toJson())
        mutableState.update { it.copy(
            binding = binding ?: it.binding,
            confirmed = binding != null,
            message = result.optString("message").ifBlank { code },
        ) }
    }
}

/** 私有回调通过授予美式的可变 PendingIntent 接收结果，无需对外导出。 */
class McpRegistrationResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val raw = intent.getStringExtra("result") ?: return
        val pending = goAsync()
        val app = context.applicationContext as MyMusicApp
        app.appScope.launch {
            try { app.musicMcp.receive(raw) } finally { pending.finish() }
        }
    }
}
