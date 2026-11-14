package com.aichat.app.domain.cost

import com.aichat.app.net.Js
import kotlinx.serialization.json.JsonObject
import java.util.TimeZone

/**
 * 用量统计的**纯聚合**：把 usage-stats.jsonl 的条目 + usage-stats-meta.json 的价目，算成能直接画的东西。
 *
 * 为什么要单独一层：
 *  - 费用不在写入时算（旧 JS 的 `makeEntry` 只透传 `usage.cost`，没给就是 null），而是**展示时**按价目算。
 *    所以「今天花了多少」这句话的正确性全压在这里，必须有单测，不能只靠肉眼看界面。
 *  - 无 UI 依赖，`UsageScreen` 只负责排版。
 *
 * 与旧版的对应关系：`usage-stats.js` 的 `sumList` / `groupBy` / `byDay` + `CostCore.costOfEntry`。
 */
object UsageReport {

    /** 一段区间（或一组）的合计；cost 为按价目算出的美元数，pricedRequests 是有价目的条目数 */
    data class Totals(
        val requests: Int = 0,
        val prompt: Double = 0.0,
        val completion: Double = 0.0,
        val hit: Double = 0.0,
        val miss: Double = 0.0,
        val cachedRequests: Int = 0,
        val cost: Double = 0.0,
        val pricedRequests: Int = 0,
    ) {
        val total: Double get() = prompt + completion

        /** 缓存命中率：命中 / 输入（没有输入时为 0） */
        val cacheRate: Double get() = if (prompt > 0) hit / prompt else 0.0

        operator fun plus(o: Totals) = Totals(
            requests = requests + o.requests,
            prompt = prompt + o.prompt,
            completion = completion + o.completion,
            hit = hit + o.hit,
            miss = miss + o.miss,
            cachedRequests = cachedRequests + o.cachedRequests,
            cost = cost + o.cost,
            pricedRequests = pricedRequests + o.pricedRequests,
        )
    }

    /** 单条条目 → Totals。价目缺失（模型不在价目表、或条目没有 model）时 cost 记 0，但 requests 照记 */
    fun of(e: JsonObject, prices: JsonObject?, mode: String?, calib: JsonObject? = null): Totals {        val cost = CostCoreJs.costOfEntry(e, prices, mode, null, calib)
        return Totals(
            requests = 1,
            prompt = Js.i32(e["prompt"]).toDouble(),
            completion = Js.i32(e["completion"]).toDouble(),
            hit = Js.i32(e["hit"]).toDouble(),
            miss = Js.i32(e["miss"]).toDouble(),
            cachedRequests = if (Js.truthy(e["cache"])) 1 else 0,
            cost = cost ?: 0.0,
            pricedRequests = if (cost != null) 1 else 0,
        )
    }

    fun sum(items: List<JsonObject>, prices: JsonObject?, mode: String?, calib: JsonObject? = null): Totals =
        items.fold(Totals()) { acc, e -> acc + of(e, prices, mode, calib) }

    /**
     * 单条**消息**的用量 → Totals（聊天页顶栏要"这个对话花了多少"）。
     *
     * 不落盘、不入 usage-stats：这里只是把 `Message.usage` 临时拼成 usage-stats 条目的形状，
     * 交给同一套 [CostCoreJs.costOfEntry] 计价 —— 保证顶栏和用量页对同一条消息给出同一个数字。
     *
     * `usage.costUsd` 有值时优先用它（对应旧版 `actualCost`：服务商自己报了价就别再按价目猜）。
     */
    fun ofMessage(
        usage: com.aichat.app.domain.model.Usage,
        model: String?,
        at: Long,
        prices: JsonObject?,
        mode: String?,
        calib: JsonObject? = null,
    ): Totals {
        val prompt = usage.prompt ?: 0L
        val hit = usage.hit ?: 0L
        val miss = usage.miss ?: (prompt - hit).coerceAtLeast(0L)
        val cache = usage.cacheSupported == true || usage.hit != null
        val entry = JsonObject(
            linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
                "model" to kotlinx.serialization.json.JsonPrimitive(model ?: ""),
                "at" to kotlinx.serialization.json.JsonPrimitive(at),
                "prompt" to kotlinx.serialization.json.JsonPrimitive(prompt),
                "completion" to kotlinx.serialization.json.JsonPrimitive(usage.completion ?: 0L),
                "hit" to kotlinx.serialization.json.JsonPrimitive(hit),
                "miss" to kotlinx.serialization.json.JsonPrimitive(miss),
                "cache" to kotlinx.serialization.json.JsonPrimitive(cache),
            ),
        )
        val t = of(entry, prices, mode, calib)
        val actual = usage.costUsd
        return if (actual != null) t.copy(cost = actual, pricedRequests = 1) else t
    }

    /** 按模型分组，先按费用降序、再按 token 降序（与 groupBy 的 total 降序略有不同：这里更关心花钱） */
    fun byModel(items: List<JsonObject>, prices: JsonObject?, mode: String?, calib: JsonObject? = null): List<Pair<String, Totals>> {
        val map = LinkedHashMap<String, MutableList<JsonObject>>()
        items.forEach { e ->
            val k = Js.str(e["model"]).takeIf { it.isNotEmpty() } ?: UNKNOWN
            map.getOrPut(k) { ArrayList() }.add(e)
        }
        return map.map { (k, list) -> k to sum(list, prices, mode, calib) }
            .sortedWith(compareByDescending<Pair<String, Totals>> { it.second.cost }.thenByDescending { it.second.total })
    }

    /**
     * 按天（最近 [days] 天，含今天）分组，返回从早到晚。
     * 无数据的日子也返回一行（Totals 全 0），这样"这几天多少钱"的连续性看得出来。
     */
    fun byDay(
        items: List<JsonObject>,
        prices: JsonObject?,
        mode: String?,
        calib: JsonObject? = null,
        days: Int = 14,
        now: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
    ): List<Pair<String, Totals>> {
        val buckets = HashMap<String, MutableList<JsonObject>>()
        items.forEach { e ->
            val at = Js.toNumber(e["at"] ?: return@forEach)
            if (!at.isFinite()) return@forEach
            buckets.getOrPut(UsageStatsCore.dayKey(at.toLong(), null, zone)) { ArrayList() }.add(e)
        }
        val n = days.coerceAtLeast(1)
        return (n - 1 downTo 0).map { i ->
            val key = UsageStatsCore.dayKey(now - i * 86_400_000L, null, zone)
            key to sum(buckets[key] ?: emptyList(), prices, mode, calib)
        }
    }

    /** 取价目表：meta.prices 缺失时退回内置默认价（与 CostCore 的 USAGE_DEFAULT_PRICES 同源） */
    fun pricesOf(meta: JsonObject?): JsonObject? = meta?.get("prices") as? JsonObject

    fun priceModeOf(meta: JsonObject?): String = (meta?.get("priceMode")?.let { Js.str(it) })?.takeIf { it.isNotEmpty() } ?: "auto"

    fun calibOf(meta: JsonObject?): JsonObject? = meta?.get("calib") as? JsonObject

    const val UNKNOWN = "（未知）"
}
