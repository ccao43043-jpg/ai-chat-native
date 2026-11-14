package com.aichat.app.plugins.rp

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
import com.aichat.app.domain.model.Usage
import com.aichat.app.plugins.PluginHost
import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.js.jobj
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RpCachePluginTest {

    private val provider = ProviderConfig(
        id = "p1", presetId = "deepseek", name = "DeepSeek",
        type = ProviderType.OPENAI, baseUrl = "https://api.deepseek.com", models = listOf("deepseek-chat"),
    )

    private val settings = AppSettings(providers = listOf(provider))

    private val host = object : PluginHost {
        override fun now(): Long = 1_700_000_000_000L
        override fun convData(conv: Conversation, id: String): JObj = JObj()
        override fun config(settings: AppSettings, id: String): JObj = JObj()
        override fun isActive(id: String, settings: AppSettings, conv: Conversation?): Boolean = true
        override fun notify(text: String) {}
    }

    private fun conv(rpState: JsonObject = EmptyJson): Conversation = Conversation(
        id = "c1", title = "测试", providerId = "p1", model = "deepseek-chat",
        createdAt = 1L, updatedAt = 1L,
        messages = listOf(
            Message(id = "m1", role = Role.USER, content = "第一轮"),
            Message(id = "m2", role = Role.ASSISTANT, content = "回答一"),
            Message(id = "m3", role = Role.USER, content = "第二轮"),
        ),
        systemPrompt = "你是助手。",
        rpState = rpState,
    )

    /** 造一个「世界块 + 当前状态块都有内容」的 RP 状态。 */
    private fun stateWithWorld(): JsonObject {
        val st = RpState.normalizeState(EmptyJson)
        val blocks = LinkedHashMap<String, Any?>(st)
        val list = st["blocks"] as? List<*> ?: emptyList<Any?>()
        val out = ArrayList<Any?>()
        for (b in list) {
            if (b is MutableMap<*, *>) {
                @Suppress("UNCHECKED_CAST")
                val m = b as MutableMap<String, Any?>
                if (m["id"] == "world") m["content"] = "北境，终年积雪。"
                if (m["id"] == "state") m["content"] = "时间：清晨"
                out.add(m)
            } else {
                out.add(b)
            }
        }
        blocks["blocks"] = out
        return Js.toJson(blocks) as? JsonObject ?: EmptyJson
    }

    private fun ctx(c: Conversation): RequestContext = RequestContext(
        conv = c, provider = provider, model = "deepseek-chat",
        systemPrompt = c.systemPrompt ?: "",
        messages = c.messages.map { WireMessage(it.role, it.content, srcId = it.id) },
        opts = ChatOptions(), settings = settings, lastUserId = "m3",
    )

    @Test
    fun noRpBlocksLeavesRequestUntouched() = runTest {
        val plugin = RpCachePlugin(host)
        val c = conv()
        val before = ctx(c)
        assertFalse(plugin.autoActive(c, settings))
        val after = plugin.onBeforeRequest(before) {}
        assertSame(before, after)
    }

    @Test
    fun worldBlockRewritesSystemAndLastUser() = runTest {
        val plugin = RpCachePlugin(host)
        val c = conv(stateWithWorld())
        assertTrue(plugin.autoActive(c, settings))

        val out = plugin.onBeforeRequest(ctx(c)) {}
        assertTrue("system 应含 [WORLD]", out.systemPrompt.contains("[WORLD]"))
        assertTrue("system 应含世界内容", out.systemPrompt.contains("北境"))
        assertEquals(3, out.messages.size)
        assertEquals("第一轮", out.messages[0].content)
        assertEquals("回答一", out.messages[1].content)
        assertTrue("末条以本轮输入开头", out.messages[2].content.startsWith("第二轮"))
        assertTrue("末条应带当前状态", out.messages[2].content.contains("[CURRENT_STATE]"))
        assertNotNull(out.meta["rpcache"])
    }

    @Test
    fun afterResponseWritesBackState() = runTest {
        val plugin = RpCachePlugin(host)
        val c = conv(stateWithWorld())
        val out = plugin.onBeforeRequest(ctx(c)) {}
        val result = TurnResult(
            ok = true, httpStatus = 200, usage = null,
            assistant = Message(id = "m4", role = Role.ASSISTANT, content = "回答二"),
        )
        val next = plugin.onAfterResponse(result, out)
        assertNotNull("应把同步后的状态写回对话", next)
        assertTrue(next!!.rpState.toString().contains("北境"))
        assertNull("pending 只用一次", plugin.onAfterResponse(result, out))
    }

    /** 带一章摘要的状态：第 1、2 条已被章节覆盖。 */
    private fun stateWithChapter(): JsonObject {
        val st = Js.fromJson(stateWithWorld()) as JObj
        st["chapters"] = arrayListOf<Any?>(
            jobj("no" to 1, "content" to "两人在雪原上相遇。", "coveredIds" to arrayListOf("m1", "m2")),
        )
        return Js.toJson(st) as? JsonObject ?: EmptyJson
    }

    @Test
    fun afterResponseStoresCacheStatsAndBadgesReadThem() = runTest {
        val plugin = RpCachePlugin(host)
        val c = conv(stateWithWorld())
        val out = plugin.onBeforeRequest(ctx(c)) {}
        val result = TurnResult(
            ok = true, httpStatus = 200,
            usage = Usage(prompt = 1000, hit = 800, miss = 200, cacheSupported = true),
            assistant = Message(id = "m4", role = Role.ASSISTANT, content = "回答二"),
        )
        val next = plugin.onAfterResponse(result, out)!!

        val bot = next.messages.first { it.id == "m4" }
        val badges = plugin.messageBadges(bot, next)
        assertEquals(1, badges.size)
        assertEquals("缓存 80.0%", badges[0].text)
        assertEquals("ok", badges[0].tone)

        val header = plugin.headerBadges(next)
        assertEquals(1, header.size)
        assertEquals("命中 80.0%", header[0].text)

        // 用户消息不出徽章
        assertTrue(plugin.messageBadges(next.messages.first { it.id == "m1" }, next).isEmpty())
    }

    @Test
    fun unsupportedCacheShowsPlainBadgeAndNoHeader() = runTest {
        val plugin = RpCachePlugin(host)
        val c = conv(stateWithWorld())
        val out = plugin.onBeforeRequest(ctx(c)) {}
        val result = TurnResult(
            ok = true, httpStatus = 200,
            usage = Usage(prompt = 1000, hit = null, cacheSupported = false),
            assistant = Message(id = "m4", role = Role.ASSISTANT, content = "回答二"),
        )
        val next = plugin.onAfterResponse(result, out)!!
        val bot = next.messages.first { it.id == "m4" }

        val badges = plugin.messageBadges(bot, next)
        assertEquals("缓存 不支持统计", badges[0].text)
        assertEquals("info", badges[0].tone)
        assertTrue("不支持统计时顶栏不显示", plugin.headerBadges(next).isEmpty())
    }

    @Test
    fun coveredMessagesAreDimmedWithChapterLabel() {
        val plugin = RpCachePlugin(host)
        val c = conv(stateWithChapter())

        val m1 = c.messages.first { it.id == "m1" }
        val s1 = plugin.messageState(m1, c)
        assertNotNull(s1)
        assertTrue(s1!!.dim)
        assertTrue("标签应点出章节号", s1.label!!.contains("#001"))

        // 没被覆盖的消息不变灰
        val m3 = c.messages.first { it.id == "m3" }
        assertNull(plugin.messageState(m3, c))
    }

    /** 超预算必须裁历史：否则长对话会被服务商直接 400。裁掉的部分不发。 */
    @Test fun overBudgetTrimsHistoryAndRecordsOverflow() = runTest {
        val st = RpState.normalizeState(EmptyJson)
        val list = st["blocks"] as? List<*> ?: emptyList<Any?>()
        list.forEach { b ->
            if (b is MutableMap<*, *> && b["id"] == "world") {
                @Suppress("UNCHECKED_CAST")
                (b as MutableMap<String, Any?>)["content"] = "北境，终年积雪。"
            }
        }
        val set = LinkedHashMap<String, Any?>()
        (st["settings"] as? Map<*, *>)?.forEach { (k, v) -> set[k.toString()] = v }
        set["max_context"] = 3000
        set["output_reserve"] = 0
        set["safety_margin"] = 0
        st["settings"] = set

        val long = "这是一段很长的正文。".repeat(120)
        val msgs = ArrayList<Message>()
        for (i in 0 until 6) {
            msgs += Message(id = "u$i", role = Role.USER, content = long, createdAt = i.toLong())
            msgs += Message(id = "a$i", role = Role.ASSISTANT, content = long, createdAt = i.toLong())
        }
        val c = conv(Js.toJson(st) as JsonObject).copy(messages = msgs)
        val plugin = RpCachePlugin(host)
        val before = ctx(c)
        val after = plugin.onBeforeRequest(before) {}

        assertTrue("应该裁掉一部分历史（实际 ${after.messages.size}/${before.messages.size}）", after.messages.size < before.messages.size)
        val trace = after.meta["rpcache"] as JsonObject
        assertTrue("trimmed 应为 true", Js.truthy(trace["trimmed"]))
        assertTrue("dropped 应 > 0", trace["dropped"].toString().toInt() > 0)
        assertTrue("最后一条仍是本轮输入", after.messages.last().content.startsWith("这是一段很长的正文。"))
    }
}