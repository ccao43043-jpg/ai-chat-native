package com.aichat.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aichat.app.domain.model.AppSettings
import com.aichat.app.domain.model.Conversation
import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.js.jobj
import com.aichat.app.plugins.rp.RpCacheCore
import com.aichat.app.plugins.rp.RpState

/**
 * RP 检视：把「这一轮发出去之前，预算被怎么花的」摊开给人看。
 *
 * 旧版有「检视 › 缓存」面板，原生一直没有入口 —— 结果 plan 的裁剪、章节摘要、
 * 合并这些逻辑只有单测覆盖，用户在设备上既看不到也没法调。这里同时补上可编辑的预算参数：
 * 没有它就没法构造出「超预算」「章节超上限」的真实对话来验证那两条路径。
 */
@Composable
fun RpInspectDialog(
    conv: Conversation,
    settings: AppSettings,
    onDismiss: () -> Unit,
    onSaveSettings: (Map<String, String>) -> Unit,
) {
    val st = remember(conv.id) { RpState.normalizeState(Js.fromJson(conv.rpState) as? JObj ?: JObj()) }
    val s = remember(st) { (st["settings"] as? JObj) ?: JObj() }
    val sum = remember(s) { (s["summary"] as? JObj) ?: JObj() }

    val items = remember(conv.messages) {
        conv.messages.map { m -> jobj("id" to m.id, "role" to m.role.wire, "content" to m.content, "images" to m.images) }
    }
    val plan = remember(items, st) {
        runCatching { RpCacheCore.plan(st, conv.systemPrompt ?: settings.systemPrompt, items) }.getOrNull()
    }

    var maxContext by remember { mutableStateOf(RpInspect.num(s["max_context"], 100000).toString()) }
    var reserve by remember { mutableStateOf(RpInspect.num(s["output_reserve"], 12000).toString()) }
    var margin by remember { mutableStateOf(RpInspect.num(s["safety_margin"], 2000).toString()) }
    var cap by remember { mutableStateOf(RpInspect.num(sum["chapter_cap_tokens"], 12000).toString()) }
    var trigger by remember { mutableStateOf(RpInspect.num(sum["trigger_tokens"], 30000).toString()) }
    var keepRecent by remember { mutableStateOf(RpInspect.num(sum["keep_recent_turns"], 20).toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onSaveSettings(
                        mapOf(
                            "max_context" to maxContext,
                            "output_reserve" to reserve,
                            "safety_margin" to margin,
                            "chapter_cap_tokens" to cap,
                            "trigger_tokens" to trigger,
                            "keep_recent_turns" to keepRecent,
                        ),
                    )
                },
                Modifier.testTag("rp-inspect-save"),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("RP 检视") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "这一轮发出去之前，各层占多少 token。数字是估算（与旧版同一套口径），用来判断该不该摘要/裁剪，不是账单。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (plan == null) {
                    Text("读不出计划（rpState 可能损坏）。", color = MaterialTheme.colorScheme.error)
                } else {
                    val layers = listOf(
                        "static" to "固定层 · 世界观/角色/规则",
                        "memory" to "长期记忆",
                        "summary" to "章节摘要",
                        "history" to "对话历史",
                        "delta" to "状态增量",
                        "state" to "状态块",
                        "input" to "本轮输入",
                    )
                    val total = RpInspect.num(plan.tokens["total"], 0)
                    val maxLayer = layers.maxOf { RpInspect.num(plan.tokens[it.first], 0) }.coerceAtLeast(1)
                    layers.forEach { (key, label) ->
                        val v = RpInspect.num(plan.tokens[key], 0)
                        Text("$label · ${RpInspect.k(v)}", style = MaterialTheme.typography.bodySmall)
                        if (v > 0) {
                            LinearProgressIndicator(
                                progress = { (v.toFloat() / maxLayer).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                            )
                        } else {
                            // 0 的时候不画进度条：progress=0 会在轨道末端留一个小圆点，看着像"有量"
                            Box(
                                Modifier.fillMaxWidth().padding(bottom = 4.dp).height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            )
                        }
                    }
                    Text(
                        "合计 ${RpInspect.k(total)} / 上限 ${RpInspect.k(plan.limit)}" +
                            if (plan.overBudget) " · 超预算" else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        buildString {
                            append("裁剪：")
                            append(if (plan.trimmed) "已临时裁剪 ${plan.dropped} 条（只影响本轮，不永久丢）" else "无")
                            append("\n摘要：")
                            append(
                                if (plan.needSummary) "该写新章（${plan.summaryReason}，本批 ${plan.batch.size} 条）" else "不需要",
                            )
                            append("\n章节：${RpCacheCore.chaptersOf(st).size} 章 · 校准系数 ${RpInspect.num1(plan.calib)}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 缓存分段：旧版「检视 › 缓存」的核心——把 system 摊成有序段，标出稳定前缀到哪。
                // 前缀越靠前越不容易变，变了之后这一段后面全部失效（服务商按前缀命中）。
                val baseSystem = conv.systemPrompt ?: settings.systemPrompt
                val segments = remember(st, baseSystem) {
                    runCatching { RpCacheCore.buildSystemSegments(st, baseSystem) }.getOrDefault(emptyList())
                }
                val stableLen = remember(st, baseSystem) {
                    runCatching { RpCacheCore.systemStablePrefix(st, baseSystem).length }.getOrDefault(0)
                }
                if (segments.isNotEmpty()) {
                    Text("缓存分段（稳定前缀 ${RpInspect.k(stableLen)} 字符）", style = MaterialTheme.typography.labelLarge)
                    var acc = 0
                    segments.forEach { seg ->
                        val tok = RpCacheCore.estimateTokens(seg.text)
                        val stable = acc < stableLen
                        acc += seg.text.length + 2 // 段间 \n\n
                        Text(
                            "${if (stable) "稳定" else "变动"} · ${seg.layer}/${seg.kind} · ${seg.title} · ${seg.gl} · ${RpInspect.k(tok)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (stable) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                Text("预算（保存后从下一轮生效）", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("上下文上限", maxContext, Modifier.weight(1f)) { maxContext = it }
                    NumField("输出预留", reserve, Modifier.weight(1f)) { reserve = it }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("安全余量", margin, Modifier.weight(1f)) { margin = it }
                    NumField("保留轮数", keepRecent, Modifier.weight(1f)) { keepRecent = it }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("摘要触发", trigger, Modifier.weight(1f)) { trigger = it }
                    NumField("章节上限", cap, Modifier.weight(1f)) { cap = it }
                }
            }
        },
    )
}

@Composable
private fun NumField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { t -> onChange(t.filter { it.isDigit() }.take(9)) },
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

internal object RpInspect {
    fun num(v: Any?, def: Int): Int = (v as? Number)?.toInt() ?: def

    fun num1(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)

    fun k(v: Int): String = if (v >= 1000) String.format(java.util.Locale.US, "%.1fk", v / 1000.0) else v.toString()
}
