package com.aichat.app.plugins.rp

import com.aichat.app.plugins.TokenEstimator
import com.aichat.app.plugins.affect.Affect
import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.js.jobj
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * rpcache 的纯函数部分（不碰 IO、不碰状态机）：把 JS 版 `www/rpcache.js` 里
 * 「序列化 / 分层 / Token 估算 / 服务商适用性 / 上下文预算 / 请求组装」这几块搬过来。
 *
 * 行为基准是 JS 原实现，改动必须同步 [tools/rpcache-golden/gen-golden.cjs] 的 golden
 * （`app/src/test/resources/golden/rpcache/core.json`）。
 *
 * 尚未移植（见到就说明还没做，别以为漏了）：章节摘要的生成/合并、世界书触发与冻结、
 * langrangian 预算裁剪、指纹与诊断日志、面板 UI。这些不影响「请求长什么样」，
 * 只影响「什么时候摘要」和「界面显示什么」。
 */
object RpCacheCore {

    /** 静态层块定义：id → 输出标签 / gcache 层字母（JS 的 STATIC_DEFS）。 */
    val STATIC_DEFS: List<Triple<String, String, String>> = listOf(
        Triple("world", "WORLD", "B"),
        Triple("character", "CHARACTER", "C"),
        Triple("rules", "RP_RULES", "D"),
        Triple("format", "OUTPUT_FORMAT", "D"),
        Triple("fewshot", "FEW_SHOT", "D"),
        Triple("prohibit", "PROHIBITIONS", "D"),
        Triple("style", "STYLE", "D"),
    )

    private const val DEFAULT_MAX_CONTEXT = 100_000
    private const val DEFAULT_OUTPUT_RESERVE = 12_000
    private const val DEFAULT_SAFETY_MARGIN = 2_000
    private const val DEFAULT_TRIGGER_TOKENS = 30_000
    private const val DEFAULT_CHAPTER_CAP = 12_000

    fun canonicalize(text: Any?): String = Lore.canonicalize(text)

    private fun blockText(st: JObj?, id: String): String {
        val b = RpState.getBlock(st, id) ?: return ""
        return Js.str(b["content"])
    }

    /**
     * @param facts L1「已确立事实」账本（`[MEMORY_LEDGER]` 渲染结果）。它变化频率 ≈ 摘要（每 20 轮一次），
     *   所以放在静态层末尾 —— 改一次只毒化它之后的部分，而不是整个前缀。
     *   为空时逐字节等于 1.5.3 的输出（golden 靠这条保持不变）。
     */
    fun staticText(st: JObj?, baseSystem: Any?, facts: String? = null, affect: Boolean = false): String {
        val parts = ArrayList<String>()
        val b = canonicalize(baseSystem)
        if (b.isNotEmpty()) parts.add(b)
        STATIC_DEFS.forEach { (id, tag, _) ->
            val c = blockText(st, id)
            if (c.isNotEmpty()) parts.add("[$tag]\n$c")
        }
        // 常驻世界书条目属于「几乎不改」的层，排在静态块之后（JS 的 staticText 同序）
        val lore = RpLoreCore.constantLoreText(st)
        if (lore.isNotEmpty()) parts.add(lore)
        // C5：`[AFFECT_RULE]` 是固定文本，以前被 affect 插件追加在 system **最末尾**
        // （长期记忆之后）——那会让分层看到「dynamic 之后又 static」，于是 gcache 的
        // 「在记忆前劈开」分支永不触发，断点只能落整条 system。
        // 收进静态层（lore 之后、facts 之前），层序才保持「变更频率从低到高」。
        if (affect && !b.contains("[AFFECT_RULE]")) parts.add(Affect.protocolText())
        val f = factsText(facts)
        if (f.isNotEmpty()) parts.add(f)
        return parts.joinToString("\n\n")
    }

    /** L1 事实账本段；传入的已经是 `[MEMORY_LEDGER]…` 或纯行文本，这里只补标题、剥重复标题。 */
    fun factsText(facts: String?): String {
        val c = (facts ?: "").trim()
        if (c.isEmpty()) return ""
        val body = c.removePrefix("[MEMORY_LEDGER]").trim()
        return if (body.isEmpty()) "" else "[ESTABLISHED_FACTS]\n$body"
    }

    /** 章节标签：JS 的 `slabel` = "#" + 三位序号。 */
    fun slabel(no: Any?): String {
        val n = Js.i32(no).coerceAtLeast(0)
        return "#" + n.toString().padStart(3, '0')
    }

    fun chaptersOf(st: JObj?): List<JObj> =
        (st?.get("chapters") as? List<*>)?.mapNotNull { it as? JObj } ?: emptyList()

    /** L3：全部章节按顺序拼接；新章节只在末尾追加，前面逐字不变。 */
    fun summaryText(st: JObj?): String = chaptersOf(st)
        .filter { Js.str(it["content"]).isNotEmpty() }
        .joinToString("\n\n") { "[CHAPTER_SUMMARY ${slabel(it["no"])}]\n${Js.str(it["content"])}" }

