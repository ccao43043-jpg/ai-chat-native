package com.aichat.app.plugins.rp

import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C2：临时裁剪窗口要钉住，摘要失败要退避。
 *
 * - 窗口每轮重算 ⇒ 起点随预算漂移 ⇒ 前缀每轮变一次，缓存整段作废；
 *   钉住（`st.overflow.tempStartId`）的代价只是多送几条上一轮刚好装得下的旧消息。
 * - 摘要失败不推进 `lastSummaryRound` ⇒ 不记标记就每轮重试一次摘要调用，白烧钱。
 */
class RpCacheTrimBackoffTest {

    private val chunk = "历史里的一段很长的正文，用来把预算顶满。".repeat(80)

    private fun msgs(rounds: Int): List<JObj> {
        val out = ArrayList<JObj>()
        for (i in 0 until rounds) {
            out.add(jobj("id" to "u$i", "role" to "user", "content" to chunk))
            out.add(jobj("id" to "a$i", "role" to "assistant", "content" to chunk))
        }
        out.add(jobj("id" to "u$rounds", "role" to "user", "content" to "本轮输入"))
        return out
    }

    private fun state(maxContext: Int): JObj {
        val s = RpState.newState()
        val st = (s["settings"] as? JObj) ?: JObj()
        st["max_context"] = maxContext
        st["output_reserve"] = 0
        st["safety_margin"] = 0
        ((st["summary"] as? JObj) ?: JObj()).also { it["enabled"] = false; it["keep_recent_turns"] = 2 }
        return s
    }

    private fun overflowStart(st: JObj?): String =
        Js.str((st["overflow"] as? JObj)?.get("tempStartId"))

    @Test
    fun tempTrimWindowIsStickyAcrossRounds() {
        val items = msgs(6)
        val s1 = state(3000)
        val p1 = RpCacheCore.plan(s1, "", items, provider = "p1")
        assertTrue("预算必须被顶爆，否则测的不是这条路径", p1.overBudget)

        val (s2, _) = RpCacheCore.applyTempTrim(s1, p1, 0L, "p1")
        val pinned = overflowStart(s2)
        assertTrue("裁剪后要留下 tempStartId", pinned.isNotEmpty())

        // 预算放宽（相当于对话上下文变大）：不钉住的话，新算出来的起点会跳到「最近 keep 轮」
        val s3 = JObj(s2.toMutableMap())
        ((s3["settings"] as? JObj) ?: JObj())["max_context"] = 60000
        val p3 = RpCacheCore.plan(s3, "", items, provider = "p1")
        val win = RpCacheCore.computeTrimWindow(s3, p3, "p1")
        assertEquals("起点必须沿用上一轮定下的那条消息", pinned, win?.startId)
    }

    @Test
    fun withoutPinnedWindowTheTrimFollowsTheKeepWindow() {
        val items = msgs(6)
        val s = state(3000)
        val p = RpCacheCore.plan(s, "", items, provider = "p1")
        val win = RpCacheCore.computeTrimWindow(s, p, "p1")
        assertTrue(win != null)
        // keep_recent_turns=2 → 起点是「倒数第 2 个 user」= u5
        assertEquals("u5", win!!.startId)
    }

    @Test
    fun summaryFailureBacksOff() {
        var st = RpState.newState()
        val rounds = 40
        assertTrue("没有标记时照常允许", RpCacheCore.summaryRetryAllowed(st, rounds))

        st = RpCacheCore.noteSummaryFail(st, 1L, rounds)
        assertEquals("第一次失败退 2 轮", rounds + 2, (st["summaryRetry"] as JObj)["nextRound"].d().toInt())
        assertFalse(RpCacheCore.summaryRetryAllowed(st, rounds + 1))
        assertTrue(RpCacheCore.summaryRetryAllowed(st, rounds + 2))

        // 连续失败：2 轮 → 4 轮 → 8 轮封顶
        st = RpCacheCore.noteSummaryFail(st, 2L, rounds)
        assertEquals(rounds + 4, (st["summaryRetry"] as JObj)["nextRound"].d().toInt())
        st = RpCacheCore.noteSummaryFail(st, 3L, rounds)
        assertEquals(rounds + 8, (st["summaryRetry"] as JObj)["nextRound"].d().toInt())
        st = RpCacheCore.noteSummaryFail(st, 4L, rounds)
        assertEquals("封顶 8 轮", rounds + 8, (st["summaryRetry"] as JObj)["nextRound"].d().toInt())

        // 成功后清零；本来没有标记就原样返回（不产生新的状态写入）
        val cleared = RpCacheCore.clearSummaryFail(st)
        assertTrue(cleared["summaryRetry"] == null)
        assertTrue(RpCacheCore.summaryRetryAllowed(cleared, rounds))
        val untouched = RpState.newState()
        assertTrue("无标记时必须是同一个对象", untouched === RpCacheCore.clearSummaryFail(untouched))
    }

    @Test
    fun backoffSuppressesSummaryThisRound() {
        val items = msgs(4)
        val s = state(100000)
        val st = (s["settings"] as? JObj) ?: JObj()
        ((st["summary"] as? JObj) ?: JObj()).also {
            it["enabled"] = true; it["trigger_tokens"] = 10; it["min_new_turns"] = 1; it["min_interval_turns"] = 0
        }
        val p1 = RpCacheCore.plan(JObj(s.toMutableMap()), "", items, provider = "p1")
        assertTrue("没退避标记时该要摘要", p1.needSummary)

        val s2 = RpCacheCore.noteSummaryFail(JObj(s.toMutableMap()), 0L, RpCacheCore.roundsOf(items))
        val p2 = RpCacheCore.plan(s2, "", items, provider = "p1")
        assertFalse("退避期内不要摘要（避免每轮重试烧一次调用）", p2.needSummary)
    }
}