package com.aichat.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 末条用户消息的装配顺序（PLAN-CONTEXT-ARCH S3/S4）。
 *
 * 最关键的一条：三个槽位都为空时必须**逐字节**等于原文 —— 这是
 * `systemPromptByteEqualWhenOnlyRecallChanges` 那条验收标准的前提，
 * 也是缓存前缀不被无意义改写的前提。
 */
class ContextAssemblerTest {
    private val original = "灯塔在哪个方向？"

    @Test
    fun emptyTagAndRecallLeaveOriginalByteEqual() {
        assertEquals(original, ContextAssembler.assembleLastUser(original))
        assertEquals(original, ContextAssembler.assembleLastUser(original, null, null))
        assertEquals(original, ContextAssembler.assembleLastUser(original, "  ", ""))
    }

    @Test
    fun tagGoesFirst() {
        assertEquals("[AFFECT] mood=平静\n$original", ContextAssembler.assembleLastUser(original, "[AFFECT] mood=平静"))
    }

    @Test
    fun recallGoesLast() {
        assertEquals("$original\n\n[RECALL] 片段", ContextAssembler.assembleLastUser(original, null, "[RECALL] 片段"))
    }

    @Test
    fun orderIsTagThenOriginalThenRecall() {
        val out = ContextAssembler.assembleLastUser(original, "[AFFECT] mood=平静", "[RECALL] 片段")
        assertEquals("[AFFECT] mood=平静\n$original\n\n[RECALL] 片段", out)
    }

    @Test
    fun multiLineOriginalIsNotReformatted() {
        val multi = "第一行\n第二行\n\n空行后的段落"
        assertEquals(multi, ContextAssembler.assembleLastUser(multi))
        assertEquals("[T]\n$multi\n\n[R]", ContextAssembler.assembleLastUser(multi, "[T]", "[R]"))
    }
}
