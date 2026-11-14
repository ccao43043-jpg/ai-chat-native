package com.aichat.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.aichat.app.domain.cost.BalanceCore
import com.aichat.app.ui.common.LocalUiDeps
import com.aichat.app.ui.theme.Space
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 余额：只有 deepseek / siliconflow / moonshot / openrouter 四家有查询接口，
 * 其余服务商在旧版里也只给"去控制台看"的提示 —— 这里保持同样的口径，不假装能查。
 *
 * 数据流：`DefaultBalanceClient.refresh(...)` 并发查 → 结果整份写回 `balance-cache.json`；
 * 页面只读缓存。旧版判"过期"的口径是 30 分钟（[com.aichat.app.domain.cost.DefaultBalanceClient.STALE_MS]）。
 */
@Composable
fun BalanceScreen(onBack: () -> Unit, toast: (String) -> Unit) {
    val deps = LocalUiDeps.current
    val providers by deps.settings.settings.collectAsState(initial = null)
    val client = deps.balance
    val cacheStore = deps.balanceCache
    val scope = rememberCoroutineScope()
    var cache by remember { mutableStateOf(cacheStore?.load() ?: JsonObject(emptyMap())) }
    var busy by remember { mutableStateOf(false) }

    fun refresh() {
        val list = providers?.providers.orEmpty()
        if (client == null || cacheStore == null) { toast("余额模块未就绪"); return }
        if (list.isEmpty()) { toast("还没有服务商"); return }
        busy = true
        scope.launch {
            runCatching {
                val next = client.refresh(list, cache)
                cacheStore.save(next)
                cache = next
            }.onSuccess { toast("已刷新 ${list.size} 个服务商") }
                .onFailure { toast("刷新失败：" + (it.message ?: it.toString())) }
            busy = false
        }
    }

    SettingsPageScaffold(
        title = "余额",
        onBack = onBack,
        actions = {
            TextButton(enabled = !busy, onClick = { refresh() }) { Text(if (busy) "查询中…" else "刷新") }
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.page), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "只有 DeepSeek / 硅基流动 / Moonshot / OpenRouter 提供余额查询接口；其他服务商请到官网控制台查看。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            val list = providers?.providers.orEmpty()
            if (list.isEmpty()) {
                Text("还没有服务商。先去「服务商」加一个。", style = MaterialTheme.typography.bodyMedium)
            }
            list.forEach { p ->
                BalanceCard(
                    name = p.name.ifBlank { p.id },
                    entry = cache[p.id] as? JsonObject,
                    supported = BalanceCore.supportInfo(p.presetId).supported,
                )
            }
        }
    }
}

@Composable
private fun BalanceCard(name: String, entry: JsonObject?, supported: Boolean) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (!supported) {
                Text("无接口", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }
        val result = entry?.get("result") as? JsonObject
        when {
            result == null -> Text(
                if (supported) "还没查询过，点右上角「刷新」" else "此服务商未提供余额接口",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            (result["ok"] as? JsonPrimitive)?.content == "true" -> {
                val primary = result["primary"] as? JsonObject
                val details = (result["details"] as? JsonArray).orEmpty()
                Text(
                    primary?.let { "${it["label"].text()} ${it["text"].text()}" } ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                )
                details.forEach { d ->
                    val o = d as? JsonObject ?: return@forEach
                    Text(
                        "${o["label"].text()}  ${o["text"].text()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
            else -> Text(
                result["error"].text().ifBlank { "查询失败" },
                style = MaterialTheme.typography.bodySmall,
                color = cs.error,
            )
        }
        entry?.get("at")?.let {
            val at = (it as? JsonPrimitive)?.content?.toLongOrNull()
            if (at != null) Text(fmtTime(at), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}

private fun kotlinx.serialization.json.JsonElement?.text(): String =
    (this as? JsonPrimitive)?.content.orEmpty()

/** 缓存时间：今天只显示时分，往天显示月日时分（够用就好，不做相对时间）。 */
internal fun fmtTime(at: Long): String {
    val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
    return if (today.format(java.util.Date(at)) == today.format(java.util.Date())) {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at)) + " 更新"
    } else fmt.format(java.util.Date(at)) + " 更新"
}
