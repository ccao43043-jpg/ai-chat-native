package com.aichat.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aichat.app.ui.theme.Palette
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

    Dialog(onDismissRequest = onDismiss) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                color = Palette.Surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("RP 状态", color = Palette.Fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismiss) { Text("✕", color = Palette.Fg2) }
                    }
                    Text(
                        "进系统提示词的固定层，越靠前越稳。",
                        color = Palette.Fg3,
                        fontSize = 12.sp,
                    )
                    Column(
                        Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()).padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (worlds.isNotEmpty()) {
                            Box {
                                Surface(
                                    onClick = { picking = true },
                                    shape = RoundedCornerShape(10.dp),
                                    color = Palette.Surface2,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        "从世界模板填入（${worlds.size}）  ›",
                                        color = Palette.Fg2,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    )
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
                            val tag = def["tag"] as? String ?: ""
                            RpField(title, tag, hint, valueOf(id)) { edited[id] = it }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDismiss) { Text("取消", color = Palette.Fg2, fontSize = 14.sp) }
                        Spacer(Modifier.padding(4.dp))
                        Surface(
                            onClick = {
                                val changed = edited.filter { (id, v) -> v != original[id].orEmpty() }
                                if (changed.isNotEmpty()) onSave(changed)
                                onDismiss()
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = Palette.Accent,
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
private fun RpField(title: String, tag: String, hint: String, value: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Palette.Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (tag.isNotEmpty()) {
                Surface(shape = RoundedCornerShape(999.dp), color = Palette.AccentSoft) {
                    Text(tag, color = Palette.Accent, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
        }
        if (hint.isNotEmpty()) Text(hint, color = Palette.Fg3, fontSize = 12.sp)
        Surface(shape = RoundedCornerShape(12.dp), color = Palette.Surface2, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.padding(12.dp)) {
                if (value.isEmpty()) Text(hint.ifEmpty { "在这里写…" }, color = Palette.Fg3, fontSize = 14.sp)
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    textStyle = TextStyle(color = Palette.Fg, fontSize = 14.sp),
                    cursorBrush = SolidColor(Palette.Accent),
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
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
