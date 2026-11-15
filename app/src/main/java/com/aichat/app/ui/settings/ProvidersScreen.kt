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
import com.aichat.app.domain.model.ProviderConfig
import com.aichat.app.domain.model.ProviderType
import com.aichat.app.ui.common.LocalUiDeps
import com.aichat.app.ui.common.SoftField
import com.aichat.app.ui.theme.Palette
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
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val draft = ProviderDraft(name, type, base, models, image, key)
    val dirty = draft != init

    BackHandler { if (dirty) confirmDiscard = true else onDone() }

    SettingsPageScaffold(if (isNew) "添加服务商" else p.name, { if (dirty) confirmDiscard = true else onDone() }, actions = {
        TextButton(onClick = {
            scope.launch {
                when (val r = editor.save(p, draft)) {
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
            Text("模型", color = Palette.Fg2, fontSize = 12.sp)
            SoftField(models, { models = it }, minLines = 3, maxLines = 12, modifier = Modifier.fillMaxWidth().testTag("pe-models"))
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
                Text(name.take(1), fontSize = 11.sp, color = Palette.Accent)
            }
            Column(Modifier.weight(1f)) {
                Text(name, fontSize = 14.sp, color = Palette.Fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, fontSize = 11.sp, color = Palette.Fg3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
