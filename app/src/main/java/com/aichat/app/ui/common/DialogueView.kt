package com.aichat.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aichat.app.ui.theme.BubbleTreatment
import com.aichat.app.ui.theme.Palette
import com.aichat.app.ui.theme.Speakers

/*
 * 对话式渲染。
 *
 * 三种声音用三套排版区分，靠的不是"再加几个颜色"，而是字体族/字重/斜体/字号/装饰
 * 五个维度一起拉开：
 *
 *   台词（Speech）  无衬线 16sp Medium 亮白 + 该角色的彩色气泡 + 彩色「」
 *                   —— 最重要、最亮、被框起来，扫一眼就知道是谁在说话
 *   动作（Action）  衬线 14sp 斜体 次要灰 + 左侧 2dp 竖线
 *                   —— 斜体和灰把"这是描写不是话"说清楚，竖线让它自成一段
 *   内心（Inner）   衬线 14sp 斜体 + 角色色 + 括号
 *                   —— 和动作同族但上了角色的颜色：是这个人心里想的
 *   旁白（Narration）衬线 15sp 常规 次要灰 + 字距放宽
 *                   —— 最"书"的一种，读起来就是小说正文
 *
 * 为什么台词用无衬线、旁白用衬线：手机中文字体里衬线（宋/明）天生带"书面叙述"气质，
 * 而无衬线（黑体）更接近"有人在跟我说话"。这个反差在中文排版里很稳，
 * 即使设备没有中文衬线字体回退成黑体，斜体/字号/颜色/装饰仍然把它区分开。
 */

@Composable
fun DialogueBody(
    text: String,
    defaultSpeaker: String? = null,
    modifier: Modifier = Modifier,
    onCopy: (String) -> Unit = {},
) {
    // 解析异常（极端输入）时退回整段旁白，不让一条消息拖垮列表
    val parts = remember(text, defaultSpeaker) {
        runCatching { Dialogue.parts(text, defaultSpeaker) }
            .getOrElse { listOf(DlgPart.Narration(text)) }
    }
    val fallback = defaultSpeaker.orEmpty()
    // 连续同一个角色说话时不重复标名字（中间插了动作/旁白也一样）：
    // 一段对话里名字刷屏比不标更烦
    var lastSpeech: String? = null
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEach { p ->
            val who = when (p) {
                is DlgPart.Speech -> (p.speaker ?: fallback).trim()
                is DlgPart.Inner -> (p.speaker ?: fallback).trim()
                else -> null
            }
            val showName = who != null && who != lastSpeech
            if (who != null) lastSpeech = who
            DlgPartView(p, fallback, showName, onCopy)
        }
    }
}

/**
 * 流式揭示：与 MarkdownReveal 完全相同的追赶策略（每帧补差额的 1/5，至少 1 字），
 * 只是把渲染目标换成对话分层。
 */
@Composable
fun DialogueReveal(
    text: String,
    defaultSpeaker: String? = null,
    modifier: Modifier = Modifier,
    onCopy: (String) -> Unit = {},
) {
    var shown by remember { mutableIntStateOf(text.length) }
    LaunchedEffect(text) {
        val target = text.length
        if (shown > target) shown = target      // 切消息 / 版本回退：直接对齐，不做倒放
        while (shown < target) {
            withFrameNanos { }
            val gap = target - shown
            shown = (shown + ((gap + 4) / 5).coerceAtLeast(1)).coerceAtMost(target)
        }
    }
    DialogueBody(if (shown >= text.length) text else text.substring(0, shown), defaultSpeaker, modifier, onCopy)
}

@Composable
private fun DlgPartView(p: DlgPart, fallback: String, showName: Boolean, onCopy: (String) -> Unit) {
    val base = MaterialTheme.typography.bodyLarge
    when (p) {
        is DlgPart.Block -> MarkdownBlock(p.block, onCopy)
        is DlgPart.Speech -> SpeechBubble(p.speaker ?: fallback, p.text, base, showName)
        is DlgPart.Action -> ActionLine(p.text, base)
        is DlgPart.Inner -> InnerLine(p.text, p.speaker ?: fallback, base)
        is DlgPart.Narration -> {
            val codeBg = MaterialTheme.colorScheme.surfaceVariant
            val linkColor = MaterialTheme.colorScheme.primary
            val annotated = remember(p.text, codeBg, linkColor) { toAnnotated(Markdown.inline(p.text), codeBg, linkColor) }
            Text(
                annotated,
                style = base.copy(fontFamily = FontFamily.Serif, fontSize = 15.sp, lineHeight = 26.sp, letterSpacing = 0.2.sp),
                color = Palette.Fg2,
            )
        }
    }
}

