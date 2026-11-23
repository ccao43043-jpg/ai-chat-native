package com.aichat.app.plugins.rp

import com.aichat.app.domain.model.AppSettings
import com.aichat.app.domain.model.Conversation
import com.aichat.app.domain.model.PromptTemplate
import com.aichat.app.domain.model.Role
import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.js.jobj
import kotlinx.serialization.json.JsonObject

/**
 * 「用 AI 生成角色设定」的**执行层**：把 [AutoChar] 那套纯函数接到真实请求与落盘上。
 *
 * 1.8 起不再一次请求生成整本：按 2–4 个字段级短批次请求，每批只带相关上下文。
 * 目的不是绕过审核，而是让正常内容更容易通过，并且单批失败不会清空已成功字段：
 *   - 全部作用域（没有绑定世界）：世界观+角色卡 / 规则+格式 / 示例+禁止 / 风格+开场白；
 *   - 世界作用域：角色卡 / 示例对白（世界设定只读，绝不改写）。
 *
 * 每批成功后通过 [onProgress] 把当前 rpState 交回调用方落盘（检查点）；
 * 失败批次记进 [Outcome.failedBatches]，调用方可用 [generate] 的 `only` 只重跑失败批次。
 */
class AutoCharService(private val ask: suspend (Conversation, String) -> String?) {

    data class BatchSpec(val key: String, val title: String, val ids: List<String>)

    data class BatchResult(
        val key: String,
        val title: String,
        /** done / failed */
        val status: String,
        val applied: List<String> = emptyList(),
        val dropped: List<String> = emptyList(),
        val reason: String = "",
    )

    data class Progress(
        val current: Int,
        val total: Int,
        val title: String,
        val batches: List<BatchResult>,
        /** 正在请求的批次标题；空串表示该批已结束 */
        val running: String = "",
    )

