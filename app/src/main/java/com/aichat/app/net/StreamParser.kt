package com.aichat.app.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** providers.js extractDeltas 的输出：{kind:'text'|'reasoning'|'error'|'usage'|'finish', …} */
sealed interface StreamDelta {
    data class Text(val text: String) : StreamDelta
    data class Reasoning(val text: String) : StreamDelta
    data class Error(val text: String) : StreamDelta
    /** usage = normalizeUsage 结果（golden 比这个）；raw = 服务商原始 usage 对象（Usage.raw，契约 #1） */
    data class UsageDelta(val usage: JsonObject, val raw: JsonObject? = null) : StreamDelta
    data class Finish(val reason: String) : StreamDelta

    /** 与 JS 对象同形（golden 比对用） */
    fun toJson(): JsonObject = when (this) {
        is Text -> buildJsonObject { put("kind", "text"); put("text", text) }
        is Reasoning -> buildJsonObject { put("kind", "reasoning"); put("text", text) }
        is Error -> buildJsonObject { put("kind", "error"); put("text", text) }
        is UsageDelta -> buildJsonObject { put("kind", "usage"); put("usage", usage) }
        is Finish -> buildJsonObject { put("kind", "finish"); put("reason", reason) }
    }
    /** app.js apply()：text / reasoning / error 计为「已收到内容」（gotDelta），usage / finish 不计 */
    val countsAsContent: Boolean get() = this is Text || this is Reasoning || this is Error
}

/**
 * 结束原因（finish reason）的统一口径。
 *
 * 旧 JS 版有两个洞（`providers.js:617-628` / `:690`）：
 * ① gemini 分支根本不读 `candidates[0].finishReason`，于是 Gemini 永远没有结束原因 → 引擎判不出
 *    `truncated` → 界面既不出「已中断」也不给「继续生成」，用户被截断后无路可走；
 * ② 各家叫法不一（`MAX_TOKENS` / `length` / `STOP` / `end_turn` / `eos`），引擎只认 `length`。
 * 这里做归一 + 提供「什么算正常结束」，是**有意偏离旧版**（见 docs/NATIVE_REWRITE_SPEC.md §3.5）。
 */
object FinishReasons {
    /** 客户端补发：上游既没给 finish_reason 也没有 [DONE]，直接把连接关了 */
    const val INCOMPLETE = "incomplete"

    /** 正常结束。其余一律视为「没写完」（截断） */
    private val NORMAL = setOf("stop", "end_turn", "eos", "stop_sequence", "tool_calls", "function_call")

    fun isNormal(reason: String?): Boolean {
        val r = reason?.trim()?.lowercase() ?: return true
        return r.isEmpty() || r in NORMAL
    }

    /** 各家 → 统一口径：MAX_TOKENS/length/max_output_tokens → length；STOP/END_TURN/eos → stop；其余小写透传 */
    fun normalize(raw: String?): String? {
        val r = raw?.trim()?.lowercase() ?: return null
        if (r.isEmpty()) return null
        return when (r) {
            "max_tokens", "length", "max_output_tokens", "model_context_window_exceeded" -> "length"
            "stop", "end_turn", "eos", "stop_sequence" -> "stop"
            else -> r
        }
    }
}

