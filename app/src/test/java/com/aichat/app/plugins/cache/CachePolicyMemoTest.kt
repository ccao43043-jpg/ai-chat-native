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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 缓存路线一轮只算一次。
 *
 * 以前 `pick()` 一轮被调 5 次（cachemeter、两个缓存插件的 autoActive 与 inactiveReason、
 * rpcache 的 autoActive 与打点），而 gcache 的 `onBeforeRequest` 里还自己又判了一次
 * isOpenRouterGemini。三处判断一旦不一致，表现是「开关开着、策略也说走 gcache，
 * 插件却静默空转」—— 界面上完全看不出来，只能靠读代码猜。
 */
class CachePolicyMemoTest {
    private fun provider() = ProviderConfig(
        id = "p1", presetId = "custom", name = "本地", type = ProviderType.OPENAI,
        baseUrl = "https://openrouter.ai/api/v1", models = listOf("google/gemini-2.5-pro"),
    )

    private fun conv(id: String = "c1") = Conversation(
        id = id, title = "t", providerId = "p1", model = "google/gemini-2.5-pro",
        createdAt = 1L, updatedAt = 1L,
        messages = listOf(Message(id = "u1", role = Role.USER, content = "你好", createdAt = 1L)),
    )

    private fun ctx(conv: Conversation): RequestContext = RequestContext(
        conv = conv,
        provider = provider(),
        model = "google/gemini-2.5-pro",
        systemPrompt = "你是助手。",
        messages = conv.messages.map { WireMessage(it.role, it.content, srcId = it.id) },
        opts = ChatOptions(),
        settings = AppSettings(providers = listOf(provider())),
        lastUserId = "u1",
    )

    @Test
    fun beginTurnThenPlanOfReturnsTheSamePlan() {
        val c = conv()
        val plan = CachePolicy.beginTurn(ctx(c))
        try {
            assertEquals(CachePolicy.OWNER_GCACHE, plan.owner)
            assertEquals(plan, CachePolicy.planOf(c, AppSettings(providers = listOf(provider()))))
            assertNotNull(CachePolicy.current("c1"))
        } finally {
            CachePolicy.clearTurn("c1")
        }
    }

    @Test
    fun clearTurnFallsBackToTheLiveCalculation() {
        val c = conv()
        CachePolicy.beginTurn(ctx(c))
        CachePolicy.clearTurn("c1")
        assertNull(CachePolicy.current("c1"))
        val live = CachePolicy.planOf(c, AppSettings(providers = listOf(provider())))
        assertEquals(CachePolicy.OWNER_GCACHE, live.owner)
    }

    @Test
    fun memoIsScopedToOneConversation() {
        val c = conv("c1")
        CachePolicy.beginTurn(ctx(c))
        try {
            assertNull(CachePolicy.current("c2"))
        } finally {
            CachePolicy.clearTurn("c1")
        }
    }
}
