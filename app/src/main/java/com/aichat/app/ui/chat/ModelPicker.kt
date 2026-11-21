package com.aichat.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.aichat.app.domain.model.ProviderConfig
import com.aichat.app.net.ModelCatalog
import com.aichat.app.net.ProviderRules

/**
 * 服务商 / 模型快速切换（底部弹层；返回键关闭）。OpenRouter 区块带 Flex 档开关：开启后点选模型自动变 `model:flex`（约半价、响应较慢）。
 * 顶部带「思考等级」：按对话保存，立即写入当前对话（不需要重启、不影响其它对话）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPicker(
    providers: List<ProviderConfig>,
    currentProvider: String?,
    currentModel: String?,
    onPick: (String, String) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
    currentThinking: String? = null,
    onThinkingLevel: (String) -> Unit = {},
    /** 没有打开的对话时 false：胶囊点击只 toast 不乐观变蓝（否则被拒绝后蓝着不落盘） */
    thinkingEnabled: Boolean = true,
    currentFlex: Boolean = false,
    onFlexChange: (Boolean) -> Unit = {},
) {
    // Flex 只改变「点选时提交的模型串」；开关按服务商的持久值播种（外部真值作 key，
    // 落盘回来覆盖本地翻转 —— 不能再从 currentModel 重播种，否则重开弹层就把 :flex 洗掉了）
    var flexOn by remember(currentProvider, currentFlex) { mutableStateOf(currentFlex) }
    val level = ProviderRules.normalizeThinkingLevel(currentThinking)
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("model-picker")) {
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
            item(key = "think") { ThinkingLevelRow(level, onThinkingLevel, thinkingEnabled) }
            providers.filter { p -> p.models.any { it.isNotBlank() } }.forEach { p ->
                val isOr = ProviderRules.isOpenRouterProvider(p)
                item(key = "h-" + p.id) {
                    Text(p.name + if (p.hasKey) "" else "（未填 Key）", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
                }
                if (isOr) item(key = "flex-" + p.id) {
                    // 当前服务商才并单价：flex 变体沿用基础模型 meta，按半价估算；缺价目显示 —
                    val flexPrice = if (p.id == currentProvider)
                        ModelCatalog.flexPriceHint(ProviderRules.metaFor(p.modelMeta, currentModel)) else null
                    Row(Modifier.fillMaxWidth().clickable {
                            val next = !flexOn
                            flexOn = next; onFlexChange(next)
                        }.padding(start = 20.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Flex 档", style = MaterialTheme.typography.bodyMedium,
                                color = if (flexOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("约半价 · 响应较慢 · 各供应商低价容量" + (flexPrice?.let { " · " + it } ?: ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = flexOn, onCheckedChange = { flexOn = it; onFlexChange(it) },
                            modifier = Modifier.testTag("flex-toggle"))
                    }
                }
                items(p.models.filter { it.isNotBlank() }.distinct(), key = { p.id + "/" + it }) { m ->
                    val want = if (isOr && flexOn) m + ":flex" else m
                    // 容忍 currentModel 已带 :flex（开关刚关还没重选时），✓ 别消失
                    val sel = p.id == currentProvider && (currentModel == want || currentModel == want + ":flex")
                    Text((if (sel) "✓ " else "    ") + if (sel && isOr && flexOn) want else m,
                        fontWeight = if (sel) FontWeight.SemiBold else null,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(p.id, want) }.padding(horizontal = 20.dp, vertical = 10.dp))
                }
            }
            item {
                TextButton(onClick = onManage, modifier = Modifier.padding(12.dp)) { Text("管理服务商…") }
            }
        }
    }
}

/** 思考等级：5 档小胶囊。`自动` = 请求体不加任何字段（各模型自己的默认）。`enabled=false`（无对话）时点击只回调不乐观变蓝。 */
@Composable
private fun ThinkingLevelRow(level: String, onPick: (String) -> Unit, enabled: Boolean = true) {
    // 点了立刻变：不依赖保存往返（弹层常在磁盘写完前就关了，等 state 回来会有「点了没反应」的手感）
    var picked by remember(level) { mutableStateOf(level) }
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp)) {
        Text("思考等级", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text("按模型记，换对话不会丢；自动 = 不下发字段",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            THINKING_LABELS.forEach { (key, label) ->
                val sel = picked == key
                Box(
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .border(1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(10.dp))
                        .clickable(role = Role.Button) {
                            if (enabled) { picked = key; onPick(key) } else onPick(key)   // 被拒绝时不乐观变蓝
                        }
                        .padding(vertical = 9.dp)
                        .testTag("thinking-" + key),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private val THINKING_LABELS = listOf(
    "auto" to "自动", "off" to "关", "low" to "低", "medium" to "中", "high" to "高",
)
