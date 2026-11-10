package io.neta.music.ui.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.neta.music.aggregate.PlaybackMode
import io.neta.music.ui.Cover
import io.neta.music.ui.MusicActions
import io.neta.music.ui.MusicUiState
import io.neta.music.ui.openLabel
import io.neta.music.ui.components.LocalAppWindow
import io.neta.music.ui.components.PlayButtonSize
import io.neta.music.ui.components.PlayPauseButton
import io.neta.music.ui.components.WavySeekBar

/** 底部播放条高度（不含系统导航栏），Snackbar 要让开这么多。 */
val BottomPlayerBarHeight = 88.dp

/** 播放条按折痕分两段时每段至少这么宽：左段要放封面、歌名和上一首 / 播放 / 下一首。 */
internal val BottomPlayerBarSplitMin = 260.dp

/**
 * 平板与车机统一使用底部播放条，把横向空间留给内容。
 * [visible] 为 false 时只保留同高占位，不读取进度、不保留波浪/时间观察。
 */
@Composable
fun BottomPlayerBar(
    state: MusicUiState,
    position: State<Long>,
    actions: MusicActions,
    onOpenQueue: () -> Unit,
    visible: Boolean = true,
) {
    if (!visible) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(BottomPlayerBarHeight),
        )
        return
    }
    val song = state.current ?: return
    val container = MaterialTheme.colorScheme.surfaceContainer
    // 播放条横跨内容区，折痕按它自己的位置测量（扣掉左右留白后）
    val anchor = rememberFoldAnchor()
    // 只在竖折痕（书本、双竖）上分两段；左段放不下封面、歌名和三个按钮时当没有折痕。
    // 没有折痕时不读 anchor：平板首次摆放后不必为它多重组一次
    val folded = LocalAppWindow.current.fold != null
    val split = if (folded) rememberFoldSplit(anchor, minSegment = BottomPlayerBarSplitMin)?.takeIf { it.vertical } else null
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .background(container)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .height(BottomPlayerBarHeight)
            .padding(horizontal = 16.dp)
            .foldAnchor(anchor),
    ) {
        // 分两段时左页只有约 290dp（Pixel Fold），竖着的平板整条也只有约 570dp：
        // 封面、间距和播放键都收小一号，给歌名多留点地方
        val compact = split != null || maxWidth < BottomPlayerBarCompactBelow
        val info: @Composable (Modifier) -> Unit = { modifier ->
            Row(
                modifier
                    .clip(RoundedCornerShape(16.dp))
                    // 整块（封面 + 歌名）按设置去播放页或海报墙；右边的“打开播放页”按钮总是打开播放页
                    .clickable(onClickLabel = state.miniPlayerTarget.openLabel, onClick = actions::openNowPlaying)
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
            ) {
                Cover(song.cover, song.title, if (compact) 48.dp else 56.dp, corner = if (compact) 12.dp else 14.dp)
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${song.artist} · ${state.currentSource?.providerName ?: song.source}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        val transport: @Composable () -> Unit = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else BottomPlayerBarGap),
            ) {
                IconButton(onClick = { actions.transport("previous") }) { Icon(Icons.Filled.SkipPrevious, "上一首") }
                PlayPauseButton(state.playing, { actions.transport("play") }, size = if (compact) PlayButtonSize.Small else PlayButtonSize.Medium)
                IconButton(onClick = { actions.transport("next") }) { Icon(Icons.Filled.SkipNext, "下一首") }
            }
        }
        val seek: @Composable (Modifier) -> Unit = { modifier ->
            WavySeekBar(
                position = position,
                duration = state.duration,
                playing = state.playing,
                onSeek = actions::seek,
                modifier = modifier,
                live = state.current?.isLive == true,
            )
        }
        val tail: @Composable () -> Unit = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else BottomPlayerBarGap),
            ) {
                IconButton(onClick = onOpenQueue) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "播放队列") }
                IconButton(onClick = actions::openPlayer) { Icon(Icons.Filled.OpenInFull, "打开播放页") }
            }
        }
        if (split == null) {
            // 播放键组和右边两个钮按实际大小先量，剩下的再分给歌名和进度条（见 bottomBarInfoWidth）
            Layout(
                content = {
                    info(Modifier)
                    transport()
                    seek(Modifier)
                    tail()
                },
                modifier = Modifier.fillMaxSize(),
            ) { measurables, constraints ->
                val height = constraints.maxHeight
                val gap = (if (compact) BottomPlayerBarCompactGap else BottomPlayerBarGap).roundToPx()
                val loose = Constraints(maxWidth = constraints.maxWidth, maxHeight = height)
                val transportP = measurables[1].measure(loose)
                val tailP = measurables[3].measure(loose)
                val rest = (constraints.maxWidth - transportP.width - tailP.width - gap * 3).coerceAtLeast(0)
                val infoW = bottomBarInfoWidth(rest.toDp()).roundToPx().coerceIn(0, rest)
                val seekW = rest - infoW
                val infoP = measurables[0].measure(Constraints(minWidth = infoW, maxWidth = infoW, maxHeight = height))
                val seekP = measurables[2].measure(Constraints(minWidth = seekW, maxWidth = seekW, maxHeight = height))
                layout(constraints.maxWidth, height) {
                    var x = 0
                    listOf(infoP, transportP, seekP, tailP).forEach { p ->
                        p.place(x, (height - p.height) / 2)
                        x += p.width + gap
                    }
                }
            }
        } else {
            // 书本、双竖：左页封面、歌名、上一首 / 播放 / 下一首，右页进度、队列、展开；折痕两侧留空，什么都不压
            val density = LocalDensity.current
            val start = with(density) { split.firstEnd.toDp() }
            val gap = with(density) { (split.secondStart - split.firstEnd).toDp() }
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.width(start), verticalAlignment = Alignment.CenterVertically) {
                    info(Modifier.weight(1f))
                    transport()
                }
                Spacer(Modifier.width(gap))
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BottomPlayerBarGap),
                ) {
                    seek(Modifier.weight(1f))
                    tail()
                }
            }
        }
    }
}

