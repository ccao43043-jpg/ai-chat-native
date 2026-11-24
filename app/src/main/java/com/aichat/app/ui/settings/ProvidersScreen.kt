package com.aichat.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aichat.app.domain.Diag
import com.aichat.app.domain.model.ProviderConfig
import com.aichat.app.domain.model.ProviderType
import com.aichat.app.ui.common.LocalUiDeps
import com.aichat.app.ui.common.SoftField
import com.aichat.app.ui.theme.Palette
import com.aichat.app.net.ModelCatalog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.coroutines.launch

@Composable
fun ProvidersScreen(onBack: () -> Unit, toast: (String) -> Unit) {
    val deps = LocalUiDeps.current
    val s by deps.settings.settings.collectAsStateWithLifecycle()
    val editor = remember(deps) { ProviderEditor(deps.settings, deps.keys) }
    var editing by remember { mutableStateOf<ProviderConfig?>(null) }
    var isNew by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }

    editing?.let { p ->
        ProviderEditScreen(p, isNew, editor, onDone = { editing = null }, toast = toast)
        return
    }
    SettingsPageScaffold("服务商", onBack, actions = { TextButton(onClick = { adding = true }, Modifier.testTag("provider-add")) { Text("添加") } }) {
        s.providers.forEach { p ->
            ProviderRow(
                p.name,
                listOf(p.baseUrl, if (deps.keys.has(p.id)) "已填 Key" else "未填 Key", "${p.models.count { it.isNotBlank() }} 个模型").joinToString(" · "),
                "provider-${p.id}",
            ) { isNew = false; editing = p }
        }
    }
    if (adding) AlertDialog(onDismissRequest = { adding = false }, title = { Text("添加服务商") }, text = {
        Column {
            ProviderPresets.all.forEach { pr ->
                TextButton(onClick = {
                    adding = false; isNew = true
                    editing = editor.newFromPreset(pr, s.providers.map { it.id }.toSet())
                }) { Text(pr.name) }
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = { adding = false }) { Text("取消") } })
}

