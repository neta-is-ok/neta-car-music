package io.neta.music.ui.settings

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.neta.music.BuildConfig
import io.neta.music.R
import io.neta.music.data.update.UpdatePhase
import io.neta.music.data.update.UpdateUiState
import io.neta.music.data.update.formatSize
import io.neta.music.ui.ErrorBlock
import io.neta.music.ui.InfoNote
import io.neta.music.ui.MusicActions
import io.neta.music.ui.MusicUiState
import io.neta.music.ui.components.IconTone
import io.neta.music.ui.components.ListRow
import io.neta.music.ui.components.LocalAppSnackbar
import io.neta.music.ui.components.SegmentedGroup
import io.neta.music.ui.components.tvFocusRing
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val PROJECT_URL = "https://github.com/neta-is-ok/neta-car-music"
private const val UPSTREAM_URL = "https://github.com/mytv-android/myMusic"

/**
 * 关于与更新：应用信息、新版本卡片（下载进度、校验、安装都在卡片里完成）、
 * 检查更新、隐私、许可、项目与源码来源、诊断。
 */
@Composable
fun AboutScreen(
    state: MusicUiState,
    update: UpdateUiState,
    actions: MusicActions,
    onBack: () -> Unit,
    onLicenses: () -> Unit,
    onPrivacy: () -> Unit,
    embedded: Boolean = false,
    progress: () -> Float = { update.progress },
) {
    val openUri = rememberOpenUri()
    val clipboard = LocalClipboard.current
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    val info = update.info
    val showCard = info != null
    val isChecking = checking || update.phase == UpdatePhase.Checking

    fun check() {
        if (checking) return
        scope.launch {
            checking = true
            val result = actions.checkUpdatesNow()
            checking = false
            if (result.info == null && result.error.isBlank()) {
                snackbar.showSnackbar(result.message.ifBlank { "已是最新版本" })
            }
        }
    }

    val checkSummary = when {
        isChecking -> "正在检查…"
        info != null -> "有新版本 ${info.versionName}（构建 ${info.versionCode}）"
        update.error.isNotBlank() -> "检查失败"
        update.message.isNotBlank() -> update.message
        else -> "下载后校验 SHA-256"
    }

    SettingsPage(SettingsSection.About, embedded, onBack, subtitle = "版本 ${update.currentVersionName.ifBlank { BuildConfig.VERSION_NAME }}（构建 ${update.currentVersionCode}）") {
        item(key = "head", contentType = "appHeader") { AppHeader(update.currentVersionName, update.currentVersionCode) }
        if (showCard && info != null) {
            item(key = "update", contentType = "updateCard") {
                UpdateCard(update, actions, progress)
            }
        }
        if (info == null && update.error.isNotBlank() && !isChecking) {
            item(key = "error", contentType = "error") {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ErrorBlock("检查更新失败：${update.error}", onRetry = ::check)
                    TextButton(onClick = actions::openReleasePage, modifier = Modifier.tvFocusRing(CircleShape)) {
                        Text("在浏览器打开发布页")
                    }
                }
            }
        }
        item(key = "rows", contentType = "settingsGroup") {
            SegmentedGroup(Modifier.padding(top = 12.dp)) {
                ListRow(
                    title = "检查更新",
                    index = 0,
                    count = 7,
                    onClick = ::check,
                    icon = Icons.Filled.Refresh,
                    summary = checkSummary,
                    showChevron = false,
                    trailing = if (isChecking) {
                        { LoadingIndicator(Modifier.size(32.dp)) }
                    } else {
                        null
                    },
                )
                ListRow(
                    title = "隐私说明",
                    index = 1,
                    count = 7,
                    onClick = onPrivacy,
                    icon = Icons.Filled.PrivacyTip,
                    summary = "哪些数据留在本机，哪些会离开",
                )
                ListRow(
                    title = "开源许可",
                    index = 2,
                    count = 7,
                    onClick = onLicenses,
                    icon = Icons.Filled.Code,
                    summary = "AGPL-3.0 · music-lib、Folia 模组、Rush 与第三方依赖",
                )
                ListRow(
                    title = "项目源码",
                    index = 3,
                    count = 7,
                    onClick = { openUri(PROJECT_URL) },
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    summary = "neta-is-ok/neta-car-music",
                    showChevron = false,
                )
                ListRow(
                    title = "源码来源：myMusic",
                    index = 4,
                    count = 7,
                    onClick = { openUri(UPSTREAM_URL) },
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    summary = "mytv-android/myMusic · AGPL-3.0",
                    showChevron = false,
                )
                ListRow(
                    title = "复制诊断信息",
                    index = 5,
                    count = 7,
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("吒友爱听诊断信息", actions.diagnostics())))
                            snackbar.showSnackbar("诊断信息已复制")
                        }
                    },
                    icon = Icons.Filled.ContentCopy,
                    iconTone = IconTone.Tertiary,
                    summary = "设备、版本、启用平台和最近 ${io.neta.music.AppLog.snapshot().size} 条运行日志",
                    showChevron = false,
                )
                ListRow(
                    title = "导出日志文件",
                    index = 6,
                    count = 7,
                    onClick = {
                        scope.launch { snackbar.showSnackbar(actions.exportLog()) }
                    },
                    icon = Icons.Filled.Share,
                    iconTone = IconTone.Tertiary,
                    summary = "运行日志写成文件后分享，适合条数多的时候",
                    showChevron = false,
                )
            }
        }
        item(key = "sodam", contentType = "creditCard") {
            Box(Modifier.padding(top = 12.dp)) {
                SodamCredit(note = "汽水会员整曲的签名服务与协议来自 SodaM（AGPL-3.0）")
            }
        }
        item(key = "logNote", contentType = "infoNote") {
            Box(Modifier.padding(top = 8.dp)) {
                InfoNote("运行日志只记在本机内存里，最多 800 条，退出应用即清空。Cookie 和令牌在写入前就已抹掉，导出前也会再过滤一遍，但仍建议只发给可信的人。")
            }
        }
        item(key = "note", contentType = "infoNote") {
            Box(Modifier.padding(top = 16.dp)) {
                InfoNote("播放记录、进度和下载文件保存在本机。同步将自建歌单、收藏和平台账号 Cookie 写入你选择的 Gist、Gitee 或 WebDAV；本地音频只有主动上传时才会传到云端。")
            }
        }
        item(key = "footer", contentType = "footer") {
            Text(
                "平台层 music-lib / 7cc4467b\n学习项目，请遵守各音乐平台服务条款。",
                Modifier.fillMaxWidth().padding(top = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AppHeader(versionName: String, versionCode: Long) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painter = painterResource(R.drawable.neta_music_icon),
            contentDescription = null,
            modifier = Modifier
                .size(84.dp)
                .clip(RoundedCornerShape(24.dp)),
        )
        Text(stringResource(R.string.app_name), Modifier.padding(top = 10.dp), style = MaterialTheme.typography.headlineSmall)
        Text(
            "版本 ${versionName.ifBlank { BuildConfig.VERSION_NAME }}（构建 $versionCode） · AGPL-3.0",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 新版本卡片：展示更新说明和下载进度；后台下载完成后可在这里主动安装。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UpdateCard(update: UpdateUiState, actions: MusicActions, progress: () -> Float) {
    val info = update.info ?: return
    val scheme = MaterialTheme.colorScheme
    val content = scheme.onTertiaryContainer
    val downloading = update.phase == UpdatePhase.Downloading || update.phase == UpdatePhase.Verifying
    val ready = update.phase == UpdatePhase.Ready
    val primaryColors = ButtonDefaults.buttonColors(containerColor = scheme.tertiary, contentColor = scheme.onTertiary)
    val zone = ZoneId.systemDefault()
    val releaseSummary = remember(info.size, info.publishedAt, zone) {
        "${formatSize(info.size)} · ${formatDate(info.publishedAt, zone)}"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(scheme.tertiaryContainer)
            .padding(20.dp),
    ) {
        Text("发现新版本 ${info.versionName}（构建 ${info.versionCode}）", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = content)
        Text(
            releaseSummary,
            style = MaterialTheme.typography.bodyMedium,
            color = content.copy(alpha = 0.85f),
        )
        if (info.notes.isNotEmpty()) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                info.notes.forEach { note ->
                    Text("· $note", style = MaterialTheme.typography.bodyMedium, color = content)
                }
            }
        }
        if (downloading || ready) {
            UpdateDownloadProgress(update.phase, update.phaseLabel, progress, content)
        } else {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.Verified, null, Modifier.size(18.dp), tint = content)
                Text(
                    if (update.backgroundDownload) "下载后自动校验 SHA-256、包名和版本号，完成后可在这里安装。"
                    else "下载后自动校验 SHA-256、包名和版本号，然后自动打开系统安装器。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = content,
                )
            }
        }
        if (update.error.isNotBlank()) {
            Box(Modifier.padding(top = 10.dp)) { InfoNote(update.error, "red") }
        }
        if (update.permissionRequired) {
            Box(Modifier.padding(top = 10.dp)) { InfoNote("需要允许本应用安装未知来源的应用。", "amber") }
        }
        if (!downloading) {
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val textButton: @Composable (String, () -> Unit) -> Unit = { label, onClick ->
                    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp).tvFocusRing(CircleShape)) {
                        Text(label, color = content)
                    }
                }
                val mainButton: @Composable (String, () -> Unit) -> Unit = { label, onClick ->
                    Button(onClick = onClick, colors = primaryColors, modifier = Modifier.heightIn(min = 48.dp).tvFocusRing(CircleShape)) {
                        Text(label)
                    }
                }
                when {
                    ready -> {
                        if (update.permissionRequired) textButton("允许安装", actions::openInstallPermission)
                        mainButton(if (update.backgroundDownload) "安装更新" else "重新打开安装器", actions::installUpdate)
                    }
                    update.phase == UpdatePhase.Error -> {
                        textButton("浏览器打开", actions::openReleasePage)
                        mainButton("重试下载", actions::downloadUpdate)
                    }
                    else -> mainButton("立即下载", actions::downloadUpdate)
                }
            }
        }
    }
}

