package io.neta.music.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.PaletteStyle
import kotlin.math.abs

/** 图标服饰中的朱红，默认品牌配色及动态取色失败时使用。 */
val BrandSeed = Color(0xFFC93426)

enum class ColorSource(val label: String, val hint: String) {
    Cover("跟随封面", "歌曲配色"),
    Wallpaper("系统壁纸", "Android 8.1+"),
    Brand("朱红暖金", "默认"),
    Custom("自选颜色", "色板"),
}

enum class SchemeStyle(val label: String, val palette: PaletteStyle) {
    TonalSpot("柔和", PaletteStyle.TonalSpot),
    Vibrant("鲜明", PaletteStyle.Vibrant),
    Expressive("表现力", PaletteStyle.Expressive),
    Fidelity("保真", PaletteStyle.Fidelity),
}

/** 布局模式：自动按设备判断，其余同时把屏幕方向锁到对应形态。 */
enum class LayoutMode(val label: String) {
    Auto("自动"),
    Phone("手机竖屏"),
    Tablet("平板横屏"),
    Tv("电视横屏"),
}

/**
 * 界面文字大小：把 [scale] 乘到系统字号上，只放大文字，dp 尺寸不变。
 * 电视三米开外看不清小字时往上调；列表和卡片会随着文字变高。
 */
enum class FontScale(val label: String, val scale: Float) {
    Standard("标准", 1f),
    Large("大", 1.15f),
    Larger("更大", 1.3f),
    Largest("特大", 1.5f),
    Extended175("175%", 1.75f),
    Extended200("200%", 2f),
    Extended225("225%", 2.25f),
    ;

    companion object {
        fun of(scale: Float): FontScale = entries.minByOrNull { abs(it.scale - scale) } ?: Standard
    }
}

/** 外观设置的完整快照，主题层只读这一个对象。 */
@Immutable
data class Appearance(
    val theme: String = "system",
    val layout: LayoutMode = LayoutMode.Auto,
    val colorSource: ColorSource = ColorSource.Brand,
    val customSeed: Int = BrandSeed.toArgb(),
    val browseStyle: SchemeStyle = SchemeStyle.Vibrant,
    val playerStyle: SchemeStyle = SchemeStyle.Vibrant,
    val contrast: Float = 0f,
    val fontScale: FontScale = FontScale.Larger,
    val sidebarIconDp: Int = 36,
    val sidebarTextSp: Int = 20,
    val lyricsFollowAppText: Boolean = true,
    val lyricsFontScale: FontScale = FontScale.Larger,
    val reduceMotion: Boolean = false,
    val lastSeed: Int = 0,
) {
    fun isDark(system: Boolean): Boolean = when (theme) {
        "dark" -> true
        "light" -> false
        else -> system
    }
}

/** 自选颜色的色板：覆盖色相环，彩度足够让 HCT 生成有区分度的配色。 */
val CustomSeedSwatches = listOf(
    BrandSeed, Color(0xFFD49A30), Color(0xFF9E6B3D), Color(0xFFE8833A),
    Color(0xFF7D4BA0), Color(0xFFD9668F), Color(0xFF006B5F), Color(0xFF1F6F78),
    Color(0xFF3A5BA0), Color(0xFF5E7D3A),
)
