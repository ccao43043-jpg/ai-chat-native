package com.aichat.app.domain

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * 请求的五个槽位（PLAN-CONTEXT-ARCH §3.1）。
 *
 * 目的不是「分类好看」，而是把「谁拥有请求的哪一段」变成**可检查的契约**：
 * 同一槽位只许一个所有者，第二个来抢时由 [com.aichat.app.plugins.SlotRegistry] 报出来。
 * 历史教训：世界书触发曾整条链路静默失效一个版本，就是因为没有任何机制能发现
 * 「另一个插件把这行覆盖掉了」。
 */
enum class Slot(val code: String, val label: String, val hint: String) {
    /** system 前段：模板 / 角色卡 / 世界七块 / 常驻世界书 —— 几乎不变，是缓存前缀 */
    S1_STABLE("s1", "稳定前缀", "system 前段：模板、角色卡、世界设定、常驻世界书"),
    /** system 中段：章节摘要 —— 低频追加 */
    S2_DELTA("s2", "低频增量", "system 中段：章节摘要"),
    /** 末条 user 尾部：资料库 / 世界书触发 / 记忆召回 —— 每轮变，但不碰前缀 */
    S3_RECALL("s3", "每轮召回", "末条 user 尾部：资料库、世界书触发、记忆召回"),
    /** 末条 user 首行：心情 / 关系 */
    S4_TAG("s4", "状态行", "末条 user 首行：心情与关系"),
    /** 请求体协议层：cache_control / 断点 / session */
    S5_BODY("s5", "协议标记", "请求体：缓存标记与断点");

    companion object {
        fun of(code: String?): Slot? = entries.firstOrNull { it.code == code }
    }
}

/**
 * 一次请求里「某一段内容」的候选片段。
 *
 * M4 起插件不再自己改 `ctx.messages`，而是交片段：谁交的（[src]）、交给哪一段（[slot]）、
 * 优先级多少（[priority]，世界书 3 > 资料库 2 > 记忆 1）。由管线统一排序/去重/预算/渲染。
 */
data class Fragment(
    val slot: Slot,
    val src: String,
    val text: String,
    val tokens: Int = 0,
    val priority: Int = 0,
)

/** 本轮收集到的片段（挂在 RequestContext 上，随 copy 一起传递） */
class TurnFragments {
    private val items = ArrayList<Fragment>()

    fun add(f: Fragment): TurnFragments {
        if (f.text.isNotBlank()) items += f
        return this
    }

    fun add(slot: Slot, src: String, text: String, tokens: Int = 0, priority: Int = 0): TurnFragments =
        add(Fragment(slot, src, text, tokens, priority))

    fun all(): List<Fragment> = items.toList()
    fun of(slot: Slot): List<Fragment> = items.filter { it.slot == slot }
    fun isEmpty(): Boolean = items.isEmpty()
    fun clear() = items.clear()
}

/**
 * 一轮请求的分段账本：谁动了 system、谁动了末条 user、各多少 token。
 *
 * 由 `PluginRegistryImpl.runBeforeRequest` **自动**测量（比较每个插件调用前后的
 * `systemPrompt` 与末条 user 长度），插件不需要自己上报 —— 少一次「忘了打点」的机会。
 */
class ContextTrace {
    data class Step(
        val plugin: String,
        val slots: List<Slot>,
        val systemDelta: Int,
        val userDelta: Int,
        val note: String = "",
    )

    private val steps = ArrayList<Step>()
    private val conflicts = ArrayList<String>()
    private val errors = ArrayList<String>()
    private val notes = ArrayList<String>()

    var systemTokens: Int = 0
    var lastUserTokens: Int = 0
    /** sha256(systemPrompt) 前 16 位：只改召回时这个值必须不变（见 PLAN-CONTEXT-ARCH §5.5） */
    var stableHash: String = ""
    var recallTokens: Int = 0
    var tagTokens: Int = 0

    fun record(step: Step) {
        steps += step
    }

    fun conflict(text: String) {
        conflicts += text
    }

    /**
     * 插件抛异常。
     *
     * 以前这类失败只进 registry 的 errors 列表（顶栏红点，跨会话汇总），
     * 体检面板看不到「这一轮具体哪个插件炸了、炸在哪一步」。
     */
    fun error(plugin: String, hook: String, message: String) {
        errors += "$plugin · $hook · $message"
    }

    /** 装配阶段的说明（预算不足跳过召回、跨来源重复被合并……），给人看的，不参与请求 */
    fun note(text: String) {
        notes += text
    }

    fun steps(): List<Step> = steps.toList()
    fun conflicts(): List<String> = conflicts.toList()
    fun errors(): List<String> = errors.toList()
    fun notes(): List<String> = notes.toList()

    /** 哪些插件声称拥有这段（用于面板「谁改的」一栏） */
    fun ownersOf(slot: Slot): List<String> =
        steps.filter { slot in it.slots }.map { it.plugin }.distinct()
}
/**
 * 召回统一装配的开关（PLAN-CONTEXT-ARCH M4）。
 *
 * 默认 **关**：新旧两条路并存，先把新的跑一轮对比，确认「只改召回时 system 逐字节不变」
 * 再默认打开。开关位置 `settings.plugins.recall.unified`。
 */
fun recallUnified(settings: com.aichat.app.domain.model.AppSettings): Boolean {
    val o = settings.plugins["recall"] as? JsonObject ?: return false
    val v = o["unified"] as? JsonPrimitive ?: return false
    return v.booleanOrNull ?: false
}
