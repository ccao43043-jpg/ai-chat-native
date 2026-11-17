package com.aichat.app.ui.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import com.aichat.app.ui.theme.AiChatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 逐字揭示（MarkdownReveal）的行为锁定。
 *
 * 要证明三件事：
 *  1. 文本变长后**不会立刻整段显示**（否则就没有「逐渐出现」）；
 *  2. 每帧都在往前追，最终一定追平（不能永远显示半截）；
 *  3. 内容变短（切消息、版本回退）时直接对齐，不做倒放。
 *
 * 用 `mainClock.autoAdvance = false` 手动推进帧，所以是确定性的，不依赖真机速度。
 */
class MarkdownRevealTest {

    @get:Rule
    val rule = createComposeRule()

    /** 直接取当前渲染出来的字符串（Markdown 段落最终是一个 Text 节点） */
    private fun rendered(): String {
        val nodes = rule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
            .fetchSemanticsNodes()
        return nodes
            .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { t -> t.text } }
            .joinToString("")
    }

    @Test
    fun growsProgressivelyAndCatchesUp() {
        var text by mutableStateOf("开头")
        rule.mainClock.autoAdvance = false
        rule.setContent {
            AiChatTheme {
                MarkdownReveal(text)
            }
        }
        rule.mainClock.advanceTimeByFrame()
        assertEquals("开头", rendered())

        // 一次性到一大段（模拟服务商一次返回几十字）
        val full = "开头" + "这是一个一次性到达的比较长的片段".repeat(2)
        text = full
        rule.mainClock.advanceTimeByFrame()   // 让 LaunchedEffect 起来
        rule.mainClock.advanceTimeByFrame()
        val mid = rendered()
        assertTrue("推进两帧后应该还没显示完，实际长度=${mid.length}", mid.length < full.length)
        assertTrue("已经显示的部分必须是原文前缀", full.startsWith(mid))

        // 继续推进足够多帧 → 必须追平
        rule.mainClock.advanceTimeBy(2_000)
        assertEquals(full, rendered())
    }

    @Test
    fun shrinksImmediatelyWhenTextIsReplaced() {
        var text by mutableStateOf("这是一段已经显示完的比较长的内容")
        rule.mainClock.autoAdvance = false
        rule.setContent {
            AiChatTheme {
                MarkdownReveal(text)
            }
        }
        rule.mainClock.advanceTimeByFrame()
        assertEquals("这是一段已经显示完的比较长的内容", rendered())

        // 切到更短的内容：不允许残留旧尾巴
        text = "短"
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        assertEquals("短", rendered())
    }
}
