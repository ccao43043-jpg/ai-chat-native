package com.aichat.app.net

import com.aichat.app.domain.WireMessage
import com.aichat.app.domain.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C6：路由估算要算上 system。
 *
 * 线上证据：11-18 的 49 次请求里 7 次在同一对话中途换上游，其中 3 次紧接着一条 cached=0
 * —— Gemini 的隐式缓存按上游（模型部署）分片，换上游等于整段命中作废。
 * 而 `prefixEstimate` 只累加 messages 的字符数；调用点传的是「system 还没并进去」的
 * 消息列表，于是一个 3k+ token 的固定层在路由眼里等于零前缀。
 */
class PrefixEstimateTest {

    private fun msgs(vararg pairs: Pair<Role, String>) = pairs.map { WireMessage(it.first, it.second) }

    @Test
    fun systemCharsCountTowardThePrefix() {
        val ms = msgs(Role.USER to "x".repeat(1000))
        val without = OpenRouterRouting.prefixEstimate(ms)
        val with = OpenRouterRouting.prefixEstimate(ms, 3000)
        assertEquals(500.0, without, 0.001)
        assertEquals(2000.0, with, 0.001)
        assertTrue("带 system 的估算必须更大", with > without)
    }

    @Test
    fun negativeSystemCharsAreClamped() {
        val ms = msgs(Role.USER to "x".repeat(10))
        assertEquals(5.0, OpenRouterRouting.prefixEstimate(ms, -1000), 0.001)
    }

    @Test
    fun emptyConversationStillReportsTheSystemPrefix() {
        assertEquals(0.0, OpenRouterRouting.prefixEstimate(emptyList()), 0.001)
        assertEquals(1500.0, OpenRouterRouting.prefixEstimate(emptyList(), 3000), 0.001)
    }
}