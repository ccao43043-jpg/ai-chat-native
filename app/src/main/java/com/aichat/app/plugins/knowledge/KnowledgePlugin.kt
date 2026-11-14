package com.aichat.app.plugins.knowledge

import com.aichat.app.domain.ChatPlugin
import com.aichat.app.domain.RequestContext
import com.aichat.app.domain.model.Conversation
import com.aichat.app.domain.model.Role
import com.aichat.app.plugins.PluginHost
import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.js.jobj
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * www/knowledge-plugin.js 的 onBeforeRequest 移植（本地资料库检索 + [LOCAL_KNOWLEDGE] 注入）。
 *
 * 与 JS 逐条对齐的三道闸：
 *  1. 总闸 settings.plugins.knowledge.injectEnabled（默认关闭）；
 *  2. 对话闸 conv.extras.plugins.knowledge.injectEnabled —— JS 的 convCfg 在值不是布尔时**会把它写成 false**，
 *     所以「没勾过本对话」等价于关闭。这是 JS 的既有行为，不是移植引入的差异；
 *  3. worldId：先 conv.extras.worldId，再 [fallbackWorldId]（JS 里是面板维护的 selectedWorldId）。
 *
 * 面板 / 导入导出 / 世界切换 / traceHtml 不在本插件范围。
 */
class KnowledgePlugin(
    private val host: PluginHost,
    private val store: Knowledge.Store,
    private val fallbackWorldId: () -> String? = { null },
) : ChatPlugin {
    override val id = ID
    override val name = "本地资料库"
    override val defaultEnabled = false
    override val perConversation = false

    /** 最近一次的注入 trace（JS 挂在 conv._knowledgeTrace 上供面板显示；这里留在实例上） */
    @Volatile
    var lastTrace: JObj? = null
        private set

    override suspend fun onBeforeRequest(ctx: RequestContext, status: (String?) -> Unit): RequestContext {
        val g = host.config(ctx.settings, ID)
        if (g["injectEnabled"] != true) return ctx
        if (!convInjectOn(ctx.conv)) return ctx
        val worldId = worldIdOf(ctx.conv) ?: return ctx

        val msgs = ctx.messages
        val idx = msgs.indexOfLast { it.role == Role.USER }
        if (idx < 0) return ctx

        val doc = Knowledge.load(store, worldId)
        val recent = msgs.subList(maxOf(0, idx - 2), idx + 1).joinToString("\n") { it.content }
        val scene = msgs.subList(0, idx).lastOrNull { it.role == Role.ASSISTANT }?.content?.takeLast(200) ?: ""

        val cc = host.convData(ctx.conv, ID)
        val budget = budgetOf(cc, g)

        val already = ArrayList<Any?>()
        if (ctx.systemPrompt.isNotEmpty()) already += ctx.systemPrompt.take(2000)
        val user0 = msgs[idx].content
        if (user0.contains("[MEMORY_UPDATE]") || user0.contains("[MEMORY_RECALL]")) already += user0

        val sel = Knowledge.selectForInject(doc, recent, jobj(
            "budget" to budget.toDouble(),
            "maxBudget" to MAX_BUDGET.toDouble(),
            "alreadyTexts" to already,
            "expand" to jobj("scene" to scene, "context" to scene),
        ))

        // inj 为空也要写 trace（JS 同样先写 ctx.meta.knowledge 再判断注入）
        val trace = (sel["trace"] as? JObj) ?: jobj()
        lastTrace = trace
        val out = ctx.copy(meta = withKnowledge(ctx.meta, trace))

        val inj = Knowledge.renderInjection(sel["hits"] as? List<Any?>)
        if (inj.isEmpty()) return out
        val spliced = Knowledge.spliceIntoUserContent(user0, inj) ?: return out
        val next = msgs.toMutableList()
        next[idx] = msgs[idx].copy(content = textOf(spliced))
        return out.copy(messages = next)
    }

    /** 对话闸；非布尔值一律钉成 false 并视为关闭（复刻 JS convCfg 的副作用） */
    private fun convInjectOn(conv: Conversation): Boolean {
        val cc = host.convData(conv, ID)
        val v = cc["injectEnabled"]
        if (v !is Boolean) {
            cc["injectEnabled"] = false
            return false
        }
        return v
    }

    private fun worldIdOf(conv: Conversation): String? {
        val fromConv = textOf(conv.extras["worldId"])
        if (fromConv.isNotEmpty()) return fromConv
        return fallbackWorldId()?.takeIf { it.isNotEmpty() }
    }

    private fun budgetOf(cc: JObj, g: JObj): Int {
        val raw = if (cc.containsKey("kbTokens")) cc["kbTokens"] else g["kbTokens"]
        val n = toInt(raw) ?: DEFAULT_BUDGET
        return if (n < 0) 0 else if (n > MAX_BUDGET) MAX_BUDGET else n
    }

    private fun withKnowledge(meta: JsonObject, trace: JObj): JsonObject {
        val m = LinkedHashMap(meta)
        m["knowledge"] = Js.toJson(trace)
        return JsonObject(m)
    }

    companion object {
        const val ID = "knowledge"
        /** knowledge-plugin.js：budget = cc.kbTokens ?? g.kbTokens ?? 400，夹在 0..1200 */
        private const val DEFAULT_BUDGET = 400
        private const val MAX_BUDGET = 1200

        private fun toInt(v: Any?): Int? = when (v) {
            null -> null
            is Boolean -> null
            is Number -> v.toInt()
            is String -> v.trim().toDoubleOrNull()?.toInt()
            else -> null
        }

        private fun textOf(e: Any?): String = when (e) {
            null -> ""
            is String -> e
            is JsonPrimitive -> if (e.isString) e.content else ""
            is JsonElement -> ""
            else -> e.toString()
        }
    }
}

/**
 * [Knowledge.Store] 的 store/ 文件适配：key 形如 `knowledge_world_<worldId>.json`，
 * 值按 JS 形状（Map/List/原始值）读写，与旧 WebView 落盘格式逐字节一致。
 */
class JsonKnowledgeStore(
    private val read: (String) -> JsonObject?,
    private val write: (String, JsonObject) -> Boolean,
) : Knowledge.Store {
    override fun get(key: String): Any? = worldIdOf(key)?.let(read)?.let { Js.fromJson(it) }

    override fun set(key: String, value: Any?) {
        val worldId = worldIdOf(key) ?: return
        val e = Js.toJson(value)
        if (e is JsonObject) write(worldId, e)
    }

    private fun worldIdOf(key: String): String? =
        KEY.find(key)?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() }

    private companion object {
        val KEY = Regex("^knowledge_world_(.+)\\.json$")
    }
}
