package com.aichat.app.domain.cost

import com.aichat.app.net.Js
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

/**
 * 用量页的全部算术都在 [UsageReport] 里，所以这些断言就是「今天花了多少钱」这句话的证明。
 *
 * 价目用自定义的小表（不依赖真实市价），并且**显式写 `peak:false`** ——
 * 否则 `CostCore.factorFor` 会按 auto 模式套用「非高峰 0.5 折」，测试就变成在验证折扣表而不是验证聚合。
 *
 *   test-model: hit=1 / miss=10 / out=100（美元每 1M token）
 *   → 800 命中 + 400 未命中 + 40 输出 = (800*1 + 400*10 + 40*100) / 1e6 = 0.0088
 */
class UsageReportTest {

    private val prices: JsonObject = Js.parse(
        """{"test-model":{"hit":1,"miss":10,"out":100,"peak":false},""" +
            """"other":{"hit":2,"miss":20,"out":200,"peak":false},""" +
            """"default":{"hit":0,"miss":0,"out":0,"peak":false}}"""
    ) as JsonObject

    private fun entry(
        at: Long = 1_700_000_000_000L,
        model: String = "test-model",
        prompt: Int = 1200,
        completion: Int = 40,
        hit: Int = 800,
        miss: Int = 400,
        cache: Boolean = true,
    ): JsonObject = JsonObject(
        linkedMapOf(
            "at" to JsonPrimitive(at),
            "type" to JsonPrimitive("chat"),
            "prompt" to JsonPrimitive(prompt),
            "completion" to JsonPrimitive(completion),
            "hit" to JsonPrimitive(hit),
            "miss" to JsonPrimitive(miss),
            "cache" to JsonPrimitive(cache),
            "model" to JsonPrimitive(model),
        )
    )

    @Test
    fun sumsTokensAndPricesByCacheSplit() {
        val t = UsageReport.sum(listOf(entry()), prices, "auto")
        assertEquals(1, t.requests)
        assertEquals(1200.0, t.prompt, 1e-9)
        assertEquals(40.0, t.completion, 1e-9)
        assertEquals(1240.0, t.total, 1e-9)
        assertEquals(800.0, t.hit, 1e-9)
        assertEquals(400.0, t.miss, 1e-9)
        assertEquals(1, t.cachedRequests)
        assertEquals(0.0088, t.cost, 1e-9)
        assertEquals(1, t.pricedRequests)
        assertEquals(800.0 / 1200.0, t.cacheRate, 1e-9)
    }

    /**
     * 未知模型不是「不算钱」：`CostCore.priceForUsage` 会退化到价目表的 `default` 行，
     * 再退化到内置默认价。这里把 default 设成全 0，断言的是**回落路径走到了 default**（cost 恰为 0），
     * 否则真实环境里未知模型会被悄悄按默认价计费而界面显示 $0。
     */
    @Test
    fun unknownModelFallsBackToDefaultPriceRow() {
        val t = UsageReport.sum(listOf(entry(model = "no-such-model")), prices, "auto")
        assertEquals(1, t.requests)
        assertEquals(1240.0, t.total, 1e-9)
        assertEquals(0.0, t.cost, 1e-9)
        assertEquals(1, t.pricedRequests)   // 有价（default 行），只是价是 0
    }

    @Test
    fun noCacheEntryChargesWholePromptAtMissPrice() {
        // cache=false 时 hit 视为 0、miss 视为全部 prompt（costOfEntry 的映射规则）
        val t = UsageReport.of(entry(hit = 0, miss = 0, cache = false), prices, "auto")
        assertEquals(0.0, t.hit, 1e-9)
        assertEquals(1, t.pricedRequests)
        assertEquals((1200 * 10 + 40 * 100) / 1e6, t.cost, 1e-9)
    }

    @Test
    fun byModelSortsByCostDescending() {
        val rows = UsageReport.byModel(
            listOf(
                entry(model = "other", prompt = 1000, hit = 0, miss = 1000, completion = 0),
                entry(model = "test-model"),
            ),
            prices, "auto",
        )
        assertEquals(listOf("other", "test-model"), rows.map { it.first })
        // other: 1000*20/1e6 = 0.02 > test-model 0.0088
        assertEquals(0.02, rows[0].second.cost, 1e-9)
    }

