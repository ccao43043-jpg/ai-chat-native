package com.aichat.app.plugins.rp

import com.aichat.app.data.repo.JsonDocStore
import com.aichat.app.domain.ChatOptions
import com.aichat.app.domain.RequestContext
import com.aichat.app.domain.TurnResult
import com.aichat.app.domain.WireMessage
import com.aichat.app.domain.model.AppSettings
import com.aichat.app.domain.model.Conversation
import com.aichat.app.domain.model.Message
import com.aichat.app.domain.model.ProviderConfig
import com.aichat.app.domain.model.ProviderType
import com.aichat.app.domain.model.Role
import com.aichat.app.plugins.PluginHost
import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.js.jobj
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * rpmemory 插件层：注入只改末条 user、三重闸（默认关/无条目/无 user 消息）、
 * 自动提取按 interval 触发、失败也推进 lastTurn。
 */
class RpMemoryPluginTest {

    private val provider = ProviderConfig(
        id = "p1", presetId = "deepseek", name = "DeepSeek",
        type = ProviderType.OPENAI, baseUrl = "https://api.deepseek.com", models = listOf("deepseek-chat"),
    )
    private val settings = AppSettings(providers = listOf(provider))

    private fun host(cfg: JObj = jobj("interval" to 20)): PluginHost = object : PluginHost {
        override fun now(): Long = 1_700_000_000_000L
        override fun convData(conv: Conversation, id: String): JObj = JObj()
        override fun config(settings: AppSettings, id: String): JObj = cfg
        override fun isActive(id: String, settings: AppSettings, conv: Conversation?): Boolean = true
        override fun notify(text: String) {}
    }

    private fun conv(personaName: String? = null, turns: Int = 3): Conversation {
        val msgs = ArrayList<Message>()
        for (i in 0 until turns) {
            msgs += Message(id = "u$i", role = Role.USER, content = "第 $i 轮", createdAt = i.toLong())
            msgs += Message(id = "a$i", role = Role.ASSISTANT, content = "回答 $i", createdAt = i.toLong())
        }
        return Conversation(
            id = "c1", title = "测试", providerId = "p1", model = "deepseek-chat",
            createdAt = 1L, updatedAt = 1L, messages = msgs, personaName = personaName,
        )
    }

    private fun ctx(conv: Conversation, settings: AppSettings = this.settings): RequestContext = RequestContext(
        conv = conv, provider = provider, model = "deepseek-chat", systemPrompt = "你是助手。",
        messages = conv.messages.map { WireMessage(it.role, it.content, it.images, srcId = it.id) },
        opts = ChatOptions(), settings = settings, lastUserId = null,
    )

    private fun bucketJson(entries: List<JObj>, lastTurn: Int = 0): JsonObject {
        val all = HashMap<String, Any?>()
        all["conv:c1"] = jobj("entries" to entries, "lastTurn" to lastTurn, "lastAt" to 0L, "lastError" to "")
        return Js.toJson(all) as JsonObject
    }

    private fun storeWith(entries: List<JObj>, lastTurn: Int = 0): JsonDocStore {
        val fs = com.aichat.app.data.store.MemFileStore()
        val s = JsonDocStore(fs, "rp-memory.json")
        s.save(bucketJson(entries, lastTurn))
        return s
    }

    @Test fun noEntriesLeavesRequestUntouched() = runTest {
        val store = storeWith(emptyList())
        val p = RpMemoryPlugin(host(), store) { _, _ -> null }
        val c = ctx(conv())
        assertSame(c, p.onBeforeRequest(c) {})
    }

    @Test fun injectsRecallIntoLastUserOnly() = runTest {
        val store = storeWith(
            listOf(
                jobj("id" to "e1", "type" to "fact", "text" to "主角住在海边渔村", "keywords" to listOf("渔村"), "seq" to 1, "pinned" to true),
                jobj("id" to "e2", "type" to "event", "text" to "铁箱已经上锁", "keywords" to listOf("铁箱"), "seq" to 2, "pinned" to true),
            )
        )
        val p = RpMemoryPlugin(host(), store) { _, _ -> null }
        val c = ctx(conv())
        val out = p.onBeforeRequest(c) {}

        // system 一个字都不动（缓存前缀不受影响）
        assertEquals("你是助手。", out.systemPrompt)
        // 只有最后一条 user 变了，历史原样
        assertEquals(c.messages[0].content, out.messages[0].content)
        // 注入的是最后一条 user，不是最后一条消息（末尾通常是 assistant）
        val li = out.messages.indexOfLast { it.role == Role.USER }
        val u = out.messages[li]
        assertTrue("末条 user 原文应保留在前", u.content.startsWith("第 2 轮"))
        assertTrue("应追加记忆召回", u.content.contains("[MEMORY_RECALL]"))
        assertTrue(u.content.contains("渔村") || u.content.contains("铁箱"))
        // trace 写进 meta（面板以后用）
        assertTrue(out.meta.containsKey("rpmemory"))
    }