    data class Outcome(
        val ok: Boolean,
        /** 本次走的作用域（世界里 = CHARACTER_ONLY） */
        val scope: AutoChar.Scope,
        /** done / partial / already / failed */
        val status: String,
        val applied: List<String> = emptyList(),
        val dropped: List<String> = emptyList(),
        /** 本次仍失败的批次 key，供「只重跑失败批次」使用 */
        val failedBatches: List<String> = emptyList(),
        /** 写回后的 rpState；有任何批次成功时才有意义 */
        val state: JObj? = null,
        /** 全部作用域下的开场白；世界里为空。由调用方决定是否插入 */
        val opening: String = "",
        val batches: List<BatchResult> = emptyList(),
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

    /** 已持久化的自动生成状态（conv.extras.autoChar）；没有则为 null */
    fun stateOf(conv: Conversation): JObj? =
        (conv.extras["autoChar"] as? JsonObject)?.let { Js.fromJson(it) as? JObj }

    /**
     * 询问状态的作用域键：绑定世界优先用 worldId；只有 blocks.world 内容时用 "blocks"。
     * 换世界后旧状态不再命中，会重新评估一次，避免「在 A 世界选过不用，B 世界永远不问」。
     */
    fun worldKey(conv: Conversation): String {
        val w = AutoChar.worldOf(convJs(conv)) ?: return ""
        return when {
            w.worldId.isNotEmpty() -> w.worldId
            w.world.isNotEmpty() || w.lore.isNotEmpty() -> "blocks"
            else -> ""
        }
    }

    /** rpState 是 kotlinx 的 JsonObject，而 RpState / AutoChar 认的是 Kotlin 的 Map。 */
    private fun stateJs(conv: Conversation): JObj = (Js.fromJson(conv.rpState) as? JObj) ?: jobj()

    /** 这里重写而不是复用 `AutoChar.mode(settings)`：native AppSettings 不是 JS 对象，读法不一样 */
    fun modeOf(settings: AppSettings): String {
        val m = settings.autoCharMode.ifEmpty { "auto" }.lowercase()
        return if (m == "ask" || m == "auto" || m == "off") m else "auto"
    }

    /**
     * 现在该不该问「要不要生成角色」：模式不是 off、满足 AutoChar 的候选条件，
     * 且当前世界作用域下还没问过。世界里开场白是 assistant 消息，但不应因此永远不触发。
     */
    fun offer(conv: Conversation, templates: List<PromptTemplate>, settings: AppSettings): Boolean {
        if (modeOf(settings) == "off") return false
        val st = stateOf(conv)
        val key = worldKey(conv)
        if (st != null && Js.truthy(st["asked"]) && Js.str(st["worldId"]) == key) return false
        val cj = convJs(conv)
        // 上一个世界的状态不能压制当前世界：形状上先去掉，交给 shouldOffer 重新判断。
        if (st != null && Js.str(st["worldId"]) != key) cj.remove("autoChar")
        return AutoChar.shouldOffer(cj, templatesJs(templates), jobj())
    }

    /**
     * 生成并写回。返回的 [Outcome.state] 由调用方存进会话（这里不碰仓库，方便单测与重试）。
     *
     * @param overwrite 手动「重新生成角色卡」传 true；自动生成保持 false（已有角色卡时不覆盖）。
     * @param only      只重跑这些批次 key（来自上一次的 [Outcome.failedBatches]）。
     * @param onProgress 每批开始/结束时回调；结束时可直接把 state 落盘做检查点。
     */
    suspend fun generate(
        conv: Conversation,
        templates: List<PromptTemplate>,
        settings: AppSettings,
        skill: String? = null,
        at: Long = System.currentTimeMillis(),
        overwrite: Boolean = false,
        only: List<String>? = null,
        onProgress: (suspend (Progress, JObj) -> Unit)? = null,
    ): Outcome {
        val cj = convJs(conv)
        val scope = AutoChar.scopeOf(cj)
        val first = conv.messages.firstOrNull { it.role == Role.USER && it.content.isNotBlank() }?.content.orEmpty()
        if (first.isBlank()) return Outcome(false, scope, "failed", message = "还没有用户消息")

        val sys = conv.systemPrompt.orEmpty()
        val world = AutoChar.worldOf(cj)
        var state = stateJs(conv)
        var opening = ""
        var already = false
        val results = ArrayList<BatchResult>()
        val appliedAll = ArrayList<String>()
        val droppedAll = ArrayList<String>()
        val failedAll = ArrayList<String>()

        val allSpecs = batchesOf(scope)
        val specs = if (only.isNullOrEmpty()) allSpecs else allSpecs.filter { it.key in only }
        if (specs.isEmpty()) return Outcome(false, scope, "failed", message = "没有需要重跑的批次")

        for ((i, spec) in specs.withIndex()) {
            onProgress?.invoke(Progress(i, specs.size, spec.title, results.toList(), spec.title), state)
            val prior = priorFields(state, spec.ids)
            val prompt = buildBatchPrompt(scope, first, sys, world, skill, spec.ids, prior)
            val raw = ask(conv, prompt)
            if (raw.isNullOrBlank()) {
                failedAll += spec.key
                results += BatchResult(spec.key, spec.title, "failed", reason = "模型没有返回内容")
                onProgress?.invoke(Progress(i + 1, specs.size, spec.title, results.toList()), state)
                continue
            }
            val fields = AutoChar.parseBatch(raw, spec.ids)
            if (fields.isEmpty()) {
                failedAll += spec.key
                results += BatchResult(spec.key, spec.title, "failed", reason = "没能从回复里解析出「${spec.title}」")
                onProgress?.invoke(Progress(i + 1, specs.size, spec.title, results.toList()), state)
                continue
            }

            val rawOpening = fields["opening"]
            val open = if (Js.truthy(rawOpening)) Js.trim(Js.str(rawOpening)) else ""
            if (open.isNotEmpty()) opening = open

            val apply = JObj()
            for (id in spec.ids) if (id != "opening" && Js.truthy(fields[id])) apply[id] = fields[id]
            var applied = emptyList<String>()
            var dropped = emptyList<String>()
            if (apply.isNotEmpty()) {
                val r = AutoChar.applyToState(state, apply, scope, at, overwrite = overwrite)
                state = r.state
                applied = r.applied
                dropped = r.dropped
                appliedAll += applied
                droppedAll += dropped
                if (r.status == "already") {
                    // 非覆盖模式下已有角色卡：后续批次也一并停下，避免只补示例对白造成半套设定。
                    already = true
                    results += BatchResult(spec.key, spec.title, "done", applied, dropped)
                    onProgress?.invoke(Progress(i + 1, specs.size, spec.title, results.toList()), state)
                    break
                }
            }
            results += BatchResult(spec.key, spec.title, "done", applied, dropped)
            onProgress?.invoke(Progress(i + 1, specs.size, spec.title, results.toList()), state)
        }

        val ok = appliedAll.isNotEmpty() || opening.isNotEmpty()
        val status = when {
            already && !ok -> "already"
            !ok -> "failed"
            failedAll.isEmpty() -> "done"
            else -> "partial"
        }
        val prefix = if (scope == AutoChar.Scope.CHARACTER_ONLY) "已写入角色卡（世界设定未改动）" else "已写入角色设定"
        val message = when (status) {
            "already" -> "这个世界的角色卡已经有了，没有覆盖"
            "done", "partial" -> prefix
            else -> if (failedAll.size == specs.size && results.all { it.reason == "模型没有返回内容" }) "模型没有返回内容"
                else "没能从回复里解析出设定"
        }
        return Outcome(
            ok = ok,
            scope = scope,
            status = status,
            applied = appliedAll.distinct(),
            dropped = droppedAll.distinct(),
            failedBatches = failedAll,
            state = state,
            opening = opening,
            batches = results,
            message = message,
        )
    }

    /** 字段级批次：每批 1–2 个标题，减少无关上下文造成的误判与截断。 */
    fun batchesOf(scope: AutoChar.Scope): List<BatchSpec> =
        if (scope == AutoChar.Scope.CHARACTER_ONLY) listOf(
            BatchSpec("character", "角色卡", listOf("character")),
            BatchSpec("fewshot", "示例对白", listOf("fewshot")),
        ) else listOf(
            BatchSpec("world_character", "世界观 + 角色卡", listOf("world", "character")),
            BatchSpec("rules_format", "RP 规则 + 输出格式", listOf("rules", "format")),
            BatchSpec("fewshot_prohibit", "示例对白 + 禁止事项", listOf("fewshot", "prohibit")),
            BatchSpec("style_opening", "写作风格 + 开场白", listOf("style", "opening")),
        )

    private fun titleOf(id: String): String = AutoChar.TITLES[id] ?: if (id == "opening") "开场白" else id

    /** 已完成字段作为下一批的上下文（保持一致），但不再重复要求本批正在生成的字段。 */
    private fun priorFields(state: JObj, exclude: List<String>): JObj {
        val o = JObj()
        for (id in AutoChar.TITLES.keys) {
            if (id in exclude) continue
            val text = Js.trim(Js.str(RpState.getBlock(state, id)?.get("content")))
            if (text.isNotEmpty()) o[id] = text
        }
        return o
    }

    private fun priorText(prior: JObj): String =
        AutoChar.TITLES.keys.filter { Js.truthy(prior[it]) }.joinToString("\n\n") { id ->
            "【${AutoChar.TITLES[id]}】\n" + Js.slice(Js.str(prior[id]), 0, 2000)
        }

    /**
     * 单批提示词：与整本提示词共用 [AutoChar.rulesOf]，只列本批标题。
     * 世界里把世界设定标为只读；opening 只作为输出字段，不写进系统提示词。
     */
    fun buildBatchPrompt(
        scope: AutoChar.Scope,
        firstText: String,
        systemPrompt: String,
        world: AutoChar.WorldContext?,
        skillContent: String?,
        ids: List<String>,
        prior: JObj,
    ): String {
        val titles = ids.joinToString("、") { titleOf(it) }
        val n = ids.size
        val rules = AutoChar.rulesOf(scope, skillContent)
        val sb = StringBuilder()
        sb.append(rules).append("\n\n---\n")
        if (scope == AutoChar.Scope.CHARACTER_ONLY) {
            sb.append("下面是一场角色扮演，世界观与规则已经写好。你只负责补写其中 ").append(n).append(" 段，绝对不要改写或复述世界设定。\n")
            val w = world?.world.orEmpty()
            val lore = world?.lore.orEmpty()
            if (w.isNotEmpty()) sb.append("\n【已有世界设定（只读，不要改写，也不要复述）】\n").append(Js.slice(w, 0, 6000)).append("\n")
            if (lore.isNotEmpty()) sb.append("\n【已有关键设定（只读）】\n").append(Js.slice(lore, 0, 2000)).append("\n")
        } else {
            sb.append("下面是一场角色扮演的设定上下文。这一次只写其中 ").append(n).append(" 段，不要写其他段落。\n")
        }
        sb.append("硬性要求：\n")
        sb.append("1. 输出 ").append(n).append(" 段，每段以单独一行的【标题】开头，标题字面完全一致，按以下顺序：")
            .append(titles).append("；不要加序号、不要用 Markdown 标题或代码块；\n")
        sb.append("2. 段内用「- 」列表；\n")
        sb.append("3. 用户首条消息里已经给出的名字、身份、关系、场景必须原样保留，只补全缺失部分；\n")
        sb.append("4. 只输出上面列出的段落，不要输出其它【标题】段落；不要寒暄、解释或提问；\n")
        sb.append("5. 所有内容写成成品，不留 {{变量}}、「待定」或需要用户填写的空位。\n")
        if (ids.contains("character")) sb.append("6. 【角色卡】第一行写「- 姓名：xxx」，写具体可扮演的细节（身份、外貌、性格、说话方式、喜好禁忌、关系、动机）。\n")
        if (ids.contains("world")) sb.append("· 【世界观】写时代、地点、势力、规则，并写「- 主线剧情：」与「- 当前局势：」。\n")
        if (ids.contains("fewshot")) sb.append("· 【示例对白】写 2–3 轮符合角色口吻的示例，用「角色名：台词」格式。\n")
        if (ids.contains("opening")) sb.append("· 【开场白】写一段符合当前 RP 模板文风、以角色口吻展开的开场。\n")

        val pt = priorText(prior)
        if (pt.isNotEmpty()) sb.append("\n【已经写好的设定（保持一致，不要重复）】\n").append(pt).append("\n")
        if (systemPrompt.isNotBlank()) sb.append("\n【当前 RP 模板（系统提示词，供参考，保持一致）】\n").append(Js.slice(systemPrompt, 0, 3000)).append("\n")
        sb.append("\n【用户首条消息】\n").append(Js.slice(firstText, 0, 6000))
        return sb.toString()
    }

    /** `t-rp-cardgen` 这类 Skill 模板的正文，作为生成时的格式要求（没有就传 null） */
    fun skillOf(templates: List<PromptTemplate>): String? =
        templates.firstOrNull { it.id == "t-rp-cardgen" }?.content?.takeIf { it.isNotBlank() }
}