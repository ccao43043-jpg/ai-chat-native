package com.aichat.app.plugins.cache

import com.aichat.app.domain.*
import com.aichat.app.domain.model.*
import com.aichat.app.plugins.PluginHost
import com.aichat.app.plugins.js.*

/**
 * 提示缓存插件的公共骨架。
 *
 * 这里只放「gcache 与 mmcache 一字不差的共同部分」：会话级生效、S5 归属、失活原因、
 * 徽章收口、前缀连续性记账、滚动窗口。**协议差异一律留在子类**——
 * 两家上游对请求的改法不一样（Gemini 有 hybrid/分层/sessionId，MiniMax 只补 session 与 route order），
 * 硬揉成一个分支只会让两边都变得难懂。
 *
 * 为什么要抽这一层：两个类原本各自维护一份 lastPrefix/lastBody/prefixChanges 记账，
 * 改一处忘了另一处就会出现「一个插件算得出前缀变了几次，另一个永远是 0」这种哑巴 bug。
 */
abstract class CachePluginBase(protected val host: PluginHost) : ChatPlugin {
    /** 按会话生效：由 CachePolicy 决定这个会话该不该走它（见 PLAN-CONTEXT-ARCH §3.4） */
    override val perConversation = true

    /** 协议层缓存标记：只有缓存插件允许改 body（S5） */
    override fun slots(): List<Slot> = listOf(Slot.S5_BODY)

    override fun inactiveReason(conv: Conversation, settings: AppSettings): String {
        val p = CachePolicy.providerOf(settings, conv)
        return CachePolicy.pick(p, conv.model, settings, conv).reason
    }

    /** 徽章收口到 cachemeter（PLAN-CONTEXT-ARCH §3.3）：仍写 stat/滚动率，但不再自己出徽章 */
    override fun headerBadges(conv: Conversation): List<Badge> = emptyList()

    /** 仅运行时：convId → 上一轮 system 前缀 / 完整请求体 */
    private val lastPrefix = HashMap<String, String>()
    private val lastBody = HashMap<String, String>()

    /**
     * 前缀连续性记账：前缀变了就 +1，并记下第一处差异。
     * @param prevKnown 上一轮是否记过（首轮不计数，否则每个新对话都白记一次）
     */
    protected fun trackPrefix(
        convId: String,
        prefix: String,
        stat: JObj,
        hash: (String) -> String,
        firstDiff: (String, String) -> Any?,
    ): String {
        val ph = hash(prefix)
        val prev = lastPrefix[convId]
        val changed = if (prev != null) prev != prefix else (stat["prefix_hash"].truthy() && Js.str(stat["prefix_hash"]) != ph)
        if (changed) {
            stat["prefixChanges"] = Js.i32(stat["prefixChanges"]) + 1
            if (prev != null) firstDiff(prev, prefix)?.let { stat["lastDiff"] = it }
        }
        lastPrefix[convId] = prefix
        stat["prefix_hash"] = ph
        stat["prefixLen"] = prefix.length
        return ph
    }

    /** 请求体连续性：返回与上一轮请求体的最长公共前缀比例（首轮 null） */
    protected fun trackBody(convId: String, body: String, stat: JObj, lcp: (String, String) -> Double?): Double? {
        val ratio = lastBody[convId]?.let { lcp(it, body) }
        lastBody[convId] = body
        stat["lastLcp"] = ratio
        return ratio
    }

    /** 滚动窗口记账（两家的 ROLL_N 不同，由子类传） */
    protected fun pushRoll(stat: JObj, prompt: Any?, hit: Any?, known: Boolean, n: Int) {
        stat["roll"] = Js.slice(
            (stat["roll"].a() ?: jarr()) + listOf<Any?>(jobj("prompt" to prompt, "hit" to hit, "known" to known)),
            -n,
        )
    }

    /** 缺口（秒）记账：保留最近 50 个，供 TTL 建议与归因使用 */
    protected fun pushGap(stat: JObj, gapSec: Double) {
        stat["gaps"] = Js.slice((stat["gaps"].a() ?: jarr()) + listOf<Any?>(gapSec), -50)
    }
}
