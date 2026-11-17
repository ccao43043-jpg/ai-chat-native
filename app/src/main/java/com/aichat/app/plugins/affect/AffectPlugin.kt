package com.aichat.app.plugins.affect

import com.aichat.app.domain.Badge
import com.aichat.app.domain.ChatPlugin
import com.aichat.app.domain.RequestContext
import com.aichat.app.domain.Slot
import com.aichat.app.domain.TurnResult
import com.aichat.app.domain.model.AppSettings
import com.aichat.app.domain.model.Conversation
import com.aichat.app.domain.model.Message
import com.aichat.app.domain.model.Role
import com.aichat.app.plugins.PluginHost
import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.js.jobj
import com.aichat.app.plugins.rp.RpState
import kotlinx.serialization.json.JsonObject

/**
 * 好感度 / 心情（旧版 `www/affect.js` 的插件壳）。
 *
 * 数学部分（`Affect.kt`）此前已移植完，缺的是这一层协议：
 *  - 请求前：把固定协议 `[AFFECT_RULE]` 挂进 system（每轮相同 → 可缓存），
 *    把当前状态压成一行短 tag `[AFFECT] mood=… relation=…` 前置到最后一条 user 消息；
 *  - 请求后：从回复尾部解析 `<state>{"a","m","why"}</state>`，剥离标签、按 KF/旧版估计器推进状态，
 *    存进这条消息的 `extras.affect`；
 *  - 徽章：顶栏显示「心情 · 关系」。
 *
 * 还没做：面板（走势图 / 漏标率 / 护栏统计 / 估计器切换）、`invalidateFrom`
 * （切版本或编辑后清除受影响的 affect）。
 */
class AffectPlugin @Suppress("UNUSED_PARAMETER") constructor(private val host: PluginHost) : ChatPlugin {

    override val id: String get() = "affect"
    override val name: String get() = "好感度 / 心情"
    override val defaultEnabled: Boolean get() = true

    /**
     * 按对话启用（旧版是全局默认开，见 SPEC §3.5 偏离 D7）。
     *
     * 原因：affect 会往 system 挂 `[AFFECT_RULE]`、往最后一条 user 前置 `[AFFECT] mood=…`，
     * 模型在**普通聊天**里也会顺着演起来（实测对一句 "hi" 回「（抬眼打量）侦探。不闲聊，有事直说。」），
     * 而且每轮都被记成「未输出 <state>」污染漏标率。所以改成：
     *   - 填过 RP 块的对话（也就是真的在角色扮演）→ 自动开；
     *   - 其余对话 → 默认关，想要就点「本对话插件」里手动开。
     * 全局开关（设置 › 插件）关掉时一律不生效，这一层仍由 isGloballyEnabled 管。
     */
    override val perConversation: Boolean get() = true

    /**
     * 心情/关系状态行（末条 user 首行）。
     * 注意：本插件还会往 systemPrompt 里塞 [AFFECT_RULE] 协议说明——那是历史上的越界行为，
     * S1 的所有者是 rpcache，所以这里**不**声明 S1，只让 trace 的 systemDelta 把它暴露出来；
     * M4 统一装配时再把协议说明并进 S4。
     */
    override fun slots(): List<Slot> = listOf(Slot.S4_TAG)

    override fun autoActive(conv: Conversation, settings: AppSettings): Boolean {
        val st = RpState.normalizeState(Js.fromJson(conv.rpState))
        val blocks = st["blocks"] as? List<*> ?: return false
        return blocks.any { b -> b is Map<*, *> && Js.str(b["content"]).trim().isNotEmpty() }
    }

    /** 本轮冻结的短 tag，等 onAfterResponse 落到那条 user 消息上。 */
    private val pendingInj = HashMap<String, String>()

