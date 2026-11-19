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
 * 「思考等级」的契约：**按模型存**（服务商 + 模型名一份，写进 settings.extras.thinkingLevels）。
 *
 * 用户报过两轮问题——先是「点不动」（热区），再是「退出再进变回自动」。后者查到两件事：
 * ① 存档从没丢过（对话 extras 里一直有）；② 弹层高亮读的是整份对话快照，会被晚到的回填盖掉。
 * 既然「思考深度」是模型的属性，改成一模型一档、跨对话共享，顺带把快照这条路彻底绕开。
 *
 * 引擎侧兜底仍然读旧字段 `conv.extras.thinkingLevel`（1.7.5 及更早写在对话上），见 [ProviderRules.resolveLevel]。
 */
class ThinkingLevelPersistTest {
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

    private suspend fun onDisk(g: DataGraph, providerId: String, model: String): String? {
        val extras = FakeDeps.create(g, engineDelayMs = 2, plugins = FakePluginRegistry()).settings.settings.value.extras
        return (extras["thinkingLevels"] as? kotlinx.serialization.json.JsonObject)
            ?.let { (it[ProviderRules.thinkingKey(providerId, model)] as? kotlinx.serialization.json.JsonPrimitive)?.content }
    }

    @Test
    fun levelIsStoredPerModelAndSurvivesReopen() {
        runBlocking {
            val (c, g) = make()
            val conv = g.chats.create("openrouter", "google/gemini-3.8-flash-20260902")
            c.select(conv.id)
            until { c.state.value.conv?.id == conv.id }

            c.setThinkingLevel("high")
            assertEquals("点完立刻生效", "high", c.thinkingLevelOf(conv.providerId, conv.model))

            // 关掉弹层 = 只是把 sheet 的 show 置回 false；这里等价于「再查一次」
            until { ProviderRules.hasLevel(c.settings.value.extras, conv.providerId, conv.model) }
            assertEquals("再打开仍读得到", "high",
                ProviderRules.levelFor(c.settings.value.extras, conv.providerId, conv.model))
            assertEquals("落盘也在", "high", onDisk(g, conv.providerId, conv.model))
        }
    }

    @Test
    fun levelFollowsTheModelAcrossConversations() {
        runBlocking {
            val (c, g) = make()
            val a = g.chats.create("openrouter", "google/gemini-3.8-flash-20260902")
            val b = g.chats.create("openrouter", "google/gemini-3.8-flash-20260902")
            c.select(a.id)
            until { c.state.value.conv?.id == a.id }

            c.setThinkingLevel("medium")
            until { ProviderRules.hasLevel(c.settings.value.extras, a.providerId, a.model) }

            // 切到另一个对话（同一个模型）：档位跟着模型走，不该回到自动
            c.select(b.id)
            until { c.state.value.conv?.id == b.id }
            assertEquals("同模型另一个对话也是中", "medium", c.thinkingLevelOf(b.providerId, b.model))
        }
    }

    @Test
    fun differentModelKeepsItsOwnLevel() {
        runBlocking {
            val (c, g) = make()
            val a = g.chats.create("openrouter", "google/gemini-3.8-flash-20260902")
            val b = g.chats.create("openrouter", "deepseek/deepseek-chat")
            c.select(a.id)
            until { c.state.value.conv?.id == a.id }

            c.setThinkingLevel("high")
            until { ProviderRules.hasLevel(c.settings.value.extras, a.providerId, a.model) }

            c.select(b.id)
            until { c.state.value.conv?.id == b.id }
            assertEquals("另一个模型没设过 → 自动", "auto", c.thinkingLevelOf(b.providerId, b.model))
        }
    }

    @Test
    fun autoIsRememberedAsAnExplicitChoice() {
        runBlocking {
            val (c, g) = make()
            val conv = g.chats.create("openrouter", "google/gemini-3.8-flash-20260902")
            c.select(conv.id)
            until { c.state.value.conv?.id == conv.id }

            c.setThinkingLevel("high")
            until { ProviderRules.levelFor(c.settings.value.extras, conv.providerId, conv.model) == "high" }
            c.setThinkingLevel("auto")
            until { ProviderRules.levelFor(c.settings.value.extras, conv.providerId, conv.model) == "auto" }

            // 显式选过 auto：hasLevel 为真（不是「从没设过」），值仍是 auto
            assertTrue("显式自动要留痕", ProviderRules.hasLevel(c.settings.value.extras, conv.providerId, conv.model))
            assertEquals("auto", c.thinkingLevelOf(conv.providerId, conv.model))

            // 旧版写在对话里的档位不该反过来盖住模型设置
            val legacy = conv.copy(extras = kotlinx.serialization.json.JsonObject(
                conv.extras + ("thinkingLevel" to kotlinx.serialization.json.JsonPrimitive("high"))))
            assertEquals("模型设置优先于对话里的旧值", "auto",
                ProviderRules.resolveLevel(c.settings.value.extras, legacy.providerId, legacy.model, "high"))
        }
    }

    @Test
    fun legacyConvValueIsUsedWhenModelHasNoChoice() {
        runBlocking {
            val (c, g) = make()
            val conv = g.chats.create("openrouter", "google/gemini-3.8-flash-20260902")
            c.select(conv.id)
            until { c.state.value.conv?.id == conv.id }

            assertFalse("新模型没设过", ProviderRules.hasLevel(c.settings.value.extras, conv.providerId, conv.model))
            assertEquals("回落到旧版写在对话里的档位", "low",
                ProviderRules.resolveLevel(c.settings.value.extras, conv.providerId, conv.model, "low"))
        }
    }
}