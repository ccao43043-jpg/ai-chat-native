package com.aichat.app.plugins.rp

import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.js.jobj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 事实账本（`www/rpmemory.js` 的 §6）：生效区间、等价/冲突合并、注入文本、摘要一致性。
 *
 * 只用纯函数，不碰文件与插件宿主。
 */
class RpMemoryLedgerTest {

    private fun fact(
        id: String,
        subject: String,
        attribute: String,
        obj: String,
        polarity: Int,
        turn: Int,
        quote: String = "",
    ): JObj = jobj(
        "id" to id, "subject" to subject, "attribute" to attribute,
        "value" to jobj("object" to obj, "polarity" to polarity, "predicate" to "", "raw" to quote),
        "validFromTurn" to turn, "validToTurn" to null, "supersededBy" to null,
        "confidence" to 0.7, "quote" to quote.ifEmpty { "$subject$attribute$obj" },
    )

    @Test
    fun factFromTextUsesTripleWhenPossible() {
        val f = RpMemoryCore.factFromText("老周喜欢码头", mapOf("turn" to 3, "sourceMsgId" to "m9"))
        assertEquals("老周", Js.str(f["subject"]))
        assertEquals("affection", Js.str(f["attribute"]))
        assertEquals(3, (f["validFromTurn"] as Number).toInt())
        assertEquals("m9", Js.str(f["sourceMsgId"]))
        val v = f["value"] as Map<*, *>
        assertEquals("码头", Js.str(v["object"]))
        assertEquals(1, (v["polarity"] as Number).toInt())
    }

    @Test
    fun factFromTextFallsBackToNote() {
        val f = RpMemoryCore.factFromText("风浪很大", mapOf("turn" to 1))
        assertEquals("note", Js.str(f["attribute"]))
        assertEquals(0, ((f["value"] as Map<*, *>)["polarity"] as Number).toInt())
        assertEquals(0.5, (f["confidence"] as Number).toDouble(), 1e-9)
    }

    @Test
    fun upsertMergesEquivalentAndClosesOnConflict() {
        val a = fact("f1", "铁箱", "lock", "钥匙", 1, 1)
        val first = RpMemoryCore.upsertFact(emptyList(), a, 1)
        assertEquals("add", first.action)
        assertEquals(1, first.ledger.size)

        // 等价（同 object 同极性）→ 合并置信度，不新增
        val same = fact("f2", "铁箱", "lock", "钥匙", 1, 2)
        val merged = RpMemoryCore.upsertFact(first.ledger, same, 2)
        assertEquals("merge", merged.action)
        assertEquals(1, merged.ledger.size)
        val conf = (merged.ledger[0]["confidence"] as Number).toDouble()
        assertTrue("置信度应被抬高：$conf", conf > 0.7)

        // 极性相反 → conflict：旧事实被关闭并指向新事实
        val opposite = fact("f3", "铁箱", "lock", "钥匙", -1, 3)
        val conflicted = RpMemoryCore.upsertFact(merged.ledger, opposite, 3)
        assertEquals("conflict", conflicted.action)
        assertEquals(2, conflicted.ledger.size)
        val closed = conflicted.ledger.first { Js.str(it["id"]) == "f1" }
        assertEquals("f3", Js.str(closed["supersededBy"]))
        assertEquals(3, (closed["validToTurn"] as Number).toInt())
        assertNotNull(conflicted.closed)
    }

    @Test
    fun currentFactsSkipsSupersededAndHonoursTurnWindow() {
        val old = fact("f1", "铁箱", "lock", "钥匙", 1, 1).also { it["validToTurn"] = 3; it["supersededBy"] = "f2" }
        val cur = fact("f2", "铁箱", "lock", "钥匙", -1, 3)
        assertTrue(RpMemoryCore.currentFacts(listOf(old, cur), null).map { Js.str(it["id"]) } == listOf("f2"))
        // 回看第 2 轮：f1 仍在生效区间内
        // 被 supersededBy 关掉的事实，即使在旧回合也不再计入当前视图（与 JS 一致）
        assertTrue(RpMemoryCore.currentFacts(listOf(old, cur), 2).isEmpty())
    }

