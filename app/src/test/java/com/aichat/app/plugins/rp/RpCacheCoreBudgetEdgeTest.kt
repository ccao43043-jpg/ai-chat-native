package com.aichat.app.plugins.rp

import com.aichat.app.plugins.js.jobj
import org.junit.Assert.assertEquals
import org.junit.Test

/** EXPERIENCE-GUARD（2026-11-21）：autoBudget 的窗口来源边界（未知模型默认窗口 / meta.context 覆盖）。 */
class RpCacheCoreBudgetEdgeTest {

    @Test fun unknownModelFallsBackToDefaultWindow() {
        val got = RpCacheCore.autoBudget(RpCacheCore.capability("openai", null, "openai", "totally-unknown-model"), "totally-unknown-model")
        assertEquals(64000, got.window)
        assertEquals(51000, got.maxContext)   // round1k(64000 * 0.8) = 51.2 千 → 四舍五入 51k
    }

    @Test fun metaContextOverridesWindow() {
        val got = RpCacheCore.autoBudget(RpCacheCore.capability("openai", null, "openai", "gpt-x"), "gpt-x", jobj("context" to 32000))
        assertEquals(32000, got.window)
        assertEquals("meta", got.source)
        assertEquals(26000, got.maxContext)   // round1k(32000 * 0.8) = 25.6 千 → 四舍五入 26k
    }
}
