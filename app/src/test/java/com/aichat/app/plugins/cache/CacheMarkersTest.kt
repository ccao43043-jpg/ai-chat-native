package com.aichat.app.plugins.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 显式缓存断点（对齐旧版 `placeMarkers`）。
 *
 * 三条不能忘的规则：不足 1024 token 不标、最多 4 个、历史断点要给它留一个位置。
 */
class CacheMarkersTest {
    @Test
    fun shortSystemIsNotMarked() {
        val p = CacheMarkers.plan(sysTokens = 800, staticTokens = 800, memoryTokens = 0, summaryTokens = 0, historyTokens = 0, historyCount = 0)
        assertFalse(p.system)
        assertFalse(p.mutatesRequest)
    }

    @Test
    fun longSystemGetsSystemMarker() {
        val p = CacheMarkers.plan(sysTokens = 3000, staticTokens = 3000, memoryTokens = 0, summaryTokens = 0, historyTokens = 0, historyCount = 0)
        assertTrue(p.system)
        assertEquals(1, p.count)
        assertEquals(0, p.systemSplit)
    }

    @Test
    fun stablePrefixSplitsWhenMemoryIsPresent() {
        val p = CacheMarkers.plan(
            sysTokens = 3000, staticTokens = 1500, memoryTokens = 500, summaryTokens = 800,
            historyTokens = 0, historyCount = 0,
            stablePrefixLen = 2000, systemLen = 3000,
        )
        assertTrue(p.system)
        assertEquals(2000, p.systemSplit)
        assertEquals(2, p.count)
    }

    @Test
    fun noSplitWhenStablePartIsTooShort() {
        val p = CacheMarkers.plan(
            sysTokens = 3000, staticTokens = 300, memoryTokens = 500, summaryTokens = 200,
            historyTokens = 0, historyCount = 0,
            stablePrefixLen = 500, systemLen = 3000,
        )
        assertEquals(0, p.systemSplit)
    }

    @Test
    fun noSplitWithoutStablePrefix() {
        val p = CacheMarkers.plan(
            sysTokens = 3000, staticTokens = 1500, memoryTokens = 500, summaryTokens = 800,
            historyTokens = 0, historyCount = 0,
            stablePrefixLen = 0, systemLen = 3000,
        )
        assertEquals(0, p.systemSplit)
    }

    @Test
    fun historyGetsTheLastMessageMarked() {
        val p = CacheMarkers.plan(
            sysTokens = 500, staticTokens = 500, memoryTokens = 0, summaryTokens = 0,
            historyTokens = 2000, historyCount = 6,
        )
        assertEquals(5, p.historyIndex)
        assertTrue(p.mutatesRequest)
    }

    @Test
    fun systemSplitLeavesRoomForHistory() {
        // maxMarkers = 2 时：system + 历史 已经占满，不该再切一刀
        val p = CacheMarkers.plan(
            sysTokens = 3000, staticTokens = 1500, memoryTokens = 500, summaryTokens = 800,
            historyTokens = 3000, historyCount = 4,
            stablePrefixLen = 2000, systemLen = 3000,
            maxMarkers = 2,
        )
        assertTrue(p.system)
        assertEquals(0, p.systemSplit)
        assertEquals(3, p.historyIndex)
        assertEquals(2, p.count)
    }

    @Test
    fun totalMarkersNeverExceedTheCap() {
        val p = CacheMarkers.plan(
            sysTokens = 3000, staticTokens = 1500, memoryTokens = 500, summaryTokens = 800,
            historyTokens = 3000, historyCount = 4,
            stablePrefixLen = 2000, systemLen = 3000,
            maxMarkers = 4,
        )
        assertTrue(p.count <= 4)
    }

    @Test
    fun onlyQwenExplicitNeedsMarkers() {
        assertTrue(CacheMarkers.needsExplicitMarkers("qwen", "explicit"))
        assertFalse(CacheMarkers.needsExplicitMarkers("qwen", "implicit"))
        assertFalse(CacheMarkers.needsExplicitMarkers("deepseek", "implicit"))
        assertFalse(CacheMarkers.needsExplicitMarkers(null, null))
    }
}
