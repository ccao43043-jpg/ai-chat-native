package com.aichat.app.plugins.rp

import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js

/**
 * 「这个对话属于哪个世界」的唯一答案来源。
 *
 * 以前有三处各读各的，读出来的结果可以互相矛盾：
 * - `conv.extras.worldId / worldName / worldEmoji`：用世界模板开聊时写的（`Worlds.conversationFromWorld`）；
 * - `rpState.blocks.world`：RP 面板里手写/粘贴的【世界观】块；
 * - `rpState.worldRef.id`：旧版逐轮冻结的世界引用（`worldRef.set` op）。
 *
 * 三处都存在时谁优先？旧代码没有答案 —— `AutoChar` 只看 blocks、`Knowledge` 只看 extras、
 * 世界书面板又按 worldId 分文件，于是「世界里生成角色把世界覆盖掉」这类问题会在不同入口重复出现。
 *
 * 这里定死口径：**绑定关系优先于内容**。只要任意一处声明了世界 id，就算「绑定在世界里」；
 * 内容（世界观文本）可以暂时为空，那是用户还没填，不代表这个对话不属于这个世界。
 * 判「要不要按世界规则生成/注入」时用 [of]；只想知道 id 时用 [boundId]。
 */
object WorldResolver {

    /**
     * @param id     世界 id（三处任一有就取，extras 优先）
     * @param name   世界名（可能为空：老数据只有 id）
     * @param emoji  世界图标（可能为空）
     * @param text   世界观正文（`blocks.world`，可能为空）
     * @param lore   常驻世界书条目（`[LOREBOOK]`，可能为空）
     * @param source 这个结论来自哪一处（`extras` / `blocks` / `worldRef` / 组合），便于体检面板显示
     */
    data class WorldRef(
        val id: String,
        val name: String,
        val emoji: String,
        val text: String,
        val lore: String,
        val source: String,
    ) {
        /** 有正文或常驻世界书 = 真有设定可参考（用于「世界设定是否已有」这类判断） */
        val hasContent: Boolean get() = text.isNotEmpty() || lore.isNotEmpty()
    }

    /**
     * 解析对话的世界。**任何一处都没声明**时返回 null（= 专场聊天）。
     *
     * 注意：返回非 null 不代表世界有内容 —— 看 [WorldRef.hasContent]。
     */
    fun of(conv: Any?): WorldRef? {
        val state = stateOf(conv)
        val blocksWorld = blockText(state, "world")
        val lore = if (state != null) RpLoreCore.constantLoreText(state) else ""
        val extrasId = str(dig(conv, "worldId"))
        val refId = str(dig(state, "worldRef", "id")).ifEmpty { str(dig(conv, "worldRef", "id")) }

        val sources = ArrayList<String>()
        if (extrasId.isNotEmpty()) sources.add("extras")
        if (refId.isNotEmpty() && refId != extrasId) sources.add("worldRef")
        if (blocksWorld.isNotEmpty()) sources.add("blocks")
        if (sources.isEmpty()) return null

        val id = extrasId.ifEmpty { refId }
        val name = str(dig(conv, "worldName")).ifEmpty { str(dig(state, "worldRef", "name")) }
        val emoji = str(dig(conv, "worldEmoji")).ifEmpty { str(dig(state, "worldRef", "emoji")) }
        return WorldRef(id, name, emoji, blocksWorld, lore, sources.joinToString("+"))
    }

    /** 只要 id（没有绑定关系时为空串）。 */
    fun boundId(conv: Any?): String = of(conv)?.id ?: ""

    /** 世界正文（`blocks.world`）；没绑定世界时为空串。 */
    fun text(conv: Any?): String = of(conv)?.text ?: ""

    private fun stateOf(conv: Any?): JObj? = dig(conv, "plugins", "rpcache", "state") as? JObj

    private fun blockText(state: JObj?, id: String): String =
        str(RpState.getBlock(state, id)?.get("content"))

    /**
     * 逐层取键：同时认 Kotlin [Map]（插件层的 JObj）与 kotlinx [kotlinx.serialization.json.JsonObject]。
     * 插件层的 `Js` 没有 JS 的 `path`，这里手写一层，避免两套对象形状各写一遍。
     */
    private fun dig(v: Any?, vararg keys: String): Any? {
        var cur: Any? = v
        for (k in keys) {
            cur = when (cur) {
                is Map<*, *> -> cur[k]
                is kotlinx.serialization.json.JsonObject -> cur[k]
                else -> null
            }
            if (cur == null) return null
        }
        return cur
    }

    /** JS 语义：null / "null" / "undefined" 一律当空串（`Js.str(null)` 返回 "undefined"） */
    private fun str(v: Any?): String {
        val s = Js.str(v)
        return if (s == "null" || s == "undefined") "" else s.trim()
    }
}
