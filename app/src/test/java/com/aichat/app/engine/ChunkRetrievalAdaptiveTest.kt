package com.aichat.app.engine

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 成本守卫（W4/C4）：自适应检索预算的三档 profile 与开关关闭等价性。 */
class ChunkRetrievalAdaptiveTest {

    private fun files(body: String) = listOf(buildJsonObject {
        put("name", JsonPrimitive("d.txt"))
        put("text", JsonPrimitive(body))
    })

    private fun pickedOf(rendered: String): Int =
        Regex("挑选的 (\\d+)/").find(rendered)?.groupValues?.get(1)?.toInt() ?: -1

    @Test fun shortQuestionDropsToSmallProfile() {
        assertEquals(6 to 12_000, ChunkRetrieval.adaptiveBudget("这个参数是什么"))
    }

    @Test fun mediumQuestionUsesMiddleProfile() {
        assertEquals(10 to 24_000, ChunkRetrieval.adaptiveBudget("帮我把这份文档里关于部署流程的章节梳理成要点，并给出你的建议".repeat(3)))
    }

    @Test fun longQuestionOrSummaryHintsKeepFullBudget() {
        assertEquals(12 to 30_000, ChunkRetrieval.adaptiveBudget("a".repeat(600)))
        assertEquals("短问题但含总结意图 → 全量档", 12 to 30_000, ChunkRetrieval.adaptiveBudget("帮我总结这份文档"))
        assertEquals(12 to 30_000, ChunkRetrieval.adaptiveBudget("逐条列出所有差异"))
    }

    @Test fun adaptiveOnReducesChunksForShortQuestion() {
        val body = "参".repeat(40_000)
        val q = "参数"
        val on = pickedOf(ChunkRetrieval.buildFileContext(files(body), q, adaptive = true))
        val off = pickedOf(ChunkRetrieval.buildFileContext(files(body), q))
        assertEquals("短问题自适应档 = 6 块", 6, on)
        assertEquals("固定档 = 12 块", 12, off)
        assertTrue("自适应确实更少", on < off)
    }

    @Test fun adaptiveOffIsIdenticalToDefaultOverload() {
        val body = "字".repeat(40_000)
        val q = "全文讲了什么"
        val fs = files(body)
        assertEquals(ChunkRetrieval.buildFileContext(fs, q), ChunkRetrieval.buildFileContext(fs, q, adaptive = false))
    }
}
