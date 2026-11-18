package com.aichat.app.plugins.rp

import com.aichat.app.domain.ChatOptions
import com.aichat.app.domain.RequestContext
import com.aichat.app.domain.WireMessage
import com.aichat.app.domain.model.AppSettings
import com.aichat.app.domain.model.Conversation
import com.aichat.app.domain.model.EmptyJson
import com.aichat.app.domain.model.Message
import com.aichat.app.domain.model.ProviderConfig
import com.aichat.app.domain.model.ProviderType
import com.aichat.app.domain.model.Role
import com.aichat.app.plugins.PluginHost
import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 千问显式断点是否真的落到请求上。
 *
 * 这条链在 1.6.0 里断成两截：`CachePolicy` 判了 `owner=rpcache`，但 rpcache 只认 RP 块，
 * 纯聊天时插件根本没跑 → 请求里一个 `cache_control` 都没有。这里用真插件跑一遍，
 * 断言「够长就打点、太短就明确不算打点（留给徽章解释）」。
 */
class RpCacheMarkersWiringTest {

    private val qwen = ProviderConfig(
        id = "p-qwen", presetId = "qwen", name = "千问",
        type = ProviderType.OPENAI, baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        models = listOf("qwen-plus"),
    )
    private val settings = AppSettings(providers = listOf(qwen))

    private val host = object : PluginHost {
        override fun now(): Long = 1_700_000_000_000L
        override fun convData(conv: Conversation, id: String): JObj = JObj()
        override fun config(settings: AppSettings, id: String): JObj = JObj()
        override fun isActive(id: String, settings: AppSettings, conv: Conversation?): Boolean = true
        override fun notify(text: String) {}
    }

    private fun stateWithWorld(text: String): JsonObject =
        Js.toJson(RpState.setBlock(RpState.newState(), "world", text, 1L)["state"] as JObj) as JsonObject

    private fun conv(state: JsonObject = EmptyJson) = Conversation(
        id = "c1", title = "测试", providerId = "p-qwen", model = "qwen-plus",
        createdAt = 1L, updatedAt = 1L,
        messages = listOf(Message(id = "u1", role = Role.USER, content = "你好", createdAt = 1L)),
        rpState = state,
    )

    private fun ctx(c: Conversation) = RequestContext(
        conv = c, provider = qwen, model = "qwen-plus",
        systemPrompt = c.systemPrompt ?: "",
        messages = c.messages.map { WireMessage(it.role, it.content, srcId = it.id) },
        opts = ChatOptions(), settings = settings, lastUserId = "u1",
    )

    @Test
    fun longSystemOnQwenGetsExplicitMarkers() = runTest {
        val plugin = RpCachePlugin(host)
        val long = "北境终年积雪，寒风常年不停。".repeat(150)
        val out = plugin.onBeforeRequest(ctx(conv(stateWithWorld(long)))) {}
        assertTrue("够长的 system 应打上 cache_control", out.opts.cacheControl)
        val marks = out.meta["rpcacheMarks"]
        assertNotNull("rpcacheMarks 必须写进 meta，徽章才有话可说", marks)
    }

    @Test
    fun shortSystemReportsNoMarkersInsteadOfSilence() = runTest {
        val plugin = RpCachePlugin(host)
        val out = plugin.onBeforeRequest(ctx(conv(stateWithWorld("A cold harbor town.")))) {}
        assertFalse(out.opts.cacheControl)
        assertNotNull(out.meta["rpcacheMarks"])
        val marks = Js.fromJson(out.meta["rpcacheMarks"]) as JObj
        // applied=「这条路要不要打点」，count=「实际打了几个」：设定太短时前者真、后者 0，
        // 徽章就是靠这两个字段说清「不是没生效，是你的设定还不够长」。
        assertTrue(marks["applied"] as Boolean)
        assertEquals(0, (marks["count"] as Number).toInt())
    }

