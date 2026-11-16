package com.aichat.app.plugins.rp

import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.jobj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §4.2 摘要时点最优停止（改进方案 §4.2 / advisory §10.2③）：
 * `summary.mode = "adaptive"` 时枚举「再等 t 轮再摘」的折现成本决定现在摘还是等；
 * 默认 `trigger` 保持旧行为（golden 兼容）。
 */
class RpCacheCoreAdaptiveSummaryTest {

    // ---------------- 纯函数：summaryTiming ----------------

    @Test
    fun heavyGrowthSummarizesNow() {
        // U 大、增长快、省得多（saved 15000）→ 等待每轮多付 saved×折现，超过 cs+重建的折现收益
        assertEquals(0, RpCacheCore.summaryTiming(20000, 2000.0, 15000, 8000, 100000))
    }

    @Test
    fun expensiveRebuildWaitsOneRound() {
        // 固定层 45000：重建太贵 → 等一轮（γ 折现让 cs/重建都变便宜），滚动时域下轮再解
        assertTrue(RpCacheCore.summaryTiming(3000, 100.0, 2250, 45000, 120000) >= 1)
    }

    @Test
    fun emptyHistoryReturnsNow() {
        assertEquals(0, RpCacheCore.summaryTiming(0, 100.0, 0, 8000, 100000))
    }

    // ---------------- plan() 集成：开关与守卫 ----------------

    private fun msg(id: String, chars: Int, role: String = "user") = jobj("id" to id, "role" to role, "content" to "港".repeat(chars))

    private fun st(mode: String?) = jobj(
        "settings" to jobj("summary" to jobj(
            "enabled" to true,
            "mode" to mode,                       // null → 默认 trigger（旧行为）
            "trigger_tokens" to 30000.0,
            "min_new_turns" to 1.0,
            "min_interval_turns" to 0.0,
            "keep_recent_turns" to 2.0,
            "chapter_cap_tokens" to 12000.0,
        )),
    )

    /** 5 条未摘要历史（约 1.98 万 token）+ 本轮输入：低于 trigger_tokens(30000)，高于 adaptive_min_tokens(4000) */
    private fun items() = listOf(
        msg("m1", 6600), msg("m2", 6600, "assistant"), msg("m3", 6600), msg("m4", 6600, "assistant"), msg("m5", 6600),
        msg("m6", 100),
    )

    @Test
    fun defaultModeKeepsTriggerBehavior() {
        val p = RpCacheCore.plan(st(null), "你是角色扮演助手。", items())
        assertFalse("默认 trigger 模式：低于硬触发不摘要（旧行为不变）", p.needSummary)
    }

    @Test
    fun adaptiveModeSummarizesEarlierWhenCostOptimal() {
        val p = RpCacheCore.plan(st("adaptive"), "你是角色扮演助手。", items())
        assertTrue("adaptive 模式成本最优时应提前摘要", p.needSummary)
        assertEquals("adaptive", p.summaryReason)
    }

    @Test
    fun adaptiveModeRespectsMinTokensFloor() {
        val small = listOf(msg("m1", 660), msg("m2", 100))
        val p = RpCacheCore.plan(st("adaptive"), "你是角色扮演助手。", small)
        assertFalse("低于 adaptive_min_tokens 不摘要", p.needSummary)
    }
}
