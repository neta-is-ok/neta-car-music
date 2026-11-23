package io.neta.music.ui.player.stage

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.neta.music.data.RECENT_COLORS_LIMIT
import io.neta.music.ui.player.blobColorCache
import io.neta.music.ui.player.coverBlobColors

/** 解析 ；格式不对返回 null。 */
internal fun parseHexColor(text: String): Color? {
    val trimmed = text.trim()
    if (trimmed.length != 7 || trimmed[0] != '#') return null
    val rgb = trimmed.substring(1).toLongOrNull(16) ?: return null
    return Color((rgb or 0xFF000000).toInt())
}

/** 颜色写成 。 */
internal fun Color.toHex(): String = "#" + listOf(red, green, blue).joinToString("") { c -> (c * 255).toInt().toString(16).padStart(2, '0').uppercase() }

/** 封面量化色不够 6 个再用两种底色上的文字色补。 */
internal fun coverSwatches(colors: StageColors): List<Color> {
    val out = LinkedHashMap<String, Color>()
    (listOf(colors.coverVibrant) + colors.cover + listOf(colors.coverMuted, colors.onCoverVibrant, colors.onCoverMuted))
        .forEach { color -> if (out.size < 6) out.putIfAbsent(color.toHex(), color) }
    return out.values.toList()
}

/** 记一个“最近用过”的颜色：放到最前面，去掉重复的，最多留 [RECENT_COLORS_LIMIT] 个。 */
internal fun pushRecentColor(recent: List<String>, color: Color): List<String> {
    val hex = color.toHex()
    return (listOf(hex) + recent.filter { !it.equals(hex, ignoreCase = true) }).take(RECENT_COLORS_LIMIT)
}

/**
 * 舞台配色：当前配色（播放页里是 PlayerTheme）加封面 [cover] 的量化色，量化色取到之前用配色补。
 * 舞台、缩略图和面板（颜色项的初值、取色面板的“从封面取”）共用。
 */
@Composable
internal fun rememberStageColors(cover: String): StageColors {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val coverColors by produceState(blobColorCache.get(cover), cover) { value = coverBlobColors(context, cover) }
    return remember(scheme.onSurface, scheme.primary, scheme.secondary, scheme.tertiary, scheme.primaryContainer, scheme.surface, coverColors) {
        stageColors(scheme, coverColors)
    }
}

/** 电视遥控旋钮加速滑条：每转 [TV_SLIDER_REPEATS_PER_BOOST] 格提速一档，最多 [TV_SLIDER_MAX_BOOST] 档。 */
internal const val TV_SLIDER_REPEATS_PER_BOOST = 4
internal const val TV_SLIDER_MAX_BOOST = 5
