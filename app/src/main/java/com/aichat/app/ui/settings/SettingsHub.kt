package com.aichat.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aichat.app.ui.common.Pressable
import com.aichat.app.ui.theme.Radius
import com.aichat.app.ui.theme.Space

/**
 * 枢纽页顶部的概览卡：一件事的现状 + 一个入口。
 *
 * 刻意只放「一眼要知道的」：当前用的是谁、配了几个 Key、状态如何。
 * 详细数字进子页面，避免枢纽页变成报表。
 */
@Composable
fun HubOverviewCard(
    title: String,
    subtitle: String,
    status: String,
    onClick: () -> Unit,
    icon: ImageVector,
) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(Radius.card), color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Pressable(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = Space.page, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(cs.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) { Icon(icon, null, Modifier.size(22.dp), tint = cs.onPrimaryContainer) }
                    Spacer(Modifier.width(Space.row))
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = cs.onSurfaceVariant)
                }
                Spacer(Modifier.size(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(cs.primary))
                    Spacer(Modifier.width(Space.tight + 2.dp))
                    Text(status, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

/** 分组标题 + 卡片式分组。卡片内部不画分隔线，靠行高与留白分开。 */
@Composable
fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = Space.group)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Space.tight, bottom = Space.titleGap),
        )
        Surface(shape = RoundedCornerShape(Radius.card), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = Space.tight)) { content() }
        }
    }
}

/**
 * 设置行：左边 32 圆底图标，中间标题/副标题，右边当前值 + 箭头。
 *
 * 右侧的当前值让用户不用点进去就知道现在是什么状态。
 */
@Composable
fun SettingsTileRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    tag: String? = null,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val minHeight = if (subtitle == null) Space.rowOne else Space.rowTwo
    Pressable(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().let { if (tag != null) it.testTag(tag) else it },
    ) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = minHeight)
                .padding(horizontal = Space.row, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.row),
        ) {
            Box(
                Modifier.size(Space.iconBox).clip(RoundedCornerShape(16.dp)).background(cs.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, Modifier.size(Space.icon), tint = cs.onSecondaryContainer) }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (value != null) {
                Text(value, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
        }
    }
}
