package com.aichat.app.net

import com.aichat.app.domain.model.ProviderConfig
import com.aichat.app.domain.model.ProviderType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/** providers.js 中与服务商判定 / OpenRouter / max_tokens 相关的纯函数（逐个对照 JS，golden 测试比对） */
object ProviderRules {
    val STREAM_USAGE_PRESETS = listOf("openai", "deepseek", "qwen", "qwen-intl", "openrouter")
    private val JSON_MODE_PRESETS = listOf("openai", "deepseek", "qwen", "qwen-intl", "openrouter")
    private val RE_DEEPSEEK = Regex("//api\\.deepseek\\.com", RegexOption.IGNORE_CASE)
    private val RE_DEEPSEEK_OPENAI = Regex("//api\\.(deepseek|openai)\\.com", RegexOption.IGNORE_CASE)
    private val RE_QWEN = Regex("dashscope(-intl|-us)?\\.aliyuncs\\.com|\\.maas\\.aliyuncs\\.com|dashscope", RegexOption.IGNORE_CASE)
    private val RE_OPENROUTER = Regex("openrouter\\.ai", RegexOption.IGNORE_CASE)

    /** 阿里云百炼 / DashScope 接口 */
    fun isQwenEndpoint(p: ProviderConfig?): Boolean {
        if (p == null) return false
        if (p.presetId == "qwen" || p.presetId == "qwen-intl") return true
        return RE_QWEN.containsMatchIn(p.baseUrl)
    }
    fun streamUsageEnabled(p: ProviderConfig?): Boolean {
        if (p == null || p.type != ProviderType.OPENAI) return false
        p.streamUsage?.let { return it }
        return p.presetId in STREAM_USAGE_PRESETS || RE_DEEPSEEK.containsMatchIn(p.baseUrl) || isQwenEndpoint(p)
    }
    fun jsonModeSupported(p: ProviderConfig?): Boolean {
        if (p == null || p.type != ProviderType.OPENAI) return false
        return p.presetId in JSON_MODE_PRESETS || RE_DEEPSEEK_OPENAI.containsMatchIn(p.baseUrl) || isQwenEndpoint(p)
    }
    fun isOpenRouterProvider(p: ProviderConfig?): Boolean {
        if (p == null) return false
        return p.presetId == "openrouter" || RE_OPENROUTER.containsMatchIn(p.baseUrl)
    }
    internal fun isOpenRouterBase(presetId: String, trimmedBase: String) = presetId == "openrouter" || RE_OPENROUTER.containsMatchIn(trimmedBase)

    // ---------------- OpenRouter ----------------
    private val OR_SORTS = setOf("balanced", "price", "throughput")
    fun normalizeOrSort(v: String?): String { val s = (v ?: "").lowercase(Locale.ROOT); return if (s in OR_SORTS) s else "balanced" }
    /** 无钉定时的 provider 字段；balanced 返回 null（不设 sort） */
    fun openRouterSortField(provider: ProviderConfig?, optsOrSort: String? = null): JsonObject? {
        val sort = normalizeOrSort(optsOrSort ?: provider?.orSort)
        if (sort == "balanced") return null
        return buildJsonObject { put("sort", sort) }
    }
    /** router.js buildProviderField(slug, {enabled:true}) */
    fun buildProviderField(chosenSlug: String?, enabled: Boolean = true): JsonObject =
        if (!enabled || chosenSlug.isNullOrEmpty()) buildJsonObject { put("sort", "price") }
        else buildJsonObject {
            put("order", buildJsonArray { add(JsonPrimitive(chosenSlug)) })
            put("allow_fallbacks", true)
            put("require_parameters", true)
        }

