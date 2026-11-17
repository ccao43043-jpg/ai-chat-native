package com.aichat.app.domain

/**
 * 末条用户消息的单点装配（PLAN-CONTEXT-ARCH S3/S4）。
 *
 * 顺序固定：`[状态行]` 在前、原文居中、`[召回]` 在后。
 *
 * 为什么是这个顺序：
 * - 状态行（affect 的 `[AFFECT]`）是「现在怎么说话」的指令，贴在开头最显眼；
 * - 召回是参考资料，放最后 —— 它每轮都在变，放在尾部就不会把前面的稳定前缀顶掉；
 * - 三者都为空时必须**逐字节**等于原文，否则缓存前缀会被无意义地改写。
 */
object ContextAssembler {
    fun assembleLastUser(original: String, tag: String? = null, recall: String? = null): String {
        val t = tag.orEmpty().trim()
        val r = recall.orEmpty().trim()
        if (t.isEmpty() && r.isEmpty()) return original
        val b = StringBuilder(original.length + t.length + r.length + 8)
        if (t.isNotEmpty()) b.append(t).append('\n')
        b.append(original)
        if (r.isNotEmpty()) b.append("\n\n").append(r)
        return b.toString()
    }
}
