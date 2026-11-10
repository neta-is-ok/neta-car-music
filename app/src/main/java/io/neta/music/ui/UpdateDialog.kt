package io.neta.music.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.neta.music.data.update.UpdatePhase
import io.neta.music.data.update.UpdateUiState
import io.neta.music.data.update.formatSize
import io.neta.music.ui.adaptive.DialogAvoidsFold
import io.neta.music.ui.components.LocalAppWindow
import io.neta.music.ui.components.tvFocusRing

/**
 * 发现更新后自动下载；可主动收起下载进度继续使用应用。
 * 下载完成后在应用前台自动打开安装器，不依赖车机通知入口。
 *
 * @param suppressed 当前正停在“关于与更新”页面时不叠弹窗，由页面里的更新卡片走同样流程
 */
@Composable
fun UpdateDialog(
    update: UpdateUiState,
    suppressed: Boolean,
    actions: MusicActions,
    progress: () -> Float,
) {
    val info = update.info ?: return
    val busy = update.phase == UpdatePhase.Downloading || update.phase == UpdatePhase.Verifying
    val visible = !suppressed &&
        (!update.backgroundDownload || update.phase == UpdatePhase.Ready || update.phase == UpdatePhase.Error) &&
        update.phase != UpdatePhase.Idle &&
        update.phase != UpdatePhase.Checking
    if (!visible) return
    val tv = LocalAppWindow.current.isTv
    val actionFocus = remember { FocusRequester() }
    LaunchedEffect(tv, update.phase, update.permissionRequired) {
        if (tv) runCatching { actionFocus.requestFocus() }
    }
    AlertDialog(
        // 通过明确的“后台下载”操作收起，不把误触或返回键当作下载选择。
        onDismissRequest = {},
        title = {
            DialogAvoidsFold()
            Text(
                "发现新版本 ${info.versionName}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "构建 ${info.versionCode} · ${formatSize(info.size)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (info.notes.isNotEmpty()) {
                    Column(
                        Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        info.notes.forEach { note ->
                            Text("· $note", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (busy || update.phase == UpdatePhase.Ready) {
                    UpdateDialogProgress(update.phase, update.phaseLabel, progress)
                }
                Text(
                    if (busy) "可以切换到后台下载，继续使用应用。" else "校验通过后会自动打开系统安装器。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (update.permissionRequired) {
                    Text(
                        "允许本应用安装未知来源的应用后会自动继续安装。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (update.error.isNotBlank()) {
                    Text(
                        update.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            when {
                update.permissionRequired -> Button(
                    onClick = actions::openInstallPermission,
                    modifier = Modifier.focusRequester(actionFocus).tvFocusRing(CircleShape),
                ) { Text("允许安装") }
                update.phase == UpdatePhase.Error -> Button(
                    onClick = actions::downloadUpdate,
                    modifier = Modifier.focusRequester(actionFocus).tvFocusRing(CircleShape),
                ) { Text("重试下载") }
                update.phase == UpdatePhase.Ready -> Button(
                    onClick = actions::installUpdate,
                    modifier = Modifier.focusRequester(actionFocus).tvFocusRing(CircleShape),
                ) { Text("重新打开安装器") }
                else -> Unit
            }
        },
        dismissButton = {
            if (busy) TextButton(
                onClick = actions::backgroundUpdate,
                modifier = Modifier.focusRequester(actionFocus).tvFocusRing(CircleShape),
            ) { Text("后台下载") }
        },
    )
}

@Composable
private fun UpdateDialogProgress(phase: UpdatePhase, label: String, progress: () -> Float) {
    val percent by remember(phase, progress) {
        derivedStateOf {
            if (phase == UpdatePhase.Downloading) (progress().coerceIn(0f, 1f) * 100).toInt() else 100
        }
    }
    Column(Modifier.padding(top = 4.dp)) {
        LinearWavyProgressIndicator(
            progress = { if (phase == UpdatePhase.Downloading) progress() else 1f },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "${label.ifBlank { if (phase == UpdatePhase.Ready) "校验通过，正在打开系统安装器" else "正在下载" }} $percent%",
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
