package io.neta.music.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.material3.dynamicColorScheme

private val BrandCopper = Color(0xFF9E6B3D)
private val BrandGold = Color(0xFFD49A30)
private val BrandNeutral = Color(0xFF887B71)
private val BrandNeutralVariant = Color(0xFF90796B)

/** 界面、播放页和小组件共用色板；MaterialKolor 负责深浅模式、色阶与对比度。 */
internal fun appColorScheme(
    appearance: Appearance,
    seed: Color,
    dark: Boolean,
    forPlayer: Boolean = false,
): ColorScheme {
    val brand = appearance.colorSource == ColorSource.Brand
    val scheme = dynamicColorScheme(
        seedColor = seed,
        isDark = dark,
        secondary = BrandCopper.takeIf { brand },
        tertiary = BrandGold.takeIf { brand },
        neutral = BrandNeutral.takeIf { brand },
        neutralVariant = BrandNeutralVariant.takeIf { brand },
        style = (if (forPlayer) appearance.playerStyle else appearance.browseStyle).palette,
        contrastLevel = appearance.contrast.toDouble(),
        specVersion = ColorSpec.SpecVersion.SPEC_2025,
    )
    // 深色详情页也使用暖暗底色，不使用 Material 默认的纯黑最低层。
    return if (dark) scheme.copy(surfaceContainerLowest = scheme.surface) else scheme
}