    @Test
    fun renderLedgerGroupsBySubjectAndRespectsBudget() {
        val l = listOf(
            fact("f1", "老周", "affection", "码头", 1, 1),
            fact("f2", "老周", "trust", "林月", 1, 2),
            fact("f3", "林月", "affection", "老周", 1, 3),
        )
        val text = RpMemoryCore.renderLedger(l)
        assertTrue(text.startsWith("[MEMORY_LEDGER]"))
        assertTrue(text.contains("- [老周/affection]"))
        assertTrue(text.contains("- [林月/affection]"))
        // budget=1 → 只留打分最高的一条
        val one = RpMemoryCore.renderLedger(l, mapOf("budget" to 1))
        assertEquals(2, one.split("\n").size) // 标题 + 1 行
        assertEquals("", RpMemoryCore.renderLedger(emptyList()))
    }

    @Test
    fun pruneReopensFactWhenSupersederIsPruned() {
        val old = fact("f1", "铁箱", "lock", "钥匙", 1, 1).also {
            it["validToTurn"] = 2; it["supersededBy"] = "f2"; it["sourceMsgId"] = "m1"
        }
        val cur = fact("f2", "铁箱", "lock", "钥匙", -1, 2).also { it["sourceMsgId"] = "m2" }
        val kept = RpMemoryCore.pruneBySourceIds(listOf(old, cur), setOf("m1"))
        assertEquals(1, kept.size)
        assertNull(kept[0]["supersededBy"])
        assertNull(kept[0]["validToTurn"])
        // 无 sourceMsgId 的老数据默认保留
        val legacy = fact("f0", "船", "location", "北港", 0, 1)
        assertEquals(1, RpMemoryCore.pruneBySourceIds(listOf(legacy), emptySet()).size)
    }

    @Test
    fun assertionLinesDropsTitlesAndEmptyMarkers() {
        val text = """
            FACTS
            - 老周喜欢码头
            无
            ## UNRESOLVED
            - 铁箱里是什么
        """.trimIndent()
        assertEquals(listOf("老周喜欢码头", "铁箱里是什么"), RpMemoryCore.assertionLines(text))
    }

    @Test
    fun summaryConflictsWithLedgerArePenalised() {
        val l = listOf(fact("f1", "老周", "affection", "码头", 1, 1, quote = "老周喜欢码头"))
        val good = RpChapterCore.parseSummary(sampleSummary("老周喜欢码头"), l)
        val bad = RpChapterCore.parseSummary(sampleSummary("老周讨厌码头"), l)

        assertEquals(0, good.ledgerCheck!!.conflictCount)
        assertEquals(good.quality, good.ledgerCheck.summaryQuality, 1e-9)
        assertTrue("冲突应被识别", bad.ledgerCheck!!.conflictCount >= 1)
        assertTrue("冲突后质量要掉", bad.quality < good.quality)
        assertEquals(good.quality, bad.quality + 0.10, 1e-9)
    }

    @Test
    fun manyConflictsPushQualityBelowThresholdAndExplainWhy() {
        val l = listOf(fact("f1", "老周", "affection", "码头", 1, 1, quote = "老周喜欢码头"))
        val text = sampleSummary("老周讨厌码头") + "\n" + (1..5).joinToString("\n") { "- 老周讨厌码头" }
        val r = RpChapterCore.parseSummary(text, l)
        assertFalse(r.ok)
        assertTrue(r.ledgerCheck!!.conflictCount >= 5)
        assertTrue("质量应低于 0.6：${r.quality}", r.quality < 0.6)
        assertTrue(r.retryHint!!.contains("账本冲突"))
    }

    @Test
    fun unknownSubjectCountsAsUnsupported() {
        val l = listOf(fact("f1", "老周", "affection", "码头", 1, 1))
        val r = RpChapterCore.parseSummary(sampleSummary("林月喜欢老周"), l)
        assertTrue(r.ledgerCheck!!.unsupportedCount >= 1)
        assertTrue(r.ledgerCheck.summaryQuality < 1.0)
    }

    private fun sampleSummary(mid: String): String = """
        FACTS
        - $mid
        CHARACTER_KNOWLEDGE
        - 老周知道码头
        PLAYER_KNOWLEDGE
        - 玩家知道老周
        SECRET
        - 无
        UNRESOLVED
        - 铁箱里是什么
        RELATIONSHIPS
        - 老周与林月
        OBJECTS
        - 钥匙
        LOCATION
        - 码头
    """.trimIndent()

    private fun assertNotNull(v: Any?) = org.junit.Assert.assertNotNull(v)
}
