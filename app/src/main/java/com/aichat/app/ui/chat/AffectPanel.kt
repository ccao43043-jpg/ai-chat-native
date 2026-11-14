package com.aichat.app.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.aichat.app.domain.model.Conversation
import com.aichat.app.domain.model.Role
import com.aichat.app.plugins.affect.Affect
import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.js.jobj

/**
 * 好感度 / 心情面板。
 *
 * 旧版有这块（当前心情与关系、好感走势、漏标率、护栏命中），原生此前只有顶栏一枚徽章。
 * 统计口径全部走 [Affect] 里刚补的 `series` / `missingStats` / `guardStats`，与 JS 一致。
 */
@Composable
fun AffectPanelDialog(conv: Conversation, onDismiss: () -> Unit) {
    val convJs = remember(conv) { toJs(conv) }

    val current = remember(convJs) { Affect.affectSnapshots(convJs, 1).lastOrNull() }
    val series = remember(convJs) { Affect.series(convJs, 30) }
    val missing = remember(convJs) { Affect.missingStats(convJs, Affect.MISS_WINDOW) }
    val guards = remember(convJs) { Affect.guardStats(convJs) }

    val mood = Js.str(current?.get("mood")).ifEmpty { "平静" }
    val aff = Js.toNumber(current?.get("aff") ?: Affect.BASE_AFF)
    val tier = Affect.tier(aff)
    val relation = Affect.tierLabel(tier)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("affect-close")) { Text("关闭") }
        },
        title = { Text("好感度 / 心情") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("当前：$mood · $relation（$tier 档） · 好感 ${Affect.r4(aff)}", style = MaterialTheme.typography.bodyMedium)
                if (current == null) {
                    Text(
                        "还没有带 <state> 的回复。第一次聊完就会出现。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (series.size >= 2) {
                    Text("最近 ${series.size} 轮好感走势", style = MaterialTheme.typography.labelLarge)
                    val line = MaterialTheme.colorScheme.primary
                    val grid = MaterialTheme.colorScheme.outlineVariant
                    Canvas(Modifier.fillMaxWidth().height(72.dp).testTag("affect-spark")) {
                        fun y(v: Double) = size.height - 4f - (v.coerceIn(0.0, 1.0).toFloat() * (size.height - 8f))
                        // 基准线（BASE_AFF = 0.30）
                        val by = y(Affect.BASE_AFF)
                        drawLine(grid, Offset(0f, by), Offset(size.width, by), strokeWidth = 1f)
                        var prev: Offset? = null
                        series.forEachIndexed { i, v ->
                            val x = 2f + i * (size.width - 4f) / (series.size - 1)
                            val p = Offset(x, y(v))
                            prev?.let { drawLine(line, it, p, strokeWidth = 3f) }
                            prev = p
                        }
                    }
                }

                val missTotal = (missing["total"] as? Number)?.toInt() ?: 0
                val missCount = (missing["missing"] as? Number)?.toInt() ?: 0
                Text(
                    "漏标：最近 $missTotal 轮里 $missCount 轮没有 <state>",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (Affect.shouldDemoState(convJs)) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (Affect.shouldDemoState(convJs)) {
                    Text(
                        "漏标偏多，下一轮请求会拿存盘的好感值给历史助手消息补出 <state> 示范（至多 ${Affect.DEMO_K} 条，不写入历史）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val softCount = (guards["softCount"] as? Number)?.toInt() ?: 0
                val hardCount = (guards["hardCount"] as? Number)?.toInt() ?: 0
                Text(
                    if (softCount + hardCount == 0) "护栏：这段时间没有触发"
                    else "护栏：强制 $hardCount 次 · 软提醒 $softCount 次",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                (guards["all"] as? Map<*, *>)?.let { all ->
                    if (all.isNotEmpty()) {
                        Text(
                            all.entries.joinToString("、") { (k, v) -> "$k×${(v as? Number)?.toInt() ?: 0}" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    )
}

/**
 * 把对话摊成 JS 语义的 map（Affect 的统计函数按 JS 形状读 `messages[].affect`）。
 *
 * `affect` 存在消息的 extras 里（`extras["affect"]`），`affectInj` 同理。
 */
internal fun toJs(conv: Conversation): JObj {
    val msgs = conv.messages.map { m ->
        jobj(
            "id" to m.id,
            "role" to m.role.wire,
            "content" to m.content,
            "affect" to (m.extras["affect"]?.let { Js.fromJson(it) }),
            "affectInj" to (m.extras["affectInj"]?.let { Js.fromJson(it) }),
        )
    }
    return jobj(
        "id" to conv.id,
        "messages" to msgs,
        "affectMode" to (conv.extras["affectMode"]?.let { Js.fromJson(it) }),
        "affectClock" to (conv.extras["affectClock"]?.let { Js.fromJson(it) }),
        "personaName" to conv.personaName,
    )
}

/** 供测试用：角色是否 assistant（避免测试里写错字符串）。 */
internal fun isAssistant(role: Role): Boolean = role == Role.ASSISTANT
