package com.aichat.app.ui

import com.aichat.app.data.DataGraph
import com.aichat.app.net.ProviderRules
import com.aichat.app.ui.chat.ChatController
import com.aichat.app.ui.common.fake.FakeDeps
import com.aichat.app.ui.common.fake.FakePluginRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「Flex 档」的契约：**按服务商存**（写进 settings.extras.flexProviders，false 也留痕）。
 *
 * 1.7.9 及更早的旧 bug：弹层每次重开都从「当前模型串」重播种（ModelPicker 的无 key remember），
 * 为了点思考等级反复开合弹层就把 `:flex` 悄悄洗成裸名 —— 账单实测多付 37%。
 * 修复后：播种读持久值（服务商从没设过才回落模型串，保住升级用户），开关一旦动过一律以存储为准。
 */
class FlexPersistTest {
    private val jobs = mutableListOf<Job>()
    @After fun tearDown() { jobs.forEach { it.cancel() } }

    private fun make(): Pair<ChatController, DataGraph> {
        val g = DataGraph.inMemory()
        val deps = FakeDeps.create(g, engineDelayMs = 2, plugins = FakePluginRegistry())
        val scope = CoroutineScope(Dispatchers.Default + Job().also { jobs += it })
        return ChatController(deps, scope) to g
    }

    private suspend fun until(cond: suspend () -> Boolean) {
        withTimeout(10_000) { while (!cond()) delay(5) }
    }

    private suspend fun onDisk(g: DataGraph, providerId: String): Boolean? {
        val extras = FakeDeps.create(g, engineDelayMs = 2, plugins = FakePluginRegistry()).settings.settings.value.extras
        return ProviderRules.flexOf(extras, providerId)
    }

    @Test
    fun flexIsStoredPerProviderAndSurvivesReopen() {
        runBlocking {
            val (c, g) = make()
            val conv = g.chats.create("openrouter", "google/gemini-3.8-flash-20260902")
            c.select(conv.id)
            until { c.state.value.conv?.id == conv.id }

            c.setFlexOn(conv.providerId, true)
            assertTrue("点完立刻生效（覆盖层）", c.flexOnOf(conv.providerId, conv.model))

            until { ProviderRules.flexOf(c.settings.value.extras, conv.providerId) == true }
            assertEquals("落盘也在", true, onDisk(g, conv.providerId))

            // 重开弹层 ≈ 新 controller 读同一份存档：播种必须仍是开
            val deps2 = FakeDeps.create(g, engineDelayMs = 2, plugins = FakePluginRegistry())
            val scope2 = CoroutineScope(Dispatchers.Default + Job().also { jobs += it })
            assertTrue("重开后播种仍为开", ChatController(deps2, scope2).flexOnOf(conv.providerId, conv.model))
        }
    }

    @Test
    fun explicitOffBeatsFlexModelStringAndLeavesTrace() {
        runBlocking {
            val (c, g) = make()
            // 模型串带 :flex（升级用户升级前手开的形态）：从没设过 → 播种回落为开
            val conv = g.chats.create("openrouter", "google/gemini-3.8-flash-20260902:flex")
            c.select(conv.id)
            until { c.state.value.conv?.id == conv.id }
            assertTrue("回落：模型串带 :flex 就播种为开", c.flexOnOf(conv.providerId, conv.model))

            c.setFlexOn(conv.providerId, false)
            until { ProviderRules.flexOf(c.settings.value.extras, conv.providerId) == false }
            assertFalse("显式关压过模型串（不再洗掉、也不再被模型串顶回来）",
                c.flexOnOf(conv.providerId, conv.model))
            assertTrue("显式关要留痕（区别于从没设过的 null）",
                ProviderRules.flexOf(c.settings.value.extras, conv.providerId) == false)
            assertEquals(false, onDisk(g, conv.providerId))
        }
    }

    @Test
    fun providersKeepTheirOwnSwitch() {
        runBlocking {
            val (c, g) = make()
            val conv = g.chats.create("openrouter", "google/gemini-3.8-flash-20260902")
            c.select(conv.id)
            until { c.state.value.conv?.id == conv.id }

            c.setFlexOn(conv.providerId, true)
            until { ProviderRules.flexOf(c.settings.value.extras, conv.providerId) == true }

            // 别的服务商不受影响：从没设过 → 按它自己的模型串回落
            assertTrue("OR 服务商的开不漏给别的服务商",
                ProviderRules.resolveFlex(c.settings.value.extras, "other-provider", "x:flex"))
            assertEquals(true, onDisk(g, conv.providerId))
        }
    }

    @Test
    fun flexToggleUpdatesCurrentConversationAndMatchingLastModel() {
        runBlocking {
            val (c, g) = make()
            val conv = g.chats.create("openrouter", "google/gemini-3.8-flash-20260902")
            c.select(conv.id)
            until { c.state.value.conv?.id == conv.id }

            // 先建立 lastProviderId = openrouter / lastModel = m
            c.setModel(conv.providerId, "google/gemini-3.8-flash-20260902")
            until { c.state.value.conv?.model == "google/gemini-3.8-flash-20260902" }
            until { c.settings.value.lastModel == "google/gemini-3.8-flash-20260902" }

            c.setFlexOn(conv.providerId, true)
            until { c.state.value.conv?.model == "google/gemini-3.8-flash-20260902:flex" }
            until { c.settings.value.lastModel == "google/gemini-3.8-flash-20260902:flex" }
            assertEquals("google/gemini-3.8-flash-20260902:flex", c.currentTarget()?.second)

            c.setFlexOn(conv.providerId, false)
            until { c.state.value.conv?.model == "google/gemini-3.8-flash-20260902" }
            until { c.settings.value.lastModel == "google/gemini-3.8-flash-20260902" }
        }
    }

    @Test
    fun openingLegacyConversationRepairsDuplicateFlexSuffix() {
        runBlocking {
            val (c, g) = make()
            val conv = g.chats.create("openrouter", "google/gemini-3.8-flash-20260902:flex:flex")
            c.select(conv.id)
            until { c.state.value.conv?.id == conv.id }
            assertEquals("google/gemini-3.8-flash-20260902:flex", c.state.value.conv?.model)
        }
    }

    @Test
    fun nonOpenRouterModelIsNeverRewrittenByFlex() {
        runBlocking {
            val (c, g) = make()
            val conv = g.chats.create("deepseek", "deepseek-chat:flex")
            c.select(conv.id)
            until { c.state.value.conv?.id == conv.id }
            assertEquals("加载非 OpenRouter 会话不改模型串", "deepseek-chat:flex", c.state.value.conv?.model)

            c.setFlexOn(conv.providerId, true)
            until { ProviderRules.flexOf(c.settings.value.extras, conv.providerId) == true }
            assertEquals("非 OpenRouter 仍不改模型串", "deepseek-chat:flex", c.state.value.conv?.model)
        }
    }
}