    @Test fun personaBucketIsSharedAcrossConversations() = runTest {
        val fs = com.aichat.app.data.store.MemFileStore()
        val store = JsonDocStore(fs, "rp-memory.json")
        val all = HashMap<String, Any?>()
        all["persona:小满"] = jobj(
            "entries" to listOf(jobj("id" to "e1", "type" to "fact", "text" to "小满怕水", "keywords" to listOf("小满"), "seq" to 1, "pinned" to true)),
            "lastTurn" to 0,
        )
        store.save(Js.toJson(all) as JsonObject)

        val p = RpMemoryPlugin(host(), store) { _, _ -> null }
        val out = p.onBeforeRequest(ctx(conv(personaName = "小满"))) {}
        val u = out.messages[out.messages.indexOfLast { it.role == Role.USER }]
        assertTrue("角色卡桶应被共享到新对话", u.content.contains("[MEMORY_RECALL]"))
    }

    @Test fun noUserMessageIsNoOp() = runTest {
        val store = storeWith(listOf(jobj("id" to "e1", "type" to "fact", "text" to "x", "keywords" to listOf("x"), "seq" to 1)))
        val p = RpMemoryPlugin(host(), store) { _, _ -> null }
        val c = ctx(conv()).let { it.copy(messages = it.messages.filter { m -> m.role == Role.ASSISTANT }) }
        assertSame(c, p.onBeforeRequest(c) {})
    }

    @Test fun afterResponseExtractsWhenIntervalReached() = runTest {
        val fs = com.aichat.app.data.store.MemFileStore()
        val store = JsonDocStore(fs, "rp-memory.json")
        store.save(bucketJson(emptyList(), lastTurn = 0))
        var asked = 0
        val p = RpMemoryPlugin(host(jobj("interval" to 5)), store) { _, prompt ->
            asked++
            assertTrue("抽取提示里应带最近对话", prompt.contains("最近对话"))
            """[{"type":"fact","text":"主角叫阿浦","keywords":["阿浦"]}]"""
        }
        val c = ctx(conv(turns = 6))   // interval 会被夹到最小 5，所以给 6 个 user 回合
        p.onAfterResponse(TurnResult(ok = true, httpStatus = 200, usage = null, assistant = c.conv.messages.last()), c)

        // onAfterResponse 是 fire-and-forget，等它落盘
        var all: JsonObject = JsonObject(emptyMap())
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            all = store.load()
            val b = all["conv:c1"] as? JsonObject
            val entries = (b?.get("entries") as? kotlinx.serialization.json.JsonArray)
            if (!entries.isNullOrEmpty()) break
            Thread.sleep(20)
        }
        assertEquals("应只问一次模型", 1, asked)
        val entries = ((all["conv:c1"] as? JsonObject)?.get("entries") as? kotlinx.serialization.json.JsonArray)
        assertEquals(1, entries?.size)
        assertTrue(entries.toString().contains("阿浦"))
    }

    @Test fun failedExtractionStillAdvancesLastTurn() = runTest {
        val fs = com.aichat.app.data.store.MemFileStore()
        val store = JsonDocStore(fs, "rp-memory.json")
        store.save(bucketJson(emptyList(), lastTurn = 0))
        val p = RpMemoryPlugin(host(jobj("interval" to 5)), store) { _, _ -> throw IllegalStateException("boom") }
        val c = ctx(conv(turns = 6))
        p.onAfterResponse(TurnResult(ok = true, httpStatus = 200, usage = null, assistant = c.conv.messages.last()), c)

        var lastTurn = -1
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val b = store.load()["conv:c1"] as? JsonObject
            lastTurn = (b?.get("lastTurn") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: -1
            if (lastTurn > 0) break
            Thread.sleep(20)
        }
        // 失败也要推进（否则每轮都重试，等于每轮多烧一次钱）
        assertEquals(6, lastTurn)
        val err = ((store.load()["conv:c1"] as? JsonObject)?.get("lastError") as? kotlinx.serialization.json.JsonPrimitive)?.content
        assertTrue("应记下 lastError", err?.contains("boom") == true)
    }

    @Test fun disabledPluginDoesNotExtract() = runTest {
        val fs = com.aichat.app.data.store.MemFileStore()
        val store = JsonDocStore(fs, "rp-memory.json")
        store.save(bucketJson(emptyList(), lastTurn = 0))
        var asked = 0
        val p = RpMemoryPlugin(host(jobj("interval" to 5)), store) { _, _ -> asked++; "[]" }
        p.onAfterResponse(TurnResult(ok = false, httpStatus = 500, usage = null, assistant = Message(id = "x", role = Role.ASSISTANT, content = "")), ctx(conv(turns = 5)))
        Thread.sleep(120)
        assertEquals("失败的一轮不触发抽取", 0, asked)
    }
    /** 面板「立即提取」走的就是这条：不等回合数，直接落库 */
    @Test fun manualExtractWritesEntries() = runTest {
        val fs = com.aichat.app.data.store.MemFileStore()
        val store = JsonDocStore(fs, "rp-memory.json")
        store.save(bucketJson(emptyList(), lastTurn = 0))
        var asked = 0
        val p = RpMemoryPlugin(host(jobj("interval" to 5)), store) { _, pr ->
            asked++
            """[{"type":"fact","text":"主角叫阿浦","keywords":["阿浦"]}]"""
        }
        val r = p.extractNow(conv(turns = 3), settings)
        org.junit.Assert.assertTrue("extractNow 失败：" + r.exceptionOrNull(), r.isSuccess)
        org.junit.Assert.assertEquals("asked", 1, asked)
        org.junit.Assert.assertTrue("文件应有条目：" + store.load(), store.load().toString().contains("阿浦"))
    }}
