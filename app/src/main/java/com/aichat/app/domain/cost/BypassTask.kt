package com.aichat.app.domain.cost

/**
 * 旁路任务档（PLAN-COST-OPTIMIZATION W1/W2 = C1/C2）：每类任务独立的出参上限、思考档、超时与入账类型。
 *
 * thinkingLevel 语义与 ChatOptions.thinkingLevel 一致（null = auto = 请求体不加思考字段；
 * "off" = 显式关闭，服务商不支持时由 wire 层降级到最低档，见 ProviderRules.thinkingPatch / geminiThinkingConfig）。
 * 数字依据：成本审计 B1（此前摘要/记忆/AutoChar 三类共享 2048 且无思考字段）+ 任务书 W2 表
 * （摘要 1200 / 记忆 800 / 搜索 256 / AutoChar 质量敏感保持独立配置）。
 */
enum class BypassTask(val usageType: String, val maxTokens: Int, val thinkingLevel: String?, val timeoutMs: Long) {
    CHAPTER_SUMMARY("chapter_summary", 1200, "off", 60_000),
    MEMORY_EXTRACT("memory_extract", 800, "off", 45_000),
    SEARCH_REWRITE("search_rewrite", 256, "off", 20_000),
    AUTO_CHAR("auto_char", 2048, null, 60_000),
}
