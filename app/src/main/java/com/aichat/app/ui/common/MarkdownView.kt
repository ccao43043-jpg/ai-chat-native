package com.aichat.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aichat.app.ui.theme.Palette

/** 全局段缓存：流式时只有最后一段被重新解析 */
private val sharedCache = MarkdownCache()

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, onCopy: (String) -> Unit = {}) {
    // 解析异常（极端输入）时退回纯文本，不让一条消息拖垮整个列表
    val blocks = remember(text) { runCatching { sharedCache.parse(text) }.getOrElse { listOf(MdBlock.Paragraph(text)) } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { b -> MdBlockView(b, onCopy) }
    }
}

/**
 * 流式揭示：把「已收到的文本」逐帧追赶显示，看起来像在连续书写。
 *
 * 为什么需要：网络分片是一块一块到的（有的服务商一次几十上百字、思考型模型
 * 还有成段的思考与正文交替），直接整块渲染就是「跳一下、跳一下」。
 *
 * 追赶策略：每帧补掉剩余差额的 1/5（最少 1 字），所以
 *   - 差距大（一次到了 300 字）→ 一帧补 60 字，几帧之内跟上；
 *   - 差距小（一句一句到）→ 一帧 1 字，读起来是匀速书写；
 * 永远不会让显示落后于网络，也不会因为「追不上」而让文字越积越多。
 *
 * 逐帧重绘的代价是可控的：MarkdownCache 按段缓存，只有最后那一小段会重新解析。
 */
@Composable
fun MarkdownReveal(text: String, modifier: Modifier = Modifier, onCopy: (String) -> Unit = {}) {
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
    MarkdownText(if (shown >= text.length) text else text.substring(0, shown), modifier, onCopy)
}

/**
 * 渲染单个 Markdown 块。
 *
 * 对话分层（DialogueBody）会把代码块、表格、列表原样交回 Markdown 渲染，
 * 所以这里要有一个公开入口，而不是让 DialogueView 复制一份块渲染逻辑。
 */
@Composable
fun MarkdownBlock(b: MdBlock, onCopy: (String) -> Unit = {}) = MdBlockView(b, onCopy)

@Composable
private fun MdBlockView(b: MdBlock, onCopy: (String) -> Unit) {
    val typo = MaterialTheme.typography
    when (b) {
        is MdBlock.Heading -> InlineText(b.text, when (b.level) {
            1 -> typo.titleLarge; 2 -> typo.titleMedium; else -> typo.titleSmall }.copy(fontWeight = FontWeight.SemiBold))
        is MdBlock.Paragraph -> InlineText(b.text, typo.bodyLarge)
        is MdBlock.Code -> CodeBlock(b, onCopy)
        is MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
        is MdBlock.Quote -> {
            val bar = MaterialTheme.colorScheme.outlineVariant
            Column(Modifier.fillMaxWidth().drawBehind { drawRect(bar, size = Size(3.dp.toPx(), size.height)) }.padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                b.blocks.forEach { MdBlockView(it, onCopy) }
            }
        }
        is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            b.items.forEach { it ->
                Row(Modifier.padding(start = (it.indent * 16).dp)) {
                    val bullet = when {
                        it.checked == true -> "☑"
                        it.checked == false -> "☐"
                        it.number != null -> "${it.number}."
                        else -> if (it.indent % 2 == 0) "•" else "◦"
                    }
                    Text(bullet, style = typo.bodyLarge, modifier = Modifier.widthIn(min = 18.dp).padding(end = 4.dp))
                    InlineText(it.text, typo.bodyLarge)
                }
            }
        }
        is MdBlock.Table -> TableView(b)
    }
}


@Composable
fun InlineText(text: String, style: TextStyle, modifier: Modifier = Modifier, textAlign: TextAlign? = null) {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, codeBg, linkColor) { toAnnotated(Markdown.inline(text), codeBg, linkColor) }
    Text(annotated, style = style, modifier = modifier, textAlign = textAlign)
}

