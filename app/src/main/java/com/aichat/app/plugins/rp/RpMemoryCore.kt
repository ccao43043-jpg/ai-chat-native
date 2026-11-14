package com.aichat.app.plugins.rp

import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.js.jobj

/**
 * 结构化记忆的纯函数层（旧版 `www/rpmemory.js`）。
 *
 * 只做「算」：关键词、相似度、冲突判定、合并、检索（BM25F + MMR）、注入文本。
 * 不做：调模型抽取（下一 PR 接 `HostProvider`）、账本（ledger）、面板。
 *
 * 所有条目用 `JObj`（JS 语义的 Map）表示，落盘时 `Js.stringify` 即可，字段名与旧版一致。
 */
object RpMemoryCore {

    val TYPES = listOf("fact", "event", "relation", "detail")
    val TYPE_CN = mapOf("fact" to "事实", "event" to "事件", "relation" to "关系", "detail" to "细节")
    const val FILE = "rp-memory.json"
    const val KW_MAX = 48

    /** 默认设置；与旧版 DEFAULTS 一致。 */
    val DEFAULTS = jobj(
        "interval" to 20, "window" to 40, "maxInject" to 8, "pinBudget" to 8,
        "minScore" to 1, "retrieval" to "bm25", "memTokens" to 600, "tau" to 20, "mmrLambda" to 0.7,
    )

    data class Settings(
        val interval: Int, val window: Int, val maxInject: Int, val pinBudget: Int,
        val minScore: Double, val retrieval: String, val memTokens: Int, val tau: Int, val mmrLambda: Double,
    ) {
        fun toJs(): JObj = jobj(
            "interval" to interval, "window" to window, "maxInject" to maxInject, "pinBudget" to pinBudget,
            "minScore" to minScore, "retrieval" to retrieval, "memTokens" to memTokens, "tau" to tau,
            "mmrLambda" to mmrLambda,
        )
    }

    /** 归一化：缺省值 + 夹紧；`retrieval` 只认 `bm25` / `legacy`。 */
    fun settingsOf(cfg: Any?): Settings {
        val o = cfg as? Map<*, *>
        fun num(k: String, def: Int): Int {
            val v = o?.get(k)
            val d = when (v) {
                is Number -> v.toInt()
                is String -> v.toDoubleOrNull()?.toInt()
                else -> null
            } ?: def
            return d
        }
        val interval = num("interval", 20).let { if (it == 0) 20 else it }.coerceIn(5, 200)
        val maxInject = num("maxInject", 0).coerceIn(0, 50)
        val pinBudget = num("pinBudget", 8).coerceIn(0, 50)
        val memTokens = num("memTokens", 600).coerceIn(0, 8000)
        val window = num("window", 40).let { if (it == 0) 40 else it }.coerceIn(interval, 200)
        val retrieval = if (Js.str(o?.get("retrieval")) == "legacy") "legacy" else "bm25"
        return Settings(
            interval = interval, window = window, maxInject = maxInject, pinBudget = pinBudget,
            minScore = when (val v = o?.get("minScore")) {
                is Number -> v.toDouble(); is String -> v.toDoubleOrNull() ?: 1.0; else -> 1.0
            },
            retrieval = retrieval, memTokens = memTokens,
            tau = num("tau", 20), mmrLambda = when (val v = o?.get("mmrLambda")) {
                is Number -> v.toDouble(); is String -> v.toDoubleOrNull() ?: 0.7; else -> 0.7
            },
        )
    }

    private val PUNCT = Regex("[\\s\\p{P}\\p{S}]+")

    fun norm(s: Any?): String = Js.str(s).lowercase().replace(PUNCT, "")

    fun bigrams(s: Any?): Set<String> {
        val n = norm(s)
        val g = LinkedHashSet<String>()
        for (i in 0 until n.length - 1) g.add(n.substring(i, i + 2))
        return g
    }

    /** 2-gram 重合率；两边都为空时退化成「完全相同才算 1」。 */
    fun similarity(a: Any?, b: Any?): Double {
        val A = bigrams(a); val B = bigrams(b)
        if (A.isEmpty() || B.isEmpty()) return if (norm(a) == norm(b)) 1.0 else 0.0
        var inter = 0
        A.forEach { if (it in B) inter++ }
        return inter.toDouble() / minOf(A.size, B.size)
    }

