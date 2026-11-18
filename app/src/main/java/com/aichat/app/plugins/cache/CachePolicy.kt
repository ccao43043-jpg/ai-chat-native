package com.aichat.app.plugins.cache

import com.aichat.app.domain.RequestContext
import com.aichat.app.domain.model.AppSettings
import com.aichat.app.domain.model.Conversation
import com.aichat.app.domain.model.ProviderConfig
import com.aichat.app.domain.model.ProviderType
import com.aichat.app.plugins.PluginRegistryImpl
import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.rp.RpCacheCore

/**
 * 一个会话这轮该走哪条缓存路线。
 *
 * 为什么需要「选」而不是「全开」：六个缓存插件对请求的改法互相冲突（gcache 会重排 system、
 * mmcache 会加 lastPrefix 锚点、prefix 只改断点），谁先跑谁赢，而且只有 OpenRouter+Gemini
 * 这一条路径上 gcache 的改写是**必要**的；其它服务商要么服务端自动缓存（OpenAI/DeepSeek/
 * Gemini 原生），要么有自己的一套（MiniMax），盲目改写只会把缓存写坏。
 *
 * 所以：一个会话只允许一个 owner。
 * - gcache   ：改写请求（OpenRouter / openai-compat 的 Gemini 显式缓存）
 * - mmcache  ：MiniMax 专用
 * - prefix   ：**不改请求**，只做前缀稳定性观测与断点建议（服务端隐式缓存的服务商）
 * - none     ：不参与（用户关闭、或该服务商压根没有缓存）
 */
data class CachePlan(
    val owner: String,
    val mode: String,
    val ttlSec: Long,
    val minTokens: Int,
    val reason: String,
) {
    val monitored: Boolean get() = owner != CachePolicy.OWNER_NONE
    val mutates: Boolean get() = owner == CachePolicy.OWNER_GCACHE || owner == CachePolicy.OWNER_MMCACHE || owner == CachePolicy.OWNER_RPCACHE

    fun toJs(): JObj = JObj().also {
        it["owner"] = owner
        it["mode"] = mode
        it["ttlSec"] = ttlSec
        it["minTokens"] = minTokens
        it["reason"] = reason
    }

    companion object {
        fun of(o: JObj?): CachePlan? {
            if (o == null) return null
            val owner = o["owner"] as? String ?: return null
            return CachePlan(
                owner = owner,
                mode = o["mode"] as? String ?: CachePolicy.MODE_AUTO,
                ttlSec = (o["ttlSec"] as? Number)?.toLong() ?: 0L,
                minTokens = (o["minTokens"] as? Number)?.toInt() ?: 0,
                reason = o["reason"] as? String ?: "",
            )
        }
    }
}

object CachePolicy {
    const val ID = "cachepolicy"

    const val OWNER_GCACHE = "gcache"
    const val OWNER_MMCACHE = "mmcache"
    /** 千问显式缓存断点由 RP 缓存优化器自己打（旧版 placeMarkers 的归属） */
    const val OWNER_RPCACHE = "rpcache"
    const val OWNER_PREFIX = "prefix"
    const val OWNER_NONE = "none"

    /** 全量改写（gcache 的 openrouter 模式） */
    const val MODE_FULL = "full"

    /** 只打缓存断点（Anthropic 显式 cache_control） */
    const val MODE_BREAKPOINTS = "breakpoints"

    /** 不改请求，只观测与稳定前缀 */
    const val MODE_AUTO = "auto"
    const val MODE_OFF = "off"

    /** 低于这个前缀长度，服务端不会建缓存条目（Gemini 1024 / OpenAI 1024 / Anthropic 1024） */
    const val MIN_CACHE_TOKENS = 1024

    /** 观测型路线的建议 TTL：服务端隐式缓存的存活窗口一般是分钟级 */
    const val PREFIX_TTL_SEC = 300L

    /**
     * 会话 → 服务商。conv.providerId 可能是真实 id，也可能是预设 id（旧版就这么存的），
     * 两种都认；都认不到返回 null（此时一律走 none，不改请求）。
     */
    fun providerOf(settings: AppSettings?, conv: Conversation?): ProviderConfig? {
        val pid = conv?.providerId ?: return null
        val list = settings?.providers ?: return null
        return list.firstOrNull { it.id == pid } ?: list.firstOrNull { it.presetId == pid }
    }

