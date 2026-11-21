package com.aichat.app.plugins.rp

import com.aichat.app.plugins.js.jobj
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 成本守卫（W5/C5）：`rpcache.budgetProfile` 接线 —— legacy 默认逐字节、auto 走能力表。 */
class RpCacheCoreBudgetWiringTest {

    private fun st() = RpState.normalizeState(jobj())
    private fun geminiCap() = RpCacheCore.capability("openrouter", null, "openai", "google/gemini-2.5-pro")

    @Test fun legacyDefaultKeepsHandFilledLimit() {
        assertEquals(86000, RpCacheCore.plan(st(), "", emptyList()).limit)   // 100000-12000-2000
    }

    @Test fun budgetAutoUsesCapabilityTableForGemini() {
        val p = RpCacheCore.plan(st(), "", emptyList(), budgetAuto = true, cap = geminiCap(), model = "google/gemini-2.5-pro")
        assertEquals("1M 窗口不再被 200k 封顶：400k*0.8=320000；出参钳 16k、余量钳 4k", 300000, p.limit)
    }

    @Test fun budgetAutoDeepseekFallsToDefaultConstants() {
        val p = RpCacheCore.plan(st(), "", emptyList(), budgetAuto = true,
            cap = RpCacheCore.capability("deepseek", null, "openai", "deepseek-chat"), model = "deepseek-chat")
        assertEquals(86000, p.limit)   // 特判走默认常量：100000-12000-2000
    }

    @Test fun autoBudgetGeminiUsesBiggerCap() {
        val b = RpCacheCore.autoBudget(geminiCap(), "google/gemini-2.5-pro")
        assertEquals(320000, b.maxContext)      // 400k × 0.8
        assertEquals(16000, b.outputReserve)    // 钳上限 16k
        assertEquals(112000, b.triggerTokens)   // 320000 × 0.35
        assertEquals(38000, b.chapterCap)       // 320000 × 0.12 = 38400 → round1k 归千 38k，钳 [4k,48k]
    }

    @Test fun budgetAutoReadsPluginsSwitch() {
        fun plugins(v: String?) = buildJsonObject {
            put("rpcache", buildJsonObject {
                if (v != null) put("budgetProfile", JsonPrimitive(v))
            })
        }
        assertFalse(RpCacheCore.budgetAuto(plugins(null)))
        assertFalse(RpCacheCore.budgetAuto(plugins("legacy")))
        assertTrue(RpCacheCore.budgetAuto(plugins("auto")))
    }
}
