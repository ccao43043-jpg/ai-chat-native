package com.aichat.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * `Retry-After` 支持（PLAN-ALGO-CACHE P4）。
 *
 * 改之前只会猜：429 一律 2s 起步。服务端说「30 秒后再来」，我们 2 秒就撞回去，
 * `MAX_RETRIES = 2` 的两次机会瞬间烧光，最后给用户一个假失败。
 * `delayFor` = max(指数退避, Retry-After)，封顶 60s。
 */
class RetryAfterTest {

    /** rnd = 0.5 → 抖动系数 (0.8 + 0.4×0.5) = 1.0，退避数值可预期。 */
    private fun backoffExact(attempt: Int, kind: String?): Long = NetRetry.backoff(attempt, kind, 0.5)

    @Test
    fun retryAfterSecondsWins() {
        val ra = NetRetry.parseRetryAfter(mapOf("Retry-After" to "30"), 0L)
        assertEquals(30_000L, ra)
        // 退避是 2s，服端让等 30s —— 必须听服端的
        assertEquals(2_000L, backoffExact(0, "rate_limit"))
        assertEquals(30_000L, NetRetry.delayFor(0, "rate_limit", ra, 0.5))
    }

    @Test
    fun headerNameIsCaseInsensitive() {
        assertEquals(5_000L, NetRetry.parseRetryAfter(mapOf("retry-after" to "5"), 0L))
        assertEquals(5_000L, NetRetry.parseRetryAfter(mapOf("RETRY-AFTER" to " 5 "), 0L))
    }

    @Test
    fun retryAfterHttpDate() {
        val now = ZonedDateTime.of(2015, 10, 21, 7, 27, 30, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        val ra = NetRetry.parseRetryAfter(mapOf("Retry-After" to "Wed, 21 Oct 2015 07:28:00 GMT"), now)
        assertEquals(30_000L, ra)
    }

    @Test
    fun httpDateInThePastMeansNoWait() {
        val now = ZonedDateTime.of(2015, 10, 21, 8, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(0L, NetRetry.parseRetryAfter(mapOf("Retry-After" to "Wed, 21 Oct 2015 07:28:00 GMT"), now))
    }

    @Test
    fun retryAfterClampedTo60s() {
        // 比 60s 更久的等待不如直接报错让用户决定；也防中转编个「等一小时」
        assertEquals(NetRetry.MAX_DELAY_MS, NetRetry.delayFor(0, "rate_limit", 120_000L, 0.5))
        assertEquals(60_000L, NetRetry.MAX_DELAY_MS)
    }

    @Test
    fun backoffStillWinsWhenItIsLonger() {
        // attempt=2、rate_limit：2s×4 = 8s > 服端说的 3s，取大的
        assertEquals(8_000L, backoffExact(2, "rate_limit"))
        assertEquals(8_000L, NetRetry.delayFor(2, "rate_limit", 3_000L, 0.5))
    }

    @Test
    fun noHeaderFallsBackToBackoff() {
        assertNull(NetRetry.parseRetryAfter(emptyMap(), 0L))
        assertEquals(2_000L, NetRetry.delayFor(0, "rate_limit", null, 0.5))
    }

    @Test
    fun garbageHeaderIsIgnored() {
        assertNull(NetRetry.parseRetryAfterValue("soon", 0L))
        assertNull(NetRetry.parseRetryAfterValue("-5", 0L))
        assertNull(NetRetry.parseRetryAfterValue("", 0L))
        assertNull(NetRetry.parseRetryAfterValue(null, 0L))
    }
}
