package com.aichat.app.plugins.rp

import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.jobj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L1 事实账本进 system 静态段（[RpCacheCore.factsText] / `staticText(…, facts)`）。
 *
 * 之所以放在静态段末尾而不是每轮换的位置：账本的更新频率 ≈ 摘要频率（每 20 轮一次抽取），
 * 它一变，后面所有内容本来就要重算前缀；但每轮都变就是灾难。
 */
class RpCacheFactsTest {

    private fun state(world: String = "", memory: String = ""): JObj {
        // RpState.setBlock 返回 {state, changed} 包装，必须续传 state
        var st = RpState.newState()
        if (world.isNotEmpty()) st = RpState.setBlock(st, "world", world, 1L)["state"] as JObj
        if (memory.isNotEmpty()) st = RpState.setBlock(st, "memory", memory, 1L)["state"] as JObj
        return st
    }

    @Test
    fun withoutFactsTheSystemIsUnchanged() {
        val st = state(world = "雪港")
        val a = RpCacheCore.staticText(st, "你是助手。")
        val b = RpCacheCore.staticText(st, "你是助手。", null)
        assertEquals(a, b)
        assertFalse(a.contains("[ESTABLISHED_FACTS]"))
    }

    @Test
    fun factsAreAppendedAfterBlocksAndLore() {
        val st = state(world = "雪港", memory = "渔民口音")
        val withFacts = RpCacheCore.staticText(st, "你是助手。", "[MEMORY_LEDGER]\n- [老周/身份] 灯塔看守")
        assertTrue(withFacts.contains("[ESTABLISHED_FACTS]"))
        assertTrue(withFacts.contains("- [老周/身份] 灯塔看守"))
        // 顺序：静态块 → 长期记忆块 → 事实账本（越靠后越易变）
        assertTrue(withFacts.indexOf("[LONG_TERM_MEMORY]") < withFacts.indexOf("[ESTABLISHED_FACTS]"))
        // 账本自带的 [MEMORY_LEDGER] 标题被剥掉，只留一个 ESTABLISHED_FACTS 标题
        assertEquals(0, Regex("MEMORY_LEDGER").findAll(withFacts).count())
        assertEquals(1, Regex("ESTABLISHED_FACTS").findAll(withFacts).count())
    }

    @Test
    fun blankFactsProduceNothing() {
        assertEquals("", RpCacheCore.factsText(null))
        assertEquals("", RpCacheCore.factsText("   "))
        assertEquals("", RpCacheCore.factsText("[MEMORY_LEDGER]"))
    }

    @Test
    fun segmentsExposeFactsAsTheirOwnLayer() {
        val st = state(world = "雪港")
        val segs = RpCacheCore.buildSystemSegments(st, "你是助手。", "- [老周/身份] 灯塔看守")
        val f = segs.firstOrNull { it.title == "FACTS" }
        assertTrue(f != null)
        assertEquals("L1", f!!.layer)
        assertEquals("static", f.kind)
        // 分段拼起来必须逐字节等于 buildSystem（否则「分层」和「实际发出去的 system」会漂）
        val joined = segs.joinToString("\n\n") { it.text }
        assertEquals(RpCacheCore.buildSystem(st, "你是助手。", "- [老周/身份] 灯塔看守"), joined)
    }

    @Test
    fun planCountsFactsIntoTheStaticLayer() {
        val st = state(world = "雪港")
        val items = listOf<JObj>(jobj("id" to "u1", "role" to "user", "content" to "在吗"))
        val noFacts = RpCacheCore.plan(st, "你是助手。", items)
        val withFacts = RpCacheCore.plan(st, "你是助手。", items, facts = "- [老周/身份] 灯塔看守\n- [林月/关系] 老周的女儿")
        val a = (noFacts.tokens["static"] as Number).toInt()
        val b = (withFacts.tokens["static"] as Number).toInt()
        assertTrue("带账本的静态层必须更大：$a vs $b", b > a)
        assertTrue(withFacts.system.contains("[ESTABLISHED_FACTS]"))
        assertFalse(noFacts.system.contains("[ESTABLISHED_FACTS]"))
    }
}
