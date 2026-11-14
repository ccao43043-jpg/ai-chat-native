package com.aichat.app.plugins.rp

import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.js.jobj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 章节摘要纯函数层 vs 旧版 `rpcache.js` 的 golden（`app/src/test/resources/golden/rpcache/chapter.json`）。
 * golden 由 `tools/rpcache-golden/gen-chapter.cjs` 直接跑旧版生成 —— 改这里必须重跑那个脚本。
 */
class RpChapterCoreTest {

    private val golden: Map<*, *> = run {
        val text = javaClass.classLoader!!.getResourceAsStream("golden/rpcache/chapter.json")!!
            .readBytes().toString(Charsets.UTF_8)
        Js.parse(text) as Map<*, *>
    }

    private fun g(key: String): Any? = golden[key]

    private val BATCH4 = listOf(
        jobj("id" to "m1", "role" to "user", "content" to "船还好吗？"),
        jobj("id" to "m2", "role" to "assistant", "content" to "桅杆断了，帆还能补。"),
        jobj("id" to "m3", "role" to "user", "content" to "铁箱呢？"),
        jobj("id" to "m4", "role" to "assistant", "content" to "锁着，钥匙在老周那儿。"),
    )

    @Test
    fun goodSummaryParsesLikeJs() {
        val want = g("good") as Map<*, *>
        val got = RpChapterCore.parseSummary(want["text"])

        assertTrue("八分区齐全、质量 1.0 应通过", got.ok)
        assertEquals((want["quality"] as Number).toDouble(), got.quality, 1e-9)
        assertTrue(got.missing.isEmpty())
        assertTrue(got.critEmpty.isEmpty())
        assertEquals(Js.str(want["text"]), got.text)
    }

    @Test
    fun fencedAndTitledSummaryIsCleaned() {
        val want = g("fenced") as Map<*, *>
        val input = "```markdown\n[CHAPTER SUMMARY #003]\n" + Js.str(want["text"]) + "\n```"
        val got = RpChapterCore.parseSummary(input)
        assertTrue("带代码块和标题行也要能解析", got.ok)
        assertEquals(Js.str(want["text"]), got.text)
    }

    @Test
    fun missingSectionsAreRejected() {
        val want = g("invalid") as Map<*, *>
        val got = RpChapterCore.parseSummary("FACTS:\n- 只有一条事实。")

        assertFalse("只写 FACTS 不能落章", got.ok)
        assertEquals((want["quality"] as Number).toDouble(), got.quality, 1e-9)
        assertEquals((want["missing"] as List<*>).map { Js.str(it) }, got.missing)
    }

    @Test
    fun emptySummaryIsRejected() {
        val got = RpChapterCore.parseSummary("")
        assertFalse(got.ok)
        assertEquals(0.0, got.quality, 1e-9)
        assertEquals(RpChapterCore.SECTIONS.size, got.missing.size)
    }

    @Test
    fun summaryPromptMatchesJs() {
        val batch = BATCH4
        val chapters = listOf(
            jobj("no" to 1, "content" to "FACTS:\n- 船队从北港出发。"),
            jobj("no" to 2, "content" to "FACTS:\n- 遇上风暴。"),
        )
        assertEquals(Js.str(g("prompt")), RpChapterCore.buildSummaryPrompt(chapters, batch, "- [事实] 林月是船长。"))
        assertEquals(Js.str(g("promptNoChapters")), RpChapterCore.buildSummaryPrompt(emptyList(), batch, null))
    }

    @Test
    fun consolidatePromptMatchesJs() {
        val batch = BATCH4
        val chapters = listOf(
            jobj("no" to 1, "content" to "FACTS:\n- 船队从北港出发。"),
            jobj("no" to 2, "content" to "FACTS:\n- 遇上风暴。"),
        )
        assertEquals(Js.str(g("consolidate")), RpChapterCore.buildConsolidatePrompt(chapters, batch, ""))
    }

    @Test
    fun transcriptUsesChineseSpeakers() {
        assertEquals(Js.str(g("transcript")), RpChapterCore.transcriptOf(BATCH4))
    }