fun toAnnotated(runs: List<InlineRun>, codeBg: Color, linkColor: Color): AnnotatedString = buildAnnotatedString {
    for (r in runs) {
        val st = r.style
        val span = SpanStyle(
            fontWeight = if (st.bold) FontWeight.Bold else null,
            fontStyle = if (st.italic) FontStyle.Italic else null,
            textDecoration = when { st.strike -> TextDecoration.LineThrough; st.link != null -> TextDecoration.Underline; else -> null },
            fontFamily = if (st.code) FontFamily.Monospace else null,
            background = if (st.code) codeBg else Color.Unspecified,
            color = if (st.link != null) linkColor else Color.Unspecified,
            fontSize = if (st.code) 0.92.em() else androidx.compose.ui.unit.TextUnit.Unspecified,
        )
        if (st.link != null && (st.link.startsWith("http://") || st.link.startsWith("https://"))) {
            pushLink(LinkAnnotation.Url(st.link, TextLinkStyles(span)))
            append(r.text)
            pop()
        } else {
            pushStyle(span); append(r.text); pop()
        }
    }
}

private fun Double.em() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Em)

@Composable
fun CodeBlock(b: MdBlock.Code, onCopy: (String) -> Unit) {
    // 固定深色。本 app 只有一套暗色皮肤（AiChatTheme 只传 darkColorScheme，
    // 见 Theme.kt 顶部注释：旧版 www/app.css 就是 color-scheme: dark）。
    // 旧代码在这里读 isSystemInDarkTheme()，于是系统切浅色时，代码块会渲染成
    // #F4F4F7 白底 + Light 语法配色，嵌在深色的助手气泡里 —— 一块刺眼的白斑。
    // 主题不跟随系统，这里的判断也就不该跟随系统。
    val bg = Color(0xFF1E1F24)
    val palette = CodePalette.Dark
    val annotated = remember(b.code, b.lang) { highlight(b.code, b.lang, palette) }
    val clip = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().background(bg, RoundedCornerShape(8.dp))) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(b.lang.ifEmpty { "code" } + if (!b.closed) " …" else "", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onClick = { clip.setText(AnnotatedString(b.code)); onCopy("已复制代码") }) {
                // primary(#7C3AED) 当文字只有 3.49:1，低于 AA；AccentText 8.87:1
                Text("复制", fontSize = 12.sp, color = Palette.AccentText)
            }
        }
        SelectionContainer {
            Text(annotated, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp,
                color = palette.plain, softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 10.dp, end = 10.dp, bottom = 10.dp))
        }
    }
}

enum class CodePalette(val plain: Color, val keyword: Color, val str: Color, val comment: Color, val number: Color, val annotation: Color) {
    Light(Color(0xFF24292F), Color(0xFFCF222E), Color(0xFF0A3069), Color(0xFF6E7781), Color(0xFF0550AE), Color(0xFF8250DF)),
    Dark(Color(0xFFE6EDF3), Color(0xFFFF7B72), Color(0xFFA5D6FF), Color(0xFF8B949E), Color(0xFF79C0FF), Color(0xFFD2A8FF)),
}

fun highlight(code: String, lang: String, p: CodePalette): AnnotatedString = buildAnnotatedString {
    append(code)
    for (t in CodeHighlighter.tokenize(code, lang)) {
        val c = when (t.kind) {
            TokKind.Keyword -> p.keyword; TokKind.Str -> p.str; TokKind.Comment -> p.comment
            TokKind.Number -> p.number; TokKind.Annotation -> p.annotation; TokKind.Plain -> continue
        }
        addStyle(SpanStyle(color = c, fontStyle = if (t.kind == TokKind.Comment) FontStyle.Italic else null), t.start, t.end)
    }
}

@Composable
private fun TableView(t: MdBlock.Table) {
    val border = MaterialTheme.colorScheme.outlineVariant
    Box(Modifier.horizontalScroll(rememberScrollState())) {
        Column(Modifier.border(1.dp, border, RoundedCornerShape(4.dp))) {
            val cols = maxOf(t.header.size, t.rows.maxOfOrNull { it.size } ?: 0)
            @Composable fun row(cells: List<String>, header: Boolean) {
                Row {
                    for (i in 0 until cols) {
                        val align = when (t.align.getOrNull(i)) { MdAlign.Center -> TextAlign.Center; MdAlign.End -> TextAlign.End; else -> TextAlign.Start }
                        InlineText(cells.getOrElse(i) { "" },
                            MaterialTheme.typography.bodyMedium.copy(fontWeight = if (header) FontWeight.SemiBold else null),
                            Modifier.width(120.dp).padding(6.dp), textAlign = align)
                    }
                }
            }
            row(t.header, true)
            HorizontalDivider(color = border)
            t.rows.forEach { row(it, false) }
        }
    }
}
