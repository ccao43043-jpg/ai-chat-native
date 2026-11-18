package com.aichat.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aichat.app.domain.Slot
import com.aichat.app.domain.model.Conversation
import com.aichat.app.ui.theme.Palette
import com.aichat.app.ui.theme.Radii
import com.aichat.app.ui.theme.Space
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * 上下文体检（PLAN-CONTEXT-ARCH §3.3）。
 *
 * 只读：把 cachemeter 上一轮存下来的 trace 摊开给人看 —— 五段谁拥有、各多少 token、
 * 谁动了 system（越界）、有没有槽位冲突。**不改任何东西**，所以永远不会把对话搞坏。
 *
 * 为什么需要它：以前的排查方式是我去读代码猜「哪个插件覆盖了哪行」；
 * 有了这面板，用户自己就能看见「这一轮是谁把 system 改了 800 token」。
 */
@Composable
fun ContextInspectorDialog(conv: Conversation?, onDismiss: () -> Unit) {
    val trace = traceOf(conv)
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(Radii.Lg.dp),
            color = Palette.Surface,
            modifier = Modifier.fillMaxWidth().testTag("ctx-inspect"),
        ) {
            Column(Modifier.padding(Space.page)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("上下文体检", fontSize = 16.sp, color = Palette.Fg, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("ctx-close")) {
                        Text("关闭", fontSize = 13.sp, color = Palette.Fg2)
                    }
                }
                Text(
                    "看的是上一轮请求：五段分别由谁拥有、有多大、有没有人越界改 system。",
                    fontSize = 11.5.sp,
                    color = Palette.Fg3,
                )
                Spacer(Modifier.height(10.dp))

                if (trace == null) {
                    Text(
                        "还没有数据。发一条消息后再打开这里。",
                        fontSize = 12.5.sp,
                        color = Palette.Fg2,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                    return@Column
                }

                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val owners = trace.obj("owners")
                    Slot.entries.forEach { s ->
                        SlotRow(s, owners?.get(s.code).text(), trace.obj("segments"))
                    }
                    Spacer(Modifier.height(4.dp))
                    SummaryRow("system 合计", "${trace.int("sysTokens")} tok")
                    SummaryRow("末条 user", "${trace.int("userTokens")} tok")
                    SummaryRow("稳定前缀 hash", trace.str("stableHash").ifEmpty { "—" })

                    val steps = (trace["steps"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
                    if (steps.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("这一轮谁动了什么", fontSize = 12.sp, color = Palette.Fg2)
                        steps.forEach { st ->
                            val slotCodes = (st["slots"] as? JsonArray)
                                ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
                            val sys = st["sys"]?.jsonPrimitive?.content ?: "0"
                            val user = st["user"]?.jsonPrimitive?.content ?: "0"
                            SummaryRow(
                                st["plugin"]?.jsonPrimitive?.content ?: "?",
                                "${slotCodes.joinToString(",")}  system ${sign(sys)} · user ${sign(user)}",
                            )
                        }
                    }

                    val conflicts = (trace["conflicts"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
                    if (conflicts.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("槽位冲突（${conflicts.size}）", fontSize = 12.sp, color = Palette.Danger)
                        conflicts.forEach { c ->
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .background(Palette.Danger.copy(alpha = 0.12f), RoundedCornerShape(Radii.Sm.dp))
                                    .padding(8.dp)
                                    .testTag("ctx-conflict"),
                            ) { Text(c, fontSize = 11.5.sp, color = Palette.Danger) }
                        }
                    }

                    // 装配阶段的说明（预算不足跳过召回、跨来源重复被合并）：以前这些只活在代码里
                    val notes = (trace["notes"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
                    if (notes.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("本轮说明", fontSize = 12.sp, color = Palette.Fg2)
                        notes.forEach { n ->
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .background(Palette.Surface2, RoundedCornerShape(Radii.Sm.dp))
                                    .padding(8.dp)
                                    .testTag("ctx-note"),
                            ) { Text(n, fontSize = 11.5.sp, color = Palette.Fg2) }
                        }
                    }

                    // 插件异常：以前只能翻 logcat
                    val errs = (trace["errors"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
                    if (errs.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("插件异常（${errs.size}）", fontSize = 12.sp, color = Palette.Danger)
                        errs.forEach { e ->
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .background(Palette.Danger.copy(alpha = 0.12f), RoundedCornerShape(Radii.Sm.dp))
                                    .padding(8.dp)
                                    .testTag("ctx-error"),
                            ) { Text(e, fontSize = 11.5.sp, color = Palette.Danger) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SlotRow(s: Slot, owners: String?, segments: JsonObject?) {
    val owned = !owners.isNullOrBlank()
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .width(26.dp)
                .background(
                    if (owned) Palette.AccentSoft else Palette.Surface2,
                    RoundedCornerShape(Radii.Tag.dp),
                )
                .padding(vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) { Text(s.code, fontSize = 10.sp, color = if (owned) Palette.Accent else Palette.Fg3) }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(s.label, fontSize = 12.5.sp, color = if (owned) Palette.Fg else Palette.Fg3)
            Text(
                if (owned) "所有者：$owners" else "无人占用",
                fontSize = 11.sp,
                color = Palette.Fg3,
            )
            if (s == Slot.S1_STABLE || s == Slot.S2_DELTA) {
                val n = segments?.size ?: 0
                if (n > 0) Text("分层段：$n 段", fontSize = 10.5.sp, color = Palette.Fg3)
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, fontSize = 11.5.sp, color = Palette.Fg3, modifier = Modifier.weight(1f))
        Text(value, fontSize = 11.5.sp, color = Palette.Fg2, fontFamily = FontFamily.Monospace)
    }
}

private fun sign(v: String): String = if (v.startsWith("-")) v else "+$v"

/** conv.extras["plugins"]["cachemeter"]["lastTrace"] */
private fun traceOf(conv: Conversation?): JsonObject? =
    ((conv?.extras?.get("plugins") as? JsonObject)
        ?.get("cachemeter") as? JsonObject)
        ?.get("lastTrace") as? JsonObject

private fun JsonObject.obj(k: String): JsonObject? = get(k) as? JsonObject
private fun JsonObject.str(k: String): String = (get(k) as? JsonPrimitive)?.content ?: ""
private fun JsonObject.int(k: String): Int = (get(k) as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.content