@Composable
private fun ProviderEditScreen(p: ProviderConfig, isNew: Boolean, editor: ProviderEditor, onDone: () -> Unit, toast: (String) -> Unit) {
    val deps = LocalUiDeps.current
    val scope = rememberCoroutineScope()
    val init = remember(p.id) { ProviderDraft.of(p, deps.keys.has(p.id)) }
    var name by rememberSaveable(p.id) { mutableStateOf(init.name) }
    var type by rememberSaveable(p.id) { mutableStateOf(init.type) }
    var base by rememberSaveable(p.id) { mutableStateOf(init.baseUrl) }
    var models by rememberSaveable(p.id) { mutableStateOf(init.modelsText) }
    var image by rememberSaveable(p.id) { mutableStateOf(init.imageModel) }
    var key by rememberSaveable(p.id) { mutableStateOf(init.keyText) }
    var showKey by remember { mutableStateOf(false) }
    var meta by remember(p.id) { mutableStateOf(p.modelMeta) }
    var fetching by remember { mutableStateOf(false) }
    var fetchOpen by remember { mutableStateOf(false) }
    var fetched by remember { mutableStateOf(emptyList<String>()) }
    var fetchCat by remember { mutableStateOf("all") }
    var fetchQuery by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var priceBusy by remember { mutableStateOf(false) }
    val isOr = base.contains("openrouter.ai", ignoreCase = true) || p.presetId == "openrouter"
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val draft = ProviderDraft(name, type, base, models, image, key)
    val dirty = draft != init

    BackHandler { if (dirty) confirmDiscard = true else onDone() }

    SettingsPageScaffold(if (isNew) "添加服务商" else p.name, { if (dirty) confirmDiscard = true else onDone() }, actions = {
        TextButton(onClick = {
            scope.launch {
                when (val r = editor.save(p, draft, meta)) {
                    is ProviderEditor.Result.Invalid -> toast(r.message)
                    is ProviderEditor.Result.Ok -> { toast(r.message); onDone() }
                }
            }
        }, Modifier.testTag("provider-save")) { Text("保存") }
    }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("名称", color = Palette.Fg2, fontSize = 12.sp)
            SoftField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("请求格式", color = Palette.Fg, fontSize = 14.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProviderType.entries.forEach { t ->
                    FilterChip(type == t, { type = t }, label = { Text(when (t) { ProviderType.OPENAI -> "OpenAI 兼容"; ProviderType.ANTHROPIC -> "Anthropic"; ProviderType.GEMINI -> "Gemini" }, fontSize = 13.sp) })
                }
            }
            Text("Base URL", color = Palette.Fg2, fontSize = 12.sp)
            SoftField(base, { base = collapseScheme(base, it) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("pe-base"),
                supportingText = { Text(when (type) {
                    ProviderType.OPENAI -> "填写到版本路径这一级（通常以 /v1 结尾），程序会自动拼接 /chat/completions。"
                    ProviderType.ANTHROPIC -> "一般为 https://api.anthropic.com（程序拼接 /v1/messages）。"
                    ProviderType.GEMINI -> "一般为 https://generativelanguage.googleapis.com/v1beta。"
                }, fontSize = 12.sp) })
            Text("API Key", color = Palette.Fg2, fontSize = 12.sp)
            SoftField(key, { key = KeyMask.sanitizeEdit(key, it) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("pe-key"),
                visualTransformation = if (showKey || key == KeyMask.MASK) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "隐藏" else "显示") } },
                supportingText = { Text("保持「已保存」= 不改；清空 = 删除；输入新值 = 替换。Key 只保存在本机加密存储，只发往上面的 Base URL。", fontSize = 12.sp) })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("模型", color = Palette.Fg2, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    if (!Regex("^https?://.+").matches(base.trim())) { toast("请先填写正确的 Base URL"); return@TextButton }
                    if (key.isBlank()) { toast("请先填写 API Key"); return@TextButton }
                    if (fetching) return@TextButton
                    fetching = true
                    scope.launch {
                        try {
                            val client = deps.client ?: throw IllegalStateException("当前环境不能获取模型列表")
                            deps.settings.registerProvider(p.id, base.trim(), ProviderPresets.authFor(type))
                            if (key != KeyMask.MASK) deps.keys.set(p.id, key.trim())
                            val probe = p.copy(type = type, baseUrl = base.trim())
                            val (ids, m) = client.listModels(probe)
                            if (ids.isEmpty()) throw IllegalStateException("接口没有返回任何模型")
                            val merged = LinkedHashMap<String, JsonElement>(meta)
                            m.forEach { (k, v) -> merged[k] = v }
                            meta = JsonObject(merged)
                            fetched = ids
                            fetchCat = "all"; fetchQuery = ""; picked = emptySet(); fetchOpen = true
                            if (isOr) toast("已获取 ${ids.size} 个模型，可切换分类查看")
                            else toast("已获取 ${ids.size} 个模型")
                        } catch (e: Exception) {
                            toast("获取失败：" + (e.message ?: "未知错误") + "。部分服务商不提供模型列表，可手动输入。")
                        } finally { fetching = false }
                    }
                }, enabled = !fetching) { Text(if (fetching) "获取中…" else "获取模型列表") }
                if (isOr) TextButton(onClick = {
                    val ids = editor.parseModels(models)
                    if (ids.isEmpty()) { toast("请先添加模型"); return@TextButton }
                    if (priceBusy) return@TextButton
                    priceBusy = true
                    scope.launch {
                        try {
                            val client = deps.client ?: throw IllegalStateException("当前环境不能刷新价格")
                            val next = LinkedHashMap<String, JsonElement>(meta)
                            var ok = 0
                            for (id in ids.take(20)) {
                                try {
                                    val r = client.openRouterEndpoints(p.copy(type = type, baseUrl = base.trim()), id)
                                    val floor = r["floor"]
                                    val eps = r["endpoints"]
                                    val applied = ModelCatalog.applyFloorToMeta(JsonObject(next), id, floor as? JsonObject, eps)
                                    if (applied != null) { next.clear(); applied.forEach { (k, v) -> next[k] = v } }
                                    ok++
                                } catch (t: Exception) { Diag.warn("ModelCatalog", "服务商 $id 的价格下限写回失败（其余服务商继续）", t) }
                            }
                            meta = JsonObject(next)
                            toast(if (ok > 0) "价格已刷新（$ok）" else "刷新失败")
                        } catch (e: Exception) { toast("刷新失败：" + (e.message ?: "")) }
                        finally { priceBusy = false }
                    }
                }, enabled = !priceBusy) { Text(if (priceBusy) "刷新中…" else "刷新价格") }
            }
            var addModel by remember(p.id) { mutableStateOf("") }
            val modelIds = editor.parseModels(models)
            if (modelIds.isEmpty()) Text("还没有模型。获取列表或手动添加。", color = Palette.Fg3, fontSize = 12.sp)
            modelIds.forEach { id ->
                val m = meta[id] as? JsonObject
                val price = ModelCatalog.formatModelPrice(m)
                val cat = when (ModelCatalog.classifyModel(id, m)) {
                    "text" -> "文本"; "image" -> "图片"; "video" -> "视频"; "embedding" -> "嵌入"; "speech", "transcription" -> "音频"; else -> ""
                }
                val sub = listOf(cat, if (price == "—") "" else price).filter { it.isNotEmpty() }.joinToString(" · ")
                Surface(color = Palette.Surface2, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().testTag("pe-models")) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(id, color = Palette.Fg, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (sub.isNotEmpty()) Text(sub, color = Palette.Fg3, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("移除", color = Palette.Fg3, fontSize = 12.sp, modifier = Modifier.clickable {
                            models = modelIds.filter { it != id }.joinToString("\n")
                        }.padding(start = 8.dp))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftField(addModel, { addModel = it }, singleLine = true, modifier = Modifier.weight(1f), label = { Text("手动添加模型") })
                TextButton(onClick = {
                    val id = addModel.trim()
                    if (id.isEmpty()) return@TextButton
                    if (id !in modelIds) models = (modelIds + id).joinToString("\n")
                    addModel = ""
                }) { Text("添加") }
            }
            Text("生图模型", color = Palette.Fg2, fontSize = 12.sp)
            SoftField(image, { image = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (!isNew) {
                HorizontalDivider()
                TextButton(onClick = { confirmDelete = true }) { Text("删除此服务商", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("删除服务商？") },
        text = { Text("将删除「${p.name}」及其 API Key。") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; scope.launch { editor.delete(p); onDone() } }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, title = { Text("放弃修改？") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; onDone() }) { Text("放弃") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } })
    if (fetchOpen) ModelFetchSheet(
        fetched, meta, fetchCat, { fetchCat = it }, fetchQuery, { fetchQuery = it }, picked,
        { id -> picked = if (id in picked) picked - id else picked + id },
        editor.parseModels(models).toSet(),
        onAdd = {
            val add = picked.filter { it !in editor.parseModels(models) }
            if (add.isNotEmpty()) models = (editor.parseModels(models) + add).joinToString("\n")
            fetchOpen = false
            toast(if (add.isEmpty()) "没有选择新模型" else "已添加 ${add.size} 个模型，记得保存")
        },
        onClose = { fetchOpen = false },
    )
}

/**
 * Base URL 输入框预填的是真文本 `https://`（自定义预设的默认值，与旧版一致），
 * 用户直接往里粘贴完整 URL 就会得到 `https://http://host/v1` —— 保存时会因为
 * "不是合法 URL" 或"地址与登记源不一致"莫名失败。
 *
 * 这里把**紧挨着的重复 scheme 前缀**压掉（保留最后一个）：
 *   `https://http://a/v1` → `http://a/v1`
 *   `https://https://a/v1` → `https://a/v1`
 * 而 `https://httpsproxy.example.com` 这种正常域名不会被误伤（后面不是 `https://`）。
 */

@Composable
private fun ModelFetchSheet(
    fetched: List<String>, meta: JsonObject, cat: String, onCat: (String) -> Unit,
    query: String, onQuery: (String) -> Unit, picked: Set<String>, onPick: (String) -> Unit,
    existing: Set<String>, onAdd: () -> Unit, onClose: () -> Unit,
) {
    fun catOf(id: String): String {
        val c = ModelCatalog.classifyModel(id, meta[id] as? JsonObject)
        return if (c == "speech" || c == "transcription") "audio" else c
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(color = Palette.Surface, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), modifier = Modifier.fillMaxWidth().padding(top = 48.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("模型列表", color = Palette.Fg, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    SoftField(query, onQuery, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("搜索") })
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("all" to "全部", "text" to "文本", "image" to "图片", "video" to "视频", "embedding" to "嵌入", "audio" to "音频").forEach { (k, label) ->
                            FilterChip(cat == k, { onCat(k) }, label = { Text(label, fontSize = 13.sp) })
                        }
                    }
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        fetched.filter { id ->
                            (query.isBlank() || id.contains(query, true)) && (cat == "all" || catOf(id) == cat)
                        }.take(200).forEach { id ->
                            val m = meta[id] as? JsonObject
                            val price = ModelCatalog.formatModelPrice(m)
                            val on = id in picked || id in existing
                            Surface(onClick = { if (id !in existing) onPick(id) }, color = Palette.Surface2, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    Text(id, color = if (on) Palette.Accent else Palette.Fg, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(listOf(when (catOf(id)) { "text" -> "文本"; "image" -> "图片"; "video" -> "视频"; "embedding" -> "嵌入"; "audio" -> "音频"; else -> "" }, if (price == "—") "" else price, if (id in existing) "已添加" else if (id in picked) "已选" else "").filter { it.isNotEmpty() }.joinToString(" · "), color = Palette.Fg3, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onClose) { Text("取消", color = Palette.Fg2) }
                        TextButton(onClick = onAdd) { Text("添加所选") }
                    }
                }
            }
        }
    }
}

internal fun collapseScheme(old: String, next: String): String {
    if (next == old) return next
    val dup = Regex("^(?:https?://)+(?=https?://)").find(next) ?: return next
    return next.removeRange(0, dup.value.length)
}


@Composable
private fun ProviderRow(name: String, sub: String, tag: String, onClick: () -> Unit) {
    Surface(
        color = Palette.Surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp).clickable(onClick = onClick).testTag(tag),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(Palette.AccentSoft), contentAlignment = Alignment.Center) {
                Text(name.take(1), fontSize = 11.sp, color = Palette.AccentText)
            }
            Column(Modifier.weight(1f)) {
                Text(name, fontSize = 14.sp, color = Palette.Fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, fontSize = 11.sp, color = Palette.Fg3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
