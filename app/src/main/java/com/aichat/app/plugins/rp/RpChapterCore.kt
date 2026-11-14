package com.aichat.app.plugins.rp

import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.js.jobj

/**
 * 章节摘要的纯函数层（旧版 `www/rpcache.js` 的 buildSummaryPrompt / parseSummary / commitChapter）。
 *
 * 为什么这么严：摘要一旦把「谁不知道什么」写错，模型会立刻越界演出（NPC 说出玩家才知道的事）；
 * 一旦漏掉未解决线索，剧情就永久断了。所以旧版要求**八分区齐全**、五个关键分区不能全空、
 * 质量分 >= 0.60 才允许落章，失败一律不推进窗口（宁可这轮多发历史）。
 */
object RpChapterCore {

    /** 八分区，顺序即提示词里的顺序。 */
    val SECTIONS = listOf(
        "FACTS", "CHARACTER_KNOWLEDGE", "PLAYER_KNOWLEDGE", "SECRET",
        "UNRESOLVED", "RELATIONSHIPS", "OBJECTS", "LOCATION",
    )

    /** 缺任一项就拒提交（勿静默丢剧情边界）。 */
    val CRITICAL = listOf("FACTS", "CHARACTER_KNOWLEDGE", "PLAYER_KNOWLEDGE", "SECRET", "UNRESOLVED")

    /** 统一质量门槛（与旧版 SUMMARY_MIN_QUALITY 一致）。 */
    const val MIN_QUALITY = 0.60

    val FORMAT_RULES = buildString {
        append("严格按以下格式输出，标题保持英文原样，每项用「- 」开头；某项没有内容写「- 无」：\n\n")
        append("FACTS:\n- 已确认的事实\n\n")
        append("CHARACTER_KNOWLEDGE:\n- 角色名：该角色知道什么（每个重要角色一行）\n\n")
        append("PLAYER_KNOWLEDGE:\n- 玩家知道什么\n\n")
        append("SECRET:\n- 尚未公开的秘密（注明对谁保密）\n\n")
        append("UNRESOLVED:\n- 未解决事件\n\n")
        append("RELATIONSHIPS:\n- 人物关系\n\n")
        append("OBJECTS:\n- 关键物品及归属\n\n")
        append("LOCATION:\n- 地点状态\n\n")
        append("只输出摘要正文，不要任何解释。\n\n")
    }

    /** 对话正文：玩家用【玩家】，角色用【角色】，段间空行。 */
    fun transcriptOf(batch: List<JObj>): String = batch.joinToString("\n\n") { m ->
        val who = if (Js.str(m["role"]) == "user") "【玩家】" else "【角色】"
        who + Js.str(m["content"]).trim()
    }

    private fun sectionRe(key: String): Regex {
        val others = SECTIONS.joinToString("|")
        return Regex(
            "(?:^|\\n)\\s*[#*]*\\s*$key\\s*:?\\s*\\n([\\s\\S]*?)(?=(?:\\n\\s*[#*]*\\s*(?:$others)\\b)|\$)",
            RegexOption.IGNORE_CASE,
        )
    }

    fun sectionBody(text: String?, key: String): String =
        sectionRe(key).find(text ?: "")?.groupValues?.get(1)?.trim() ?: ""

    /** 整区只有「无 / none / n/a / nil / 空」也算空 —— 不许靠「- 无」凑分。 */
    fun isEmptySectionBody(body: String?): Boolean {
        val b = (body ?: "").trim()
        if (b.isEmpty()) return true
        val lines = b.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return true
        return lines.all { Regex("^[-–—*•]?\\s*(无|none|n/?a|nil|空)\\s*\$", RegexOption.IGNORE_CASE).matches(it) }
    }

    /** 0..1 的质量分：关键分区 0.14、普通分区 0.06，再按填充数补 <=0.02。 */
    fun scoreSummary(text: String?): Double {
        var score = 0.0
        var filled = 0
        var critFilled = 0
        SECTIONS.forEach { k ->
            val body = sectionBody(text, k)
            if (body.isEmpty() || isEmptySectionBody(body)) return@forEach
            filled++
            if (k in CRITICAL) {
                critFilled++
                score += 0.14
            } else {
                score += 0.06
            }
        }
        // 五个关键分区全空：直接 0（soft 分区写得再好也不放行）
        if (critFilled == 0) return 0.0
        val v = score + minOf(0.2, filled * 0.02)
        return Math.round(v.coerceIn(0.0, 1.0) * 100) / 100.0
    }

