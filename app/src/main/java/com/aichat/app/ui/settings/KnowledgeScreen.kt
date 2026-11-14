package com.aichat.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.js.jobj
import com.aichat.app.plugins.knowledge.Knowledge
import com.aichat.app.ui.common.LocalUiDeps
import com.aichat.app.ui.theme.Space
import kotlinx.coroutines.launch

/**
 * 知识库：按世界存（`knowledge_world_<worldId>.json`），五个内置分类。
 *
 * 这里是**唯一的编辑入口** —— 旧版把条目挂在世界面板里，但注入逻辑（`KnowledgePlugin`）
 * 只认"当前世界 + 本次提问"的检索结果，所以只要写进这个文件就会被检索到。
 */
@Composable
fun KnowledgeScreen(onBack: () -> Unit, toast: (String) -> Unit) {
    val deps = LocalUiDeps.current
    val store = deps.knowledge
    val worlds by deps.library.worlds.collectAsState()
    var worldId by remember { mutableStateOf(worlds.firstOrNull()?.id) }
    var doc by remember { mutableStateOf<JObj?>(null) }
    var catIdx by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<Triple<String, String, String>?>(null) } // (entryId, title, content)
    val scope = rememberCoroutineScope()

    fun reload(wid: String?) {
        doc = if (store == null || wid == null) null else Knowledge.load(store, wid)
    }

    // worlds 是异步来的，第一次拿到时补一次加载
    remember(worlds, store) {
        if (worldId == null && worlds.isNotEmpty()) { worldId = worlds.first().id }
        if (doc == null) reload(worldId)
        true
    }

    fun save(next: JObj) {
        val wid = worldId ?: return
        if (store == null) { toast("知识库未就绪"); return }
        Knowledge.save(store, next)
        doc = Knowledge.load(store, wid)
    }

    val cats = (doc?.get("categories") as? List<*>)?.filterIsInstance<JObj>().orEmpty()
    val cat = cats.getOrNull(catIdx)

    SettingsPageScaffold(
        title = "知识库",
        onBack = onBack,
        actions = {
            if (cat != null) {
                TextButton(onClick = { editing = Triple("", "", "") }) { Text("新建") }
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.page), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "条目按当前世界保存，聊天时按提问自动检索并注入（最多 400 token）。没填过内容的世界不会注入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (worlds.isEmpty()) {
                Text("还没有世界模板。先去「世界模板」建一个。", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                worlds.forEach { w ->
                    AssistChip(
                        onClick = { worldId = w.id; catIdx = 0; reload(w.id) },
                        label = { Text("${w.emoji} ${w.name}".trim(), style = MaterialTheme.typography.labelLarge) },
                    )
                }
            }
            if (cats.isEmpty()) {
                Text("这个世界的知识库还是空的。", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            // 五个分类 + 世界很多时一行放不下 —— 横向滚动，别让它溢出边界
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                cats.forEachIndexed { i, c ->
                    AssistChip(
                        onClick = { catIdx = i },
                        label = { Text("${c["name"]} (${((c["entries"] as? List<*>) ?: emptyList<Any?>()).size})") },
                        enabled = i != catIdx,
                    )
                }
            }
            val entries = (cat?.get("entries") as? List<*>)?.filterIsInstance<JObj>().orEmpty()
            if (entries.isEmpty()) {
                Text("「${cat?.get("name")}」还没有条目。点右上「新建」。", style = MaterialTheme.typography.bodySmall)
            }
            entries.forEach { e ->
                KnowledgeRow(
                    title = Js.str(e["title"]),
                    content = Js.str(e["content"]),
                    keywords = (e["keywords"] as? List<*>)?.joinToString(" ") { Js.str(it) }.orEmpty(),
                    onEdit = { editing = Triple(Js.str(e["id"]), Js.str(e["title"]), Js.str(e["content"])) },
                    onDelete = {
                        runCatching { Knowledge.removeEntry(doc!!, cat!!["id"], e["id"]) }
                            .onSuccess { save(it) }
                            .onFailure { toast("删除失败：" + (it.message ?: "")) }
                    },
                )
            }
        }
    }

    val edit = editing
    if (edit != null && doc != null && cat != null) {
        val (entryId, t0, c0) = edit
        KnowledgeEditorDialog(
            title0 = t0, content0 = c0,
            onDismiss = { editing = null },
            onSave = { title, content ->
                editing = null
                if (title.isBlank() && content.isBlank()) { toast("标题和正文至少填一个"); return@KnowledgeEditorDialog }
                val patch = jobj("title" to title, "content" to content)
                runCatching {
                    if (entryId.isEmpty()) Knowledge.addEntry(doc!!, cat["id"], patch)
                    else Knowledge.updateEntry(doc!!, cat["id"], entryId, patch)
                }.onSuccess { save(it); toast("已保存") }
                    .onFailure { toast("保存失败：" + (it.message ?: "")) }
            },
        )
    }
}

@Composable
private fun KnowledgeRow(
    title: String, content: String, keywords: String,
    onEdit: () -> Unit, onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title.ifBlank { "(无标题)" }, style = MaterialTheme.typography.bodyLarge)
        if (content.isNotBlank()) {
            Text(
                content.take(120) + if (content.length > 120) "…" else "",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        }
        if (keywords.isNotBlank()) {
            Text(keywords, style = MaterialTheme.typography.labelSmall, color = cs.primary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onEdit) { Text("编辑") }
            TextButton(onClick = onDelete) { Text("删除", color = cs.error) }
        }
    }
}

@Composable
private fun KnowledgeEditorDialog(
    title0: String, content0: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var title by remember { mutableStateOf(title0) }
    var content by remember { mutableStateOf(content0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (title0.isEmpty() && content0.isEmpty()) "新建条目" else "编辑条目") },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(title, { title = it }, label = { Text("标题") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    content, { content = it }, label = { Text("内容") },
                    minLines = 4, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(title.trim(), content.trim()) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
