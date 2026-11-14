package com.aichat.app.plugins.rp

import com.aichat.app.domain.ChatPlugin
import com.aichat.app.domain.RequestContext
import com.aichat.app.domain.TurnResult
import com.aichat.app.domain.model.AppSettings
import com.aichat.app.domain.model.Conversation
import com.aichat.app.domain.model.Role
import com.aichat.app.plugins.PluginHost
import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.js.jobj
import kotlinx.serialization.json.JsonObject

/**
 * RP 上下文构建器（旧版 `www/rp-plugin.js` 的 onBeforeRequest 主干 + `www/rpcache.js` 的纯函数）。
 *
 * 做三件事，且**只在对话真的填过 RP 块时**才做（其它对话请求逐字节不变）：
 *  1. system 按「固定设定 → 章节摘要 → 长期记忆」分层重排（层序按变更频率从低到高）；
 *  2. 最后一条用户消息改成「本轮输入 + 记忆增量 + 当前状态」，输入放最前，让公共前缀能延伸到上一轮输入末尾；
 *  3. 把同步过的状态写回 `conv.rpState`（syncBase 会推进 staticVersion，不落盘下次就白算）。
 *
 * 还没做（别以为漏了）：章节摘要生成与合并、世界书触发与冻结、预算裁剪、
 * 命中率徽章与面板。见 `docs/ROADMAP.md`。
 */
class RpCachePlugin @Suppress("UNUSED_PARAMETER") constructor(private val host: PluginHost) : ChatPlugin {

    override val id: String get() = "rpcache"
    override val name: String get() = "RP 缓存优化器"
    override val defaultEnabled: Boolean get() = true
    override val perConversation: Boolean get() = true

    /** 上一轮算好的状态，等 onAfterResponse 落盘。 */
    private val pending = HashMap<String, JObj>()

    /** RP 类对话自动启用：只有真的填过块才生效，普通对话不受影响。 */
    override fun autoActive(conv: Conversation, settings: AppSettings): Boolean =
        hasAnyBlock(RpState.normalizeState(Js.fromJson(conv.rpState)))

    private fun hasAnyBlock(st: JObj): Boolean {
        val blocks = st["blocks"] as? List<*> ?: return false
        return blocks.any { b -> b is Map<*, *> && Js.str(b["content"]).isNotEmpty() }
    }

    override suspend fun onBeforeRequest(ctx: RequestContext, status: (String?) -> Unit): RequestContext {
        val base = ctx.systemPrompt
        val st0 = RpState.normalizeState(Js.fromJson(ctx.conv.rpState))
        if (!hasAnyBlock(st0)) return ctx

        val st = RpState.syncBase(st0, base)
        val idx = ctx.messages.indexOfLast { it.role == Role.USER }
        if (idx < 0) return ctx

        val last = ctx.messages[idx]
        val msgs = ArrayList(ctx.messages)
        msgs[idx] = last.copy(content = RpCacheCore.finalUserContent(st, last.content))
        val system = RpCacheCore.buildSystem(st, base)

        pending[ctx.conv.id] = st

        val cap = RpCacheCore.capability(ctx.provider.presetId, ctx.provider.baseUrl, ctx.provider.type.wire, ctx.model)
        val trace = jobj(
            "vendor" to cap.vendor,
            "kind" to cap.kind,
            "system" to RpCacheCore.estimateTokens(system),
            "history" to RpCacheCore.estimateItems(msgs.map { m ->
                jobj("role" to m.role.wire, "content" to m.content, "images" to m.images)
            }),
            "trimmed" to false,
        )
        val meta = LinkedHashMap(ctx.meta)
        meta["rpcache"] = Js.toJson(trace)
        return ctx.copy(systemPrompt = system, messages = msgs, meta = JsonObject(meta))
    }

    override fun onAfterResponse(result: TurnResult, ctx: RequestContext): Conversation? {
        val st = pending.remove(ctx.conv.id) ?: return null
        return ctx.conv.copy(rpState = Js.toJson(st) as? JsonObject ?: ctx.conv.rpState)
    }
}
