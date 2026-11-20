package com.aichat.app.ui

import com.aichat.app.data.DataGraph
import com.aichat.app.ui.chat.ChatController
import com.aichat.app.ui.chat.RpInspect
import com.aichat.app.ui.common.fake.FakeDeps
import com.aichat.app.ui.common.fake.FakePluginRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「摘要省钱档」（weekend plan T3）的契约：summary_model 是字符串键（空 = 摘要走主模型），
 * 经 setRpSettings 落进 rpState.settings；校验提示剥 :variant 后比对服务商模型列表。
 * 旁路调用的 override 传递已有 RpChapterManualTest.summaryModelSettingIsHonored 覆盖，这里不重复。
 */
class RpSummaryBudgetTest {
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

    private suspend fun summaryModelOf(g: DataGraph, convId: String): String? {
        val st = g.chats.load(convId)?.rpState as? JsonObject ?: return null
        val s = st["settings"] as? JsonObject ?: return null
        return (s["summary_model"] as? JsonPrimitive)?.content
    }

    @Test
    fun setRpSettingsStoresSummaryModelAsString() {
        runBlocking {
            val (c, g) = make()
            val conv = g.chats.create("openrouter", "z-ai/glm-5.3")
            c.select(conv.id)
            until { c.state.value.conv?.id == conv.id }

            c.setRpSettings(conv.id, mapOf("summary_model" to " z-ai/glm-5.3 "))
            until { summaryModelOf(g, conv.id) == "z-ai/glm-5.3" }

            // 空串 = 清空（回落主模型）；它必须能穿过「非负整数」守卫而不是被吞掉
            c.setRpSettings(conv.id, mapOf("summary_model" to "  "))
            until { summaryModelOf(g, conv.id) == "" }
        }
    }

    @Test
    fun summaryModelWarningCoversVariantsAndUnknownList() {
        val models = listOf("google/gemini-3.8-flash", "z-ai/glm-5.3")
        assertNull("空 = 走主模型，不警告", RpInspect.summaryModelWarning("", models))
        assertNull("空白同理", RpInspect.summaryModelWarning("  ", models))
        assertNull("列表里的裸名", RpInspect.summaryModelWarning("z-ai/glm-5.3", models))
        assertNull("带 :variant 也算命中（baseSlug 剥后缀）", RpInspect.summaryModelWarning("z-ai/glm-5.3:flex", models))
        assertNull("列表拿不到（还没拉过模型列表）不唠叨", RpInspect.summaryModelWarning("z-ai/glm-5.3", emptyList()))
        assertEquals(
            "不在列表 → 黄字提示",
            "该服务商的模型列表里没有它，摘要会回落主模型",
            RpInspect.summaryModelWarning("z-ai/glm-9.9", models),
        )
    }
}