    data class Parsed(
        val ok: Boolean,
        val text: String,
        val missing: List<String>,
        val critEmpty: List<String>,
        val quality: Double,
    )

    /** 宽松清洗：去代码块、去掉自带的 `[CHAPTER_SUMMARY]` 标题行、再 canonicalize。 */
    fun cleanSummary(raw: Any?): String {
        var t = Js.str(raw).trim()
        t = t.replace(Regex("^```[a-z]*\\n?", RegexOption.IGNORE_CASE), "").replace(Regex("\\n?```\$"), "")
        t = t.replace(Regex("^\\[?CHAPTER[ _]SUMMARY[^\\n]*\\]?\\s*\\n", RegexOption.IGNORE_CASE), "")
        return RpCacheCore.canonicalize(t)
    }

    fun parseSummary(raw: Any?): Parsed {
        val t = cleanSummary(raw)
        val missing = SECTIONS.filter { !Regex("(^|\\n)\\s*[#*]*\\s*$it\\b", RegexOption.IGNORE_CASE).containsMatchIn(t) }
        val critEmpty = CRITICAL.filter { isEmptySectionBody(sectionBody(t, it)) }
        val quality = scoreSummary(t)
        val ok = t.isNotEmpty() && missing.isEmpty() && quality >= MIN_QUALITY && critEmpty.size < CRITICAL.size
        return Parsed(ok, t, missing, critEmpty, quality)
    }

    /** 新章节提示词：只总结「本批新移出的旧对话」，之前的章节只读参考、不要求复述。 */
    fun buildSummaryPrompt(chapters: List<JObj>, batch: List<JObj>, memory: String?): String {
        val prev = if (chapters.isNotEmpty()) {
            chapters.joinToString("\n\n") { "[" + RpCacheCore.slabel(it["no"]) + "]\n" + Js.str(it["content"]) }
        } else {
            ""
        }
        return buildString {
            append("你是长篇角色扮演（RP）的「章节摘要记录员」。请把「本批旧对话」写成新的一章摘要。")
            append("之前的章节会原样保留并继续提供给模型，新章节将接在它们之后。\n\n")
            append("硬性要求：\n")
            append("1. 只记录本批对话里真实发生、已经确认的内容，不要编造、不要推测剧情走向、不要改写人设。\n")
            append("2. 必须区分「知识边界」：每个角色只知道自己亲眼所见或被告知的事；玩家知道的不等于 NPC 知道的。\n")
            append("3. 之前章节已经写过的内容不要重复；只有被新剧情改变时才写出新的状态（例如「关系：A 与 B 由敌对转为合作」）。\n")
            append("4. 不要写“他们聊了很多”这类空泛总结，写具体的人、事、物、地点、数字。\n")
            append("5. ").append(FORMAT_RULES)
            if (!memory.isNullOrEmpty()) append("===== 长期记忆（只读参考，不要复述） =====\n").append(memory).append("\n\n")
            append("===== 之前的章节（只读参考，不要复述） =====\n").append(prev.ifEmpty { "（无）" }).append("\n\n")
            append("===== 本批旧对话（按原顺序） =====\n").append(transcriptOf(batch))
        }
    }

    /** 合并提示词：章节总量超上限时把全部章节压成一章（会让 L3 之后的缓存重建一次）。 */
    fun buildConsolidatePrompt(chapters: List<JObj>, batch: List<JObj>, memory: String?): String {
        val prev = chapters.joinToString("\n\n") { "[" + RpCacheCore.slabel(it["no"]) + "]\n" + Js.str(it["content"]) }
        return buildString {
            append("你是长篇角色扮演（RP）的「章节摘要记录员」。之前的章节摘要太长了，请把它们")
            if (batch.isNotEmpty()) append("和「本批旧对话」") 
            append("合并压缩成一份完整的章节摘要（它将替代全部旧章节）。\n\n")
            append("硬性要求：\n")
            append("1. 完整保留仍然有效的事实、知识边界、秘密、未解决线索和关键物品；删除已被后续剧情推翻或已经无关紧要的细节。\n")
            append("2. 区分「知识边界」：每个角色只知道自己亲眼所见或被告知的事。\n")
            append("3. 不要编造，不要推测。\n")
            append("4. ").append(FORMAT_RULES)
            if (!memory.isNullOrEmpty()) append("===== 长期记忆（只读参考，不要复述） =====\n").append(memory).append("\n\n")
            append("===== 需要合并的章节（按顺序） =====\n").append(prev.ifEmpty { "（无）" })
            if (batch.isNotEmpty()) append("\n\n===== 本批旧对话（按原顺序） =====\n").append(transcriptOf(batch))
        }
    }