    /** 会话级覆盖（UI 里「这个会话强制用/不用哪个插件」）写在 conv.plugins[ID] 里 */
    fun overrideOf(conv: Conversation?): String? {
        val o = ((Js.fromJson(conv?.extras?.get("plugins")) as? JObj)?.get("cachepolicy") as? JObj)?.get("owner")
        val s = Js.str(Js.or(o, "")).trim().lowercase()
        // rpcache 也必须在这一档里：千问显式缓存与 Anthropic 断点都由它执行，
        // 少了它，用户「这个会话用 rpcache」会被判非法 → 静默退回自动判断。
        return if (s in setOf(OWNER_GCACHE, OWNER_MMCACHE, OWNER_RPCACHE, OWNER_PREFIX, OWNER_NONE)) s else null
    }

    fun pick(
        provider: ProviderConfig?,
        model: String?,
        settings: AppSettings? = null,
        conv: Conversation? = null,
    ): CachePlan {
        if (provider == null) return CachePlan(OWNER_NONE, MODE_OFF, 0, 0, "还没选服务商")
        if (settings != null && !enabled(settings)) return CachePlan(OWNER_NONE, MODE_OFF, 0, 0, "缓存策略插件已关闭")

        val override = overrideOf(conv)
        if (override != null) {
            return planFor(override, "会话指定：$override")
        }

        val p = PluginRegistryImpl.providerJs(provider)
        val m = Js.str(Js.or(model, "")).ifEmpty { provider.models.firstOrNull() ?: "" }
        val adapter = GCache.resolveCacheAdapter(p, m)

        // 强制 none：用户明确不想用
        if (adapter == "none" && Js.trim(Js.str(Js.or(p["cacheAdapter"], ""))).lowercase() == "none") {
            return CachePlan(OWNER_NONE, MODE_OFF, 0, 0, "该服务商被设为不使用缓存适配器")
        }

        // MiniMax：有自己的一套（mmcache），不走通用路线
        if (isMiniMax(provider)) {
            return CachePlan(OWNER_MMCACHE, MODE_FULL, 240L, MIN_CACHE_TOKENS, "MiniMax 专用缓存插件")
        }

        // OpenRouter / openai-compat 上的 Gemini：显式缓存，只有这条路径 gcache 的改写是必要的
        if (GCache.isOpenRouterGemini(p, m)) {
            val where = if (adapter == "openrouter") "OpenRouter" else "OpenAI 兼容"
            return CachePlan(OWNER_GCACHE, MODE_FULL, 240L, MIN_CACHE_TOKENS, "$where 的 Gemini 显式缓存")
        }

        // 原生千问：显式 cache_control 断点，打点是 RP 缓存优化器自己的活（旧版 rp-plugin.js placeMarkers）。
        // 移植后这条一直没接上，只有隐式缓存在命中 —— 所以它也必须是「能改请求」的 owner 之一。
        val cap = RpCacheCore.capability(provider.presetId, provider.baseUrl, provider.type.wire, m)
        if (CacheMarkers.needsExplicitMarkers(cap.vendor, cap.kind)) {
            return CachePlan(OWNER_RPCACHE, MODE_BREAKPOINTS, cap.ttl * 1000L, MIN_CACHE_TOKENS, "千问显式缓存（RP 缓存优化器打点）")
        }

        // 原生 Anthropic：显式 cache_control 断点。执行者必须是 rpcache ——
        // 它算得到 system 各段的边界与长度，也统一改 opts；prefix 路线只观测不改请求，
        // 挂在那里等于没有执行者（实测一个断点都没打出去）。
        if (provider.type == ProviderType.ANTHROPIC) {
            return CachePlan(OWNER_RPCACHE, MODE_BREAKPOINTS, PREFIX_TTL_SEC, MIN_CACHE_TOKENS, "Anthropic 断点缓存（RP 缓存优化器打点）")
        }

        // 原生 Gemini：隐式缓存，服务端自动命中，请求不能动（动了反而打断缓存）
        if (provider.type == ProviderType.GEMINI) {
            return CachePlan(OWNER_PREFIX, MODE_AUTO, PREFIX_TTL_SEC, MIN_CACHE_TOKENS, "Gemini 原生隐式缓存")
        }

        // 其余 OpenAI 兼容服务商（OpenAI/DeepSeek/Qwen/Moonshot/SiliconFlow…）：
        // 多数已支持服务端自动前缀缓存，同样只观测不改写
        if (provider.type == ProviderType.OPENAI) {
            return CachePlan(OWNER_PREFIX, MODE_AUTO, PREFIX_TTL_SEC, MIN_CACHE_TOKENS, "服务端自动前缀缓存")
        }

        return CachePlan(OWNER_NONE, MODE_OFF, 0, 0, "未知协议，暂不参与缓存")
    }

