package com.aichat.app.engine

import com.aichat.app.net.Js
import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.knowledge.RetrievalCore
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.ceil

/**
 * D7 长文档分块检索：段落级滑窗切块 + BM25F 挑选相关片段（复用 knowledge.RetrievalCore，不新写分词），
 * 替代「按前 N 字符截断」的前缀策略——长文档中后段内容由此进入请求。
 * 开关：settings.plugins["files"]["chunkRetrieval"]（缺省关；关时走 FileContext 旧路径，输出逐字节一致）。
 * 开关开启时附件入库上限同步放大到 [SOURCE_MAX]（InputBar），engine 侧靠挑选把请求体压回预算内。
 */
object ChunkRetrieval {
    const val CHUNK_SIZE = 1200
    const val CHUNK_OVERLAP = 150
    const val TOP_K = 12
    const val BUDGET_CHARS = 30000          // 单文件注入预算（与 DEFAULT_FILE_MAX 同量级）
    const val SOURCE_MAX = 120000           // 开关开启时附件入库的抽取上限（默认 30000）

    data class Chunk(val index: Int, val text: String, val startChar: Int, val endChar: Int)

    private val SEP = arrayOf("\n\n", "\n", "。", ".", "！", "!", "？", "?", "；", ";", " ")

    /** 段落级滑窗切块：优先在 \n\n、句号、换行处断开（断句符留在前块尾部）；块间重叠 overlap 字符。 */
    fun chunk(text: String, size: Int = CHUNK_SIZE, overlap: Int = CHUNK_OVERLAP): List<Chunk> {
        if (text.isEmpty()) return emptyList()
        if (text.length <= size) return listOf(Chunk(0, text, 0, text.length))
        val out = ArrayList<Chunk>(); var start = 0; var n = 0
        while (start < text.length) {
            var end = minOf(start + size, text.length)
            if (end < text.length) end = breakAfter(text, start + size / 2, end)
            out += Chunk(n++, text.substring(start, end), start, end)
            if (end >= text.length) break
            start = maxOf(end - overlap, start + 1)
        }
        return out
    }

    /** 在 [minKeep, hardEnd) 里从后往前找断句符，返回断句符之后的位置；找不到按 hardEnd 硬切。 */
    private fun breakAfter(text: String, minKeep: Int, hardEnd: Int): Int {
        for (sep in SEP) {
            val i = text.lastIndexOf(sep, hardEnd - sep.length)
            if (i >= minKeep) return i + sep.length
        }
        return hardEnd
    }

    /** W4（C4）：按问题形态选 (topK, 单文件预算)。只在 `files.adaptiveBudget` 开启时走；默认路径用固定常量。 */
    internal val SUMMARY_HINTS = listOf("总结", "全文", "对比", "所有", "逐条")
    fun adaptiveBudget(question: String): Pair<Int, Int> = when {
        question.length > 512 || SUMMARY_HINTS.any { question.contains(it) } -> TOP_K to BUDGET_CHARS
        question.length < 64 -> 6 to 12_000
        else -> 10 to 24_000
    }

    /** W4（C4）：`files.adaptiveBudget` 开关（默认关 = 固定 12 块/30k）。 */
    fun adaptiveEnabled(plugins: JsonObject?): Boolean =
        (plugins?.get("files") as? JsonObject)?.get("adaptiveBudget")
            ?.let { it as? JsonPrimitive }?.content == "true"

    /**
     * BM25F 打分取 Top-K，预算内贪心：永远保留第 0 块（标题/摘要/目录）；
     * 空查询返回空（调用方降级走旧路径）；预算不够时按分数顺序丢尾部块，不留半块。输出按块序升序。
     */
    fun pickRelevantChunks(chunks: List<Chunk>, question: String, budgetChars: Int = BUDGET_CHARS, topK: Int = TOP_K): List<Chunk> {
        if (chunks.isEmpty() || Js.trim(question).isEmpty()) return emptyList()
        if (chunks.size == 1) return listOf(chunks[0])
        val docs: List<JObj> = chunks.map { c -> linkedMapOf<String, Any?>("id" to ("c" + c.index) as Any?, "text" to c.text as Any?) }
        val idx = RetrievalCore.buildIndex(docs, listOf("text"))
        val first = chunks[0]
        val ranked = chunks.withIndex().drop(1)
            .map { (pos, c) -> c to RetrievalCore.bm25f(idx, question, docs[pos], null) }
            .sortedWith(compareByDescending<Pair<Chunk, Double>> { it.second }.thenBy { it.first.index })
        val picked = ArrayList<Chunk>(); var used = first.text.length
        for ((c, _) in ranked) {
            if (picked.size + 1 >= topK) break        // topK 含第 0 块
            if (used + c.text.length > budgetChars) break
            picked += c; used += c.text.length
        }
        return (listOf(first) + picked).sortedBy { it.index }
    }

