package io.neta.music.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.neta.music.ui.MusicActions
import io.neta.music.ui.components.AdaptiveSheet
import io.neta.music.ui.components.ConnectedToggleGroup
import io.neta.music.ui.components.SwitchRow
import io.neta.music.ui.settings.FontScaleOptions
import io.neta.music.ui.theme.LocalAppearance
import io.neta.music.ui.theme.LyricsAlignment
import io.neta.music.ui.theme.LyricsTextScope

/** 播放页和全屏歌词共用的 Aa 入口，不离开当前歌曲即可调节字号和对齐。 */
@Composable
internal fun LyricsSizeButton(actions: MusicActions, idle: IdleState, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    KeepPlayerAwake(idle, open)
    ToolbarIconButton(Icons.Filled.TextFields, "歌词字号与对齐", { open = true }, modifier)
    if (open) LyricsSizeSheet(actions, onDismiss = { open = false })
}

@Composable
internal fun LyricsSizeSheet(actions: MusicActions, onDismiss: () -> Unit) {
    val appearance = LocalAppearance.current
    val selected = if (appearance.lyricsFollowAppText) appearance.fontScale else appearance.lyricsFontScale
    AdaptiveSheet(onDismiss = onDismiss, title = "歌词字号与对齐", subtitle = "立即生效，与外观设置同步") {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FontScaleOptions(selected) { size ->
                actions.setLyricsFontScale(size)
                actions.setLyricsFollowAppText(false)
            }
            ConnectedToggleGroup(
                options = LyricsAlignment.entries,
                selected = appearance.lyricsAlignment,
                onSelect = actions::setLyricsAlignment,
                label = { it.label },
                modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow(
                title = "跟随应用文字",
                checked = appearance.lyricsFollowAppText,
                onCheckedChange = actions::setLyricsFollowAppText,
                index = 0,
                count = 1,
            )
            Text(
                "选择字号会单独调整歌词，不改变其他界面文字；对齐作用于播放页歌词列表和全屏歌词动画（星潮·银河、巨幕、跳一跳除外）。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LyricsTextScope {
                Text(
                    "听见音乐，也看清每一句",
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = appearance.lyricsAlignment.align,
                )
            }
        }
    }
}