    private fun planFor(owner: String, reason: String): CachePlan = when (owner) {
        OWNER_GCACHE -> CachePlan(OWNER_GCACHE, MODE_FULL, 240L, MIN_CACHE_TOKENS, reason)
        OWNER_MMCACHE -> CachePlan(OWNER_MMCACHE, MODE_FULL, 240L, MIN_CACHE_TOKENS, reason)
        OWNER_RPCACHE -> CachePlan(OWNER_RPCACHE, MODE_BREAKPOINTS, PREFIX_TTL_SEC, MIN_CACHE_TOKENS, reason)
        OWNER_PREFIX -> CachePlan(OWNER_PREFIX, MODE_AUTO, PREFIX_TTL_SEC, MIN_CACHE_TOKENS, reason)
        else -> CachePlan(OWNER_NONE, MODE_OFF, 0, 0, reason)
    }

    /** 插件开关：settings.plugins["cachepolicy"].enabled，缺省视为开（默认开启才能自动生效） */
    fun enabled(settings: AppSettings?): Boolean {
        if (settings == null) return true
        val o = ((Js.fromJson(settings.plugins) as? JObj)?.get("cachepolicy") as? JObj)?.get("enabled")
        return if (o is Boolean) o else true
    }

    private fun isMiniMax(p: ProviderConfig): Boolean {
        val id = (p.presetId + " " + p.id + " " + p.baseUrl).lowercase()
        return id.contains("minimax")
    }

    // ---------------- 回合级 memo ----------------

    /**
     * 一段对话一轮只算一次路线。
     *
     * 以前 [pick] 一轮被调 5 次（cachemeter / 两个缓存插件的 autoActive 与 inactiveReason /
     * rpcache 的 autoActive 与打点），每次都要重建 providerJs、跑 capability 判定；
     * 更要紧的是**口径可能不一致** —— gcache 的 onBeforeRequest 里还自己又判了一次
     * isOpenRouterGemini，于是「策略说走 gcache，插件自己说不是我的活」这种矛盾
     * 在界面上看不出来，只表现为「开关开着但什么都没省」。
     *
     * memo 的范围就是一轮：`runBeforeRequest` 开头写入、finally 清除。
     */
    private val turnMemo = java.util.concurrent.ConcurrentHashMap<String, CachePlan>()

    fun beginTurn(ctx: RequestContext): CachePlan {
        val p = pick(ctx.provider, ctx.model, ctx.settings, ctx.conv)
        turnMemo[ctx.conv.id] = p
        return p
    }

    fun current(convId: String?): CachePlan? = if (convId.isNullOrEmpty()) null else turnMemo[convId]

    fun clearTurn(convId: String?) {
        if (!convId.isNullOrEmpty()) turnMemo.remove(convId)
    }

    /** 非回合场景（插件页、autoActive/inactiveReason）的统一入口：有 memo 用 memo，没有即时算 */
    fun planOf(conv: Conversation?, settings: AppSettings?): CachePlan {
        if (conv == null) return pick(providerOf(settings, null), null, settings, null)
        current(conv.id)?.let { return it }
        return pick(providerOf(settings, conv), conv.model, settings, conv)
    }
}
