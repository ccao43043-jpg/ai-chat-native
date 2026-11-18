package com.aichat.app.plugins

import com.aichat.app.domain.ChatOptions
import com.aichat.app.domain.ChatPlugin
import com.aichat.app.domain.RequestContext
import com.aichat.app.domain.Slot
import com.aichat.app.domain.WireMessage
import com.aichat.app.domain.model.AppSettings
import com.aichat.app.domain.model.Conversation
import com.aichat.app.domain.model.Message
import com.aichat.app.domain.model.ProviderConfig
import com.aichat.app.domain.model.ProviderType
import com.aichat.app.domain.model.Role
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 召回必须活在 rpcache 的预算里。
 *
 * 背景（真问题）：rpcache 用 `plan().limit` 裁历史时，召回段还不存在 —— 它是所有插件跑完之后
 * 由 `assembleRecall` 拼上去的。于是「预算刚好够」的请求会被一段 1200 token 的召回顶出模型窗口，
 * 服务端只会报超长或者静默截断，而界面上一切正常。
 *
 * 修法：rpcache 把 `remaining` 写进 `ctx.meta["rpBudget"]`，装配时按它给召回封顶。
 */
class RecallBudgetTest {
    private fun settings() = AppSettings(
        providers = emptyList(),
        plugins = buildJsonObject { putJsonObject("recall") { put("unified", true) } },
    )

    private class BudgetStub(private val remaining: Int) : ChatPlugin {
        override val id = "budgetstub"
        override val name = "预算桩"
        override val defaultEnabled = true
        override val perConversation = false
        override suspend fun onBeforeRequest(ctx: RequestContext, status: (String?) -> Unit): RequestContext {
            val o = JsonObject(
                mapOf(
                    "limit" to JsonPrimitive(1000),
                    "used" to JsonPrimitive(1000 - remaining),
                    "remaining" to JsonPrimitive(remaining),
                ),
            )
            return ctx.copy(meta = JsonObject(ctx.meta.toMutableMap().also { it["rpBudget"] = o }))
        }
    }

    private class TagStub(private val text: String) : ChatPlugin {
        override val id = "tagstub"
        override val name = "状态行桩"
        override val defaultEnabled = true
        override val perConversation = false
        override suspend fun onBeforeRequest(ctx: RequestContext, status: (String?) -> Unit): RequestContext {
            ctx.fragments.add(Slot.S4_TAG, "stub", text, priority = 100)
            return ctx
        }
    }

    private class RecallStub : ChatPlugin {
        override val id = "recallstub"
        override val name = "召回桩"
        override val defaultEnabled = true
        override val perConversation = false
        override suspend fun onBeforeRequest(ctx: RequestContext, status: (String?) -> Unit): RequestContext {
            ctx.fragments.add(Slot.S3_RECALL, "lore", "第一条召回：码头在北边", tokens = 8, priority = 100)
            ctx.fragments.add(Slot.S3_RECALL, "knowledge", "第二条召回：".padEnd(80, 'x'), tokens = 100, priority = 40)
            return ctx
        }
    }

    private fun conv(): Conversation {
        val t = 1_700_000_000_000L
        return Conversation(
            id = "c1", title = "海边渔村", providerId = "p1", model = "mock-1",
            createdAt = t, updatedAt = t,
            messages = listOf(Message(id = "u1", role = Role.USER, content = "码头在哪？", createdAt = t)),
        )
    }

    private fun ctx(): RequestContext {
        val c = conv()
        return RequestContext(
            conv = c,
            provider = ProviderConfig(
                id = "p1", presetId = "custom", name = "本地", type = ProviderType.OPENAI,
                baseUrl = "http://localhost/v1", models = listOf("mock-1"),
            ),
            model = "mock-1",
            systemPrompt = "你是助手。",
            messages = c.messages.map { WireMessage(it.role, it.content, srcId = it.id) },
            opts = ChatOptions(),
            settings = settings(),
            lastUserId = "u1",
        )
    }

    private fun run(remaining: Int?, withTag: String? = null): RequestContext = runBlocking {
        val reg = PluginRegistryImpl()
        remaining?.let { reg.register(BudgetStub(it)) }
        withTag?.let { reg.register(TagStub(it)) }
        reg.register(RecallStub())
        reg.runBeforeRequest(ctx(), {})
    }

    @Test
    fun remainingSmallDropsTheSecondItem() {
        val out = run(remaining = 12)
        val user = out.messages.last().content
        assertTrue(user.contains("第一条召回"))
        assertFalse(user.contains("第二条召回"))
    }

    @Test
    fun zeroRemainingSkipsRecallEntirelyAndLeavesANote() {
        val out = run(remaining = 0)
        assertEquals("码头在哪？", out.messages.last().content)
        assertTrue(out.trace.notes().any { it.contains("召回预算不足") })
    }

    @Test
    fun withoutBudgetMetaItFallsBackToTheDefault() {
        val out = run(remaining = null)
        val user = out.messages.last().content
        assertTrue(user.contains("第一条召回"))
        assertTrue(user.contains("第二条召回"))
    }

    @Test
    fun tagConsumesTheBudgetToo() {
        // 状态行也进末条 user，必须一起算：预算 5 token，状态行就吃光了 → 召回整段跳过
        val longTag = "心情：平静；关系：戒备；场景：清晨的码头，薄雾还没散，船影摇晃"
        val withTag = run(remaining = 5, withTag = longTag)
        assertTrue(withTag.messages.last().content.contains("心情：平静"))
        assertFalse(withTag.messages.last().content.contains("[RECALL]"))

        val withoutTag = run(remaining = 5)
        assertTrue(withoutTag.messages.last().content.contains("[RECALL]"))
    }
}