    @Test
    fun commitRejectsLowQualityButForceAccepts() {
        val st = RpState.normalizeState(jobj("blocks" to emptyList<Any?>()))
        val bad = RpChapterCore.commitChapter(st, "FACTS:\n- 只有一条。", null, listOf("m1"), 1L, 1, 1)
        assertFalse(bad.ok)
        assertEquals("invalid_summary", bad.reason)
        assertNull(bad.chapter)

        // 旧版 force 只绕过「质量 < 0.60」，不绕过「分区不全」——非法摘要永远拒
        val forced = RpChapterCore.commitChapter(st, "FACTS:\n- 只有一条。", null, listOf("m1"), 1L, 1, 1, force = true)
        assertFalse("分区不全时 force 也不能放行", forced.ok)
        assertEquals("invalid_summary", forced.reason)
    }

    @Test
    fun commitAdvancesChapterSeqAndResetsWindow() {
        val base = RpState.normalizeState(jobj("blocks" to emptyList<Any?>()))
        val st = JObj(base.toMutableMap().also {
            it["chapterSeq"] = 2
            it["window"] = jobj("startId" to "m0", "startOrdinal" to 3)
            it["overflow"] = jobj("tempStartId" to "m9")
        })
        val good = (g("good") as Map<*, *>).let { Js.str(it["text"]) }
        val r = RpChapterCore.commitChapter(st, good, null, listOf("m1", "m2"), 5L, 4, 4)
        assertTrue(r.ok)

        val next = r.state
        assertEquals("章节号要递增", 3, (next["chapterSeq"] as Number).toInt())
        assertEquals("提交后窗口回到最前", 0, ((next["window"] as JObj)["startOrdinal"] as Number).toInt())
        assertNull("提交后清掉临时裁剪标记", next["overflow"])
        assertEquals("上轮摘要回合 = 4", 4, (next["lastSummaryRound"] as Number).toInt())
        assertEquals("章节内容 = 解析后的正文", good, Js.str(r.chapter!!["content"]))
        assertEquals("质量分要落进章节", (g("good") as Map<*, *>)["quality"], r.chapter!!["quality"])
        assertEquals("来源默认 auto", "auto", Js.str(r.chapter!!["source"]))
        assertEquals(2, (r.chapter!!["coveredIds"] as List<*>).size)
    }

    @Test
    fun clearingLoreStateDropsOnlyCoveredAnchors() {
        val base = RpState.normalizeState(jobj("blocks" to emptyList<Any?>()))
        val st = JObj(base.toMutableMap().also {
            it["loreState"] = jobj(
                "turn" to 7,
                "entries" to jobj(
                    "e1" to jobj("lastAnchorId" to "m1", "sticky" to true),
                    "e2" to jobj("lastAnchorId" to "m9", "sticky" to true),
                ),
            )
        })
        val next = RpChapterCore.clearLoreStateForCovered(st, listOf("m1"))
        val entries = ((next["loreState"] as JObj)["entries"] as Map<*, *>)
        assertFalse("被覆盖的锚点应清掉", entries.containsKey("e1"))
        assertTrue("未被覆盖的保留", entries.containsKey("e2"))
        assertEquals(7, ((next["loreState"] as JObj)["turn"] as Number).toInt())
    }

    @Test
    fun sourceFingerprintIsStableAndFiltered() {
        val items = listOf(
            jobj("id" to "m1", "role" to "user", "content" to "a"),
            jobj("id" to "m2", "role" to "assistant", "content" to "b"),
            jobj("id" to "m3", "role" to "user", "content" to "c"),
        )
        val a = RpChapterCore.sourceFp(items, listOf("m1", "m2"))
        val b = RpChapterCore.sourceFp(items, listOf("m2", "m1"))
        assertEquals("顺序无关、内容相关", a, b)
        assertTrue(a!!.startsWith("sha256:"))
        assertNull(RpChapterCore.sourceFp(items, listOf("zzz")))
    }
}
