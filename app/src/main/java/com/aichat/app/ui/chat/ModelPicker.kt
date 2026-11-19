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
    /** 只在「内存表」和「对话里的值」不一致时传：把双方原样显示出来，方便一眼定位是谁丢了 */
    debugLine: String? = null,
) {
    // Flex 只改变「点选时提交的模型串」；开关按当前选中播种，本次弹层内有效
    var flexOn by remember { mutableStateOf(currentModel?.endsWith(":flex") == true) }
    val level = ProviderRules.normalizeThinkingLevel(currentThinking)
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("model-picker")) {
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
            item(key = "think") { ThinkingLevelRow(level, onThinkingLevel, debugLine) }
            providers.filter { p -> p.models.any { it.isNotBlank() } }.forEach { p ->
                val isOr = ProviderRules.isOpenRouterProvider(p)
                item(key = "h-" + p.id) {
                    Text(p.name + if (p.hasKey) "" else "（未填 Key）", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
                }
                if (isOr) item(key = "flex-" + p.id) {
                    Row(Modifier.fillMaxWidth().clickable { flexOn = !flexOn }.padding(start = 20.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Flex 档", style = MaterialTheme.typography.bodyMedium,
                                color = if (flexOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("约半价 · 响应较慢 · 各供应商低价容量", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = flexOn, onCheckedChange = { flexOn = it }, modifier = Modifier.testTag("flex-toggle"))
                    }
                }
                items(p.models.filter { it.isNotBlank() }.distinct(), key = { p.id + "/" + it }) { m ->
                    val want = if (isOr && flexOn) m + ":flex" else m
                    val sel = p.id == currentProvider && currentModel == want
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

/** 思考等级：5 档小胶囊。`自动` = 请求体不加任何字段（各模型自己的默认）。 */
@Composable
private fun ThinkingLevelRow(level: String, onPick: (String) -> Unit, debug: String? = null) {
    // 点了立刻变：不依赖保存往返（弹层常在磁盘写完前就关了，等 state 回来会有「点了没反应」的手感）
    var picked by remember(level) { mutableStateOf(level) }
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp)) {
        Text("思考等级", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text("自动 = 不下发字段；只对本对话生效" + if (level == "auto") "" else "（已存：" + ProviderRules.thinkingLabel(level) + "）",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (debug != null) {
            Text(debug, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("thinking-debug"))
        }
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
                        .clickable(role = Role.Button) { picked = key; onPick(key) }
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
