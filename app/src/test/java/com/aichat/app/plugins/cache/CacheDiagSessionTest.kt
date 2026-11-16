package com.aichat.app.plugins.cache

import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.js.jobj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** advisory §10.3 小项：session_id 入口收口（值不变）+ 缓存诊断骤降告警。 */
class CacheDiagSessionTest {

    private fun entry(prompt: Double, hit: Double) = jobj(
        "t" to 1.0, "prompt" to prompt, "hit" to hit, "known" to true, "session_id" to "s", "tokens" to 4096.0,
    )

    // ---------------- session_id：入口收口，生成值不变 ----------------

    @Test
    fun sessionIdsKeepValueShapes() {
        assertTrue("gcache 保留 aichat- 前缀", SessionIds.gcache("c1").startsWith("aichat-"))
        assertEquals(GCache.sessionIdFor("c1"), SessionIds.gcache("c1"))
        assertEquals(CacheCommonImpl.sessionIdFor("c1", "mmcache"), SessionIds.common("c1", "mmcache"))
        assertEquals("", SessionIds.gcache(null))
        assertEquals("", SessionIds.gcache(""))
    }

    // ---------------- 骤降告警 ----------------

    @Test
    fun suddenDropWarns() {
        // rates: 80, 80, 39, 19, 9 —— 连续 3 轮严格低于前值一半（"不足一半"为严格小于，恰好一半不算）
        val h = listOf(entry(1000.0, 800.0), entry(1000.0, 800.0), entry(1000.0, 390.0), entry(1000.0, 190.0), entry(1000.0, 90.0))
        val w = GCache.dropWarning(h)
        assertTrue("应告警：$w", w != null && w.contains("骤降"))
        // good 档之外，issues 也要带上（diagnose 顶层检查）
        val d = GCache.diagnose(jobj("roll" to listOf(jobj("prompt" to 5000.0, "hit" to 1000.0, "known" to true)), "hist" to h))
        assertTrue((d["issues"] as List<*>).any { Js.str(it).contains("骤降") })
    }

    @Test
    fun stableOrShortSeriesDoNotWarn() {
        // 稳定低值（无腰斩链）
        assertNull(GCache.dropWarning(listOf(entry(1000.0, 300.0), entry(1000.0, 310.0), entry(1000.0, 290.0), entry(1000.0, 300.0))))
        // 样本不足
        assertNull(GCache.dropWarning(listOf(entry(1000.0, 800.0), entry(1000.0, 400.0), entry(1000.0, 200.0))))
        // 前值太低（<20%）不触发，避免小样本噪声
        assertNull(GCache.dropWarning(listOf(entry(1000.0, 50.0), entry(1000.0, 24.0), entry(1000.0, 11.0), entry(1000.0, 5.0))))
        // 空序列
        assertNull(GCache.dropWarning(emptyList()))
    }
}
