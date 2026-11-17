package com.aichat.app.plugins.rp

import com.aichat.app.domain.model.AppSettings
import com.aichat.app.domain.model.Conversation
import com.aichat.app.domain.model.EmptyJson
import com.aichat.app.domain.model.ProviderConfig
import com.aichat.app.domain.model.ProviderType
import com.aichat.app.plugins.PluginHost
import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.JsonObject

/**
 * rpcache 的启用条件。
 *
 * 实测事故：千问显式缓存的断点由 rpcache 打，但 rpcache 只认「有没有 RP 块 / 世界书」——
 * 纯聊天场景 CachePolicy 判了 owner=rpcache，却没有插件执行，请求里一个 cache_control 都没有。
 */
class RpCacheAutoActiveTest {

    private val host = object : PluginHost {
        override fun now(): Long = 1_700_000_000_000L
        override fun convData(conv: Conversation, id: String): JObj = JObj()
        override fun config(settings: AppSettings, id: String): JObj = JObj()
        override fun isActive(id: String, settings: AppSettings, conv: Conversation?): Boolean = true
        override fun notify(text: String) {}
    }

    private val qwen = ProviderConfig(
        id = "p-qwen", presetId = "qwen", name = "千问",
        type = ProviderType.OPENAI, baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        models = listOf("qwen-plus"),
    )
    private val custom = ProviderConfig(
        id = "p-custom", presetId = "custom", name = "自定义",
        type = ProviderType.OPENAI, baseUrl = "http://10.0.2.2:8787/v1",
        models = listOf("mock-1"),
    )

    private fun conv(providerId: String, model: String, rpState: JsonObject = EmptyJson) = Conversation(
        id = "c1", title = "测试", providerId = providerId, model = model,
        createdAt = 1L, updatedAt = 1L, messages = emptyList(), rpState = rpState,
    )

    @Test
    fun qwenExplicitChatWithoutRpBlocksStillRuns() {
        val plugin = RpCachePlugin(host)
        val settings = AppSettings(providers = listOf(qwen))
        assertTrue(plugin.autoActive(conv("p-qwen", "qwen-plus"), settings))
    }

    @Test
    fun plainOpenAiCompatibleChatStaysOff() {
        val plugin = RpCachePlugin(host)
        val settings = AppSettings(providers = listOf(custom))
        assertFalse(plugin.autoActive(conv("p-custom", "mock-1"), settings))
    }

    @Test
    fun rpBlocksStillTurnItOn() {
        val plugin = RpCachePlugin(host)
        val settings = AppSettings(providers = listOf(custom))
        val st = RpState.setBlock(RpState.newState(), "world", "A cold harbor town.", 1L)["state"] as JObj
        assertTrue(plugin.autoActive(conv("p-custom", "mock-1", Js.toJson(st) as JsonObject), settings))
    }
}