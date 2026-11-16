package com.aichat.app.plugins.knowledge

import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.js.jobj
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 知识库检索质量评测（改进方案 §4.5.2 第 4 条「评测先行」）：
 * golden 里的记忆召回已饱和（P@5/MRR = 1，输入是英文/整句），看不出改进空间。
 * 这里补「同义改写 / 跨条目」类困难用例，**建立优化前基线**——
 * 改进 §4.5.2（PRF / RRF / 实体整词）之前，先让指标可信。
 *
 * 基线（2026-11-17 实测，9 例）：overall MRR = 0.889；**同义改写子集 MRR = 0.5（最弱项，
 * 印证方案诊断：bigram 对同义改写吃不到重叠）**；跨条目 = 1.0。
 * 方案目标 overall ≥ 0.75 → 实测已过，真正的改进空间在同义改写子集（目标 1.0）。
 * §4.5.2（PRF / RRF / 实体整词）落地时逐步抬高断言门槛，本测试即回归门禁。
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
    fun paraphraseQueriesFindRightEntries() {
        // 同义改写：query 与条目用词不同，靠语义近似命中（bigram 只能吃到零星重叠）。
        // 当前基线下 top1 常常不是期望条目——这两条先按 MRR 记入基线，§4.5.2 落地后改成硬断言。
        val cases = listOf(
            "薇拉平时负责修理哪些机械设备？" to listOf("e1"),
            "港口什么时候不让船只进出？" to listOf("e3"),
        )
        val v = mrr(cases)
        assertTrue("同义改写 MRR=$v 基线 ≥ 0.5（§4.5.2 落地后逐步抬到 1.0）", v >= 0.5)
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
        assertTrue("整体 MRR=$mrrVal 基线 ≥ 0.85", mrrVal >= 0.85)
        assertTrue("MRR=$mrrVal 不应意外高于 0.95（否则困难集失去区分度）", mrrVal <= 0.95)
    }
}
