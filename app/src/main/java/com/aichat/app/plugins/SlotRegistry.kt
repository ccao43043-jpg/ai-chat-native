package com.aichat.app.plugins

import com.aichat.app.domain.Slot

/**
 * 一槽一主（PLAN-CONTEXT-ARCH §3.2）。
 *
 * 用法：每轮请求开始时，把所有**激活**插件的 [com.aichat.app.domain.ChatPlugin.slots] 声明收上来，
 * 同一个槽位出现两个不同所有者就记一条冲突。冲突**不改变请求**（release 不崩用户），
 * 但要写进 [com.aichat.app.domain.ContextTrace] 并显示在「上下文体检」面板里；
 * 单测 `defaultRegistryHasNoSlotConflicts` 守住「默认组装不许有冲突」。
 *
 * 过渡期：[SHAREABLE_NOW] 里的槽位允许并存。现在只剩 S3 —— 资料库、结构化记忆、
 * rpcache 的记忆增量三家都往末条 user 尾部拼（这正是 R1 描述的混乱）。
 * M4 的 RecallBus 上线后它们改成「交片段」，[SHAREABLE_NOW] 随之清空，
 * 由 `s3BecomesExclusiveAfterRecallBus` 单测锁定两种状态。
 */
class SlotRegistry(private val shareable: Set<Slot> = SHAREABLE_NOW) {
    private val owners = LinkedHashMap<Slot, MutableList<String>>()
    private val conflicts = ArrayList<String>()

    /** 返回 false = 抢到了别人的槽位（已记冲突）；重复声明同一槽位不算冲突 */
    fun claim(slot: Slot, pluginId: String): Boolean {
        val list = owners.getOrPut(slot) { ArrayList() }
        if (list.contains(pluginId)) return true
        val taken = list.isNotEmpty()
        if (taken && slot !in shareable) {
            conflicts += "${slot.code}（${slot.label}）已由 ${list.joinToString("、")} 拥有，$pluginId 又声明了一次"
            list += pluginId
            return false
        }
        list += pluginId
        return true
    }

    fun ownerOf(slot: Slot): String? = owners[slot]?.firstOrNull()
    fun ownersOf(slot: Slot): List<String> = owners[slot]?.toList() ?: emptyList()
    fun conflicts(): List<String> = conflicts.toList()

    companion object {
        /** 过渡期可共享的槽位（M4 之后改为空集） */
        val SHAREABLE_NOW: Set<Slot> = setOf(Slot.S3_RECALL)
    }
}
