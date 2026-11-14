package com.aichat.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.aichat.app.domain.model.AppSettings
import com.aichat.app.domain.model.Conversation
import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.rp.RpCacheCore
import com.aichat.app.plugins.rp.RpCachePlugin
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * RP 章节：看已有章节，手动生成 / 合并。
 *
 * 自动路径要等「超出预算」或「到触发阈值」才动，长篇想提前压一次时没有入口；
 * 章节越积越多会把 system 越撑越长，旧版面板里有对应的「生成 / 合并」按钮，这里补上。
 */
@Composable
fun RpChapterDialog(
    conv: Conversation,
    settings: AppSettings,
    plugin: RpCachePlugin?,
    onSaveState: (JsonObject) -> Unit,
    onDismiss: () -> Unit,
    onToast: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<Boolean>(false) }
    val chapters = remember(conv.rpState) {
        runCatching { RpCacheCore.chaptersOf(rpStateOf(conv)) }.getOrDefault(emptyList())
    }
    val coveredTotal = chapters.sumOf { (it["coveredIds"] as? List<*>)?.size ?: 0 }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("章节摘要") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "长篇会把最早的一批对话压成章节，只追加不改写；超出章节上限时合并成一章。" +
                        "已有 ${chapters.size} 章，覆盖 $coveredTotal 条消息。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (chapters.isEmpty()) {
                    Text("还没有章节。多聊几轮后点「生成下一章」，或在 RP 检视里把「保留轮数」调小。",
                        style = MaterialTheme.typography.bodyMedium)
                } else {
                    chapters.reversed().forEach { ch ->
                        val no = (ch["no"] as? Number)?.toInt() ?: 0
                        val src = Js.str(ch["source"]).ifEmpty { "auto" }
                        val merged = (ch["mergedFrom"] as? List<*>)?.size ?: 0
                        val count = (ch["coveredIds"] as? List<*>)?.size ?: 0
                        Column {
                            Text(
                                "#" + no.toString().padStart(3, '0') + " · " +
                                    (if (src == "merge") "合并（$merged 章）" else if (src == "manual") "手动" else "自动") +
                                    " · $count 条",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            val body = Js.str(ch["content"])
                            Text(body.take(160) + if (body.length > 160) "…" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (busy) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("正在请求模型…", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        enabled = !busy && plugin != null,
                        onClick = {
                            val p = plugin ?: return@TextButton
                            busy = true
                            scope.launch {
                                val r = p.summarizeNow(conv, settings, merge = false)
                                busy = false
                                r.onSuccess { m ->
                                    onSaveState(Js.toJson(m.state) as? JsonObject ?: conv.rpState)
                                    onToast("已追加章节 #" + m.no.toString().padStart(3, '0'))
                                }.onFailure { e -> onToast("生成失败：" + (e.message ?: "未知错误")) }
                            }
                        },
                        modifier = Modifier.testTag("rp-chapter-generate"),
                    ) { Text("生成下一章") }
                    TextButton(
                        enabled = !busy && plugin != null && chapters.size >= 2,
                        onClick = {
                            val p = plugin ?: return@TextButton
                            busy = true
                            scope.launch {
                                val r = p.summarizeNow(conv, settings, merge = true)
                                busy = false
                                r.onSuccess { m ->
                                    onSaveState(Js.toJson(m.state) as? JsonObject ?: conv.rpState)
                                    onToast("已合并为章节 #" + m.no.toString().padStart(3, '0'))
                                }.onFailure { e -> onToast("合并失败：" + (e.message ?: "未知错误")) }
                            }
                        },
                        modifier = Modifier.testTag("rp-chapter-merge"),
                    ) { Text("合并全部") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") } },
    )
}

private fun rpStateOf(conv: Conversation): JObj =
    com.aichat.app.plugins.rp.RpState.normalizeState(Js.fromJson(conv.rpState) as? JObj ?: JObj())
