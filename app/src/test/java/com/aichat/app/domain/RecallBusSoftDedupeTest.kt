package com.aichat.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨来源近似去重（PLAN-ALGO-CACHE P6 的延伸）。
 *
 * 为什么需要：资料库命中的是一整段原文，记忆命中的是抽取出来的短句，两者常常在说同一件事，
 * 只是措辞不同。精确去重（空白归一后逐字相同）抓不到这种，于是同一事实被注入两遍：
 * 白花预算、还让提示词变啰嗦。
 *
 * 为什么只在跨来源时做、还有两道守卫：同来源的两条短句往往是并列事实（「灯塔高 30 米」/「灯塔高 40 米」），
 * 合并它们就是**丢信息**；数字或否定不同同理。
 */
class RecallBusSoftDedupeTest {
    private fun bus(vararg items: RecallItem): RecallBus =
        RecallBus().also { b -> items.forEach { b.offer(it) } }

    private val lighthouse = "灯塔在渔村的东侧山崖上，塔顶常年积雪，夜里点亮给回港的渔船指路"

    @Test
    fun crossSourceNearDuplicateIsMerged() {
        val b = bus(
            RecallItem("knowledge", lighthouse, RecallBus.P_KNOWLEDGE),
            RecallItem("memory", "$lighthouse。", RecallBus.P_MEMORY),
        )
        val out = b.render()
        assertEquals(1, Regex("灯塔").findAll(out).count())
        assertEquals(1, b.lastSoftDropped)
    }

    @Test
    fun sameSourceIsNotSoftDeduped() {
        val b = bus(
            RecallItem("knowledge", lighthouse, RecallBus.P_KNOWLEDGE),
            RecallItem("knowledge", "$lighthouse。", RecallBus.P_KNOWLEDGE),
        )
        val out = b.render()
        // 精确去重只管空白归一，句末标点不同 → 两条都留（同来源不做近似合并）
        assertEquals(2, Regex("灯塔").findAll(out).count())
        assertEquals(0, b.lastSoftDropped)
    }

    @Test
    fun differentNumbersKeepBoth() {
        val a = "灯塔高 30 米，渔民晚上靠它回港，塔顶常年积雪"
        val bText = "灯塔高 40 米，渔民晚上靠它回港，塔顶常年积雪"
        val b = bus(RecallItem("knowledge", a, RecallBus.P_KNOWLEDGE), RecallItem("memory", bText, RecallBus.P_MEMORY))
        val out = b.render()
        assertTrue(out.contains("30"))
        assertTrue(out.contains("40"))
        assertEquals(0, b.lastSoftDropped)
    }

    @Test
    fun negationDifferenceKeepsBoth() {
        val positive = lighthouse
        val negative = "灯塔不在渔村的东侧山崖上，塔顶常年积雪，夜里点亮给回港的渔船指路"
        val b = bus(
            RecallItem("knowledge", positive, RecallBus.P_KNOWLEDGE),
            RecallItem("memory", negative, RecallBus.P_MEMORY),
        )
        val out = b.render()
        assertTrue(out.contains("不在"))
        assertEquals(0, b.lastSoftDropped)
    }

    @Test
    fun counterResetsOnEveryRender() {
        val b = bus(
            RecallItem("knowledge", lighthouse, RecallBus.P_KNOWLEDGE),
            RecallItem("memory", "$lighthouse。", RecallBus.P_MEMORY),
        )
        assertEquals(1, run { b.render(); b.lastSoftDropped })
        assertEquals(1, run { b.render(); b.lastSoftDropped })
    }
}
