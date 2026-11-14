package com.aichat.app.plugins.affect

import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.js.jobj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 好感度面板的统计口径（与 `www/affect.js` 逐行对齐）。
 *
 * 这些函数只吃 JS 形状的 Map（`messages[].affect`），所以测试直接造 map，不碰 Conversation。
 */
class AffectStatsTest {

    private fun msg(role: String, affect: Any? = null, inj: Any? = null) = jobj(
        "id" to "m-${role}-${affect.hashCode()}-${inj.hashCode()}",
        "role" to role,
        "content" to "",
        "affect" to affect,
        "affectInj" to inj,
    )

    private fun conv(vararg ms: Any?) = jobj("id" to "c1", "messages" to ms.toList())

    @Test
    fun snapshotsOnlyCountAssistantWithAffect() {
        val c = conv(
            msg("user"),
            msg("assistant", jobj("aff" to 0.5, "mood" to "开心")),
            msg("assistant"),
            msg("assistant", jobj("aff" to 0.4, "mood" to "平静")),
        )
        val snaps = Affect.affectSnapshots(c, 10)
        assertEquals(2, snaps.size)
        assertEquals("开心", Js.str(snaps[0]["mood"]))
        // n 只留最后 n 条
        assertEquals(1, Affect.affectSnapshots(c, 1).size)
    }

    @Test
    fun missingStatsCountsStateMissingFlagAndProbe() {
        val c = conv(
            msg("assistant", jobj("aff" to 0.4)),
            msg("assistant", jobj("aff" to 0.4, "stateMissing" to true)),
            msg("assistant", jobj("aff" to 0.4, "p" to "missing_state")),
        )
        val st = Affect.missingStats(c, 10)
        assertEquals(3, (st["total"] as Number).toInt())
        assertEquals(2, (st["missing"] as Number).toInt())
        assertTrue((st["rate"] as Number).toDouble() > 0.66)
        // 3 条里 2 条漏标 → 达到 MISS_THRESHOLD(3)? 不，2 < 3
        assertFalse(Affect.shouldDemoState(c, 10))
    }

    @Test
    fun shouldDemoStateAtThreshold() {
        val c = conv(
            msg("assistant", jobj("aff" to 0.4, "stateMissing" to true)),
            msg("assistant", jobj("aff" to 0.4, "stateMissing" to true)),
            msg("assistant", jobj("aff" to 0.4, "stateMissing" to true)),
        )
        assertTrue(Affect.shouldDemoState(c, 10))
    }

    @Test
    fun guardStatsSkipsWhyHintsAndSplitsSoftHard() {
        val c = conv(
            msg("assistant", jobj("aff" to 0.4, "g" to jobj("guard" to listOf("abuse_soft", "why: 用户说的是反话", "force_hard")))),
            msg("assistant", jobj("aff" to 0.4, "g" to jobj("guard" to listOf("abuse_soft", "other_soft")))),
        )
        val g = Affect.guardStats(c)
        assertEquals(1, (g["hardCount"] as Number).toInt())
        assertEquals(3, (g["softCount"] as Number).toInt())
        val all = g["all"] as Map<*, *>
        assertFalse(all.containsKey("why: 用户说的是反话"))
        assertEquals(2, (all["abuse_soft"] as Number).toInt())
    }

    @Test
    fun seriesOnlyUsesAssistantAff() {
        val c = conv(
            msg("assistant", jobj("aff" to 0.2)),
            msg("user"),
            msg("assistant", jobj("aff" to 0.35)),
            msg("assistant", jobj("aff" to 0.55)),
        )
        val s = Affect.series(c, 30)
        assertEquals(listOf(0.2, 0.35, 0.55), s)
        assertEquals(listOf(0.35, 0.55), Affect.series(c, 2))
    }

    @Test
    fun invalidateFromClearsAffectAndVersions() {
        val boom = jobj("aff" to 0.5, "mood" to "开心")
        val v0 = jobj("content" to "v0", "affect" to boom)
        val m1 = jobj("id" to "a", "role" to "assistant", "content" to "x", "affect" to boom, "versions" to listOf(v0))
        val m2 = jobj("id" to "b", "role" to "user", "content" to "y", "affectInj" to "[AFFECT] mood=平静 relation=戒备")
        val m3 = jobj("id" to "c", "role" to "assistant", "content" to "z")
        val c = jobj("id" to "c1", "messages" to mutableListOf(m1, m2, m3))

        val n = Affect.invalidateFrom(c, 1)
        assertEquals(1, n) // 只有 m2 带 affectInj；m1 在 idx 之前不动
        assertTrue((m1 as Map<*, *>).containsKey("affect"))
        assertFalse((m2 as Map<*, *>).containsKey("affectInj"))
        // 再从 0 开始：m1 的 affect 与 versions 里的都被清掉
        val n2 = Affect.invalidateFrom(c, 0)
        assertEquals(1, n2)
        assertFalse((m1 as Map<*, *>).containsKey("affect"))
        val v = ((m1["versions"] as List<*>)[0]) as Map<*, *>
        assertFalse(v.containsKey("affect"))
    }
}