object Deltas {
    private fun o(e: JsonElement?) = e as? JsonObject
    private fun s(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun errText(err: JsonElement): String {
        val m = o(err)?.get("message")
        return if (Js.truthy(m)) Js.str(m) else Js.stringify(err)
    }

    fun extract(type: String, obj: JsonElement?): List<StreamDelta> {
        val out = mutableListOf<StreamDelta>()
        if (!Js.truthy(obj)) return out
        val ob = obj as? JsonObject ?: JsonObject(emptyMap())
        when (type) {
            "anthropic" -> {
                val t = s(ob["type"])
                val au = if (t == "message_start" && Js.truthy(ob["message"])) o(ob["message"])?.get("usage") else ob["usage"]
                UsageCodec.normalizeUsage("anthropic", au)?.let { out += StreamDelta.UsageDelta(it, au as? JsonObject) }
                val delta = o(ob["delta"])
                if (t == "content_block_delta" && Js.truthy(ob["delta"])) {
                    when (s(delta?.get("type"))) {
                        "text_delta" -> out += StreamDelta.Text(textOr(delta?.get("text")))
                        "thinking_delta" -> out += StreamDelta.Reasoning(textOr(delta?.get("thinking")))
                    }
                } else if (t == "message_delta" && Js.truthy(delta?.get("stop_reason"))) {
                    // 有意偏离旧版：Anthropic 把结束原因放在 message_delta.delta.stop_reason，
                    // 旧版不读 → max_tokens 截断永远看不出来。归一后交给引擎判 truncated。
                    FinishReasons.normalize(s(delta?.get("stop_reason")))?.let { out += StreamDelta.Finish(it) }
                } else if (t == "error") {
                    val m = o(ob["error"])?.get("message")
                    out += StreamDelta.Error(if (Js.truthy(ob["error"]) && Js.truthy(m)) Js.str(m) else Js.stringify(ob))
                } else if (ob["content"] is JsonArray) {
                    (ob["content"] as JsonArray).forEach { c ->
                        val co = o(c)
                        when (s(co?.get("type"))) {
                            "text" -> out += StreamDelta.Text(Js.str(co?.get("text")))
                            "thinking" -> out += StreamDelta.Reasoning(Js.str(co?.get("thinking")))
                        }
                    }
                }
            }
            "gemini" -> {
                if (Js.truthy(ob["error"])) { out += StreamDelta.Error(errText(ob["error"]!!)); return out }
                UsageCodec.normalizeUsage("gemini", ob["usageMetadata"])?.let { out += StreamDelta.UsageDelta(it, ob["usageMetadata"] as? JsonObject) }
                val c0 = o((ob["candidates"] as? JsonArray)?.firstOrNull())
                val parts = o(c0?.get("content"))?.get("parts")
                if (Js.truthy(c0?.get("content")) && Js.truthy(parts)) (parts as? JsonArray)?.forEach { p ->
                    val po = o(p); val txt = s(po?.get("text"))
                    if (txt != null) out += if (Js.truthy(po?.get("thought"))) StreamDelta.Reasoning(txt) else StreamDelta.Text(txt)
                }
                // 有意偏离旧版（providers.js:617-628 不读 finishReason）：不读它，Gemini 的 MAX_TOKENS
                // 就永远传不到引擎，界面既不出「已中断」也不给「继续生成」。
                if (Js.truthy(c0?.get("finishReason"))) FinishReasons.normalize(Js.str(c0?.get("finishReason")))?.let { out += StreamDelta.Finish(it) }
            }
            else -> {
                if (Js.truthy(ob["error"])) { out += StreamDelta.Error(errText(ob["error"]!!)); return out }
                val ch = o((ob["choices"] as? JsonArray)?.firstOrNull())
                // OpenRouter 流中途报错时把 error 嵌在 choices[0] 里（顶层没有 error）：
                // 旧版只认顶层，于是错误被吞掉、只剩一个 Finish("error")，用户看到「莫名其妙断在半句」。
                if (Js.truthy(ch?.get("error"))) { out += StreamDelta.Error(errText(ch!!["error"]!!)); return out }
                if (ch != null) {
                    val d = o(if (Js.truthy(ch["delta"])) ch["delta"] else if (Js.truthy(ch["message"])) ch["message"] else null) ?: JsonObject(emptyMap())
                    val r = if (Js.truthy(d["reasoning_content"])) d["reasoning_content"] else d["reasoning"]
                    s(r)?.takeIf { it.isNotEmpty() }?.let { out += StreamDelta.Reasoning(it) }
                    s(d["content"])?.takeIf { it.isNotEmpty() }?.let { out += StreamDelta.Text(it) }
                    if (Js.truthy(ch["finish_reason"])) FinishReasons.normalize(Js.str(ch["finish_reason"]))?.let { out += StreamDelta.Finish(it) }
                }
                UsageCodec.normalizeUsage("openai", ob["usage"])?.let { out += StreamDelta.UsageDelta(it, ob["usage"] as? JsonObject) }
            }
        }
        return out
    }
    /** JS: obj.delta.text || '' */
    private fun textOr(e: JsonElement?) = if (Js.truthy(e)) Js.str(e) else ""

    /** 非流式完整响应（也用于「请求了流式但服务商回了整包 JSON」的兜底） */
    fun parseFullResponse(type: String, text: String): List<StreamDelta> {
        val obj = try { Js.parse(text) } catch (e: Exception) {
            return listOf(StreamDelta.Error("无法解析响应：" + text.take(300)))
        }
        if (obj is JsonArray) return obj.flatMap { extract(type, it) }
        return extract(type, obj)
    }

    private val HINTS = mapOf(
        401 to "API Key 无效或未填写", 403 to "无权限访问（检查 Key/地区/账户）", 404 to "地址或模型名不存在",
        429 to "请求过于频繁或额度不足", 400 to "请求参数有误",
        402 to "额度不足或 max_tokens 过大（请在设置里调小「最大输出 tokens」）",
    )
    /** HTTP 错误 → 可读信息（parseError） */
    fun parseError(status: Int, text: String?): String {
        var msg = ""
        try {
            val obj = Js.parse(text ?: "")
            val oo = obj as? JsonObject
            val e: JsonElement? = if (obj is JsonArray) { val f = obj.firstOrNull(); if (Js.truthy(f)) o(f)?.get("error") else f } else oo?.get("error")
            if (obj is kotlinx.serialization.json.JsonNull) throw IllegalStateException()
            val fromE: JsonElement? = if (Js.truthy(e)) {
                val m = o(e)?.get("message")
                if (Js.truthy(m)) m else if (e is JsonPrimitive && e.isString) e else JsonPrimitive("")
            } else e
            msg = when {
                Js.truthy(fromE) -> Js.str(fromE)
                Js.truthy(oo?.get("message")) -> Js.str(oo!!["message"])
                Js.truthy(oo?.get("msg")) -> Js.str(oo!!["msg"])
                else -> ""
            }
        } catch (e: Exception) { /* 非 JSON */ }
        if (msg.isEmpty()) msg = (text ?: "").take(500)
        val hint = HINTS[status]
        return "HTTP " + status + (if (hint != null) "（$hint）" else "") + (if (msg.isNotEmpty()) "：$msg" else "")
    }
}

/**
 * createStreamParser(type)：喂任意切分的文本块，按 SSE 规则拆事件 → JSON.parse → extractDeltas。
 * 规则同 JS：\n 分行、去尾部 \r、空行结束事件、':' 开头为注释、只认 data:（去掉 1 个前导空格），event:/id:/retry: 忽略；
 * 多行 data 以 \n 连接；"[DONE]" 置 done（之后的数据仍会处理，与 JS 一致）；非 JSON 忽略。
 */
class StreamParser(private val type: String) {
    private val buf = StringBuilder()
    private val dataLines = mutableListOf<String>()
    var done = false
        private set