    private val KW_REL = Regex("喜欢|讨厌|爱|恨|朋友|敌人|信任|结盟|决裂|敌对|合作|忠诚|背叛|已婚|未婚")
    private val KW_TIME = Regex("今晚|当天|次日|曾经|现在|之后|之前|当时|目前|\\d{1,2}月|\\d{1,4}年|昨天|今天|明天")
    private val KW_OBJ = Regex("账本|钥匙|信件|照片|剑|刀|戒指|地图|密道|卷轴|护符|盒子|文件|证据")
    private val CN_ENTITY = Regex("^[\\u4e00-\\u9fff]{2,4}$")
    private val EN_ENTITY = Regex("^[A-Za-z][A-Za-z0-9_-]{2,}$")

    fun kwPriority(k: Any?): Int {
        val s = Js.str(k)
        if (KW_REL.containsMatchIn(s)) return 400 + s.length
        if (KW_OBJ.containsMatchIn(s)) return 300 + s.length
        if (KW_TIME.containsMatchIn(s)) return 200 + s.length
        if (CN_ENTITY.matches(s) || EN_ENTITY.matches(s)) return 100 + s.length
        return s.length
    }

    /** 旧版用 `String.prototype.localeCompare` 做末位 tie-break，中文按拼音排；`compareTo` 是码点序，会不一致。 */
    private fun zh(): java.text.Collator = java.text.Collator.getInstance(java.util.Locale.CHINA)

