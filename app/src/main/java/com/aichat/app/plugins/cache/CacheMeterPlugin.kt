package com.aichat.app.plugins.cache

import com.aichat.app.domain.Badge
import com.aichat.app.domain.ChatPlugin
import com.aichat.app.domain.RequestContext
import com.aichat.app.domain.TurnResult
import com.aichat.app.domain.model.Conversation
import com.aichat.app.plugins.PluginHost
import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.js.jarr
import com.aichat.app.plugins.js.jobj
import com.aichat.app.plugins.rp.RpCacheCore
import com.aichat.app.plugins.rp.RpState
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt

/**
 * 缓存体检：**只观测，不改请求**。
 *
 * 做三件事：
 * 1. 每轮决定这个会话走哪条缓存路线（[CachePolicy]），结果写进会话数据；
 * 2. 每轮记一条样本进 [CacheLedger]，并对没命中的轮次归因（换模型 / 空闲太久 / 前缀被改写…）；
 * 3. 用 [PrefixStabilizer] 统计 system prompt 各段的稳定度，给出「期望命中率」与断点建议。
 *
 * 为什么不直接改请求：改请求的活儿已经有 gcache / mmcache 在做，两者改写方式互斥；
 * 这个插件负责**量**和**判断**，把「该不该缓存、为什么没命中」变成可看的数据，
 * 后续调参（TTL、断点位置、模型钉住）才有依据。
 */
class CacheMeterPlugin(private val host: PluginHost) : ChatPlugin {
    override val id: String = ID
    override val name: String = "缓存体检"
    override val defaultEnabled: Boolean = true
    override val perConversation: Boolean = false

