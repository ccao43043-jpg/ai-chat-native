package com.aichat.app.engine

import com.aichat.app.domain.model.Message
import com.aichat.app.domain.model.Role
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** D7 分块检索：chunk / pickRelevantChunks / renderChunks + 接线开关（§夜班计划 A3 的 12 例 + 接线补充） */
class ChunkRetrievalTest {

    private fun fileJson(name: String, text: String, pages: Int? = null, truncated: Boolean = false, total: Int? = null): JsonObject {
        val m = LinkedHashMap<String, JsonElement>(
            mapOf("name" to JsonPrimitive(name), "kind" to JsonPrimitive("pdf"), "text" to JsonPrimitive(text),
                "total" to JsonPrimitive((total ?: text.length).toLong()), "truncated" to JsonPrimitive(truncated)))
        pages?.let { m["pages"] = JsonPrimitive(it.toLong()) }
        return JsonObject(m)
    }

    private fun manualChunks(n: Int, make: (Int) -> String): List<ChunkRetrieval.Chunk> =
        (0 until n).map { i -> ChunkRetrieval.Chunk(i, make(i), i * 400, (i + 1) * 400) }

    // 1. chunk 在 \n\n 处断开，断句符留在前块尾部，不切半个句子
    @Test fun chunkBreaksAtParagraph() {
        val para = "甲".repeat(700)
        val text = para + "\n\n" + "乙".repeat(700) + "\n\n" + "丙".repeat(700)
        val cs = ChunkRetrieval.chunk(text)
        assertTrue(cs.size >= 2)
        assertTrue(cs[0].text.endsWith("\n\n"))
        assertEquals(text.indexOf("\n\n") + 2, cs[0].endChar)
        // 每一块都是原文的完整切片（不留半句：切片首尾与原文对应位置一致）
        cs.forEach { c -> assertEquals(text.substring(c.startChar, c.endChar), c.text) }
    }

    // 2. 相邻块重叠 ≥ overlap 的 80%，重叠区内容一致
    @Test fun chunkOverlap() {
        val text = (1..200).joinToString("") { "第${it}句话。" }
        val cs = ChunkRetrieval.chunk(text)
        assertTrue(cs.size >= 2)
        for (i in 0 until cs.size - 1) {
            val ov = cs[i].endChar - cs[i + 1].startChar
            assertTrue("overlap=$ov", ov >= (ChunkRetrieval.CHUNK_OVERLAP * 0.8).toInt())
            assertEquals(cs[i].text.substring(cs[i].text.length - ov), cs[i + 1].text.take(ov))
        }
    }

    // 3. 超长无换行文本强制按 size 切，覆盖连续
    @Test fun chunkHardSplit() {
        val text = "a".repeat(5000)
        val cs = ChunkRetrieval.chunk(text)
        assertTrue(cs.size >= 4)
        assertTrue(cs.dropLast(1).all { it.text.length <= ChunkRetrieval.CHUNK_SIZE })
        assertEquals(0, cs.first().startChar); assertEquals(5000, cs.last().endChar)
        for (i in 0 until cs.size - 1) assertTrue(cs[i + 1].startChar < cs[i].endChar)
    }

    // 4. 短文本只出 1 块
    @Test fun chunkShortText() {
        assertEquals(listOf(ChunkRetrieval.Chunk(0, "短文本", 0, 3)), ChunkRetrieval.chunk("短文本"))
        assertTrue(ChunkRetrieval.chunk("").isEmpty())
    }

    // 5. 命中问题关键词的块被优先保留（预算压力下只有它和第 0 块入选）
    @Test fun pickRanksKeywordChunk() {
        val cs = manualChunks(6) { i -> if (i == 4) "量子隧穿效应使得电子穿越势垒".repeat(50) else "普通段落文字$i".repeat(200) }
        val picked = ChunkRetrieval.pickRelevantChunks(cs, "什么是量子隧穿？", budgetChars = 3000)
        assertEquals(listOf(0, 4), picked.map { it.index })
    }

    // 6. 第 0 块永远在结果里（即使分数最低，预算只够一块时也保它）
    @Test fun pickAlwaysKeepsFirst() {
        val cs = manualChunks(5) { i -> if (i == 0) "标题页目录" else "关键词火箭升空流程$i".repeat(80) }
        val picked = ChunkRetrieval.pickRelevantChunks(cs, "火箭升空", budgetChars = 900)
        assertEquals(2, picked.size)
        assertEquals(0, picked.first().index)
    }