    override suspend fun onBeforeRequest(ctx: RequestContext, status: (String?) -> Unit): RequestContext {
        val lastIdx = ctx.messages.indexOfLast { it.role == Role.USER }
        if (lastIdx < 0) return ctx

        val now = host.now().toDouble()
        val convJs = convJs(ctx.conv)
        val clock = Affect.clockOf(convJs, null)
        val prev = latestAffect(ctx.conv)
        val turn = if (clock == "turn") Js.toNumber(prev?.get("turn") ?: 0.0) else null
        val st = Affect.decay(prev, now, jobj("clock" to clock, "turn" to turn))
        val tag = renderTag(st)

        val proto = fixedProtocol()
        val sys = ctx.systemPrompt
        val system = when {
            sys.contains("[AFFECT_RULE]") -> sys
            sys.isEmpty() -> proto
            else -> sys + "\n" + proto
        }

        // 漏标补偿：最近若干轮模型没输出 <state> 时，用存盘的好感值给历史 assistant 补回 <state> 示范。
        // 只在请求里补，不写回对话（与 affect.js 的 MISS_THRESHOLD / DEMO_K 口径一致）。
        val demo = Affect.shouldDemoState(convJs, Affect.MISS_WINDOW)
        var demoLeft = if (demo) Affect.DEMO_K else 0

        val out = ArrayList(ctx.messages)
        for (i in out.indices) {
            val m = out[i]
            when {
                // 历史 assistant：默认剥掉 <state>（模型不该看到自己上一轮的标签格式）
                m.role == Role.ASSISTANT -> {
                    var text = stripState(m.content)
                    if (demoLeft > 0 && !text.contains("<state>")) {
                        val aff = affectOf(ctx.conv, m.srcId)
                        val rebuilt = if (aff != null) Affect.rebuildStateTag(aff) else ""
                        if (rebuilt.isNotEmpty()) {
                            text = text.trimEnd() + "\n" + rebuilt
                            demoLeft--
                        }
                    }
                    out[i] = m.copy(content = text)
                }
                m.role == Role.USER && i == lastIdx -> out[i] = m.copy(content = tag + "\n" + m.content)
                m.role == Role.USER -> {
                    // 历史 user：只回放当轮冻结的短 tag（P0-3）
                    val inj = injOf(ctx.conv, m.srcId)
                    if (inj != null && !m.content.startsWith(inj)) out[i] = m.copy(content = inj + "\n" + m.content)
                }
            }
        }

        pendingInj[ctx.conv.id] = tag
        val meta = LinkedHashMap(ctx.meta)
        meta["affect"] = Js.toJson(jobj("injection" to tag, "clock" to clock, "demo" to false))
        return ctx.copy(systemPrompt = system, messages = out, meta = JsonObject(meta))
    }

    override fun onAfterResponse(result: TurnResult, ctx: RequestContext): Conversation? {
        val tag = pendingInj.remove(ctx.conv.id)
        val bot = result.assistant
        val raw = bot.content
        val stripped = stripState(raw)

        // 1) 先把 <state> 从正文里剥掉，2) 把这轮 tag 冻到那条 user 消息上
        var conv = upsertMessage(ctx.conv, bot.copy(content = stripped))
        if (tag != null) {
            val lastUser = conv.messages.lastOrNull { it.role == Role.USER }
            if (lastUser != null) conv = replaceMessage(conv, lastUser.id) { it.withExtra("affectInj", tag) }
        }
        if (!result.ok) return conv

        val parsed = Affect.parseState(raw)
        val convJs = convJs(ctx.conv)
        val clock = Affect.clockOf(convJs, null)
        val prev = latestAffectBefore(ctx.conv, bot.id)
        val turn = if (clock == "turn") Js.toNumber(prev?.get("turn") ?: 0.0) + 1 else null
        val est = Affect.estimatorOf(convJs, JObj())
        val lastUserText = ctx.conv.messages.lastOrNull { it.role == Role.USER }?.content ?: ""

        val st = Affect.step(
            prev, parsed, lastUserText, host.now().toDouble(),
            jobj(
                "mode" to Affect.modeOf(convJs), "clock" to clock, "turn" to turn,
                "estimator" to est, "assistantText" to stripped,
                "settings" to JObj(), "conv" to convJs,
            ),
        )
        // 模型这轮没输出 <state>：记一笔漏标（JS 的 p='missing_state'），面板才能算漏标率并触发补偿示范
        val tagged = if (parsed == null) {
            val mark = LinkedHashMap<String, Any?>(st)
            mark["a"] = 0
            mark["stateMissing"] = true
            mark["p"] = "missing_state"
            mark
        } else st
        return replaceMessage(conv, bot.id) { it.withExtra("affect", Js.toJson(tagged)) }
    }