    fun mergeKeywords(a: Any?, b: Any?): List<String> {
        val freq = LinkedHashMap<String, Int>()
        for (list in listOf(a, b)) {
            (list as? List<*>)?.forEach { k ->
                val t = Js.str(k).trim()
                if (t.isNotEmpty()) freq[t] = (freq[t] ?: 0) + 1
            }
        }
        return freq.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenByDescending { kwPriority(it.key) }
                    .thenByDescending { it.key.length }
                    .thenBy(zh()) { it.key },
            )
            .map { it.key }
            .take(KW_MAX)
    }

    fun autoKeywords(text: Any?): List<String> {
        val t = Js.str(text)
        val out = LinkedHashSet<String>()
        Regex("[A-Za-z][A-Za-z0-9_-]{2,}").findAll(t).forEach { out.add(it.value.lowercase()) }
        Regex("[\\u4e00-\\u9fff]{2,}").findAll(t).forEach { m ->
            val seg = m.value
            if (seg.length <= 4) {
                out.add(seg)
            } else {
                // 长中文段：保留 4 字前缀 + 2/3/4 字滑窗（旧版明确不截成前两字）
                out.add(seg.substring(0, 4))
                for (n in 2..4) for (i in 0..seg.length - n) out.add(seg.substring(i, i + n))
            }
        }
        return out.sortedWith(
            compareByDescending<String> { kwPriority(it) }.thenByDescending { it.length }.thenBy(zh()) { it },
        ).take(KW_MAX)
    }

    fun buildExtractPrompt(turns: List<Map<*, *>>, existing: List<Map<*, *>>): String {
        val conv = turns.joinToString("\n") { m ->
            val who = if (Js.str(m["role"]) == "user") "用户" else "角色"
            "$who：${Js.str(m["content"]).take(2000)}"
        }
        val ex = existing.takeLast(40)
            .joinToString("\n") { "- [${Js.str(it["type"])}] ${Js.str(it["text"])}" }
        return "你是角色扮演对话的记忆整理器。从下面的最近对话中提取之后仍然重要的信息，输出 JSON 数组，不要输出其他内容。\n" +
            "每个元素：{\"type\":\"fact|event|relation|detail\",\"text\":\"一句话、第三人称、具体\",\"keywords\":[\"人名/地名/物品等 1-5 个\"]}\n" +
            "fact=稳定事实（身份、设定、喜好）；event=发生的事件；relation=人物关系变化；detail=以后可能用到的细节（约定、物品、伏笔）。\n" +
            "只记录新的或有变化的信息；已有记忆里已经有的不要重复；最多 12 条；没有就输出 []。\n\n" +
            (if (ex.isNotEmpty()) "===== 已有记忆 =====\n$ex\n\n" else "") +
            "===== 最近对话 =====\n$conv"
    }

    /** 宽松解析：去代码块 → 第一个 JSON 数组 → 对象里的 items/memories/entries → 退化到「- [类型] 文本」行。 */
    fun parseExtraction(raw: Any?): List<JObj> {
        val s = Js.str(raw).replace(Regex("```(?:json)?", RegexOption.IGNORE_CASE), "").trim()
        var arr: List<*>? = null
        val a = s.indexOf('['); val b = s.lastIndexOf(']')
        if (a >= 0 && b > a) arr = runCatching { Js.parse(s.substring(a, b + 1)) as? List<*> }.getOrNull()
        if (arr == null) {
            val oa = s.indexOf('{'); val ob = s.lastIndexOf('}')
            if (oa >= 0 && ob > oa) {
                val o = runCatching { Js.parse(s.substring(oa, ob + 1)) as? Map<*, *> }.getOrNull()
                arr = (o?.get("items") ?: o?.get("memories") ?: o?.get("entries")) as? List<*>
            }
        }
        if (arr == null) {
            val re = Regex("^\\s*[-*]\\s*\\[(\\w+|[\\u4e00-\\u9fff]+)]\\s*(.+)$")
            arr = s.split("\n").mapNotNull { line ->
                val m = re.find(line) ?: return@mapNotNull null
                jobj("type" to m.groupValues[1], "text" to m.groupValues[2])
            }
        }
        val rev = mapOf("事实" to "fact", "事件" to "event", "关系" to "relation", "细节" to "detail")
        val items: List<*> = arr ?: emptyList<Any?>()
        return items.mapNotNull { item ->
            val m = when (item) {
                is String -> jobj("text" to item)
                is Map<*, *> -> LinkedHashMap(item as Map<String, Any?>)
                else -> null
            } ?: return@mapNotNull null
            if (Js.str(m["text"]).isEmpty()) return@mapNotNull null
            val rawType = Js.str(m["type"])
            var type = rawType.lowercase()
            rev[rawType]?.let { type = it }
            if (type !in TYPES) type = "detail"
            val text = Js.str(m["text"]).trim().take(300)
            var kw = (m["keywords"] as? List<*>)?.map { Js.str(it).trim() }?.filter { it.isNotEmpty() }?.take(KW_MAX)
            if (kw.isNullOrEmpty()) kw = autoKeywords(text)
            jobj("type" to type, "text" to text, "keywords" to kw)
        }.filter { Js.str(it["text"]).isNotEmpty() }
    }

    val CONFLICT_PAIRS = listOf(
        listOf("喜欢", "讨厌"), listOf("爱", "恨"), listOf("朋友", "敌人"), listOf("信任", "不信任"),
        listOf("结盟", "决裂"), listOf("存活", "死亡"), listOf("活着", "死了"), listOf("同意", "反对"),
        listOf("敌对", "合作"), listOf("忠诚", "背叛"), listOf("在场", "缺席"), listOf("已婚", "未婚"),
        listOf("解锁", "上锁"),
    )

    val REL_FAMILY = linkedMapOf(
        "喜欢" to "affection", "讨厌" to "affection", "爱" to "affection", "恨" to "affection",
        "朋友" to "alliance", "敌人" to "alliance", "结盟" to "alliance", "决裂" to "alliance",
        "敌对" to "alliance", "合作" to "alliance",
        "信任" to "trust", "不信任" to "trust", "忠诚" to "trust", "背叛" to "trust",
        "存活" to "alive", "死亡" to "alive", "活着" to "alive", "死了" to "alive",
        "同意" to "agree", "反对" to "agree",
        "在场" to "presence", "缺席" to "presence",
        "已婚" to "marital", "未婚" to "marital",
        "解锁" to "lock", "上锁" to "lock",
        "住在" to "location", "居住在" to "location", "位于" to "location", "搬到" to "location", "移居" to "location",
    )

    val POLARITY = mapOf(
        "喜欢" to 1, "爱" to 1, "朋友" to 1, "信任" to 1, "结盟" to 1, "合作" to 1, "忠诚" to 1,
        "存活" to 1, "活着" to 1, "同意" to 1, "在场" to 1, "已婚" to 1, "解锁" to 1,
        "讨厌" to -1, "恨" to -1, "敌人" to -1, "不信任" to -1, "决裂" to -1, "敌对" to -1, "背叛" to -1,
        "死亡" to -1, "死了" to -1, "反对" to -1, "缺席" to -1, "未婚" to -1, "上锁" to -1,
        "住在" to 0, "居住在" to 0, "位于" to 0, "搬到" to 0, "移居" to 0,
    )

    data class RelTriple(val subject: String, val obj: String, val predicate: String, val family: String?, val polarity: Int, val raw: String)

    fun extractRelationTriple(text: Any?): RelTriple? {
        val s = Js.str(text).trim()
        if (s.isEmpty()) return null
        for (pred in REL_FAMILY.keys.sortedByDescending { it.length }) {
            val i = s.indexOf(pred)
            if (i < 0) continue
            var subject = s.substring(0, i)
                .replace(Regex("^[关于对与和]"), "")
                .replace(Regex("[的地得了着过]"), "")
                .trim()
            var obj = s.substring(i + pred.length)
                .replace(Regex("^[了着过的]"), "")
                .replace(Regex("[。！？!.].*$"), "")
                .trim()
            subject = subject.replace(Regex("^[\\s，,：:]+|[\\s，,：:]+$"), "")
            obj = obj.replace(Regex("^[\\s，,：:]+|[\\s，,：:]+$"), "")
            if (subject.isEmpty() || subject.length > 16) continue
            if (obj.isEmpty()) obj = "_"
            return RelTriple(norm(subject), norm(obj), pred, REL_FAMILY[pred], POLARITY[pred] ?: 0, s)
        }
        return null
    }

    fun sameEntity(a: String?, b: String?): Boolean {
        if (a.isNullOrEmpty() || b.isNullOrEmpty()) return false
        if (a == b) return true
        return a.length >= 2 && b.length >= 2 && (a.contains(b) || b.contains(a))
    }

    fun detectConflict(a: Any?, b: Any?): Boolean {
        val ta = extractRelationTriple(a); val tb = extractRelationTriple(b)
        if (ta != null && tb != null) {
            return sameEntity(ta.subject, tb.subject) && sameEntity(ta.obj, tb.obj) &&
                ta.family != null && ta.family == tb.family &&
                ta.polarity != 0 && tb.polarity != 0 && ta.polarity != tb.polarity
        }
        val na = norm(a); val nb = norm(b)
        if (na.isEmpty() || nb.isEmpty()) return false
        val neg = Regex("不|没|并非|未|无")
        val sa = Js.str(a); val sb = Js.str(b)
        if (neg.containsMatchIn(sa) != neg.containsMatchIn(sb)) {
            val ca = na.replace(neg, ""); val cb = nb.replace(neg, "")
            if (ca.length >= 2 && cb.length >= 2) {
                val subA = ca.take(4); val subB = cb.take(4)
                if (sameEntity(subA, subB) && (ca.contains(cb) || cb.contains(ca) || similarity(ca, cb) >= 0.85)) return true
            }
        }
        return false
    }

    /** 合并结果；`list` 是合并后的全部条目。 */
    data class MergeResult(val list: List<JObj>, val added: Int, val merged: Int, val conflicts: Int, val superseded: Int) {
        fun toJs(): JObj = jobj(
            "list" to list, "added" to added, "merged" to merged,
            "conflicts" to conflicts, "superseded" to superseded,
        )
    }

    /**
     * 冲突优先于相似：冲突 → 旧条目 `disabled` + `supersededBy`；同向相似 → 合并关键词、取更长文本。
     * `opts` 里支持 `turn`（生效轮次）与 `sourceMsgId`。
     */
    fun mergeEntries(list: List<JObj>?, incoming: List<JObj>?, at: Long, src: String, opts: Map<String, Any?> = emptyMap()): MergeResult {
        val out = (list ?: emptyList()).map { LinkedHashMap<String, Any?>(it) }.toMutableList()
        var added = 0; var merged = 0; var superseded = 0; var conflicts = 0
        var seq = out.maxOfOrNull { (it["seq"] as? Number)?.toInt() ?: 0 } ?: 0
        val turn = (opts["turn"] as? Number)?.toInt()
        val sourceMsgId = opts["sourceMsgId"] as? String
        val suffix = (at % 100_000).toString()

        for (n in incoming ?: emptyList()) {
            val nn = norm(n["text"])
            val type = Js.str(n["type"])
            val conflictHit = out.firstOrNull {
                it["disabled"] != true && it["supersededBy"] == null && Js.str(it["type"]) == type &&
                    detectConflict(it["text"], n["text"])
            }
            if (conflictHit != null) {
                seq++
                val id = "m${seq}_$suffix"
                conflictHit["supersededBy"] = id
                conflictHit["supersededAt"] = at
                conflictHit["disabled"] = true
                if (turn != null && conflictHit["validToTurn"] == null) conflictHit["validToTurn"] = turn
                val neu = LinkedHashMap<String, Any?>()
                neu["id"] = id; neu["seq"] = seq; neu["type"] = type; neu["text"] = n["text"]
                neu["keywords"] = n["keywords"] ?: emptyList<Any?>()
                neu["createdAt"] = at; neu["updatedAt"] = at; neu["source"] = src
                neu["pinned"] = false
                neu["version"] = ((conflictHit["version"] as? Number)?.toInt() ?: 0) + 1
                neu["supersedes"] = conflictHit["id"]
                if (turn != null) { neu["validFromTurn"] = turn; neu["validToTurn"] = null }
                if (sourceMsgId != null) neu["sourceMsgId"] = sourceMsgId
                neu["confidence"] = (n["confidence"] as? Number)?.toDouble()?.let { clamp01(it) } ?: 0.7
                out.add(neu)
                added++; conflicts++; superseded++
                continue
            }
            val hit = out.firstOrNull {
                it["supersededBy"] == null && Js.str(it["type"]) == type &&
                    (norm(it["text"]) == nn || norm(it["text"]).contains(nn) || nn.contains(norm(it["text"])) ||
                        similarity(it["text"], n["text"]) >= 0.8)
            }
            if (hit != null) {
                val nt = Js.str(n["text"])
                if (nt.length > Js.str(hit["text"]).length && hit["edited"] != true) hit["text"] = nt
                hit["keywords"] = mergeKeywords(hit["keywords"], n["keywords"])
                hit["updatedAt"] = at
                merged++
                if (n["confidence"] != null || hit["confidence"] != null) {
                    hit["confidence"] = mergeConfidence(hit["confidence"], n["confidence"] ?: 0.7)
                }
                if (sourceMsgId != null && hit["sourceMsgId"] == null) hit["sourceMsgId"] = sourceMsgId
            } else {
                seq++
                val neu = LinkedHashMap<String, Any?>()
                neu["id"] = "m${seq}_$suffix"; neu["seq"] = seq; neu["type"] = type; neu["text"] = n["text"]
                neu["keywords"] = n["keywords"] ?: emptyList<Any?>()
                neu["createdAt"] = at; neu["updatedAt"] = at; neu["source"] = src
                neu["pinned"] = false; neu["version"] = 1
                if (turn != null) { neu["validFromTurn"] = turn; neu["validToTurn"] = null }
                if (sourceMsgId != null) neu["sourceMsgId"] = sourceMsgId
                neu["confidence"] = (n["confidence"] as? Number)?.toDouble()?.let { clamp01(it) } ?: 0.7
                out.add(neu); added++
            }
        }
        return MergeResult(out, added, merged, conflicts, superseded)
    }

    fun clamp01(x: Any?): Double = ((x as? Number)?.toDouble() ?: 0.0).coerceIn(0.0, 1.0)

    /** 证据合并：1-(1-a)(1-b)，略抬升但不超过 1。 */
    fun mergeConfidence(a: Any?, b: Any?): Double {
        val x = if (a != null) clamp01(a) else 0.5
        val y = if (b != null) clamp01(b) else 0.5
        return clamp01(1 - (1 - x) * (1 - y))
    }

    // ---------------- 检索（BM25F + MMR） ----------------

    private val FIELDS = listOf("text", "keywords")
    private val W = mapOf("text" to 1.0, "keywords" to 1.6)
    private const val K1 = 1.2
    private const val B = 0.75
    private const val OPEN_BOOST = 1.15

    private val TERM_SPLIT = Regex("[\\s\\p{P}\\p{S}]+")

    fun terms(s: Any?): List<String> =
        Js.str(s).lowercase().split(TERM_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }

    data class Index(
        val N: Int,
        val df: MutableMap<String, Int> = HashMap(),
        val postings: MutableMap<String, MutableList<String>> = HashMap(),
        val tf: MutableMap<String, MutableMap<String, MutableMap<String, Int>>> = HashMap(),
        val len: MutableMap<String, MutableMap<String, Int>> = HashMap(),
        val avgLen: MutableMap<String, Double> = HashMap(),
    )

    fun buildIndex(list: List<JObj>): Index {
        val idx = Index(list.size)
        val sum = HashMap<String, Int>()
        val n = HashMap<String, Int>()
        for (e in list) {
            val id = Js.str(e["id"])
            val tf = HashMap<String, MutableMap<String, Int>>()
            val len = HashMap<String, Int>()
            for (f in FIELDS) {
                val raw = if (f == "keywords") (e["keywords"] as? List<*>)?.joinToString(" ") { Js.str(it) } ?: "" else Js.str(e["text"])
                val ts = terms(raw)
                val m = HashMap<String, Int>()
                ts.forEach { t -> m[t] = (m[t] ?: 0) + 1 }
                tf[f] = m
                len[f] = ts.size
                sum[f] = (sum[f] ?: 0) + ts.size
                n[f] = (n[f] ?: 0) + 1
            }
            idx.tf[id] = tf
            idx.len[id] = len
            val seen = HashSet<String>()
            tf.values.forEach { m -> seen.addAll(m.keys) }
            seen.forEach { t ->
                idx.df[t] = (idx.df[t] ?: 0) + 1
                idx.postings.getOrPut(t) { ArrayList() }.add(id)
            }
        }
        for (f in FIELDS) idx.avgLen[f] = if (list.isNotEmpty() && sum[f] != null) (sum[f]!!.toDouble() / list.size).let { if (it == 0.0) 1.0 else it } else 1.0
        return idx
    }

    private fun idf(idx: Index, t: String): Double {
        val df = idx.df[t] ?: 0
        return kotlin.math.ln(1 + (idx.N - df + 0.5) / (df + 0.5))
    }

    fun buildQuery(query: Any?, expand: Map<*, *>?): Map<String, Double> {
        val q = LinkedHashMap<String, Double>()
        fun add(s: Any?, w: Double) { terms(s).forEach { t -> q[t] = (q[t] ?: 0.0) + w } }
        if (expand == null) { add(query, 1.0); return q }
        add(query, 0.6)
        add(expand["context"], 0.3)
        add((expand["pinnedKeywords"] as? List<*>)?.joinToString(" "), 0.1)
        return q
    }

    fun bm25f(idx: Index, Q: Map<String, Double>, e: JObj): Double {
        val id = Js.str(e["id"])
        val tf = idx.tf[id] ?: return 0.0
        var s = 0.0
        Q.forEach { (t, qw) ->
            if ((idx.df[t] ?: 0) == 0) return@forEach
            val i = idf(idx, t)
            FIELDS.forEach { f ->
                val n = tf[f]?.get(t) ?: 0
                if (n == 0) return@forEach
                val L = (idx.len[id]?.get(f) ?: 0).toDouble() / (idx.avgLen[f] ?: 1.0)
                s += qw * (W[f] ?: 1.0) * i * (n * (K1 + 1)) / (n + K1 * (1 - B + B * L))
            }
        }
        return s
    }

    private fun estTokens(e: JObj): Int = kotlin.math.ceil(Js.str(e["text"]).length * 0.7).toInt() + 6

    private fun decay(e: JObj, nowTurn: Int?, tau: Int, maxSeq: Int): Double {
        val turn = (e["turn"] as? Number)?.toInt()
        val d = if (nowTurn != null && turn != null) maxOf(0, nowTurn - turn) else maxOf(0, maxSeq - ((e["seq"] as? Number)?.toInt() ?: 0))
        val boost = if (Js.str(e["status"]) == "open") OPEN_BOOST else 1.0
        return kotlin.math.exp(-d.toDouble() / tau.coerceAtLeast(1)) * boost
    }

    /**
     * 相关记忆：置顶条目独立预算（按 seq 升序取前 `pinBudget` 条），其余按相关性检索；
     * `bm25` 走 BM25F + MMR（λ 默认 0.7）+ token 预算，`legacy` 走关键词打分。
     */
    fun selectRelevant(list: List<JObj>?, query: Any?, cfg: Any?): List<JObj> {
        val o = settingsOf(cfg)
        val q = Js.str(query)
        val active = (list ?: emptyList()).filter { it["disabled"] != true && it["supersededBy"] == null }
        val pinned = active.filter { it["pinned"] == true }
            .sortedBy { (it["seq"] as? Number)?.toInt() ?: 0 }
            .take(o.pinBudget)
        val pinIds = pinned.map { Js.str(it["id"]) }.toHashSet()
        val rest = active.filter { Js.str(it["id"]) !in pinIds }

        val picked = if (o.retrieval == "bm25") {
            selectBm25(rest, q, o)
        } else {
            val qn = norm(q); val qg = bigrams(q)
            val qKeys = autoKeywords(q).map { norm(it) }.filter { it.isNotEmpty() }.toSet()
            rest.map { e ->
                var s0 = 0.0
                (e["keywords"] as? List<*>)?.forEach { k ->
                    val kn = norm(k)
                    if (kn.isNotEmpty()) {
                        if (qn.contains(kn) || (kn.contains(qn) && qn.length >= 2)) s0 += 2
                        else if (qKeys.any { qk -> qk.contains(kn) || kn.contains(qk) }) s0 += 2
                    }
                }
                var ov = 0
                bigrams(e["text"]).forEach { g -> if (g in qg) ov++ }
                s0 += minOf(3.0, ov / 3.0)
                e to s0
            }.filter { it.second >= o.minScore }
                .sortedWith(compareByDescending<Pair<JObj, Double>> { it.second }.thenByDescending { (it.first["seq"] as? Number)?.toInt() ?: 0 })
                .take(o.maxInject).map { it.first }
        }
        return (pinned + picked).sortedBy { (it["seq"] as? Number)?.toInt() ?: 0 }
    }

    private fun selectBm25(active: List<JObj>, query: String, o: Settings): List<JObj> {
        if (active.isEmpty()) return emptyList()
        val idx = buildIndex(active)
        val Q = buildQuery(query, null)
        val maxSeq = active.maxOfOrNull { (it["seq"] as? Number)?.toInt() ?: 0 } ?: 0
        val cands = active.map { e -> e to bm25f(idx, Q, e) }
            .filter { it.second > 0.0 }
            .map { (e, s) -> Triple(e, s * decay(e, null, o.tau, maxSeq), 0.0) }
        if (cands.isEmpty()) return emptyList()
        val top = cands.maxOf { it.second }
        val scored = cands.map { Triple(it.first, it.second, if (top > 0) it.second / top else 0.0) }
        val lam = o.mmrLambda
        val picked = ArrayList<JObj>()
        var budget = o.memTokens
        val left = scored.toMutableList()
        while (left.isNotEmpty() && picked.size < maxOf(o.maxInject, 1) * 4) {
            var best: Triple<JObj, Double, Double>? = null
            var bv = Double.NEGATIVE_INFINITY
            for (x in left) {
                val red = if (picked.isEmpty()) 0.0 else picked.maxOf { y -> similarity(x.first["text"], y["text"]) }
                val v = lam * x.third - (1 - lam) * red
                val xSeq = (x.first["seq"] as? Number)?.toInt() ?: 0
                val bSeq = best?.let { (it.first["seq"] as? Number)?.toInt() ?: 0 } ?: -1
                if (v > bv || (v == bv && xSeq > bSeq)) { bv = v; best = x }
            }
            val b = best ?: break
            left.remove(b)
            val c = estTokens(b.first)
            if (c > budget) continue
            budget -= c
            picked.add(b.first)
        }
        return picked
    }

    fun renderInjection(entries: List<JObj>?): String {
        if (entries.isNullOrEmpty()) return ""
        val body = entries.joinToString("\n") { "- [${TYPE_CN[Js.str(it["type"])] ?: Js.str(it["type"])}] ${Js.str(it["text"])}" }
        return "[MEMORY_RECALL]（相关记忆，只作参考，不要复述）\n$body"
    }

    fun toMemoryText(entries: List<JObj>): String =
        entries.joinToString("\n") { "- [${TYPE_CN[Js.str(it["type"])] ?: Js.str(it["type"])}] ${Js.str(it["text"])}" }

    /** 到了提取点？`turns` = 当前对话的用户轮数。 */
    fun shouldExtract(turns: Int, lastTurn: Any?, interval: Int): Boolean =
        turns - ((lastTurn as? Number)?.toInt() ?: 0) >= interval

    /** 记忆按角色卡隔离（有 personaId 时），否则按对话。 */
    fun charKey(convId: String, personaId: String?): String =
        if (!personaId.isNullOrEmpty()) "persona:$personaId" else "conv:$convId"

    /** 把注入文本追加到消息内容末尾（字符串模型；旧版的多段 content 已在原生侧归一成 String）。 */
    fun appendToContent(content: String?, add: String): String =
        if (content.isNullOrEmpty()) add else "$content\n\n$add"
}
