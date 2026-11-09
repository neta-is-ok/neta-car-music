package io.neta.music.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.neta.music.ui.MusicChip
import io.neta.music.ui.theme.FontScale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FontScaleOptions(selected: FontScale, onSelect: (FontScale) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FontScale.entries.forEach { size ->
            MusicChip(size.label, size == selected, description = "文字大小：${(size.scale * 100).roundToInt()}%") {
                onSelect(size)
            }
        }
    }
}

/** 拖动时更新数值，松开后写入设置，避免每个像素都触发持久化。 */
@Composable
internal fun AppearanceSizeSlider(
    title: String,
    value: Int,
    range: IntRange,
    step: Int,
    unit: String,
    onChange: (Int) -> Unit,
) {
    var pending by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("${pending.roundToInt()}$unit", style = MaterialTheme.typography.titleMedium)
        }
        Slider(
            value = pending,
            onValueChange = { pending = it },
            onValueChangeFinished = { onChange(pending.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first) / step - 1,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = title },
        )
    }
}
