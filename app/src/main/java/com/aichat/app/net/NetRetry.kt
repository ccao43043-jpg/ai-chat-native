package com.aichat.app.net

/**
 * www/netretry.js：网络失败分类 + 指数退避（只在还没收到正文时重试；用户停止 / 4xx（除 408/409/425/429）不重试）。golden：netretry.jsonl。
 */
object NetRetry {
    const val MAX_RETRIES = 2
    val RETRY_STATUS = setOf(408, 409, 425, 429, 500, 502, 503, 504, 520, 522, 524, 529)
    data class Kind(val kind: String, val retry: Boolean, val label: String)

    private val RE_TIMEOUT = Regex("timeout|timed out|超时", RegexOption.IGNORE_CASE)
    private val RE_ABORT = Regex("abort|cancel", RegexOption.IGNORE_CASE)
    private val RE_FATAL = Regex("context.{0,20}(length|window)|maximum context|too many tokens|insufficient.{0,10}(quota|balance)|余额不足", RegexOption.IGNORE_CASE)

    /** classify(status, raw, err)：status 用 Number(status)||0；err = 网络异常的 message（无则 null） */
    fun classify(status: Double, raw: String?, err: String?): Kind {
        val s = if (status.isNaN()) 0.0 else status
        val t = (raw ?: "") + " " + (err ?: "")
        if (s == 0.0) {
            if (RE_TIMEOUT.containsMatchIn(t)) return Kind("timeout", true, "请求超时")
            if (RE_ABORT.containsMatchIn(t)) return Kind("aborted", false, "已取消")
            return Kind("network", true, "网络连接失败")
        }
        val st = Js.numberToString(s)
        if (s == 429.0) return Kind("rate_limit", true, "请求过于频繁（429）")
        if (s == 401.0 || s == 403.0) return Kind("auth", false, "鉴权失败（$st）")
        if (RE_FATAL.containsMatchIn(t)) return Kind("fatal", false, "不可重试错误（$st）")
        if (s == Math.floor(s) && s.toInt() in RETRY_STATUS) return Kind(if (s >= 500) "server" else "transient", true, "服务端暂时不可用（$st）")
        if (s >= 400) return Kind("client", false, "请求错误（$st）")
        return Kind("ok", false, "")
    }
    fun classify(status: Int, raw: String?, err: String?): Kind = classify(status.toDouble(), raw, err)

    /** backoff(attempt, kind, rnd)：base·2^attempt（429 起步 2s，其余 0.8s），上限 15s，×(0.8+0.4·rnd) 后取整 */
    fun backoff(attempt: Double, kind: String?, rnd: Double = Math.random()): Long {
        val base = if (kind == "rate_limit") 2000.0 else 800.0
        val a = maxOf(0, Js.toInt32(attempt))
        return Js.round(minOf(15000.0, base * Math.pow(2.0, a.toDouble())) * (0.8 + 0.4 * rnd)).toLong()
    }
    fun backoff(attempt: Int, kind: String?, rnd: Double = Math.random()): Long = backoff(attempt.toDouble(), kind, rnd)

    fun shouldRetry(c: Kind?, attempt: Int, gotDelta: Boolean, stopped: Boolean): Boolean =
        c != null && c.retry && !gotDelta && !stopped && attempt < MAX_RETRIES

    /** 等待上限：比 60s 更久的等待不如直接报错让用户决定（也可能只是中转编了个大数）。 */
    const val MAX_DELAY_MS = 60_000L

    /**
     * 服务端说「多久之后再来」（`Retry-After` 响应头），毫秒。
     *
     * 以前我们只会猜：429 一律 2s 起步。服务端说「30 秒后再来」，我们 2 秒就重试，
     * `MAX_RETRIES = 2` 的两次机会瞬间烧光，最后给用户一个假失败。
     * 键名大小写不敏感（OkHttp 的 `header()` 已经不敏感，这里兼容普通 Map 调用）。
     */
    fun parseRetryAfter(headers: Map<String, String>, nowMs: Long = System.currentTimeMillis()): Long? =
        parseRetryAfterValue(headers.entries.firstOrNull { it.key.equals("Retry-After", true) }?.value, nowMs)

    /** 两种格式都认：秒数（"30"）与 HTTP-date（"Wed, 21 Oct 2015 07:28:00 GMT"）。解析不出来 → null。 */
    fun parseRetryAfterValue(raw: String?, nowMs: Long = System.currentTimeMillis()): Long? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        s.toLongOrNull()?.let { sec -> return if (sec < 0) null else sec * 1000L }
        val at = parseHttpDate(s) ?: return null
        return (at - nowMs).coerceAtLeast(0L)
    }

    /** 实际等待 = max(指数退避, Retry-After)，封顶 [MAX_DELAY_MS]。只在 429/503 传 retryAfterMs。 */
    fun delayFor(attempt: Int, kind: String?, retryAfterMs: Long?, rnd: Double = Math.random()): Long =
        minOf(MAX_DELAY_MS, maxOf(backoff(attempt, kind, rnd), retryAfterMs ?: 0L))

    private fun parseHttpDate(s: String): Long? = try {
        java.time.ZonedDateTime.parse(s, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant().toEpochMilli()
    } catch (_: Exception) {
        null
    }
}
