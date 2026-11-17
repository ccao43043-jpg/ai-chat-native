package com.aichat.app.plugins.cache

import com.aichat.app.domain.model.Usage
import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.jobj

/**
 * 缓存未命中的归因。
 *
 * 为什么要归因：命中率是个结果指标，只看到「这轮没命中」没法改；能区分「换了模型」「空闲太久」
 * 「前缀被改写」才知道该动哪一根杠杆（钉模型 / 调 TTL / 稳定前缀）。
 */
enum class MissReason(val code: String, val label: String) {
    MODEL_SWITCH("model_switch", "换了模型"),
    PROVIDER_SWITCH("provider_switch", "换了服务商"),
    TTL_EXPIRED("ttl_expired", "空闲超过缓存 TTL"),
    PREFIX_SHRINK("prefix_shrink", "前缀比上轮短"),
    LCP_LOW("lcp_low", "前缀被改写（LCP 过低）"),
    UNKNOWN("unknown", "原因未知"),
    ;

    companion object {
        fun of(code: String?): MissReason = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/**
 * 一条缓存样本 = 一轮请求的缓存结果。
 *
 * `known = false` 表示服务商没回缓存字段（很多三方中转会吞掉 prompt_tokens_details），
 * 这种样本**不进命中率分母**——否则「不知道」会被算成「没命中」，把命中率虚压低。
 */
data class CacheSample(
    val at: Long,
    val providerId: String,
    val model: String,
    val owner: String,
    val prompt: Long,
    val hit: Long,
    val write: Long,
    val known: Boolean,
    val lcpRatio: Double?,
    val idleSec: Double?,
    val prefixHash: String,
    val prefixTokens: Long,
    val reason: String? = null,
) {
    fun toJs(): JObj = jobj(
        "at" to at,
        "providerId" to providerId,
        "model" to model,
        "owner" to owner,
        "prompt" to prompt,
        "hit" to hit,
        "write" to write,
        "known" to known,
        "lcp" to lcpRatio,
        "idle" to idleSec,
        "hash" to prefixHash,
        "pTokens" to prefixTokens,
        "reason" to reason,
    )

    companion object {
        fun fromJs(o: JObj?): CacheSample? {
            if (o == null) return null
            val at = (o["at"] as? Number)?.toLong() ?: return null
            return CacheSample(
                at = at,
                providerId = o["providerId"] as? String ?: "",
                model = o["model"] as? String ?: "",
                owner = o["owner"] as? String ?: CachePolicy.OWNER_NONE,
                prompt = (o["prompt"] as? Number)?.toLong() ?: 0L,
                hit = (o["hit"] as? Number)?.toLong() ?: 0L,
                write = (o["write"] as? Number)?.toLong() ?: 0L,
                known = (o["known"] as? Boolean) ?: false,
                lcpRatio = (o["lcp"] as? Number)?.toDouble(),
                idleSec = (o["idle"] as? Number)?.toDouble(),
                prefixHash = o["hash"] as? String ?: "",
                prefixTokens = (o["pTokens"] as? Number)?.toLong() ?: 0L,
                reason = o["reason"] as? String,
            )
        }
    }
}

/** 记录的上下文（由插件在请求前算好，响应后写成一条样本） */
data class LedgerCtx(
    val providerId: String,
    val model: String,
    val owner: String,
    val prefixHash: String = "",
    val prefixTokens: Long = 0,
    val lcpRatio: Double? = null,
    val idleSec: Double? = null,
)

/**
 * 缓存账本：纯函数，不依赖 Android / 网络，便于单测。
 *
 * 数据流：插件请求前算 [LedgerCtx] → 响应后 [sample] 出一条 → [push] 进会话账本 →
 * UI 用 [summary] / [hitRate] 显示，人用 [attribute] 的结果决定改什么。
 */
object CacheLedger {
    /** 每个会话只留最近 N 条，避免 conv 文件膨胀（每条 ~150 字节） */
    const val MAX_SAMPLES = 40

    /** 与 GCache.TTL_EFF_DEFAULT 对齐；只用于归因判断，不参与决策 */
    const val DEFAULT_TTL_SEC = 240L

    /** LCP 低于这个值就认为「前缀被改写了」 */
    const val LCP_OK = 0.9

    fun sample(usage: Usage?, now: Long, ctx: LedgerCtx, prev: CacheSample?): CacheSample? {
        if (usage == null) return null
        val prompt = usage.prompt ?: 0L
        val hit = usage.hit ?: 0L
        val write = usage.cacheWrite ?: 0L
        // 服务商明说支持缓存、或给了 hit 字段，才算「可判定」样本
        val known = (usage.cacheSupported == true) || (usage.hit != null && prompt > 0L)
        val base = CacheSample(
            at = now,
            providerId = ctx.providerId,
            model = ctx.model,
            owner = ctx.owner,
            prompt = prompt,
            hit = hit,
            write = write,
            known = known,
            lcpRatio = ctx.lcpRatio,
            idleSec = ctx.idleSec,
            prefixHash = ctx.prefixHash,
            prefixTokens = ctx.prefixTokens,
        )
        if (!known || prompt <= 0L || hit >= prompt) return base
        // 首轮没有可比对象：此时"没命中"是正常的（缓存还没建立），不归因，避免每个对话第一轮都被记成 unknown
        if (prev == null) return base
        return base.copy(reason = attribute(base, prev, DEFAULT_TTL_SEC).code)
    }

    /** 归因优先级：换模型 > 换服务商 > 空闲超 TTL > 前缀变短 > LCP 低 > 未知 */
    fun attribute(cur: CacheSample, prev: CacheSample?, ttlSec: Long = DEFAULT_TTL_SEC): MissReason {
        if (prev == null) return MissReason.UNKNOWN
        if (cur.model.isNotEmpty() && prev.model.isNotEmpty() && cur.model != prev.model) return MissReason.MODEL_SWITCH
        if (cur.providerId.isNotEmpty() && prev.providerId.isNotEmpty() && cur.providerId != prev.providerId) return MissReason.PROVIDER_SWITCH
        val idle = cur.idleSec
        if (idle != null && idle > ttlSec) return MissReason.TTL_EXPIRED
        if (cur.prefixTokens > 0L && prev.prefixTokens > 0L && cur.prefixTokens < prev.prefixTokens) return MissReason.PREFIX_SHRINK
        val lcp = cur.lcpRatio
        if (lcp != null && lcp < LCP_OK) return MissReason.LCP_LOW
        return MissReason.UNKNOWN
    }

    /** 按 token 加权（不是按次数）：10 万 token 里命中 6 万 = 0.6，与花钱的口径一致 */
    fun hitRate(samples: List<CacheSample>, n: Int = 8): Double {
        val tail = samples.filter { it.known && it.prompt > 0L }.takeLast(n)
        if (tail.isEmpty()) return 0.0
        val prompt = tail.sumOf { it.prompt }
        if (prompt <= 0L) return 0.0
        return tail.sumOf { it.hit }.toDouble() / prompt.toDouble()
    }

    fun push(list: List<CacheSample>, s: CacheSample): List<CacheSample> {
        val next = list + s
        return if (next.size <= MAX_SAMPLES) next else next.takeLast(MAX_SAMPLES)
    }

    fun summary(samples: List<CacheSample>): JObj {
        val known = samples.filter { it.known && it.prompt > 0L }
        val prompt = known.sumOf { it.prompt }
        val hit = known.sumOf { it.hit }
        val write = known.sumOf { it.write }
        val byReason = LinkedHashMap<String, Int>()
        known.mapNotNull { it.reason }.forEach { byReason[it] = (byReason[it] ?: 0) + 1 }
        val byOwner = LinkedHashMap<String, Int>()
        known.forEach { byOwner[it.owner] = (byOwner[it.owner] ?: 0) + 1 }
        return jobj(
            "requests" to samples.size,
            "known" to known.size,
            "promptTokens" to prompt,
            "hitTokens" to hit,
            "missTokens" to (prompt - hit).coerceAtLeast(0L),
            "writeTokens" to write,
            "hitRate" to if (prompt > 0L) hit.toDouble() / prompt.toDouble() else 0.0,
            "roll" to hitRate(samples),
            "byReason" to JObj(byReason),
            "byOwner" to JObj(byOwner),
            "lastAt" to (samples.lastOrNull()?.at ?: 0L),
        )
    }
}