    fun memoryText(st: JObj?): String {
        val c = blockText(st, "memory")
        return if (c.isEmpty()) "" else "[LONG_TERM_MEMORY]\n$c"
    }

    fun deltaText(st: JObj?): String {
        val c = blockText(st, "delta")
        return if (c.isEmpty()) "" else "[MEMORY_UPDATE]\n$c"
    }

    fun stateText(st: JObj?): String {
        val c = blockText(st, "state")
        return if (c.isEmpty()) "" else "[CURRENT_STATE]\n$c"
    }

    /** C-2：层序按变更频率从低到高，memory 变动不再毒化 summary 缓存。 */
    fun buildSystem(st: JObj?, baseSystem: Any?, facts: String? = null, affect: Boolean = false): String =
        listOf(staticText(st, baseSystem, facts, affect), summaryText(st), memoryText(st))
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")

    /** system 里「static + summary」这一段稳定前缀，供第二个缓存断点使用。 */
    fun systemStablePrefix(st: JObj?, baseSystem: Any?, facts: String? = null, affect: Boolean = false): String =
        listOf(staticText(st, baseSystem, facts, affect), summaryText(st))
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")

    data class Segment(val layer: String, val kind: String, val title: String, val gl: String, val text: String)

    /** 结构化分段：`segments.joinToString("\n\n") == buildSystem`。 */
    fun buildSystemSegments(st: JObj?, baseSystem: Any?, facts: String? = null, affect: Boolean = false): List<Segment> {
        val out = ArrayList<Segment>()
        val b = canonicalize(baseSystem)
        if (b.isNotEmpty()) out.add(Segment("L1", "static", "base", "-", b))
        STATIC_DEFS.forEach { (id, tag, gl) ->
            val c = blockText(st, id)
            if (c.isNotEmpty()) out.add(Segment("L1", "static", tag, gl, "[$tag]\n$c"))
        }
        val sm = summaryText(st)
        val lore = RpLoreCore.constantLoreText(st)
        if (lore.isNotEmpty()) out.add(Segment("L1", "static", "LORE", "D", lore))
        // 与 staticText 同序：AFFECT_RULE 排在 facts 之前（C5）
        if (affect && !b.contains("[AFFECT_RULE]")) out.add(Segment("L1", "static", "AFFECT_RULE", "D", Affect.protocolText()))
        val f = factsText(facts)
        if (f.isNotEmpty()) out.add(Segment("L1", "static", "FACTS", "D", f))
        if (sm.isNotEmpty()) out.add(Segment("L3", "static", "CHAPTER_SUMMARY", "S", sm))
        val mm = memoryText(st)
        if (mm.isNotEmpty()) out.add(Segment("L2", "dynamic", "LONG_TERM_MEMORY", "E", mm))
        return out
    }

    /**
     * 最后一条用户消息 = 本轮输入 + 记忆增量 + 当前状态（V2：输入在前）。
     * 输入在开头，所以上一轮请求与本轮的公共前缀能一直延伸到上一轮输入的末尾。
     */
    fun finalUserContent(st: JObj?, input: Any?): String {
        val tail = listOf(deltaText(st), stateText(st)).filter { it.isNotEmpty() }
        if (tail.isEmpty()) return Js.str(input)
        return (listOf(Js.str(input)).filter { it.isNotEmpty() } + tail).joinToString("\n\n")
    }

    fun imgSig(images: Any?): List<String> =
        (images as? List<*>)?.map { x ->
            val s = Js.str(x)
            "${s.length}:${if (s.length > 48) s.takeLast(48) else s}"
        } ?: emptyList()

    fun serializeHistory(msgs: List<Any?>, n: Int? = null): String {
        val arr = if (n == null) msgs else msgs.take(max(0, n))
        val json = arr.map { m ->
            val o = m as? JObj ?: jobj()
            JsonArray(
                listOf(
                    JsonPrimitive(Js.str(o["role"])),
                    JsonPrimitive(Js.str(o["content"])),
                    JsonArray(imgSig(o["images"]).map { JsonPrimitive(it) }),
                )
            )
        }
        return JsonArray(json).toString()
    }

    /**
     * 本地 Token 估算：委托 [TokenEstimator.text]（逐码点分档，DeepSeek 官方换算——
     * 全仓唯一文本尺子，评审 3.2 / advisory §10.2① 统一，cache 插件也用这套）。
     */
    fun estimateTokens(text: Any?): Int = TokenEstimator.text(Js.str(text))

    /**
     * 每条消息 = 正文 + 4（角色/分隔开销）+ 图片。
     * **provider 决定图片怎么算**：Gemini 每图 258 / Anthropic (w×h)/750 / OpenAI tile 计费；
     * 传 null 时沿用旧口径 1000/张（golden 与旧版逐位一致）。
     * 见 PLAN-ALGO-CACHE P1——同一句话「这轮会不会超预算」，文本与图片必须同一把尺子。
     */
    fun estimateItems(items: List<Any?>, provider: String? = null): Int = TokenEstimator.messages(items, provider)