private fun formatDate(value: String, zone: ZoneId): String = runCatching {
    DateTimeFormatter.ISO_LOCAL_DATE.withZone(zone).format(Instant.parse(value))
}.getOrDefault(value)

/** 只有进度绘制和百分比文字观察下载数值，版本说明与按钮不跟着刷新。 */
@Composable
private fun UpdateDownloadProgress(phase: UpdatePhase, label: String, progress: () -> Float, color: Color) {
    LinearWavyProgressIndicator(
        progress = { if (phase == UpdatePhase.Downloading) progress() else 1f },
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        color = color,
        trackColor = color.copy(alpha = 0.2f),
    )
    UpdateProgressLabel(phase, label, progress, color)
}

@Composable
private fun UpdateProgressLabel(phase: UpdatePhase, label: String, progress: () -> Float, color: Color) {
    val percent by remember(phase, progress) {
        derivedStateOf { if (phase == UpdatePhase.Downloading) (progress().coerceIn(0f, 1f) * 100).toInt() else 100 }
    }
    Text(
        when (phase) {
            UpdatePhase.Ready -> "校验通过，安装包已就绪"
            UpdatePhase.Verifying -> "正在校验安装包…"
            else -> "${label.ifBlank { "正在下载" }} $percent% · 下载后校验 SHA-256"
        },
        Modifier.padding(top = 6.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = color.copy(alpha = 0.85f),
    )
}
