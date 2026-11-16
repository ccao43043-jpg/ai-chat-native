package com.aichat.app.ui.chat

import com.aichat.app.domain.model.Conversation
import com.aichat.app.domain.model.Message
import com.aichat.app.domain.model.Role
import com.aichat.app.domain.model.Usage
import kotlinx.serialization.json.JsonObject

/** 一路进行中的流式回复（UI 侧临时态；落盘由 ChatEngine 负责） */
data class Streaming(
    val convId: String,
    val base: List<Message>,          // 流开始时、生成目标之前的消息
    val pendingUser: Message?,        // 本轮用户消息（乐观显示；收到 Started 后换成真实 id）
    val liveId: String = ChatUiState.STREAMING_ID,
    val prefix: Message? = null,      // 续写 / 原位重生成：目标消息原有的字段（版本信息、续写前的正文）
    val text: String = "",
    val reasoning: String = "",
    val error: String? = null,
    val usage: Usage? = null,
    val startedAt: Long = System.currentTimeMillis(),
    /** 带文件附件发送时：流结束后把用户消息改回「原文 + files」（见 [ChatController.send]） */
    val filePatch: FilePatch? = null,
)

data class FilePatch(val sentText: String, val rawText: String, val files: List<JsonObject>)

/** 需要 UI 配合的操作结果：直接提示 / 先确认再执行（文案照 app.js dialog） */
sealed interface Plan {
    data class Toast(val text: String) : Plan
    data class Ask(val title: String, val body: String?, val okText: String, val danger: Boolean = false, val run: () -> Unit) : Plan
    data object Done : Plan
}

data class ChatUiState(
    val convId: String? = null,
    val conv: Conversation? = null,
    val streams: Map<String, Streaming> = emptyMap(),
    val toast: String? = null,
    /** 输入区待发送的图片（data URL）/ 文件（已抽取文字）。放在这里而不是 rememberSaveable：体积大，不能进 Bundle */
    val draftImages: List<String> = emptyList(),
    val draftFiles: List<JsonObject> = emptyList(),
    /** 开着联网搜索但没配置好：UI 弹 app.js askSearchSetup 的对话框 */
    val askSearchSetup: Boolean = false,
) {
    val streaming: Streaming? get() = convId?.let { streams[it] }
    val isStreaming: Boolean get() = streaming != null

    /** 消息列表 = 已落盘消息；流式中 = 目标之前的消息 + 乐观用户消息 + 正在生成的助手消息 */
    val messages: List<Message> get() = live?.let { stable + it } ?: stable

    /** 不随流式增量变化的部分（流式中 = base + 乐观用户消息）。UI 据此 remember，增量到来时这些条目不重组 */
    val stable: List<Message> get() {
        val s = streaming ?: return conv?.messages.orEmpty()
        return s.pendingUser?.let { s.base + it } ?: s.base
    }

    /** 正在生成的助手消息；不在流式中为 null */
    val live: Message? get() {
        val s = streaming ?: return null
        val p = s.prefix
        return (p ?: Message(s.liveId, Role.ASSISTANT, "", s.startedAt)).copy(
            id = s.liveId, content = (p?.content ?: "") + s.text,
            reasoning = ((p?.reasoning ?: "") + s.reasoning).ifEmpty { null },
            error = s.error, usage = s.usage ?: p?.usage, truncated = false)
    }

    companion object { const val STREAMING_ID = "__streaming__" }
}