    // 7. 预算不够时按分数顺序丢尾部块，不留半块
    @Test fun pickRespectsBudget() {
        val cs = manualChunks(8) { i -> "内容块$i。".repeat(100) }
        val picked = ChunkRetrieval.pickRelevantChunks(cs, "内容块", budgetChars = 1500)
        assertTrue(picked.size < cs.size)
        assertTrue(picked.sumOf { it.text.length } <= 1500)
        picked.forEach { c -> assertEquals(cs[c.index].text, c.text) }   // 块完整，无半块
    }

    // 8. 空查询 → 返回空，调用方走旧路径
    @Test fun pickEmptyQuery() {
        val cs = manualChunks(3) { "无关正文内容".repeat(50) }
        assertTrue(ChunkRetrieval.pickRelevantChunks(cs, "").isEmpty())
        assertTrue(ChunkRetrieval.pickRelevantChunks(cs, "   ").isEmpty())
        assertEquals(cs.size, ChunkRetrieval.pickRelevantChunks(cs, "无关正文", budgetChars = 100000).size)
    }

    // 9. 全角/英文混合查询都能命中
    @Test fun pickMixedScriptQuery() {
        val cs = manualChunks(4) { i -> when (i) {
            1 -> "The battery capacity is 5000mAh."
            2 -> "电池容量为五千毫安时，续航更久。"
            else -> "无关内容填充无关内容填充无关。"
        } }
        // 预算 70：够 0+1+2（15+16+32=63），不够无关块 3（63+15=78 > 70）——中英文命中的块都在，无关块被丢
        val picked = ChunkRetrieval.pickRelevantChunks(cs, "battery 电池容量", budgetChars = 70)
        assertEquals(listOf(0, 1, 2), picked.map { it.index })
    }

    // 10. renderChunks 序号正确（片段 n/N）且页码范围随 endChar 单调
    @Test fun renderOrdinalAndPages() {
        val cs = listOf(ChunkRetrieval.Chunk(0, "甲", 0, 500), ChunkRetrieval.Chunk(3, "乙", 5000, 6200), ChunkRetrieval.Chunk(7, "丙", 9000, 10000))
        val s = ChunkRetrieval.renderChunks(cs, 8, pages = 10, totalChars = 10000)
        assertTrue(s.contains("【片段 1/8】"))
        assertTrue(s.contains("【片段 4/8】"))
        assertTrue(s.contains("【片段 8/8】"))
        assertTrue(s.contains("第 1 页")); assertTrue(s.contains("第 6-7 页")); assertTrue(s.contains("第 10 页"))
        val ends = Regex("第 (\\d+)(-(\\d+))? 页").findAll(s).map { (it.groupValues[3].ifEmpty { it.groupValues[1] }).toInt() }.toList()
        assertEquals(ends, ends.sorted())
        // 无页数信息时只留序号
        val noPages = ChunkRetrieval.renderChunks(cs, 8, null, 10000)
        assertFalse(noPages.contains("页"))
        assertTrue(noPages.contains("【片段 4/8】"))
    }

    // 11. 干草堆：命中块在文本最后 20% 时仍被选出（D7 回归本体）
    @Test fun haystackNeedleAtEnd() {
        val needle = "关键参数PARAM-Z20=0.9137记录于此。"
        val cs = manualChunks(12) { i -> if (i == 11) needle else "章节${i}的普通叙述内容，与问题无关。".repeat(10) }
        val picked = ChunkRetrieval.pickRelevantChunks(cs, "PARAM-Z20 是多少？", budgetChars = 100000)
        assertTrue(picked.any { it.index == 11 })
        // 预算收紧到只够两块（第0块170 + 命中块25 = 195；再塞任意一块都超 300）——干草堆里的命中块仍入选
        val tight = ChunkRetrieval.pickRelevantChunks(cs, "PARAM-Z20 是多少？", budgetChars = 300)
        assertEquals(listOf(0, 11), tight.map { it.index })
        // 端到端：真长文 → chunk → 命中块落在最后 20%
        val body = "叙述".repeat(600) + "\n\n" + (("." + "叙述".repeat(590) + "\n\n").repeat(9)) + needle + "收尾".repeat(600)
        val chunks = ChunkRetrieval.chunk(body)
        assertTrue(chunks.size >= 10)
        assertTrue(chunks.last().endChar >= body.length * 0.95)
        val endPicked = ChunkRetrieval.pickRelevantChunks(chunks, "PARAM-Z20 的值是多少？", budgetChars = 2900)
        assertTrue(endPicked.any { it.text.contains("PARAM-Z20=0.9137") })
    }

