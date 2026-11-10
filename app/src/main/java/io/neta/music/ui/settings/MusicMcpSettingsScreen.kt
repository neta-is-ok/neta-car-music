package io.neta.music.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.neta.music.MyMusicApp
import io.neta.music.mcp.MusicMcpServer
import io.neta.music.ui.components.ListRow
import io.neta.music.ui.components.LocalAppSnackbar
import io.neta.music.ui.components.SectionLabel
import io.neta.music.ui.components.SwitchRow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun MusicMcpSettingsScreen(onBack: () -> Unit, embedded: Boolean) {
    val context = LocalContext.current
    val manager = (context.applicationContext as MyMusicApp).musicMcp
    val state by manager.state.collectAsStateWithLifecycle()
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    SettingsTvFocus(first)
    val busy = state.pending.isNotEmpty()
    SettingsPage(SettingsSection.MusicMcp, embedded, onBack) {
        item { SectionLabel("本机服务") }
        item {
            SwitchRow(
                title = "本机 MCP 点歌服务",
                checked = state.enabled,
                onCheckedChange = { manager.setEnabled(it) },
                index = 0, count = 1,
                modifier = Modifier.focusRequester(first),
                enabled = !busy,
                icon = Icons.Filled.SmartToy,
                summary = when {
                    state.serviceError.isNotBlank() -> "启动失败：${state.serviceError}"
                    state.running -> "运行中 · ${MusicMcpServer.ENDPOINT}"
                    state.enabled -> "正在启动…"
                    else -> "一键绑定时会自动开启，后台也能接收点歌"
                },
            )
        }
        item { SettingsNote("只供本机应用连接，不需要 Token。与广播共用搜索、换源和播放逻辑。") }
        item { SectionLabel("美式 · 奔奔 AI") }
        item {
            TextCard(
                title = when (state.pending) {
                    "REGISTER" -> "正在绑定美式…"
                    "UNREGISTER" -> "正在解除绑定…"
                    "QUERY" -> "正在查询注册状态…"
                    else -> state.bindingSummary
                },
                body = buildString {
                    append(state.message.ifBlank { "先在美式启动核心服务并启用奔奔 AI，再点击一键绑定。" })
                    state.binding?.let { binding ->
                        append("\n上次确认：${if (binding.bound) "已绑定" else "未绑定"} · ")
                        append(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(binding.checkedAt)))
                    }
                    if (state.binding?.bound == true && !state.running) append("\n本机服务已关闭，开启后才能点歌。")
                },
                index = 0, count = 1,
            )
        }
        item {
            ListRow("一键绑定", 0, 4, if (busy) null else ({ manager.request("REGISTER") }),
                icon = Icons.Filled.Link, showChevron = false)
        }
        item {
            ListRow("解除绑定", 1, 4, if (busy) null else ({ manager.request("UNREGISTER") }),
                icon = Icons.Filled.LinkOff, showChevron = false)
        }
        item {
            ListRow("查询注册状态", 2, 4, if (busy) null else ({ manager.request("QUERY") }),
                icon = Icons.Filled.Refresh, showChevron = false)
        }
        item {
            ListRow("复制注册信息", 3, 4, {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newPlainText("吒友爱听 MCP 注册信息", MusicMcpServer.registration().toString(2)))
                scope.launch { snackbar.showSnackbar("已复制 MCP 注册信息") }
            }, icon = Icons.Filled.ContentCopy, showChevron = false)
        }
    }
}