    /**
     * 这轮流是否**明确宣告过结束**（[DONE]、finish_reason、Anthropic message_stop 任一）。
     * 客户端靠它区分「正常收尾但服务商不发结束原因」和「上游半路把连接掐了」。
     */
    var terminal = false
        private set

    private fun flushEvent(out: MutableList<StreamDelta>) {
        if (dataLines.isEmpty()) return
        val data = dataLines.joinToString("\n"); dataLines.clear()
        if (Js.trim(data) == "[DONE]") { done = true; terminal = true; return }
        val obj = try { Js.parse(data) } catch (e: Exception) { return }
        if (type == "anthropic" && (obj as? JsonObject)?.let { (it["type"] as? JsonPrimitive)?.content } == "message_stop") terminal = true
        out += Deltas.extract(type, obj)
        if (out.any { it is StreamDelta.Finish }) terminal = true
    }
    private fun processLine(raw: String, out: MutableList<StreamDelta>) {
        val line = if (raw.endsWith("\r")) raw.dropLast(1) else raw
        if (line.isEmpty()) { flushEvent(out); return }
        if (line.startsWith(":")) return
        if (line.startsWith("data:")) dataLines += line.substring(5).let { if (it.startsWith(" ")) it.substring(1) else it }
    }
    fun push(chunk: String): List<StreamDelta> {
        val out = mutableListOf<StreamDelta>()
        buf.append(chunk)
        var start = 0
        while (true) {
            val idx = buf.indexOf("\n", start); if (idx < 0) break
            processLine(buf.substring(start, idx), out); start = idx + 1
        }
        if (start > 0) buf.delete(0, start)
        return out
    }
    fun end(): List<StreamDelta> {
        val out = mutableListOf<StreamDelta>()
        if (buf.isNotEmpty()) { processLine(buf.toString(), out); buf.setLength(0) }
        flushEvent(out)
        return out
    }
}
