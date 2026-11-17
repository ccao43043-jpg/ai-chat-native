package com.aichat.app.plugins

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件页「说真话」的数据来源（PLAN-CONTEXT-ARCH §3.3）。
 *
 * 这些用例守的是可见性契约：快照里必须能查到「本会话谁真跑了 / 谁为什么没跑」，
 * 以及缓存路线（owner + 原因）。界面只做展示，判定逻辑在注册表里，这里只测记录与查询。
 */
class PluginActivationTest {
    @After
    fun tearDown() {
        PluginActivation.reset()
    }

    @Test
    fun snapshotIsQueryablePerPlugin() {
        PluginActivation.record(
            convId = "c1",
            convTitle = "海边渔村",
            model = "gemini-2.5-pro",
            now = 1000L,
            rows = listOf(
                PluginActivation.Row("rpcache", active = true, reason = ""),
                PluginActivation.Row("gcache", active = false, reason = "本会话路线是 prefix，不改请求"),
            ),
            cacheOwner = "prefix",
            cacheReason = "原生 Gemini 隐式缓存，不能动请求",
        )
        val snap = PluginActivation.last!!
        assertEquals("海边渔村", snap.convTitle)
        assertEquals("gemini-2.5-pro", snap.model)
        assertTrue(PluginActivation.rowOf("rpcache")!!.active)
        assertFalse(PluginActivation.rowOf("gcache")!!.active)
        assertEquals("本会话路线是 prefix，不改请求", PluginActivation.rowOf("gcache")!!.reason)
        assertEquals("prefix", snap.cacheOwner)
        assertNull(PluginActivation.rowOf("mmcache"))
    }

    @Test
    fun blankTitleFallsBackSoTheUiNeverShowsEmpty() {
        PluginActivation.record("c2", "  ", "m", 1L, emptyList())
        assertEquals("未命名对话", PluginActivation.last!!.convTitle)
    }

    @Test
    fun resetClearsSnapshot() {
        PluginActivation.record("c3", "t", "m", 1L, emptyList())
        PluginActivation.reset()
        assertNull(PluginActivation.last)
        assertNull(PluginActivation.rowOf("rpcache"))
    }
}