    /** 拼成注入文本：【片段 n/N · 第 x-y 页】+ 正文；页码按字符位置均摊（抽取文本没有页边界）。 */
    fun renderChunks(chunks: List<Chunk>, totalChunks: Int, pages: Int?, totalChars: Int): String =
        chunks.joinToString("\n") { c ->
            val p = pageRange(c, pages, totalChars)
            "【片段 " + (c.index + 1) + "/" + totalChunks + "】" + (if (p != null) " · 第 $p 页" else "") + "\n" + c.text
        }

    private fun pageRange(c: Chunk, pages: Int?, totalChars: Int): String? {
        if (pages == null || pages <= 0 || totalChars <= 0) return null
        val per = totalChars.toDouble() / pages
        val s = (c.startChar / per).toInt().coerceIn(0, pages - 1) + 1
        val e = ceil(c.endChar / per).toInt().coerceIn(1, pages)
        return if (s >= e) "$e" else "$s-$e"
    }

    // ---- 接线：files[]（app.js user.files[] 形状）的分块版 buildFileContext ----

    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonObject.b(k: String): Boolean = s(k) == "true"
    private fun JsonObject.i(k: String): Int? = Js.num(this[k])?.toInt()

    /** 该消息是否走分块路径：有非空问题，且存在「入库时被截断」或「超过单文件预算」的文件。 */
    fun needsChunking(files: List<JsonObject>, question: String?): Boolean {
        if (question.isNullOrBlank()) return false
        return files.any { f ->
            Js.truthy(f["text"]) && (f.b("truncated") || (f.s("text")?.length ?: 0) > FileContext.DEFAULT_FILE_MAX)
        }
    }

    /**
     * 分块版 buildFileContext：小文件保持旧格式；长文件以「挑选片段」注入，
     * 并明确告知模型其余内容未包含（否则它以为中间没内容）。包装层与旧版逐字一致。
     */
    fun buildFileContext(files: List<JsonObject>, question: String, totalMax: Int = FileContext.DEFAULT_TOTAL_MAX, adaptive: Boolean = false): String {
        var used = 0
        val (topK, fileBudget) = if (adaptive) adaptiveBudget(question) else TOP_K to BUDGET_CHARS
        val blocks = files.filter { Js.truthy(it["text"]) }.map { f ->
            val name = f.s("name") ?: ""
            val body = f.s("text") ?: ""
            val meta = listOf(if (Js.truthy(f["pages"])) f.s("pages") + " 页" else "",
                if (Js.truthy(f["total"])) f.s("total") + " 字符" else "")
                .filter { it.isNotEmpty() }.joinToString("，")
            val head = "【文件：$name】" + (if (meta.isNotEmpty()) "（$meta）" else "")
            if (!needsChunking(listOf(f), question)) {
                used += body.length
                head + "\n" + body + "\n【文件结束：$name】"
            } else {
                val budget = minOf(fileBudget, maxOf(0, totalMax - used))
                val all = chunk(body)
                val picked = pickRelevantChunks(all, question, budget, topK)
                used += picked.sumOf { it.text.length }
                head + "\n" +
                    "【注意：文件较长，以下为按相关性挑选的 " + picked.size + "/" + all.size + " 个片段，其余内容未包含；片段编号为原文档中的顺序】\n" +
                    renderChunks(picked, all.size, f.i("pages"), body.length) +
                    "\n【文件结束：$name】"
            }
        }
        if (blocks.isEmpty()) return question
        return "用户上传了以下文件，请基于文件内容回答：\n\n" + blocks.joinToString("\n\n") + "\n\n" +
            (if (question.isNotEmpty()) "用户的问题：$question" else "请先概括这些文件的主要内容。")
    }
}
