package com.aichat.app.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.aichat.app.ui.theme.Palette
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
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
    // 自定义区间的两端（取当天 00:00）。用 saveable 保住旋转/重进后的选择。
    var fromMs by rememberSaveable { mutableStateOf(0L) }
    var toMs by rememberSaveable { mutableStateOf(0L) }
    var pickFrom by remember { mutableStateOf(false) }
    var pickTo by remember { mutableStateOf(false) }

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
    val custom = if (fromMs > 0L && toMs > 0L) UsageStatsCore.Range(minOf(fromMs, toMs), maxOf(fromMs, toMs)) else null
    val ranged = remember(log, preset, now, custom) {
        UsageStatsCore.filterLog(log, UsageStatsCore.rangeOf(preset, now, custom))
    }
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
            val ctx = LocalContext.current
            TextButton(
                onClick = {
                    val csv = UsageStatsCore.toCSV(ranged)
                    val name = "usage-" + UsageStatsCore.dayKey(System.currentTimeMillis()) + ".csv"
                    val where = CsvExport.save(ctx, name, csv)
                    toast(if (where != null) "已导出：$where" else "导出失败（没有可写的目录）")
                },
                modifier = Modifier.testTag("usage-export"),
            ) { Text("导出") }
            TextButton(onClick = { scope.launch { load() } }, Modifier.testTag("usage-refresh")) { Text("刷新") }
        },
    ) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("today" to "今天", "7d" to "7 天", "30d" to "30 天", "all" to "全部", "custom" to "自定义").forEach { (id, label) ->
                FilterChip(
                    selected = preset == id,
                    onClick = {
                        if (id == "custom" && fromMs == 0L) {
                            toMs = System.currentTimeMillis()
                            fromMs = toMs - 6 * 86_400_000L
                        }
                        preset = id
                    },
                    label = { Text(label, maxLines = 1, fontSize = 13.sp) },
                    modifier = Modifier.heightIn(max = 36.dp).testTag("usage-range-$id"),
                )
            }
        }

        if (preset == "custom") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { pickFrom = true }, Modifier.testTag("usage-custom-from")) {
                    Text("起 " + UsageStatsCore.dayKey(fromMs.coerceAtLeast(1L)))
                }
                Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { pickTo = true }, Modifier.testTag("usage-custom-to")) {
                    Text("止 " + UsageStatsCore.dayKey(toMs.coerceAtLeast(1L)))
                }
            }
            if (pickFrom) {
                UsageDatePicker(initial = fromMs) { pickFrom = false; it?.let { v -> fromMs = v } }
            }
            if (pickTo) {
                UsageDatePicker(initial = toMs) { pickTo = false; it?.let { v -> toMs = v } }
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
                    Surface(color = Palette.Surface, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            byModel.take(12).forEach { (model, t) ->
                                StatRow(
                                    title = model,
                                    sub = "${t.requests} 次 · ${fmtTok(t.prompt)} 入 / ${fmtTok(t.completion)} 出 · 命中 ${pct(t.cacheRate)}",
                                    trailing = if (t.pricedRequests > 0) money(t.cost) else "—",
                                )
                            }
                        }
                    }
                }

                SectionTitle("最近 14 天")
                DayChart(byDay)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SummaryCard(t: UsageReport.Totals) {
    Surface(color = Palette.Surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
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
    }
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
        color = Palette.Fg2,
    )
}


@Composable
private fun DayChart(days: List<Pair<String, UsageReport.Totals>>) {
    val max = days.maxOfOrNull { it.second.total }?.takeIf { it > 0 } ?: 1.0
    Surface(color = Palette.Surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 14.dp)) {
            Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                if (days.isEmpty()) return@Canvas
                val gap = 6.dp.toPx()
                val w = (size.width - gap * (days.size - 1)).coerceAtLeast(0f) / days.size
                days.forEachIndexed { i, (_, t) ->
                    val h = if (t.total <= 0) 4f else (t.total / max).toFloat().coerceIn(0.08f, 1f) * size.height
                    drawRoundRect(
                        color = Palette.Accent,
                        topLeft = Offset(i * (w + gap), size.height - h),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                val marks = listOf(days.firstOrNull()?.first, days.getOrNull(days.size / 2)?.first, days.lastOrNull()?.first).filterNotNull().distinct()
                marks.forEach { Text(it.takeLast(5), color = Palette.Fg3, fontSize = 11.sp) }
            }
        }
    }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UsageDatePicker(initial: Long, onDone: (Long?) -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.takeIf { it > 0L } ?: System.currentTimeMillis(),
    )
    DatePickerDialog(
        onDismissRequest = { onDone(null) },
        confirmButton = {
            TextButton(onClick = { onDone(state.selectedDateMillis) }, Modifier.testTag("usage-date-ok")) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("取消") } },
    ) { DatePicker(state = state) }
}