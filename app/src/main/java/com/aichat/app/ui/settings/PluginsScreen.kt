package com.aichat.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aichat.app.domain.ChatPlugin
import com.aichat.app.domain.model.AppSettings
import com.aichat.app.plugins.PluginActivation
import com.aichat.app.ui.common.LocalUiDeps
import com.aichat.app.ui.theme.Palette
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** 插件开关（plugins.js isEnabled：settings.plugins[id].enabled，未设时用 defaultEnabled） */
object PluginToggles {
    fun enabled(s: AppSettings, p: ChatPlugin): Boolean =
        ((s.plugins[p.id] as? JsonObject)?.get("enabled") as? JsonPrimitive)?.booleanOrNull ?: p.defaultEnabled

    /** 只改 enabled，保留插件私有配置 / 价格等其他键 */
    fun withEnabled(s: AppSettings, id: String, on: Boolean): AppSettings {
        val cur = (s.plugins[id] as? JsonObject) ?: JsonObject(emptyMap())
        return s.copy(plugins = JsonObject(s.plugins + (id to JsonObject(cur + ("enabled" to JsonPrimitive(on))))))
    }

    /** 对话级开关：conv.extras.plugins[id].enabled（spec §3.3 ChatPlugin 注释）；null = 未设置（按 autoActive） */
    fun convEnabled(extras: JsonObject, id: String): Boolean? =
        (((extras["plugins"] as? JsonObject)?.get(id) as? JsonObject)?.get("enabled") as? JsonPrimitive)?.booleanOrNull

    fun withConvEnabled(extras: JsonObject, id: String, on: Boolean): JsonObject {
        val all = (extras["plugins"] as? JsonObject) ?: JsonObject(emptyMap())
        val cur = (all[id] as? JsonObject) ?: JsonObject(emptyMap())
        return JsonObject(extras + ("plugins" to JsonObject(all + (id to JsonObject(cur + ("enabled" to JsonPrimitive(on)))))))
    }
}

/** 插件面板挂载点（C 注册各插件的配置 / 诊断面板；点插件名打开） */
object PluginPanels {
    private val pages = HashMap<String, SettingsPage>()
    fun register(id: String, page: SettingsPage) { pages[id] = page }
    fun get(id: String) = pages[id]
}

/**
 * 插件页。**说真话**是这里的第一原则：
 *
 * 开关是「全局开关」，而插件是否执行还要看本会话（`isActive`）。历史上这两件事在界面上
 * 是混在一起的 —— 原生 Gemini 上 gcache 的开关亮着，实际一行都没执行，用户问了两次
 * 「为什么两个缓存插件一起开」。现在每行都标出真实状态，状态来源写在页首。
 */
@Composable
fun PluginsScreen(onBack: () -> Unit, openPanel: (String) -> Unit) {
    val deps = LocalUiDeps.current
    val s by deps.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val errors by deps.plugins.errors.collectAsStateWithLifecycle()
    SettingsPageScaffold("插件", onBack) {
        if (deps.plugins.plugins.isEmpty()) {
            Text("插件尚未接入（工作流 C）", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val snap = PluginActivation.last
        Text(
            if (snap == null) "还没发过请求：下面是全局开关状态，不代表本会话会不会跑"
            else "状态依据：${snap.convTitle} · ${snap.model}",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Fg3,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp).testTag("plugins-status-source"),
        )
        deps.plugins.plugins.forEach { p ->
            PluginRow(
                p = p,
                s = s,
                row = snap?.rows?.firstOrNull { it.pluginId == p.id },
                onClick = { openPanel(p.id) },
                onToggle = { on -> scope.launch { deps.settings.update { PluginToggles.withEnabled(it, p.id, on) } } },
            )
        }
        CachePolicyRow(s, snap) { on ->
            scope.launch { deps.settings.update { PluginToggles.withEnabled(it, "cachepolicy", on) } }
        }
        if (errors.isNotEmpty()) {
            Text("最近的插件错误（${errors.size}）", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(20.dp, 12.dp, 20.dp, 4.dp))
            errors.takeLast(10).forEach { e ->
                Text(e.toString().take(200), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
            }
        }
    }
}

@Composable
private fun PluginRow(
    p: ChatPlugin,
    s: AppSettings,
    row: PluginActivation.Row?,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val globalOn = PluginToggles.enabled(s, p)
    val (status, detail) = when {
        !globalOn -> "已关闭" to "全局开关关闭"
        row == null -> "未知" to if (p.perConversation) "按对话自动（还没发过请求）" else "默认开启"
        row.active -> "运行中" to "本会话参与"
        else -> "本会话未运行" to row.reason.ifEmpty { "不满足自动条件" }
    }
    val color = when {
        !globalOn -> Palette.Fg3
        row?.active == true -> Palette.Ok
        else -> Palette.Fg3
    }
    androidx.compose.material3.Surface(
        color = Palette.Surface,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            Modifier.clickable(enabled = PluginPanels.get(p.id) != null) { onClick() }.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "  $status",
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                        modifier = Modifier.testTag("plugin-status-${p.id}"),
                    )
                }
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(globalOn, onToggle, Modifier.testTag("plugin-${p.id}"))
        }
    }
}

/** 缓存策略：它不是一个「开关型插件」，而是一条每会话选出来的路线 */
@Composable
private fun CachePolicyRow(s: AppSettings, snap: PluginActivation.Snapshot?, onToggle: (Boolean) -> Unit) {
    val on = ((s.plugins["cachepolicy"] as? JsonObject)?.get("enabled") as? JsonPrimitive)?.booleanOrNull ?: true
    val route = when (snap?.cacheOwner) {
        null, "" -> "还没判定（发一条消息后显示）"
        "none" -> "none · 这个服务商没有可用的缓存机制"
        else -> snap.cacheOwner + (if (snap.cacheReason.isBlank()) "" else " · " + snap.cacheReason)
    }
    androidx.compose.material3.Surface(
        color = Palette.Surface,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("缓存策略", style = MaterialTheme.typography.bodyLarge)
                Text(
                    route,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Fg3,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.testTag("cachepolicy-route"),
                )
            }
            Switch(on, onToggle, Modifier.testTag("plugin-cachepolicy"))
        }
    }
}
