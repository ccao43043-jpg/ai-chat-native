package com.aichat.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aichat.app.data.repo.JsonDocStore
import com.aichat.app.domain.model.AppSettings
import com.aichat.app.domain.model.Conversation
import com.aichat.app.plugins.rp.RpMemoryCore
import com.aichat.app.plugins.rp.RpMemoryOps
import com.aichat.app.plugins.rp.RpMemoryPlugin
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 记忆面板：列 / 置顶 / 停用 / 删除 / 立即提取。
 *
 * 数据在 `rp-memory.json`，桶键见 [RpMemoryOps.bucketKey]（有角色卡时跨对话共享）。
 * 旧版 `rpmemory.js` 的面板有「编辑正文」「批量清空」，这里先不做——它们对缓存收益没有影响。
 */
@Composable
fun RpMemoryPanelDialog(
    conv: Conversation,
    settings: AppSettings,
    store: JsonDocStore?,
    plugin: RpMemoryPlugin?,
    onDismiss: () -> Unit,
    onToast: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val key = remember(conv.id, conv.personaName) { RpMemoryOps.bucketKey(conv.id, conv.personaName) }
    var doc by remember { mutableStateOf(JsonObject(emptyMap())) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }

    androidx.compose.runtime.LaunchedEffect(store, key, reload) {
        doc = store?.load() ?: JsonObject(emptyMap())
    }
    val rows = remember(doc, key) { RpMemoryOps.sorted(RpMemoryOps.entries(doc, key)) }

    fun write(next: JsonObject) {
        doc = next
        if (store?.save(next) == false) onToast("写入失败：存储不可写")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记忆") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "这些条目会按相关性拼进本轮提问；置顶的必进，停用的不参与。桶：$key",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(8.dp))
                if (store == null) {
                    Text("没有接入记忆存储（演示模式）。", style = MaterialTheme.typography.bodySmall)
                    return@Column
                }
                if (rows.isEmpty()) {
                    Text("还没有记忆条目。多聊几轮，或点下面「立即提取」。", style = MaterialTheme.typography.bodySmall)
                }
                rows.forEach { e ->
                    val id = (e["id"] as? JsonPrimitive)?.content ?: return@forEach
                    val type = (e["type"] as? JsonPrimitive)?.content ?: "detail"
                    val text = (e["text"] as? JsonPrimitive)?.content ?: ""
                    val pinned = (e["pinned"] as? JsonPrimitive)?.content == "true"
                    val disabled = (e["disabled"] as? JsonPrimitive)?.content == "true"
                    val kws = (e["keywords"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(
                            "[${RpMemoryCore.TYPE_CN[type] ?: type}] $text",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (kws.isNotEmpty()) {
                            Text(
                                kws.take(6).joinToString(" "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            AssistChip(
                                onClick = { write(RpMemoryOps.setPinned(doc, key, id, !pinned)) },
                                label = { Text(if (pinned) "已置顶" else "置顶", style = MaterialTheme.typography.labelSmall) },
                            )
                            AssistChip(
                                onClick = { write(RpMemoryOps.setDisabled(doc, key, id, !disabled)) },
                                label = { Text(if (disabled) "已停用" else "停用", style = MaterialTheme.typography.labelSmall) },
                            )
                            AssistChip(
                                onClick = { write(RpMemoryOps.remove(doc, key, id)) },
                                label = { Text("删除", style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        enabled = !busy && plugin != null,
                        onClick = {
                            if (plugin == null) { onToast("记忆插件未启用"); return@TextButton }
                            busy = true
                            scope.launch {
                                val r = plugin.extractNow(conv, settings)
                                busy = false
                                r.fold(
                                    onSuccess = { (added, total) ->
                                        onToast(if (added > 0) "提取完成：新增 $added 条，共 $total 条" else "没有新记忆")
                                        reload++
                                    },
                                    onFailure = { onToast("提取失败：${it.message ?: "未知错误"}") },
                                )
                            }
                        },
                    ) { Text(if (busy) "提取中…" else "立即提取") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