    @Test
    fun deepSeekChatNeverGetsMarkers() = runTest {
        val ds = ProviderConfig(
            id = "p-ds", presetId = "deepseek", name = "DeepSeek",
            type = ProviderType.OPENAI, baseUrl = "https://api.deepseek.com", models = listOf("deepseek-chat"),
        )
        val s = AppSettings(providers = listOf(ds))
        val plugin = RpCachePlugin(host)
        val long = "北境终年积雪，寒风常年不停。".repeat(150)
        val c = Conversation(
            id = "c2", title = "t", providerId = "p-ds", model = "deepseek-chat",
            createdAt = 1L, updatedAt = 1L,
            messages = listOf(Message(id = "u1", role = Role.USER, content = "你好", createdAt = 1L)),
            rpState = stateWithWorld(long),
        )
        val base = RequestContext(
            conv = c, provider = ds, model = "deepseek-chat", systemPrompt = "",
            messages = c.messages.map { WireMessage(it.role, it.content, srcId = it.id) },
            opts = ChatOptions(), settings = s, lastUserId = "u1",
        )
        val out = plugin.onBeforeRequest(base) {}
        assertFalse("DeepSeek 是隐式缓存，绝不能带 cache_control", out.opts.cacheControl)
    }
    @Test
    fun emptyConversationStaysByteIdenticalEvenWhenRouteIsRpcache() = runTest {
        val plugin = RpCachePlugin(host)
        val c = conv()
        val base = ctx(c)
        val out = plugin.onBeforeRequest(base) {}
        assertEquals("没有 RP 内容时 system 必须逐字节不变", base.systemPrompt, out.systemPrompt)
        assertEquals("末条 user 也不能被改写", base.messages.last().content, out.messages.last().content)
        assertEquals("opts 不该被动过", base.opts, out.opts)
    }
    private val anthropic = ProviderConfig(
        id = "p-anth", presetId = "anthropic", name = "Anthropic",
        type = ProviderType.ANTHROPIC, baseUrl = "https://api.anthropic.com", models = listOf("claude-sonnet-4-5"),
    )

    private fun ctxAnthropic(c: Conversation) = RequestContext(
        conv = c, provider = anthropic, model = "claude-sonnet-4-5",
        systemPrompt = c.systemPrompt ?: "",
        messages = c.messages.map { WireMessage(it.role, it.content, srcId = it.id) },
        opts = ChatOptions(), settings = AppSettings(providers = listOf(anthropic)), lastUserId = "u1",
    )

    /**
     * Anthropic 以前判给 `owner=prefix`「只观测」，而 prefix 路线没有执行者 ——
     * 实测一个 `cache_control` 都没出去过。现在由 rpcache 打点，这里锁住行为。
     */
    @Test
    fun longSystemOnAnthropicGetsCacheControl() = runTest {
        val plugin = RpCachePlugin(host)
        val long = "北境终年积雪，寒风常年不停。".repeat(150)
        val c = conv(stateWithWorld(long)).copy(providerId = "p-anth", model = "claude-sonnet-4-5")
        val out = plugin.onBeforeRequest(ctxAnthropic(c)) {}
        assertTrue("Anthropic 也要真打上 cache_control", out.opts.cacheControl)
        val marks = Js.fromJson(out.meta["rpcacheMarks"]) as JObj
        assertTrue("打了点就要有计数", (marks["count"] as Number).toInt() > 0)
    }

    /**
     * `ctx.meta["rpSegments"]` 以前只有 JS 写、原生从不写，
     * gcache 的分层分析在原生侧永远拿到空值。这里锁住「必须发布且字段齐全」。
     */
    @Test
    fun rpSegmentsArePublishedForDownstreamLayering() = runTest {
        val plugin = RpCachePlugin(host)
        val out = plugin.onBeforeRequest(ctx(conv(stateWithWorld("海边渔村，终年多雾。")))) {}
        val raw = out.meta["rpSegments"]
        assertNotNull("rpcache 必须发布 rpSegments（gcache 靠它分层）", raw)
        val arr = raw!!.jsonArray
        assertTrue("至少要有一段", arr.isNotEmpty())
        assertTrue("每段都要有 kind/text/gl", arr.all { e ->
            e.jsonObject.containsKey("kind") && e.jsonObject.containsKey("text") && e.jsonObject.containsKey("gl")
        })
    }
}