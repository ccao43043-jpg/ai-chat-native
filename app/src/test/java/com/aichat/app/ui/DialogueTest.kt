package com.aichat.app.ui

import com.aichat.app.ui.common.Dialogue
import com.aichat.app.ui.common.DlgPart
import com.aichat.app.ui.theme.BubbleTreatment
import com.aichat.app.ui.theme.Speakers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogueTest {

    private fun parts(t: String, def: String? = null) = Dialogue.parts(t, def)

    @Test fun speechActionNarrationSplit() {
        // 文本顺序就是演讲顺序：先有动作描写，才有那句台词
        val p = parts("林墨：*把热茶推过去* 「先喝一口。我听着。」窗外的雨还在下。", "林墨")
        assertEquals(DlgPart.Action("把热茶推过去"), p[0])
        assertEquals(DlgPart.Speech("林墨", "先喝一口。我听着。"), p[1])
        assertEquals(DlgPart.Narration("窗外的雨还在下。"), p[2])
    }

    @Test fun speakerInBracketsIsBare() {
        // 「名字：」前缀只是标签，不该出现在台词里；说话人取名字本身
        val p = parts("【林墨】：*轻轻点头*「嗯。」")
        assertEquals(DlgPart.Action("轻轻点头"), p[0])
        assertEquals(DlgPart.Speech("林墨", "嗯。"), p[1])
    }

    @Test fun bareQuoteFallsBackToDefaultSpeaker() {
        // 单角色 RP 常见形态：没有任何「名字：」前缀
        val p = parts("「你终于来了。」*她转过身。*", "苏晚")
        assertEquals(DlgPart.Speech("苏晚", "你终于来了。"), p[0])
        assertEquals(DlgPart.Action("她转过身。"), p[1])
    }

    @Test fun unclosedQuoteDuringStreamingStillRenders() {
        // 打字过程中右引号还没到：必须已经把内容当台词渲染，不能退回旁白
        val p = parts("「我还没说完")
        assertEquals(1, p.size)
        assertEquals(DlgPart.Speech(null, "我还没说完"), p[0])
    }

    @Test fun boldIsNotAnAction() {
        val p = parts("这是 **很重要** 的一句话。")
        assertTrue(p.none { it is DlgPart.Action })
        assertEquals(DlgPart.Narration("这是 **很重要** 的一句话。"), p[0])
    }

    @Test fun bulletAsteriskIsNotAnAction() {
        val p = parts("开头\n* 第一项\n* 第二项")
        assertTrue(p.none { it is DlgPart.Action })
    }

    @Test fun innerMonologue() {
        val p = parts("（他好像不太高兴。）「怎么了？」", "苏晚")
        assertEquals(DlgPart.Inner("他好像不太高兴。", "苏晚"), p[0])
        assertEquals(DlgPart.Speech("苏晚", "怎么了？"), p[1])
    }

    @Test fun innerMonologueTakesCurrentSpeaker() {
        // 内心独白必须跟着当前说话人上色，不能掉回会话默认角色 ——
        // 否则「苏晚在想」会被画成林墨的颜色
        val p = parts("苏晚：（他今天不太对劲。）「我不冷。」", "林墨")
        assertEquals(DlgPart.Inner("他今天不太对劲。", "苏晚"), p[0])
        assertEquals(DlgPart.Speech("苏晚", "我不冷。"), p[1])
    }

    @Test fun speakerContinuesOnFollowingLines() {
        // 小说排版：同一个人的后续台词不再重复写名字
        val p = parts("林墨：「早。」\n「今天雨很大。」", "苏晚")
        assertEquals(DlgPart.Speech("林墨", "早。"), p[0])
        assertEquals(DlgPart.Speech("林墨", "今天雨很大。"), p[1])
    }

    @Test fun oocParenthesisStaysNarration() {
        // OOC 场外音不是角色内心，不该被染成角色的颜色
        val p = parts("（OOC：这条是出戏说明）继续。")
        assertTrue(p.none { it is DlgPart.Inner })
    }

    @Test fun labelLikePrefixIsNotASpeaker() {
        // 「注意：」「结论：」不是人名；不带引号时也不该被吞成台词
        val p = parts("注意：这条消息没有引号。\n结论：保持原样。")
        assertTrue(p.none { it is DlgPart.Speech })
    }

    @Test fun knownSpeakerWithoutQuotesIsSpeech() {
        // 名字在本篇带引号说过话之后，无引号的那句也算它说的
        val p = parts("林墨：「早。」\n林墨：今天雨很大。")
        assertEquals(DlgPart.Speech("林墨", "早。"), p[0])
        assertEquals(DlgPart.Speech("林墨", "今天雨很大。"), p[1])
    }

    @Test fun codeBlockStaysMarkdown() {
        val p = parts("「看这里。」\n\n```kotlin\nval a = 1\n```\n", "林墨")
        assertEquals(DlgPart.Speech("林墨", "看这里。"), p[0])
        assertTrue(p.last() is DlgPart.Block)
    }

    @Test fun escapingAsterisk() {
        val p = parts("""乘号是 \* 而不是动作。""")
        assertTrue(p.none { it is DlgPart.Action })
    }

    // ---- 闸门：非 RP 内容不能被分层 ----

    @Test fun gateRejectsTechnicalAnswer() {
        assertFalse(Dialogue.isDialogue("```kotlin\nval a = 1\n```\n这段代码会打印 1。"))
        assertFalse(Dialogue.isDialogue("# 标题\n\n正文内容。"))
        assertFalse(Dialogue.isDialogue("今天天气不错，我们出去走走吧，顺便买点东西回来。"))
    }

    @Test fun gateAcceptsRpProse() {
        assertTrue(Dialogue.isDialogue("林墨：*把热茶推过去* 「先喝一口。我听着。」窗外的雨还在下。"))
        assertTrue(Dialogue.isDialogue("「你终于来了。」她转过身，眼里带着笑意。"))
    }

    @Test fun gateRejectsEnglishChat() {
        assertFalse(Dialogue.isDialogue("Sure, here is the plan: * step one * step two"))
    }

    @Test fun gateRejectsQuotedTermsInTechnicalAnswer() {
        // 技术回复里到处是引号，但它们不是台词：整篇必须继续走 Markdown
        assertFalse(Dialogue.isDialogue("这里的所谓「幂等」是指同一个请求执行多次，结果都一致。"))
        assertFalse(Dialogue.isDialogue("注意：「配置」一项需要在启动时读取，否则会报错。"))
    }

    @Test fun gateAcceptsScriptStyleLine() {
        // 剧本式排版：行首就是引号
        assertTrue(Dialogue.isDialogue("「你终于来了。」她转过身，眼里带着笑意。看来等了很久。"))
    }

    // ---- 说话人配色：跨消息稳定 ----

    @Test fun skinIsStableAcrossCalls() {
        assertEquals(Speakers.skin("林墨"), Speakers.skin("林墨"))
        // 大小写与首尾空格不影响取色：模型经常前后不一致
        assertEquals(Speakers.skin("林墨"), Speakers.skin(" 林墨 "))
        assertEquals(Speakers.skin("Alice"), Speakers.skin("alice"))
    }

    @Test fun differentSpeakersUsuallyDiffer() {
        // 8 个色相互相区分；这里只要求「不是同一个色」，不锁定具体色相
        val a = Speakers.skin("林墨")
        val b = Speakers.skin("苏晚")
        assertTrue(a.color != b.color || a.treatment != b.treatment)
    }

    @Test fun nullSpeakerGetsDefaultSkin() {
        assertNotNull(Speakers.skin(null))
    }
}