    override fun headerBadges(conv: Conversation): List<Badge> {
        val lt = latestAffect(conv) ?: return emptyList()
        val clock = Affect.clockOf(convJs(conv), null)
        val turn = if (clock == "turn") Js.toNumber(lt["turn"] ?: 0.0) else null
        val s = Affect.decay(lt, System.currentTimeMillis().toDouble(), jobj("clock" to clock, "turn" to turn))
        val aff = Js.toNumber(s["aff"])
        val t = Affect.tier(aff)
        val rel = Affect.tierLabel(t)
        val mood = Js.str(s["mood"]).ifEmpty { "平静" }
        return listOf(
            Badge(
                plugin = id, text = "$mood · $rel", tone = if (t <= 2) "warn" else "info",
                detail = "心情 $mood · 关系 $rel（档 $t，好感 ${"%.2f".format(aff)}）",
            ),
        )
    }

    // ---- JS 语义适配（Affect.kt 的入参都是 Map，不是 JsonObject） ----

    private fun convJs(conv: Conversation): JObj {
        val msgs = conv.messages.mapTo(ArrayList<Any?>()) { m ->
            val o = jobj("id" to m.id, "role" to m.role.wire, "content" to m.content)
            (m.extras["affect"] as? JsonObject)?.let { o["affect"] = Js.fromJson(it) }
            o
        }
        val c = jobj("messages" to msgs)
        (conv.extras["affectMode"] as? String)?.let { c["affectMode"] = it }
        (conv.extras["affectClock"] as? String)?.let { c["affectClock"] = it }
        (conv.extras["tags"] as? JsonObject)?.let { c["tags"] = Js.fromJson(it) }
        return c
    }

    private fun latestAffect(conv: Conversation): JObj? = conv.messages.lastOrNull {
        it.role == Role.ASSISTANT && it.extras["affect"] is JsonObject
    }?.let { Js.fromJson(it.extras["affect"]!!) as? JObj }

    private fun latestAffectBefore(conv: Conversation, beforeId: String): JObj? {
        val end = conv.messages.indexOfFirst { it.id == beforeId }.let { if (it < 0) conv.messages.size else it }
        for (i in end - 1 downTo 0) {
            val m = conv.messages[i]
            if (m.role == Role.ASSISTANT && m.extras["affect"] is JsonObject) {
                return Js.fromJson(m.extras["affect"]!!) as? JObj
            }
        }
        return null
    }

    private fun injOf(conv: Conversation, srcId: String?): String? {
        if (srcId == null) return null
        return conv.messages.firstOrNull { it.id == srcId }?.extras?.get("affectInj") as? String
    }

    /** 取某条消息存盘的好感状态（JsObject 形状，给漏标补偿重建 `<state>` 用）。 */
    private fun affectOf(conv: Conversation, srcId: String?): Any? {
        if (srcId == null) return null
        val el = conv.messages.firstOrNull { it.id == srcId }?.extras?.get("affect") ?: return null
        return Js.fromJson(el)
    }

    private fun replaceMessage(conv: Conversation, id: String, f: (Message) -> Message): Conversation {
        val ms = conv.messages.map { if (it.id == id) f(it) else it }
        return conv.copy(messages = ms)
    }

    /** 引擎可能已经把这轮回复加进对话，也可能还没加 —— 两种都接住。 */
    private fun upsertMessage(conv: Conversation, msg: Message): Conversation {
        if (conv.messages.any { it.id == msg.id }) return replaceMessage(conv, msg.id) { msg }
        return conv.copy(messages = conv.messages + msg)
    }

    private fun Message.withExtra(key: String, value: kotlinx.serialization.json.JsonElement): Message {
        val e = LinkedHashMap(extras)
        e[key] = value
        return copy(extras = JsonObject(e))
    }

    private fun Message.withExtra(key: String, value: String): Message =
        withExtra(key, kotlinx.serialization.json.JsonPrimitive(value))

    private fun stripState(text: String): String = Affect.stripState(text) as? String ?: text

    private fun renderTag(st: Any?): String =
        "[AFFECT] mood=" + (Js.str(st?.let { (it as? Map<*, *>)?.get("mood") }).ifEmpty { "平静" }) +
            " relation=" + Affect.relationOf(st)

    private fun fixedProtocol(): String =
        "[AFFECT_RULE] relation 五档含义：疏远(1)/戒备(2)/熟悉(3)/亲近(4)/亲密(5)。" +
            "回复最末尾单独一行输出 <state>{\"a\":好感变化-2~2整数,\"m\":\"" + Affect.MOODS.joinToString("|") +
            "\",\"why\":\"a≠0 时摘用户原话 ≤12 字作依据\"}</state>，正文不要提及该标签或状态。"
}