/** 播放条里各块之间的间距；紧凑时收到 8dp，省下来给歌名。 */
private val BottomPlayerBarGap = 12.dp
internal val BottomPlayerBarCompactGap = 8.dp

/** 播放条内容区（扣掉左右 16dp）窄于这个宽度时用小一号的封面和播放键：竖着的平板约 570dp，横着的 840dp 起约 710dp。 */
internal val BottomPlayerBarCompactBelow = 640.dp

/**
 * 歌名块最宽 280dp；地方够时至少 184dp——扣掉 4dp 内边距、48dp 封面和 8dp 间距后还有 120dp，
 * 放得下 7 个 titleMedium 汉字（16sp + 0.15sp 字距），但进度条要先留够 [BottomPlayerBarSeekMin]。
 */
internal val BottomPlayerBarInfoMax = 280.dp
internal val BottomPlayerBarInfoMin = 184.dp
internal val BottomPlayerBarSeekMin = 120.dp

/**
 * 不分两段时歌名块的宽：[rest] 是扣掉播放键组、队列 / 展开两个钮和间距后，歌名和进度条一起分的宽度。
 * 歌名块拿一半，最多 [BottomPlayerBarInfoMax]；一半不到 [BottomPlayerBarInfoMin] 时补到它，
 * 但不挤得进度条短于 [BottomPlayerBarSeekMin]——再窄就两边一起收。
 */
internal fun bottomBarInfoWidth(rest: Dp): Dp {
    val floor = minOf(BottomPlayerBarInfoMin, (rest - BottomPlayerBarSeekMin).coerceAtLeast(0.dp))
    return (rest * 0.5f).coerceIn(floor, BottomPlayerBarInfoMax)
}

fun modeLabel(mode: PlaybackMode): String = when (mode) {
    PlaybackMode.Auto -> "自动"
    PlaybackMode.Priority -> "优先"
    PlaybackMode.Pinned -> "固定"
}