    // 12. 开关关：requestContent 输出与旧实现逐字节相等；开关开 + 空问题 → 降级走旧路径
    @Test fun wiringLegacyByteExact() {
        val f = fileJson("big.pdf", "字".repeat(40000), pages = 30)
        val m = Message(id = "m", role = Role.USER, content = "总结", files = listOf(f))
        val legacy = FileContext.requestContent("user", "总结", listOf(f), null, false, "2026-11-18")
        assertEquals(legacy, FileContext.requestContent(m, false, "2026-11-18", chunk = false))
        // 开关开、问题为空 → 降级
        val emptyQ = FileContext.buildFileContext(listOf(f), "")
        assertEquals(emptyQ, FileContext.requestContent(m.copy(content = ""), false, "2026-11-18", chunk = true))
        // 开关开 + 长文 → 走分块路径，输出不同且带片段标记
        val chunked = FileContext.requestContent(m, false, "2026-11-18", chunk = true)
        assertNotEquals(legacy, chunked)
        assertTrue(chunked.contains("【片段 "))
        assertTrue(chunked.contains("其余内容未包含"))
    }

    // 13. 开关解析 + needsChunking 判定
    @Test fun switchAndNeedsChunking() {
        fun plugins(files: JsonObject): JsonObject = JsonObject(mapOf("files" to files))
        assertTrue(FileContext.chunkEnabled(null))                                  // 缺省开
        assertTrue(FileContext.chunkEnabled(JsonObject(emptyMap())))
        assertTrue(FileContext.chunkEnabled(plugins(JsonObject(emptyMap()))))
        assertTrue(FileContext.chunkEnabled(plugins(JsonObject(mapOf("chunkRetrieval" to JsonPrimitive(true))))))
        assertTrue(FileContext.chunkEnabled(plugins(JsonObject(mapOf("chunkRetrieval" to JsonPrimitive("true"))))))
        assertFalse(FileContext.chunkEnabled(plugins(JsonObject(mapOf("chunkRetrieval" to JsonPrimitive(false))))))  // 显式关

        val small = fileJson("a.txt", "hello")
        val big = fileJson("big.pdf", "字".repeat(40000))
        val cut = fileJson("cut.txt", "字".repeat(30000), truncated = true)
        assertFalse(ChunkRetrieval.needsChunking(listOf(small), "问题"))          // 小文件
        assertTrue(ChunkRetrieval.needsChunking(listOf(big), "问题"))             // 超过单文件预算
        assertTrue(ChunkRetrieval.needsChunking(listOf(cut), "问题"))             // 入库时截断
        assertFalse(ChunkRetrieval.needsChunking(listOf(big), ""))               // 无问题不块选
        assertFalse(ChunkRetrieval.needsChunking(listOf(big), "  "))
    }

    // 14. 分块版 buildFileContext：包装与旧版同构，片段标记、预算受控
    @Test fun chunkedFileContextFormat() {
        val body = (1..50).joinToString("") { "第${it}章。" + "叙述内容".repeat(200) }   // ≈ 51000 字符
        val f = fileJson("doc.pdf", body, pages = 50, total = 90000)
        val out = ChunkRetrieval.buildFileContext(listOf(f), "找标记串", 80000)
        assertTrue(out.startsWith("用户上传了以下文件，请基于文件内容回答："))
        assertTrue(out.contains("【文件：doc.pdf】（50 页，90000 字符）"))
        assertTrue(out.contains("【片段 1/"))
        assertTrue(out.contains("其余内容未包含"))
        assertTrue(out.contains("【文件结束：doc.pdf】"))
        assertTrue(out.contains("用户的问题：找标记串"))
        assertTrue("len=${out.length}", out.length < 33000)
        // 疑问命中靠后的章（干草堆在渲染层仍可命中）
        val tail = ChunkRetrieval.buildFileContext(listOf(f), "第50章", 80000)
        assertTrue(tail.contains("第50章"))
    }

    // 15. 小文件：分块版与旧版逐字节一致（混排时小文件保持旧格式）
    @Test fun smallFileStaysLegacyFormat() {
        val f = fileJson("a.txt", "hello", total = 5)
        assertEquals(FileContext.buildFileContext(listOf(f), "问题"), ChunkRetrieval.buildFileContext(listOf(f), "问题"))
    }
}
