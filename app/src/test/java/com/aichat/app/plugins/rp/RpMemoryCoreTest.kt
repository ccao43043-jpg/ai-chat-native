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
 * 与 `app/src/test/resources/golden/rpmemory/core.json` 比对。
 *
 * golden 由 `tools/rpmemory-golden/gen.cjs` 直接跑旧版 `www/rpmemory.js` 生成；
 * 改口径必须重跑（`node tools/rpmemory-golden/gen.cjs`）并说明为什么。
 */
class RpMemoryCoreTest {

    private val golden: Map<*, *> by lazy {
        val text = javaClass.classLoader!!.getResource("golden/rpmemory/core.json")!!.readText()
        Js.parse(text) as Map<*, *>
    }

    private fun entries(): List<JObj> = listOf(
        jobj("id" to "a1", "seq" to 1, "type" to "fact", "text" to "林月是船长，住在北港。",
            "keywords" to listOf("林月", "船长", "北港"), "pinned" to false, "disabled" to false),
        jobj("id" to "a2", "seq" to 2, "type" to "event", "text" to "昨天船队遇上了风暴。",
            "keywords" to listOf("船队", "风暴"), "pinned" to false, "disabled" to false),
        jobj("id" to "a3", "seq" to 3, "type" to "relation", "text" to "林月信任老周。",
            "keywords" to listOf("林月", "老周", "信任"), "pinned" to true, "disabled" to false),
        jobj("id" to "a4", "seq" to 4, "type" to "detail", "text" to "货舱里有一只上锁的铁箱。",
            "keywords" to listOf("货舱", "铁箱"), "pinned" to false, "disabled" to false),
        jobj("id" to "a5", "seq" to 5, "type" to "fact", "text" to "这艘船叫「白鲸号」。",
            "keywords" to listOf("白鲸号", "船"), "pinned" to false, "disabled" to false),
    )

    @Test
    fun keywordsMatchJs() {
        // 顺序比集合更脆：JS 的 localeCompare 走 Node 的 ICU，JVM 的 Collator 在「3 字英文 vs 2 字中文」
        // 这种同分项上取舍不同。这里比**集合**（截断到 KW_MAX 后到底留下了哪些词），顺序在 mergeKeywords 里另有覆盖。
        val expected = (golden["autoKeywords"] as List<*>).map { Js.str(it) }.sorted()
        val actual = RpMemoryCore.autoKeywords("林月在北港的码头等老周，手里拿着账本。Lin Yue waits.").sorted()
        assertEquals(expected, actual)

        val mk = (golden["mergeKeywords"] as List<*>).map { Js.str(it) }
        assertEquals(mk, RpMemoryCore.mergeKeywords(listOf("林月", "船长", "北港"), listOf("林月", "风暴", "船长")))
    }

    @Test
    fun relationTriplesMatchJs() {
        val texts = listOf("林月信任老周。", "林月不信任老周了。", "铁箱上锁了。", "这句话没有关系词。")
        val expected = golden["triples"] as List<*>
        texts.forEachIndexed { i, t ->
            val got = RpMemoryCore.extractRelationTriple(t)
            val want = expected[i]
            if (want == null) {
                assertNull("「$t」不该抽到三元组", got)
            } else {
                val w = want as Map<*, *>
                assertEquals(Js.str(w["subject"]), got!!.subject)
                assertEquals(Js.str(w["object"]), got.obj)
                assertEquals(Js.str(w["predicate"]), got.predicate)
                assertEquals(Js.str(w["family"]), got.family)
                assertEquals((w["polarity"] as Number).toInt(), got.polarity)
            }
        }
    }

    @Test
    fun conflictDetectionMatchesJs() {
        val pairs = golden["conflicts"] as List<*>
        pairs.forEach { p ->
            val m = p as Map<*, *>
            val a = Js.str(m["a"]); val b = Js.str(m["b"])
            val want = m["hit"] as Boolean
            assertEquals("detectConflict($a, $b)", want, RpMemoryCore.detectConflict(a, b))
        }
    }

    @Test
    fun mergeMatchesJs() {
        val m = golden["mergeSimple"] as Map<*, *>
        val list = entries().take(3)
        val r = RpMemoryCore.mergeEntries(
            list,
            listOf(
                jobj("type" to "fact", "text" to "林月是船长，住在北港的灯塔旁。", "keywords" to listOf("林月", "灯塔")),
                jobj("type" to "event", "text" to "船队修好了帆。", "keywords" to listOf("船队", "帆")),
            ),
            1_700_000_000_000L, "auto", mapOf("turn" to 7),
        )
        assertEquals((m["added"] as Number).toInt(), r.added)
        assertEquals((m["merged"] as Number).toInt(), r.merged)
        assertEquals((m["conflicts"] as Number).toInt(), r.conflicts)
        assertEquals((m["superseded"] as Number).toInt(), r.superseded)

        val wantList = m["list"] as List<*>
        assertEquals(wantList.size, r.list.size)
        wantList.forEachIndexed { i, w ->
            val wm = w as Map<*, *>
            val got = r.list[i]
            assertEquals("第 $i 条的 id", Js.str(wm["id"]), Js.str(got["id"]))
            assertEquals("第 $i 条的 text", Js.str(wm["text"]), Js.str(got["text"]))
            assertEquals("第 $i 条的 keywords", (wm["keywords"] as List<*>).map { Js.str(it) },
                (got["keywords"] as List<*>).map { Js.str(it) })
        }
    }

