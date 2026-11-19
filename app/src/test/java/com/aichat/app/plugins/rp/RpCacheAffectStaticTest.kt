package com.aichat.app.plugins.rp

import com.aichat.app.plugins.affect.Affect
import com.aichat.app.plugins.cache.GCache
import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C5：`[AFFECT_RULE]` 属于静态层，必须由 rpcache 放进 system（lore 之后、facts 之前）。
 *
 * 以前它由 affect 插件 `sys + "\n" + proto` 追加在 system **最末尾**，也就是长期记忆
 * （`[LONG_TERM_MEMORY]`，分层里唯一标 dynamic 的段）之后。gcache 的 `layersFromSegments`
 * 会把「dynamic 之后又出现的 static」判成 `ordered=false`，于是「在记忆前劈开前缀」的
 * 分支（GCache.kt:555-562）永不触发，断点只能落整条 system —— 线上 cached 恒等于
 * system 长度（3385/4012/4015/5014/5021）正是这个后果。
 */
class RpCacheAffectStaticTest {

    private val base = "你是一位角色扮演助手。"

    private fun state(): JObj {
        var st = RpState.newState()
        fun set(id: String, text: String) {
            st = RpState.setBlock(st, id, text, 1_700_000_000_000L)["state"] as JObj
        }
        set("world", "蒸汽朋克港城，永夜，齿轮与锅炉。")
        set("character", "薇拉，机械师，19 岁，说话简短。")
        set("memory", "玩家在第三章救过薇拉。")      // → dynamic / L2 / E
        set("delta", "薇拉刚刚提到她怕水。")
        set("state", "时间：第 4 天清晨｜地点：锅炉房｜情绪：戒备")
        return st
    }

    private val facts = "[ESTABLISHED_FACTS]\n- 玩家欠薇拉一笔钱"

    @Test
    fun protocolSitsInsideStaticLayerBeforeFacts() {
        val st = state()
        val sys = RpCacheCore.buildSystem(st, base, facts, affect = true)
        val iOpt = sys.indexOf("[AFFECT_RULE]")
        val iFacts = sys.indexOf("[ESTABLISHED_FACTS]")
        val iMem = sys.indexOf("[LONG_TERM_MEMORY]")
        assertTrue("协议必须在 system 里", iOpt >= 0)
        assertTrue("协议排在事实账本之前", iOpt < iFacts)
        assertTrue("协议排在长期记忆之前（否则又是 dynamic 之后的 static）", iOpt < iMem)
        assertTrue("协议文本逐字来自 Affect", sys.contains(Affect.protocolText()))
    }

    @Test
    fun withoutAffectFlagOutputIsUnchanged() {
        // flag=false 必须与 1.5.3 逐字节一致：affect 插件仍自行追加
        val st = state()
        assertFalse(RpCacheCore.buildSystem(st, base, facts).contains("[AFFECT_RULE]"))
        assertEquals(
            RpCacheCore.buildSystem(st, base, facts),
            RpCacheCore.buildSystem(st, base, facts, affect = false),
        )
        assertEquals(
            RpCacheCore.staticText(st, base, facts),
            RpCacheCore.staticText(st, base, facts, affect = false),
        )
    }

    @Test
    fun existingProtocolInBaseIsNotDuplicated() {
        val st = state()
        val sys = RpCacheCore.buildSystem(st, "[AFFECT_RULE] 旧协议\n" + base, facts, affect = true)
        assertEquals("base 里已有就不再加一份", 1, Regex("\\[AFFECT_RULE]").findAll(sys).count())
    }

    @Test
    fun segmentsKeepLayersOrdered() {
        val st = state()
        // 1) 旧布局：协议被 affect 追加在 system 末尾（memory 之后）
        val legacySystem = RpCacheCore.buildSystem(st, base, facts, affect = false) + "\n" + Affect.protocolText()
        val legacySegs = RpCacheCore.buildSystemSegments(st, base, facts, affect = false).map { seg ->
            jobj("layer" to seg.layer, "kind" to seg.kind, "title" to seg.title, "gl" to seg.gl, "text" to seg.text)
        }
        assertFalse("旧布局：dynamic 之后还有 static", GCache.layersFromSegments(legacySystem, legacySegs)["ordered"].truthy())

        // 2) 新布局：协议在静态层内，memory 仍是最后一段
        val sys = RpCacheCore.buildSystem(st, base, facts, affect = true)
        val segs = RpCacheCore.buildSystemSegments(st, base, facts, affect = true).map { seg ->
            jobj("layer" to seg.layer, "kind" to seg.kind, "title" to seg.title, "gl" to seg.gl, "text" to seg.text)
        }
        val layers = GCache.layersFromSegments(sys, segs)
        assertTrue("新布局：层序只降不升", layers["ordered"].truthy())
        assertTrue("动态段只剩长期记忆", layers["dynamicText"].jstr().startsWith("[LONG_TERM_MEMORY]"))
        assertTrue("协议进静态前缀", layers["staticText"].jstr().contains("[AFFECT_RULE]"))
        assertEquals("段拼回去必须等于 system", sys, segs.joinToString("\n\n") { it["text"].jstr() })
    }
}