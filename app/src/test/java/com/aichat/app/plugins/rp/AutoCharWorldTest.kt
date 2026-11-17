package com.aichat.app.plugins.rp

import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.jarr
import com.aichat.app.plugins.js.jobj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 角色生成的作用域（PLAN-AUTOCHAR-WORLD §5）。
 *
 * 守的是两件事：
 * 1. 世界已经写好时，AI 只许补角色卡，不许重写世界/规则/风格；
 * 2. 没有世界时行为与旧版一致（8 段），不能因为加作用域改变专场聊天的结果。
 */
class AutoCharWorldTest {
    private val worldText = "- 时代：海边渔村，禁航季\n- 势力：渔会、灯塔看守人\n- 规则：船必须在涨潮前回港"

    private fun stateWith(vararg blocks: Pair<String, String>): JObj {
        var st: JObj = RpState.newState()
        for ((id, text) in blocks) {
            @Suppress("UNCHECKED_CAST")
            st = RpState.setBlock(st, id, text, 1L)["state"] as JObj
        }
        return st
    }

    private fun convWith(
        worldId: String = "",
        worldName: String = "",
        template: String = "t-rp-immersive",
        state: JObj = stateWith(),
    ): Any? = jobj(
        "templateId" to template,
        "worldId" to worldId,
        "worldName" to worldName,
        "messages" to jarr(jobj("role" to "user", "content" to "我想玩一个海边渔村的故事")),
        "plugins" to jobj("rpcache" to jobj("state" to state)),
    )

    private fun worldConv(vararg blocks: Pair<String, String>, worldId: String = "", template: String = "t-rp-immersive") =
        convWith(worldId = worldId, template = template, state = stateWith(*blocks))

    @Test
    fun noWorldMeansFullScope() {
        assertEquals(AutoChar.Scope.FULL, AutoChar.scopeOf(convWith()))
        assertNull(AutoChar.worldOf(convWith()))
    }

    @Test
    fun worldIdAloneIsEnoughToNarrowTheScope() {
        val c = convWith(worldId = "w1", worldName = "海边渔村")
        assertEquals(AutoChar.Scope.CHARACTER_ONLY, AutoChar.scopeOf(c))
        assertEquals("海边渔村", AutoChar.worldOf(c)!!.worldName)
    }

    @Test
    fun worldBlockAloneIsEnoughToNarrowTheScope() {
        val c = worldConv("world" to worldText)
        val w = AutoChar.worldOf(c)
        assertNotNull(w)
        assertTrue(w!!.hasWorld)
        assertEquals(AutoChar.Scope.CHARACTER_ONLY, AutoChar.scopeOf(c))
    }

    @Test
    fun characterPromptAsksOnlyForCharacterAndFewshot() {
        val p = AutoChar.buildCharacterPrompt("我想当灯塔看守人的女儿", null, AutoChar.WorldContext(world = worldText), null)
        assertTrue(p.contains("【角色卡】"))
        assertTrue(p.contains("【示例对白】"))
        // 世界是只读约束，必须出现在提示词里，否则模型会自己编一个世界
        assertTrue(p.contains("【已有世界设定（只读，不要改写，也不要复述）】"))
        assertTrue(p.contains("灯塔看守人"))
        assertTrue(p.contains("绝对不要输出【世界观】【RP规则】【输出格式】【禁止事项】【写作风格】"))
        assertTrue(p.contains("【用户首条消息】\n我想当灯塔看守人的女儿"))
    }

    @Test
    fun fullScopePromptStillAsksForAllEightSections() {
        val p = AutoChar.buildPrompt("随便编个故事", null, null)
        assertTrue(p.contains("【世界观】"))
        assertTrue(p.contains("【开场白】"))
        assertEquals("【世界观】\n【角色卡】\n【RP规则】\n【输出格式】\n【示例对白】\n【禁止事项】\n【写作风格】\n【开场白】", AutoChar.FORMAT)
    }