    @Test
    fun mergeConflictSupersedesOldEntry() {
        val m = golden["mergeConflict"] as Map<*, *>
        val r = RpMemoryCore.mergeEntries(
            listOf(jobj("id" to "a3", "seq" to 3, "type" to "relation", "text" to "林月信任老周。",
                "keywords" to listOf("林月", "老周"), "disabled" to false, "version" to 1)),
            listOf(jobj("type" to "relation", "text" to "林月不信任老周了。", "keywords" to listOf("林月", "老周"))),
            1_700_000_005_000L, "auto", mapOf("turn" to 9),
        )
        assertEquals((m["conflicts"] as Number).toInt(), r.conflicts)
        assertEquals((m["superseded"] as Number).toInt(), r.superseded)

        val old = r.list.first { Js.str(it["id"]) == "a3" }
        assertEquals("冲突后旧条目要失效", true, old["disabled"])
        assertTrue("旧条目要指向新版本", Js.str(old["supersededBy"]).isNotEmpty())
        assertEquals(9, (old["validToTurn"] as Number).toInt())

        val neu = r.list.last()
        assertEquals("新版本号 = 旧版本 + 1", 2, (neu["version"] as Number).toInt())
        assertEquals("旧 id 要记在 supersedes 上", "a3", Js.str(neu["supersedes"]))
        assertEquals(9, (neu["validFromTurn"] as Number).toInt())
    }

    @Test
    fun retrievalMatchesJs() {
        val bm25 = (golden["selectBm25"] as List<*>).map { Js.str(it) }
        assertEquals(bm25, RpMemoryCore.selectRelevant(entries(), "林月 老周 信任",
            mapOf("memTokens" to 600, "maxInject" to 3)).map { Js.str(it["id"]) })

        val legacy = (golden["selectLegacy"] as List<*>).map { Js.str(it) }
        assertEquals("legacy 走关键词打分", legacy,
            RpMemoryCore.selectRelevant(entries(), "货舱 铁箱", mapOf("retrieval" to "legacy", "maxInject" to 2))
                .map { Js.str(it["id"]) })
    }

    @Test
    fun renderedTextMatchesJs() {
        assertEquals(Js.str(golden["injection"]),
            RpMemoryCore.renderInjection(RpMemoryCore.selectRelevant(entries(), "林月 信任", mapOf("maxInject" to 2))))

        assertEquals(Js.str(golden["memoryText"]), RpMemoryCore.toMemoryText(entries().take(2)))
    }

    @Test
    fun extractionParsingMatchesJs() {
        val want = (golden["parsed"] as List<*>).map { it as Map<*, *> }
        val got = RpMemoryCore.parseExtraction(
            "```json\n[{\"type\":\"事实\",\"text\":\"林月是船长\",\"keywords\":[\"林月\"]},{\"type\":\"event\",\"text\":\"遇上了风暴\"}]\n```",
        )
        assertEquals(want.size, got.size)
        want.forEachIndexed { i, w ->
            assertEquals(Js.str(w["type"]), Js.str(got[i]["type"]))
            assertEquals(Js.str(w["text"]), Js.str(got[i]["text"]))
        }

        val lines = (golden["parsedLines"] as List<*>).map { it as Map<*, *> }
        val gotLines = RpMemoryCore.parseExtraction("- [关系] 林月信任老周\n- [细节] 铁箱上锁了")
        assertEquals(lines.size, gotLines.size)
        lines.forEachIndexed { i, w ->
            assertEquals("行格式：中文类型要能映射", Js.str(w["type"]), Js.str(gotLines[i]["type"]))
        }
    }

    @Test
    fun scheduleSettingsAndPromptMatchJs() {
        val want = (golden["shouldExtract"] as List<*>).map { it as Boolean }
        assertEquals(want, listOf(
            RpMemoryCore.shouldExtract(20, 0, 20),
            RpMemoryCore.shouldExtract(19, 0, 20),
            RpMemoryCore.shouldExtract(40, 20, 20),
        ))

        val s = RpMemoryCore.settingsOf(mapOf("interval" to 3, "maxInject" to 99, "retrieval" to "x",
            "window" to 1, "memTokens" to 99999))
        val gs = golden["settings"] as Map<*, *>
        assertEquals((gs["interval"] as Number).toInt(), s.interval)
        assertEquals((gs["maxInject"] as Number).toInt(), s.maxInject)
        assertEquals((gs["memTokens"] as Number).toInt(), s.memTokens)
        assertEquals(Js.str(gs["retrieval"]), s.retrieval)

        val head = (golden["promptHead"] as List<*>).map { Js.str(it) }
        val prompt = RpMemoryCore.buildExtractPrompt(
            listOf(jobj("role" to "user", "content" to "你好"), jobj("role" to "assistant", "content" to "你好，船长。")),
            listOf(jobj("type" to "fact", "text" to "林月是船长")),
        ).split("\n").take(6)
        assertEquals(head, prompt)
    }

    @Test
    fun disabledAndSupersededAreNotRecalled() {
        val list = entries().toMutableList()
        list[0]["disabled"] = true
        list[1]["supersededBy"] = "m9_1"
        val ids = RpMemoryCore.selectRelevant(list, "林月 船队", mapOf("maxInject" to 5)).map { Js.str(it["id"]) }
        assertFalse("失效条目不该被召回", "a1" in ids)
        assertFalse("被取代条目不该被召回", "a2" in ids)
    }

    @Test
    fun charKeyIsolatesMemoryByPersona() {
        assertEquals("persona:p1", RpMemoryCore.charKey("c1", "p1"))
        assertEquals("conv:c1", RpMemoryCore.charKey("c1", null))
    }
}
