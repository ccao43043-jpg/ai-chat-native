package com.aichat.app.plugins.rp

import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.js.jobj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 世界书触发层 vs 旧版 `rpcache.js` 的 golden（`app/src/test/resources/golden/rpcache/lore.json`）。
 * golden 由 `tools/lore-golden/gen.cjs` 直接跑旧版生成——改这里必须重跑那个脚本。
 */
class RpLoreCoreTest {

    private val golden: Map<*, *> = run {
        val text = javaClass.classLoader!!.getResourceAsStream("golden/rpcache/lore.json")!!
            .readBytes().toString(Charsets.UTF_8)
        Js.parse(text) as Map<*, *>
    }

    private fun g(key: String): Any? = golden[key]

    private fun state() = RpState.normalizeState(
        jobj(
            "lore" to Lore.parseLore(
                listOf(
                    "## 港口 | *",
                    "海风很咸。",
                    "",
                    "## 灯塔 | 灯塔, 灯 | priority=200; sticky=2; cooldown=1",
                    "红光每十五秒转一圈。",
                    "",
                    "## 老船工 | 船工, 老人 | sec=渔网; delay=2",
                    "他右手少了两根手指。",
                    "",
                    "## 潮汐 | 潮水 | prob=40",
                    "涨潮时不让人下海。",
                ).joinToString("\n"),
            ),
            "settings" to jobj("max_context" to 4000.0, "lore" to jobj()),
        ),
    )

    private val items = listOf(
        jobj("id" to "u1", "role" to "user", "content" to "我想去看看灯塔。"),
        jobj("id" to "a1", "role" to "assistant", "content" to "灯塔的光扫过海面。"),
        jobj("id" to "u2", "role" to "user", "content" to "船工说渔网破了。"),
    )

    @Test
    fun seedHashMatchesJs() {
        // JS 的 cyrb53 返回无符号 32 位，可能大于 Int.MAX（2298524500）→ 统一按无符号 Long 比
        val want = (g("cyrb53") as List<*>).map { (it as Number).toLong() }
        assertEquals(want[0], RpLoreCore.seedHash("c1\u0000u2\u0000e1").toLong() and 0xFFFFFFFFL)
        assertEquals(want[1], RpLoreCore.seedHash("").toLong() and 0xFFFFFFFFL)
    }

    @Test
    fun mulberrySequenceMatchesJs() {
        val want = (g("roll") as List<*>).map { (it as Number).toDouble() }
        val rng = RpLoreCore.rngForSeed("c1\u0000u2\u0000x")
        want.forEach { w -> assertEquals(w, Math.round(rng() * 1e6) / 1e6, 1e-9) }
    }

    @Test
    fun constantLoreTextMatchesJs() {
        assertEquals(g("constantLoreText") as String, RpLoreCore.constantLoreText(state()))
    }

    @Test
    fun fireFreezesTriggerOnAnchorAndMatchesJs() {
        val st = state()
        val (after, fired) = RpLoreCore.fireLore(st, items, 1700000000000L, jobj("convId" to "c1"))

        val wantPicked = (g("firePicked") as List<*>).map { (it as Map<*, *>)["name"] as String }
        assertEquals(wantPicked, fired.map { Js.str(it["name"]) })
        fired.forEach {
            assertEquals("u2", Js.str(it["anchorId"]))
            assertTrue(Js.str(it["content"]).isNotEmpty())
        }

        val wantText = (g("firedText") as List<*>).map { (it as Map<*, *>)["content"] as String }
        val gotText = RpLoreCore.applyFrozenLore(after, items).map { Js.str(it["content"]) }
        assertEquals(wantText, gotText)

        val wantState = g("loreState") as Map<*, *>
        val gotState = RpState.normalizeLoreState(after["loreState"])
        assertEquals((wantState["turn"] as Number).toInt(), Js.i32(gotState["turn"]))
        val wantEntries = (wantState["entries"] as List<*>).map { it as Map<*, *> }
        val gotEntries = gotState["entries"] as Map<*, *>
        assertEquals(wantEntries.size, gotEntries.size)
        wantEntries.forEach { w ->
            val e = gotEntries[w["id"] as String] as Map<*, *>
            assertEquals((w["stickyUntil"] as Number).toInt(), Js.i32(e["stickyUntil"]))
            assertEquals((w["cooldownUntil"] as Number).toInt(), Js.i32(e["cooldownUntil"]))
            assertEquals(w["lastAnchorId"], Js.str(e["lastAnchorId"]))
        }
    }

    @Test
    fun secondTurnOnSameAnchorDoesNotRefire() {
        val st = state()
        val (after, _) = RpLoreCore.fireLore(st, items, 1700000000000L, jobj("convId" to "c1"))
        val frozen = RpLoreCore.applyFrozenLore(after, items)
        val (_, again) = RpLoreCore.fireLore(after, frozen, 1700000001000L, jobj("convId" to "c1"))
        assertEquals((g("secondPick") as List<*>).size, again.size)
    }

    @Test
    fun noLoreMeansEmptyConstantTextAndNoFire() {
        val st = RpState.normalizeState(jobj("blocks" to emptyList<Any?>(), "settings" to jobj()))
        assertEquals("", RpLoreCore.constantLoreText(st))
        val (after, fired) = RpLoreCore.fireLore(st, items, 1L, jobj("convId" to "c"))
        assertTrue("没有世界书条目时不该有触发", fired.isEmpty())
        // 旧版即使一条也没触发，也会推进 turn 并写 loreTrace（rpcache.js:716），这里只断言"没冻结任何条目"
        assertNull(after["loreFired"])
    }

    @Test
    fun keywordMatchingFlagsStayStableAcrossCachedCalls() {
        val regex = jobj("useRegex" to true)
        assertTrue(RpLoreCore.matchKey("Lighthouse 7", "light\\s*house", regex))
        assertTrue(RpLoreCore.matchKey("LIGHTHOUSE", "light\\s*house", regex))
        assertFalse(RpLoreCore.matchKey("shore", "[invalid", regex))

        val words = jobj("matchWholeWords" to true)
        assertTrue(RpLoreCore.matchKey("a Cat!", "cat", words))
        assertFalse(RpLoreCore.matchKey("scatter", "cat", words))

        val cases = jobj("caseSensitive" to true)
        assertTrue(RpLoreCore.matchKey("Harbor", "Harbor", cases))
        assertFalse(RpLoreCore.matchKey("Harbor", "harbor", cases))
    }
}
