package io.neta.music.ui.adaptive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.neta.music.ui.TopTab
import io.neta.music.ui.components.LocalAppWindow
import io.neta.music.ui.components.tvFocusRing

/** 手机、平板与车机共用顶部导航；Tab 可横向滚动，设置始终留在右侧。 */
@Composable
fun HomeTopBar(selected: String, onSelect: (String) -> Unit, onSettings: () -> Unit) {
    val window = LocalAppWindow.current
    val tabs = TopTab.entries
    val selectedIndex = tabs.indexOfFirst { it.route == selected }.coerceAtLeast(0)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = window.gutter, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 窗口变窄后重新定位当前 Tab，避免选中的入口留在视口外。
        key(window.widthDp) {
            PrimaryScrollableTabRow(
                selectedTabIndex = selectedIndex,
                modifier = Modifier.weight(1f).semantics { contentDescription = "首页导航" },
                containerColor = MaterialTheme.colorScheme.surface,
                edgePadding = 0.dp,
                divider = {},
            ) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = index == selectedIndex,
                        onClick = { onSelect(tab.route) },
                        modifier = Modifier.heightIn(min = 72.dp).tvFocusRing(),
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = {
                            Text(
                                tab.label,
                                modifier = Modifier.padding(horizontal = 12.dp),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = if (index == selectedIndex) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false,
                            )
                        },
                    )
                }
            }
        }
        IconButton(
            onClick = onSettings,
            modifier = Modifier.size(48.dp).tvFocusRing(CircleShape),
        ) {
            Icon(Icons.Outlined.Settings, "设置", Modifier.size(26.dp))
        }
    }
}
