package com.aichat.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
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
import com.aichat.app.ui.theme.SpeakerSkin
import com.aichat.app.ui.theme.Speakers

/*
 * 对话式渲染：把同一条 RP 回复变成一张可扫读的「剧本卡片」。
 *
 * 视觉层级只有一句话：台词是有色的实体，其余三类都是它的注脚。
 *
 *   台词  角色色卡片 + 首字徽记 + 名字 + 16sp 无衬线正文
 *          —— 一条消息里唯一有底、有名、有角色身份的内容
 *   动作  无底色，衬线斜体 + 中性细竖线
 *          —— 像舞台指示，清楚但主动退后
 *   内心  无底色，角色色斜体 + 虚线
 *          —— 借角色色表明"这是谁在想"，不抢台词的存在感
 *   旁白  无底色，衬线常规体 + 更松的行距
 *          —— 场景文字，回到小说正文的节奏
 *
 * 旧版在每个角色的小气泡外再套一个大助手气泡，盒中盒让层级互相打架；
 * 现在对白消息不再画外层容器，角色卡片直接落在消息流里。这样视觉分组由
 * "谁在说话"承担，而不是再由一个巨大的灰色盒子重复一次。
 *
 * 每个角色的色相仍由 Speakers.skin(name) 稳定生成，跨消息、跨会话不变；
 * 底色浓淡只做轻微变化，形状和排版语言保持一致，避免上一版四种气泡像四套组件。
 */

/** 对话正文各块之间的距离。比普通 Markdown 更松，读起来像分镜而不是文档。 */
private val DlgGap = 11.dp

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
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DlgGap)) {
        parts.forEach { p -> DlgPartView(p, fallback, onCopy) }
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
private fun DlgPartView(p: DlgPart, fallback: String, onCopy: (String) -> Unit) {
    val base = MaterialTheme.typography.bodyLarge
    when (p) {
        is DlgPart.Block -> MarkdownBlock(p.block, onCopy)
        is DlgPart.Speech -> SpeechCard(p.speaker ?: fallback, p.text, base)
        is DlgPart.Action -> ActionLine(p.text, base)
        is DlgPart.Inner -> InnerLine(p.text, p.speaker ?: fallback, base)
        is DlgPart.Narration -> NarrationLine(p.text, base)
    }
}

/**
 * 一个角色 + 一句台词 = 一张卡片。
 *
 * 名字、首字徽记和台词放在同一个容器里，避免名字悬在气泡外、再靠一根细线
 * 强行关联。卡片本身是台词唯一的底色，因此也不需要外层助手气泡再来框一次。
 */
@Composable
private fun SpeechCard(speaker: String, text: String, base: TextStyle) {
    val name = speaker.trim()
    val skin = remember(name) { Speakers.skin(name) }
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    val spoken = remember(text, codeBg, linkColor) { quoted(text, codeBg, linkColor) }
    val shape = speechShape(skin.treatment)

    Column(
        Modifier
            // 短台词收成真正的话语气泡，长台词自然在可用宽度内换行。
            // 不再强制每句铺满整行：否则连续几个角色会变成一排横向面板，
            // 读者先看到「卡片」再看到「台词」，反而丢掉对话节奏。
            .widthIn(min = 152.dp, max = 560.dp)
            .clip(shape)
            .background(Palette.Surface.copy(alpha = solidity(skin.treatment)), shape)
            .background(speechWash(skin), shape)
            .border(1.dp, skin.color.copy(alpha = borderAlpha(skin.treatment)), shape)
            .then(if (skin.treatment == BubbleTreatment.Rail) Modifier.drawBehind {
                drawRect(skin.color.copy(alpha = 0.72f), size = Size(3.dp.toPx(), size.height))
            } else Modifier)
            .padding(
                start = if (skin.treatment == BubbleTreatment.Rail) 16.dp else 14.dp,
                end = 14.dp,
                top = 11.dp,
                bottom = 12.dp,
            ),
    ) {
        if (name.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SpeakerMark(name, skin)
                Text(
                    name,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    letterSpacing = 0.55.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = skin.color.copy(alpha = 0.96f),
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        Text(
            spoken,
            style = base.copy(fontSize = 16.sp, lineHeight = 25.sp, fontWeight = FontWeight.Medium),
            color = Palette.Fg,
        )
    }
}

/** 角色首字徽记：比纯文字名字更像「一个人」，也让同屏多角色更容易扫到。 */
@Composable
private fun SpeakerMark(name: String, skin: SpeakerSkin) {
    val initial = name.firstOrNull()?.toString()?.uppercase().orEmpty()
    Box(
        Modifier
            .size(23.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(skin.color, skin.color.copy(alpha = 0.64f))))
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initial,
            fontSize = 10.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF111114),
        )
    }
}

/**
 * 动作 / 舞台指示：中性细线 + 衬线斜体。
 * 它只在描述发生了什么，不参与「谁在说话」的颜色编码。
 */
