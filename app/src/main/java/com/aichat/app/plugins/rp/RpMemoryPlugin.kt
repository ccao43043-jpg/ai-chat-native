package com.aichat.app.plugins.rp

import com.aichat.app.data.repo.JsonDocStore
import com.aichat.app.domain.ChatPlugin
import com.aichat.app.domain.RequestContext
import com.aichat.app.domain.Slot
import com.aichat.app.domain.model.AppSettings
import com.aichat.app.domain.model.Conversation
import com.aichat.app.domain.model.Role
import com.aichat.app.plugins.PluginHost
import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.js.jobj
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 结构化记忆（rpmemory.js 的插件层）。
 *
 * 与 JS 一致的三个行为：
 * 1. **注入只改最后一条 user 消息**，不动 system、不动历史 → 缓存前缀不受影响；
 * 2. 每 `interval` 个用户回合自动提取一次，**失败也推进 lastTurn**（避免每轮重试烧钱，可手动重试）；
 * 3. 提取结果按 `charKey` 存一份（有角色卡时跨对话共享，否则按对话隔离）。
 *
 * 与 JS 的已知差异（都在注释里标了 WHY）：
 * - `lastTurn` 存在记忆文件里，而不是 `conv.extras.plugins`：插件层拿不到「写回会话」的时机，
 *   存文件更简单也不丢；代价是同一对话在两个设备上会各算各的（旧版也一样，因为写在 conv 里）。
 * - 提取是 fire-and-forget 的 `CoroutineScope`，没有 `host.isCurrent(conv)` 那种「还在看这个对话」判断；
 *   提取完写的是文件，不刷新 UI，所以没有可见影响。
 */
