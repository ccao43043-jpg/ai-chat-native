package com.aichat.app.plugins.rp

import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.js.jobj

/**
 * 世界书（lorebook）触发：对应 `rpcache.js` 的 `activateLore` / `fireLore` / `applyFrozenLore` /
 * `constantLoreText` / `selectConstantLore` 这一组。
 *
 * 语义与旧版一致：
 * - `mode=constant` 的条目进 system 的 `[LOREBOOK]` 段（受 `constantBudgetPct` 约束）；
 * - `mode=trigger` 的条目按 `keys` 命中最近的 `scanDepth` 条消息后**冻结到锚点消息上**
 *   （`[LORE_TRIGGERED 名字]` 追加在那条 user 消息末尾）—— 冻结而不是每轮重算，历史前缀才稳定、缓存才命中；
 * - `sticky` / `cooldown` / `delay` / `probability` / `inclusionGroup` 都按 ST 语义实现；
 * - 概率用 `cyrb53(convId \\0 anchorId \\0 entryId)` 播种的 mulberry32，**同一条消息重新生成结果一致**。
 */
object RpLoreCore {

    private val DEFAULT = jobj(
        "scanDepth" to 2.0,
        "recursionDepth" to 0.0,
        "budgetPct" to 0.25,
        "constantBudgetPct" to 0.15,
        "rebuildOnEdit" to false,
    )

    fun loreSettings(st: JObj?): JObj {
        val out = LinkedHashMap<String, Any?>(DEFAULT)
        val s = st?.get("settings") as? JObj
        val l = s?.get("lore") as? JObj ?: return out
        out.putAll(l)
        return out
    }

    fun loreOf(st: JObj?): List<JObj> = (st?.get("lore") as? List<*>)?.filterIsInstance<JObj>() ?: emptyList()

    fun firedOf(st: JObj?): List<JObj> = (st?.get("loreFired") as? List<*>)?.filterIsInstance<JObj>() ?: emptyList()

    private fun d(v: Any?): Double = when (v) {
        null -> 0.0
        is Double -> v
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }

    private fun i(v: Any?): Int = when (v) {
        null -> 0
        is Number -> v.toInt()
        is String -> v.toIntOrNull() ?: 0
        else -> 0
    }

    private fun s(v: Any?): String = when (v) {
        null -> ""
        is String -> v
        else -> Js.str(v)
    }

    fun estimateLoreTokens(e: JObj): Int =
        RpCacheCore.estimateTokens(s(e["name"]) + "\n" + s(e["content"]))

    // ------------------------------------------------------------------ 确定性 RNG

    private fun imul(a: Int, b: Int): Int = (a.toLong() * b.toLong()).toInt()

    private fun ushr(x: Int, n: Int): Int = ((x.toLong() and 0xFFFFFFFFL) ushr n).toInt()

    /** 与 JS 的 `cyrb53` 逐位一致（返回 14 位十六进制）。 */
    fun cyrb53(str: String, seed: Int = 0): String {
        var h1 = 0xdeadbeef.toInt() xor seed
        var h2 = 0x41c6ce57 xor seed
        for (ch in str) {
            val c = ch.code
            h1 = imul(h1 xor c, 2654435761.toInt())
            h2 = imul(h2 xor c, 1597334677)
        }
        h1 = imul(h1 xor ushr(h1, 16), 2246822507.toInt()) xor imul(h2 xor ushr(h2, 13), 3266489909.toInt())
        h2 = imul(h2 xor ushr(h2, 16), 2246822507.toInt()) xor imul(h1 xor ushr(h1, 13), 3266489909.toInt())
        val v = (h2.toLong() and 2097151L) * 4294967296L + (h1.toLong() and 0xFFFFFFFFL)
        return java.lang.Long.toHexString(v).padStart(14, '0')
    }

    fun seedHash(str: String): Int {
        val hex = cyrb53(str)
        return java.lang.Long.parseLong(hex.takeLast(8), 16).toInt()
    }

