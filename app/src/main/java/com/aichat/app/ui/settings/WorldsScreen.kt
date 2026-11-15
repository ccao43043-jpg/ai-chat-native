package com.aichat.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aichat.app.ui.theme.Palette
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aichat.app.domain.model.World
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.rp.RpState
import com.aichat.app.ui.common.LocalUiDeps
import com.aichat.app.ui.common.Pressable
import com.aichat.app.ui.theme.Space
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive


/**
 * 世界模板：一套可复用的 RP 块（世界观/角色卡/规则/…）+ 一句开场白。
 *
 * 与旧版 worlds.js 的对齐点：块清单来自 [World.BLOCK_IDS]（与 rpcache 的 STATIC_DEFS 同源），
 * 存进 `worlds.json` 后由 RP 状态面板的「从世界模板填入」消费。
 *
 * 这里不做"应用世界到对话"——那一步在聊天页的 RP 状态面板里，因为要改的是 `conv.rpState`。
 */
@Composable
fun WorldsScreen(onBack: () -> Unit, toast: (String) -> Unit) {
    val deps = LocalUiDeps.current
    val worlds by deps.library.worlds.collectAsState()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<World?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<World?>(null) }

    fun persist(next: List<World>, ok: String) {
        scope.launch {
            deps.library.saveWorlds(next)
            toast(ok)
        }
    }

    SettingsPageScaffold(
        title = "世界模板",
        onBack = onBack,
        actions = { TextButton(onClick = { creating = true }) { Text("新建") } },
    ) {
      Column(Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (worlds.isEmpty()) {
            Text(
                "还没有世界模板。在对话的「⋮ › RP 状态 › 从世界模板填入」里套用。",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Fg3,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        } else {
            worlds.forEach { w ->
                WorldRow(world = w, onClick = { editing = w }, onLongClick = { deleting = w })
            }
            Text("点一行编辑，长按删除", fontSize = 12.sp, color = Palette.Fg3, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
        }
      }
    }

    val target = editing
    if (target != null) {
        WorldEditorDialog(
            world = target,
            onDismiss = { editing = null },
            onSave = { updated ->
                editing = null
                persist(worlds.map { if (it.id == updated.id) updated else it }, "已保存「${updated.name}」")
            },
        )
    }
    if (creating) {
        WorldEditorDialog(
            world = newWorld(),
            onDismiss = { creating = false },
            onSave = { created ->
                creating = false
                persist(worlds + created, "已新建「${created.name}」")
            },
        )
    }
    deleting?.let { w ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除世界模板？") },
            text = { Text("将删除「${w.name}」，不可恢复。已套用到对话里的块不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    persist(worlds.filterNot { it.id == w.id }, "已删除")
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun WorldEditorDialog(world: World, onDismiss: () -> Unit, onSave: (World) -> Unit) {
    var emoji by remember(world.id) { mutableStateOf(world.emoji.ifBlank { "🌍" }) }
    var name by remember(world.id) { mutableStateOf(world.name) }
    var desc by remember(world.id) { mutableStateOf(world.desc) }
    var greeting by remember(world.id) { mutableStateOf(world.greeting) }
    val blocks = remember(world.id) { world.blockTexts().toMutableMap() }
    var tick by remember(world.id) { mutableStateOf(0) }
    val title = if (world.name.isBlank()) "新建世界模板" else "编辑世界模板"

    Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(
                color = Palette.Surface,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
            ) {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 16.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(title, color = Palette.Fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(color = Palette.Surface2, shape = androidx.compose.foundation.shape.CircleShape, modifier = Modifier.size(44.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                BasicTextField(
                                    value = emoji,
                                    onValueChange = { emoji = it.take(4) },
                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 20.sp, color = Palette.Fg),
                                    singleLine = true,
                                    cursorBrush = SolidColor(Palette.Accent),
                                )
                            }
                        }
                        FilledField("名字", name, { name = it }, modifier = Modifier.weight(1f))
                    }
                    Labeled("一句话说明")
                    FilledField("一句话说明", desc, { desc = it })
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("开场白", color = Palette.Fg2, fontSize = 12.sp)
                        Pill("可选")
                    }
                    FilledField("开场白", greeting, { greeting = it }, minLines = 2)
                    Text("设定", color = Palette.Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                    var paste by remember(world.id) { mutableStateOf("") }
                    var pasteHint by remember(world.id) { mutableStateOf("") }
                    Labeled("整段粘贴，自动拆到各栏")
                    FilledField("粘贴完整设定。用【世界观】【角色卡】【写作风格】这类标题分段", paste, { paste = it }, minLines = 4)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            onClick = {
                                val parsed = RpState.parseWorldSections(paste)
                                if (parsed == null) {
                                    pasteHint = "没认出分段。请用【世界观】【角色卡】【RP规则】【写作风格】这类标题。"
                                    return@Surface
                                }
                                val b = parsed["blocks"] as? Map<*, *>
                                var n = 0
                                World.BLOCK_IDS.forEach { id ->
                                    val v = Js.trim(Js.str(b?.get(id) ?: ""))
                                    if (v.isNotEmpty()) { blocks[id] = v; n++ }
                                }
                                val g = Js.trim(Js.str(parsed["greeting"] ?: ""))
                                if (g.isNotEmpty()) greeting = g
                                val nm = Js.trim(Js.str(parsed["name"] ?: ""))
                                if (name.isBlank() && nm.isNotEmpty()) name = nm
                                tick++
                                paste = ""
                                pasteHint = "已填入 $n 段"
                            },
                            color = Palette.Accent,
                            shape = RoundedCornerShape(12.dp),
                        ) { Text("拆分填入", color = androidx.compose.ui.graphics.Color.White, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) }
                        if (pasteHint.isNotEmpty()) Text(pasteHint, color = Palette.Fg3, fontSize = 12.sp)
                    }
                    RpState.BLOCK_DEFS.filter { it["layer"] == "L1" }.forEach { def ->
                        val id = def["id"] as? String ?: return@forEach
                        if (id !in World.BLOCK_IDS) return@forEach
                        val label = def["title"] as? String ?: id
                        val tag = def["tag"] as? String
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(label, color = Palette.Fg2, fontSize = 12.sp)
                            if (!tag.isNullOrBlank()) Pill(tag)
                        }
                        FilledField(label, blocks[id].orEmpty(), { blocks[id] = it; tick++ }, minLines = 2)
                        if (tick < 0) Text("")
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDismiss) { Text("取消", color = Palette.Fg2) }
                        Spacer(Modifier.padding(4.dp))
                        Surface(
                            onClick = {
                                if (name.isBlank()) return@Surface
                                val now = System.currentTimeMillis()
                                onSave(world.copy(
                                    emoji = emoji.trim().ifBlank { "🌍" },
                                    name = name.trim(),
                                    desc = desc.trim(),
                                    greeting = greeting,
                                    blocks = buildBlocks(blocks),
                                    version = if (world.name.isBlank() || blocksChanged(world, blocks)) {
                                        if (world.version <= 0) 1 else world.version + 1
                                    } else world.version,
                                    updatedAt = now,
                                ))
                            },
                            enabled = name.isNotBlank(),
                            shape = RoundedCornerShape(12.dp),
                            color = if (name.isNotBlank()) Palette.Accent else Palette.Surface3,
                        ) {
                            Text("保存", color = androidx.compose.ui.graphics.Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Labeled(text: String) = Text(text, color = Palette.Fg2, fontSize = 12.sp)

@Composable
private fun Pill(text: String) = Surface(shape = RoundedCornerShape(999.dp), color = Palette.AccentSoft) {
    Text(text, color = Palette.Accent, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
}

@Composable
private fun FilledField(placeholder: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, minLines: Int = 1) {
    Surface(color = Palette.Surface2, shape = RoundedCornerShape(12.dp), modifier = modifier.fillMaxWidth()) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (value.isEmpty()) Text(placeholder, color = Palette.Fg3, fontSize = 14.sp)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = androidx.compose.ui.text.TextStyle(color = Palette.Fg, fontSize = 14.sp),
                cursorBrush = SolidColor(Palette.Accent),
                minLines = minLines,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 一行世界：emoji 当图标，长按删除。 */
@Composable
private fun WorldRow(world: World, onClick: () -> Unit, onLongClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Pressable(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.fillMaxWidth().testTag("world-${world.id}"),
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Surface)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Palette.Surface2),
                contentAlignment = Alignment.Center,
            ) { Text(world.emoji.ifBlank { "🌍" }, fontSize = 22.sp) }
            Column(Modifier.weight(1f)) {
                Text(world.name.ifBlank { "（未命名）" }, color = Palette.Fg, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(world.desc.ifBlank { blockSummary(world) }, fontSize = 12.sp, color = Palette.Fg3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 新建一个空世界：id 用时间戳 base36，块按 [World.BLOCK_IDS] 建全（缺块会被 WorldCodec 当空）。 */
internal fun newWorld(now: Long = System.currentTimeMillis()): World = World(
    id = "w" + now.toString(36),
    emoji = "🌍",
    name = "",
    desc = "",
    greeting = "",
    blocks = World.BLOCK_IDS.associateWith { JsonObject(emptyMap()) },
    blockHash = emptyMap(),
    version = 0,
    changelog = emptyList(),
    createdAt = now,
    updatedAt = now,
)

internal fun World.blockTexts(): Map<String, String> = World.BLOCK_IDS.associateWith { id ->
    (blocks[id]?.get("content") as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
}

internal fun buildBlocks(texts: Map<String, String>): Map<String, JsonObject> =
    World.BLOCK_IDS.associateWith { id ->
        JsonObject(mapOf("content" to JsonPrimitive(texts[id].orEmpty())))
    }

internal fun blocksChanged(world: World, texts: Map<String, String>): Boolean =
    World.BLOCK_IDS.any { (world.blockTexts()[it] ?: "") != (texts[it] ?: "") }

internal fun blockSummary(w: World): String {
    val filled = w.blockTexts().filterValues { it.isNotBlank() }.keys
    return if (filled.isEmpty()) "空模板" else "${filled.size} 个块：" + filled.joinToString("、") {
        RpState.defOf(it)?.get("title") as? String ?: it
    }
}
