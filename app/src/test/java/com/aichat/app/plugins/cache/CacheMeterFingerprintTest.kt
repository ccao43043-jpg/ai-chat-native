package com.aichat.app.plugins.cache

import com.aichat.app.domain.ChatOptions
import com.aichat.app.domain.RequestContext
import com.aichat.app.domain.TurnResult
import com.aichat.app.domain.model.AppSettings
import com.aichat.app.domain.model.Conversation
import com.aichat.app.domain.model.Message
import com.aichat.app.domain.model.ProviderConfig
import com.aichat.app.domain.model.ProviderType
import com.aichat.app.domain.model.Role
import com.aichat.app.domain.model.Usage
import com.aichat.app.plugins.PluginHost
import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.jobj
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C7：前缀观测从「前 4000 字符」改成「层块 hash」。
 *
 * 旧实现把 system 截断到 4000 字符再比 LCP，而 RP 的 system 基本都超过 4000，
 * 章节 / 事实 / 长期记忆恰好排在 4000 之后 —— 改动完全看不见，恒返回 0.95。
 * 这组测试把那个 0.95 钉死，同时锁住新口径「断在第几块」。
 */
class CacheMeterFingerprintTest {

    private val provider = ProviderConfig(
        id = "p1", presetId = "openrouter", name = "OpenRouter",
        type = ProviderType.OPENAI, baseUrl = "https://openrouter.ai/api/v1",
        models = listOf("google/gemini-2.5-flash"),
    )
    private val settings = AppSettings(providers = listOf(provider))

    private var clock = 1_000_000L
    private val store = HashMap<String, JObj>()

    private val host = object : PluginHost {
        override fun now(): Long = clock
        override fun convData(conv: Conversation, id: String): JObj = store.getOrPut(conv.id + "/" + id) { JObj() }
        override fun config(settings: AppSettings, id: String): JObj = JObj()
        override fun isActive(id: String, settings: AppSettings, conv: Conversation?): Boolean = true
        override fun notify(text: String) {}
    }

    private fun conv(system: String) = Conversation(
        id = "c1", title = "t", providerId = "p1", model = "google/gemini-2.5-flash",
        createdAt = 1L, updatedAt = 1L, messages = emptyList(), systemPrompt = system,
    )

    private fun ctx(c: Conversation) = RequestContext(
        conv = c,
        provider = provider,
        model = "google/gemini-2.5-flash",
        systemPrompt = c.systemPrompt ?: "",
        messages = emptyList(),
        opts = ChatOptions(),
        settings = settings,
        lastUserId = "u1",
        bodyPatch = JsonObject(emptyMap()),
        meta = JsonObject(emptyMap()),
        activePlugins = emptyList(),
    )

    private fun turn(usage: Usage) = TurnResult(
        ok = true, httpStatus = 200, usage = usage,
        assistant = Message(id = "a1", role = Role.ASSISTANT, content = "嗯。"),
    )

    /** 前 4000 字符恒定、之后才是章节：这正是线上 RP 对话的形状 */
    private fun rpSystem(chapter: String): String =
        "甲".repeat(4000) + "\n\n[CHAPTER_SUMMARY #001]\n" + chapter +
            "\n\n[CURRENT_STATE]\n第三幕，码头"

    @Test
    fun changeBeyondSnapshotIsVisibleNow() = runTest {
        val p = CacheMeterPlugin(host)

        val c1 = conv(rpSystem("乙".repeat(1200)))
        p.onBeforeRequest(ctx(c1), {})
        clock += 10_000L
        p.onAfterResponse(turn(Usage(prompt = 2000L, hit = 1900L, cacheSupported = true)), ctx(c1))

        // 只改章节（在 4000 字符之后），前缀其余部分逐字不变
        clock += 10_000L
        val c2 = conv(rpSystem("丙".repeat(1200)))
        p.onBeforeRequest(ctx(c2), {})
        clock += 10_000L
        p.onAfterResponse(turn(Usage(prompt = 2000L, hit = 100L, cacheSupported = true)), ctx(c2))

        val d = host.convData(c2, CacheMeterPlugin.ID)
        val info = d["lcpInfo"] as JObj
        val lcp = (info["lcp"] as Number).toDouble()

        // 旧口径（截断 4000 + i>=prev.length → 0.95）在这里会报「几乎全中」
        val prevSnap = rpSystem("乙".repeat(1200)).take(CacheMeterPlugin.SYS_SNAPSHOT)
        val cur = rpSystem("丙".repeat(1200))
        val oldStyle = if (prevSnap == cur) 1.0 else 0.95
        assertEquals("旧口径确实会误报 0.95", 0.95, oldStyle, 0.0)

        assertTrue("章节在 4000 之后也必须看得见（实际 $lcp）", lcp < 0.90)
        assertEquals("断在第 2 块（0 基下标 1）", 1, (info["firstDiff"] as Number).toInt())
        assertEquals(3, (info["blocks"] as Number).toInt())
        assertEquals(3, (d["lastBlocks"] as List<*>).size)
    }