    override suspend fun onBeforeRequest(ctx: RequestContext, status: (String?) -> Unit): RequestContext {
        val now = host.now()
        val plan = CachePolicy.pick(ctx.provider, ctx.model, ctx.settings, ctx.conv)
        val state = try {
            RpState.normalizeState(Js.fromJson(ctx.conv.rpState))
        } catch (e: Throwable) {
            null
        }
        val segs = PrefixStabilizer.segsOf(state, ctx.systemPrompt)
        val d = host.convData(ctx.conv, ID)

        val hist = (d["hist"] as? List<*>)?.filterIsInstance<JObj>() ?: emptyList()
        val stability = PrefixStabilizer.stability(hist, segs.map { it.id })
        val lastAt = (d["lastAt"] as? Number)?.toLong() ?: 0L
        val idleSec = if (lastAt > 0L && now > lastAt) (now - lastAt) / 1000.0 else null
        val prevSys = d["lastSys"] as? String ?: ""
        val prevLen = (d["lastLen"] as? Number)?.toInt() ?: 0
        val lcp = when {
            prevSys.isEmpty() -> null
            prevSys == ctx.systemPrompt -> 1.0
            else -> lcpRatio(prevSys, ctx.systemPrompt, prevLen)
        }
        val gaps = ((d["gaps"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() } ?: emptyList())
            .let { if (idleSec != null && idleSec > 0) (it + idleSec).takeLast(8) else it }

        // 这些是「本轮的事实」，响应回来以后要用来结算，先暂存
        d["pending"] = JObj().also {
            it["owner"] = plan.owner
            it["model"] = ctx.model
            it["providerId"] = ctx.provider.id
            it["hash"] = PrefixStabilizer.hashOf(ctx.systemPrompt)
            it["pTokens"] = RpCacheCore.estimateTokens(ctx.systemPrompt)
            it["lcp"] = lcp
            it["idle"] = idleSec
        }
        d["plan"] = plan.toJs()
        d["stab"] = PrefixStabilizer.summary(segs, stability)
        d["gaps"] = jarr(*gaps.toTypedArray())
        d["lastAt"] = now
        d["lastSys"] = ctx.systemPrompt.take(SYS_SNAPSHOT)
        d["lastLen"] = ctx.systemPrompt.length
        d["hist"] = jarr(*PrefixStabilizer.historyPush(hist, segs).toTypedArray())

        // 上下文体检（PLAN-CONTEXT-ARCH §3.3）：把本轮「谁动了哪一段」原样存下来，
        // 面板只读这份快照，不去猜。注意 cachemeter 在 ORDER 里排最后，
        // 所以这里看到的是「所有插件处理完之后」的最终结果。
        d["lastTrace"] = traceJs(ctx.trace)
        d["segments"] = Js.toJson(PrefixStabilizer.summary(segs, stability))

        // 千问/RP 的显式断点由 rpcache 去打，这里只记「打了没有、系统提示词多大」——
        // 没有这份记录，用户只能看到「开了千问显式缓存却毫无变化」，无从判断是没生效还是设定太短。
        val marks = (ctx.meta["rpcacheMarks"] as? JsonObject)?.let { Js.fromJson(it) as? JObj }
        d["marks"] = marks ?: jobj("applied" to false, "count" to 0)
        d["marksSys"] = RpCacheCore.estimateTokens(ctx.systemPrompt)

        return ctx.copy(meta = JsonObject(LinkedHashMap(ctx.meta).also { it["cachePlan"] = Js.toJson(plan.toJs()) }))
    }

    override fun onAfterResponse(result: TurnResult, ctx: RequestContext): Conversation? {
        val d = host.convData(ctx.conv, ID)
        val pending = d["pending"] as? JObj ?: return null
        val owner = pending["owner"] as? String ?: CachePolicy.OWNER_NONE
        val prev = ((d["ledger"] as? List<*>)?.filterIsInstance<JObj>() ?: emptyList())
            .mapNotNull { CacheSample.fromJs(it) }
        val ctxL = LedgerCtx(
            providerId = pending["providerId"] as? String ?: ctx.provider.id,
            model = pending["model"] as? String ?: ctx.model,
            owner = owner,
            prefixHash = pending["hash"] as? String ?: "",
            prefixTokens = (pending["pTokens"] as? Number)?.toLong() ?: 0L,
            lcpRatio = (pending["lcp"] as? Number)?.toDouble(),
            idleSec = (pending["idle"] as? Number)?.toDouble(),
        )
        val s = CacheLedger.sample(result.usage, host.now(), ctxL, prev.lastOrNull())
        if (s != null) {
            d["ledger"] = jarr(*CacheLedger.push(prev, s).map { it.toJs() }.toTypedArray())
            d["last"] = s.toJs()
            d["summary"] = CacheLedger.summary(CacheLedger.push(prev, s))
        }
        d["ttlSuggest"] = suggestTtl(d)
        d["worthWrite"] = worthWriting(ctx, d, s)
        d["ok"] = result.ok
        return null
    }

    override fun headerBadges(conv: Conversation): List<Badge> {
        val d = (conv.extras["plugins"] as? JsonObject)?.get(ID) as? JsonObject ?: return emptyList()
        val m = Js.fromJson(d) as? JObj ?: return emptyList()
        val summary = m["summary"] as? JObj ?: return emptyList()
        val known = (summary["known"] as? Number)?.toInt() ?: 0
        if (known <= 0) return emptyList()
        val rate = ((summary["roll"] as? Number)?.toDouble() ?: 0.0) * 100.0
        val pct = rate.roundToInt()
        val tone = when {
            pct >= 70 -> "ok"
            pct >= 40 -> "warn"
            else -> "bad"
        }
        val top = topReason(summary)
        val detail = buildString {
            append("最近 ").append(known).append(" 轮按 token 加权命中 ").append(pct).append("%")
            append("（累计命中 ").append(summary["hitTokens"]).append(" / 提示 ").append(summary["promptTokens"]).append(" tokens）")
            if (top != null) append("；主要失分：").append(top.second).append(" ×").append(top.third)
            val plan = CachePlan.of(m["plan"] as? JObj)
            if (plan != null) append("；路线：").append(plan.owner).append("（").append(plan.reason).append("）")
            if (plan != null && plan.owner == CachePolicy.OWNER_RPCACHE) {
                val count = ((m["marks"] as? JObj)?.get("count") as? Number)?.toInt() ?: 0
                if (count > 0) append("；断点 ").append(count).append(" 处")
                else append("；断点未打出：system 只有 ").append((m["marksSys"] as? Number)?.toInt() ?: 0)
                    .append(" token（门槛 ").append(CacheMarkers.DEFAULT_MIN_TOKENS).append("）")
            }
        }
        return listOf(Badge(id, "缓存 $pct%", tone, detail, action = ID))
    }

    private fun topReason(summary: JObj): Triple<String, String, Int>? {
        val by = summary["byReason"] as? JObj ?: return null
        val best = by.entries
            .mapNotNull { (k, v) -> (v as? Number)?.toInt()?.let { Triple(k, MissReason.of(k).label, it) } }
            .maxByOrNull { it.third }
        return best
    }

    private fun suggestTtl(d: JObj): Long {
        val gaps = (d["gaps"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() } ?: emptyList()
        return CacheCostModel.suggestedTtlSec(gaps)
    }

    /**
     * 「这段前缀值不值得显式写缓存」：用当前输入价与缓存价算一次净收益。
     * 拿不到价格的模型一律返回 false（宁可不写，也不要为省钱反而多花钱）。
     */
    private fun worthWriting(ctx: RequestContext, d: JObj, s: CacheSample?): Boolean {
        val pTokens = s?.prefixTokens ?: 0L
        if (pTokens <= 0L) return false
        val meta = ctx.provider.modelMeta
        val priceIn = (meta["input"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() ?: return false
        val gaps = (d["gaps"] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() } ?: emptyList()
        val turns = CacheCostModel.expectedTurns(gaps)
        val hit = s?.lcpRatio ?: 0.5
        return CacheCostModel.worthWriting(
            prefixTokens = pTokens.toInt(),
            priceInPerM = priceIn,
            priceCachedPerM = priceIn * ((GCache.PRICE["read"] as? Number)?.toDouble() ?: 0.25),
            writePricePerM = priceIn * ((GCache.PRICE["write"] as? Number)?.toDouble() ?: 1.0),
            hitProb = hit,
            turns = turns,
        )
    }

    private fun lcpRatio(prev: String, cur: String, prevLen: Int): Double {
        val n = minOf(prev.length, cur.length)
        var i = 0
        while (i < n && prev[i] == cur[i]) i++
        if (prevLen > prev.length && i >= prev.length) return 0.95
        val denom = prevLen.coerceAtLeast(1)
        return (i.toDouble() / denom.toDouble()).coerceIn(0.0, 1.0)
    }

    companion object {
        const val ID = "cachemeter"

        /** 只留 4000 字符的前缀快照用于 LCP 比较，避免把整个 system 落进 conv 文件 */
        const val SYS_SNAPSHOT = 4000
    }

    /** ContextTrace → 面板可读的 JS 结构 */
    private fun traceJs(t: com.aichat.app.domain.ContextTrace): JObj = JObj().also { o ->
        o["steps"] = jarr(*t.steps().map { s ->
            jobj(
                "plugin" to s.plugin,
                "slots" to jarr(*s.slots.map { it.code }.toTypedArray()),
                "sys" to s.systemDelta,
                "user" to s.userDelta,
                "note" to s.note,
            ) as Any
        }.toTypedArray())
        o["conflicts"] = jarr(*t.conflicts().toTypedArray())
        o["sysTokens"] = t.systemTokens
        o["userTokens"] = t.lastUserTokens
        o["stableHash"] = t.stableHash
        o["owners"] = JObj().also { m ->
            com.aichat.app.domain.Slot.entries.forEach { s ->
                val owners = t.ownersOf(s)
                if (owners.isNotEmpty()) m[s.code] = owners.joinToString("、")
            }
        }
    }
}
