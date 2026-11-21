package com.aichat.app.domain.cost

import com.aichat.app.domain.model.Conversation
import com.aichat.app.domain.model.ProviderConfig
import com.aichat.app.net.StreamDelta
import com.aichat.app.net.UsageCodec
import kotlinx.serialization.json.JsonObject

/** W1（C1）：旁路 usage → 账本条目的纯函数管道（可单测；此前 UsageDelta 被 filterIsInstance 丢弃，A1/A5）。 */
object BypassUsageEntry {

    /**
     * 把一次旁路请求的 `UsageDelta` 转成独立 type 的账本条目。
     * 返回 null = 不入账（usage 缺失 / prompt 为空），**不伪造 0 成本**；调用方不重试、不报错。
     */
    fun of(
        task: BypassTask,
        model: String,
        p: ProviderConfig,
        conv: Conversation,
        du: StreamDelta.UsageDelta,
        at: Long = System.currentTimeMillis(),
    ): JsonObject? {
        val u = UsageCodec.toUsage(du.usage, du.raw) ?: return null
        return UsageEntry.of(
            u, at = at, type = task.usageType, model = model,
            providerId = p.presetId.ifEmpty { p.id }, providerName = p.name,
            convId = conv.id, convTitle = conv.title,
        )
    }
}