    /** 稳定 session_id：FNV-1a ×4 → UUID 形状（与 cache-common.js sessionIdFor(convId,'or') 逐字一致） */
    fun orSessionIdFor(convId: String?, ns: String? = null): String {
        if (convId.isNullOrEmpty()) return ""
        val n = if (ns.isNullOrEmpty()) "or" else ns
        val s = "$n:$convId"
        val h = fnv(s) + fnv("a$s") + fnv("b$s") + fnv("c$s")
        return h.substring(0, 8) + "-" + h.substring(8, 12) + "-" + h.substring(12, 16) + "-" + h.substring(16, 20) + "-" + h.substring(20, 32)
    }
    fun fnv(str: String): String {
        var h = 0x811c9dc5.toInt()
        for (ch in str) { h = h xor ch.code; h *= 0x01000193 }
        return Integer.toHexString(h).padStart(8, '0')
    }

    // ---------------- max_tokens ----------------
    const val SAFE_DEFAULT_MAX_TOKENS = 8192
    /**
     * 推理型模型的默认输出上限。它们先「思考」再作答，而思考 token 与正文**共用** maxOutputTokens
     * （Gemini 2.5/3.x 的 thoughtsTokenCount、o 系列的 reasoning tokens 都计入 completion），
     * 用 8192 会在几轮对话后把正文挤成半截，还看不出被截断。
     */
    const val REASONING_DEFAULT_MAX_TOKENS = 32768
    const val MIN_DOWNGRADE_MAX_TOKENS = 256

    /** 模型名里能认出「会先思考」的：gemini-2.5+ / gemini-3+、o1/o3/o4、*-reasoner、deepseek-r1、qwq 等 */
    private val REASONING_MODEL = Regex(
        "(?i)(^|[/:])(" +
            "gemini-2\\.[5-9]|gemini-[3-9]|" +          // google/gemini-2.5-pro、gemini-3.8-flash
            "o[134]([-_.]|$)|" +                         // o1、o3-mini、o4-mini
            "deepseek-r1|qwq|" +
            "[^/]*reasoner|" +                           // deepseek-reasoner
            "[^/]*thinking" +
            ")"
    )

    fun isReasoningModel(model: String?): Boolean {
        val m = model?.trim() ?: return false
        return m.isNotEmpty() && REASONING_MODEL.containsMatchIn(m)
    }

    /**
     * OpenRouter 模型变体：`vendor/model:flex` → `vendor/model`（nitro/floor/flex/free… 一律剥）。
     * 裸 slug 不含冒号；无冒号原样返回。用于 modelMeta 的**回退查找**——精确键优先，miss 才剥，
     * 自定义服务商里真带冒号的模型 id 不受影响。
     */
    fun baseSlug(model: String?): String? {
        val m = model?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val i = m.indexOf(':')
        return if (i < 0) m else m.substring(0, i).takeIf { it.isNotEmpty() }
    }

    /** 精确键优先、miss 回退剥变体后缀——modelMeta 的统一查找入口（flex/nitro 等 :variant 串共享基础模型的元数据） */
    fun metaFor(modelMeta: JsonObject?, model: String?): JsonObject? {
        if (modelMeta == null || model.isNullOrEmpty()) return null
        return modelMeta[model] as? JsonObject ?: baseSlug(model)?.let { modelMeta[it] as? JsonObject }
    }

    /** metaFor 的键版：返回「能查到 meta 的模型串」，都没有时原样返回（供内部重载用） */
    private fun metaKeyFor(modelMeta: JsonObject?, model: String?): String? {
        if (modelMeta == null || model.isNullOrEmpty()) return model
        if (modelMeta[model] != null) return model
        return baseSlug(model) ?: model
    }

