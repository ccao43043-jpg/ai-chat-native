package com.aichat.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.aichat.app.domain.cost.UsageReport
import com.aichat.app.domain.cost.UsageStatsCore
import com.aichat.app.ui.common.LocalUiDeps
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.text.DecimalFormat
import java.util.Locale

/**
 * 用量统计页（旧 JS `renderUsageStats` 的可用子集）。
 *
 * 数据来源：`usage-stats.jsonl`（条目）+ `usage-stats-meta.json`（价目 `prices` / `priceMode` / `calib`）。
 * 费用**不是**写入时算的，是这里按价目实时算的 —— 换价目表后历史条目会跟着重算，与旧版一致。
 * 没做：区间自定义、按服务商/对话分组的切换、CSV 导出（`UsageStatsCore.toCSV` 已就绪，等按钮）。
 */
@Composable
fun UsageScreen(onBack: () -> Unit, toast: (String) -> Unit = {}) {
    val deps = LocalUiDeps.current
    val scope = rememberCoroutineScope()
    var log by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var meta by remember { mutableStateOf<JsonObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var preset by rememberSaveable { mutableStateOf("today") }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    suspend fun load() {
        loading = true
        val repo = deps.usage
        if (repo == null) {
            log = emptyList(); meta = null
        } else {
            log = repo.readLog()
            meta = repo.meta()
        }
        now = System.currentTimeMillis()
        loading = false
    }

    LaunchedEffect(Unit) { load() }

    val prices = UsageReport.pricesOf(meta)
    val mode = UsageReport.priceModeOf(meta)
    val calib = UsageReport.calibOf(meta)
    val ranged = remember(log, preset, now) { UsageStatsCore.filterLog(log, UsageStatsCore.rangeOf(preset, now)) }
    val totals = remember(ranged, prices, mode) { UsageReport.sum(ranged, prices, mode, calib) }
    val byModel = remember(ranged, prices, mode) { UsageReport.byModel(ranged, prices, mode, calib) }
    val byDay = remember(log, now, prices, mode) {
        // 按天永远看整段历史，不跟着上面的区间走 —— 否则「最近 14 天」在"今天"区间下只剩一行
        UsageReport.byDay(log, prices, mode, calib, days = 14, now = now)
    }

    SettingsPageScaffold(
        title = "用量统计",
        onBack = onBack,
        actions = {
            TextButton(onClick = { scope.launch { load() } }, Modifier.testTag("usage-refresh")) { Text("刷新") }
        },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("today" to "今天", "7d" to "7 天", "30d" to "30 天", "all" to "全部").forEach { (id, label) ->
                FilterChip(
                    selected = preset == id,
                    onClick = { preset = id },
                    label = { Text(label) },
                    modifier = Modifier.testTag("usage-range-$id"),
                )
            }
        }

        when {
            loading -> Text("读取中…", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)

            log.isEmpty() -> Column(Modifier.padding(16.dp)) {
                Text("还没有用量记录", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "发一条消息之后，这里会出现 token 与费用。费用按「价目表」实时计算，换价目后历史会跟着重算。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                SummaryCard(totals)
                if (prices == null) {
                    Text(
                        "还没有价目表：只能看 token，看不到费用。未知模型会退化到价目表的 default 行。",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (byModel.isNotEmpty()) {
                    SectionTitle("按模型")
                    byModel.take(12).forEach { (model, t) ->
                        StatRow(
                            title = model,
                            sub = "${t.requests} 次 · ${fmtTok(t.prompt)} 入 / ${fmtTok(t.completion)} 出 · 命中 ${pct(t.cacheRate)}",
                            trailing = if (t.pricedRequests > 0) money(t.cost) else "—",
                        )
                    }
                }

                SectionTitle("最近 14 天")
                val maxTotal = byDay.maxOfOrNull { it.second.total } ?: 0.0
                byDay.forEach { (day, t) ->
                    StatRow(
                        title = day,
                        sub = if (t.requests > 0) "${t.requests} 次 · ${fmtTok(t.total)} tok · 命中 ${pct(t.cacheRate)}" else "—",
                        trailing = if (t.pricedRequests > 0) money(t.cost) else "",
                        // 没数据的日子不画条：进度条在 0 时会画出一小截，看着像"有量"
                        progress = if (maxTotal > 0 && t.requests > 0) (t.total / maxTotal).toFloat() else null,
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SummaryCard(t: UsageReport.Totals) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatBlock("费用", if (t.pricedRequests > 0) money(t.cost) else "—")
            StatBlock("请求", "${t.requests}")
            StatBlock("命中率", pct(t.cacheRate))
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatBlock("输入", fmtTok(t.prompt))
            StatBlock("输出", fmtTok(t.completion))
            StatBlock("合计", fmtTok(t.total))
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
    }
}

@Composable
private fun StatBlock(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun StatRow(title: String, sub: String, trailing: String, progress: Float? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (trailing.isNotEmpty()) Text(trailing, style = MaterialTheme.typography.bodyMedium)
        }
        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (progress != null) {
            Spacer(Modifier.height(4.dp))
            androidx.compose.material3.LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 1200 → 1.2k；1_234_567 → 1.23M */
internal fun fmtTok(v: Double): String = when {
    v >= 1_000_000 -> String.format(Locale.US, "%.2fM", v / 1_000_000)
    v >= 1_000 -> String.format(Locale.US, "%.1fk", v / 1_000)
    else -> v.toLong().toString()
}

internal fun pct(v: Double): String = String.format(Locale.US, "%.0f%%", v * 100)

/** 小额也看得见：<0.01 用 4 位小数，否则 3 位 */
internal fun money(v: Double): String {
    val f = if (v != 0.0 && kotlin.math.abs(v) < 0.01) DecimalFormat("0.0000") else DecimalFormat("0.000")
    return "$" + f.format(v)
}