    @Test
    fun byDayReturnsFullWindowOldestFirst() {
        val zone = TimeZone.getTimeZone("UTC")
        val day = 86_400_000L
        val now = 1_700_000_000_000L
        val rows = UsageReport.byDay(
            listOf(entry(at = now - 2 * day), entry(at = now - 2 * day)),
            prices, "auto", days = 14, now = now, zone = zone,
        )
        assertEquals(14, rows.size)
        assertEquals(rows.map { it.first }.sorted(), rows.map { it.first })   // 从早到晚
        val target = UsageStatsCore.dayKey(now - 2 * day, null, zone)
        val hit = rows.first { it.first == target }.second
        assertEquals(2, hit.requests)
        assertEquals(0.0176, hit.cost, 1e-9)
        // 今天之前没有数据的那几天必须是 0 行，而不是被裁掉：14 天窗口的第 12 行 = 两天前
        assertEquals(11, rows.indexOfFirst { it.first == target })
        assertEquals(0, rows.last().second.requests)
    }

    @Test
    fun metaHelpersTolerateMissingFields() {
        assertEquals("auto", UsageReport.priceModeOf(null))
        assertEquals("auto", UsageReport.priceModeOf(JsonObject(emptyMap())))
        assertEquals(null, UsageReport.pricesOf(JsonObject(emptyMap())))
    }

    // ---- 顶栏「这个对话花了多少」：Message.usage 临时拼成条目再计价 ----

    @Test
    fun ofMessagePricesLikeTheUsagePage() {
        // 与 entry() 同一条数据：800 命中 + 400 未命中 + 40 输出 = 0.0088
        val u = com.aichat.app.domain.model.Usage(
            prompt = 1200, completion = 40, hit = 800, miss = 400, cacheSupported = true,
        )
        val t = UsageReport.ofMessage(u, "test-model", 1_700_000_000_000L, prices, "auto")
        assertEquals(1, t.requests)
        assertEquals(1200.0, t.prompt, 1e-9)
        assertEquals(40.0, t.completion, 1e-9)
        assertEquals(0.0088, t.cost, 1e-9)
        assertEquals(1, t.pricedRequests)
    }

    @Test
    fun ofMessageInfersMissWhenProviderOmitsIt() {
        // 有些服务商只给 prompt + hit，没给 miss：miss = prompt - hit，否则会按 0 未命中算，白送钱
        val u = com.aichat.app.domain.model.Usage(prompt = 1000, completion = 0, hit = 400, cacheSupported = true)
        val t = UsageReport.ofMessage(u, "test-model", 0L, prices, "auto")
        assertEquals(600.0, t.miss, 1e-9)
        assertEquals((400 * 1 + 600 * 10) / 1e6, t.cost, 1e-9)
    }

    @Test
    fun ofMessageWithoutCacheCountsWholePromptAsMiss() {
        // 服务商没报缓存能力：整段输入都按未命中价算（与 CostCoreJs.costOfEntry 的 cache=false 口径一致）
        val u = com.aichat.app.domain.model.Usage(prompt = 1000, completion = 0, cacheSupported = null)
        val t = UsageReport.ofMessage(u, "test-model", 0L, prices, "auto")
        assertEquals(0, t.cachedRequests)
        assertEquals(1000 * 10 / 1e6, t.cost, 1e-9)
    }

    @Test
    fun ofMessagePrefersProviderReportedCost() {
        // 服务商自己报了价（旧版 actualCost）就别再按价目表猜
        val u = com.aichat.app.domain.model.Usage(prompt = 1200, completion = 40, hit = 800, costUsd = 0.5)
        val t = UsageReport.ofMessage(u, "test-model", 0L, prices, "auto")
        assertEquals(0.5, t.cost, 1e-9)
        assertEquals(1, t.pricedRequests)
    }

    @Test
    fun ofMessageWithoutMetaPricesFallsBackToBuiltin() {
        // 没有 usage-stats-meta.json 的价目时，CostCoreJs 会回落到内置价目表的 default 行，
        // 所以「pricedRequests 为 1 但价格是兜底价」是预期行为；UI 侧再决定要不要显示（chip 在 prices==null 时只显示 token）
        val u = com.aichat.app.domain.model.Usage(prompt = 10, completion = 5)
        val t = UsageReport.ofMessage(u, "unknown-model", 0L, null, "auto")
        assertEquals(1, t.requests)
        assertEquals(15.0, t.total, 1e-9)
        assertEquals(1, t.pricedRequests)
        assert(t.cost > 0.0) { "内置 default 价目应当给出非零费用，实际 ${t.cost}" }
    }
}
