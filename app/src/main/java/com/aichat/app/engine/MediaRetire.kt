package com.aichat.app.engine

import com.aichat.app.domain.WireMessage
import com.aichat.app.domain.model.Role
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * W3（C3）：历史图片的**请求侧退役**——旧图片不再进入模型请求，但本地存储（BlobStore）、
 * 消息 UI、导出一律不动；`Message.images` 本体不改，只影响本轮 `WireMessage`。
 *
 * 开关：`settings.plugins["media"]["retireOldImages"]`，**默认关闭**（关闭时调用方根本不进来，逐字节一致）。
 * 默认保留最近 [KEEP_LAST_USER_TURNS] 个用户轮次的图片（任务书建议 2–4，取保守值 2；
 * 更早轮次的图片在请求里换成短占位文本，让模型知道「曾经有图、现在没带」，而不是假装没图）。
 */
object MediaRetire {
    const val KEEP_LAST_USER_TURNS = 2

    fun enabled(plugins: JsonObject?): Boolean =
        (plugins?.get("media") as? JsonObject)?.get("retireOldImages")
            ?.let { it as? JsonPrimitive }?.content == "true"

    /**
     * 退役 [wire] 中较旧用户消息的图片：只保留最近 [keepLastUserTurns] 个用户轮次。
     * 被退役的消息 content 追加占位说明（不删消息、不改本地）；无图会话返回原列表。
     */
    fun retire(wire: List<WireMessage>, keepLastUserTurns: Int = KEEP_LAST_USER_TURNS): List<WireMessage> {
        if (keepLastUserTurns <= 0) return wire
        // 从尾部数第 keepLastUserTurns 个 USER 消息：它（含）之后的用户轮次保留图片，之前的退役
        var seen = 0
        var cutoffIdx = -1
        for (i in wire.indices.reversed()) {
            if (wire[i].role == Role.USER) {
                seen++
                if (seen == keepLastUserTurns) { cutoffIdx = i; break }
            }
        }
        if (cutoffIdx < 0) return wire   // 用户轮次不足 N：全部保留
        var changed = false
        val out = wire.mapIndexed { idx, m ->
            if (idx < cutoffIdx && m.role == Role.USER && m.images.isNotEmpty()) {
                changed = true
                m.copy(
                    images = emptyList(),
                    content = m.content + "\n【历史图片已省略：" + m.images.size + " 张（如需基于旧图提问，请重新附加）】",
                )
            } else m
        }
        return if (changed) out else wire
    }
}