class RpMemoryPlugin(
    private val host: PluginHost,
    private val store: JsonDocStore,
    /** 用「当前模型」跑一次抽取请求；返回 null 表示没有可用服务商/失败。 */
    private val ask: suspend (Conversation, String) -> String?,
) : ChatPlugin {

    override val id: String = "rpmemory"
    override val name: String = "结构化记忆"
    override val defaultEnabled: Boolean = false
    override val perConversation: Boolean = false

    override fun inactiveReason(conv: Conversation, settings: com.aichat.app.domain.model.AppSettings): String =
        "本对话还没有抽取过记忆（默认每 20 轮抽一次，可在记忆面板手动提取）"
    /** 结构化记忆只往末条 user 尾部追加召回（S3） */
    override fun slots(): List<Slot> = listOf(Slot.S3_RECALL)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    private val running = HashSet<String>()

    override suspend fun onBeforeRequest(ctx: RequestContext, status: (String?) -> Unit): RequestContext {
        val idx = ctx.messages.indexOfLast { it.role == Role.USER }
        if (idx < 0) return ctx
        val bucket = bucketOf(charKey(ctx.conv)) ?: return ctx
        val entries = entriesOf(bucket)
        if (entries.isEmpty()) return ctx

        val cfg = cfgOf(ctx.settings)
        val q = ctx.messages.subList(maxOf(0, idx - 2), idx + 1)
            .joinToString("\n") { it.content }
        val lastA = ctx.messages.subList(0, idx).lastOrNull { it.role == Role.ASSISTANT }
        val mood = (ctx.conv.extras["affect"] as? JsonObject)
            ?.let { (it["mood"] ?: it["label"]) }
            ?.let { if (it is kotlinx.serialization.json.JsonPrimitive && it.isString) it.content else "" }
            ?: ""
        val pinKw = entries.filter { Js.truthy(it["pinned"]) && !Js.truthy(it["disabled"]) }
            .flatMap { (it["keywords"] as? List<*>)?.mapNotNull { k -> k?.toString() } ?: emptyList() }

        val opt = jobj(
            "maxInject" to cfg.maxInject,
            "pinBudget" to cfg.pinBudget,
            "memTokens" to cfg.memTokens,
            "retrieval" to cfg.retrieval,
            "minScore" to cfg.minScore,
            "tau" to cfg.tau,
            "mmrLambda" to cfg.mmrLambda,
            // rpcache 用真实 usage 校准出来的系数，记忆条目也按同一把尺子折：否则同一次请求里
            // 「分层预算」按校准值算、「记忆预算」按未校准值算，两条预算的判断互相矛盾。
            "calib" to calibOf(ctx.conv),
            "nowTurn" to userTurns(ctx.conv),
            "expand" to jobj(
                "context" to (mood + " " + (lastA?.content ?: "").takeLast(200)),
                "pinnedKeywords" to pinKw,
            ),
        )
        val sel = RpMemoryCore.selectRelevant(entries, q, opt)
        val inj = RpMemoryCore.renderInjection(sel)
        if (inj.isEmpty()) return ctx

        val msgs = ctx.messages.toMutableList()
        val m = msgs[idx]
        msgs[idx] = m.copy(content = RpMemoryCore.appendToContent(m.content, inj))
        val meta = JsonObject(ctx.meta.toMutableMap().also {
            it["rpmemory"] = Js.toJson(jobj("ids" to sel.map { e -> e["id"] }))
        })
        return ctx.copy(messages = msgs, meta = meta)
    }

    override fun onAfterResponse(result: com.aichat.app.domain.TurnResult, ctx: RequestContext): Conversation? {
        if (!result.ok) return null
        val conv = ctx.conv
        val cfg = cfgOf(ctx.settings)
        val bucket = bucketOf(charKey(conv)) ?: return null
        val lastTurn = (bucket["lastTurn"] as? Number)?.toInt() ?: 0
        if (!RpMemoryCore.shouldExtract(userTurns(conv), lastTurn, cfg.interval)) return null
        scope.launch { extract(conv, ctx.settings, manual = false) }
        return null
    }

    /** 立即提取（面板「立即提取」用）。返回 `(added, merged)`；`skipped` 表示已有一次在跑。 */
    suspend fun extractNow(conv: Conversation, settings: AppSettings): Result<Pair<Int, Int>> =
        runCatching { extract(conv, settings, manual = true) ?: (0 to 0) }

    /** 提取一次；返回 (added, merged)。JS 的 extract() 等价物。 */
    private suspend fun extract(conv: Conversation, settings: AppSettings, manual: Boolean): Pair<Int, Int>? {
        val key = charKey(conv)
        synchronized(running) { if (!running.add(key)) return null }
        try {
            val cfg = cfgOf(settings)
            val bucket = bucketOf(key) ?: jobj()
            val entries = entriesOf(bucket)
            val msgs = conv.messages
                .filter { (it.role == Role.USER || it.role == Role.ASSISTANT) && it.error == null && !Js.truthy(it.extras["pending"]) }
                .takeLast(cfg.window * 2)
            if (msgs.isEmpty()) return 0 to 0

            val prompt = RpMemoryCore.buildExtractPrompt(
                msgs.map { m -> m.extras.toMutableMap() + mapOf("role" to m.role.wire, "content" to m.content) },
                entries,
            )
            val raw = ask(conv, prompt) ?: throw IllegalStateException("没有可用的服务商或模型")
            val items = RpMemoryCore.parseExtraction(raw)
            val turns = userTurns(conv)
            val ledgerIn = (bucket["ledger"] as? List<*>)?.mapNotNull { it as? JObj }
            val res = RpMemoryCore.mergeEntries(entries, items, System.currentTimeMillis(), if (manual) "manual" else "auto", jobj("turn" to turns, "ledger" to ledgerIn))
            val merged = res.list

            mutex.withLock {
                val all = store.load().toMutableMap()
                all[key] = Js.toJson(
                    jobj(
                        "entries" to merged,
                        "lastTurn" to turns,
                        "lastAt" to System.currentTimeMillis(),
                        "lastError" to "",
                        "ledger" to (res.ledger ?: ledgerIn),
                    )
                )
                store.save(JsonObject(all))
            }
            // host.notify 只做提示；自动提取不打扰用户（与 JS 的 host.refresh 等价物是「下次读文件」）
            return res.added to res.merged
        } catch (e: Throwable) {
            // 失败也推进 lastTurn：否则每一轮都会重试，等于每轮多烧一次钱（JS 注释同此）
            runCatching {
                mutex.withLock {
                    val b = bucketOf(key) ?: jobj()
                    b["lastTurn"] = userTurns(conv)
                    b["lastError"] = e.message ?: e.toString()
                    val all = store.load().toMutableMap()
                    all[key] = Js.toJson(b)
                    store.save(JsonObject(all))
                }
            }
            if (manual) throw e
            return null
        } finally {
            synchronized(running) { running.remove(key) }
        }
    }

    private fun cfgOf(settings: AppSettings) = RpMemoryCore.settingsOf(host.config(settings, id))

    /**
     * rpcache 校准出来的 token 系数（`rpState.calib`）。拿不到就按 1.0（未校准），
     * 夹到 0.5–2.5 与 [RpCacheCore.calibrate] 一致——这是「同一个请求里两条预算用同一把尺子」的唯一保证。
     */
    private fun calibOf(conv: Conversation): Double {
        val v = (conv.rpState["calib"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { !it.isString }?.content?.toDoubleOrNull() ?: return 1.0
        return if (v.isNaN() || v <= 0.0) 1.0 else v.coerceIn(0.5, 2.5)
    }

    private fun bucketOf(key: String): JObj? {
        val all = store.load()
        val b = all[key] as? JsonObject ?: return null
        return Js.fromJson(b) as? JObj
    }

    private fun entriesOf(bucket: JObj): List<JObj> =
        (bucket["entries"] as? List<*>)?.mapNotNull { it as? JObj } ?: emptyList()

    /** 与 JS 的 `charKey` 一致：有角色卡按角色卡共享，否则按对话隔离 */
    private fun charKey(conv: Conversation): String =
        RpMemoryCore.charKey(conv.id, conv.personaName?.takeIf { it.isNotEmpty() })

    private fun userTurns(conv: Conversation): Int =
        conv.messages.count { it.role == Role.USER && it.error == null }

    private fun JsonObject.toMutableJObj(): JObj = Js.fromJson(this) as? JObj ?: JObj()

    @Suppress("unused")
    private fun unusedJsonElement(): JsonElement = JsonObject(emptyMap())
}
