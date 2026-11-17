package com.aichat.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 每轮召回总线（PLAN-CONTEXT-ARCH S3）。
 *
 * 守的是三件事：同一事实只出现一次、优先级决定谁被预算挤掉、渲染格式稳定。
 */
class RecallBusTest {
    @Test
    fun emptyBusRendersNothing() {
        val bus = RecallBus()
        assertTrue(bus.isEmpty())
        assertEquals("", bus.render())
    }

    @Test
    fun duplicatesAcrossSourcesAreMergedKeepingHigherPriority() {
        val bus = RecallBus()
            .offer("memory", "- 灯塔是红色的", RecallBus.P_MEMORY)
            .offer("knowledge", "- 灯塔是红色的", RecallBus.P_KNOWLEDGE)
        val out = bus.render()
        assertEquals(1, out.lines().count { it.contains("灯塔是红色的") })
        // 保留的是资料库那条（优先级高）
        assertTrue(out.contains("(资料库) - 灯塔是红色的"))
        assertFalse(out.contains("(记忆) - 灯塔是红色的"))
    }

    @Test
    fun whitespaceDifferencesDoNotDefeatDedup() {
        val bus = RecallBus()
            .offer("memory", "  - 灯塔   是红色的  ", RecallBus.P_MEMORY)
            .offer("knowledge", "- 灯塔 是红色的", RecallBus.P_KNOWLEDGE)
        assertEquals(1, bus.render().lines().count { it.contains("红色的") })
    }

    @Test
    fun higherPriorityIsRenderedFirst() {
        val bus = RecallBus()
            .offer("memory", "记忆条目", RecallBus.P_MEMORY)
            .offer("lore", "世界书条目", RecallBus.P_LORE)
        val lines = bus.render().lines().filter { it.startsWith("- (") }
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("世界书条目"))
        assertTrue(lines[1].contains("记忆条目"))
    }

    @Test
    fun budgetDropsLowPriorityItemsFirst() {
        val bus = RecallBus()
            .offer("lore", "甲".repeat(200), RecallBus.P_LORE, tokens = 400)
            .offer("memory", "乙".repeat(200), RecallBus.P_MEMORY, tokens = 400)
        val out = bus.render(budgetTokens = 500)
        assertTrue(out.contains("(世界书)"))
        assertFalse(out.contains("(记忆)"))
    }

    @Test
    fun firstItemIsKeptEvenIfItExceedsBudget() {
        // 一条超预算的植入总比什么都不给强：空召回会让模型以为「没有资料」
        val out = RecallBus().offer("lore", "很长的世界书", RecallBus.P_LORE, tokens = 5000).render(100)
        assertTrue(out.contains("很长的世界书"))
    }

    @Test
    fun headerExplainsTheseAreReferencesNotUserWords() {
        val out = RecallBus().offer("knowledge", "文档片段", RecallBus.P_KNOWLEDGE).render()
        assertTrue(out.startsWith(RecallBus.HEADER))
        assertTrue(out.contains("不要复述"))
    }

    @Test
    fun multilineTextIsIndentedSoItStaysInsideItsBullet() {
        val out = RecallBus().offer("knowledge", "第一行\n第二行", RecallBus.P_KNOWLEDGE).render()
        val lines = out.lines()
        val i = lines.indexOfFirst { it.contains("第一行") }
        assertTrue(lines[i].startsWith("- (资料库)"))
        assertTrue(lines[i + 1].startsWith("  "))
    }

    @Test
    fun blankTextIsIgnored() {
        val bus = RecallBus().offer("memory", "   ", RecallBus.P_MEMORY)
        assertTrue(bus.isEmpty())
        assertEquals("", bus.render())
    }

    @Test
    fun labelOfUnknownSourceFallsBackToItsName() {
        assertEquals("自定义", RecallBus.labelOf("自定义"))
        assertEquals("世界书", RecallBus.labelOf("lore"))
    }
}