    /**
     * §4.2 摘要时点最优停止：枚举「再等 t 轮（t ∈ 0..H）再摘」的折现总成本 J[t]，取 argmin。
     * 成本为相对量：每 token 发送 = 1（固定层 + 该轮负载一起发），超预算罚 ×2，
     * 摘要调用输入 ≈ U（折现 γ^t），摘要改写前缀 → 在 t+1 轮付一次固定层重建（fixed）。
     *
     * 返回 t*：0 = 现在摘；>0 = 再等（滚动时域，下一轮重新求解）。模型下 t≥1 的 J 随 t 单调
     * 不降（等待的收益 (1−γ^t) 与代价同比例增长），故 t* 实际只取 0 或 1——与「等一轮再看」
     * 的滚动语义一致。质量罚（<0.60×1.15）在摘要产出了才知道，不进时点决策。
     */
    fun summaryTiming(U: Int, g: Double, saved: Int, fixed: Int, limit: Int, h: Int = 12, gamma: Double = 0.9): Int {
        if (U <= 0) return 0
        fun cost(sent: Double, overLimit: Boolean, rebuild: Boolean): Double {
            var c = fixed + sent + (if (rebuild) fixed.toDouble() else 0.0)
            if (overLimit) c *= 2
            return c
        }
        var best = Double.MAX_VALUE; var bestT = 0
        for (t in 0..h) {
            var j = Math.pow(gamma, t.toDouble()) * U
            for (i in 1..h) {
                val u = U + g * (i - 1)
                val sent = if (i <= t) u else maxOf(0.0, u - saved)
                j += Math.pow(gamma, i.toDouble()) * cost(sent, fixed + sent > limit, i == t + 1)
            }
            if (j < best - 1e-9) { best = j; bestT = t }
        }
        return bestT
    }

    // ---------------- 估算校准（用服务商返回的真实 prompt_tokens 修正估算） ----------------

    /** 校准键：provider / model / modality 隔离，避免跨模型互相污染（JS calibKey）。 */
    fun calibKey(meta: JObj?): String = listOf(
        s(meta?.get("provider")),
        s(meta?.get("model")),
        s(meta?.get("modality")).ifEmpty { "text" },
    ).joinToString("|")

    /** JS 的 `x || ''` 口径：null / Undefined / 非字符串一律当空串（**别用 Js.str**，它把 null 变成 "undefined"）。 */
    private fun s(v: Any?): String = when {
        v == null || v is com.aichat.app.plugins.js.Undefined -> ""
        v is String -> v
        v is Number || v is Boolean -> v.toString()
        else -> ""
    }

    /** 该模型的校准系数；新模型从 1.0 起，**不**继承全局 calib（跨模型污染）。 */
    fun calibOf(st: JObj?, meta: JObj?): Double {
        val key = calibKey(meta)
        val by = st?.get("calibBy") as? Map<*, *>
        val v = by?.get(key) as? Number
        return v?.toDouble()?.takeIf { it in 0.5..2.5 } ?: 1.0
    }

    /**
     * 用真实输入 token 校准估算：指数平均（旧值 0.7 + 本轮 0.3），系数夹在 0.5–2.5。
     *
     * 只在「估算 > 200 且真实 > 0」时才校准 —— 太小的请求里，4/条 的固定开销占比过高，
     * 拿去校准会把系数带偏。
     */
    fun calibrate(st: JObj, rawEstimate: Int, actualPrompt: Long, meta: JObj?): JObj {
        if (rawEstimate <= 200 || actualPrompt <= 0L) return st
        val key = calibKey(meta)
        val by = LinkedHashMap<String, Any?>()
        (st["calibBy"] as? Map<*, *>)?.forEach { (k, v) -> by[Js.str(k)] = v }
        val prev = (by[key] as? Number)?.toDouble() ?: 1.0
        val ratio = (actualPrompt.toDouble() / rawEstimate).coerceIn(0.5, 2.5)
        val calib = kotlin.math.round((prev * 0.7 + ratio * 0.3) * 1000) / 1000
        by[key] = calib

        val nBy = LinkedHashMap<String, Any?>()
        (st["calibNBy"] as? Map<*, *>)?.forEach { (k, v) -> nBy[Js.str(k)] = v }
        nBy[key] = ((nBy[key] as? Number)?.toInt() ?: 0) + 1

        val next = LinkedHashMap<String, Any?>(st)
        next["calib"] = calib
        next["calibBy"] = by
        next["calibN"] = ((st["calibN"] as? Number)?.toInt() ?: 0) + 1
        next["calibNBy"] = nBy
        next["calibKey"] = key
        return JObj(next)
    }

    // ---------------- 服务商缓存能力 / 上下文窗口 / 自动预算 ----------------

    data class Cap(val vendor: String, val kind: String, val auto: Boolean, val ttl: Int, val label: String)

