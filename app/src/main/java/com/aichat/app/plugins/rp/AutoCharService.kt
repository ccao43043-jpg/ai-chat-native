package com.aichat.app.plugins.rp

import com.aichat.app.domain.model.AppSettings
import com.aichat.app.domain.model.Conversation
import com.aichat.app.domain.model.PromptTemplate
import com.aichat.app.domain.model.Role
import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.js.jobj

/**
 * 「用 AI 生成角色设定」的**执行层**：把 [AutoChar] 那套纯函数接到真实请求与落盘上。
 *
 * 为什么要有这一层：`AutoChar` 里 7 个入口（shouldOffer / buildPrompt / applyToState …）写了很久
 * 却**零调用**——纯函数再正确，没人调也是死的。这里负责三件它不做的事：
 *   1. 把 native [Conversation] 摊成 JS 形状（AutoChar 的状态读取沿用旧版口径，不另造一套）；
 *   2. 调一次非流式请求（由调用方注入 `ask`，与 rpmemory 的抽取共用同一条通道）；
 *   3. 给出「写回了哪几块、丢了哪几块、为什么没写」的可展示结果。
 *
 * 作用域由 [AutoChar.scopeOf] 决定：绑了世界就只补角色卡，绝不重写用户已经写好的世界观。
 */
class AutoCharService(private val ask: suspend (Conversation, String) -> String?) {

    data class Outcome(
        val ok: Boolean,
        /** 本次走的作用域（世界里 = CHARACTER_ONLY） */
        val scope: AutoChar.Scope,
        /** done / already（已有角色卡，不覆盖）/ failed */
        val status: String,
        val applied: List<String> = emptyList(),
        val dropped: List<String> = emptyList(),
        /** 写回后的 rpState；ok 时才有意义 */
        val state: JObj? = null,
        val message: String = "",
    )

    /**
     * native 会话 → JS 形状（AutoChar 读 conv["plugins"]["rpcache"]["state"] / conv["worldId"] …）。
     *
     * 这里手搭而不是复用 ConversationCodec.encode：codec 的产物是**存储形状**（rpState 在顶层、
     * worldId 这类键回落进 extras），而 AutoChar 读的是 JS 运行时形状（状态挂在 plugins.rpcache.state、
     * 世界键在顶层）。两套形状混用正是上一版读不到世界的原因。
     */
    fun convJs(conv: Conversation): JObj {
        val o = jobj(
            "id" to conv.id,
            "title" to conv.title,
            "providerId" to conv.providerId,
            "model" to conv.model,
            "systemPrompt" to conv.systemPrompt,
            "templateId" to conv.templateId,
            "messages" to conv.messages.map { m ->
                jobj("id" to m.id, "role" to m.role.wire, "content" to m.content)
            }.toMutableList(),
            "plugins" to jobj("rpcache" to jobj("state" to stateJs(conv))),
        )
        // extras 里的顶层键（worldId / worldName / worldEmoji / worldRef …）在 JS 里就是 conv 的顶层键
        conv.extras.forEach { (k, v) -> if (k !in o) o[k] = Js.fromJson(v) }
        return o
    }

    fun templatesJs(templates: List<PromptTemplate>): List<Any?> = templates.map { t ->
        jobj("id" to t.id, "name" to t.name, "tags" to t.tags, "skill" to t.skill, "content" to t.content)
    }

    /**
     * rpState 是 kotlinx 的 JsonObject，而 RpState / AutoChar 认的是 Kotlin 的 Map。
     * `RpState.normalizeState` 遇到非 Map 会**直接返回空状态**（不抛错），
     * 于是世界里生成会把世界块当空、并把用户写的角色卡覆盖掉 —— 必须先转一道。
     */
    private fun stateJs(conv: Conversation): JObj = (Js.fromJson(conv.rpState) as? JObj) ?: jobj()

    /** 这里重写而不是复用 `AutoChar.mode(settings)`：native AppSettings 不是 JS 对象，读法不一样 */
    fun modeOf(settings: AppSettings): String {
        val m = settings.autoCharMode.ifEmpty { "auto" }.lowercase()
        return if (m == "ask" || m == "auto" || m == "off") m else "auto"
    }

    /** 现在该不该问「要不要生成角色」：模式不是 off、且满足 AutoChar 的四个前置条件 */
    fun offer(conv: Conversation, templates: List<PromptTemplate>, settings: AppSettings): Boolean {
        if (modeOf(settings) == "off") return false
        return AutoChar.shouldOffer(convJs(conv), templatesJs(templates), jobj())
    }

    /** 生成并写回。返回的 [Outcome.state] 由调用方存进会话（这里不碰仓库，方便单测与重试） */
    suspend fun generate(
        conv: Conversation,
        templates: List<PromptTemplate>,
        settings: AppSettings,
        skill: String? = null,
        at: Long = System.currentTimeMillis(),
    ): Outcome {
        val cj = convJs(conv)
        val scope = AutoChar.scopeOf(cj)
        val first = conv.messages.firstOrNull { it.role == Role.USER && it.content.isNotBlank() }?.content.orEmpty()
        if (first.isBlank()) return Outcome(false, scope, "failed", message = "还没有用户消息")

        val sys = conv.systemPrompt.orEmpty()
        val world = AutoChar.worldOf(cj)
        val prompt = if (scope == AutoChar.Scope.CHARACTER_ONLY) {
            AutoChar.buildCharacterPrompt(first, sys, world, skill)
        } else {
            AutoChar.buildPrompt(first, sys, skill)
        }

        val raw = ask(conv, prompt) ?: return Outcome(false, scope, "failed", message = "模型没有返回内容")
        val fields = AutoChar.parse(raw) ?: return Outcome(false, scope, "failed", message = "没能从回复里解析出设定")

        val applied = AutoChar.applyToState(stateJs(conv), fields, scope, at)
        return Outcome(
            ok = applied.status == "applied",
            scope = scope,
            status = applied.status,
            applied = applied.applied,
            dropped = applied.dropped,
            state = applied.state,
            message = when (applied.status) {
                "applied" -> if (scope == AutoChar.Scope.CHARACTER_ONLY) "已写入角色卡（世界设定未改动）" else "已写入角色设定"
                "already" -> "这个世界的角色卡已经有了，没有覆盖"
                "empty" -> "模型没给出可用的设定"
                else -> applied.status
            },
        )
    }

    /** `t-rp-cardgen` 这类 Skill 模板的正文，作为生成时的格式要求（没有就传 null） */
    fun skillOf(templates: List<PromptTemplate>): String? =
        templates.firstOrNull { it.id == "t-rp-cardgen" }?.content?.takeIf { it.isNotBlank() }
}
