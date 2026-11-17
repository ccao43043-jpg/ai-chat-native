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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓存体检插件端到端：请求前选路线 → 响应后记样本 → 徽章出数字。
 *
 * 这条链路最容易「纯函数都对、接线断了」，所以单独锁一份。
 */
class CacheMeterPluginTest {

    private val provider = ProviderConfig(
        id = "p1", presetId = "openrouter", name = "OpenRouter",
        type = ProviderType.OPENAI, baseUrl = "https://openrouter.ai/api/v1",
        models = listOf("google/gemini-2.5-pro"),
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

    private fun conv(system: String = "你是助手。") = Conversation(
        id = "c1", title = "t", providerId = "p1", model = "google/gemini-2.5-pro",
        createdAt = 1L, updatedAt = 1L, messages = emptyList(), systemPrompt = system,
    )

    private fun ctx(c: Conversation) = RequestContext(
        conv = c,
        provider = provider,
        model = "google/gemini-2.5-pro",
        systemPrompt = c.systemPrompt ?: "",
        messages = emptyList(),
        opts = ChatOptions(),
        settings = settings,
        lastUserId = "u1",
        bodyPatch = JsonObject(emptyMap()),
        meta = JsonObject(emptyMap()),
        activePlugins = emptyList(),
    )

    private fun turn(usage: Usage?, ok: Boolean = true) = TurnResult(
        ok = ok, httpStatus = 200, usage = usage,
        assistant = Message(id = "a1", role = Role.ASSISTANT, content = "嗯。"),
    )

    @Test
    fun firstTurnRecordsPlanAndSample() = runTest {
        val p = CacheMeterPlugin(host)
        val c = conv()
        p.onBeforeRequest(ctx(c), {})
        p.onAfterResponse(turn(Usage(prompt = 1000L, hit = 600L, cacheSupported = true)), ctx(c))

        val d = host.convData(c, CacheMeterPlugin.ID)
        val plan = CachePlan.of(d["plan"] as? JObj)
        assertNotNull(plan)
        assertEquals("OpenRouter 上的 Gemini 必须走 gcache", CachePolicy.OWNER_GCACHE, plan!!.owner)

        val ledger = d["ledger"] as List<*>
        assertEquals(1, ledger.size)
        val summary = d["summary"] as JObj
        assertEquals(1, (summary["known"] as Number).toInt())
        assertEquals(0.6, (summary["hitRate"] as Number).toDouble(), 0.0001)
        // 首轮没有可比对象，不归因
        assertEquals(null, (ledger[0] as JObj)["reason"])
    }

    @Test
    fun changedPrefixIsAttributedAsLcpLow() = runTest {
        val p = CacheMeterPlugin(host)
        val c1 = conv("你是助手。世界：海边渔村，夜里涨潮。")
        p.onBeforeRequest(ctx(c1), {})
        clock += 10_000L
        p.onAfterResponse(turn(Usage(prompt = 2000L, hit = 1500L, cacheSupported = true)), ctx(c1))

        // 第二轮 system 中段被改写（RP 状态变了），命中归零
        clock += 10_000L
        val c2 = c1.copy(systemPrompt = "你是助手。世界：海边渔村，白天退潮。")
        p.onBeforeRequest(ctx(c2), {})
        clock += 10_000L
        p.onAfterResponse(turn(Usage(prompt = 2000L, hit = 0L, cacheSupported = true)), ctx(c2))

        val d = host.convData(c2, CacheMeterPlugin.ID)
        val ledger = d["ledger"] as List<*>
        assertEquals(2, ledger.size)
        val second = ledger[1] as JObj
        assertEquals(MissReason.LCP_LOW.code, second["reason"])
        val s = d["summary"] as JObj
        assertEquals(2, (s["known"] as Number).toInt())
    }

    @Test
    fun badgeShowsTokenWeightedRate() = runTest {
        val p = CacheMeterPlugin(host)
        val c = conv()
        p.onBeforeRequest(ctx(c), {})
        p.onAfterResponse(turn(Usage(prompt = 1000L, hit = 700L, cacheSupported = true)), ctx(c))

        val d = host.convData(c, CacheMeterPlugin.ID)
        val convWithData = c.copy(
            extras = JsonObject(LinkedHashMap(c.extras).also { it["plugins"] = kotlinx.serialization.json.JsonObject(mapOf(CacheMeterPlugin.ID to Js2.toJson(d))) }),
        )
        val badges = p.headerBadges(convWithData)
        assertEquals(1, badges.size)
        assertEquals("缓存 70%", badges[0].text)
        assertEquals("ok", badges[0].tone)
        assertTrue("详情里要写清路线", badges[0].detail!!.contains("gcache"))
    }

    @Test
    fun unknownUsageProducesNoBadge() = runTest {
        val p = CacheMeterPlugin(host)
        val c = conv()
        p.onBeforeRequest(ctx(c), {})
        p.onAfterResponse(turn(Usage(prompt = 800L, completion = 20L)), ctx(c))
        val d = host.convData(c, CacheMeterPlugin.ID)
        val convWithData = c.copy(
            extras = JsonObject(LinkedHashMap(c.extras).also { it["plugins"] = kotlinx.serialization.json.JsonObject(mapOf(CacheMeterPlugin.ID to Js2.toJson(d))) }),
        )
        assertTrue(p.headerBadges(convWithData).isEmpty())
    }

    private object Js2 {
        fun toJson(v: JObj) = com.aichat.app.plugins.js.Js.toJson(v)
    }
}
