package com.aichat.app.plugins.cache

import com.aichat.app.domain.ChatOptions
import com.aichat.app.domain.RequestContext
import com.aichat.app.domain.WireMessage
import com.aichat.app.domain.model.AppSettings
import com.aichat.app.domain.model.Conversation
import com.aichat.app.domain.model.Message
import com.aichat.app.domain.model.ProviderConfig
import com.aichat.app.domain.model.ProviderType
import com.aichat.app.domain.model.Role
import com.aichat.app.plugins.PluginHost
import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.jobj
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓存路线只留一处判定。
 *
 * gcache 的 `onBeforeRequest` 以前第一行是 `if (!GCache.isOpenRouterGemini(...)) return ctx`：
 * 策略层（CachePolicy）说「走 gcache」，插件自己又判一遍「不是我的活」，两处不一致时
 * 表现就是**开关开着、策略也对，但插件静默空转**，界面上看不出来。
 * 现在这条判定只剩 CachePolicy 一处（memo），插件不再自查。
 */
class GcacheSingleGateTest {
    private class Host : PluginHost {
        val data = HashMap<String, JObj>()
        override fun notify(text: String) {}
        override fun now(): Long = 1_700_000_000_000L
        override fun convData(conv: Conversation, id: String): JObj = data.getOrPut("${conv.id}|$id") { jobj() }
        override fun config(settings: AppSettings, id: String): JObj = jobj()
        override fun isActive(id: String, settings: AppSettings, conv: Conversation?): Boolean = false
    }

    private fun provider(preset: String, base: String, model: String) = ProviderConfig(
        id = "p1", presetId = preset, name = preset, type = ProviderType.OPENAI,
        baseUrl = base, models = listOf(model),
    )

    private fun conv(model: String) = Conversation(
        id = "c1", title = "t", providerId = "p1", model = model,
        createdAt = 1L, updatedAt = 1L,
        messages = listOf(Message(id = "u1", role = Role.USER, content = "你好", createdAt = 1L)),
    )

    private fun ctx(p: ProviderConfig, c: Conversation, s: AppSettings) = RequestContext(
        conv = c,
        provider = p,
        model = c.model,
        systemPrompt = "你是助手。",
        messages = c.messages.map { WireMessage(it.role, it.content, srcId = it.id) },
        opts = ChatOptions(),
        settings = s,
        lastUserId = "u1",
    )

    @Test
    fun autoActiveStaysOffForPrefixRoute() {
        val p = provider("deepseek", "https://api.deepseek.com/v1", "deepseek-chat")
        val s = AppSettings(providers = listOf(p))
        val c = conv("deepseek-chat")
        assertFalse(GCachePluginImpl(Host()).autoActive(c, s))
    }

    @Test
    fun autoActiveTurnsOnForOpenRouterGemini() {
        val p = provider("openrouter", "https://openrouter.ai/api/v1", "google/gemini-2.5-pro")
        val s = AppSettings(providers = listOf(p))
        val c = conv("google/gemini-2.5-pro")
        assertTrue(GCachePluginImpl(Host()).autoActive(c, s))
    }

    @Test
    fun onBeforeRequestNoLongerSilentlyReturnsEarly() = runBlocking {
        val p = provider("deepseek", "https://api.deepseek.com/v1", "deepseek-chat")
        val s = AppSettings(providers = listOf(p))
        val out = GCachePluginImpl(Host()).onBeforeRequest(ctx(p, conv("deepseek-chat"), s), {})
        // 以前这里是原样返回（meta 里什么都没有）；现在无论走哪条路线都会把配置交给 transformRequest，
        // 真正的协议判断留在 GCache.resolveCacheAdapter 那一层
        assertTrue(out.meta.containsKey("orGemini"))
    }
}
