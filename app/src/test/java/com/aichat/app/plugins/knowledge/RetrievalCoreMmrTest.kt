package com.aichat.app.plugins.knowledge

import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.js.jobj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * mmr 增量实现（D1）与逐轮重算参考实现的差分对照。
 * 参考实现 = 改造前的原算法（每轮对全部已选重算 max sim），随机输入下两者输出必须完全一致。
 */
class RetrievalCoreMmrTest {

    /** 改造前的 mmr（逐轮重算），含 seed 语义 */
    private fun mmrLegacy(cands: List<JObj>, lam: Double, sim: (JObj, JObj) -> Double, maxN: Int, seed: List<JObj>): MutableList<JObj> {
        val left = ArrayList(cands); val S = ArrayList(seed); val out = ArrayList<JObj>()
        var picked = 0
        while (left.isNotEmpty() && picked < maxN) {
            var best: JObj? = null; var bv = Double.NEGATIVE_INFINITY
            for (x in left) {
                val red = if (S.isNotEmpty()) S.maxOf { y -> sim(x, y) } else 0.0
                val v = lam * Js.toNumber(Js.or(x["score"], 0.0)) - (1 - lam) * red
                if (v > bv) { bv = v; best = x }
            }
            if (best == null) break
            left.removeAt(left.indexOfFirst { it === best }); S += best; out += best; picked++
        }
        return out
    }

    private fun randText(r: Random, len: Int): String {
        val pool = "艾琳卡尔海港灯塔渔村酒馆老板娘身世北方迁移家有关旧城墙梅雨季修补码头凌晨闭港防记录设定落难旅"
        return buildString { repeat(len) { append(pool[r.nextInt(pool.length)]) } }
    }

    private fun randCand(r: Random): JObj =
        jobj("id" to ("c" + r.nextInt(1_000_000)), "score" to r.nextDouble(), "text" to randText(r, 40 + r.nextInt(120)))

    @Test
    fun incrementalMatchesLegacyOnRandomInputs() {
        val r = Random(20261116)
        repeat(30) {
            val n = 1 + r.nextInt(60)
            val cands = List(n) { randCand(r) }
            val seed = List(r.nextInt(4)) { randCand(r) }
            val lam = r.nextDouble()
            val maxN = r.nextInt(n + 3)
            val expected = mmrLegacy(cands, lam, { a, b -> RetrievalCore.jaccard3(Js.or(a["text"], ""), Js.or(b["text"], "")) }, maxN, seed)
            val actual = RetrievalCore.mmr(cands, lam, null, maxN, seed)
            assertEquals("round=$it n=$n seed=${seed.size} lam=$lam maxN=$maxN", expected, actual)
        }
    }

    @Test
    fun edgeCasesMatchLegacy() {
        val r = Random(7)
        // 空 / limit 超量 / 全部入选 / seed 占多数
        val cases: List<Triple<List<JObj>, List<JObj>, Int>> = listOf(
            Triple(emptyList(), emptyList(), 5),
            Triple(List(3) { randCand(r) }, emptyList(), 10),
            Triple(List(5) { randCand(r) }, List(2) { randCand(r) }, 2),
            Triple(List(2) { randCand(r) }, List(5) { randCand(r) }, 8),
        )
        for ((i, c) in cases.withIndex()) {
            val expected = mmrLegacy(c.first, 0.7, { a, b -> RetrievalCore.jaccard3(Js.or(a["text"], ""), Js.or(b["text"], "")) }, c.third, c.second)
            val actual = RetrievalCore.mmr(c.first, 0.7, null, c.third, c.second)
            assertEquals("case=$i", expected, actual)
        }
        assertEquals(emptyList<JObj>(), RetrievalCore.mmr(emptyList(), 0.7, null, null, null))
    }

    @Test
    fun mmrStaysFastAtN60() {
        val r = Random(42)
        val cands = List(60) { randCand(r) }
        val t0 = System.nanoTime()
        RetrievalCore.mmr(cands, 0.7, null, 60, null)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("n=60 耗时 ${ms}ms（桌面应远小于 1s）", ms < 1000)
    }
}
