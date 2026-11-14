package com.aichat.app.plugins.rp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 记忆文件的读写小工具（面板与插件共用）。
 *
 * 文件形状（与旧版 `rpmemory.js` 一致）：
 * ```
 * { "conv:<id>": { "entries": [ {id,type,text,keywords,seq,pinned,disabled,…} ], "lastTurn": 3, "lastAt": 0, "lastError": "" } }
 * ```
 * 桶键有角色卡时是 `persona:<名字>`（跨对话共享），否则是 `conv:<会话 id>`。
 */
object RpMemoryOps {

    fun bucketKey(convId: String, personaName: String?): String =
        RpMemoryCore.charKey(convId, personaName?.takeIf { it.isNotEmpty() })

    fun doc(doc: JsonObject, key: String): JsonObject = (doc[key] as? JsonObject) ?: JsonObject(emptyMap())

    fun entries(doc: JsonObject, key: String): List<JsonObject> =
        (doc(doc, key)["entries"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

    /** 按 seq 升序（旧版渲染顺序）；没有 seq 的排最后。 */
    fun sorted(entries: List<JsonObject>): List<JsonObject> = entries.sortedBy { e ->
        (e["seq"] as? JsonPrimitive)?.content?.toIntOrNull() ?: Int.MAX_VALUE
    }

    private fun patchEntry(doc: JsonObject, key: String, id: String, f: (MutableMap<String, JsonElement>) -> Unit): JsonObject {
        val bucket = doc(doc, key)
        val list = (bucket["entries"] as? JsonArray)?.toMutableList() ?: return doc
        var hit = false
        for (i in list.indices) {
            val e = list[i] as? JsonObject ?: continue
            if ((e["id"] as? JsonPrimitive)?.content != id) continue
            val m = LinkedHashMap(e)
            f(m)
            list[i] = JsonObject(m)
            hit = true
            break
        }
        if (!hit) return doc
        val nb = LinkedHashMap(bucket)
        nb["entries"] = JsonArray(list)
        val out = LinkedHashMap(doc)
        out[key] = JsonObject(nb)
        return JsonObject(out)
    }

    fun setPinned(doc: JsonObject, key: String, id: String, pinned: Boolean): JsonObject =
        patchEntry(doc, key, id) { it["pinned"] = JsonPrimitive(pinned) }

    fun setDisabled(doc: JsonObject, key: String, id: String, disabled: Boolean): JsonObject =
        patchEntry(doc, key, id) { it["disabled"] = JsonPrimitive(disabled) }

    fun remove(doc: JsonObject, key: String, id: String): JsonObject {
        val bucket = doc(doc, key)
        val list = (bucket["entries"] as? JsonArray)?.toMutableList() ?: return doc
        val left = list.filterNot { e -> (e as? JsonObject)?.let { (it["id"] as? JsonPrimitive)?.content } == id }
        if (left.size == list.size) return doc
        val nb = LinkedHashMap(bucket)
        nb["entries"] = JsonArray(left)
        val out = LinkedHashMap(doc)
        out[key] = JsonObject(nb)
        return JsonObject(out)
    }

    /** 新增一条（面板「手动添加」用）：seq 取当前最大值 + 1。 */
    fun add(doc: JsonObject, key: String, type: String, text: String, keywords: List<String>, now: Long): JsonObject {
        val bucket = doc(doc, key)
        val list = (bucket["entries"] as? JsonArray)?.toMutableList() ?: mutableListOf()
        val seq = list.mapNotNull { e ->
            (e as? JsonObject)?.let { (it["seq"] as? JsonPrimitive)?.content?.toIntOrNull() }
        }.maxOrNull() ?: 0
        val im = LinkedHashMap<String, JsonElement>()
        im["id"] = JsonPrimitive("m$now")
        im["seq"] = JsonPrimitive(seq + 1)
        im["type"] = JsonPrimitive(type)
        im["text"] = JsonPrimitive(text)
        im["keywords"] = JsonArray(keywords.map { JsonPrimitive(it) })
        im["pinned"] = JsonPrimitive(false)
        im["disabled"] = JsonPrimitive(false)
        im["createdAt"] = JsonPrimitive(now)
        im["updatedAt"] = JsonPrimitive(now)
        im["source"] = JsonPrimitive("manual")
        im["version"] = JsonPrimitive(1)
        im["confidence"] = JsonPrimitive(0.7)
        list.add(JsonObject(im))

        val nb = LinkedHashMap(bucket)
        nb["entries"] = JsonArray(list)
        val out = LinkedHashMap(doc)
        out[key] = JsonObject(nb)
        return JsonObject(out)
    }
}
