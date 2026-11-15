package com.aichat.app.ui.conversation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.annotation.DrawableRes
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import com.aichat.app.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aichat.app.domain.model.ConversationMeta
import com.aichat.app.ui.common.pressScale
import com.aichat.app.ui.theme.Palette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 抽屉底部各项的计数（旧版 v0.29 的「已启用 4」「8 助手 · 6 提示词」「10 个模板」）。
 * 全部可空：拿不到就不显示，不猜。
 */
data class DrawerSummary(
    val enabledPlugins: Int? = null,
    val personaCount: Int? = null,
    val promptCount: Int? = null,
    val templateCount: Int? = null,
    val worldCount: Int? = null,
    val hasBalanceKey: Boolean? = null,
)

/**
 * 对话抽屉。结构对齐旧版 v0.29：
 *   白底「＋ 新对话」大按钮 → 搜索框 → 对话列表 → 最近使用 → 设置/插件 → 更多（助手/世界/模板/生图/用量/余额）
 * 测试依赖的 tag 保持不变：drawer / drawer-new / drawer-personas / drawer-settings / conv-<id> / rename-field。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationDrawer(
    index: List<ConversationMeta>,
    currentId: String?,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onExport: ((String) -> Unit)? = null,
    onOpenPersonas: (() -> Unit)? = null,
    summary: DrawerSummary? = null,
    onOpenPlugins: (() -> Unit)? = null,
    onOpenWorlds: (() -> Unit)? = null,
    onOpenTemplates: (() -> Unit)? = null,
    onOpenUsage: (() -> Unit)? = null,
    onOpenBalance: (() -> Unit)? = null,
    onOpenImage: (() -> Unit)? = null,
) {
    var renaming by remember { mutableStateOf<ConversationMeta?>(null) }
    var deleting by remember { mutableStateOf<ConversationMeta?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    var recentOpen by rememberSaveable { mutableStateOf(true) }
    var moreOpen by rememberSaveable { mutableStateOf(true) }

    val shown = remember(index, query) {
        val q = query.trim()
        if (q.isEmpty()) index else index.filter {
            it.title.contains(q, ignoreCase = true) ||
                (it.model?.contains(q, ignoreCase = true) == true)
        }
    }

    ModalDrawerSheet(Modifier.testTag("drawer")) {
        Column(Modifier.fillMaxWidth().weight(1f)) {
            // 白底大按钮：旧版这里是整个抽屉最显眼的元素，也是「新建」唯一入口
            Surface(
                onClick = onNew,
                shape = RoundedCornerShape(12.dp),
                color = Color.White,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 10.dp)
                    .height(48.dp).testTag("drawer-new"),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp), tint = Color(0xFF18181B))
                    Spacer(Modifier.width(8.dp))
                    Text("新对话", color = Color(0xFF18181B), fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyLarge)
                }
            }

            // 搜索：旧版是 surface-2 的圆角框，44dp 高
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Palette.Surface2,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(44.dp).testTag("drawer-search"),
            ) {
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Search, null, Modifier.size(18.dp), tint = Palette.Fg3)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text("搜索对话", style = MaterialTheme.typography.bodyMedium, color = Palette.Fg3)
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Palette.Fg),
                            cursorBrush = SolidColor(Palette.Accent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            if (shown.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (query.isEmpty()) "还没有对话" else "没有匹配「$query」的对话",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Palette.Fg2,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (query.isEmpty()) "点击上方「新对话」开始" else "换个词试试",
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.Fg3,
                        )
                    }
                }
            } else {
                LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    items(shown, key = { it.id }) { m ->
                        ConvRow(
                            m, m.id == currentId,
                            onClick = { onSelect(m.id) },
                            onRename = { renaming = m },
                            onDelete = { deleting = m },
                            onExport = onExport?.let { f -> { f(m.id) } },
                        )
                    }
                }
            }

            HorizontalDivider(color = Palette.Line)

            // ── 最近使用 ──
            SectionHeader("最近使用", recentOpen) { recentOpen = !recentOpen }
            if (recentOpen) {
                Text(
                    "使用世界、助手或模板后会出现在这里",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Fg3,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                )
            }

            DrawerEntry(Icons.Filled.Settings, "设置", Palette.Fg2, tag = "drawer-settings", onClick = onOpenSettings)
            onOpenPlugins?.let {
                DrawerEntry(
                    R.drawable.ic_drawer_plugin, "插件", Palette.Fg2, tag = "drawer-plugins",
                    trailing = summary?.enabledPlugins?.let { n -> "已启用 $n" },
                    onClick = it,
                )
            }

            // ── 更多 ──
            SectionHeader("更多", moreOpen) { moreOpen = !moreOpen }
            if (moreOpen) {
                onOpenPersonas?.let {
                    DrawerEntry(
                        Icons.Filled.Person, "助手", Palette.Fg2, tag = "drawer-personas",
                        trailing = summary?.let { s ->
                            listOfNotNull(
                                s.personaCount?.let { n -> "$n 助手" },
                                s.promptCount?.let { n -> "$n 提示词" },
                            ).joinToString(" · ").ifEmpty { null }
                        },
                        onClick = it,
                    )
                }
                onOpenWorlds?.let {
                    DrawerEntry(
                        R.drawable.ic_drawer_world, "世界", Palette.Fg2, tag = "drawer-worlds",
                        trailing = summary?.worldCount?.let { n -> "$n 个世界" } ?: "世界模板",
                        onClick = it,
                    )
                }
                onOpenTemplates?.let {
                    DrawerEntry(
                        Icons.Filled.List, "模板", Palette.Fg2, tag = "drawer-templates",
                        trailing = summary?.templateCount?.let { n -> "$n 个模板" },
                        onClick = it,
                    )
                }
                onOpenImage?.let {
                    DrawerEntry(R.drawable.ic_drawer_image, "生图", Palette.Fg2, tag = "drawer-image", trailing = "文字生成图片", onClick = it)
                }
                onOpenUsage?.let {
                    DrawerEntry(R.drawable.ic_drawer_usage, "用量", Palette.Fg2, tag = "drawer-usage", trailing = "Token 统计", onClick = it)
                }
                onOpenBalance?.let {
                    DrawerEntry(
                        R.drawable.ic_drawer_wallet, "余额", Palette.Fg2, tag = "drawer-balance",
                        trailing = when (summary?.hasBalanceKey) {
                            true -> "查看余额"
                            false -> "未配置 Key"
                            null -> null
                        },
                        onClick = it,
                    )
                }
            }
            Spacer(Modifier.height(8.dp).navigationBarsPadding())
        }
    }

    renaming?.let { m ->
        var t by rememberSaveable(m.id) { mutableStateOf(m.title) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("重命名对话") },
            text = { OutlinedTextField(t, { t = it }, singleLine = true, modifier = Modifier.testTag("rename-field")) },
            confirmButton = { TextButton(onClick = { onRename(m.id, t); renaming = null }, enabled = t.isNotBlank()) { Text("确定") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } })
    }
    deleting?.let { m ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除对话？") },
            text = { Text("将删除「${m.title}」，不可恢复。") },
            confirmButton = { TextButton(onClick = { onDelete(m.id); deleting = null }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}

/** 可折叠分组标题：左侧灰色小标题，右侧箭头 */
@Composable
private fun SectionHeader(label: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).clickable(onClick = onToggle).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Palette.Fg3, modifier = Modifier.weight(1f))
        Icon(
            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            null, Modifier.size(18.dp), tint = Palette.Fg3,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConvRow(m: ConversationMeta, selected: Boolean, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onExport: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    val src = remember { MutableInteractionSource() }
    Surface(
        color = if (selected) Palette.AccentSoft else Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
            .pressScale(src)
            .combinedClickable(
                interactionSource = src,
                indication = LocalIndication.current,
                onClick = onClick,
                onLongClick = { menu = true },
            ).testTag("conv-${m.id}"),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text((m.personaEmoji?.let { "$it " } ?: "") + m.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) Palette.Accent else Palette.Fg)
                Text(listOf(m.model, formatTime(m.updatedAt)).filter { it.isNotEmpty() }.joinToString(" · "), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Palette.Fg3)
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.MoreVert, "更多", Modifier.size(18.dp), tint = Palette.Fg3)
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; onRename() })
                    if (onExport != null) DropdownMenuItem(text = { Text("导出") }, onClick = { menu = false; onExport() })
                    DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