    private val QWEN_EXPLICIT = Regex(
        "^(qwen3\\.[78]-max|qwen3\\.[567]-plus|qwen3\\.[5-8]-flash|qwen3\\.8-(2\\.4t|27b)|qwen-plus|qwen-flash|qwen3-coder-flash|qwen3-vl-plus)"
    )

    fun capability(presetId: String?, baseUrl: String?, type: String?, model: String?): Cap {
        val base = Js.str(baseUrl).lowercase()
        val m = Js.str(model).lowercase()
        val bare = m.substringAfterLast('/')
        if (presetId == "deepseek" || base.contains("deepseek") || m.contains("deepseek")) {
            return Cap("deepseek", "implicit", true, 24 * 3600, "DeepSeek 自动前缀缓存（硬盘缓存，通常数小时到数天）")
        }
        val dashscope = presetId == "qwen" || presetId == "qwen-intl" ||
            base.contains("dashscope") || base.contains(".maas.aliyuncs.com")
        if (dashscope || m.contains("qwen") || m.contains("qwq")) {
            return if (dashscope && QWEN_EXPLICIT.containsMatchIn(bare) && !bare.contains("flash-character")) {
                Cap("qwen", "explicit", true, 300, "千问显式缓存（cache_control，5 分钟有效，命中时重置）+ 隐式缓存")
            } else {
                Cap("qwen", "implicit", true, 600, "千问隐式缓存（自动，命中按输入价 20%）")
            }
        }
        if (type == "anthropic" || m.contains("claude")) {
            return Cap("anthropic", "explicit", false, 300, "Claude cache_control（需手动启用）")
        }
        if (type == "gemini" || m.contains("gemini")) {
            val adapter = when {
                presetId == "openrouter" || base.contains("openrouter.ai") -> "openrouter"
                (type == null || type == "openai") && m.contains("gemini") -> "openai-compat"
                else -> "none"
            }
            val auto = adapter == "openrouter" || adapter == "openai-compat"
            return Cap(
                "gemini", "implicit", auto, 300,
                if (auto) "Gemini 隐式缓存（适配器 $adapter）" else "Gemini 隐式缓存（需手动启用）"
            )
        }
        if (presetId == "openai" || base.contains("api.openai.com") || Regex("^(gpt-|o\\d)").containsMatchIn(bare)) {
            return Cap("openai", "implicit", false, 600, "OpenAI 自动前缀缓存（需手动启用）")
        }
        return Cap("other", "none", false, 0, "未知 / 无上下文缓存（不改写请求）")
    }

    data class CtxInfo(val window: Int, val tier: Int, val source: String)

    private val CONTEXT_TABLE: List<Triple<Regex, Int, Int>> = listOf(
        Triple(Regex("deepseek"), 128_000, 0),
        Triple(Regex("qwen3\\.7-flash"), 1_000_000, 32_000),
        Triple(Regex("qwen3-coder-flash"), 1_000_000, 32_000),
        Triple(Regex("qwen3-vl-plus"), 256_000, 32_000),
        Triple(Regex("qwen3\\.7-plus"), 1_000_000, 256_000),
        Triple(Regex("qwen3\\.6-(plus|flash)"), 1_000_000, 256_000),
        Triple(Regex("qwen3\\.5-(plus|flash)|qwen-plus-character|qwen-flash-character"), 0, 0),
        Triple(Regex("character"), 32_000, 0),
        Triple(Regex("qwen3\\.5-(plus|flash)"), 1_000_000, 128_000),
        Triple(Regex("qwen-plus|qwen-flash|qwen-turbo"), 1_000_000, 128_000),
        Triple(Regex("qwen3\\.8-flash"), 1_000_000, 0),
        Triple(Regex("qwen3\\.[78]-max"), 256_000, 0),
        Triple(Regex("qwen-max"), 32_000, 0),
        Triple(Regex("qwen-long"), 10_000_000, 0),
        Triple(Regex("qwq|qwen"), 128_000, 0),
        Triple(Regex("claude"), 200_000, 0),
        Triple(Regex("gemini"), 1_000_000, 0),
        Triple(Regex("gpt-|^o\\d"), 400_000, 0),
    )

    fun contextInfo(model: String?, meta: JObj? = null): CtxInfo {
        val m = Js.str(model).lowercase().substringAfterLast('/')
        var window = 0
        var tier = 0
        var source = "default"
        for ((re, w, t) in CONTEXT_TABLE) {
            if (!re.containsMatchIn(m)) continue
            if (w == 0) {
                if (m.contains("character")) window = 32_000 else continue
            } else {
                window = w
                tier = t
            }
            source = "table"
            break
        }
        val metaContext = Js.i32(meta?.get("context"))
        if (metaContext > 0) {
            window = metaContext
            source = "meta"
        }
        if (window == 0) window = 64_000
        return CtxInfo(window, tier, source)
    }

    private fun round1k(n: Double): Int = max(1000, (n / 1000).roundToInt() * 1000)
    private fun clamp(v: Double, a: Double, b: Double): Double = min(b, max(a, v))

