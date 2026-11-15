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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 世界书端到端：存了条目 → 发一条含触发词的消息 → 请求里必须出现 `[LORE_TRIGGERED …]`。
 *
 * 这条链路在 1.0.1 上是不通的（能存能查，但从不注入），所以单独锁一份端到端断言，
 * 避免以后又在「纯函数都对、接线断了」的地方翻车。
 */
class RpLoreTriggerTest {

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

    /** 世界块有内容（让 rp 缓存插件 autoActive 成立）+ 写一条关键词触发的世界书。 */
    private fun convWithLore(): Conversation {
        val st = stateWithWorld("海边渔村，夜里涨潮。", "## Harbor | boats, harbor | priority=7\n船必须在涨潮前回港。")
        return Conversation(
            id = "c1", title = "世界书", providerId = "p1", model = "deepseek-chat",
            createdAt = 1L, updatedAt = 1L,
            messages = listOf(
                Message(id = "u1", role = Role.USER, content = "hi"),
                Message(id = "a1", role = Role.ASSISTANT, content = "嗯。"),
                Message(id = "u2", role = Role.USER, content = "where is the harbor"),
            ),
            systemPrompt = "你是助手。",
            rpState = Js.toJson(st) as JsonObject,
        )
    }

    /** `RpState.setBlock` 返回的是 `{state, changed}` 包装，必须取 `state`。 */
    private fun stateWithWorld(world: String, loreText: String): com.aichat.app.plugins.js.JObj {
        val st0 = RpState.normalizeState(EmptyJson)
        val r = RpState.setBlock(st0, "world", world, 1L)
        val st1 = r["state"] as com.aichat.app.plugins.js.JObj
        return RpLoreCore.setLore(st1, loreText, 2L)
    }

    private fun ctx(c: Conversation) = RequestContext(
        conv = c, provider = provider, model = "deepseek-chat",
        systemPrompt = c.systemPrompt ?: "",
        messages = c.messages.map { WireMessage(it.role, it.content, srcId = it.id) },
        opts = ChatOptions(), settings = settings, lastUserId = "u2",
    )

    @Test
    fun keywordHitInjectsLoreBlockIntoLastUserMessage() = runTest {
        val plugin = RpCachePlugin(host)
        val c = convWithLore()
        val out = plugin.onBeforeRequest(ctx(c)) {}
        val last = out.messages.last()
        assertTrue(
            "最后一条 user 里应带冻结的世界书片段，实际：${last.content}",
            last.content.contains("[LORE_TRIGGERED"),
        )
        assertTrue("应带上条目名", last.content.contains("Harbor"))
        assertTrue("应带上正文", last.content.contains("涨潮前回港"))
    }

    @Test
    fun noKeywordNoInjection() = runTest {
        val plugin = RpCachePlugin(host)
        val c = convWithLore().let { conv ->
            conv.copy(messages = conv.messages.dropLast(1) + Message(id = "u2", role = Role.USER, content = "今晚吃什么"))
        }
        val out = plugin.onBeforeRequest(ctx(c)) {}
        assertTrue(
            "没命中触发词就不该注入，实际：${out.messages.last().content}",
            !out.messages.last().content.contains("[LORE_TRIGGERED"),
        )
    }

    @Test
    fun loreOnlyConversationStillFires() = runTest {
        // 只写世界书、一个 RP 块都不填——旧版这种对话静默不生效，用户以为写了没用。
        val st = RpLoreCore.setLore(
            RpState.normalizeState(EmptyJson),
            "## Harbor | boats, harbor\nBoats must return before high tide.",
            2L,
        )
        val c = Conversation(
            id = "c3", title = "只有世界书", providerId = "p1", model = "deepseek-chat",
            createdAt = 1L, updatedAt = 1L,
            messages = listOf(Message(id = "u1", role = Role.USER, content = "where is the harbor")),
            systemPrompt = "你是助手。",
            rpState = Js.toJson(st) as JsonObject,
        )
        val plugin = RpCachePlugin(host)
        assertTrue("只写世界书也应算启用", plugin.autoActive(c, settings))
        val out = plugin.onBeforeRequest(ctx(c)) {}
        assertTrue(
            "只写世界书也要能注入，实际：${out.messages.last().content}",
            out.messages.last().content.contains("[LORE_TRIGGERED"),
        )
    }

    @Test
    fun emptyStateStaysUntouched() = runTest {
        val c = Conversation(
            id = "c4", title = "空", providerId = "p1", model = "deepseek-chat",
            createdAt = 1L, updatedAt = 1L,
            messages = listOf(Message(id = "u1", role = Role.USER, content = "hi")),
            systemPrompt = "你是助手。",
            rpState = EmptyJson,
        )
        val plugin = RpCachePlugin(host)
        assertTrue("什么都不填就不该启用", !plugin.autoActive(c, settings))
        val before = ctx(c)
        assertTrue("不该改写请求", plugin.onBeforeRequest(before) {} === before)
    }

    @Test
    fun constantEntryGoesIntoSystem() = runTest {
        // 触发词写 * → 常驻，进 system 的 [LOREBOOK] 段
        val st = stateWithWorld("北境。", "## 地理 | *\n终年积雪，没有夏天。")
        val c = Conversation(
            id = "c2", title = "常驻", providerId = "p1", model = "deepseek-chat",
            createdAt = 1L, updatedAt = 1L,
            messages = listOf(Message(id = "u1", role = Role.USER, content = "hi")),
            systemPrompt = "你是助手。",
            rpState = Js.toJson(st) as JsonObject,
        )
        val out = RpCachePlugin(host).onBeforeRequest(ctx(c)) {}
        assertTrue(
            "常驻条目应进 system 的 [LOREBOOK] 段，实际 system：${out.systemPrompt.takeLast(200)}",
            out.systemPrompt.contains("[LOREBOOK]") && out.systemPrompt.contains("终年积雪"),
        )
    }
}
