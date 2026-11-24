package com.aichat.app.ui.chat

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aichat.app.ui.theme.Palette

internal val MenuItemPad = PaddingValues(horizontal = 16.dp, vertical = 0.dp)

@Composable
internal fun MenuSection(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = Palette.Fg3,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
internal fun MenuEntry(label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyMedium, color = Palette.Fg) },
        onClick = onClick,
        contentPadding = MenuItemPad,
        // 48dp 是 Material 3 菜单项的默认高度；原来的 40dp 在长按弹出的
        // 溢出菜单里偏挤，连续点几项容易误触相邻项。
        modifier = Modifier.height(48.dp),
    )
}

@Composable
internal fun ChatOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    conv: com.aichat.app.domain.model.Conversation?,
    extraMenu: List<Pair<String, () -> Unit>>,
    onSettings: () -> Unit,
    onExport: () -> Unit,
    onConvPlugins: () -> Unit,
    onContext: () -> Unit,
    onRp: () -> Unit,
    onMemory: () -> Unit,
    onInspect: () -> Unit,
    onChapter: () -> Unit,
    onLore: () -> Unit,
    onImage: () -> Unit,
    onAffect: () -> Unit,
    onBackup: () -> Unit,
) {
    DropdownMenu(expanded, onDismiss) {
        MenuSection("对话")
        if (conv != null) MenuEntry("导出本对话", onExport)
        if (conv != null) MenuEntry("本对话插件", onConvPlugins)
        if (conv != null) MenuEntry("上下文体检", onContext)
        if (conv != null) MenuEntry("据此生图", onImage)
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            color = Palette.Line,
        )
        MenuSection("RP")
        MenuEntry("RP 状态", onRp)
        MenuEntry("记忆", onMemory)
        MenuEntry("RP 检视", onInspect)
        MenuEntry("RP 章节", onChapter)
        MenuEntry("世界书", onLore)
        MenuEntry("好感度", onAffect)
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            color = Palette.Line,
        )
        MenuSection("其他")
        MenuEntry("设置", onSettings)
        MenuEntry("数据与备份", onBackup)
        extraMenu.forEach { (label, act) -> MenuEntry(label) { onDismiss(); act() } }
    }
}