@Composable
private fun ActionLine(text: String, base: TextStyle) {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, codeBg, linkColor) { toAnnotated(Markdown.inline(text), codeBg, linkColor) }
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = Palette.Line2,
                    start = Offset(1.dp.toPx(), 3.dp.toPx()),
                    end = Offset(1.dp.toPx(), size.height - 3.dp.toPx()),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }
            .padding(start = 12.dp, top = 1.dp, bottom = 1.dp),
    ) {
        Text(
            annotated,
            style = base.copy(
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 14.sp,
                lineHeight = 23.sp,
                letterSpacing = 0.16.sp,
            ),
            color = Palette.Fg2.copy(alpha = 0.88f),
        )
    }
}

/**
 * 内心独白：角色色斜体 + 细虚线，先读到颜色，再读到括号。
 * 虚线是"不是正说话"的第二层提示，不会像另一颗气泡那样抢正文。
 */
@Composable
private fun InnerLine(text: String, speaker: String, base: TextStyle) {
    val skin = remember(speaker) { Speakers.skin(speaker) }
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, skin.color, codeBg, linkColor) {
        buildAnnotatedString {
            val mark = SpanStyle(color = skin.color.copy(alpha = 0.58f), fontStyle = FontStyle.Italic)
            pushStyle(mark); append("（"); pop()
            append(toAnnotated(Markdown.inline(text), codeBg, linkColor))
            pushStyle(mark); append("）"); pop()
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp)
            .drawBehind {
                drawLine(
                    color = skin.color.copy(alpha = 0.52f),
                    start = Offset(1.dp.toPx(), 2.dp.toPx()),
                    end = Offset(1.dp.toPx(), size.height - 2.dp.toPx()),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx())),
                )
            }
            .padding(start = 12.dp, top = 1.dp, bottom = 1.dp),
    ) {
        Text(
            annotated,
            style = base.copy(
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 14.sp,
                lineHeight = 23.sp,
                letterSpacing = 0.14.sp,
            ),
            color = skin.color.copy(alpha = 0.88f),
        )
    }
}

/** 旁白：无底色、无角色色，靠衬线字体和更松的字距回到「小说正文」。 */
@Composable
private fun NarrationLine(text: String, base: TextStyle) {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, codeBg, linkColor) { toAnnotated(Markdown.inline(text), codeBg, linkColor) }
    Text(
        annotated,
        style = base.copy(
            fontFamily = FontFamily.Serif,
            fontSize = 15.sp,
            lineHeight = 26.sp,
            letterSpacing = 0.24.sp,
        ),
        color = Palette.Fg2.copy(alpha = 0.92f),
        modifier = Modifier.padding(start = 2.dp, end = 2.dp),
    )
}

/** 台词正文：行内 Markdown 照常生效；不在正文里重复补引号，卡片本身就是引号。 */
private fun quoted(text: String, codeBg: Color, linkColor: Color): AnnotatedString = buildAnnotatedString {
    append(toAnnotated(Markdown.inline(text), codeBg, linkColor))
}

/** 同一套卡片骨架，只允许极小的圆角差异；颜色才是角色之间的主要区分。 */
private fun speechShape(t: BubbleTreatment): RoundedCornerShape = when (t) {
    BubbleTreatment.Rail -> RoundedCornerShape(7.dp, 20.dp, 20.dp, 20.dp)
    BubbleTreatment.Outline -> RoundedCornerShape(20.dp, 20.dp, 20.dp, 7.dp)
    BubbleTreatment.Gradient -> RoundedCornerShape(20.dp)
    BubbleTreatment.Quiet -> RoundedCornerShape(20.dp, 20.dp, 20.dp, 12.dp)
}

/** 卡片底色先铺一层稳定的深灰玻璃，再叠角色色，避免直接叠在黑底上显脏。 */
private fun solidity(t: BubbleTreatment): Float = when (t) {
    BubbleTreatment.Rail -> 0.88f
    BubbleTreatment.Outline -> 0.58f
    BubbleTreatment.Gradient -> 0.76f
    BubbleTreatment.Quiet -> 0.82f
}

private fun speechWash(skin: SpeakerSkin): Brush = when (skin.treatment) {
    BubbleTreatment.Rail -> Brush.horizontalGradient(
        listOf(skin.color.copy(alpha = 0.24f), skin.color.copy(alpha = 0.075f)),
    )
    BubbleTreatment.Outline -> Brush.horizontalGradient(
        listOf(skin.color.copy(alpha = 0.105f), skin.color.copy(alpha = 0.035f)),
    )
    BubbleTreatment.Gradient -> Brush.horizontalGradient(
        listOf(skin.color.copy(alpha = 0.29f), skin.color.copy(alpha = 0.055f)),
    )
    BubbleTreatment.Quiet -> Brush.horizontalGradient(
        listOf(skin.color.copy(alpha = 0.15f), skin.color.copy(alpha = 0.05f)),
    )
}

private fun borderAlpha(t: BubbleTreatment): Float = when (t) {
    BubbleTreatment.Rail -> 0.25f
    BubbleTreatment.Outline -> 0.38f
    BubbleTreatment.Gradient -> 0.22f
    BubbleTreatment.Quiet -> 0.15f
}
