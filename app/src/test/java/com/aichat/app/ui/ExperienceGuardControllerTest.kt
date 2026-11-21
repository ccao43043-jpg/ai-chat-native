package com.aichat.app.ui

import com.aichat.app.data.DataGraph
import com.aichat.app.domain.model.Message
import com.aichat.app.domain.model.Role
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EXPERIENCE-GUARD（2026-11-21）：`restoreInputOf` 五个分支、草稿 setter、流式中 regenerate 守卫。
 * 此前这三处都是 0 测试（grep 确认）；构造手段与 ChatControllerTest 同一套（FakeDeps + until 轮询）。
 */
class ExperienceGuardControllerTest {
    private val jobs = mutableListOf<Job>()
    @After fun tearDown() { jobs.forEach { it.cancel() } }

    private fun make(delayMs: Long = 2): Pair<ChatController, com.aichat.app.domain.ChatRepository> {
        val deps = FakeDeps.create(DataGraph.inMemory(), engineDelayMs = delayMs, plugins = FakePluginRegistry())
        val scope = CoroutineScope(Dispatchers.Default + Job().also { jobs += it })
        return ChatController(deps, scope) to deps.chats
    }

    private suspend fun until(msg: String = "条件", cond: () -> Boolean) {
        withTimeout(10_000) { while (!cond()) delay(5) }
        assertTrue(msg, cond())
    }

    private fun failedTurn(userText: String = "原问题") = listOf(
        Message("u-1", Role.USER, userText, 1),
        Message("a-1", Role.ASSISTANT, "", 1, error = "HTTP 500：演示错误"),
    )

    @Test fun restoreInputOfReturnsPrecedingUserText() = runBlocking {
        val (c, repo) = make()
        val conv = repo.create("openrouter", "m", "失败恢复")
        repo.save(conv.copy(messages = failedTurn()))
        c.select(conv.id)
        until { c.state.value.conv?.messages?.size == 2 }
        assertEquals("原问题", c.restoreInputOf("a-1"))
        assertTrue("要 toast 给出反馈", c.state.value.toast != null)
    }

    @Test fun restoreInputOfWithoutConversationReturnsNull() = runBlocking {
        val (c, _) = make()
        assertNull(c.restoreInputOf("a-1"))
        assertTrue(c.state.value.toast != null)
    }

    @Test fun restoreInputOfFailsWhenTargetIsFirstMessage() = runBlocking {
        val (c, repo) = make()
        val conv = repo.create("openrouter", "m", "失败恢复")
        repo.save(conv.copy(messages = listOf(Message("a-1", Role.ASSISTANT, "", 1, error = "e"))))
        c.select(conv.id)
        until { c.state.value.conv != null }
        assertNull(c.restoreInputOf("a-1"))
    }

    @Test fun restoreInputOfFailsWhenPredecessorIsNotUser() = runBlocking {
        val (c, repo) = make()
        val conv = repo.create("openrouter", "m", "失败恢复")
        repo.save(conv.copy(messages = listOf(
            Message("a-0", Role.ASSISTANT, "", 1, error = "e"),
            Message("a-1", Role.ASSISTANT, "", 1, error = "e"),
        )))
        c.select(conv.id)
        until { c.state.value.conv?.messages?.size == 2 }
        assertNull(c.restoreInputOf("a-1"))
    }

    @Test fun restoreInputOfFailsOnBlankSource() = runBlocking {
        val (c, repo) = make()
        val conv = repo.create("openrouter", "m", "失败恢复")
        repo.save(conv.copy(messages = failedTurn(userText = "   ")))
        c.select(conv.id)
        until { c.state.value.conv?.messages?.size == 2 }
        assertNull(c.restoreInputOf("a-1"))
        assertTrue(c.state.value.toast != null)
    }

    @Test fun draftSettersOverwriteState() {
        val (c, _) = make()
        c.setDraftImages(listOf("data:image/png;base64,AAA"))
        c.setDraftFiles(listOf(buildJsonObject { put("name", JsonPrimitive("a.txt")) }))
        assertEquals(1, c.state.value.draftImages.size)
        assertEquals(1, c.state.value.draftFiles.size)
        c.setDraftImages(emptyList())
        assertEquals(0, c.state.value.draftImages.size)
        assertEquals(1, c.state.value.draftFiles.size)
    }

    @Test fun regenerateIsNoOpWhileStreaming() = runBlocking {
        val (c, repo) = make(delayMs = 150)
        val conv = repo.create("openrouter", "m", "守卫")
        repo.save(conv.copy(messages = listOf(
            Message("u-0", Role.USER, "第一句", 1),
            Message("a-0", Role.ASSISTANT, "第一答", 1),
        )))
        c.select(conv.id)
        until { c.state.value.conv?.messages?.size == 2 }
        assertTrue(c.send("第二句", emptyList()))
        withTimeout(10_000) { while ((repo.load(conv.id)?.messages?.size ?: 0) < 4) delay(10) }   // 第二轮落盘
        assertTrue(c.isStreaming())

        c.regenerate("a-0")   // 流式中：守卫直接 return
        assertEquals("流式中 regenerate 不得产生新消息/新版本", 4, repo.load(conv.id)?.messages?.size)

        c.stop()
        until { !c.isStreaming() }
        assertEquals("结束仍是两轮", 4, repo.load(conv.id)?.messages?.size)
    }
}
