package com.aichat.app.plugins.cache

/**
 * 显式缓存的断点摆放（对齐旧版 `www/rp-plugin.js` 的 `placeMarkers`）。
 *
 * 只有「显式缓存」的服务商需要它：Google 的隐式缓存、DeepSeek 的前缀自动命中都不用人操心，
 * 而千问（dashscope）必须用 `cache_control` 明确标出「从这里往前都可以复用」，
 * 而且——这是关键——**不足 minTokens 的前缀标了也没意义**（服务端会忽略），
 * 断点太多也会挤占额度，所以旧版限制最多 4 个。
 *
 * 这里只做「算」，不改请求：算出来交给 `OpenAiCompat` 的 `cacheControl/cacheSystemSplit/cacheMark`
 * 三个开关去落地。这样 golden 与「路线不该改请求」的约束都能守住。
 */
data class MarkerPlan(
    /** 给 system 打 `cache_control` */
    val system: Boolean = false,
    /** system 内部再断一刀的位置（字符 offset），0 = 不切 */
    val systemSplit: Int = 0,
    /** 历史里哪一条打标记（相对 history 列表的下标），-1 = 不打 */
    val historyIndex: Int = -1,
    val count: Int = 0,
) {
    val mutatesRequest: Boolean get() = system || systemSplit > 0 || historyIndex >= 0
}

object CacheMarkers {
    const val DEFAULT_MAX_MARKERS = 4

    /** 旧版写死的门槛：短前缀标了服务端也不认，标了反而可能报错。 */
    const val DEFAULT_MIN_TOKENS = 1024

    /**
     * @param sysTokens system 里稳定部分的 token（static + memory + summary）
     * @param staticTokens 其中「永远不变」的部分（static）
     * @param memoryTokens 长期记忆块
     * @param summaryTokens 章节摘要
     * @param historyTokens 历史消息合计
     * @param historyCount 历史消息条数（>0 才可能打历史断点）
     * @param stablePrefixLen 稳定前缀长度（0 = 没有稳定前缀，不许切 system）
     * @param systemLen 实际 system 长度（切点必须落在里面）
     * @param maxMarkers 断点上限
     * @param minTokens 最小可标 token
     */
    fun plan(
        sysTokens: Int,
        staticTokens: Int,
        memoryTokens: Int,
        summaryTokens: Int,
        historyTokens: Int,
        historyCount: Int,
        stablePrefixLen: Int = 0,
        systemLen: Int = 0,
        maxMarkers: Int = DEFAULT_MAX_MARKERS,
        minTokens: Int = DEFAULT_MIN_TOKENS,
    ): MarkerPlan {
        var count = 0
        var system = false
        var split = 0
        var histIdx = -1

        if (sysTokens >= minTokens) {
            system = true
            count++
        }

        // 静态前缀 + 摘要构成「近乎不变」的稳定段；记忆块夹在中间。
        // 如果记忆块也在稳定前缀里（常见情形），在「静态+摘要」够长时再断一刀，
        // 这样记忆块变化时只损失后半段缓存。
        val stableTokens = staticTokens + summaryTokens
        val reserveHist = if (historyCount > 0) 1 else 0
        if (system && memoryTokens > 0 && stableTokens >= minTokens &&
            count + 1 + reserveHist <= maxMarkers &&
            stablePrefixLen > 0 && systemLen > stablePrefixLen
        ) {
            split = stablePrefixLen
            count++
        }

        if (historyCount > 0 && sysTokens + historyTokens >= minTokens && count < maxMarkers) {
            histIdx = historyCount - 1
            count++
        }

        return MarkerPlan(system = system, systemSplit = split, historyIndex = histIdx, count = count)
    }

    /**
     * 哪些服务商要我们亲手打显式断点。
     *
     * - 千问（dashscope）显式缓存：`cache_control` + system 切分
     * - 原生 Anthropic：`cache_control` 断点（系统块 + 历史锚点）
     *
     * 之前只有千问一支：策略层把 Anthropic 判成 `owner=prefix`「只观测」，
     * 而 prefix 路线根本没有执行者 —— Anthropic 的断点一个都没打出去过。
     */
    fun needsExplicitMarkers(vendor: String?, kind: String?): Boolean =
        (vendor == "qwen" && kind == "explicit") || vendor == "anthropic"
}
