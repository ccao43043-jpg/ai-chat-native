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
) {
    /** 正在生成的助手消息（prefix + 增量）。热数据随 [ChatController.streams] 每 tick 变化，只在 live 气泡内使用 */
    fun toLiveMessage(): Message {
        val p = prefix
        return (p ?: Message(liveId, Role.ASSISTANT, "", startedAt)).copy(
            id = liveId, content = (p?.content ?: "") + text,
            reasoning = ((p?.reasoning ?: "") + reasoning).ifEmpty { null },
            error = error, usage = usage ?: p?.usage, truncated = false)
    }
}

data class FilePatch(val sentText: String, val rawText: String, val files: List<JsonObject>)

/** 需要 UI 配合的操作结果：直接提示 / 先确认再执行（文案照 app.js dialog） */
sealed interface Plan {
    data class Toast(val text: String) : Plan
    data class Ask(val title: String, val body: String?, val okText: String, val danger: Boolean = false, val run: () -> Unit) : Plan
    data object Done : Plan
}

/**
 * 当前对话进行中流的「稳定视图」：只在流开始 / Started / 结束这些结构性时刻变化。
 * 增量（text / reasoning）在 [ChatController.streams] 里，不进主 state——否则每次 flush 都会
 * 触发整屏重组（评审 §5.1）。
 */
data class StreamView(val base: List<Message>, val pendingUser: Message?, val liveId: String)

data class ChatUiState(
    val convId: String? = null,
    val conv: Conversation? = null,
    /** 当前对话的流式稳定层；非流式为 null。热数据见 [ChatController.streams] */
    val stream: StreamView? = null,
    val toast: String? = null,
    /** 输入区待发送的图片（data URL）/ 文件（已抽取文字）。放在这里而不是 rememberSaveable：体积大，不能进 Bundle */
    val draftImages: List<String> = emptyList(),
    val draftFiles: List<JsonObject> = emptyList(),
    /** 开着联网搜索但没配置好：UI 弹 app.js askSearchSetup 的对话框 */
    val askSearchSetup: Boolean = false,
) {
    val isStreaming: Boolean get() = stream != null

    /**
     * 消息列表（稳定层，实例在整个流期间不变）：流式中 = base + 乐观用户消息；
     * 空闲 = conv.messages。正在生成的助手消息由列表末尾单独的 item 渲染，
     * 该 item 内部订阅 [ChatController.streamOf]——增量 tick 只重组那一个气泡。
     */
    val messages: List<Message>
        get() = stream?.let { (it.pendingUser?.let { u -> it.base + u } ?: it.base) } ?: conv?.messages.orEmpty()

    companion object { const val STREAMING_ID = "__streaming__" }
}
