package com.aichat.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 体检面板要能看见「本轮哪个插件炸了、预算为什么不够」。
 *
 * 以前插件异常只进 registry 的 errors（顶栏红点，跨会话汇总），排查时还得翻 logcat；
 * 装配阶段的说明（预算不足跳过召回、跨来源重复被合并）更是只活在代码里。
 */
class ContextTraceErrorTest {
    @Test
    fun errorsAndNotesAreRecordedInOrder() {
        val t = ContextTrace()
        t.error("rpcache", "onBeforeRequest", "boom")
        t.error("cachemeter", "onAfterResponse", "kaboom")
        t.note("召回预算不足")
        assertEquals(listOf("rpcache · onBeforeRequest · boom", "cachemeter · onAfterResponse · kaboom"), t.errors())
        assertEquals(listOf("召回预算不足"), t.notes())
    }

    @Test
    fun freshTraceIsEmpty() {
        val t = ContextTrace()
        assertTrue(t.errors().isEmpty())
        assertTrue(t.notes().isEmpty())
    }

    @Test
    fun errorsDoNotLeakIntoSlotOwnership() {
        val t = ContextTrace()
        t.record(ContextTrace.Step("rpcache", listOf(Slot.S1_STABLE), 10, 0))
        t.error("rpcache", "onBeforeRequest", "boom")
        assertEquals(listOf("rpcache"), t.ownersOf(Slot.S1_STABLE))
        assertEquals(1, t.errors().size)
    }
}
