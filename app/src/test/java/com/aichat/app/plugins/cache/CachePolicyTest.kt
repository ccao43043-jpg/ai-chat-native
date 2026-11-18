package com.aichat.app.plugins.cache

import com.aichat.app.domain.model.AppSettings
import com.aichat.app.domain.model.Conversation
import com.aichat.app.domain.model.ProviderConfig
import com.aichat.app.domain.model.ProviderType
import com.aichat.app.domain.model.Usage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓存三件套的纯函数测试：选路线（CachePolicy）、记样本与归因（CacheLedger）、
 * 前缀稳定度与成本判断（PrefixStabilizer / CacheCostModel）。
 *
 * 关键契约：
 * - 只有 OpenRouter/openai-compat 上的 Gemini 才允许「改请求」（gcache），其余一律只观测；
 * - 没有缓存字段的响应不能算成「没命中」；
 * - 命中率按 token 加权，不按次数。
 */
class CachePolicyTest {

    private fun provider(
        id: String = "p1",
        presetId: String = "deepseek",
        type: ProviderType = ProviderType.OPENAI,
        baseUrl: String = "https://api.deepseek.com",
        models: List<String> = listOf("deepseek-chat"),
    ) = ProviderConfig(id = id, presetId = presetId, name = presetId, type = type, baseUrl = baseUrl, models = models)

    private fun conv(providerId: String, model: String, extras: kotlinx.serialization.json.JsonObject = com.aichat.app.domain.model.EmptyJson) =
        Conversation(
            id = "c1", title = "t", providerId = providerId, model = model,
            createdAt = 1L, updatedAt = 1L, messages = emptyList(), extras = extras,
        )

    @Test
    fun openRouterGeminiGoesToGcache() {
        val p = provider(presetId = "openrouter", baseUrl = "https://openrouter.ai/api/v1", models = listOf("google/gemini-2.5-pro"))
        val plan = CachePolicy.pick(p, "google/gemini-2.5-pro", AppSettings(providers = listOf(p)), conv("p1", "google/gemini-2.5-pro"))
        assertEquals(CachePolicy.OWNER_GCACHE, plan.owner)
        assertEquals(CachePolicy.MODE_FULL, plan.mode)
        assertTrue("gcache 是唯一允许改请求的路线", plan.mutates)
    }

    @Test
    fun nativeGeminiIsMonitorOnly() {
        val p = provider(presetId = "gemini", type = ProviderType.GEMINI, baseUrl = "https://generativelanguage.googleapis.com", models = listOf("gemini-2.5-pro"))
        val plan = CachePolicy.pick(p, "gemini-2.5-pro", AppSettings(providers = listOf(p)), conv("p1", "gemini-2.5-pro"))
        assertEquals(CachePolicy.OWNER_PREFIX, plan.owner)
        assertEquals(CachePolicy.MODE_AUTO, plan.mode)
        assertFalse("原生 Gemini 是隐式缓存，改请求反而会打断缓存", plan.mutates)
    }

    /**
     * Anthropic 必须由 rpcache 执行打点。
     *
     * 以前判给 `owner=prefix`，而 prefix 路线只观测不改请求 —— 等于没有执行者，
     * 实测 Anthropic 请求里一个 `cache_control` 都没出去过。
     */
    @Test
    fun anthropicIsExecutedByRpcache() {
        val p = provider(presetId = "anthropic", type = ProviderType.ANTHROPIC, baseUrl = "https://api.anthropic.com", models = listOf("claude-sonnet-4-5"))
        val plan = CachePolicy.pick(p, "claude-sonnet-4-5", AppSettings(providers = listOf(p)), conv("p1", "claude-sonnet-4-5"))
        assertEquals(CachePolicy.OWNER_RPCACHE, plan.owner)
        assertEquals(CachePolicy.MODE_BREAKPOINTS, plan.mode)
        assertTrue(plan.mutates)
    }