@Composable
private fun SpeechBubble(speaker: String, text: String, base: TextStyle, showName: Boolean) {
    val skin = remember(speaker) { Speakers.skin(speaker) }
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    val spoken = remember(text, skin.color, codeBg, linkColor) { quoted(text, skin.color, codeBg, linkColor) }
    val shape = speechShape(skin.treatment)
    val name = speaker.trim().ifEmpty { null }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (name != null && showName) Text(
            name,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            letterSpacing = 0.6.sp,
            fontWeight = FontWeight.Medium,
            color = skin.color,
            modifier = Modifier.padding(start = 14.dp),
        )
        Box(
            Modifier
                .clip(shape)
                .then(if (skin.treatment == BubbleTreatment.Gradient)
                    Modifier.background(Brush.horizontalGradient(listOf(skin.color.copy(alpha = 0.26f), skin.color.copy(alpha = 0.05f))), shape)
                else Modifier.background(Speakers.tint(skin), shape))
                .then(when (skin.treatment) {
                    BubbleTreatment.Outline -> Modifier.border(1.dp, skin.color.copy(alpha = 0.5f), shape)
                    BubbleTreatment.Quiet -> Modifier
                    else -> Modifier.border(1.dp, skin.color.copy(alpha = 0.26f), shape)
                })
                // Rail 花样的左侧竖条：画在底色之上、文字之下
                // （Modifier 链里 drawBehind 排在 background 之后，绘制顺序就在它上面）
                .then(if (skin.treatment == BubbleTreatment.Rail) Modifier.drawBehind {
                    drawRect(skin.color.copy(alpha = 0.8f), size = Size(3.dp.toPx(), size.height))
                } else Modifier),
        ) {
            Text(
                spoken,
                style = base.copy(fontSize = 16.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
                color = Palette.Fg,
                modifier = Modifier.padding(
                    start = if (skin.treatment == BubbleTreatment.Rail) 16.dp else 12.dp,
                    end = 12.dp, top = 9.dp, bottom = 9.dp,
                ),
            )
        }
    }
}

@Composable
private fun ActionLine(text: String, base: TextStyle) {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, codeBg, linkColor) { toAnnotated(Markdown.inline(text), codeBg, linkColor) }
    Row(
        Modifier.fillMaxWidth()
            .drawBehind { drawRect(Palette.Line2, size = Size(2.dp.toPx(), size.height)) }
            .padding(start = 10.dp),
    ) {
        Text(
            annotated,
            style = base.copy(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 14.sp, lineHeight = 22.sp),
            color = Palette.Fg2,
        )
    }
}

@Composable
private fun InnerLine(text: String, speaker: String, base: TextStyle) {
    val skin = remember(speaker) { Speakers.skin(speaker) }
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, skin.color, codeBg, linkColor) {
        buildAnnotatedString {
            val mark = SpanStyle(color = skin.color.copy(alpha = 0.75f), fontStyle = FontStyle.Italic)
            pushStyle(mark); append("（"); pop()
            append(toAnnotated(Markdown.inline(text), codeBg, linkColor))
            pushStyle(mark); append("）"); pop()
        }
    }
    Text(
        annotated,
        style = base.copy(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 14.sp, lineHeight = 23.sp),
        color = skin.color.copy(alpha = 0.9f),
        modifier = Modifier.padding(start = 2.dp),
    )
}

/** 台词正文：行内 Markdown 照常生效，两端补一对角色色的「」当地标 */
private fun quoted(text: String, accent: Color, codeBg: Color, linkColor: Color): AnnotatedString = buildAnnotatedString {
    val mark = SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)
    pushStyle(mark); append("「"); pop()
    append(toAnnotated(Markdown.inline(text), codeBg, linkColor))
    pushStyle(mark); append("」"); pop()
}

private fun speechShape(t: BubbleTreatment): RoundedCornerShape = when (t) {
    // 左下角收窄成 4dp，和助手气泡的"尖角"方向一致
    BubbleTreatment.Rail -> RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp)
    BubbleTreatment.Outline -> RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    BubbleTreatment.Gradient -> RoundedCornerShape(16.dp, 15.dp, 15.dp, 4.dp)
    BubbleTreatment.Quiet -> RoundedCornerShape(18.dp)
}