/** 底部导航行：图标 + 文案 + 右侧计数 */
@Composable
private fun DrawerEntry(
    @DrawableRes icon: Int,
    label: String,
    tint: Color,
    tag: String,
    trailing: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onClick)
            .padding(horizontal = 20.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = tint)
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Palette.Fg, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.bodySmall, color = Palette.Fg3, fontSize = 12.sp)
        }
    }
}

@Composable
private fun DrawerEntry(
    icon: ImageVector,
    label: String,
    tint: Color,
    tag: String,
    trailing: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onClick)
            .padding(horizontal = 20.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Palette.Fg, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.bodySmall, color = Palette.Fg3, fontSize = 12.sp)
        }
    }
}

/** 今天显示 HH:mm，今年显示 M/d，否则 yyyy/M/d */
fun formatTime(ms: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (ms <= 0) return ""
    val d = Date(ms); val n = Date(nowMs)
    val day = SimpleDateFormat("yyyyMMdd", Locale.ROOT)
    val year = SimpleDateFormat("yyyy", Locale.ROOT)
    return when {
        day.format(d) == day.format(n) -> SimpleDateFormat("HH:mm", Locale.ROOT).format(d)
        year.format(d) == year.format(n) -> SimpleDateFormat("M/d", Locale.ROOT).format(d)
        else -> SimpleDateFormat("yyyy/M/d", Locale.ROOT).format(d)
    }
}
