package com.aichat.app.plugins.cache

import com.aichat.app.domain.model.AppSettings
import com.aichat.app.domain.model.Conversation
import com.aichat.app.domain.model.Message
import com.aichat.app.domain.model.Role
import com.aichat.app.plugins.PluginHost
import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.js.jobj
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顶栏徽章要说清「为什么没打点」。
 *
 * 实测：千问显式缓存的路线判对了，但 system 只有 97 token（门槛 1024），
 * 请求里一个 `cache_control` 也没有 —— 徽章如果只说「缓存 67%」，用户完全无法判断是没生效还是设定太短。
 */
class CacheBadgeHintTest {

    private val host = object : PluginHost {
        override fun now(): Long = 1L
        override fun convData(conv: Conversation, id: String): JObj = jobj()
        override fun config(settings: AppSettings, id: String): JObj = jobj()
        override fun isActive(id: String, settings: AppSettings, conv: Conversation?): Boolean = true
        override fun notify(text: String) {}
    }

    private fun convWith(cache: JsonObject) = Conversation(
        id = "c1", title = "t", providerId = "p1", model = "qwen-plus",
        createdAt = 1L, updatedAt = 1L,
        messages = listOf(Message(id = "u1", role = Role.USER, content = "hi", createdAt = 1L)),
        extras = buildJsonObject {
            put("plugins", buildJsonObject { put("cachemeter", cache) })
        },
    )

    private fun cacheData(markCount: Int) = buildJsonObject {
        put("summary", buildJsonObject {
            put("known", kotlinx.serialization.json.JsonPrimitive(3))
            put("roll", kotlinx.serialization.json.JsonPrimitive(0.667))
            put("hitTokens", kotlinx.serialization.json.JsonPrimitive(2400))
            put("promptTokens", kotlinx.serialization.json.JsonPrimitive(3600))
        })
        put("plan", buildJsonObject {
            put("owner", kotlinx.serialization.json.JsonPrimitive(CachePolicy.OWNER_RPCACHE))
            put("mode", kotlinx.serialization.json.JsonPrimitive(CachePolicy.MODE_BREAKPOINTS))
            put("ttlSec", kotlinx.serialization.json.JsonPrimitive(300))
            put("minTokens", kotlinx.serialization.json.JsonPrimitive(1024))
            put("reason", kotlinx.serialization.json.JsonPrimitive("千问显式缓存（RP 缓存优化器打点）"))
        })
        put("marks", buildJsonObject {
            put("applied", kotlinx.serialization.json.JsonPrimitive(true))
            put("count", kotlinx.serialization.json.JsonPrimitive(markCount))
        })
        put("marksSys", kotlinx.serialization.json.JsonPrimitive(97))
    }

    @Test
    fun badgeExplainsWhyNoMarkerWasWritten() {
        val badge = CacheMeterPlugin(host).headerBadges(convWith(cacheData(0))).single()
        assertTrue(badge.text.contains("67"))
        assertTrue("没打点必须写清原因与门槛", badge.detail!!.contains("断点未打出"))
        assertTrue(badge.detail!!.contains("97"))
        assertTrue(badge.detail!!.contains("1024"))
    }

    @Test
    fun badgeReportsMarkerCountWhenApplied() {
        val badge = CacheMeterPlugin(host).headerBadges(convWith(cacheData(2))).single()
        assertTrue(badge.detail!!.contains("断点 2 处"))
        assertFalse(badge.detail!!.contains("断点未打出"))
    }
}