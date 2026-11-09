package io.neta.music.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** 应用字号缩放前的系统密度，供侧边栏和独立歌词字号使用。 */
internal val LocalSystemDensity = staticCompositionLocalOf<Density?> { null }

@Composable
internal fun systemTextDensity(): Density = LocalSystemDensity.current ?: LocalDensity.current

/** 只改变歌词区域的 sp 换算，不缩放播放控件；嵌套使用也不会重复放大。 */
@Composable
internal fun LyricsTextScope(content: @Composable () -> Unit) {
    val appearance = LocalAppearance.current
    val base = systemTextDensity()
    val density = if (appearance.lyricsFollowAppText) {
        LocalDensity.current
    } else {
        remember(base, appearance.lyricsFontScale) {
            Density(base.density, base.fontScale * appearance.lyricsFontScale.scale)
        }
    }
    CompositionLocalProvider(LocalDensity provides density, content = content)
}
