package com.aichat.app.plugins.rp

import com.aichat.app.domain.Badge
import com.aichat.app.domain.ChatPlugin
import com.aichat.app.domain.MessageState
import com.aichat.app.domain.RequestContext
import com.aichat.app.domain.Slot
import com.aichat.app.domain.TurnResult
import com.aichat.app.domain.model.AppSettings
import com.aichat.app.domain.model.Conversation
import com.aichat.app.domain.model.Message
import com.aichat.app.domain.model.Role
import com.aichat.app.plugins.PluginHost
import com.aichat.app.plugins.TokenEstimator
import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.cache.CacheMarkers
import com.aichat.app.plugins.cache.CachePolicy
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
    /**
     * L1「已确立事实」账本（结构化记忆里的事实三元组）。
     *
     * 旧版这事没接：账本每次抽取都在写文件，但**从不进请求** —— 模型看不到「上次说定了什么」，
     * 于是隔几轮又问一遍、或者自己编一个。这里由 AppGraph 注入读取逻辑（纯函数层不认识存储）。
     * 它属于低频层（≈ 摘要频率），所以进 system 静态段末尾，不破前缀。
     */
    private val facts: ((Conversation) -> String)? = null,
) : ChatPlugin {

    override val id: String get() = "rpcache"
    override val name: String get() = "RP 缓存优化器"
    override val defaultEnabled: Boolean get() = true
    override val perConversation: Boolean get() = true

    override fun inactiveReason(conv: Conversation, settings: com.aichat.app.domain.model.AppSettings): String =
        "本对话没有 RP 分层设定，也没有世界书条目"
    /** system 前段（模板/角色卡/世界设定）与中段（章节摘要）的唯一所有者 */
    override fun slots(): List<Slot> = listOf(Slot.S1_STABLE, Slot.S2_DELTA, Slot.S3_RECALL)

    /** 事实账本的 token 上限：再多也不是「已确立」，塞进去只会挤掉剧情。 */
    private val FACTS_MAX_TOKENS = 400

    /** 上一轮算好的状态，等 onAfterResponse 落盘。 */
    /** 本轮请求的快照：状态 + 规划时用的原始估算 + provider/model（收尾时拿去校准） */
    private class Pending(val st: JObj, val rawTotal: Int, val provider: String, val model: String)

    private val pending = HashMap<String, Pending>()

    /**
     * RP 类对话自动启用：填过块、或者写过世界书都算。
     *
     * 世界书单独也算「启用」是刻意的偏离：只写世界书、没填任何 RP 块时，
     * 用户预期显然是「关键词命中就该注入」。旧版只认块，会导致这种对话静默不生效。
     */
    override fun autoActive(conv: Conversation, settings: AppSettings): Boolean {
        val st = RpState.normalizeState(Js.fromJson(conv.rpState))
        if (hasAnyBlock(st) || RpLoreCore.loreOf(st).isNotEmpty()) return true
        // 千问显式缓存的 cache_control 断点由本插件打：CachePolicy 判 owner=rpcache 时，
        // 哪怕一个 RP 块都没有也要跑起来把断点打出去 —— 否则策略层说「交给 rpcache」，
        // 实际没有任何插件执行，纯聊天里千问一个断点都拿不到。
        val p = CachePolicy.providerOf(settings, conv) ?: return false
        return CachePolicy.pick(p, conv.model, settings, conv).owner == CachePolicy.OWNER_RPCACHE
    }

    /**
     * 取本对话的事实账本并控制预算：账本本身按 confidence×recency 取前 12 条，
     * 这里再按 token 封顶 [FACTS_MAX_TOKENS]（超了从尾部丢，行粒度，不截半行）。
     */
    private fun factsFor(conv: Conversation): String? {
        val raw = facts?.invoke(conv).orEmpty().trim()
        if (raw.isEmpty()) return null
        if (RpCacheCore.estimateTokens(raw) <= FACTS_MAX_TOKENS) return raw
        val kept = ArrayList<String>()
        var used = 0
        for (line in raw.split("\n")) {
            val t = RpCacheCore.estimateTokens(line) + 1
            if (used + t > FACTS_MAX_TOKENS) break
            kept.add(line)
            used += t
        }
        return if (kept.isEmpty()) null else kept.joinToString("\n")
    }

    private fun hasAnyBlock(st: JObj): Boolean {
        val blocks = st["blocks"] as? List<*> ?: return false
        return blocks.any { b -> b is Map<*, *> && Js.str(b["content"]).isNotEmpty() }
    }

    /** 有块内容、或世界书里有条目（含常驻条目）才值得往下走。 */
    private fun shouldEngage(st: JObj): Boolean =
        hasAnyBlock(st) || RpLoreCore.loreOf(st).isNotEmpty()

    override suspend fun onBeforeRequest(ctx: RequestContext, status: (String?) -> Unit): RequestContext {
        val base = ctx.systemPrompt
        val st0 = RpState.normalizeState(Js.fromJson(ctx.conv.rpState))
        if (!shouldEngage(st0)) return ctx

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
        // provider 与 calib 同源（presetId，自定义服务商回落到 id）。
        // 它决定图片怎么计价：一张 1024×1024 的图 Gemini 真实 258 token、Anthropic 1398，
        // 而旧口径一律按 1000 算 —— 前者导致提前裁剪白丢历史，后者导致该裁不裁撞窗口。
        val provId = ctx.provider.presetId.ifEmpty { ctx.provider.id }
        val calKey = jobj("provider" to provId, "model" to ctx.model)
        val factsText = factsFor(ctx.conv)
        val p0 = RpCacheCore.plan(stFired, base, itemsLore, meta = calKey, provider = provId, facts = factsText)

        // 需要章节摘要时先摘要、再按新状态重排（旧版 rp-plugin.js 是同一个顺序：
        // plan → makeSummary → assemble）。摘要失败就按超预算走临时裁剪，绝不静默丢剧情。
        var stBase = stFired
        var summaryFail: String? = null
        if (p0.needSummary && ask != null) {
            val r = summarize(ctx.conv, stFired, p0)
            if (r != null) stBase = r else summaryFail = "summary_failed"
        }
        val pRaw = if (stBase !== stFired) {
            RpCacheCore.plan(stBase, base, itemsLore, meta = calKey, provider = provId, facts = factsText)
        } else p0

        val (st2, p) = if (pRaw.overBudget) RpCacheCore.applyTempTrim(stBase, pRaw, host.now(), provId) else stBase to pRaw

        // 按 plan 的结果挑消息：被裁掉的整条不发；最后一条 user 用 finalUserContent 改写
        val keepIds = p.hist.map { Js.str(it["id"]) }.toHashSet()
        val inputId = p.input?.let { Js.str(it["id"]) }
        // p.input 可能已带世界书冻结片段（applyFrozenLore 追加在锚点 user 上），
        // finalUserContent 必须基于它改写，否则 [LORE_TRIGGERED …] 会被原内容覆盖
        val inputBase = p.input?.let { Js.str(it["content"]) }.orEmpty()
        val msgs = ctx.messages.mapIndexedNotNull { i, m ->
            val mid = m.srcId ?: "i$i"
            when {
                mid == inputId -> m.copy(content = RpCacheCore.finalUserContent(st2, inputBase.ifEmpty { m.content }))
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
            // 检视面板的历史估算：图片按服务商计价（advisory §10.2②），不再是固定 1000/张
            "history" to TokenEstimator.messages(p.hist.map { m ->
                jobj("role" to m["role"], "content" to m["content"], "images" to m["images"])
            }, ctx.provider.presetId.ifEmpty { ctx.provider.id }),
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
        // 显式缓存断点（旧版 v0.21.3 P6 placeMarkers 的移植）。
        // 只有服务商真支持显式标记才动手；其余情况 opts 与 messages **逐字节不变** ——
        // 缓存前缀一旦被无关插件改动，命中率就归零，而且界面上完全看不出来。
        val histTok = TokenEstimator.messages(p.hist.map { m ->
            jobj("role" to m["role"], "content" to m["content"], "images" to m["images"])
        }, provId)
        val stableTok = RpCacheCore.estimateTokens(RpCacheCore.staticText(st2, base) + RpCacheCore.summaryText(st2))
        val markers = CacheMarkers.plan(
            sysTokens = RpCacheCore.estimateTokens(p.system),
            staticTokens = RpCacheCore.estimateTokens(RpCacheCore.staticText(st2, base)),
            memoryTokens = RpCacheCore.estimateTokens(RpCacheCore.memoryText(st2)),
            summaryTokens = RpCacheCore.estimateTokens(RpCacheCore.summaryText(st2)),
            historyTokens = histTok,
            historyCount = p.hist.size,
            stablePrefixLen = stableTok,
            systemLen = p.system.length,
        )
        val useMarks = CacheMarkers.needsExplicitMarkers(cap.vendor, cap.kind)
        val marked = if (useMarks && markers.historyIndex >= 0) {
            val at = msgs.indexOfLast { it.role.wire == "user" }
            if (at >= 0) msgs.toMutableList().also { list -> list[at] = list[at].copy(cacheMark = true) } else msgs
        } else {
            msgs
        }
        val outOpts = if (useMarks) ctx.opts.copy(cacheControl = markers.system, cacheSystemSplit = markers.systemSplit) else ctx.opts

        val meta = LinkedHashMap(ctx.meta)
        meta["rpcache"] = Js.toJson(trace)
        meta["rpcacheMarks"] = Js.toJson(jobj(
            "vendor" to cap.vendor,
            "kind" to cap.kind,
            "applied" to useMarks,
            "system" to markers.system,
            "split" to markers.systemSplit,
            "history" to (markers.historyIndex >= 0),
            "count" to markers.count,
        ))
        // 结构化分层交给下游（旧版 rp-plugin.js:375 的 `ctx.meta.rpSegments`）。
        // gcache 靠它把 system 拆成「静态段 / 参考段」；这段以前只在 JS 里写过，
        // 原生侧一直没写 —— gcache 的分层分析永远拿到空值，只能整段猜。
        meta["rpSegments"] = Js.toJson(RpCacheCore.buildSystemSegments(st2, base, factsText).map { seg ->
            jobj("layer" to seg.layer, "kind" to seg.kind, "title" to seg.title, "gl" to seg.gl, "text" to seg.text)
        })
        return ctx.copy(systemPrompt = p.system, messages = marked, opts = outOpts, meta = JsonObject(meta))
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

    /** 面板手动操作的结果：新状态 + 章号 + 是否合并 */
    data class ManualChapter(val state: JObj, val no: Int, val merged: Boolean)

    /**
     * 面板上的手动操作：生成下一章 / 合并所有章节。
     *
     * 自动路径只在「超预算或触发阈值」时才跑，用户想提前压缩长篇必须能手动触发；
     * 合并则用来把越积越多的章节压成一章（否则 system 只会越来越长）。
     * 失败时抛带人话的异常交给面板 toast。
     */
    suspend fun summarizeNow(conv: Conversation, settings: AppSettings, merge: Boolean): Result<ManualChapter> =
        runCatching {
            val a = ask ?: throw IllegalStateException("没有可用的服务商或模型")
            val st = RpState.normalizeState(Js.fromJson(conv.rpState) as? JObj ?: JObj())
            val items = conv.messages
                .filter { it.error == null && it.extras["imageGen"] == null && it.extras["pending"] == null }
                .map { m -> jobj("id" to m.id, "role" to m.role.wire, "content" to m.content, "images" to m.images) }
            val base = conv.systemPrompt ?: settings.systemPrompt
            if (merge) {
                val chapters = RpCacheCore.chaptersOf(st)
                if (chapters.size < 2) throw IllegalStateException("只有 ${chapters.size} 章，不需要合并")
                val prompt = RpChapterCore.buildConsolidatePrompt(chapters, emptyList(), RpCacheCore.memoryText(st).ifEmpty { null })
                val text = a(conv, prompt) ?: throw IllegalStateException("模型没有返回内容")
                val r = RpChapterCore.consolidate(
                    st = st,
                    content = text,
                    batch = emptyList(),
                    coveredIds = chapters.flatMap { ch -> (ch["coveredIds"] as? List<*>)?.map { Js.str(it) } ?: emptyList() },
                    at = host.now(),
                    rounds = RpCacheCore.roundsOf(items),
                    atRound = RpCacheCore.roundsOf(items),
                    source = "merge",
                )
                if (!r.ok) throw IllegalStateException("合并被拒绝：${r.reason ?: "invalid"}")
                ManualChapter(r.state, Js.str(r.chapter?.get("no")).toIntOrNull() ?: 0, true)
            } else {
                val p = RpCacheCore.plan(st, base, items, provider = conv.providerId)
                if (p.batch.isEmpty()) throw IllegalStateException("没有可总结的旧对话（保留轮数内的不总结，可在 RP 检视里调小「保留轮数」）")
                val prompt = RpChapterCore.buildSummaryPrompt(
                    RpCacheCore.chaptersOf(st), p.batch, RpCacheCore.memoryText(st).ifEmpty { null },
                )
                val text = a(conv, prompt) ?: throw IllegalStateException("模型没有返回内容")
                val next = commit(st, p, text, source = "manual") ?: throw IllegalStateException("摘要未通过质量检查（八分区不全或内容太短），已丢弃")
                ManualChapter(next, Js.str((RpCacheCore.chaptersOf(next).lastOrNull())?.get("no")).toIntOrNull() ?: 0, false)
            }
        }

    private fun commit(st: JObj, p: RpCacheCore.Plan, text: String, source: String = "auto"): JObj? {
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

    /**
     * 缓存徽章已收口到「缓存体检」（cachemeter）一处，见 PLAN-CONTEXT-ARCH §3.3。
     * 原来这里还有一个「命中 N%」（上一轮口径），和 gcache/mmcache 的滚动口径、cachemeter
     * 的 token 加权口径三套并存，三个数字常打架 → 只保留最诚实的那一个。
     */
    override fun headerBadges(conv: Conversation): List<Badge> = emptyList()

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
