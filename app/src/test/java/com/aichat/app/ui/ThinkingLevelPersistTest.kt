package com.aichat.app.ui

import com.aichat.app.data.DataGraph
import com.aichat.app.ui.chat.ChatController
import com.aichat.app.ui.common.fake.FakeDeps
import com.aichat.app.ui.common.fake.FakePluginRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「思考等级」的持久化契约：点完要真的写进这个对话，退出弹层/切走对话再回来还在。
 *
 * 用户报过「选中之后有蓝框，退出再进又变回自动」——根因是 state 的回填用 `convId == conv.id` 做守卫，
 * 一旦两者对不上就整条更新被丢掉（弹层里靠本地 remembered 选中态看着正常）。这里把行为钉住。
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

    private fun inState(c: ChatController): String? =
        (c.state.value.conv?.extras?.get("thinkingLevel") as? JsonPrimitive)?.content

    private suspend fun onDisk(g: DataGraph, id: String): String? =
        (g.chats.load(id)?.extras?.get("thinkingLevel") as? JsonPrimitive)?.content

    @Test
    fun levelSurvivesReopen() {
        runBlocking {
            val (c, g) = make()
            val conv = g.chats.create("openrouter", "google/gemini-3.8-flash-20260902")
            c.select(conv.id)
            until { c.state.value.conv?.id == conv.id }

            c.setThinkingLevel("high")
            assertEquals("点完 state 立刻有", "high", inState(c))
            until { onDisk(g, conv.id) == "high" }
            delay(100)
            assertEquals("回填不该冲掉", "high", inState(c))

            // 退出再进（切走再切回来）
            c.select(null)
            until { c.state.value.conv == null }
            c.select(conv.id)
            until { c.state.value.conv?.id == conv.id }
            assertEquals("再进还在", "high", inState(c))
        }
    }

    @Test
    fun autoIsStoredAsAutoNotDeleted() {
        runBlocking {
            val (c, g) = make()
            val conv = g.chats.create("openrouter", "google/gemini-3.8-flash-20260902")
            c.select(conv.id)
            until { c.state.value.conv?.id == conv.id }

            c.setThinkingLevel("high")
            until { onDisk(g, conv.id) == "high" }
            c.setThinkingLevel("auto")
            assertEquals("auto 立刻生效", "auto", inState(c))
            until { onDisk(g, conv.id) == "auto" }
            // 退出再进还是 auto（不会变成「JSON null」那种既非有值也非缺键的怪状态）
            c.select(null)
            until { c.state.value.conv == null }
            c.select(conv.id)
            until { c.state.value.conv?.id == conv.id }
            assertEquals("再进是 auto", "auto", inState(c))
        }
    }
}