    data class Budget(
        val maxContext: Int,
        val outputReserve: Int,
        val safetyMargin: Int,
        val triggerTokens: Int,
        val chapterCap: Int,
        val window: Int,
        val tier: Int,
        val source: String,
    )

    /** B2（P2）：按 vendor 只描述差异的预算档。默认行与历史常量逐位一致；gemini/anthropic 行「拟议，待实测」。 */
    data class BudgetProfile(
        val hardCap: Int = 200_000, val ctxRatio: Double = 0.8, val minCtxFloor: Int = 8_000,
        val outRatio: Double = 0.1, val outMin: Int = 4_000, val outMax: Int = 16_000,
        val marginRatio: Double = 0.02, val marginMin: Int = 1_000, val marginMax: Int = 4_000,
        val triggerRatio: Double = 0.3, val chapterRatio: Double = 0.12, val chapterMin: Int = 4_000, val chapterMax: Int = 24_000,
    )

    fun profileOf(vendor: String?): BudgetProfile = when (vendor?.lowercase()) {
        "gemini" -> BudgetProfile(hardCap = 400_000, triggerRatio = 0.35, chapterMax = 48_000)
        "anthropic", "claude" -> BudgetProfile(triggerRatio = 0.32)
        else -> BudgetProfile()   // deepseek 特判在 autoBudget 入口走常量分支；openai/未知与今天逐位一致
    }

    /** W5（C5）：`rpcache.budgetProfile = "auto"` 时预算三项由能力表接管；缺省/legacy = 手填值（逐字节一致）。 */
    fun budgetAuto(plugins: JsonObject?): Boolean =
        (plugins?.get("rpcache") as? JsonObject)?.get("budgetProfile")
            ?.let { it as? JsonPrimitive }?.content == "auto"

    /** 上限 = min(窗口, 价格第一档, hardCap) × ctxRatio；出参/余量/触发/章节按档位比例+钳制（B2，默认档与历史常量逐位一致）。 */
    fun autoBudget(cap: Cap?, model: String?, meta: JObj? = null, profile: BudgetProfile = profileOf(cap?.vendor)): Budget {
        val ci = contextInfo(model, meta)
        if (cap?.vendor == "deepseek" && ci.source != "meta") {
            return Budget(
                DEFAULT_MAX_CONTEXT, DEFAULT_OUTPUT_RESERVE, DEFAULT_SAFETY_MARGIN,
                DEFAULT_TRIGGER_TOKENS, DEFAULT_CHAPTER_CAP, ci.window, 0, "deepseek-default"
            )
        }
        val usable = min(ci.window.toDouble(), if (ci.tier > 0) ci.tier.toDouble() else Double.MAX_VALUE).let { min(it, profile.hardCap.toDouble()) }
        val maxCtx = round1k(max(profile.minCtxFloor.toDouble(), usable * profile.ctxRatio))
        return Budget(
            maxContext = maxCtx,
            outputReserve = round1k(clamp(maxCtx * profile.outRatio, profile.outMin.toDouble(), profile.outMax.toDouble())),
            safetyMargin = clamp(maxCtx * profile.marginRatio, profile.marginMin.toDouble(), profile.marginMax.toDouble()).roundToInt(),
            triggerTokens = round1k(maxCtx * profile.triggerRatio),
            chapterCap = round1k(clamp(maxCtx * profile.chapterRatio, profile.chapterMin.toDouble(), profile.chapterMax.toDouble())),
            window = ci.window,
            tier = ci.tier,
            source = ci.source,
        )
    }

    // ---------------- 请求组装 ----------------

    data class Built(val system: String, val messages: List<Pair<String, String>>)

    /**
     * 组装最终请求：历史原样追加（前缀不动）+ 最后一条用户消息 = 输入 + 增量 + 状态。
     * 注意：这里不做预算裁剪（JS 的 temporaryTrimPlan / lagrangian），调用方决定要不要裁。
     */
    fun assemble(system: String, hist: List<JObj>, input: JObj?, st: JObj?): Built {
        val msgs = ArrayList<Pair<String, String>>(hist.size + 1)
        hist.forEach { m -> msgs.add(Js.str(m["role"]) to Js.str(m["content"])) }
        if (input != null) msgs.add("user" to finalUserContent(st, input["content"]))
        return Built(system, msgs)
    }

    /** 估算各层 token（用于界面与预算判断）。 */
    fun layerTokens(st: JObj?, baseSystem: Any?, items: List<Any?>, provider: String? = null, facts: String? = null,
                    affect: Boolean = false): JObj {
        val input = items.lastOrNull() as? JObj
        val hist = if (input != null && Js.str(input["role"]) == "user") items.dropLast(1) else items
        return jobj(
            "static" to estimateTokens(staticText(st, baseSystem, facts, affect)),
            "memory" to estimateTokens(memoryText(st)),
            "summary" to estimateTokens(summaryText(st)),
            "history" to estimateItems(hist, provider),
            "delta" to estimateTokens(deltaText(st)),
            "state" to estimateTokens(stateText(st)),
            "input" to (if (input != null) estimateItems(listOf(input), provider) + 6 else 0),
        )
    }