    @Test
    fun anthropicMarkersAreRequestedByTheMarkerLayer() {
        assertTrue(CacheMarkers.needsExplicitMarkers("anthropic", "explicit"))
        assertTrue(CacheMarkers.needsExplicitMarkers("qwen", "explicit"))
        assertFalse(CacheMarkers.needsExplicitMarkers("deepseek", "implicit"))
    }

    @Test
    fun deepseekIsMonitorOnly() {
        val p = provider()
        val plan = CachePolicy.pick(p, "deepseek-chat", AppSettings(providers = listOf(p)), conv("p1", "deepseek-chat"))
        assertEquals(CachePolicy.OWNER_PREFIX, plan.owner)
        assertFalse(plan.mutates)
    }

    @Test
    fun minimaxGoesToMmCache() {
        val p = provider(presetId = "minimax", baseUrl = "https://api.minimax.chat/v1", models = listOf("MiniMax-Text-01"))
        val plan = CachePolicy.pick(p, "MiniMax-Text-01", AppSettings(providers = listOf(p)), conv("p1", "MiniMax-Text-01"))
        assertEquals(CachePolicy.OWNER_MMCACHE, plan.owner)
        assertTrue(plan.mutates)
    }

    @Test
    fun switchOffMeansNone() {
        val p = provider(presetId = "openrouter", baseUrl = "https://openrouter.ai/api/v1", models = listOf("google/gemini-2.5-pro"))
        val s = AppSettings(
            providers = listOf(p),
            plugins = buildJsonObject { putJsonObject("cachepolicy") { put("enabled", false) } },
        )
        val plan = CachePolicy.pick(p, "google/gemini-2.5-pro", s, conv("p1", "google/gemini-2.5-pro"))
        assertEquals(CachePolicy.OWNER_NONE, plan.owner)
        assertFalse(plan.monitored)
    }

    @Test
    fun sessionOverrideWins() {
        val p = provider(presetId = "openrouter", baseUrl = "https://openrouter.ai/api/v1", models = listOf("google/gemini-2.5-pro"))
        val c = conv(
            "p1", "google/gemini-2.5-pro",
            buildJsonObject { putJsonObject("plugins") { putJsonObject("cachepolicy") { put("owner", "none") } } },
        )
        val plan = CachePolicy.pick(p, "google/gemini-2.5-pro", AppSettings(providers = listOf(p)), c)
        assertEquals(CachePolicy.OWNER_NONE, plan.owner)
    }

    @Test
    fun noProviderIsNone() {
        val plan = CachePolicy.pick(null, "whatever", AppSettings(providers = emptyList()), null)
        assertEquals(CachePolicy.OWNER_NONE, plan.owner)
    }

    @Test
    fun providerLookupAcceptsPresetId() {
        val p = provider()
        val s = AppSettings(providers = listOf(p))
        assertNotNull("按真实 id 找", CachePolicy.providerOf(s, conv("p1", "deepseek-chat")))
        assertNotNull("旧数据存的是预设 id，也要认", CachePolicy.providerOf(s, conv("deepseek", "deepseek-chat")))
        assertNull(CachePolicy.providerOf(s, conv("nope", "deepseek-chat")))
    }

    // ── 账本 ──

    private fun ctx(owner: String = CachePolicy.OWNER_PREFIX, providerId: String = "p1", model: String = "m") =
        LedgerCtx(providerId = providerId, model = model, owner = owner, prefixHash = "h1", prefixTokens = 2000)

    private fun sample(
        prompt: Long = 1000, hit: Long = 0, model: String = "m", providerId: String = "p1",
        prefixTokens: Long = 2000, lcp: Double? = null, idle: Double? = null,
    ) = CacheSample(
        at = 1L, providerId = providerId, model = model, owner = CachePolicy.OWNER_PREFIX,
        prompt = prompt, hit = hit, write = 0L, known = true,
        lcpRatio = lcp, idleSec = idle, prefixHash = "h1", prefixTokens = prefixTokens,
    )

