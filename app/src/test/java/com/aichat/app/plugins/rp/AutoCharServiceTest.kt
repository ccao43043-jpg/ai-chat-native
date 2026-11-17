package com.aichat.app.plugins.rp

import com.aichat.app.domain.model.AppSettings
import com.aichat.app.domain.model.Conversation
import com.aichat.app.domain.model.Message
import com.aichat.app.domain.model.PromptTemplate
import com.aichat.app.domain.model.Role
import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.js.jobj
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `AutoCharService` = AutoChar 纯函数 + 一次真实请求 + 写回结果。
 * 这层的价值在「世界里不乱写」：绑了世界就只补角色卡，世界块必须逐字节不变。
 */
class AutoCharServiceTest {

    // 模型按旧版协议回「【标题】正文」分段，不是 JSON
    private val characterReply = "【角色卡】\n- 姓名：阿澈\n- 身份：渔村守灯人\n【示例对白】\n阿澈：今晚风向不对。"

    private fun service(reply: String?) = AutoCharService { _, _ -> reply }

    private fun templates(): List<PromptTemplate> = listOf(
        PromptTemplate(
            id = "t-rp-immersive", name = "沉浸式 RP（全自动）", emoji = "🎬", content = "按分层设定扮演",
            tags = listOf("角色扮演"), pinned = true, builtin = true, skill = false,
        ),
        PromptTemplate(
            id = "t-rp-cardgen", name = "角色卡生成", emoji = "🃏", content = "输出 8 段",
            tags = listOf("角色扮演"), pinned = true, builtin = true, skill = true,
        ),
    )

    private fun settings(mode: String = "auto") = AppSettings(providers = emptyList(), autoCharMode = mode)

    /** 用 RpState.setBlock 造一份真实结构的分层设定 */
    private fun state(vararg blocks: Pair<String, String>): JsonObject {
        // 必须从 RpState.newState() 起手：setBlock 按 id 在 blocks 列表里找块，空 jobj 会直接抛
        var st: JObj = RpState.newState()
        blocks.forEachIndexed { i, (id, text) ->
            @Suppress("UNCHECKED_CAST")
            st = RpState.setBlock(st, id, text, 1_700_000_000_000L + i)["state"] as JObj
        }
        return Js.toJson(st) as JsonObject
    }

    private fun conv(
        worldId: String? = null,
        rpState: JsonObject = state("world" to "海边渔村，终年雾气。"),
        templateId: String? = "t-rp-immersive",
    ): Conversation {
        val t = 1_700_000_000_000L
        return Conversation(
            id = "c1", title = "海边渔村", providerId = "p1", model = "mock-1",
            createdAt = t, updatedAt = t,
            messages = listOf(Message(id = "u1", role = Role.USER, content = "我想当个守灯人", createdAt = t)),
            systemPrompt = "按分层设定扮演",
            templateId = templateId,
            rpState = rpState,
            extras = if (worldId != null) {
                JsonObject(mapOf("worldId" to JsonPrimitive(worldId), "worldName" to JsonPrimitive("海边渔村")))
            } else {
                JsonObject(emptyMap())
            },
        )
    }

    /** 走 service 自己那条路读世界块，避免测试和实现各读一套 */
    private fun worldOf(c: Conversation): String {
        val svc = service(null)
        return AutoChar.worldOf(svc.convJs(c))?.world.orEmpty()
    }

    @Test
    fun inWorldOnlyTouchesCharacterAndKeepsWorldByteIdentical() = runBlocking {
        val before = conv()
        val worldBefore = worldOf(before)
        assertTrue(worldBefore.contains("终年雾气"))

        val out = service(characterReply).generate(before, templates(), settings())

        assertTrue(out.ok)
        assertEquals(AutoChar.Scope.CHARACTER_ONLY, out.scope)
        // applied 里是中文标题（TITLES 的值），跟设置面板显示的一致
        assertEquals(listOf("角色卡", "示例对白"), out.applied)
        val after = before.copy(rpState = Js.toJson(out.state!!) as JsonObject)
        assertEquals(worldBefore, worldOf(after))
    }

    @Test
    fun worldIdAloneNarrowsScopeEvenWhenBlocksAreEmpty() = runBlocking {
        val c = conv(worldId = "w1", rpState = JsonObject(emptyMap()))
        val out = service(characterReply).generate(c, templates(), settings())
        assertEquals(AutoChar.Scope.CHARACTER_ONLY, out.scope)
        assertTrue(out.ok)
    }

    @Test
    fun existingCharacterIsNotOverwritten() = runBlocking {
        val c = conv(rpState = state("world" to "海边渔村", "character" to "- 姓名：老周"))
        val out = service(characterReply).generate(c, templates(), settings())
        assertFalse(out.ok)
        assertEquals("already", out.status)
        assertEquals("这个世界的角色卡已经有了，没有覆盖", out.message)
    }

    @Test
    fun fullScopeStillWritesWorldWhenNotBoundToAWorld() = runBlocking {
        val c = conv(templateId = "t-rp-immersive", rpState = JsonObject(emptyMap()))
        val out = service("【世界观】\n只有一片雾\n【角色卡】\n- 姓名：阿澈").generate(c, templates(), settings())
        assertEquals(AutoChar.Scope.FULL, out.scope)
        assertTrue(out.ok)
        assertEquals("已写入角色设定", out.message)
        assertTrue(out.applied.contains("世界观"))
        assertTrue(out.applied.contains("角色卡"))
    }

    @Test
    fun modelFailureReturnsFailedInsteadOfThrowing() = runBlocking {
        val out = service(null).generate(conv(), templates(), settings())
        assertFalse(out.ok)
        assertEquals("failed", out.status)
        assertEquals("模型没有返回内容", out.message)
    }

    @Test
    fun offerHonoursModeAndPrerequisites() {
        val svc = service(characterReply)
        assertTrue(svc.offer(conv(), templates(), settings("auto")))
        assertTrue(svc.offer(conv(), templates(), settings("ask")))
        assertFalse(svc.offer(conv(), templates(), settings("off")))
        // 已经有角色卡 → 不再问
        assertFalse(
            svc.offer(
                conv(rpState = state("world" to "w", "character" to "- 姓名：老周")),
                templates(), settings(),
            )
        )
    }

    @Test
    fun skillContentIsPickedFromCardgenTemplate() {
        val svc = service(characterReply)
        assertEquals("输出 8 段", svc.skillOf(templates()))
        assertEquals(null, svc.skillOf(emptyList()))
    }
}
