package io.neta.music.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.neta.music.MyMusicApp
import io.neta.music.mcp.MusicMcpManager
import io.neta.music.mcp.MusicMcpServer
import io.neta.music.mcp.MusicMcpState
import io.neta.music.ui.components.ListRow
import io.neta.music.ui.components.LocalAppSnackbar
import io.neta.music.ui.components.LocalAppWindow
import io.neta.music.ui.components.PageScaffold
import io.neta.music.ui.components.SectionLabel
import io.neta.music.ui.components.SwitchRow
import io.neta.music.ui.components.tvFocusRing
import io.neta.music.ui.theme.versionWarningColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val NETAMADE_PACKAGE = "io.github.netamade"
private val RequestMethods = listOf("美式普通点歌", "美式奔奔 AI 助手点歌 (MCP)")

private sealed interface NetamadeInstallation {
    data object Checking : NetamadeInstallation
    data object Missing : NetamadeInstallation
    data object Unavailable : NetamadeInstallation
    data class Installed(val name: String?, val code: Long) : NetamadeInstallation
}

private fun readNetamadeInstallation(context: Context): NetamadeInstallation = try {
    val info = context.packageManager.getPackageInfo(NETAMADE_PACKAGE, 0)
    NetamadeInstallation.Installed(info.versionName, PackageInfoCompat.getLongVersionCode(info))
} catch (_: PackageManager.NameNotFoundException) {
    NetamadeInstallation.Missing
} catch (_: Exception) {
    NetamadeInstallation.Unavailable
}

@Composable
internal fun MusicMcpSettingsScreen(onBack: () -> Unit, embedded: Boolean) {
    val context = LocalContext.current
    val manager = (context.applicationContext as MyMusicApp).musicMcp
    val state by manager.state.collectAsStateWithLifecycle()
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var installation by remember { mutableStateOf<NetamadeInstallation>(NetamadeInstallation.Checking) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    LaunchedEffect(refresh, context) {
        installation = NetamadeInstallation.Checking
        installation = withContext(Dispatchers.IO) { readNetamadeInstallation(context) }
    }
    SettingsTvFocus(first)

    fun copy(label: String, value: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, value))
        scope.launch { snackbar.showSnackbar("已复制$label") }
    }

    val gutter = if (embedded) 0.dp else LocalAppWindow.current.gutter
    val content: @Composable (PaddingValues) -> Unit = { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).padding(horizontal = gutter)) {
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.padding(bottom = 12.dp),
            ) {
                RequestMethods.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        modifier = Modifier.heightIn(min = 64.dp)
                            .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                            .tvFocusRing(),
                    ) {
                        Text(
                            title,
                            Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Medium,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                // 只重建列表滚动位置；服务、绑定请求和版本检测仍由页级状态持有。
                key(selectedTab) {
                    SettingsPage(SettingsSection.MusicMcp, embedded = true, onBack = onBack) {
                        item {
                            VersionRequirementCard(selectedTab == 1, installation, onRefresh = { refresh++ })
                        }
                        if (selectedTab == 0) {
                            item { SectionLabel("通过原车语音点歌") }
                            item {
                                TextCard(
                                    title = "在美式中开启语音点歌",
                                    body = "使用原车语音识别，让吒友爱听播放你想听的歌曲。\n使用支持版本的美式，在「语音识别」中开启「语音点歌」。",
                                    index = 0, count = 1,
                                )
                            }
                            item {
                                ListRow(
                                    "前往美式「语音识别」", 0, 1,
                                    if (installation == NetamadeInstallation.Missing) null else ({
                                        runCatching {
                                            context.startActivity(Intent().setComponent(
                                                ComponentName(NETAMADE_PACKAGE, "$NETAMADE_PACKAGE.MainActivity"),
                                            ).putExtra("page", "VoiceRecognition"))
                                        }.onFailure {
                                            scope.launch { snackbar.showSnackbar("无法打开美式「语音识别」，请在美式中手动进入") }
                                        }
                                    }),
                                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                                    summary = if (installation == NetamadeInstallation.Missing) "未安装哪吒美式，暂时无法前往" else "前往后手动开启，不会自动修改美式设置",
                                )
                            }
                        } else {
                            mcpSettings(state, manager, installation != NetamadeInstallation.Missing, ::copy)
                        }
                    }
                }
            }
        }
    }
    if (embedded) {
        content(PaddingValues())
    } else {
        PageScaffold(
            title = SettingsSection.MusicMcp.title,
            subtitle = SettingsSection.MusicMcp.subtitle,
            onBack = onBack,
            content = content,
        )
    }
}

