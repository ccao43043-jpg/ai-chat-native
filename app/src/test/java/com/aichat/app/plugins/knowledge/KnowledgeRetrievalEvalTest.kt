package com.aichat.app.plugins.knowledge

import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.js.jobj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 知识库检索质量评测（改进方案 §4.5.2 第 4 条「评测先行」）：
 * 这里补「同义改写 / 跨条目」类困难用例，作为检索优化的回归门禁。
 *
 * 时间线（2026-11-17）：
 * - 基线（PRF+RRF 前，9 例）：overall MRR = 0.889；同义改写子集 = 0.5（最弱项，印证方案诊断）；
 *   跨条目 = 1.0。
 * - PRF+RRF 落地后：修理例（「修理机械设备」↔「维修蒸汽泵」）修复 ✅；**闭港例（「不让船只进出」↔
 *   「凌晨闭港」）仍是词表鸿沟——lexical 检索无桥可过，需同义词表或向量方案，另立项**。
 * - 同义改写门槛维持 0.5（1/2 例修复）；整体 ≥ 0.85 保持不退步。
 */
class KnowledgeRetrievalEvalTest {

    private fun entry(id: String, title: String, content: String, keywords: String = ""): JObj =
        jobj("id" to id, "title" to title, "content" to content, "keywords" to keywords, "pinned" to false)

    /** 8 条目的测试世界书：覆盖人物 / 地理 / 物品 / 事件，内容用自然叙述（非关键词堆砌） */
    private fun doc(): JObj {
        var d = Knowledge.emptyDoc("eval")
        d = Knowledge.addCategory(d, jobj("id" to "people", "name" to "人物"))
        d = Knowledge.addCategory(d, jobj("id" to "world", "name" to "世界"))
        d = Knowledge.addCategory(d, jobj("id" to "items", "name" to "物品"))
        fun add(cat: String, id: String, title: String, content: String, kw: String = "") {
            d = Knowledge.addEntry(d, cat, jobj("id" to id, "title" to title, "content" to content, "keywords" to kw))
        }
        add("people", "e1", "薇拉", "薇拉是港城机械局的技师，擅长维修蒸汽泵与钟表，右手有一道旧烫伤。", "机械师")
        add("people", "e2", "卡尔文", "卡尔文是港口缉私队的队长，为人固执，左脸有疤痕，讨厌夜航。", "缉私队")
        add("world", "e3", "港城", "港城是北方最大的贸易港，全年多雾，城墙由黑曜岩砌成，东码头凌晨闭港。", "贸易港")
        add("world", "e4", "雾季", "每年梅雨前后是浓雾季，雾期持续六周，灯笼与汽笛成为唯一的导航手段。", "浓雾")
        add("items", "e5", "黄铜怀表", "那块黄铜怀表是薇拉的师父留下的遗物，表盖内侧刻着一句旧体诗。", "遗物")
        add("items", "e6", "升降机", "废料场的升降机年久失修，只有薇拉知道怎么让它在过载时不停摆。", "维修")
        add("world", "e7", "灯塔", "旧灯塔在大雾季停用后改成了气象观测站，守塔人换成了一对沉默的老夫妇。", "观测站")
        add("people", "e8", "老夫妇", "守塔的老夫妇收留过离家出走的薇拉一个月，教她认星图。", "收留")
        return d
    }

    /** 返回 query 的第一名条目 id（searchBM25 通道，limit 5） */
    private fun top1(query: String): String {
        val hits = Knowledge.search(doc(), query, jobj("limit" to 5.0, "mode" to "bm25"))
        val first = hits.firstOrNull() ?: return ""
        return Js.str((first["entry"] as JObj)["id"])
    }

