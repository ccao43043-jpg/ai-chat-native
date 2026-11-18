package com.aichat.app.data.files

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 附件文本预算的合约测试。
 *
 * 这块以前 0 测试（见 docs/ASSESS-2026-11-17.md §2），而它的失败方式是**静默的**：
 * 超预算就砍尾部，用户只会觉得「AI 没看懂我的文档」。
 * 也正因为这里的 `capText` 是纯前缀截断，才有了 PLAN-ALGO-CACHE P3（分块检索）。
 */
class FileTextTest {

    private fun file(name: String, text: String, truncated: Boolean = false, total: Long = text.length.toLong()) =
        buildJsonObject {
            put("name", JsonPrimitive(name))
            put("text", JsonPrimitive(text))
            put("truncated", JsonPrimitive(truncated))
            put("total", JsonPrimitive(total))
        }

    @Test
    fun shortTextIsNotTruncated() {
        val c = FileText.capText("abc", 10)
        assertEquals("abc", c.text)
        assertFalse(c.truncated)
        assertEquals(3, c.total)
    }

    @Test
    fun longTextIsCutAndFlagged() {
        val c = FileText.capText("abcdefghij", 4)
        assertEquals("abcd", c.text)
        assertTrue(c.truncated)
        assertEquals(10, c.total)
    }

    @Test
    fun emptyAndZeroBudgetAreSafe() {
        assertEquals("", FileText.capText("", 10).text)
        assertEquals("", FileText.capText("abc", 0).text)
        assertTrue(FileText.capText("abc", 0).truncated)
    }

    @Test
    fun formatSizeUsesReadableUnits() {
        assertEquals("1023 B", FileText.formatSize(1023))
        assertEquals("1.0 KB", FileText.formatSize(1024))
        assertEquals("1.5 KB", FileText.formatSize(1536))
        assertEquals("2.0 MB", FileText.formatSize(2L * 1024 * 1024))
    }

    @Test
    fun extOfHandlesDotsSpacesAndCase() {
        assertEquals("pdf", FileText.extOf("a.PDF"))
        assertEquals("docx", FileText.extOf("a.b.docx"))
        assertEquals("", FileText.extOf("noext"))
        assertEquals("", FileText.extOf(null))
    }

    /** 逐文件累加总预算：第一个文件吃掉大半，第二个必须被截断，而不是两个都完整塞进去。 */
    @Test
    fun totalBudgetIsSharedAcrossFiles() {
        val out = FileText.buildFileContext(
            listOf(file("a.txt", "A".repeat(80)), file("b.txt", "B".repeat(80))),
            "问题",
            totalMax = 100,
        )
        assertTrue(out.contains("【文件：a.txt】"))
        assertTrue(out.contains("【文件：b.txt】"))
        assertTrue("第二个文件必须被截断", out.contains("后续内容已截断"))
    }

    /** 空正文的文件整块跳过，不能留一个只有标题的空壳浪费预算。 */
    @Test
    fun filesWithoutTextAreSkippedEntirely() {
        val out = FileText.buildFileContext(listOf(file("empty.txt", "")), "问题", totalMax = 100)
        assertFalse(out.contains("【文件：empty.txt】"))
    }

    /** 预算耗尽后仍要继续遍历：后面的文件不能因为 remain<=0 就整体消失（用户会以为附件没带上）。 */
    @Test
    fun exhaustedBudgetStillMentionsLaterFiles() {
        val out = FileText.buildFileContext(
            listOf(file("a.txt", "A".repeat(100)), file("b.txt", "B".repeat(10))),
            "问题",
            totalMax = 50,
        )
        assertTrue(out.contains("【文件：b.txt】"))
    }

    @Test
    fun decodeTextFallsBackToUtf8WhenNotUtf16() {
        val (text, enc) = FileText.decodeText("你好".toByteArray(Charsets.UTF_8))
        assertEquals("你好", text)
        assertTrue(enc.isNotEmpty())
    }

    @Test
    fun classifyByIdentifiesOfficeKinds() {
        assertEquals("pdf", FileText.classifyFile("x.pdf", null))
        assertEquals("docx", FileText.classifyFile("x.docx", null))
        assertEquals("pptx", FileText.classifyFile("x.pptx", null))
        assertEquals("xlsx", FileText.classifyFile("x.xlsx", null))
        assertEquals("text", FileText.classifyFile("x.md", null))
    }
}