@Composable
private fun VersionRequirementCard(ai: Boolean, installation: NetamadeInstallation, onRefresh: () -> Unit) {
    val colors = MaterialTheme.colorScheme.versionWarningColors()
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(colors.container)
            .drawBehind { drawRect(colors.accent, size = Size(4.dp.toPx(), size.height)) }
            .padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.WarningAmber, null, Modifier.size(26.dp), tint = colors.accent)
            Text(
                if (ai) "一键绑定有版本要求" else "普通点歌有版本要求",
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.accent,
            )
        }
        Text(
            when (installation) {
                NetamadeInstallation.Checking -> "正在检测哪吒美式版本…"
                NetamadeInstallation.Missing -> "未安装哪吒美式"
                NetamadeInstallation.Unavailable -> "无法读取哪吒美式版本，请重新检测"
                is NetamadeInstallation.Installed -> "已安装：${installation.name?.takeIf { it.isNotBlank() } ?: "版本名未知"}（versionCode ${installation.code}）"
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.content,
        )
        Text(
            "需要哪吒美式 v5.2.9（versionCode 242）之后的稳定版，或 v5.2.9 的尝鲜版。",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = colors.content,
        )
        Text(
            if (ai) "v5.2.9（versionCode 242）稳定版无法自动绑定，需要在美式中手动添加 MCP 服务器；已安装上述尝鲜版的用户可尝试一键绑定。"
            else "v5.2.9（versionCode 242）稳定版不支持普通点歌，请先更新；已安装上述尝鲜版的用户可按下方方法开启。",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.content,
        )
        TextButton(
            onClick = onRefresh,
            enabled = installation != NetamadeInstallation.Checking,
            modifier = Modifier.align(Alignment.End).tvFocusRing(),
            colors = ButtonDefaults.textButtonColors(contentColor = colors.accent),
        ) {
            Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
            Text("重新检测", Modifier.padding(start = 6.dp))
        }
    }
}

private fun LazyListScope.mcpSettings(
    state: MusicMcpState,
    manager: MusicMcpManager,
    netamadeAvailable: Boolean,
    copy: (String, String) -> Unit,
) {
    val busy = state.pending.isNotEmpty()
    val canRequest = !busy && netamadeAvailable
    item { SectionLabel("本机服务") }
    item {
        SwitchRow(
            title = "本机 MCP 点歌服务",
            checked = state.enabled,
            onCheckedChange = { manager.setEnabled(it) },
            index = 0, count = 1,
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
    item { SettingsNote("只供本机应用连接，无需 Token。") }
    item { SectionLabel("美式 · 奔奔 AI 助手") }
    item { SettingsNote("先在美式启动核心服务并启用奔奔 AI 助手，再点击一键绑定。") }
    item {
        TextCard(
            title = when (state.pending) {
                "REGISTER" -> "正在绑定美式…"
                "UNREGISTER" -> "正在解除绑定…"
                "QUERY" -> "正在查询注册状态…"
                else -> state.bindingSummary
            },
            body = buildString {
                append(state.message.ifBlank { "一键绑定会自动开启本机 MCP 点歌服务。" })
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
        ListRow("一键绑定", 0, 3, if (canRequest) ({ manager.request("REGISTER") }) else null,
            icon = Icons.Filled.Link, showChevron = false,
            summary = if (netamadeAvailable) "" else "未安装哪吒美式，暂时无法绑定")
    }
    item {
        ListRow("查询绑定状态", 1, 3, if (canRequest) ({ manager.request("QUERY") }) else null,
            icon = Icons.Filled.Refresh, showChevron = false)
    }
    item {
        ListRow("解除绑定", 2, 3, if (canRequest) ({ manager.request("UNREGISTER") }) else null,
            icon = Icons.Filled.LinkOff, showChevron = false)
    }
    item { SectionLabel("手动添加 MCP 服务器") }
    item {
        TextCard(
            title = "在美式的奔奔 AI 助手中添加",
            body = "名称：${MusicMcpServer.NAME}\n类型：Streamable HTTP\n地址：${MusicMcpServer.ENDPOINT}\nToken：无需填写\n\n添加后，请开启上方的本机 MCP 点歌服务。",
            index = 0, count = 1,
        )
    }
    item {
        ListRow("复制服务器地址", 0, 2, { copy("服务器地址", MusicMcpServer.ENDPOINT) },
            icon = Icons.Filled.ContentCopy, showChevron = false)
    }
    item {
        ListRow("复制注册信息", 1, 2, { copy("MCP 注册信息", MusicMcpServer.registration().toString(2)) },
            icon = Icons.Filled.ContentCopy, showChevron = false)
    }
}
