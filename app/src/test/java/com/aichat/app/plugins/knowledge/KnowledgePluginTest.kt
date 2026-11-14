package com.aichat.app.plugins.knowledge

import com.aichat.app.domain.ChatOptions
import com.aichat.app.domain.RequestContext
import com.aichat.app.domain.model.AppSettings
import com.aichat.app.domain.model.Conversation
import com.aichat.app.domain.model.ProviderConfig
import com.aichat.app.domain.model.ProviderType
import com.aichat.app.domain.model.Role
import com.aichat.app.domain.model.WireMessage
import com.aichat.app.plugins.PluginHost
import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.jobj
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** knowledge-plugin.js 的 onBeforeRequest 行为对齐测试（只用内存 store，不碰 Android 文件系统） */
class KnowledgePluginTest {

    private class MemStore : Knowledge.Store {
        private val m = LinkedHashMap<String, Any?>()
        override fun get(key: String): Any? = m[key]
        override fun set(key: String, value: Any?) { m[key] = value }
    }

    private class FakeHost : PluginHost {
        val configs = LinkedHashMap<String, JObj>()
        private val data = LinkedHashMap<String, JObj>()
        override fun now(): Long = 1_700_000_000_000L
        override fun config(settings: AppSettings, id: String): JObj = configs.getOrPut(id) { jobj() }
        override fun convData(conv: Conversation, id: String): JObj = data.getOrPut(conv.id + "|" + id) { jobj() }
        override fun isActive(id: String, settings: AppSettings, conv: Conversation?): Boolean = true
        override fun notify(text: String) {}
        /** 测试用：当前对话级插件数据 */
        fun conv(id: String, plugin: String): JObj = data.getOrPut(id + "|" + plugin) { jobj() }
    }

    private fun provider() = ProviderConfig(
        id = "p1", presetId = "openai", name = "P", type = ProviderType.OPENAI,
        baseUrl = "https://api.example.com", models = listOf("gpt-4o-mini"),
    )

    private fun conversation(worldId: String? = "w1") = Conversation(
        id = "c1", title = "t", providerId = "p1", model = "gpt-4o-mini",
        createdAt = 1L, updatedAt = 1L, messages = emptyList(),
        extras = if (worldId == null) JsonObject(emptyMap()) else JsonObject(mapOf("worldId" to JsonPrimitive(worldId))),
    )

    private fun ctx(content: String, conv: Conversation = conversation(), systemPrompt: String = "SYS 你是助手") =
        RequestContext(
            conv = conv,
            provider = provider(),
            model = "gpt-4o-mini",
            systemPrompt = systemPrompt,
            messages = listOf(WireMessage(role = Role.USER, content = content, srcId = "m1")),
            opts = ChatOptions(),
            settings = AppSettings(providers = emptyList()),
            lastUserId = "m1",
        )

    private fun storeWithBvilla(): MemStore {
        val store = MemStore()
        var doc = Knowledge.emptyDoc("w1")
        doc = Knowledge.addEntry(doc, "housing", jobj(
            "title" to "主角别墅",
            "keywords" to "别墅，房间，地下室",
            "content" to "别墅有三层，地下车库停着一辆黑色轿车。",
        ))
        store.set(Knowledge.storeKey("w1"), doc)
        return store
    }

    @Test
    fun globalGateOffLeavesRequestUntouched() = runBlocking {
        val host = FakeHost()
        val plugin = KnowledgePlugin(host, storeWithBvilla())
        val input = ctx("别墅在哪？[CURRENT_STATE]\n位置：客厅")
        val out = plugin.onBeforeRequest(input, {})
        assertEquals(input.messages, out.messages)
        assertTrue(out.meta.isEmpty())
    }

    @Test
    fun conversationGateUnsetSkipsAndPinsFalse() = runBlocking {
        val host = FakeHost()
        host.configs[KnowledgePlugin.ID] = jobj("injectEnabled" to true)
        val plugin = KnowledgePlugin(host, storeWithBvilla())
        val input = ctx("别墅在哪？")
        val out = plugin.onBeforeRequest(input, {})
        assertEquals(input.messages, out.messages)
        assertEquals(false, host.conv("c1", KnowledgePlugin.ID)["injectEnabled"])
    }

    @Test
    fun injectsBeforeCurrentStateWhenBothGatesOpen() = runBlocking {
        val host = FakeHost()
        host.configs[KnowledgePlugin.ID] = jobj("injectEnabled" to true)
        host.conv("c1", KnowledgePlugin.ID)["injectEnabled"] = true
        val plugin = KnowledgePlugin(host, storeWithBvilla())
        val out = plugin.onBeforeRequest(ctx("别墅在哪？\n[CURRENT_STATE]\n位置：客厅"), {})

        val c = out.messages[0].content
        assertTrue(c.contains("[LOCAL_KNOWLEDGE]"))
        assertTrue(c.contains("主角别墅"))
        assertTrue(c.indexOf("[LOCAL_KNOWLEDGE]") < c.indexOf("[CURRENT_STATE]"))

        val trace = out.meta["knowledge"] as JsonObject
        assertEquals(1, (trace["hitIds"] as JsonArray).size)
    }

    @Test
    fun noHitsStillRecordsTrace() = runBlocking {
        val host = FakeHost()
        host.configs[KnowledgePlugin.ID] = jobj("injectEnabled" to true)
        host.conv("c1", KnowledgePlugin.ID)["injectEnabled"] = true
        val plugin = KnowledgePlugin(host, storeWithBvilla())
        val input = ctx("今天天气不错")
        val out = plugin.onBeforeRequest(input, {})
        assertEquals(input.messages, out.messages)
        val trace = out.meta["knowledge"] as JsonObject
        assertEquals(0, (trace["hitIds"] as JsonArray).size)
    }

    @Test
    fun jsonStoreAdapterRoundTripsThroughKnowledgeFormat() {
        val docs = LinkedHashMap<String, JsonObject>()
        val st = JsonKnowledgeStore(read = { id -> docs[id] }, write = { id, doc -> docs[id] = doc; true })
        val doc = Knowledge.addEntry(Knowledge.emptyDoc("w9"), "housing", jobj("title" to "灯塔", "content" to "塔顶有灯"))
        st.set(Knowledge.storeKey("w9"), doc)

        assertTrue(docs.containsKey("w9"))
        val back = Knowledge.load(st, "w9")
        val cats = back["categories"] as? List<*> ?: emptyList<Any?>()
        val entries = cats.sumOf { ((it as? Map<*, *>)?.get("entries") as? List<*>)?.size ?: 0 }
        assertEquals(1, entries)
    }

    @Test
    fun worldIdFallbackIsUsedWhenConversationHasNone() = runBlocking {
        val host = FakeHost()
        host.configs[KnowledgePlugin.ID] = jobj("injectEnabled" to true)
        host.conv("c1", KnowledgePlugin.ID)["injectEnabled"] = true
        val plugin = KnowledgePlugin(host, storeWithBvilla()) { "w1" }
        val out = plugin.onBeforeRequest(ctx("别墅在哪？", conv = conversation(worldId = null)), {})
        assertTrue(out.messages[0].content.contains("[LOCAL_KNOWLEDGE]"))
        assertFalse(out.meta.isEmpty())
    }
}
