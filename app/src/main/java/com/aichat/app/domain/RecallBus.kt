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

    /** 上一次 [render] 因为「跨来源近似重复」丢掉了几条（给体检面板用） */
    var lastSoftDropped: Int = 0
        private set

    /**
     * 去重 → 按优先级排序 → 按 token 预算截断 → 渲染成一段可注入文本。
     *
     * 去重分两层：
     * 1. **精确**：按规范化文本比，同一段资料既被资料库命中又被记忆命中时只留一份（保留优先级高的）。
     * 2. **近似**（[SOFT_DUPE]）：跨来源时再比一次 bigram Jaccard。资料库的段落常和记忆里的短句
     *    说的是同一件事，只是措辞不同 —— 精确去重抓不到，白花预算还让提示词变啰嗦。
     *    只在**跨来源**时做（同来源交给精确去重），并且数字或否定词不同就视为不同事实
     *    （「灯塔是红色」/「灯塔是白色」不能被合并）。
     */
    fun render(budgetTokens: Int = DEFAULT_BUDGET): String {
        lastSoftDropped = 0
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
            if (picked.any { p -> p.source != it.source && softSame(p.text, it.text) }) {
                lastSoftDropped++
                continue
            }
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

        /** 跨来源近似重复的相似度门槛（bigram Jaccard） */
        const val SOFT_DUPE = 0.8

        private fun grams(text: String): Set<String> {
            val s = text.trim().lowercase().replace(Regex("\\s+"), "")
            if (s.isEmpty()) return emptySet()
            if (s.length <= 2) return setOf(s)
            val out = HashSet<String>(s.length)
            for (i in 0 until s.length - 1) out += s.substring(i, i + 2)
            return out
        }

        private fun overlap(a: String, b: String): Double {
            val ga = grams(a)
            val gb = grams(b)
            if (ga.isEmpty() || gb.isEmpty()) return 0.0
            val hit = ga.count { it in gb }
            return hit.toDouble() / (ga.size + gb.size - hit)
        }

        private val DIGITS = Regex("\\d+")
        private val NEGATION = Regex("(不|没|未|别|勿|无|非|never|not|\\bno\\b)", RegexOption.IGNORE_CASE)

        /** 数字集合或否定倾向不同 → 语义不同，不允许被近似去重掉 */
        private fun differsInFacts(a: String, b: String): Boolean {
            val na = DIGITS.findAll(a).map { it.value }.toSet()
            val nb = DIGITS.findAll(b).map { it.value }.toSet()
            if (na != nb) return true
            return NEGATION.containsMatchIn(a) != NEGATION.containsMatchIn(b)
        }

        private fun softSame(a: String, b: String): Boolean =
            overlap(a, b) >= SOFT_DUPE && !differsInFacts(a, b)
    }
}
