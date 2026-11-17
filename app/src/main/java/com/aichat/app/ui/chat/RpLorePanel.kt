package com.aichat.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.aichat.app.domain.model.Conversation
import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.rp.RpLoreCore
import com.aichat.app.plugins.rp.RpState
import kotlinx.serialization.json.JsonObject

/**
 * 世界书（lorebook）编辑：一整段文本，按旧版语法 `[条目名]` 分条。
 *
 * 触发/冻结/预算这一套逻辑早就在 `RpLoreCore` 里，但界面上没有入口 —— 用户只能靠单测证明它存在。
 * 旧版 RP 面板里对应的是同一张卡：改完保存，冻结片段要重建一次才按新文本走。
 */
@Composable
fun RpLoreDialog(
    conv: Conversation,
    onSaveState: (JsonObject) -> Unit,
    onDismiss: () -> Unit,
    onToast: (String) -> Unit,
) {
    val st = remember(conv.rpState) { rpStateOfLore(conv) }
    var text by remember(conv.rpState) { mutableStateOf((st["loreText"] as? String) ?: "") }
    val frozen = RpLoreCore.firedOf(st).size
    val editedWhileFrozen = st["loreEditedWhileFrozen"] == true
    val original = (st["loreText"] as? String) ?: ""

    fun apply(rebuild: Boolean) {
        var next: JObj = RpLoreCore.setLore(st, text, System.currentTimeMillis())
        if (rebuild) next = RpLoreCore.rebuildFrozen(next)
        onSaveState(Js.toJson(next) as? JsonObject ?: conv.rpState)
        val entries = RpLoreCore.loreOf(next).size
        onToast(
            when {
                rebuild -> "已保存并重建：$entries 条，冻结已清空"
                // 解析失败必须说出来：以前这里报「已保存：0 条」，用户以为世界书生效了，
                // 实际上一轮都没注入过（最常见的成因是行首多打了空格）。
                text.isNotBlank() && entries == 0 ->
                    "已保存文本，但没解析出条目：每条要有正文，标题行下面紧接着写内容（只有标题会被丢弃）"
                else -> "已保存文本：$entries 条，下一轮按触发词生效"
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("世界书") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "每条形如「## 名字 | 触发词1, 触发词2 | 属性」，**标题下一行起写正文**（只有标题没有正文的条目会被丢弃）：" +
                        "触发词写 * 或留空表示常驻（每轮都进 system），否则按关键词触发；" +
                        "属性用分号分隔，如 priority=7; depth=2; prob=60; sticky=3; cooldown=4; group=g1。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (frozen > 0) {
                    Text(
                        "当前有 $frozen 条已冻结（内容按触发时的样子发出去）。改完文本要「重建冻结」才会换新内容。" +
                            if (editedWhileFrozen) "  ← 已经改过但还没重建。" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("世界书文本") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp).testTag("rp-lore-text"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { apply(rebuild = false) }, modifier = Modifier.testTag("rp-lore-save")) {
                        Text("保存文本")
                    }
                    TextButton(
                        onClick = { apply(rebuild = true) },
                        enabled = frozen > 0,
                        modifier = Modifier.testTag("rp-lore-rebuild"),
                    ) { Text("保存并重建") }
                }
                if (text.isBlank()) {
                    Text("留空 = 这份对话没有世界书，触发规则不会生效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private fun rpStateOfLore(conv: Conversation): JObj =
    RpState.normalizeState(Js.fromJson(conv.rpState) as? JObj ?: JObj())
