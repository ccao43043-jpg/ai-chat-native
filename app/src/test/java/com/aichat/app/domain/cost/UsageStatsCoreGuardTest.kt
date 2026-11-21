package com.aichat.app.domain.cost

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EXPERIENCE-GUARD（2026-11-21）：UsageStatsCore.record / makeEntry / reconcileEntry 的口径保护。
 * 此前 record/reconcileEntry/backfill 全部 0 测试（grep 确认）；条目构造照 UsageEntryTest 的手拼方式经 makeEntry 走正规入口。
 */
class UsageStatsCoreGuardTest {

    private fun entry(msgId: String, model: String = "deepseek-flash", at: Long = 1000L, cost: Double? = 0.0001): JsonObject =
        UsageStatsCore.makeEntry(buildJsonObject {
            put("usage", buildJsonObject {
                put("prompt", JsonPrimitive(100))
                put("completion", JsonPrimitive(10))
                put("cacheSupported", JsonPrimitive(false))
                if (cost != null) put("cost", JsonPrimitive(cost))
            })
            put("at", JsonPrimitive(at))
            put("type", JsonPrimitive("chat"))
            put("model", JsonPrimitive(model))
            put("msgId", JsonPrimitive(msgId))
        })

    private fun log(s: JsonObject): JsonArray = s["log"] as JsonArray

    @Test fun normalizeFromNullFillsDefaults() {
        val s = UsageStatsCore.normalize(null)
        assertEquals(UsageStatsCore.VERSION.toString(), (s["version"] as JsonPrimitive).content)
        assertEquals(0, log(s).size)
        assertEquals("auto", (s["priceMode"] as JsonPrimitive).content)
    }

    @Test fun recordDedupsByExactKeyAndAppendsDistinct() {
        var s = UsageStatsCore.record(UsageStatsCore.emptyState(), entry("m1", at = 1000))
        s = UsageStatsCore.record(s, entry("m1", at = 1000))       // 同 msgId+at+type → key 相同 → 去重
        assertEquals(1, log(s).size)
        s = UsageStatsCore.record(s, entry("m2", at = 1000))
        assertEquals(2, log(s).size)
    }

    @Test fun recordIgnoresEntriesWithoutPrompt() {
        // makeEntry 永远产出 prompt（缺省 0）；record 的守卫针对的是「prompt 缺失/为 null」的手拼条目
        val noPrompt = JsonObject(linkedMapOf("key" to JsonPrimitive("k"), "prompt" to JsonNull))
        val s = UsageStatsCore.record(UsageStatsCore.emptyState(), noPrompt)
        assertEquals(0, log(s).size)
    }

    @Test fun recordTrimsOldestBeyondLimit() {
        var s = UsageStatsCore.emptyState()
        for (i in 0 until UsageStatsCore.LOG_LIMIT + 5) s = UsageStatsCore.record(s, entry("m$i", at = (i + 1).toLong()))
        val kept = log(s)
        assertEquals(UsageStatsCore.LOG_LIMIT, kept.size)
        val first = kept.first() as JsonObject
        assertEquals("最旧的 5 条被裁掉", "m5", (first["msgId"] as JsonPrimitive).content)
    }

    @Test fun recordDoesNotMutateInputState() {
        val st = UsageStatsCore.emptyState()
        val before = st.toString()
        UsageStatsCore.record(st, entry("m1"))
        assertEquals("入参状态必须原样", before, st.toString())
    }

    @Test fun makeEntryCacheSemantics() {
        val cached = UsageStatsCore.makeEntry(buildJsonObject {
            put("usage", buildJsonObject {
                put("prompt", JsonPrimitive(100))
                put("completion", JsonPrimitive(10))
                put("hit", JsonPrimitive(80))
                put("cacheSupported", JsonPrimitive(true))
            })
            put("msgId", JsonPrimitive("m1"))
        })
        assertEquals(80, (cached["hit"] as JsonPrimitive).content.toInt())
        assertEquals(20, (cached["miss"] as JsonPrimitive).content.toInt())
        val uncached = UsageStatsCore.makeEntry(buildJsonObject {
            put("usage", buildJsonObject {
                put("prompt", JsonPrimitive(100))
                put("completion", JsonPrimitive(10))
            })
        })
        assertEquals(0, (uncached["hit"] as JsonPrimitive).content.toInt())
        assertEquals("cacheSupported 缺失 → 全 prompt 记 miss", 100, (uncached["miss"] as JsonPrimitive).content.toInt())
    }

    @Test fun reconcileEntryWritesCalibWhenActualCostPositive() {
        var s = UsageStatsCore.emptyState()
        s = UsageStatsCore.reconcileEntry(s, entry("m1", cost = 0.0001))
        val calib = s["calib"] as JsonObject
        assertTrue("对账要写出校准系数", calib.isNotEmpty())
    }
}
