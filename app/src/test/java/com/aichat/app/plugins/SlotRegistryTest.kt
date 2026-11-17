package com.aichat.app.plugins

import com.aichat.app.domain.ContextTrace
import com.aichat.app.domain.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一槽一主（PLAN-CONTEXT-ARCH §3.2 / §5）。
 *
 * 这两组用例是「不要出现多个插件干一件事」的机器守门：
 * - SlotRegistryTest：谁抢了谁的槽位能被记下来，且过渡期只放行 S3。
 * - ContextTraceTest：谁改了哪一段、多少 token 能被量出来；只改末条 user 时稳定前缀 hash 不变。
 */
class SlotRegistryTest {
    @Test
    fun singleOwnerClaimsCleanly() {
        val r = SlotRegistry()
        assertTrue(r.claim(Slot.S1_STABLE, "rpcache"))
        assertEquals("rpcache", r.ownerOf(Slot.S1_STABLE))
        assertTrue(r.conflicts().isEmpty())
    }

    @Test
    fun samePluginClaimingTwiceIsNotAConflict() {
        val r = SlotRegistry()
        assertTrue(r.claim(Slot.S5_BODY, "gcache"))
        assertTrue(r.claim(Slot.S5_BODY, "gcache"))
        assertEquals(listOf("gcache"), r.ownersOf(Slot.S5_BODY))
        assertTrue(r.conflicts().isEmpty())
    }

    @Test
    fun secondOwnerOfExclusiveSlotIsRejectedAndRecorded() {
        val r = SlotRegistry()
        assertTrue(r.claim(Slot.S1_STABLE, "rpcache"))
        assertFalse(r.claim(Slot.S1_STABLE, "affect"))
        assertEquals(1, r.conflicts().size)
        // 文案要能直接看懂：谁已经拥有、谁又声明了
        val msg = r.conflicts()[0]
        assertTrue(msg, msg.contains("s1"))
        assertTrue(msg, msg.contains("rpcache"))
        assertTrue(msg, msg.contains("affect"))
    }

    @Test
    fun recallSlotIsShareableDuringTransition() {
        val r = SlotRegistry()
        assertTrue(r.claim(Slot.S3_RECALL, "knowledge"))
        assertTrue(r.claim(Slot.S3_RECALL, "rpmemory"))
        assertTrue(r.claim(Slot.S3_RECALL, "rpcache"))
        assertTrue(r.conflicts().isEmpty())
        assertEquals(listOf("knowledge", "rpmemory", "rpcache"), r.ownersOf(Slot.S3_RECALL))
        // 但 ownerOf 只认第一个：M4 之后唯一所有者就是 RecallBus
        assertEquals("knowledge", r.ownerOf(Slot.S3_RECALL))
    }

    @Test
    fun recallBecomesExclusiveOnceBusTakesOver() {
        val r = SlotRegistry(shareable = emptySet())
        assertTrue(r.claim(Slot.S3_RECALL, "recallbus"))
        assertFalse(r.claim(Slot.S3_RECALL, "knowledge"))
        assertEquals(1, r.conflicts().size)
    }

    @Test
    fun bodySlotIsExclusive() {
        val r = SlotRegistry()
        assertTrue(r.claim(Slot.S5_BODY, "gcache"))
        assertFalse(r.claim(Slot.S5_BODY, "mmcache"))
        assertEquals(1, r.conflicts().size)
    }

    @Test
    fun unknownSlotLookupIsNull() {
        assertNull(Slot.of("s9"))
        assertNull(Slot.of(null))
        assertEquals(Slot.S4_TAG, Slot.of("s4"))
    }
}

class ContextTraceTest {
    @Test
    fun recordsStepsWithSlotAndDelta() {
        val t = ContextTrace()
        t.record(ContextTrace.Step("rpcache", listOf(Slot.S1_STABLE), systemDelta = 120, userDelta = -8))
        t.record(ContextTrace.Step("affect", listOf(Slot.S4_TAG), systemDelta = 0, userDelta = 16))
        assertEquals(2, t.steps().size)
        assertEquals(120, t.steps()[0].systemDelta)
        assertEquals(-8, t.steps()[0].userDelta)
        assertEquals(listOf(Slot.S4_TAG), t.steps()[1].slots)
    }

    @Test
    fun ownersOfAggregatesAcrossSteps() {
        val t = ContextTrace()
        t.record(ContextTrace.Step("rpcache", listOf(Slot.S1_STABLE, Slot.S2_DELTA), 10, 0))
        t.record(ContextTrace.Step("rpcache", listOf(Slot.S2_DELTA), 6, 0))
        t.record(ContextTrace.Step("knowledge", listOf(Slot.S3_RECALL), 0, 40))
        assertEquals(listOf("rpcache"), t.ownersOf(Slot.S1_STABLE))
        assertEquals(listOf("rpcache"), t.ownersOf(Slot.S2_DELTA))
        assertEquals(listOf("knowledge"), t.ownersOf(Slot.S3_RECALL))
        assertTrue(t.ownersOf(Slot.S5_BODY).isEmpty())
    }

    @Test
    fun conflictsAreCollectable() {
        val t = ContextTrace()
        t.conflict("s1 被 rpcache 与 affect 同时声明")
        assertEquals(1, t.conflicts().size)
        assertTrue(t.conflicts()[0].contains("s1"))
    }

    /**
     * 最重要的一条：只交召回（S3）的一轮里，没有人碰稳定前缀（S1）。
     *
     * 「缓存能不能命中」的充要条件就是 S1 的字节不变；M4 会把它升级成
     * `systemPromptByteEqualWhenOnlyRecallChanges`（拿真实请求体比对）。
     */
    @Test
    fun onlyRecallStepsLeaveStablePrefixUnowned() {
        val t = ContextTrace()
        t.record(ContextTrace.Step("knowledge", listOf(Slot.S3_RECALL), systemDelta = 0, userDelta = 40))
        t.record(ContextTrace.Step("rpmemory", listOf(Slot.S3_RECALL), systemDelta = 0, userDelta = 60))
        t.record(ContextTrace.Step("affect", listOf(Slot.S4_TAG), systemDelta = 0, userDelta = 18))
        assertTrue(t.ownersOf(Slot.S1_STABLE).isEmpty())
        assertTrue(t.ownersOf(Slot.S2_DELTA).isEmpty())
        assertTrue(t.steps().all { it.systemDelta == 0 })
        // 召回确实变了：这轮末条 user 比上轮多
        assertEquals(118, t.steps().sumOf { it.userDelta })
    }
}
