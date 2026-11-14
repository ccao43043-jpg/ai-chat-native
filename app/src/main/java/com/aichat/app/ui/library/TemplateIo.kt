package com.aichat.app.ui.library

import com.aichat.app.domain.model.PromptTemplate
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.js.JsJson
import com.aichat.app.plugins.js.Undefined
import com.aichat.app.plugins.js.jobj

/**
 * 提示词模板的导入 / 导出，逐行对齐 `www/templates.js` 的 `exportJSON` / `importJSON`。
 *
 * 导出格式：`{"format":"ai-chat-templates","version":1,"templates":[{name,emoji,content,tags,pinned}]}`
 * 导入兼容这份格式或裸模板数组；名与内容都相同的判重跳过；导入的一律变成新的自定义模板（不带 id / builtin）。
 */
object TemplateIo {

    const val FORMAT = "ai-chat-templates"
    const val VERSION = 1

    data class Imported(val list: List<PromptTemplate>, val added: Int, val skipped: Int)

    fun exportJson(list: List<PromptTemplate>): String {
        val arr = list.mapTo(ArrayList<Any?>()) { t ->
            jobj(
                "name" to t.name, "emoji" to t.emoji, "content" to t.content,
                "tags" to t.tags.toCollection(ArrayList<Any?>()), "pinned" to t.pinned,
            )
        }
        return JsJson.stringify(jobj("format" to FORMAT, "version" to VERSION.toDouble(), "templates" to arr), 2)
    }

    fun importJson(text: String, list: List<PromptTemplate>): Imported {
        // 旧版会先剥掉 BOM：Windows 上导出的文件常带
        val raw = text.removePrefix("\uFEFF")
        val parsed = runCatching { Js.parse(raw) }.getOrNull()
            ?: throw IllegalArgumentException("不是有效的 JSON")
        val arr: List<*> = when {
            parsed is List<*> -> parsed
            parsed is Map<*, *> && parsed["templates"] is List<*> -> parsed["templates"] as List<*>
            else -> throw IllegalArgumentException("没有找到模板列表（需要 {\"templates\": [...]} 或数组）")
        }
        val out = list.toMutableList()
        var added = 0
        var skipped = 0
        for (x in arr) {
            if (x !is Map<*, *>) { skipped++; continue }
            val content = str(x["content"]).ifEmpty { str(x["systemPrompt"]) }.ifEmpty { str(x["prompt"]) }
            if (content.isBlank()) { skipped++; continue }
            val n = normalizeTemplate(
                PromptTemplate(
                    id = newId("t-"),
                    name = str(x["name"]).ifEmpty { str(x["title"]) },
                    emoji = str(x["emoji"]),
                    content = content,
                    tags = tags(x["tags"]),
                    pinned = x["pinned"] == true,
                    builtin = false,
                    skill = false,
                ),
            )
            if (out.any { it.name == n.name && it.content == n.content }) { skipped++; continue }
            out += n
            added++
        }
        return Imported(out, added, skipped)
    }

    /** JS 的 `String(v || '')`：`Undefined`/null 都算空，别让 "undefined" 漏进来 */
    private fun str(v: Any?): String = when (v) {
        null, Undefined -> ""
        is String -> v
        else -> if (v.toString() == "undefined") "" else v.toString()
    }

    private fun tags(v: Any?): List<String> = when (v) {
        is List<*> -> v.mapNotNull { str(it).takeIf { s -> s.isNotBlank() } }
        is String -> v.split(Regex("[,，、\\s]+")).filter { it.isNotBlank() }
        else -> emptyList()
    }
}
