package com.aichat.app.plugins.cache

import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.js.jarr
import com.aichat.app.plugins.js.jobj
import com.aichat.app.plugins.rp.RpCacheCore

/**
 * 前缀稳定化：把 system prompt 拆成段，统计每段在最近几轮里变没变，
 * 然后回答三个问题——哪段最稳、期望能命中多少、断点该打在哪儿。
 *
 * 缓存只认「从头开始一模一样」的前缀。所以命中率的第一因不是模型、不是金额，
 * 而是**前缀从第几个 token 开始变化**。做法与其事后归因，不如事先让稳定的段排在前面。
 */
object PrefixStabilizer {
    /** 稳定窗口：看最近 8 轮，够短能跟上变化，够长不被单轮噪声带偏 */
    const val WINDOW = 8

    data class Seg(val id: String, val layer: String, val title: String, val tokens: Int, val hash: String)

    /** 用 rpcache 同一套分段（层级/标题都一致），保证「统计的段」和「真正发出去的段」是同一个东西 */
    fun segsOf(state: JObj?, baseSystem: String): List<Seg> {
        val segments = try {
            RpCacheCore.buildSystemSegments(state, baseSystem)
        } catch (e: Throwable) {
            emptyList()
        }
        if (segments.isEmpty()) {
            val text = baseSystem
            return listOf(
                Seg(
                    id = "S1",
                    layer = "L1",
                    title = "system",
                    tokens = RpCacheCore.estimateTokens(text),
                    hash = hashOf(text),
                ),
            )
        }
        return segments.map { s ->
            Seg(
                id = s.layer + ":" + s.title.ifEmpty { s.gl },
                layer = s.layer,
                title = s.title.ifEmpty { s.gl },
                tokens = RpCacheCore.estimateTokens(s.text),
                hash = hashOf(s.text),
            )
        }
    }

    fun hashOf(text: String): String = Js.sha256(text).take(16)

    /** 把这一轮的段快照推进历史（只留键→hash，避免把正文落盘） */
    fun historyPush(hist: List<JObj>, segs: List<Seg>, keep: Int = WINDOW): List<JObj> {
        val snap = JObj()
        segs.forEach { snap[it.id] = it.hash }
        val next = hist + snap
        return if (next.size <= keep) next else next.takeLast(keep)
    }

    /**
     * 每段的稳定度 = 1 - 变化次数 / 比较次数。
     * 「变化次数」按相邻两轮 hash 不同来数；从没变过的段得 1.0。
     */
    fun stability(hist: List<JObj>, ids: Collection<String>): Map<String, Double> {
        if (ids.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Double>()
        ids.forEach { id ->
            var changes = 0
            var compares = 0
            var prev: String? = null
            hist.forEach { snap ->
                val cur = snap[id] as? String
                if (cur != null) {
                    if (prev != null) {
                        compares++
                        if (prev != cur) changes++
                    }
                    prev = cur
                }
            }
            out[id] = if (compares == 0) 1.0 else (compares - changes).toDouble() / compares.toDouble()
        }
        return out
    }

    /** 稳定度高的排前面；稳定度相同保持原有相对顺序（stable sort） */
    fun orderByStability(segs: List<Seg>, stab: Map<String, Double>): List<Seg> =
        segs.sortedByDescending { stab[it.id] ?: 1.0 }

    /**
     * 期望前缀命中率：第 k 段能命中，当且仅当前面所有段都没变。
     * 所以按「排好序之后的前缀连乘」算，而不是各段平均。
     */
    fun expectedLcp(segs: List<Seg>, stab: Map<String, Double>): Double {
        if (segs.isEmpty()) return 0.0
        val total = segs.sumOf { it.tokens }.coerceAtLeast(1)
        var acc = 0.0
        var p = 1.0
        segs.forEach { s ->
            p *= (stab[s.id] ?: 1.0)
            acc += p * s.tokens
        }
        return (acc / total.toDouble()).coerceIn(0.0, 1.0)
    }

    /**
     * 断点该打在哪儿：只打在「整段从来没变过」的段末尾，且最多 [max] 个。
     * 断点越多越贵（Anthropic 每次写缓存都要付 write 价），所以取最稳的、且从头连续的。
     */
    fun breakpoints(segs: List<Seg>, stab: Map<String, Double>, max: Int = 4): List<Int> {
        val out = mutableListOf<Int>()
        segs.forEachIndexed { i, s ->
            if ((stab[s.id] ?: 1.0) >= 0.999) out += i else return out
        }
        return if (out.size <= max) out else out.takeLast(max).sorted()
    }

    /** 给 UI / 账本用的一页摘要 */
    fun summary(segs: List<Seg>, stab: Map<String, Double>): JObj {
        val ordered = orderByStability(segs, stab)
        return jobj(
            "segments" to jarr(
                *ordered.map { s ->
                    jobj(
                        "id" to s.id,
                        "layer" to s.layer,
                        "title" to s.title,
                        "tokens" to s.tokens,
                        "stability" to (stab[s.id] ?: 1.0),
                    )
                }.toTypedArray(),
            ),
            "expectedLcp" to expectedLcp(ordered, stab),
            "breakpoints" to jarr(*breakpoints(ordered, stab).toTypedArray()),
            "volatile" to ordered.count { (stab[it.id] ?: 1.0) < 0.6 },
        )
    }
}

/**
 * 值不值得为这段前缀写缓存（显式缓存服务商才需要回答）。
 *
 * benefit = P(命中) × 前缀 tokens × (输入价 − 缓存命中价) × 期望还能续几轮
 * cost    = 写缓存价 × 前缀 tokens（+ 存储费，量级小到可忽略）
 * 只有 benefit > cost 才值得写——短会话、前缀很短、或价格没差的模型会自动判定「不写」。
 */
object CacheCostModel {
    /** 假设用户还会继续聊多久：30 分钟窗口，按最近间隔折算成轮数 */
    const val HORIZON_SEC = 1800.0
    const val MAX_TURNS = 20

    fun median(xs: List<Double>): Double {
        val v = xs.filter { it > 0.0 }.sorted()
        if (v.isEmpty()) return 0.0
        val mid = v.size / 2
        return if (v.size % 2 == 1) v[mid] else (v[mid - 1] + v[mid]) / 2.0
    }

    fun expectedTurns(gapsSec: List<Double>): Int {
        val gap = median(gapsSec)
        if (gap <= 0.0) return 1
        return (HORIZON_SEC / gap).toInt().coerceIn(1, MAX_TURNS)
    }

    /** 建议 TTL = 1.5 × 典型间隔，夹在 [60, 3600] 秒；没有历史就给 240 秒 */
    fun suggestedTtlSec(gapsSec: List<Double>, minSec: Long = 60L, maxSec: Long = 3600L): Long {
        val gap = median(gapsSec)
        if (gap <= 0.0) return 240L
        return (gap * 1.5).toLong().coerceIn(minSec, maxSec)
    }

    fun worthWriting(
        prefixTokens: Int,
        priceInPerM: Double,
        priceCachedPerM: Double,
        writePricePerM: Double,
        hitProb: Double,
        turns: Int,
    ): Boolean {
        if (prefixTokens <= 0 || turns <= 0) return false
        val perM = prefixTokens.toDouble() / 1_000_000.0
        val benefit = hitProb.coerceIn(0.0, 1.0) * perM * (priceInPerM - priceCachedPerM).coerceAtLeast(0.0) * turns
        val cost = perM * writePricePerM
        return benefit > cost
    }
}
