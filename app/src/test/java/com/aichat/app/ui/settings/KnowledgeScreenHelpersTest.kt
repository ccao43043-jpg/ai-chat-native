package com.aichat.app.ui.settings

import com.aichat.app.plugins.knowledge.Knowledge
import org.junit.Assert.assertEquals
import org.junit.Test

/** 「检索体检」的纯函数部分：appendKeyword 把问法追加进检索别名（去重、保序、分隔符对齐 normalizeKeywords）。 */
class KnowledgeScreenHelpersTest {

    @Test
    fun appendsQueryToExistingKeywords() {
        assertEquals("贸易港，闭港，船只进出", Knowledge.appendKeyword("贸易港，闭港", "闭港，船只进出"))
    }

    @Test
    fun dedupesAndPreservesOrder() {
        assertEquals("贸易港，闭港，港口", Knowledge.appendKeyword("贸易港，闭港", "闭港 港口"))
    }

    @Test
    fun emptyExistingYieldsQueryOnly() {
        assertEquals("港口", Knowledge.appendKeyword("", "港口"))
    }

    @Test
    fun trimsAndSkipsEmptyFragments() {
        assertEquals("贸易港，港口", Knowledge.appendKeyword(" 贸易港 ， ", " 港口 "))
    }

    @Test
    fun wholeChineseQuestionStaysOneKeyword() {
        // 中文提问没有空格 → 整句作为一个别名，其 bigram 全部进 keywords 档（权重 3）——
        // 闭港例的桥接机制正是靠这个
        assertEquals("贸易港，港口什么时候不让船只进出", Knowledge.appendKeyword("贸易港", "港口什么时候不让船只进出"))
    }
}