    @Test
    fun characterScopeDropsWorldRulesAndStyle() {
        val fields = jobj(
            "world" to "另一个世界观",
            "character" to "- 姓名：阿岚\n- 身份：渔会记录员",
            "rules" to "不许替玩家说话",
            "fewshot" to "「今天风向不对。」",
            "style" to "第三人称过去时",
        )
        val f = AutoChar.filterFields(fields, AutoChar.Scope.CHARACTER_ONLY)
        assertEquals(setOf("character", "fewshot"), f.fields.keys)
        assertEquals(listOf("世界观", "RP规则", "写作风格"), f.dropped)
    }

    @Test
    fun fullScopeKeepsEveryField() {
        val fields = jobj("world" to "w", "character" to "c", "rules" to "r")
        val f = AutoChar.filterFields(fields, AutoChar.Scope.FULL)
        assertEquals(setOf("world", "character", "rules"), f.fields.keys)
        assertTrue(f.dropped.isEmpty())
    }

    @Test
    fun applyInWorldOnlyWritesCharacterAndLeavesWorldByteIdentical() {
        val st = stateWith("world" to worldText)
        val before = RpState.getBlock(st, "world")!!["content"]
        val beforeVersion = RpState.getBlock(st, "world")!!["version"]
        val fields = jobj("world" to "被 AI 改写过的世界", "character" to "- 姓名：阿岚\n- 身份：渔会记录员")
        val r = AutoChar.applyToState(st, fields, AutoChar.Scope.CHARACTER_ONLY, at = 10L)
        assertEquals("applied", r.status)
        assertEquals(listOf("角色卡"), r.applied)
        assertEquals(listOf("世界观"), r.dropped)
        assertEquals(before, RpState.getBlock(r.state, "world")!!["content"])
        assertEquals(beforeVersion, RpState.getBlock(r.state, "world")!!["version"])
        assertTrue((RpState.getBlock(r.state, "character")!!["content"] as String).contains("阿岚"))
    }

    @Test
    fun applyInWorldNeverOverwritesAnExistingCharacter() {
        val st = stateWith("world" to worldText, "character" to "- 姓名：老周")
        val r = AutoChar.applyToState(st, jobj("character" to "- 姓名：阿岚"), AutoChar.Scope.CHARACTER_ONLY, at = 10L)
        assertEquals("already", r.status)
        assertTrue(r.applied.isEmpty())
        assertEquals("- 姓名：老周", RpState.getBlock(r.state, "character")!!["content"])
    }

    @Test
    fun applyWithOverwriteReplacesCharacterOnly() {
        val st = stateWith("world" to worldText, "character" to "- 姓名：老周")
        val r = AutoChar.applyToState(st, jobj("character" to "- 姓名：阿岚"), AutoChar.Scope.CHARACTER_ONLY, at = 10L, overwrite = true)
        assertEquals("applied", r.status)
        assertEquals(listOf("角色卡"), r.applied)
        assertTrue((RpState.getBlock(r.state, "character")!!["content"] as String).contains("阿岚"))
    }

    @Test
    fun fullScopeStillWritesEverythingIncludingWorld() {
        val r = AutoChar.applyToState(
            RpState.newState(),
            jobj("world" to worldText, "character" to "- 姓名：阿岚"),
            AutoChar.Scope.FULL,
            at = 10L,
        )
        assertEquals("applied", r.status)
        assertEquals(listOf("世界观", "角色卡"), r.applied)
        assertTrue((RpState.getBlock(r.state, "world")!!["content"] as String).contains("禁航季"))
    }

    @Test
    fun shouldOfferAsksEvenWhenTemplateIsNotRpAsLongAsThereIsAWorld() {
        val c = worldConv("world" to worldText, template = "t-general")
        assertTrue(AutoChar.shouldOffer(c, emptyList(), jobj("autoCharMode" to "auto")))
    }

    @Test
    fun shouldOfferStaysQuietWhenCharacterAlreadyExists() {
        val c = worldConv("world" to worldText, "character" to "- 姓名：阿岚")
        assertFalse(AutoChar.shouldOffer(c, emptyList(), jobj("autoCharMode" to "auto")))
    }
}