    /** 最旧的一批历史：除最近 keep 轮之外的全部（JS 的 oldestBatch）。 */
    fun oldestBatch(hist: List<JObj>, keep: Int): List<JObj> {
        val userIdx = hist.indices.filter { Js.str(hist[it]["role"]) == "user" }
        val k = max(1, keep)
        if (userIdx.size <= k) return emptyList()
        return hist.take(userIdx[userIdx.size - k])
    }

    // ============================================================ 预算规划与裁剪
    // 对应 rpcache.js 的 plan / computeTrimWindow / temporaryTrimPlan。
    // 摘要时点**不再**用固定阈值：见 summaryTiming()（§4.2，PR #78 已实现）。
    // applyLagrangeBudget（分层预算/背包）按 MASTER-PLAN §5.3 第 1 条**明确不做**（架构造型演进取代）；
    // 预算系数本身要走能力表，见 PLAN-ALGO-CACHE P2（BudgetProfile）。

    /** JS 的 `(v | 0) || def` 口径：非数字/0/空 → 默认值。 */
    private fun numOf(v: Any?, def: Int): Int {
        val n = (v as? Number)?.toDouble() ?: return def
        if (n == 0.0 || n.isNaN()) return def
        return n.toInt()
    }

    fun coveredIds(st: JObj?): Set<String> = chaptersOf(st)
        .flatMap { c -> ((c["coveredIds"] as? List<*>) ?: emptyList<Any?>()).map { Js.str(it) } }
        .toSet()

    fun roundsOf(items: List<JObj>): Int = items.count { Js.str(it["role"]) == "user" }

    // ------------------------------------------------ 摘要失败的退避（C2）
    /**
     * 摘要失败时 `lastSummaryRound` 不推进，于是下一轮会再要一次摘要 ——
     * 模型持续失败就变成**每轮**都白烧一次调用。这里记连续失败次数并退避：
     * 1 次 → 等 2 轮，2 次 → 4 轮，3 次及以上 → 8 轮，成功后清零。
     */
    fun summaryRetryAllowed(st: JObj?, rounds: Int): Boolean {
        val r = (st ?: return true)["summaryRetry"] as? JObj ?: return true
        return rounds >= numOf(r["nextRound"], 0)
    }

    fun noteSummaryFail(st: JObj, at: Long, rounds: Int): JObj {
        val r = st["summaryRetry"] as? JObj
        val n = minOf(3, numOf(r?.get("n"), 0) + 1)
        val wait = 1 shl n
        return JObj(st.toMutableMap().also {
            it["summaryRetry"] = jobj("n" to n, "at" to at.toDouble(), "nextRound" to (rounds + wait), "wait" to wait)
        })
    }

    /** 摘要成功后清掉退避标记；本来没有就原样返回（避免每轮都改一次状态）。 */
    fun clearSummaryFail(st: JObj): JObj {
        if (st["summaryRetry"] == null) return st
        return JObj(st.toMutableMap().also { it.remove("summaryRetry") })
    }

    /**
     * 单条消息的 token 估算（JS itemTokens：内容 + 4 结构开销 + 图片）。
     * 图片必须和 [estimateItems] 同一把尺子，否则「临时裁剪窗口」是按 1000/张 算的、
     * 而「是否超预算」是按真实计价算的，两个判断会互相打架。
     */
    fun itemTokens(m: JObj, provider: String? = null): Int =
        TokenEstimator.messages(listOf<Any?>(m), provider)

    data class TrimWindow(val startId: String, val startOrdinal: Int, val startIdx: Int)

    data class Plan(
        val st: JObj,
        val system: String,
        val hist: List<JObj>,
        val input: JObj?,
        val unsummarized: List<JObj>,
        val tokens: JObj,
        val limit: Int,
        val overBudget: Boolean,
        val trimmed: Boolean,
        val tempTrim: Boolean,
        val dropped: Int,
        val needSummary: Boolean,
        val summaryReason: String?,
        /** 章节合计 token（判断要不要合并用） */
        val chapterTokens: Int,
        /** 章节上限（settings.summary.chapter_cap_tokens） */
        val cap: Int,
        /** 该合并章节了：本轮要写摘要、章节数 > 1、合计超上限 */
        val needConsolidate: Boolean,
        val batch: List<JObj>,
        val calib: Double,
    ) {
        /** 已用掉的预算（校准后的总量，含本轮输入）。 */
        val usedTokens: Int get() = (tokens["total"] as? Number)?.toInt() ?: 0

        /**
         * 还能留给「每轮召回 + 状态行」的额度。
         *
         * 存在的理由：召回段是插件跑完之后才装配的（见 PluginRegistryImpl.assembleRecall），
         * 规划时看不见它。没有这个数，一段 1200 token 的召回会把已经贴着 limit 的请求顶出窗口，
         * 服务端只会报「超长」或者静默截断，界面上完全看不出来。
         */
        val remainingTokens: Int get() = maxOf(0, limit - usedTokens)
    }