    /** MRR：期望 id 列表里第一个命中的倒数排名，取多例平均 */
    private fun mrr(cases: List<Pair<String, List<String>>>): Double =
        cases.map { (q, expected) ->
            val hits = Knowledge.search(doc(), q, jobj("limit" to 5.0, "mode" to "bm25"))
            val ids = hits.map { Js.str(((it as JObj)["entry"] as JObj)["id"]) }
            val rank = ids.indexOfFirst { it in expected } + 1
            if (rank == 0) 0.0 else 1.0 / rank
        }.average()

    // ---------------- 困难用例（同义改写 / 跨条目 / 字面不匹配） ----------------

    @Test
    fun paraphraseRepairQueryHitsVera() {
        // 同义改写：「修理机械设备」↔「维修蒸汽泵与钟表」——PRF 扩展词（蒸汽/维修…）建立词面桥
        assertEquals("e1", top1("薇拉平时负责修理哪些机械设备？"))
    }

    @Test
    fun paraphrasePortQueryIsALexicalGap() {
        // 已知极限哨兵：「不让船只进出」↔「凌晨闭港」/「港口」↔「港城·贸易港」——零 bigram 重叠，
        // lexical 检索（含 PRF：首轮捞不到就没有反馈信号）无法桥接，需同义词表或向量方案。
        // 若此断言翻转（e3 被命中）= 同义词/向量方案已生效，请同步抬高 paraphrase 门禁。
        val hits = Knowledge.search(doc(), "港口什么时候不让船只进出？", jobj("limit" to 5.0, "mode" to "bm25"))
        val ids = hits.map { Js.str(((it as JObj)["entry"] as JObj)["id"]) }
        assertTrue("词表鸿沟哨兵：e3 当前不可达（同义词方案落地后此断言会失败，属预期信号）", "e3" !in ids)
    }

    @Test
    fun paraphraseMrrGate() {
        // 同义改写门槛：PRF+RRF 修复了修理例（0.5 → 0.75 区间）；闭港例受词表鸿沟限制（见上）
        val v = mrr(listOf(
            "薇拉平时负责修理哪些机械设备？" to listOf("e1"),
            "港口什么时候不让船只进出？" to listOf("e3"),
        ))
        assertTrue("同义改写 MRR=$v ≥ 0.5（1/2 例修复；剩余例需同义词表支持）", v >= 0.5)
    }

    @Test
    fun crossEntityQueryRanksBothRelevantEntries() {
        // 跨条目：答案同时涉及两个人物（师徒关系分在两条），期望两条都排前列
        val mrrVal = mrr(listOf("谁教薇拉看星图？" to listOf("e8", "e1")))
        assertTrue("跨条目 MRR=$mrrVal 基线 1.0", mrrVal >= 1.0)
    }

    @Test
    fun overallMrrBaseline() {
        // 基线集：同义改写 ×4 + 跨条目 ×2 + 直接命中 ×3（直接命中项是「不该退步」的锚）
        val cases = listOf(
            "薇拉平时负责修理哪些机械设备？" to listOf("e1"),          // 同义改写
            "港口什么时候不让船只进出？" to listOf("e3"),              // 同义改写
            "雾一般会持续多长时间？" to listOf("e4"),                  // 同义改写（浓雾≈雾季）
            "灯塔现在被用来做什么？" to listOf("e7"),                  // 同义改写（观测站）
            "谁教薇拉看星图？" to listOf("e8", "e1"),                  // 跨条目
            "怀表上刻着什么？" to listOf("e5"),                        // 跨条目（遗物→师父）
            "薇拉的右手怎么了？" to listOf("e1"),                      // 直接命中锚
            "卡尔文为什么讨厌夜里出航？" to listOf("e2"),              // 直接命中锚（内容直述）
            "废料场的升降机谁会修？" to listOf("e6", "e1"),            // 直接命中锚
        )
        val mrrVal = mrr(cases)
        assertTrue("整体 MRR=$mrrVal ≥ 0.85（PRF+RRF 后不退步）", mrrVal >= 0.85)
        assertTrue("MRR=$mrrVal 不应意外高于 0.95（否则困难集失去区分度）", mrrVal <= 0.95)
    }
}
