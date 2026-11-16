package com.aichat.app.plugins.knowledge

import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.js.jobj
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * selectForInject 注入选择的行为测试（D11：置顶哨兵分不得参与归一化）。
 *
 * 场景（改进方案 §4.5.1 know_pin3）：置顶 P + 与问题直接相关的 A（与 P 措辞相近）+
 * 弱相关的 B（措辞不同），问题「艾琳在海港城的身世是什么？」，预算 80 token。
 * 修复前：P 的 1e6 分参与 maxS 归一化，A/B 的相关性分被压到 ~1e-5，MMR 只剩
 * 「去冗余」在起作用——与 P 措辞相近的 A 被弱相关但措辞不同的 B 顶掉。
 */
class KnowledgeInjectTest {

    @Suppress("UNCHECKED_CAST")
    private fun idByTitle(doc: JObj, title: String): String {
        for (cat in doc["categories"] as List<*>) for (e in (cat as JObj)["entries"] as List<*>) {
            val en = e as JObj
            if (Js.str(en["title"]) == title) return Js.str(en["id"])
        }
        throw AssertionError("找不到条目：$title")
    }

    private fun doc(pinned: Boolean = true): JObj {
        var d = Knowledge.emptyDoc("w1")
        d = Knowledge.addCategory(d, jobj("id" to "people", "name" to "人物"))
        d = Knowledge.addCategory(d, jobj("id" to "city", "name" to "城市"))
        d = Knowledge.addEntry(d, "people", jobj(
            "title" to "艾琳设定",
            "content" to "艾琳是海港城酒馆的老板娘，收留过很多落难的旅人，身世成谜。",
        ))
        d = Knowledge.addEntry(d, "people", jobj(
            "title" to "艾琳身世",
            "content" to "艾琳出生于北方渔村，幼年时全家因海难迁入海港城，身世与旧王家有关。",
        ))
        d = Knowledge.addEntry(d, "city", jobj(
            "title" to "城防记录",
            "content" to "海港城的城墙每年梅雨季都要修补，东门码头凌晨闭港。",
        ))
        if (pinned) d = Knowledge.setPinned(d, "people", idByTitle(d, "艾琳设定"), true)
        return d
    }

    /** token 估算：content×0.7 + title×0.5 + 8 → P=31、A=34、B=28，预算 80 只装得下两条 */
    @Suppress("UNCHECKED_CAST")
    private fun hitTitles(budget: Double, pinned: Boolean = true): List<String> {
        val d = doc(pinned)
        val sel = Knowledge.selectForInject(d, "艾琳在海港城的身世是什么？", jobj("budget" to budget))
        val hits = sel["hits"] as List<*>
        return hits.map { Js.str(((it as JObj)["entry"] as JObj)["title"]) }
    }

    @Test
    fun pinnedEntryAlwaysInjected() {
        assertTrue("置顶条目必须被注入", "艾琳设定" in hitTitles(80.0))
    }

    @Test
    fun relevantEntryNotDisplacedByPinnedScoreCollapse() {
        val ids = hitTitles(80.0)
        assertTrue("预算 80 时最相关的「艾琳身世」必须被选中（D11）", "艾琳身世" in ids)
        assertFalse("弱相关且措辞不同的「城防记录」在紧预算下应让位", "城防记录" in ids)
    }

    @Test
    fun noPinnedEntriesBehaveAsBefore() {
        val ids = hitTitles(40.0, pinned = false)
        assertTrue("无置顶时最高分条目照常入选", "艾琳身世" in ids)
        assertFalse("无置顶时紧预算下弱相关条目让位（行为不变）", "城防记录" in ids)
    }
}
