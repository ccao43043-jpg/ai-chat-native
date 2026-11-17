package com.aichat.app.plugins.rp

import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.jobj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WorldResolver]：三处世界读法（extras.worldId / blocks.world / worldRef.id）统一口径。
 *
 * 核心约定：**绑定关系优先于内容** —— 声明了世界 id 就算「在世界里」，
 * 哪怕世界观正文还是空的（用户刚选完模板、还没写设定）。
 */
class WorldResolverTest {

    private fun conv(extras: Map<String, Any?> = emptyMap(), state: JObj? = null): JObj {
        val c = jobj()
        extras.forEach { (k, v) -> c[k] = v }
        if (state != null) {
            val plugins = jobj()
            val rp = jobj()
            rp["state"] = state
            plugins["rpcache"] = rp
            c["plugins"] = plugins
        }
        return c
    }

    private fun stateWithWorld(text: String, lore: String = ""): JObj {
        // RpState.setBlock 返回 {state, changed} 包装，不是状态本身
        var st = RpState.newState()
        st = RpState.setBlock(st, "world", text, 1L)["state"] as JObj
        if (lore.isNotEmpty()) {
            // 常驻条目的真实形状：mode=constant + name/content（selectConstantLore 按 mode 过滤）
            st["lore"] = lore.split("\n").map {
                jobj("name" to "地理", "content" to it, "mode" to "constant", "keys" to ArrayList<Any?>())
            }.toMutableList()
        }
        return st
    }

    @Test
    fun noWorldAnywhereIsPureChat() {
        assertNull(WorldResolver.of(conv()))
        assertEquals("", WorldResolver.boundId(conv()))
    }

    @Test
    fun extrasIdAloneBindsEvenWithoutBlocks() {
        val r = WorldResolver.of(conv(mapOf("worldId" to "w1", "worldName" to "海边渔村")))
        assertEquals("w1", r?.id)
        assertEquals("海边渔村", r?.name)
        assertEquals("", r?.text)
        // 绑定关系在，但正文还没写 —— hasContent=false 才是「世界设定为空」的正确判断依据
        assertFalse(r!!.hasContent)
        assertTrue(r.source.contains("extras"))
    }

    @Test
    fun blocksWorldAloneCountsAsWorld() {
        val r = WorldResolver.of(conv(state = stateWithWorld("终年积雪的港口")))
        assertEquals("", r?.id)
        assertTrue(r!!.hasContent)
        assertEquals("终年积雪的港口", r.text)
        assertTrue(r.source.contains("blocks"))
    }

    @Test
    fun worldRefIdIsAcceptedAndReportedSeparately() {
        val st = RpState.newState().also { it["worldRef"] = jobj("id" to "w9", "name" to "灯塔") }
        val r = WorldResolver.of(conv(state = st))
        assertEquals("w9", r?.id)
        assertEquals("灯塔", r?.name)
        assertTrue(r!!.source.contains("worldRef"))
    }

    @Test
    fun extrasIdWinsOverWorldRefId() {
        val st = RpState.newState().also { it["worldRef"] = jobj("id" to "old") }
        val r = WorldResolver.of(conv(mapOf("worldId" to "new"), st))
        assertEquals("new", r?.id)
    }

    @Test
    fun constantLoreIsCarriedAsLoreField() {
        val r = WorldResolver.of(conv(state = stateWithWorld("海边", lore = "终年积雪。")))
        assertTrue(r!!.hasContent)
        assertTrue(r.lore.contains("终年积雪"))
    }

    @Test
    fun autoCharScopeFollowsTheResolver() {
        // 只绑 id、没有块内容也要按「世界里」处理（否则 AI 会把空的世界写出来）
        val bound = conv(mapOf("worldId" to "w1"))
        assertEquals(AutoChar.Scope.CHARACTER_ONLY, AutoChar.scopeOf(bound))
        assertEquals(AutoChar.Scope.FULL, AutoChar.scopeOf(conv()))
        // AutoChar.worldOf 与解析器同源：id 一致、内容一致
        val w = AutoChar.worldOf(conv(mapOf("worldId" to "w1", "worldName" to "海边渔村"), stateWithWorld("雪港")))
        assertEquals("w1", w?.worldId)
        assertEquals("雪港", w?.world)
    }
}
