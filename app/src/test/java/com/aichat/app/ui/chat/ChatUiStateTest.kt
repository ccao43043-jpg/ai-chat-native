package com.aichat.app.ui.chat

import com.aichat.app.domain.model.Conversation
import com.aichat.app.domain.model.Message
import com.aichat.app.domain.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `ChatUiState` 的三个派生属性（messages / stable / live）：流式与非流式两态。 */
class ChatUiStateTest {

    private fun msg(id: String, role: Role = Role.ASSISTANT, content: String = id) =
        Message(id, role, content, createdAt = 1)

    private fun conv(vararg messages: Message) = Conversation(
        id = "c", title = "t", providerId = "p", model = "m",
        createdAt = 1, updatedAt = 2, messages = messages.toList(),
    )

    @Test
    fun idleStateFallsBackToPersistedMessages() {
        val st = ChatUiState(convId = "c", conv = conv(msg("a"), msg("b", Role.USER)))
        assertFalse(st.isStreaming)
        assertNull(st.live)
        assertEquals(listOf("a", "b"), st.messages.map { it.id })
        assertEquals(st.messages, st.stable)
    }

    @Test
    fun streamingStateAppendsPendingUserAndLive() {
        val s = Streaming(
            convId = "c",
            base = listOf(msg("a")),
            pendingUser = msg("u", Role.USER),
            text = "答案",
            startedAt = 2,
        )
        val st = ChatUiState(convId = "c", streams = mapOf("c" to s))
        assertTrue(st.isStreaming)
        assertEquals(listOf("a", "u"), st.stable.map { it.id })
        assertEquals(listOf("a", "u", ChatUiState.STREAMING_ID), st.messages.map { it.id })
        assertEquals("答案", st.live?.content)
        assertEquals(ChatUiState.STREAMING_ID, st.live?.id)
    }

    @Test
    fun prefixIsContinuedInsteadOfReplaced() {
        val s = Streaming(
            convId = "c",
            base = emptyList(),
            pendingUser = null,
            prefix = msg("bot", content = "前文"),
            text = "续写",
            reasoning = "想",
            error = "e",
            startedAt = 3,
        )
        val st = ChatUiState(convId = "c", streams = mapOf("c" to s))
        val live = st.live!!
        assertEquals("前文续写", live.content)
        assertEquals("想", live.reasoning)
        assertEquals("e", live.error)
        assertTrue(st.stable.isEmpty())
        assertEquals(listOf(ChatUiState.STREAMING_ID), st.messages.map { it.id })
    }
}