package com.aichat.app.domain.cost

/**
 * 用量任务类型（1.8 固定七类）。账本 type 只能取这些值，避免散落字符串导致统计报表串类。
 */
object UsageTypes {
    const val CHAT = "chat"
    const val IMAGE = "image"
    const val SUMMARY = "summary"
    const val MEMORY = "memory"
    const val SEARCH = "search"
    const val AUTOCHAR = "autochar"
    const val CHARACTER_BOOK = "character_book"

    val ALL = listOf(CHAT, IMAGE, SUMMARY, MEMORY, SEARCH, AUTOCHAR, CHARACTER_BOOK)
}

/**
 * 旁路任务档：每类任务独立的出参上限、思考档、超时与入账类型。
 *
 * thinkingLevel 语义与 ChatOptions.thinkingLevel 一致（null = auto = 请求体不加思考字段；
 * "off" = 显式关闭，服务商不支持时由 wire 层降级到最低档，见 ProviderRules.thinkingPatch / geminiThinkingConfig）。
 * 1.8 预算：摘要/记忆 512、搜索 256、AutoChar 768、角色书 900；主对话与图片由消息级入账负责。
 */
enum class BypassTask(val usageType: String, val maxTokens: Int, val thinkingLevel: String?, val timeoutMs: Long) {
    SUMMARY(UsageTypes.SUMMARY, 512, "off", 60_000),
    MEMORY(UsageTypes.MEMORY, 512, "off", 45_000),
    SEARCH(UsageTypes.SEARCH, 256, "off", 20_000),
    AUTO_CHAR(UsageTypes.AUTOCHAR, 768, null, 60_000),
    CHARACTER_BOOK(UsageTypes.CHARACTER_BOOK, 900, null, 60_000),
}
