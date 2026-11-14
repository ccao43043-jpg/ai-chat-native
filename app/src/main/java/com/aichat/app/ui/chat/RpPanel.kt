package com.aichat.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aichat.app.domain.model.Conversation
import com.aichat.app.domain.model.World
import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.rp.RpState
import kotlinx.serialization.json.JsonPrimitive

/**
 * RP 状态块编辑（最小可用版）。
 *
 * 只做一件事：让 `conv.rpState` 能被填出来。填出来之后 rpcache 的 `buildSystem` 才会产出
 * `[WORLD]`/`[CHARACTER]`… 这些段，affect 的 `[AFFECT]` 才会有上一轮状态可 decay。
 *
 * 块清单直接来自 [RpState.BLOCK_DEFS]（与 JS 的 STATIC_DEFS 同源），所以这里不维护第二份定义。
 * 章节摘要、长期记忆的自动生成不在这个面板里——那些由 rpcache 的 plan 决定，不该手填。
 */
@Composable
fun RpPanelDialog(
    conv: Conversation,
    worlds: List<World>,
    onDismiss: () -> Unit,
    onSave: (Map<String, String>) -> Unit,
    /** 选中某个世界模板时额外回调一次（用来补开场白；块本身仍由 onSave 统一写） */
    onPickWorld: ((World) -> Unit)? = null,
) {
    val defs = remember { RpState.BLOCK_DEFS.filter { it["layer"] == "L1" } }
    val original = remember(conv.id, conv.rpState) { blockContents(conv) }
    val edited = remember(conv.id) { mutableStateMapOf<String, String>() }
    var picking by remember { mutableStateOf(false) }

    fun valueOf(id: String): String = edited[id] ?: original[id] ?: ""

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("RP 状态") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "这里填的内容会进系统提示词的固定层，按「几乎不改」排序：越靠前越稳定，缓存越省。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (worlds.isNotEmpty()) {
                    // 世界模板就是一组预填块：点一下把四/七个块填进来，再按需要改。
                    Box {
                        TextButton(onClick = { picking = true }) {
                            Text("从世界模板填入（${worlds.size}）")
                        }
                        DropdownMenu(picking, { picking = false }) {
                            worlds.forEach { w ->
                                DropdownMenuItem(
                                    text = { Text("${w.emoji} ${w.name}".trim()) },
                                    onClick = {
                                        picking = false
                                        onPickWorld?.invoke(w)
                                        World.BLOCK_IDS.forEach { id ->
                                            val text = (w.blocks[id]?.get("content") as? JsonPrimitive)
                                                ?.takeIf { it.isString }?.content
                                            if (!text.isNullOrBlank()) edited[id] = text
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                defs.forEach { def ->
                    val id = def["id"] as? String ?: return@forEach
                    val title = def["title"] as? String ?: id
                    val hint = def["hint"] as? String ?: ""
                    OutlinedTextField(
                        value = valueOf(id),
                        onValueChange = { edited[id] = it },
                        label = { Text("$title · [${def["tag"]}]") },
                        placeholder = { Text(hint, style = MaterialTheme.typography.bodySmall) },
                        supportingText = { Text(hint, style = MaterialTheme.typography.bodySmall) },
                        minLines = 2,
                        maxLines = 6,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val changed = edited.filter { (id, v) -> v != original[id].orEmpty() }
                if (changed.isNotEmpty()) onSave(changed)
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 从 `conv.rpState` 取出各块的当前内容（缺省空串）。 */
internal fun blockContents(conv: Conversation): Map<String, String> {
    val st = Js.fromJson(conv.rpState) as? JObj ?: return emptyMap()
    val out = HashMap<String, String>()
    (st["blocks"] as? List<*>)?.forEach { b ->
        val m = b as? JObj ?: return@forEach
        val id = m["id"] as? String ?: return@forEach
        out[id] = (m["content"] as? String).orEmpty()
    }
    return out
}