    /** 源指纹：本批消息的 role+content 逐字哈希，用于判断章节是否还对应同一段历史。 */
    fun sourceFp(items: List<JObj>, covered: Collection<String>): String? {
        val set = covered.toSet()
        val parts = items.filter { set.contains(Js.str(it["id"])) }
            .map { Js.str(it["role"]) + ":" + Js.str(it["content"]) }
        return if (parts.isEmpty()) null else "sha256:" + Js.sha256(parts.joinToString("\n"))
    }

    /** 造一章（no = chapterSeq + 1，content 先 canonicalize）。 */
    fun mkChapter(
        st: JObj,
        content: String,
        coveredIds: List<String>,
        at: Long,
        source: String,
        rounds: Int,
        atRound: Int,
        sourceFp: String?,
        quality: Double?,
    ): JObj {
        val no = ((st["chapterSeq"] as? Number)?.toInt() ?: 0) + 1
        val body = RpCacheCore.canonicalize(content)
        return jobj(
            "no" to no,
            "content" to body,
            "hash" to Js.sha256(body),
            "coveredIds" to coveredIds.toList(),
            "at" to at,
            "source" to source,
            "rounds" to rounds,
            "atRound" to atRound,
            "history" to emptyList<Any?>(),
            "mergedFrom" to null,
            "sourceFp" to sourceFp,
            "quality" to quality,
        )
    }

    /** 追加章节：只覆盖本批新移出的历史，之前的章节逐字不变 → 缓存前缀保持。 */
    fun addChapter(st: JObj, chapter: JObj, atRound: Int?): JObj {
        val next = LinkedHashMap<String, Any?>(st)
        val list = ArrayList<JObj>(RpCacheCore.chaptersOf(st))
        list.add(chapter)
        next["chapters"] = list
        next["chapterSeq"] = chapter["no"]
        next["lastSummaryRound"] = atRound ?: ((st["lastSummaryRound"] as? Number)?.toInt() ?: 0)
        next["window"] = jobj("startId" to null, "startOrdinal" to 0)
        next["overflow"] = null
        return clearLoreStateForCovered(next, (chapter["coveredIds"] as? List<*>)?.map { Js.str(it) } ?: emptyList())
    }

    /** 锚点被章节覆盖后，清掉对应条目的 lastAnchorId，允许世界书再次触发。 */
    fun clearLoreStateForCovered(st: JObj, covered: List<String>): JObj {
        if (covered.isEmpty()) return st
        val cov = covered.toSet()
        val ls = (st["loreState"] as? JObj) ?: return st
        val entries = (ls["entries"] as? Map<*, *>) ?: return st
        val kept = LinkedHashMap<String, Any?>()
        entries.forEach { (k, v) ->
            val anchor = Js.str((v as? Map<*, *>)?.get("lastAnchorId"))
            if (anchor.isNotEmpty() && cov.contains(anchor)) return@forEach
            kept[k.toString()] = v
        }
        val next = LinkedHashMap<String, Any?>(st)
        next["loreState"] = jobj("turn" to ((ls["turn"] as? Number)?.toInt() ?: 0), "entries" to kept)
        return next
    }

    data class Commit(
        val ok: Boolean,
        val state: JObj,
        val reason: String?,
        val parsed: Parsed,
        val chapter: JObj?,
    )

    /**
     * 事务提交：解析 → 质量门 → 源指纹 → 落章。失败**不推进窗口**。
     * `force = true` 只用于「合并章节」这种人工/尺寸驱动路径。
     */
    fun commitChapter(
        st: JObj,
        content: String,
        batch: List<JObj>?,
        coveredIds: List<String>,
        at: Long,
        rounds: Int,
        atRound: Int,
        force: Boolean = false,
        source: String = "auto",
    ): Commit {
        val ps = parseSummary(content)
        if (!ps.ok) return Commit(false, st, "invalid_summary", ps, null)
        if (ps.quality < MIN_QUALITY && !force) return Commit(false, st, "low_quality", ps, null)
        val fp = if (batch != null) sourceFp(batch, coveredIds) else null
        val ch = mkChapter(st, ps.text, coveredIds, at, source, rounds, atRound, fp, ps.quality)
        val next = addChapter(st, ch, atRound)
        val list = RpCacheCore.chaptersOf(next)
        return Commit(true, next, null, ps, list.lastOrNull())
    }
}