    @Test
    fun usageWithoutCacheFieldsIsNotCounted() {
        val s = CacheLedger.sample(Usage(prompt = 900L, completion = 10L), 1L, ctx(), null)
        assertNotNull(s)
        assertFalse("没有 hit / cacheSupported 的响应不能算样本", s!!.known)
        assertEquals(0.0, CacheLedger.hitRate(listOf(s)), 0.0001)
        assertNull("不可判定的样本不做归因", s.reason)
    }

    @Test
    fun hitRateIsTokenWeighted() {
        val a = sample(prompt = 100, hit = 60)
        val b = sample(prompt = 900, hit = 0)
        assertEquals(0.06, CacheLedger.hitRate(listOf(a, b)), 0.0001)
    }

    @Test
    fun missAttributionOrder() {
        val prev = sample(prompt = 1000, hit = 0)
        // 换模型优先级最高
        assertEquals(MissReason.MODEL_SWITCH, CacheLedger.attribute(sample(model = "m2", lcp = 0.1), prev))
        // 换服务商
        assertEquals(MissReason.PROVIDER_SWITCH, CacheLedger.attribute(sample(providerId = "p2", lcp = 0.1), prev))
        // 空闲超过 TTL
        assertEquals(MissReason.TTL_EXPIRED, CacheLedger.attribute(sample(idle = 900.0, lcp = 0.1), prev))
        // 前缀变短
        assertEquals(MissReason.PREFIX_SHRINK, CacheLedger.attribute(sample(prefixTokens = 1200, lcp = 0.95), prev))
        // LCP 低
        assertEquals(MissReason.LCP_LOW, CacheLedger.attribute(sample(lcp = 0.3), prev))
        // 都对不上
        assertEquals(MissReason.UNKNOWN, CacheLedger.attribute(sample(lcp = 0.99), prev))
    }

    @Test
    fun ledgerCapsAtMaxSamples() {
        var list = emptyList<CacheSample>()
        repeat(CacheLedger.MAX_SAMPLES + 5) { list = CacheLedger.push(list, sample(prompt = 10, hit = 0)) }
        assertEquals(CacheLedger.MAX_SAMPLES, list.size)
    }

    @Test
    fun summarySplitsKnownAndUnknown() {
        val samples = listOf(
            sample(prompt = 100, hit = 60),
            sample(prompt = 900, hit = 0).copy(reason = MissReason.PREFIX_SHRINK.code),
            sample(prompt = 500, hit = 0).copy(known = false),
        )
        val s = CacheLedger.summary(samples)
        assertEquals(3, (s["requests"] as Number).toInt())
        assertEquals(2, (s["known"] as Number).toInt())
        assertEquals(1000L, (s["promptTokens"] as Number).toLong())
        assertEquals(60L, (s["hitTokens"] as Number).toLong())
        assertEquals(0.06, (s["hitRate"] as Number).toDouble(), 0.0001)
        assertEquals(1, ((s["byReason"] as Map<*, *>)["prefix_shrink"] as Number).toInt())
    }

    // ── 前缀稳定化 / 成本模型 ──

    @Test
    fun stabilityCountsChanges() {
        val hist = listOf(
            mapOf("A" to "1", "B" to "1"),
            mapOf("A" to "1", "B" to "2"),
            mapOf("A" to "1", "B" to "2"),
        ).map { m -> com.aichat.app.plugins.js.jobj(*m.entries.map { it.key to it.value }.toTypedArray()) }
        val stab = PrefixStabilizer.stability(hist, listOf("A", "B"))
        assertEquals(1.0, stab["A"]!!, 0.0001)
        assertEquals(0.5, stab["B"]!!, 0.0001)
    }

    @Test
    fun expectedLcpIsPrefixProduct() {
        val segs = listOf(
            PrefixStabilizer.Seg("S1", "L1", "world", 500, "h1"),
            PrefixStabilizer.Seg("S2", "L2", "memory", 500, "h2"),
        )
        val stab = mapOf("S1" to 1.0, "S2" to 0.5)
        // 前 500 token 全中；后 500 token 命中概率 0.5 → 0.75
        assertEquals(0.75, PrefixStabilizer.expectedLcp(segs, stab), 0.0001)
    }