    /**
     * 规划本轮请求：算各层 token、判断是否超预算、要不要生成章节。
     * items 是「可发送的消息」（按原顺序，最后一条是本轮用户输入）。
     */
    fun plan(
        st0: JObj, baseSystem: String, items: List<JObj>,
        calibOverride: Double? = null, meta: JObj? = null,
        /** presetId（自定义服务商回落到 id）；只用于图片计价，不参与任何 golden 数值 */
        provider: String? = null,
        /** L1 事实账本（见 [factsText]）；null = 不带，输出与 1.5.3 逐字节一致 */
        facts: String? = null,
        /** 好感度协议进静态层（C5）。false = 与 1.5.3 逐字节一致，由 affect 插件自行追加 */
        affect: Boolean = false,
        /** W5（C5）：rpcache.budgetProfile=auto 时为 true——预算三项改由能力表接管；false = 手填值逐字节一致 */
        budgetAuto: Boolean = false,
        /** budgetAuto=true 时必传：能力表档位（capability 的输出） */
        cap: Cap? = null,
        /** budgetAuto=true 时必传：模型串（CONTEXT_TABLE / meta 覆盖用） */
        model: String? = null,
    ): Plan {
        val st = RpState.normalizeState(st0)
        val s = (st["settings"] as? JObj) ?: JObj()
        val sum = (s["summary"] as? JObj) ?: JObj()
        val k = calibOverride ?: calibOf(st, meta)
        fun cal(n: Int): Int = (n * k).roundToInt()

        val covered = coveredIds(st)
        val elig = items.filter { !covered.contains(Js.str(it["id"])) }
        val last = elig.lastOrNull()
        val input = if (last != null && Js.str(last["role"]) == "user") last else null
        val unsummarized = if (input != null) elig.dropLast(1) else elig

        // 分块截断（仅在超预算且无法摘要时设置过）：起点固定不动
        val win = (st["window"] as? JObj) ?: JObj()
        val startId = Js.str(win["startId"])
        var start = 0
        if (startId.isNotEmpty()) {
            val i = unsummarized.indexOfFirst { Js.str(it["id"]) == startId }
            start = if (i >= 0) i else minOf(numOf(win["startOrdinal"], 0), maxOf(0, unsummarized.size - 1))
        }
        val hist = unsummarized.drop(start)
        val system = buildSystem(st, baseSystem, facts, affect)

        val raw = linkedMapOf(
            "static" to estimateTokens(staticText(st, baseSystem, facts, affect)),
            "memory" to estimateTokens(memoryText(st)),
            "summary" to estimateTokens(summaryText(st)),
            "history" to estimateItems(hist, provider),
            "delta" to estimateTokens(deltaText(st)),
            "state" to estimateTokens(stateText(st)),
            "input" to (if (input != null) estimateItems(listOf(input), provider) + 6 else 0),
        )
        val tok = LinkedHashMap<String, Any?>()
        var total = 0
        raw.forEach { (key, v) -> val c = cal(v); tok[key] = c; total += c }
        tok["total"] = total
        tok["raw_total"] = raw.values.sum()

        // W5（C5）：budgetAuto 时三项预算由能力表接管；否则手填值（与历史逐字节一致）
        val limit = if (budgetAuto && cap != null) {
            val b = autoBudget(cap, model)
            maxOf(1000, b.maxContext - b.outputReserve - b.safetyMargin)
        } else maxOf(
            1000,
            numOf(s["max_context"], 100000) - numOf(s["output_reserve"], 12000) - numOf(s["safety_margin"], 2000),
        )
        val overBudget = total > limit

        val batch = oldestBatch(unsummarized, numOf(sum["keep_recent_turns"], 2))
        val batchRounds = roundsOf(batch)
        val rounds = roundsOf(items)
        val chapters = chaptersOf(st)
        val sinceLast = if (chapters.isNotEmpty()) rounds - numOf(st["lastSummaryRound"], 0) else Int.MAX_VALUE
        val minGap = maxOf(0, numOf(sum["min_interval_turns"], 0))

        var reason: String? = null
        val summaryOn = sum["enabled"] != false
        if (summaryOn && batch.isNotEmpty() && summaryRetryAllowed(st, rounds)) {
            if (overBudget) {
                reason = "budget"
            } else {
                val guardsOk = batchRounds >= maxOf(1, numOf(sum["min_new_turns"], 1)) && sinceLast >= minGap
                val U = cal(estimateItems(unsummarized, provider))
                val mode = Js.str(Js.or(sum["mode"], "trigger"))
                if (mode == "adaptive") {
                    // §4.2 摘要时点最优停止（开关 summary.mode=adaptive，默认 trigger 保持旧行为）：
                    // 枚举「再等 t 轮再摘」的折现总成本，t*=0 且满足守卫 → 现在摘（可早于硬触发）。
                    // 硬超预算在上面已先行；滚动时域——t*>0 只表示本轮不摘，下一轮重新求解。
                    val g = if (rounds > 0) U.toDouble() / rounds else U.toDouble()
                    val chapterEst = minOf(numOf(sum["chapter_cap_tokens"], 12000).toInt(), (U * 0.25).roundToInt())
                    val saved = maxOf(0, U - chapterEst)
                    val fixed = maxOf(0, Js.toNumber(Js.or(tok["total"], 0.0)).toInt() - Js.toNumber(Js.or(tok["history"], 0.0)).toInt())
                    val tStar = summaryTiming(U, g, saved, fixed, limit)
                    if (tStar == 0 && guardsOk && U >= maxOf(1, numOf(sum["adaptive_min_tokens"], 4000))) reason = "adaptive"
                } else if (U >= numOf(sum["trigger_tokens"], 30000) && guardsOk) {
                    reason = "trigger"
                }
            }
        }

        val chapterTokens = (tok["summary"] as? Number)?.toInt() ?: 0
        val cap = maxOf(1000, numOf(sum["chapter_cap_tokens"], 12000))
        // 只在"本轮真要写摘要"时才考虑合并：没有新章要提交，合并等于白白重建一次缓存
        val needConsolidate = reason != null && chapters.size > 1 && chapterTokens >= cap
        return Plan(
            st = st, system = system, hist = hist, input = input, unsummarized = unsummarized,
            tokens = JObj(tok), limit = limit, overBudget = overBudget,
            trimmed = start > 0, tempTrim = false, dropped = start,
            needSummary = reason != null, summaryReason = reason,
            chapterTokens = chapterTokens, cap = cap, needConsolidate = needConsolidate,
            batch = batch, calib = k,
        )
    }

