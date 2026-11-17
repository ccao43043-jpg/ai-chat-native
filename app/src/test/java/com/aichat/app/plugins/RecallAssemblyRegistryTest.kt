package com.aichat.app.plugins

import com.aichat.app.domain.ChatPlugin
import com.aichat.app.domain.RequestContext
import com.aichat.app.domain.Slot
import com.aichat.app.domain.model.AppSettings
import com.aichat.app.domain.model.Conversation
import com.aichat.app.domain.model.Message
import com.aichat.app.domain.model.ProviderConfig
import com.aichat.app.domain.model.ProviderType
import com.aichat.app.domain.model.Role
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统一装配的**验收标准**（PLAN-CONTEXT-ARCH §5）：
 * `systemPromptByteEqualWhenOnlyRecallChanges` —— 只有召回内容变化时，system 必须逐字节不变。
 *
 * 这是整套改造真正的目的：召回每轮都在变，system 是缓存前缀。以前各插件各自动手，
 * 一旦某个插件顺手改了 system，前缀就崩、命中率归零，而且从界面上完全看不出来。
 */
class RecallAssemblyRegistryTest {
    private fun settings(unified: Boolean) = AppSettings(
        providers = emptyList(),
        plugins = buildJsonObject { putJsonObject("recall") { put("unified", unified) } },
    )

    private class StubRecall(var text: String) : ChatPlugin {
        override val id = "stubrecall"
        override val name = "召回桩"
        override val defaultEnabled = true
        override val perConversation = false
        override suspend fun onBeforeRequest(ctx: RequestContext, status: (String?) -> Unit): RequestContext {
            ctx.fragments.add(Slot.S3_RECALL, "stub", text, tokens = 8, priority = 60)
            return ctx
        }
    }

    private fun conv(): Conversation {
        val t = 1_700_000_000_000L
        val user = Message(id = "u1", role = Role.USER, content = "灯塔在哪个方向？", createdAt = t)
        return Conversation(
            id = "c1", title = "海边渔村", providerId = "p1", model = "mock-1",
            createdAt = t, updatedAt = t, messages = listOf(user),
        )
    }

    private fun ctx(s: AppSettings): RequestContext {
        val c = conv()
        return RequestContext(
            conv = c,
            provider = ProviderConfig(
                id = "p1", presetId = "custom", name = "本地", type = ProviderType.OPENAI,
                baseUrl = "http://localhost/v1", models = listOf("mock-1"),
            ),
            model = "mock-1",
            systemPrompt = "你是助手。\n\n[WORLD]\n海边渔村",
            messages = c.messages.map { com.aichat.app.domain.WireMessage(it.role, it.content, srcId = it.id) },
            opts = com.aichat.app.domain.ChatOptions(),
            settings = s,
            lastUserId = "u1",
        )
    }

    @Test
    fun onlyRecallChangesKeepsSystemByteEqual() = runBlocking {
        val stub = StubRecall("旧的召回内容")
        val reg = PluginRegistryImpl().also { it.register(stub) }
        val a = reg.runBeforeRequest(ctx(settings(unified = true)), {})
        stub.text = "新的召回内容"
        val b = reg.runBeforeRequest(ctx(settings(unified = true)), {})
        assertEquals(a.systemPrompt, b.systemPrompt)
        assertTrue(a.messages.last().content.contains("旧的召回内容"))
        assertTrue(b.messages.last().content.contains("新的召回内容"))
    }

    @Test
    fun recallIsAppendedOnceAndNotDuplicated() = runBlocking {
        val reg = PluginRegistryImpl().also { it.register(StubRecall("灯塔在渔村东侧")) }
        val out = reg.runBeforeRequest(ctx(settings(unified = true)), {})
        val user = out.messages.last { it.role == Role.USER }.content
        assertTrue(user.startsWith("灯塔在哪个方向？"))
        assertTrue(user.contains("[RECALL]"))
        assertEquals(1, Regex("灯塔在渔村东侧").findAll(user).count())
    }

    @Test
    fun switchOffKeepsOldBehaviour() = runBlocking {
        val reg = PluginRegistryImpl().also { it.register(StubRecall("灯塔在渔村东侧")) }
        val out = reg.runBeforeRequest(ctx(settings(unified = false)), {})
        val user = out.messages.last { it.role == Role.USER }.content
        // 开关关着：片段被忽略，末条 user 一字不改
        assertEquals("灯塔在哪个方向？", user)
        assertFalse(user.contains("[RECALL]"))
    }
}
