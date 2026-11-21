package com.aichat.app.domain.cost

import com.aichat.app.domain.model.Conversation
import com.aichat.app.domain.model.ProviderConfig
import com.aichat.app.domain.model.ProviderType
import com.aichat.app.net.StreamDelta
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 成本守卫（W1/W2）：旁路任务档与 usage 入账管道的口径保护。 */
class BypassTaskGuardTest {

    private fun conv() = Conversation(
        id = "c", title = "t", providerId = "pid", model = "m",
        createdAt = 1, updatedAt = 2, messages = emptyList(),
    )

    private fun provider() = ProviderConfig(
        "pid", "openrouter", "OR", ProviderType.OPENAI, "https://openrouter.ai/api/v1", listOf("m"),
    )

    @Test fun perTaskProfilesMatchThePlan() {
        assertEquals(1200 to "off", BypassTask.CHAPTER_SUMMARY.let { it.maxTokens to it.thinkingLevel })
        assertEquals(800 to "off", BypassTask.MEMORY_EXTRACT.let { it.maxTokens to it.thinkingLevel })
        assertEquals(256 to "off", BypassTask.SEARCH_REWRITE.let { it.maxTokens to it.thinkingLevel })
        assertEquals(2048, BypassTask.AUTO_CHAR.maxTokens)
        assertNull("AutoChar 质量敏感：不发思考字段", BypassTask.AUTO_CHAR.thinkingLevel)
        assertEquals(45_000L, BypassTask.MEMORY_EXTRACT.timeoutMs)
    }

    @Test fun usageTypesAreKnownToTypeCn() {
        BypassTask.entries.forEach { t ->
            assertTrue("${t.usageType} 要能显示中文", UsageStatsCore.TYPE_CN.containsKey(t.usageType))
        }
    }

    @Test fun bypassUsageEntryRecordsIndependentTypeAndModel() {
        val du = StreamDelta.UsageDelta(buildJsonObject {
            put("prompt", JsonPrimitive(100))
            put("completion", JsonPrimitive(10))
            put("cacheSupported", JsonPrimitive(false))
            put("cost", JsonPrimitive(0.0004))
        })
        val e = BypassUsageEntry.of(BypassTask.MEMORY_EXTRACT, "z-ai/glm-5.3", provider(), conv(), du, at = 1000L)!!
        assertEquals("memory_extract", (e["type"] as JsonPrimitive).content)
        assertEquals("实际模型要入账，不是对话模型", "z-ai/glm-5.3", (e["model"] as JsonPrimitive).content)
        assertEquals(100, (e["prompt"] as JsonPrimitive).content.toInt())
        assertEquals(0.0004, (e["actualCost"] as JsonPrimitive).content.toDouble(), 1e-12)
    }

    @Test fun bypassUsageEntryWithoutPromptReturnsNullNotZero() {
        val du = StreamDelta.UsageDelta(buildJsonObject { put("completion", JsonPrimitive(10)) })
        assertNull("usage 缺 prompt → 不入账，不伪造 0 成本", BypassUsageEntry.of(BypassTask.CHAPTER_SUMMARY, "m", provider(), conv(), du))
    }

    @Test fun bypassUsageTypesAreDistinct() {
        assertEquals(4, BypassTask.entries.map { it.usageType }.toSet().size)
    }

    @Test fun makeEntryHonorsExplicitType() {
        val e = UsageStatsCore.makeEntry(buildJsonObject {
            put("usage", buildJsonObject { put("prompt", JsonPrimitive(10)); put("completion", JsonPrimitive(2)) })
            put("type", JsonPrimitive("chapter_summary"))
            put("msgId", JsonPrimitive("m1"))
        })
        assertEquals("record 路径必须尊重显式任务类型", "chapter_summary", (e["type"] as JsonPrimitive).content)
    }

    @Test fun usageEntryFromMessageStillTypeChat() {
        val conv = Conversation(id = "c", title = "t", providerId = "pid", model = "m", createdAt = 1, updatedAt = 2, messages = emptyList())
        val msg = com.aichat.app.domain.model.Message("b-1", com.aichat.app.domain.model.Role.ASSISTANT, "答", 1)
        val u = com.aichat.app.domain.model.Usage(prompt = 100, completion = 10)
        val e = UsageEntry.from(conv, provider(), "m", msg, u)!!
        assertEquals("主对话入账类型不得被旁路改动", "chat", (e["type"] as JsonPrimitive).content)
    }

    @Test fun recordRoutesExplicitTypeIntoLog() {
        val e = UsageStatsCore.makeEntry(buildJsonObject {
            put("usage", buildJsonObject { put("prompt", JsonPrimitive(10)); put("completion", JsonPrimitive(2)) })
            put("type", JsonPrimitive("memory_extract"))
            put("msgId", JsonPrimitive("m1"))
        })
        val s = UsageStatsCore.record(UsageStatsCore.emptyState(), e)
        val inLog = (s["log"] as kotlinx.serialization.json.JsonArray).first() as JsonObject
        assertEquals("memory_extract", (inLog["type"] as JsonPrimitive).content)
    }
}