    /** mulberry32：闭包状态的确定性随机。 */
    fun mulberry32(seed: Int): () -> Double {
        var a = seed
        return {
            a += 0x6D2B79F5
            var t = imul(a xor ushr(a, 15), 1 or a)
            t = (t + imul(t xor ushr(t, 7), 61 or t)) xor t
            ((t xor ushr(t, 14)).toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
        }
    }

    fun loreSeedKey(convId: String, anchorId: String, entryId: String): String =
        convId + "\u0000" + anchorId + "\u0000" + entryId

    fun rngForSeed(seed: String): () -> Double = mulberry32(seedHash(seed))

    fun rollProbability(p: Any?, rng: () -> Double): Boolean {
        val prob = if (p == null) 100.0 else d(p)
        if (!(prob < 100.0)) return true
        return rng() * 100.0 < prob
    }

    // ------------------------------------------------------------------ 关键词匹配

    private val regexCache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Regex?>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Regex?>?) = size > 256
        },
    )
    private val wordPatternCache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, java.util.regex.Pattern?>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, java.util.regex.Pattern?>?) = size > 256
        },
    )

    private fun cachedRegex(key: String, caseSensitive: Boolean): Regex? {
        val ck = (if (caseSensitive) "1:" else "0:") + key
        synchronized(regexCache) { if (regexCache.containsKey(ck)) return regexCache[ck] }
        val compiled = runCatching {
            Regex(key, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
        }.getOrNull()
        synchronized(regexCache) { regexCache[ck] = compiled }
        return compiled
    }

    private fun cachedWordPattern(key: String, caseSensitive: Boolean): java.util.regex.Pattern? {
        val ck = (if (caseSensitive) "1:" else "0:") + key
        synchronized(wordPatternCache) { if (wordPatternCache.containsKey(ck)) return wordPatternCache[ck] }
        val needle = java.util.regex.Pattern.quote(if (caseSensitive) key else key.lowercase())
        val src = "(?:^|[^\\w\\u4e00-\\u9fff])" + needle + "(?:$|[^\\w\\u4e00-\\u9fff])"
        val flags = if (caseSensitive) 0 else java.util.regex.Pattern.CASE_INSENSITIVE
        val compiled = runCatching { java.util.regex.Pattern.compile(src, flags) }.getOrNull()
        synchronized(wordPatternCache) { wordPatternCache[ck] = compiled }
        return compiled
    }

    fun matchKey(scan: String, key: String, e: JObj): Boolean {
        if (key.isEmpty()) return false
        val caseSensitive = Js.truthy(e["caseSensitive"])
        if (Js.truthy(e["useRegex"])) return cachedRegex(key, caseSensitive)?.containsMatchIn(scan) ?: false
        if (Js.truthy(e["matchWholeWords"])) return cachedWordPattern(key, caseSensitive)?.matcher(scan)?.find() ?: false
        val hay = if (caseSensitive) scan else scan.lowercase()
        val needle = if (caseSensitive) key else key.lowercase()
        return hay.contains(needle)
    }

    fun matchPrimary(scan: String, e: JObj): Boolean =
        (e["keys"] as? List<*>)?.any { matchKey(scan, s(it), e) } ?: false

    fun matchSecondary(scan: String, e: JObj): Boolean {
        val keys = (e["secondaryKeys"] as? List<*>)?.map { s(it) } ?: emptyList()
        if (keys.isEmpty()) return true
        val hits = keys.map { matchKey(scan, it, e) }
        return when (s(e["secondaryLogic"]).ifEmpty { "AND_ANY" }) {
            "AND_ALL" -> hits.all { it }
            "NOT_ANY" -> hits.none { it }
            "NOT_ALL" -> !hits.all { it }
            else -> hits.any { it }
        }
    }

    fun scanWindow(items: List<JObj>, depth: Int): String {
        val n = maxOf(1, depth)
        return items.takeLast(n).joinToString("\n") { s(it["content"]) }
    }

    // ------------------------------------------------------------------ 常驻条目

    fun selectConstantLore(st: JObj?, ctxTokens: Int? = null): List<JObj> {
        val ls = loreSettings(st)
        val all = loreOf(st).filter { s(it["mode"]) == "constant" }
        if (all.isEmpty()) return emptyList()
        val ctx = ctxTokens ?: i((st?.get("settings") as? JObj)?.get("max_context")).let { if (it == 0) 128000 else it }
        val pct = (ls["constantBudgetPct"] as? Number)?.toDouble() ?: 0.15
        val budget = maxOf(200, (ctx * pct).toInt())
        val sorted = all.sortedWith(compareByDescending<JObj> { d(it["priority"]) }.thenBy { d(it["order"]) })
        val out = ArrayList<JObj>()
        var remaining = budget
        for (e in sorted) {
            val t = estimateLoreTokens(e)
            if (t > remaining) continue
            out.add(e); remaining -= t
        }
        return out
    }

    fun constantLoreText(st: JObj?): String {
        val c = selectConstantLore(st)
        if (c.isEmpty()) return ""
        return "[LOREBOOK]\n" + c.joinToString("\n\n") { "# " + s(it["name"]) + "\n" + s(it["content"]) }
    }

    // ------------------------------------------------------------------ 冻结记录

    fun loreBlockText(r: JObj): String = "[LORE_TRIGGERED " + s(r["name"]) + "]\n" + s(r["content"])

    /** 仍然有效（锚点还在上下文里、且没被章节覆盖）的冻结记录。 */
    fun activeFired(st: JObj?, items: List<JObj>): List<JObj> {
        val covered = RpCacheCore.coveredIds(st)
        val ids = items.map { s(it["id"]) }.toHashSet()
        return firedOf(st).filter { r ->
            val anchor = s(r["anchorId"])
            ids.contains(anchor) && !covered.contains(anchor)
        }
    }

    /** 把冻结记录拼回各自锚点消息（顺序 = 触发顺序）。 */
    fun applyFrozenLore(st: JObj?, items: List<JObj>): List<JObj> {
        val act = activeFired(st, items)
        if (act.isEmpty()) return items
        val by = LinkedHashMap<String, MutableList<JObj>>()
        act.forEach { r -> by.getOrPut(s(r["anchorId"])) { ArrayList() }.add(r) }
        return items.map { m ->
            val list = by[s(m["id"])] ?: return@map m
            val parts = ArrayList<String>()
            val c = s(m["content"]); if (c.isNotEmpty()) parts.add(c)
            list.forEach { parts.add(loreBlockText(it)) }
            val nm = LinkedHashMap<String, Any?>(m)
            nm["content"] = parts.joinToString("\n\n")
            nm
        }
    }

    // ------------------------------------------------------------------ 激活

    /**
     * 扫最近 `scanDepth` 条消息，返回本轮该触发的条目（已过 group / budget）。
     * `opts`：`excludeIds` / `turn` / `convId` / `anchorId` / `trace`(可变列表) / `usedBudget` / `remainingBudget`
     */
    fun activateLore(st: JObj?, items: List<JObj>, opts: JObj = jobj()): List<JObj> {
        val ls = loreSettings(st)
        val lore = loreOf(st).filter { s(it["mode"]) == "trigger" && ((it["keys"] as? List<*>)?.isNotEmpty() == true) }
        if (lore.isEmpty() || items.isEmpty()) return emptyList()

        val live = LinkedHashSet<String>()
        (opts["excludeIds"] as? List<*>)?.forEach { live.add(s(it)) }
        val depth0 = i(ls["scanDepth"]).let { if (it == 0) 2 else it }
        var scan = scanWindow(items, depth0)
        val maxDepth = maxOf(depth0, lore.maxOf { i(it["scanDepth"]) })
        if (maxDepth > depth0) scan = scanWindow(items, maxDepth)

        val loreSt = RpState.normalizeLoreState(st?.get("loreState"))
        val turn = if (opts["turn"] != null) i(opts["turn"]) else i(loreSt["turn"])
        val convId = s(opts["convId"])
        val anchorId = s(opts["anchorId"]).ifEmpty { s(items.last()["id"]) }
        val trace = opts["trace"] as? MutableList<JObj>

        fun why(e: JObj, status: String, reason: String) {
            if (trace == null) return
            val id = s(e["id"])
            val r = jobj("id" to id, "name" to (s(e["name"]).ifEmpty { id }), "status" to status, "reason" to reason)
            val idx = trace.indexOfFirst { s(it["id"]) == id }
            if (idx >= 0) trace[idx] = r else trace.add(r)
        }

        val stickyLive = LinkedHashSet<String>()
        val entriesState = loreSt["entries"] as? JObj ?: jobj()
        lore.forEach { e ->
            val es = entriesState[s(e["id"])] as? JObj
            if (es != null && i(es["stickyUntil"]) > turn) stickyLive.add(s(e["id"]))
        }
        stickyLive.forEach { live.add(it) }
        if (trace != null) lore.forEach { e ->
            if (live.contains(s(e["id"]))) why(e, "rejected", if (stickyLive.contains(s(e["id"]))) "sticky_live" else "already_live")
        }

        var activated = ArrayList<JObj>()

        fun runPass(scanText: String): List<JObj> {
            val hit = ArrayList<JObj>()
            for (e in lore) {
                val id = s(e["id"])
                if (live.contains(id) || activated.any { s(it["id"]) == id }) continue
                val es = entriesState[id] as? JObj ?: jobj()
                if (i(e["delay"]) > 0 && turn < i(e["delay"])) { why(e, "rejected", "delay"); continue }
                if (i(es["cooldownUntil"]) > turn && i(es["stickyUntil"]) <= turn) { why(e, "rejected", "cooldown"); continue }
                val depth = i(e["scanDepth"]).let { if (it == 0) depth0 else it }
                val localScan = if (depth == maxDepth) scanText else scanWindow(items, depth)
                if (!matchPrimary(localScan, e)) { why(e, "rejected", "no_key"); continue }
                if (!matchSecondary(localScan, e)) { why(e, "rejected", "secondary"); continue }
                val rng = rngForSeed(loreSeedKey(convId, anchorId, id))
                if (!rollProbability(e["probability"], rng)) { why(e, "rejected", "probability"); continue }
                hit.add(e)
            }
            return hit
        }

        activated = ArrayList(runPass(scan))
        val recMax = if (opts["recursionDepth"] != null) i(opts["recursionDepth"]) else i(ls["recursionDepth"])
        for (pass in 0 until recMax) {
            if (activated.isEmpty()) break
            scan = scan + "\n" + activated.joinToString("\n") { s(it["content"]) }
            val more = runPass(scan)
            if (more.isEmpty()) break
            activated.addAll(more)
        }

        // inclusionGroup：同组只取 priority 最高的一条
        val byGroup = LinkedHashMap<String, JObj>()
        val ung = ArrayList<JObj>()
        activated.forEach { e ->
            val g = s(e["inclusionGroup"])
            if (g.isNotEmpty()) {
                val cur = byGroup[g]
                if (cur == null || d(e["priority"]) > d(cur["priority"])) byGroup[g] = e
            } else ung.add(e)
        }
        if (trace != null) activated.forEach { e ->
            val g = s(e["inclusionGroup"])
            if (g.isNotEmpty() && byGroup[g] !== e) why(e, "rejected", "group")
        }
        activated = ArrayList(ung + byGroup.values)
        activated.sortWith(compareByDescending<JObj> { d(it["priority"]) }.thenBy { d(it["order"]) })

        val ctx = if (opts["ctxTokens"] != null) i(opts["ctxTokens"])
        else i((st?.get("settings") as? JObj)?.get("max_context")).let { if (it == 0) 128000 else it }
        var budget = maxOf(200, (ctx * ((ls["budgetPct"] as? Number)?.toDouble() ?: 0.25)).toInt())
        if (opts["usedBudget"] != null) budget = maxOf(0, budget - i(opts["usedBudget"]))
        var remaining = if (opts["remainingBudget"] != null) i(opts["remainingBudget"]) else budget

        val out = ArrayList<JObj>()
        for (e in activated) {
            val t = estimateLoreTokens(e)
            if (t > remaining) { why(e, "rejected", "budget"); continue }
            out.add(e); remaining -= t; why(e, "fired", "ok")
        }
        return out
    }

    /**
     * 热路径：把本轮新触发的条目冻结到最后一条 user 消息上，并推进 sticky/cooldown 状态机。
     * 返回 `state`（含新的 `loreFired` / `loreState` / `loreTrace`）与本次触发列表。
     */
    fun fireLore(st: JObj, items: List<JObj>, at: Long, opts: JObj = jobj()): Pair<JObj, List<JObj>> {
        val last = items.lastOrNull() ?: return st to emptyList()
        if (s(last["role"]) != "user" || s(last["id"]).isEmpty()) return st to emptyList()
        val active = activeFired(st, items)
        if (active.any { s(it["anchorId"]) == s(last["id"]) }) return st to emptyList()

        val live = active.map { s(it["entryId"]) }.toMutableList()
        val loreSt0 = RpState.normalizeLoreState(st["loreState"])
        val turn = i(loreSt0["turn"]) + 1
        val trace = ArrayList<JObj>()
        val o = LinkedHashMap<String, Any?>(opts)
        o["excludeIds"] = live
        o["turn"] = turn.toDouble()
        o["convId"] = s(opts["convId"])
        o["anchorId"] = s(last["id"])
        o["trace"] = trace
        val picked = activateLore(st, items, JObj(o))

        val injectTokens = picked.sumOf { estimateLoreTokens(it) }
        val ctx = i((st["settings"] as? JObj)?.get("max_context")).let { if (it == 0) 128000 else it }
        val budgetTotal = maxOf(200, (ctx * ((loreSettings(st)["budgetPct"] as? Number)?.toDouble() ?: 0.25)).toInt())
        val loreTrace = jobj(
            "v" to 2.0, "turn" to turn.toDouble(), "anchorId" to s(last["id"]), "at" to at.toDouble(),
            "injectTokens" to injectTokens.toDouble(),
            "budget" to jobj("total" to budgetTotal.toDouble(), "used" to injectTokens.toDouble(),
                "remaining" to maxOf(0, budgetTotal - injectTokens).toDouble()),
            "items" to trace.take(60).map { t ->
                val e = loreOf(st).firstOrNull { s(it["id"]) == s(t["id"]) }
                if (e != null) jobj("id" to t["id"], "name" to t["name"], "status" to t["status"],
                    "reason" to t["reason"], "tokens" to estimateLoreTokens(e).toDouble()) else t
            },
        )

        val entries = LinkedHashMap<String, Any?>()
        (loreSt0["entries"] as? JObj)?.forEach { (k, v) -> entries[k] = v }
        picked.forEach { e ->
            val id = s(e["id"])
            val sticky = i(e["sticky"]); val cooldown = i(e["cooldown"])
            val stickyUntil = if (sticky > 0) turn + sticky else turn
            val cooldownUntil = stickyUntil + (if (cooldown > 0) cooldown else 0)
            entries[id] = jobj(
                "stickyUntil" to stickyUntil.toDouble(),
                "cooldownUntil" to (if (cooldown > 0 || sticky > 0) cooldownUntil else
                    i((entries[id] as? JObj)?.get("cooldownUntil"))).toDouble(),
                "lastAnchorId" to s(last["id"]),
            )
        }
        // 双过期且非 live 的条目删掉，避免状态机无限膨胀
        val keep = LinkedHashMap<String, Any?>()
        entries.forEach { (id, v) ->
            val es = v as? JObj ?: return@forEach
            val expired = i(es["stickyUntil"]) <= turn && i(es["cooldownUntil"]) <= turn
            val alive = picked.any { s(it["id"]) == id } || live.contains(id)
            if (!(expired && !alive)) keep[id] = v
        }
        val loreState = jobj("turn" to turn.toDouble(), "entries" to JObj(keep))

        val fired = picked.map { e ->
            jobj(
                "entryId" to s(e["id"]), "name" to s(e["name"]), "anchorId" to s(last["id"]),
                "content" to s(e["content"]), "hash" to Js.sha256(s(e["content"])),
                "at" to at.toDouble(), "priority" to i(e["priority"]).toDouble(),
            )
        }
        val st1 = LinkedHashMap<String, Any?>(st)
        st1["loreState"] = loreState
        st1["loreTrace"] = loreTrace
        if (fired.isNotEmpty()) {
            val old = firedOf(st).takeLast(200)
            st1["loreFired"] = ArrayList<Any?>(old) + fired
        }
        return JObj(st1) to fired
    }

    /**
     * 世界书文本编辑（对应 rpcache.js 的 setLore）：
     * 解析成条目、记哈希；若已冻结过又改了文本，打 loreEditedWhileFrozen；开了 rebuildOnEdit 就直接清冻结并置 loreNeedsRebuild。
     */
    fun setLore(st: JObj, text: String, at: Long): JObj {
        val prev = s(st["loreTextHash"])
        val hash = Js.sha256(text)
        val next = LinkedHashMap<String, Any?>(st)
        next["lore"] = Lore.parseLore(text)
        next["loreText"] = text
        next["loreTextHash"] = hash
        if (prev.isNotEmpty() && prev != hash && firedOf(st).isNotEmpty()) next["loreEditedWhileFrozen"] = true
        val ls = loreSettings(st)
        if (ls["rebuildOnEdit"] == true && prev.isNotEmpty() && prev != hash) {
            next["loreFired"] = ArrayList<Any?>()
            next["loreEditedWhileFrozen"] = false
            next["loreNeedsRebuild"] = true
        }
        return next
    }

    /** 手动重建：清空本次冻结，下一轮按新文本重新触发 */
    fun rebuildFrozen(st: JObj): JObj {
        val next = LinkedHashMap<String, Any?>(st)
        next["loreFired"] = ArrayList<Any?>()
        next["loreEditedWhileFrozen"] = false
        next["loreNeedsRebuild"] = false
        return next
    }
}
