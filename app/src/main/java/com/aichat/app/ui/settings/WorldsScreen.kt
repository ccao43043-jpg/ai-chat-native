package com.aichat.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
      Column(Modifier.fillMaxWidth().padding(horizontal = Space.page)) {
        if (worlds.isEmpty()) {
            Text(
                "还没有世界模板。\n\n世界模板是一组可复用的设定（世界观、角色卡、规则、写作风格……），\n" +
                    "在对话的「⋮ › RP 状态 › 从世界模板填入」里套用。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        } else {
            worlds.forEach { w ->
                WorldRow(
                    world = w,
                    onClick = { editing = w },
                    onLongClick = { deleting = w },
                )
            }
            Text(
                "点一行编辑，长按删除。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
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
    var emoji by remember(world.id) { mutableStateOf(world.emoji) }
    var name by remember(world.id) { mutableStateOf(world.name) }
    var desc by remember(world.id) { mutableStateOf(world.desc) }
    var greeting by remember(world.id) { mutableStateOf(world.greeting) }
    val blocks = remember(world.id) { world.blockTexts().toMutableMap() }
    var tick by remember(world.id) { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (world.name.isBlank()) "新建世界模板" else "编辑世界模板") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = emoji,
                        onValueChange = { emoji = it.take(4) },
                        label = { Text("图标") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(0.28f),
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("名字") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("一句话说明") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = greeting,
                    onValueChange = { greeting = it },
                    label = { Text("开场白（可选）") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                RpState.BLOCK_DEFS.filter { it["layer"] == "L1" }.forEach { def ->
                    val id = def["id"] as? String ?: return@forEach
                    if (id !in World.BLOCK_IDS) return@forEach
                    val title = def["title"] as? String ?: id
                    OutlinedTextField(
                        value = blocks[id].orEmpty(),
                        onValueChange = { blocks[id] = it; tick++ },
                        label = { Text("$title · [${def["tag"]}]") },
                        minLines = 2,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    val now = System.currentTimeMillis()
                    onSave(
                        world.copy(
                            emoji = emoji.trim(),
                            name = name.trim(),
                            desc = desc.trim(),
                            greeting = greeting,
                            blocks = buildBlocks(blocks),
                            // 内容变了 version 就 +1，旧版 worlds.js 用这个做"有更新"标记
                            version = if (world.name.isBlank() || blocksChanged(world, blocks)) {
                                (if (world.version <= 0) 1 else world.version + 1)
                            } else world.version,
                            updatedAt = now,
                        ),
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
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
            Modifier.fillMaxWidth().defaultMinSize(minHeight = Space.rowTwo)
                .padding(horizontal = Space.row, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.row),
        ) {
            Box(
                Modifier.size(Space.iconBox).clip(RoundedCornerShape(16.dp)).background(cs.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) { Text(world.emoji.ifBlank { "🌍" }, style = MaterialTheme.typography.titleMedium) }
            Column(Modifier.weight(1f)) {
                Text(
                    world.name.ifBlank { "（未命名）" },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    world.desc.ifBlank { blockSummary(world) },
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
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