    /** resolveMaxTokens(provider, model, userMax)；userMax 允许 JS 原值（''、数字串、数字、null） */
    fun resolveMaxTokens(modelMeta: JsonObject?, model: String?, userMax: JsonElement?): Double {
        // typeof userMax === 'number' ? userMax : parseFloat(userMax)
        val n = if (userMax is JsonPrimitive && userMax !is JsonNull && !userMax.isString && userMax.booleanOrNull == null)
            userMax.doubleOrNull ?: Double.NaN
        else Js.parseFloat(Js.str(userMax))
        val meta = if (modelMeta != null && !model.isNullOrEmpty()) modelMeta[model] as? JsonObject else null
        val rawCap = meta?.get("maxOutput")
        val capNum = if (rawCap == null) Double.NaN else Js.toNumber(rawCap)
        val modelCap = if (capNum > 0) capNum else 0.0
        if (!n.isNaN() && n > 0) return if (modelCap > 0) minOf(n, modelCap) else n
        if (modelCap > 0) return minOf(modelCap, SAFE_DEFAULT_MAX_TOKENS.toDouble())
        return SAFE_DEFAULT_MAX_TOKENS.toDouble()
    }
    /**
     * 设置里的 maxTokens（Int? / 未设）→ 发出的 max_tokens。
     *
     * 用户没填时**不再一律用 8192**：推理型模型给 [REASONING_DEFAULT_MAX_TOKENS]，其余仍 8192
     * （8192 是当年为绕开 OpenRouter「按 max_tokens 预扣额度 → 402」定的全局常量，仍适用于普通模型；
     * 真撞 402 时有 `parseAffordTokens` / `downgradeMaxTokens` 自动降级兜底）。
     * 两种情况都仍受模型自报的 maxOutput 钳制。**不要改上面那个 JsonElement 重载**——它按 JS 逐字对齐、有 golden 卡着。
     */
    fun resolveMaxTokens(provider: ProviderConfig?, model: String?, userMax: Int?): Int {
        if (userMax != null && userMax > 0) {
            // 带 :variant 的模型串剥后缀查 meta（JsonElement 重载按传入串精确查 key；golden 输入不含变体，不受影响）
            return resolveMaxTokens(provider?.modelMeta, metaKeyFor(provider?.modelMeta, model), JsonPrimitive(userMax))
                .let { if (it >= Int.MAX_VALUE) Int.MAX_VALUE else it.toInt() }
        }
        val meta = metaFor(provider?.modelMeta, model)
        val capNum = if (meta == null) Double.NaN else Js.toNumber(meta["maxOutput"])
        val modelCap = if (capNum > 0) capNum else 0.0
        val base = (if (isReasoningModel(model)) REASONING_DEFAULT_MAX_TOKENS else SAFE_DEFAULT_MAX_TOKENS).toDouble()
        val out = if (modelCap > 0) minOf(modelCap, base) else base
        return if (out >= Int.MAX_VALUE) Int.MAX_VALUE else out.toInt()
    }

    /** JS 正则的 \s（含 NBSP、全角空格、U+2028/2029、BOM 等 Unicode 空白；Java 的 \s 只有 ASCII） */
    private const val JS_SPACE = "[\\t\\n\\u000B\\u000C\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"
    private val AFFORD = Regex("can only afford$JS_SPACE+([0-9]+)", RegexOption.IGNORE_CASE)
    /**
     * OpenRouter 402 文案中的 can only afford N（同 JS：String(text || '') / parseInt / n > 0 ? n : null）。
     * 返回 JS number（Double）：超大数不截到 Long 上限（1e20 就是 1e20，400 位数字 → Infinity，同 parseInt）。
     */
    fun parseAffordTokens(text: String?): Double? {
        val m = AFFORD.find(text ?: "") ?: return null
        val n = m.groupValues[1].toDouble()          // 十进制串 → 最近 double，同 parseInt(…, 10)
        return if (n > 0) n else null
    }
    /** 402 降级：Math.max(min, Math.floor(afford * 0.8))，min 默认 256；返回 JS number */
    fun downgradeMaxTokens(afford: Double?, min: Int? = null): Double {
        val lo = if (min != null && min > 0) min.toDouble() else MIN_DOWNGRADE_MAX_TOKENS.toDouble()
        val a = afford ?: 0.0
        if (!(a > 0)) return lo
        return maxOf(lo, Math.floor(a * 0.8))
    }
}