    /** 临时裁剪窗口：从哪条消息开始发送，才能塞进 limit（JS 的 computeTrimWindow）。 */
    fun computeTrimWindow(st: JObj, p: Plan, provider: String? = null): TrimWindow? {
        val s = (st["settings"] as? JObj) ?: JObj()
        val sum = (s["summary"] as? JObj) ?: JObj()
        val keep = maxOf(1, numOf(sum["keep_recent_turns"], 2))
        val un = p.unsummarized
        val users = un.indices.filter { Js.str(un[it]["role"]) == "user" }
        val fixed = numOf(p.tokens["total"], 0) - numOf(p.tokens["history"], 0)
        val prefix = IntArray(un.size + 1)
        for (i in un.indices) prefix[i + 1] = prefix[i] + itemTokens(un[i], provider)
        fun fits(j: Int): Boolean =
            fixed + ((prefix[un.size] - prefix[users[j]]) * p.calib).roundToInt() <= p.limit

        var j = maxOf(0, users.size - keep)
        // C2：临时裁剪的起点一旦定了就钉住（`st.overflow.tempStartId`），不要每轮重算。
        // 每轮重算 ⇒ 起点随预算漂移 ⇒ 前缀每轮变一次、缓存整段作废；钉住的代价只是
        // 多送几条上一轮刚好装得下的旧消息，比零命中便宜得多。装不下时才重新算。
        val pinnedId = Js.str((st["overflow"] as? JObj)?.get("tempStartId"))
        if (pinnedId.isNotEmpty()) {
            val pj = users.indexOfFirst { Js.str(un[it]["id"]) == pinnedId }
            if (pj >= 0 && fits(pj)) return TrimWindow(pinnedId, users[pj], users[pj])
        }
        if (users.isNotEmpty() && !fits(j)) {
            var lo = j
            var hi = users.size - 1
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (fits(mid)) hi = mid else lo = mid + 1
            }
            j = lo
        }
        val startIdx = if (users.isNotEmpty()) users[j] else 0
        if (startIdx >= un.size) return null
        return TrimWindow(Js.str(un[startIdx]["id"]), startIdx, startIdx)
    }

    /**
     * 临时裁剪：只改本轮 plan.hist，不永久推进 st.window（摘要失败后的超预算走这里，
     * 避免剧情被静默永久丢掉）。返回新的 state 与 plan。
     */
    fun applyTempTrim(st: JObj, p: Plan, now: Long, provider: String? = null): Pair<JObj, Plan> {
        val win = computeTrimWindow(st, p, provider) ?: return st to p
        val hist = p.unsummarized.drop(win.startIdx)
        val histTok = (estimateItems(hist, provider) * p.calib).roundToInt()
        val total = numOf(p.tokens["total"], 0) - numOf(p.tokens["history"], 0) + histTok
        val tokens = p.tokens.toMutableMap().also { it["history"] = histTok; it["total"] = total }
        val np = p.copy(
            hist = hist, tokens = JObj(tokens), trimmed = true, tempTrim = true,
            dropped = win.startIdx, overBudget = total > p.limit,
        )
        val nst = st.toMutableMap().also {
            it["overflow"] = jobj(
                "tempStartId" to win.startId, "tempStartOrdinal" to win.startOrdinal,
                "permanent" to false, "reason" to "over_budget_temp", "last_error" to null, "at" to now,
            )
        }
        return JObj(nst) to np
    }
}