    @Test
    fun identicalSystemStillReportsFullPrefix() = runTest {
        val p = CacheMeterPlugin(host)
        val c = conv(rpSystem("乙".repeat(200)))
        p.onBeforeRequest(ctx(c), {})
        clock += 10_000L
        p.onAfterResponse(turn(Usage(prompt = 900L, hit = 800L, cacheSupported = true)), ctx(c))
        clock += 10_000L
        p.onBeforeRequest(ctx(c), {})
        val info = host.convData(c, CacheMeterPlugin.ID)["lcpInfo"] as JObj
        assertEquals(1.0, (info["lcp"] as Number).toDouble(), 0.0001)
        // 全中时 firstDiff 走到末尾（= 块数），表示「没有断点」
        assertEquals(3, (info["firstDiff"] as Number).toInt())
    }

    @Test
    fun tagHeadersSplitBlocks() {
        // rpcache 用 \n\n 连接层，affect 用 \n 追加协议 —— 两种都要能切开
        val text = "[LONG_TERM_MEMORY]\n记忆内容\n[AFFECT_RULE]\n协议正文"
        val blocks = PrefixStabilizer.fingerprint(text)
        assertEquals(2, blocks.size)
        assertNotEquals(blocks[0]["h"], blocks[1]["h"])
    }

    @Test
    fun lcpStopsAtFirstDifferingBlock() {
        val a = PrefixStabilizer.fingerprint("一\n\n二\n\n三")
        val b = PrefixStabilizer.fingerprint("一\n\n二改\n\n三")
        val r = PrefixStabilizer.lcpOf(a, b)
        assertEquals(1, (r["firstDiff"] as Number).toInt())
        assertTrue((r["lcp"] as Number).toDouble() < 1.0)
        assertEquals(3, (r["blocks"] as Number).toInt())
    }

    @Test
    fun blocksRoundTripThroughJson() {
        val blocks = PrefixStabilizer.fingerprint("一\n\n二")
        // 落盘形状：JsonArray<JsonObject>；blocksOf 负责还原成 JObj
        val json = com.aichat.app.plugins.js.Js.toJson(com.aichat.app.plugins.js.jarr(*blocks.toTypedArray()))
        val back = PrefixStabilizer.blocksOf(json)
        assertEquals(2, back.size)
        assertEquals(blocks[0]["h"], back[0]["h"])
    }
}

/**
 * C3：门槛只做温和上调，不能因为「命中率低 → 门槛升高 → 更打不出去」单向锁死。
 */
class GcacheAdaptiveGateTest {

    private fun stats(n: Int, rate: Double) = jobj("hitSamples" to n, "hitRate" to rate)

    @Test
    fun baseThresholdWithoutSamples() {
        assertEquals(1024.0, GCache.adaptiveMinTokens("gemini-2.5-flash", null), 0.0)
        assertEquals(2048.0, GCache.adaptiveMinTokens("gemini-2.0-flash-lite", stats(3, 0.1)), 0.0)
        assertEquals(4096.0, GCache.adaptiveMinTokens("gemini-2.5-pro", null), 0.0)
    }

    @Test
    fun onlyVeryLowHitRateRaisesAndItIsCapped() {
        // 旧实现 <40% 就 ×1.5（1024 → 1536），现在只到 1.25 倍
        assertEquals(1280.0, GCache.adaptiveMinTokens("gemini-2.5-flash", stats(8, 0.30)), 0.0)
        assertEquals(1280.0, GCache.adaptiveMinTokens("gemini-2.5-flash", stats(8, 0.34)), 0.0)
        // 0.35 是分界，从这一档起不再上调（旧实现 0.40 才停）
        assertEquals(1024.0, GCache.adaptiveMinTokens("gemini-2.5-flash", stats(8, 0.35)), 0.0)
        assertEquals(1280.0, GCache.adaptiveMinTokens("gemini-2.5-flash", stats(20, 0.0)), 0.0)
    }

    @Test
    fun mediocreHitRateNoLongerRaises() {
        // 旧实现 0.45 会 ×1.25，正是「锁死」的中间档；现在回到基线
        assertEquals(1024.0, GCache.adaptiveMinTokens("gemini-2.5-flash", stats(8, 0.45)), 0.0)
        assertEquals(1024.0, GCache.adaptiveMinTokens("gemini-2.5-flash", stats(8, 0.60)), 0.0)
    }

    @Test
    fun tooFewSamplesNeverRaises() {
        assertEquals(1024.0, GCache.adaptiveMinTokens("gemini-2.5-flash", stats(4, 0.1)), 0.0)
    }
}