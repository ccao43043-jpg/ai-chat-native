package com.aichat.app.domain

import com.aichat.app.plugins.TokenEstimator

/**
 * 每轮召回的总线（PLAN-CONTEXT-ARCH S3）。
 *
 * 以前有四个插件各自往「最后一条用户消息」里塞东西：rpcache 整段重写、资料库追加
 * `[LOCAL_KNOWLEDGE]`、记忆追加 `[MEMORY_RECALL]`、affect 前置 `[AFFECT]`。
 * 谁后跑谁就压在别人上面，去重与预算各算一遍，结果是同一句话可能被注入两次、
 * 也可能因为预算耗尽而互相挤掉。
 *
 * 改成：所有「每轮变」的内容都只是 [RecallItem]（声明来源与优先级），
 * 由 [render] 统一去重、排序、按预算截断，只产出**一段**文本。
 *
 * 这一层是纯函数式容器：不碰 Conversation、不知道谁在调用，便于单测。
 */
data class RecallItem(
    /** 来源标识：lore / knowledge / memory …（用于分组标题与调试） */
    val source: String,
    val text: String,
    /** 越大越先生效；预算不够时先丢小的 */
    val priority: Int,
    /** 预先算好的 token 数；<= 0 时按文本再算 */
    val tokens: Int = 0,
) {
    fun resolvedTokens(): Int = if (tokens > 0) tokens else TokenEstimator.text(text)
}

class RecallBus {
    private val items = ArrayList<RecallItem>()

    fun offer(item: RecallItem): RecallBus {
        if (item.text.isNotBlank()) items += item
        return this
    }

    fun offer(source: String, text: String, priority: Int, tokens: Int = 0): RecallBus =
        offer(RecallItem(source, text, priority, tokens))

    fun items(): List<RecallItem> = items.toList()

    fun isEmpty(): Boolean = items.all { it.text.isBlank() }

    fun clear(): RecallBus = apply { items.clear() }

    /**
     * 去重 → 按优先级排序 → 按 token 预算截断 → 渲染成一段可注入文本。
     *
     * 去重按「规范化后的文本」比：同一段资料既被资料库命中又被记忆命中时只留一份
     * （保留优先级高的那条），避免同一个事实在提示词里出现两遍。
     */
    fun render(budgetTokens: Int = DEFAULT_BUDGET): String {
        val seen = LinkedHashMap<String, RecallItem>()
        for (it in items) {
            if (it.text.isBlank()) continue
            val key = normalize(it.text)
            val old = seen[key]
            if (old == null || it.priority > old.priority) seen[key] = it
        }
        if (seen.isEmpty()) return ""
        val ordered = seen.values.sortedWith(compareByDescending<RecallItem> { it.priority })
        val picked = ArrayList<RecallItem>()
        var used = 0
        for (it in ordered) {
            val t = it.resolvedTokens()
            if (used + t > budgetTokens && picked.isNotEmpty()) continue
            picked += it
            used += t
        }
        if (picked.isEmpty()) return ""
        val body = StringBuilder()
        body.append(HEADER).append('\n')
        for (it in picked) {
            body.append("- (").append(labelOf(it.source)).append(") ")
            body.append(it.text.trim().replace("\n", "\n  "))
            body.append('\n')
        }
        return body.toString().trimEnd()
    }

    companion object {
        const val HEADER = "[RECALL]（本轮召回，只作参考，不要复述，也不要当成用户刚说的话）"
        const val DEFAULT_BUDGET = 1200

        /** 来源优先级：世界书是硬设定，压过资料库；资料库压过自动记忆；置顶记忆单独拔高。 */
        const val P_LORE = 100
        const val P_PINNED = 90
        const val P_KNOWLEDGE = 60
        const val P_MEMORY = 40

        fun labelOf(source: String): String = when (source) {
            "lore" -> "世界书"
            "knowledge" -> "资料库"
            "memory" -> "记忆"
            else -> source
        }

        private fun normalize(text: String): String =
            text.trim().replace(Regex("[ \\t]+"), " ").replace(Regex("\n{2,}"), "\n")
    }
}
