package com.aichat.app.plugins.affect

import com.aichat.app.domain.ChatOptions
import com.aichat.app.domain.RequestContext
import com.aichat.app.domain.TurnResult
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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AffectPluginTest {

    private val provider = ProviderConfig(
        id = "p1", presetId = "deepseek", name = "DeepSeek",
        type = ProviderType.OPENAI, baseUrl = "https://api.deepseek.com", models = listOf("deepseek-chat"),
    )

    private val settings = AppSettings(providers = listOf(provider))

    private var now: Long = 1_700_000_000_000L

    private val host = object : PluginHost {
        override fun now(): Long = now
        override fun convData(conv: Conversation, id: String): JObj = JObj()
        override fun config(settings: AppSettings, id: String): JObj = JObj()
        override fun isActive(id: String, settings: AppSettings, conv: Conversation?): Boolean = true
        override fun notify(text: String) {}
    }

    private fun conv(): Conversation = Conversation(
        id = "c1", title = "测试", providerId = "p1", model = "deepseek-chat",
        createdAt = 1L, updatedAt = 1L,
        messages = listOf(Message(id = "m1", role = Role.USER, content = "你好")),
        systemPrompt = "你是助手。",
        extras = EmptyJson,
    )

    private fun ctx(c: Conversation): RequestContext = RequestContext(
        conv = c, provider = provider, model = "deepseek-chat",
        systemPrompt = c.systemPrompt ?: "",
        messages = c.messages.map { WireMessage(it.role, it.content, srcId = it.id) },
        opts = ChatOptions(), settings = settings, lastUserId = "m1",
    )

    @Test
    fun addsProtocolOnceAndPrefixesLastUser() = runTest {
        val plugin = AffectPlugin(host)
        val out = plugin.onBeforeRequest(ctx(conv())) {}
        assertTrue("system 末尾应挂协议", out.systemPrompt.endsWith("状态。"))
        assertTrue(out.systemPrompt.contains("[AFFECT_RULE]"))
        assertTrue("末条 user 前置短 tag", out.messages.last().content.startsWith("[AFFECT] mood="))
        assertTrue(out.messages.last().content.endsWith("你好"))
        assertNotNull(out.meta["affect"])

        // 第二轮：协议不重复
        val c2 = conv().copy(systemPrompt = out.systemPrompt)
        val out2 = plugin.onBeforeRequest(ctx(c2)) {}
        assertEquals(1, Regex("\\[AFFECT_RULE\\]").findAll(out2.systemPrompt).count())
    }

    @Test
    fun afterResponseStripsStateAndStoresAffect() = runTest {
        val plugin = AffectPlugin(host)
        val c = conv()
        plugin.onBeforeRequest(ctx(c)) {}

        val bot = Message(
            id = "m2", role = Role.ASSISTANT,
            content = "好呀。\n<state>{\"a\":2,\"m\":\"开心\",\"why\":\"你好\"}</state>",
        )
        val result = TurnResult(ok = true, httpStatus = 200, usage = null, assistant = bot)
        val next = plugin.onAfterResponse(result, ctx(c))
        assertNotNull(next)
        val stored = next!!.messages.first { it.id == "m2" }
        assertFalse("正文不该留着 <state>", stored.content.contains("<state>"))
        val aff = stored.extras["affect"]
        assertNotNull("应存 affect", aff)
        assertTrue(aff.toString().contains("\"mood\""))

        // 最后一条 user 冻结了本轮 tag
        val user = next.messages.first { it.id == "m1" }
        assertTrue(user.extras["affectInj"].toString().contains("[AFFECT]"))
    }

    @Test
    fun headerBadgesNeedAffectFirst() = runTest {
        val plugin = AffectPlugin(host)
        assertTrue(plugin.headerBadges(conv()).isEmpty())

        val c = conv()
        plugin.onBeforeRequest(ctx(c)) {}
        val bot = Message(id = "m2", role = Role.ASSISTANT, content = "嗯。\n<state>{\"a\":1,\"m\":\"开心\"}</state>")
        val next = plugin.onAfterResponse(
            TurnResult(ok = true, httpStatus = 200, usage = null, assistant = bot), ctx(c),
        )!!
        val badges = plugin.headerBadges(next)
        assertEquals(1, badges.size)
        assertTrue(badges[0].text.contains("·"))
    }
}
