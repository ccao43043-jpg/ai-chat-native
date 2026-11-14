package com.aichat.app.plugins.rp

import com.aichat.app.domain.Badge
import com.aichat.app.domain.ChatPlugin
import com.aichat.app.domain.MessageState
import com.aichat.app.domain.RequestContext
import com.aichat.app.domain.TurnResult
import com.aichat.app.domain.model.AppSettings
import com.aichat.app.domain.model.Conversation
import com.aichat.app.domain.model.Message
import com.aichat.app.domain.model.Role
import com.aichat.app.plugins.PluginHost
import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.js.jobj
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * RP 上下文构建器（旧版 `www/rp-plugin.js` 的 hooks + `www/rpcache.js` 的纯函数）。
 *
 * 只在对话真的填过 RP 块时生效，其它对话请求逐字节不变。
 *
 * 已实现：分层 system 重排、末条 user 改成「输入 + 增量 + 状态」、状态写回、
 * **可见性三件套**（消息缓存徽章 / 章节徽章、顶栏命中率、覆盖消息变灰）。
 * 还没做：章节摘要生成与合并、预算裁剪、世界书触发、面板。见 `docs/ROADMAP.md`。
 */
class RpCachePlugin @Suppress("UNUSED_PARAMETER") constructor(
    private val host: PluginHost,
    /** 摘要用的「顺手问一次模型」；null = 不生成章节（只做裁剪）。 */
    private val ask: (suspend (Conversation, String) -> String?)? = null,
) : ChatPlugin {

    override val id: String get() = "rpcache"
    override val name: String get() = "RP 缓存优化器"
    override val defaultEnabled: Boolean get() = true
    override val perConversation: Boolean get() = true

    /** 上一轮算好的状态，等 onAfterResponse 落盘。 */
    /** 本轮请求的快照：状态 + 规划时用的原始估算 + provider/model（收尾时拿去校准） */
    private class Pending(val st: JObj, val rawTotal: Int, val provider: String, val model: String)

    private val pending = HashMap<String, Pending>()

    /** RP 类对话自动启用：只有真的填过块才生效，普通对话不受影响。 */
    override fun autoActive(conv: Conversation, settings: AppSettings): Boolean =
        hasAnyBlock(RpState.normalizeState(Js.fromJson(conv.rpState)))

    private fun hasAnyBlock(st: JObj): Boolean {
        val blocks = st["blocks"] as? List<*> ?: return false
        return blocks.any { b -> b is Map<*, *> && Js.str(b["content"]).isNotEmpty() }
    }

    override suspend fun onBeforeRequest(ctx: RequestContext, status: (String?) -> Unit): RequestContext {
        val base = ctx.systemPrompt
        val st0 = RpState.normalizeState(Js.fromJson(ctx.conv.rpState))
        if (!hasAnyBlock(st0)) return ctx

        val st = RpState.syncBase(st0, base)
        val idx = ctx.messages.indexOfLast { it.role == Role.USER }
        if (idx < 0) return ctx

        val items = ctx.messages.mapIndexed { i, m ->
            jobj(
                "id" to (m.srcId ?: "i$i"), "role" to m.role.wire,
                "content" to m.content, "images" to m.images,
            )
        }

        // 世界书：新触发的条目冻结到最后一条 user 消息上（只追加），再把已有冻结记录拼回去。
        // 顺序与旧版一致：先 fire（推进 sticky/cooldown）→ 再 applyFrozen（按锚点追加）→ 再 plan。
        val fireId = items.lastOrNull()?.let { Js.str(it["id"]) } ?: ""
        val (stFired, _) = if (fireId.isNotEmpty() && Js.str(items.last()["role"]) == "user") {
            RpLoreCore.fireLore(st, items, host.now(), jobj("convId" to ctx.conv.id))
        } else st to emptyList()
        val itemsLore = RpLoreCore.applyFrozenLore(stFired, items)

        // 规划：算各层 token、判断超预算；超了就走临时裁剪（只影响本轮，不改 window）
        val calKey = jobj("provider" to ctx.provider.presetId.ifEmpty { ctx.provider.id }, "model" to ctx.model)
        val p0 = RpCacheCore.plan(stFired, base, itemsLore, meta = calKey)

        // 需要章节摘要时先摘要、再按新状态重排（旧版 rp-plugin.js 是同一个顺序：
        // plan → makeSummary → assemble）。摘要失败就按超预算走临时裁剪，绝不静默丢剧情。
        var stBase = stFired
        var summaryFail: String? = null
        if (p0.needSummary && ask != null) {
            val r = summarize(ctx.conv, stFired, p0)
            if (r != null) stBase = r else summaryFail = "summary_failed"
        }
        val pRaw = if (stBase !== stFired) RpCacheCore.plan(stBase, base, itemsLore, meta = calKey) else p0

        val (st2, p) = if (pRaw.overBudget) RpCacheCore.applyTempTrim(stBase, pRaw, host.now()) else stBase to pRaw

        // 按 plan 的结果挑消息：被裁掉的整条不发；最后一条 user 用 finalUserContent 改写
        val keepIds = p.hist.map { Js.str(it["id"]) }.toHashSet()
        val inputId = p.input?.let { Js.str(it["id"]) }
        val msgs = ctx.messages.mapIndexedNotNull { i, m ->
            val mid = m.srcId ?: "i$i"
            when {
                mid == inputId -> m.copy(content = RpCacheCore.finalUserContent(st2, m.content))
                keepIds.contains(mid) -> m
                else -> null
            }
        }

        pending[ctx.conv.id] = Pending(st2, (p.tokens["raw_total"] as? Number)?.toInt() ?: 0, ctx.provider.presetId.ifEmpty { ctx.provider.id }, ctx.model)

        val cap = RpCacheCore.capability(ctx.provider.presetId, ctx.provider.baseUrl, ctx.provider.type.wire, ctx.model)
        val trace = jobj(
            "vendor" to cap.vendor,
            "kind" to cap.kind,
            "system" to RpCacheCore.estimateTokens(p.system),
            "history" to RpCacheCore.estimateItems(p.hist.map { m ->
                jobj("role" to m["role"], "content" to m["content"], "images" to m["images"])
            }),
            "total" to intOf(p.tokens["total"]),
            "limit" to p.limit,
            "dropped" to p.dropped,
            "trimmed" to p.trimmed,
            "tempTrim" to p.tempTrim,
            "needSummary" to p.needSummary,
            "summaryReason" to p.summaryReason,
            "summaryFail" to summaryFail,
            "chapters" to RpCacheCore.chaptersOf(st2).size,
        )
        val meta = LinkedHashMap(ctx.meta)
        meta["rpcache"] = Js.toJson(trace)
        return ctx.copy(systemPrompt = p.system, messages = msgs, meta = JsonObject(meta))
    }

    /**
     * 生成一章摘要并提交。返回新状态；失败返回 null（调用方按超预算裁剪兜底）。
     *
     * 与旧版一致的三条硬约束：摘要必须八分区齐全、质量 >= 0.60、失败**不推进窗口**。
     */
    private suspend fun summarize(conv: Conversation, st: JObj, p: RpCacheCore.Plan): JObj? {
        val a = ask ?: return null
        val batch = p.batch
        // 章节总量超上限时走合并：把已有章节（+ 本批）压成一章，否则章节会越积越多、system 越来越长
        if (p.needConsolidate) {
            val prompt = RpChapterCore.buildConsolidatePrompt(
                RpCacheCore.chaptersOf(st),
                batch,
                RpCacheCore.memoryText(st).ifEmpty { null },
            )
            val text = runCatching { a(conv, prompt) }.getOrNull() ?: return null
            val r = RpChapterCore.consolidate(
                st = st,
                content = text,
                batch = batch,
                coveredIds = batch.map { Js.str(it["id"]) },
                at = host.now(),
                rounds = RpCacheCore.roundsOf(p.unsummarized) + RpCacheCore.roundsOf(listOfNotNull(p.input)),
                atRound = RpCacheCore.roundsOf(p.unsummarized) + RpCacheCore.roundsOf(listOfNotNull(p.input)),
            )
            return if (r.ok) r.state else null
        }
        if (batch.isEmpty()) return null
        val prompt = RpChapterCore.buildSummaryPrompt(
            RpCacheCore.chaptersOf(st),
            batch,
            RpCacheCore.memoryText(st).ifEmpty { null },
        )
        val text = runCatching { a(conv, prompt) }.getOrNull() ?: return null
        return commit(st, p, text)
    }

    private fun commit(st: JObj, p: RpCacheCore.Plan, text: String): JObj? {
        val covered = p.batch.map { Js.str(it["id"]) }
        val r = RpChapterCore.commitChapter(
            st = st,
            content = text,
            batch = p.batch,
            coveredIds = covered,
            at = host.now(),
            rounds = RpCacheCore.roundsOf(p.batch),
            atRound = RpCacheCore.roundsOf(p.unsummarized) + RpCacheCore.roundsOf(listOfNotNull(p.input)),
        )
        return if (r.ok) r.state else null
    }

    private fun intOf(v: Any?): Int = (v as? Number)?.toInt() ?: 0

    override fun onAfterResponse(result: TurnResult, ctx: RequestContext): Conversation? {
        val snap = pending.remove(ctx.conv.id)
        val u = result.usage
        // 用服务商返回的真实 prompt_tokens 校准估算（按 provider/model 隔离），下一轮 plan 就用新系数
        val st = snap?.let { s ->
            val prompt = u?.prompt ?: 0L
            if (prompt > 0L) {
                RpCacheCore.calibrate(
                    s.st,
                    s.rawTotal,
                    prompt,
                    jobj("provider" to s.provider, "model" to s.model),
                )
            } else {
                s.st
            }
        }
        val prevRates = convRates(ctx.conv)
        val rate = hitRate(u?.hit, u?.prompt)
        val supported = u != null && u.cacheSupported != false && u.hit != null && (u.prompt ?: 0L) > 0

        val cache = if (u == null) null else jobj(
            "rate" to rate,
            "hit" to u.hit,
            "miss" to u.miss,
            "prompt" to u.prompt,
            "supported" to supported,
            // 与旧版同一判据：前 5 次都支持统计的请求取均值，均值 ≥50%、本次比均值低 30 个点且不足其 60% 才算异常
            "anomaly" to isAnomaly(prevRates, rate, supported),
            "reason" to null,
            "req" to null,
        )

        if (st == null && cache == null) return null
        // 引擎可能已经把这条回复加进对话、也可能还没加（不同收尾路径），两种都接住
        val msgs: List<Message> = if (cache == null) ctx.conv.messages else {
            val tagged = result.assistant.copy(
                extras = JsonObject(result.assistant.extras + ("rpCache" to Js.toJson(cache))),
            )
            val at = ctx.conv.messages.indexOfFirst { it.id == result.assistant.id }
            if (at >= 0) ArrayList(ctx.conv.messages).also { it[at] = tagged } else ctx.conv.messages + tagged
        }
        return ctx.conv.copy(
            messages = msgs,
            rpState = st?.let { Js.toJson(it) as? JsonObject } ?: ctx.conv.rpState,
        )
    }

    // ---------------- 可见性（旧版 messageBadges / headerBadges / messageState） ----------------

    override fun messageBadges(msg: Message, conv: Conversation): List<Badge> {
        if (msg.role != Role.ASSISTANT) return emptyList()
        val c = cacheOf(msg) ?: return emptyList()
        val out = ArrayList<Badge>(2)
        val anomaly = boolOf(c["anomaly"]) == true
        val rate = numOf(c["rate"])
        val supported = boolOf(c["supported"]) == true
        if (!supported) {
            out += Badge("", "缓存 不支持统计", "info", "该服务商未返回缓存字段")
        } else {
            out += Badge(
                "", (if (anomaly) "⚠ " else "") + "缓存 " + pct(rate),
                if (anomaly) "bad" else tone(rate),
                "缓存命中 ${numOf(c["hit"])?.toLong() ?: 0} / 输入 ${numOf(c["prompt"])?.toLong() ?: 0} Token",
            )
        }
        val no = numOf(c["summaryNo"])?.toInt() ?: 0
        if (no > 0) {
            val merged = boolOf(c["merged"]) == true
            out += Badge("", (if (merged) "📚 合并 " else "📚 章节 ") + RpCacheCore.slabel(no), "info",
                if (merged) "本次请求前合并了全部章节" else "本次请求前追加了新章节")
        }
        return out
    }

    override fun headerBadges(conv: Conversation): List<Badge> {
        val last = conv.messages.lastOrNull { it.role == Role.ASSISTANT && cacheOf(it) != null } ?: return emptyList()
        val c = cacheOf(last) ?: return emptyList()
        if (boolOf(c["supported"]) != true) return emptyList()
        val rate = numOf(c["rate"]) ?: return emptyList()
        val st = RpState.normalizeState(Js.fromJson(conv.rpState))
        val cap = numOf((st["settings"] as? Map<*, *>)?.get("max_context"))?.toLong() ?: 0L
        val used = numOf(c["prompt"])?.toLong() ?: 0L
        val detail = buildString {
            append("缓存命中率（上一轮 ").append(pct(rate)).append("）")
            if (cap > 0 && used > 0) append("\n上下文 ").append(compact(used)).append(" / ").append(compact(cap))
        }
        return listOf(Badge("", "命中 " + pct(rate), tone(rate), detail))
    }

    override fun messageState(msg: Message, conv: Conversation): MessageState? {
        val st = RpState.normalizeState(Js.fromJson(conv.rpState))
        if (!hasAnyBlock(st)) return null

        val covered = coveredIds(st)
        if (msg.id in covered) {
            val count = conv.messages.count { it.id in covered }
            val chapters = RpCacheCore.chaptersOf(st).map { RpCacheCore.slabel(it["no"]) }.joinToString("、")
            return MessageState(dim = true, label = "↑ 以上 $count 条已写入章节 $chapters，不再发送（原文保留）")
        }

        val startId = (st["window"] as? Map<*, *>)?.get("startId") as? String
        if (startId.isNullOrEmpty()) return null
        val eligible = conv.messages.filter {
            it.error == null && it.extras["imageGen"] == null && it.extras["pending"] == null && it.id !in covered
        }
        val i = eligible.indexOfFirst { it.id == startId }
        val j = eligible.indexOfFirst { it.id == msg.id }
        if (i > 0 && j in 0 until i) {
            return MessageState(
                dim = true, light = true,
                label = if (j == i - 1) "↑ 以上超出预算，暂不发送（原文保留，建议开启自动摘要）" else null,
            )
        }
        return null
    }

    // ---------------- 小工具 ----------------

    private fun cacheOf(m: Message): JsonObject? = m.extras["rpCache"] as? JsonObject

    private fun convRates(conv: Conversation): List<Double> =
        conv.messages.filter { it.role == Role.ASSISTANT }
            .mapNotNull { cacheOf(it) }
            .filter { boolOf(it["supported"]) == true }
            .mapNotNull { numOf(it["rate"]) }
            .takeLast(5)

    private fun hitRate(hit: Long?, prompt: Long?): Double? {
        if (hit == null || prompt == null || prompt <= 0L) return null
        return hit.toDouble() / prompt.toDouble()
    }

    private fun isAnomaly(prev: List<Double>, rate: Double?, supported: Boolean): Boolean {
        if (!supported || rate == null || prev.size < 2) return false
        val avg = prev.average()
        return avg >= 0.5 && rate < avg - 0.3 && rate < avg * 0.6
    }

    private fun coveredIds(st: JObj): Set<String> {
        val out = HashSet<String>()
        RpCacheCore.chaptersOf(st).forEach { c ->
            (c["coveredIds"] as? List<*>)?.forEach { id -> (id as? String)?.let { out += it } }
        }
        return out
    }

    private fun numOf(v: Any?): Double? = when (v) {
        is JsonPrimitive -> v.doubleOrNull ?: v.content.toDoubleOrNull()
        is Number -> v.toDouble()
        else -> null
    }

    private fun boolOf(v: Any?): Boolean? = when (v) {
        is JsonPrimitive -> v.booleanOrNull
        is Boolean -> v
        else -> null
    }

    /** 与旧版 pct 一致：≥99.95% 或 0 取整，其余一位小数。 */
    private fun pct(v: Double?): String {
        if (v == null) return "—"
        val n = v * 100
        return if (v >= 0.9995 || v == 0.0) "${n.toInt()}%" else String.format("%.1f%%", n)
    }

    private fun tone(r: Double?): String = when {
        r == null -> "info"
        r >= 0.7 -> "ok"
        r >= 0.3 -> "warn"
        else -> "bad"
    }

    private fun compact(n: Long): String = when {
        n >= 1_000_000 -> String.format("%.2fM", n / 1_000_000.0)
        n >= 1_000 -> String.format("%.1fk", n / 1_000.0)
        else -> n.toString()
    }
    /** 账本（`rpState.ledger`）转成纯函数层用的 List<JObj>。 */
    private fun ledgerOf(st: JObj): List<JObj>? {
        val raw = st["ledger"] as? List<*> ?: return null
        return raw.mapNotNull { it as? JObj }.takeIf { it.isNotEmpty() }
    }
}