    @Test
    fun breakpointsOnlyOnStableSegments() {
        val segs = listOf(
            PrefixStabilizer.Seg("S1", "L1", "world", 100, "h1"),
            PrefixStabilizer.Seg("S2", "L2", "memory", 100, "h2"),
            PrefixStabilizer.Seg("S3", "L3", "recent", 100, "h3"),
        )
        val stab = mapOf("S1" to 1.0, "S2" to 0.5, "S3" to 1.0)
        assertEquals(listOf(0), PrefixStabilizer.breakpoints(segs, stab))
    }

    @Test
    fun orderPutsStableFirst() {
        val segs = listOf(
            PrefixStabilizer.Seg("S1", "L1", "recent", 100, "h1"),
            PrefixStabilizer.Seg("S2", "L2", "world", 100, "h2"),
        )
        val ordered = PrefixStabilizer.orderByStability(segs, mapOf("S1" to 0.2, "S2" to 1.0))
        assertEquals(listOf("S2", "S1"), ordered.map { it.id })
    }

    @Test
    fun ttlSuggestionFollowsGaps() {
        assertEquals(240L, CacheCostModel.suggestedTtlSec(emptyList()))
        // 中位间隔 100s → 150s
        assertEquals(150L, CacheCostModel.suggestedTtlSec(listOf(100.0, 90.0, 110.0)))
        // 间隔很短 → 夹到下限 60s
        assertEquals(60L, CacheCostModel.suggestedTtlSec(listOf(5.0)))
        // 间隔很长 → 夹到上限 3600s
        assertEquals(3600L, CacheCostModel.suggestedTtlSec(listOf(5000.0)))
    }

    @Test
    fun worthWritingNeedsBenefitAboveWriteCost() {
        // 便宜的输入价：写缓存的钱赚不回来
        assertFalse(CacheCostModel.worthWriting(2000, 0.1, 0.025, 0.1, 0.9, 1))
        // 贵模型 + 长前缀 + 还会聊几轮：值得写
        assertTrue(CacheCostModel.worthWriting(20000, 3.0, 0.3, 3.0, 0.9, 4))
    }

    @Test
    fun expectedTurnsFromGaps() {
        assertEquals(1, CacheCostModel.expectedTurns(emptyList()))
        assertEquals(18, CacheCostModel.expectedTurns(listOf(100.0)))
        assertEquals(CacheCostModel.MAX_TURNS, CacheCostModel.expectedTurns(listOf(1.0)))
    }
    /**
     * 会话覆盖必须认得 `rpcache` 这一档。
     *
     * 曾经漏了：`overrideOf` 的白名单里没有它、`planFor` 也没有分支，
     * 于是「这个会话用 rpcache」被判非法退回自动判断，或直接变成 none（静默降级）。
     */
    @Test
    fun sessionOverrideAcceptsRpcacheOwner() {
        val p = provider(
            id = "p-qwen", presetId = "qwen",
            baseUrl = "https://dashscope.aliyuncs.com", models = listOf("qwen-plus"),
        )
        val c = conv(
            "p-qwen", "qwen-plus",
            buildJsonObject {
                putJsonObject("plugins") {
                    putJsonObject("cachepolicy") {
                        put("owner", kotlinx.serialization.json.JsonPrimitive("rpcache"))
                    }
                }
            },
        )
        val plan = CachePolicy.pick(p, "qwen-plus", AppSettings(providers = listOf(p)), c)
        assertEquals(CachePolicy.OWNER_RPCACHE, plan.owner)
        assertEquals(CachePolicy.MODE_BREAKPOINTS, plan.mode)
        assertTrue("rpcache 会改请求（打 cache_control），mutates 必须为 true", plan.mutates)
        assertTrue(plan.reason.contains("rpcache"))
    }
}