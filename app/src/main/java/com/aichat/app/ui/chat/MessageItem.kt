package com.aichat.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import com.aichat.app.R
import com.aichat.app.ui.theme.Palette
import com.aichat.app.ui.theme.Radii
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aichat.app.data.files.FileText
import com.aichat.app.domain.Badge
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import com.aichat.app.domain.model.Message
import com.aichat.app.domain.model.Role
import com.aichat.app.ui.common.MarkdownReveal
import com.aichat.app.ui.common.MarkdownText
import com.aichat.app.ui.common.RemoteImage
import com.aichat.app.ui.common.pressScale
import com.aichat.app.ui.common.rememberAnimationsOn
import com.aichat.app.ui.theme.Motion

/** 单条消息的操作回调（全部可空：为 null 时不显示对应按钮） */
data class MessageActions(
    val onEdit: (() -> Unit)? = null,
    val onRegenerate: (() -> Unit)? = null,
    val onContinue: (() -> Unit)? = null,
    val onVersion: ((Int) -> Unit)? = null,
    /** 点 n/m：版本菜单（切换到… / 只保留当前 / 删除当前） */
    val onVersionMenu: (() -> Unit)? = null,
    val onDelete: (() -> Unit)? = null,
    val onShareCard: (() -> Unit)? = null,
    val onShareScene: (() -> Unit)? = null,
    /** 徽章点击（Badge.action = 面板 id；没有 action 时显示 detail） */
    val onBadge: ((Badge) -> Unit)? = null,
)

/** 插件给出的消息状态（app.js：dim → .msg.dim 透明度 .45；label → 消息上方的状态行；版本切换器 .frozen 透明度 .55） */
data class MessageView(
    val busy: Boolean = false,          // 有生成在进行：隐藏编辑 / 重新生成 / 续写 / 删除（同 app.js !running）
    val dim: Boolean = false,
    val label: String? = null,
    val isLast: Boolean = false,
    val headName: String? = null,       // 「助手名 · 模型」
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageItem(
    m: Message,
    streaming: Boolean,
    badges: List<Badge>,
    onToast: (String) -> Unit,
    onImage: (String) -> Unit,
    actions: MessageActions = MessageActions(),
    view: MessageView = MessageView(),
) {
    val clip = LocalClipboardManager.current
    val copy = { clip.setText(AnnotatedString(m.content)); onToast("已复制") }
    val alpha = if (view.dim) 0.45f else 1f
    if (m.role == Role.USER) {
        Column(Modifier.fillMaxWidth().padding(start = 48.dp).testTag(if (view.dim) "msg-dim" else "msg-user-col"), horizontalAlignment = Alignment.End) {
            view.label?.let { StateLabel(it) }
            Column(Modifier.alpha(alpha), horizontalAlignment = Alignment.End) {
                Images(m.images, onImage)
                FileChips(m.files)
                val pressSource = remember { MutableInteractionSource() }
                val userShape = RoundedCornerShape(
                    com.aichat.app.ui.theme.Radii.Bubble.dp, com.aichat.app.ui.theme.Radii.Bubble.dp,
                    com.aichat.app.ui.theme.Radii.BubbleTail.dp, com.aichat.app.ui.theme.Radii.Bubble.dp,
                )
                if (m.content.isNotEmpty()) Surface(
                    // 旧版 .msg.user .bubble：16/16/4/16 的「尖角朝下」，实心紫 + 一层紫色投影
                    color = com.aichat.app.ui.theme.Palette.UserBubble,
                    shape = userShape,
                    shadowElevation = 2.dp,
                    // 大屏限宽：平板/折叠屏展开态下气泡能拉到 600dp 以上，
                    // 一行六十多个汉字，眼睛找不到下一行的起点。手机上不触发。
                    modifier = Modifier.widthIn(max = BubbleMax).clip(userShape)
                        .pressScale(pressSource)
                        .combinedClickable(interactionSource = pressSource, indication = null, onClick = {}, onLongClick = copy)
                        .testTag("msg-user"),
                ) {
                    Text(
                        m.content, style = MaterialTheme.typography.bodyLarge,
                        color = androidx.compose.ui.graphics.Color.White,
                        lineHeight = 24.sp,
                        modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp),
                    )
                }
            }
            // 用户消息的操作栏同样横向滑动：宽度够照旧右对齐，超出就从左起可滑
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Row(Modifier.horizontalScroll(rememberScrollState())) { ActionBar(m, view, copy, actions) }
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth().testTag(if (streaming) "msg-streaming" else if (view.dim) "msg-dim" else "msg-assistant"),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        view.label?.let { StateLabel(it) }
        Row(Modifier.alpha(alpha)) {
            // 旧版 .bot-avatar：28dp 渐变圆 + 2px 半透明紫描边，贴着消息左上角
            androidx.compose.foundation.layout.Box(
                Modifier.padding(top = 2.dp).size(24.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(com.aichat.app.ui.theme.Palette.Grad)
                    .border(1.dp, com.aichat.app.ui.theme.Palette.AccentSoft, androidx.compose.foundation.shape.CircleShape),
            )
            androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (view.headName != null || badges.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    view.headName?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    badges.forEach { b -> BadgeChip(b, onClick = { actions.onBadge?.invoke(b) ?: b.detail?.let(onToast) }) }
                }
                if (!m.reasoning.isNullOrEmpty()) Reasoning(m.reasoning, streaming && m.content.isEmpty())
                Images(m.images, onImage)
                val botShape = RoundedCornerShape(
                    com.aichat.app.ui.theme.Radii.Bubble.dp, com.aichat.app.ui.theme.Radii.Bubble.dp,
                    com.aichat.app.ui.theme.Radii.Bubble.dp, com.aichat.app.ui.theme.Radii.BubbleTail.dp,
                )
                if (m.content.isNotEmpty()) Surface(
                    // 旧版 .bot-body：16/16/16/4 的反向尖角，半透明底 + 一条细边
                    color = com.aichat.app.ui.theme.Palette.AssistantBubble,
                    shape = botShape,
                    border = androidx.compose.foundation.BorderStroke(1.dp, com.aichat.app.ui.theme.Palette.Line),
                    modifier = Modifier.widthIn(max = BubbleMax).clip(botShape).testTag("msg-assistant-body"),
                ) {
                    SelectionContainer {
                        androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                            // 流式中逐字揭示；已落定的消息直接整块渲染（无动画开销）
                            if (streaming) MarkdownReveal(m.content, onCopy = onToast)
                            else MarkdownText(m.content, onCopy = onToast)
                        }
                    }
                }
                else if (streaming && m.error == null) Row(
                    verticalAlignment = Alignment.CenterVertically,
                    // 读屏用户看不见转圈动画：用 polite live region 把「正在生成」播出来，
                    // 否则点了发送之后是一片死寂，不知道到底发出去了没有。
                    modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("  正在生成…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                m.error?.let { Text("⚠ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                val safetyBlocked = !streaming && (m.finishReason == "safety_blocked" ||
                    (m.extras["safetyBlocked"] as? JsonPrimitive)?.booleanOrNull == true)
                val motion = if (rememberAnimationsOn()) Motion.quick else 0
                var safetyExpanded by rememberSaveable { mutableStateOf(false) }
                AnimatedVisibility(
                    visible = safetyBlocked,
                    enter = fadeIn(tween(motion)) + expandVertically(tween(motion)),
                    exit = fadeOut(tween(motion)) + shrinkVertically(tween(motion)),
                ) {
                    Surface(
                        color = Palette.Surface2,
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Palette.Danger.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth().testTag("safety-banner"),
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "内容安全策略拦截，草稿已保留",
                                    color = Palette.Danger,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = { safetyExpanded = !safetyExpanded },
                                    modifier = Modifier.testTag("safety-banner-toggle"),
                                ) { Text(if (safetyExpanded) "收起" else "详情", fontSize = 11.sp, color = Palette.Fg2) }
                            }
                            AnimatedVisibility(
                                visible = safetyExpanded,
                                enter = fadeIn(tween(motion)),
                                exit = fadeOut(tween(motion)),
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        if (m.usage != null) "本次已记录到有效用量。" else "本次未记录到有效用量。",
                                        color = Palette.Fg2,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                    Text(
                                        "上游可能已按实际用量计费；修改内容后可主动重新发送。",
                                        color = Palette.Fg2,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (!streaming) {
            val safetyBlocked = m.finishReason == "safety_blocked" ||
                (m.extras["safetyBlocked"] as? JsonPrimitive)?.booleanOrNull == true
            val info = listOfNotNull(if (m.truncated && !safetyBlocked) "已中断" else null, m.usage?.let { u ->
                listOfNotNull(u.prompt?.let { "入 $it" }, u.completion?.let { "出 $it" }, u.hit?.takeIf { it > 0 }?.let { "命中 $it" })
                    .takeIf { it.isNotEmpty() }?.joinToString(" · ")
            }).joinToString("  ")
            // 操作栏 + 用量文字整行横向滑动：Row 放进 scroll 之后子项按自身宽度量（不受父宽限制），
            // 于是按钮永远保持原尺寸，不会因为用量数字变长被压成 0 宽；放不下的部分滑一下就能看到。
            Row(
                Modifier.fillMaxWidth().padding(start = 32.dp).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionBar(m, view, copy, actions)
                if (info.isNotEmpty()) Text(
                    info,
                    style = MaterialTheme.typography.labelSmall,
                    color = com.aichat.app.ui.theme.Palette.Fg3,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun StateLabel(text: String) = Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary,
    modifier = Modifier.padding(bottom = 2.dp).testTag("msg-state"))

/** 徽章色调（app.css .tone-ok / warn / bad / info） */
@Composable
fun toneColors(tone: String): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> {
    val cs = MaterialTheme.colorScheme
    val fg = when (tone) {
        "ok" -> androidx.compose.ui.graphics.Color(0xFF16A34A)
        "warn" -> androidx.compose.ui.graphics.Color(0xFFC27803)
        "bad" -> cs.error
        else -> cs.primary
    }
    return fg to fg.copy(alpha = 0.12f)
}

/**
 * 插件徽章。可点时给 [Role.Button]，并把文字合并进同一个语义节点 ——
 * 否则读屏会把「💓」和「好感度 60」拆成两次播报，还听不出这东西能点。
 */
@Composable
fun BadgeChip(b: Badge, onClick: () -> Unit, prefix: String = "") {
    val (fg, bg) = toneColors(b.tone)
    val tappable = b.action != null || b.detail != null
    // 高度刻意不动：徽章同时出现在顶栏（header-badges）和每条消息上，
    // 把它加高会连带顶栏一起长高，而且徽章出现/消失时顶栏会跳。
    // 这里只补语义 —— 可点时给 Role.Button，并把文字合并进同一节点，
    // 否则读屏会把「💓」和「好感度 60」拆成两次播报，还听不出这东西能点。
    Surface(color = bg, shape = RoundedCornerShape(7.dp), modifier = Modifier
        .clip(RoundedCornerShape(7.dp))
        .clickable(enabled = tappable, role = if (tappable) androidx.compose.ui.semantics.Role.Button else null, onClick = onClick)
        .semantics(mergeDescendants = true) { }
        .testTag("badge-${b.plugin}")) {
        Text(prefix + b.text, color = fg, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

/**
 * 操作栏：一行放得下，不横向滚动。
 *
 * 旧版把「复制 / 台词卡 / 场景图 / 继续生成 / 重新生成 / 编辑 / 删除」七个 12sp 文字平铺，
 * 屏宽塞不下只能左右滑——用户根本不知道右边还有按钮。
 * 现在：版本胶囊 + 至多两个高频文字胶囊 + 两个图标按钮 + 「⋯」，
 * 低频操作（分享、删除）收进菜单。**所有 testTag 保持不变**，UI 测试依赖它们。
 */
@Composable
private fun ActionBar(m: Message, v: MessageView, copy: () -> Unit, a: MessageActions) {
    val n = Versions.count(m); val k = Versions.index(m)
    var more by remember(m.id) { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.testTag("actions-${m.role.name.lowercase()}"),
    ) {
        if (n > 1 && a.onVersion != null) VersionPill(k, n, v.dim, a)
        if (!v.busy && !v.dim) {
            a.onContinue?.let { ActionPill("继续生成", "continue", it) }
            a.onRegenerate?.let { ActionPill(if (v.isLast) "重新生成" else "从头重生成", "regen", it) }
        }
        if (m.content.isNotEmpty()) ActionIconPainter(painterResource(R.drawable.ic_content_copy), "复制", "copy", copy)
        if (!v.busy) a.onEdit?.let { ActionIcon(Icons.Filled.Create, "编辑", "edit", it) }
        if (a.onShareCard != null || a.onShareScene != null || (!v.busy && a.onDelete != null)) {
            Box {
                ActionIcon(Icons.Filled.MoreVert, "更多操作", "more") { more = true }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    if (m.content.isNotEmpty()) {
                        a.onShareCard?.let {
                            DropdownMenuItem(text = { Text("分享台词卡") }, modifier = Modifier.testTag("share-card"),
                                onClick = { more = false; it() })
                        }
                        a.onShareScene?.let {
                            DropdownMenuItem(text = { Text("分享场景图") }, modifier = Modifier.testTag("share-scene"),
                                onClick = { more = false; it() })
                        }
                    }
                    if (!v.busy) a.onDelete?.let {
                        DropdownMenuItem(text = { Text("删除", color = Palette.Danger) },
                            modifier = Modifier.testTag("delete"), onClick = { more = false; it() })
                    }
                }
            }
        }
    }
}

/**
 * 版本胶囊 ‹ 1/3 ›：三段各自可点，tag 不变（ver-prev / ver-label / ver-next）。
 *
 * 两处可达性修正：
 * 1. 高度 26dp → 32dp，‹ › 的左右内边距 8/4 → 9/7。原来单个箭头的命中区约 20×20dp，
 *    而它紧挨着「1/2」（点开是版本菜单）——点错就跳到别的版本去了。
 * 2. ‹ › 是纯文本字形，读屏念作「小于号」「大于号」，完全听不懂。
 *    现在给 contentDescription + Role.Button，念「上一个版本，按钮」。
 *
 * 到头时用 [Palette.Disabled] 而不是 Fg3：Fg3 为了对比度提亮过，
 * 不提亮的话「可点」和「到头了」就一个颜色了。
 */
@Composable
private fun VersionPill(k: Int, n: Int, dim: Boolean, a: MessageActions) {
    Surface(
        color = Palette.Surface2,
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier.height(32.dp).alpha(if (dim) 0.55f else 1f).testTag(if (dim) "ver-frozen" else "ver-sw"),
    ) {
        Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.fillMaxHeight()
                    .clickable(enabled = k > 0, role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "上一个版本") { a.onVersion?.invoke(k - 1) }
                    .semantics { contentDescription = "上一个版本" }
                    .padding(horizontal = 7.dp).testTag("ver-prev"),
                contentAlignment = Alignment.Center,
            ) { Text("‹", fontSize = 14.sp, color = if (k > 0) Palette.Fg2 else Palette.Disabled) }
            Box(
                Modifier.fillMaxHeight()
                    .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "选择版本") { a.onVersionMenu?.invoke() }
                    .semantics { contentDescription = "第 ${k + 1} 版，共 $n 版" }
                    .padding(horizontal = 3.dp).testTag("ver-label"),
                contentAlignment = Alignment.Center,
            ) { Text("${k + 1}/$n", fontSize = 11.sp, color = Palette.AccentText) }
            Box(
                Modifier.fillMaxHeight()
                    .clickable(enabled = k < n - 1, role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "下一个版本") { a.onVersion?.invoke(k + 1) }
                    .semantics { contentDescription = "下一个版本" }
                    .padding(horizontal = 7.dp).testTag("ver-next"),
                contentAlignment = Alignment.Center,
            ) { Text("›", fontSize = 14.sp, color = if (k < n - 1) Palette.Fg2 else Palette.Disabled) }
        }
    }
}

/**
 * 文字胶囊：只在真正高频、且需要文字说明的动作上用（继续生成 / 重新生成）。
 *
 * 胶囊本体仍是 28dp 高（视觉不变），外面套一层 40dp 高的透明命中区。
 * 外层 Box 没有背景、没有涟漪，所以扩出来的 6dp 上下是完全看不见的。
 *
 * 只能用 heightIn 限制高度：写成 size(40.dp) 会把宽度也锁成 40dp，
 * 「重新生成」这类四五个字的标签（≈66–78dp）会被裁成一个半字。
 */
@Composable
private fun ActionPill(label: String, tag: String, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Box(
        Modifier.heightIn(min = 40.dp).pressScale(press)
            .clickable(interactionSource = press, indication = null, onClickLabel = label, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = Palette.Surface2,
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier.height(28.dp),
        ) {
            Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                Text(label, fontSize = 11.5.sp, color = Palette.Fg2, maxLines = 1)
            }
        }
    }
}

/**
 * 图标按钮：复制 / 编辑 / 更多。
 *
 * 命中区 30dp → 40dp。`indication = null` 且没有背景色，所以扩出来的 10dp
 * 是纯透明的，图标仍是 16dp 居中 —— 视觉零变化，只是手指终于点得中了。
 * 30dp 时三个按钮挤在一行、间距 2dp，想点「复制」很容易点到「编辑」。
 */
@Composable
private fun ActionIcon(icon: ImageVector, desc: String, tag: String, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(Radii.Sm.dp)).pressScale(press)
            .clickable(interactionSource = press, indication = null, onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, Modifier.size(16.dp), tint = Palette.Fg3) }
}

@Composable
private fun ActionIconPainter(painter: Painter, desc: String, tag: String, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(Radii.Sm.dp)).pressScale(press)
            .clickable(interactionSource = press, indication = null, onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Image(painter, desc, Modifier.size(15.dp), colorFilter = ColorFilter.tint(Palette.Fg3)) }
}

/** 气泡在大屏上的宽度上限（约 40 个汉字），手机上不触发 */
private val BubbleMax = 640.dp

private val SmallPad = PaddingValues(horizontal = 6.dp, vertical = 0.dp)


/** 附件文件卡片：名称 · 大小 · 类型（扫描版 PDF 标注） */
@Composable
fun FileChips(files: List<JsonObject>, onRemove: ((Int) -> Unit)? = null) {
    if (files.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 4.dp)) {
        files.forEachIndexed { i, f ->
            val name = (f["name"] as? JsonPrimitive)?.content ?: "文件"
            val size = (f["size"] as? JsonPrimitive)?.longOrNull
            val kind = (f["kind"] as? JsonPrimitive)?.content.orEmpty()
            val scanned = (f["scanned"] as? JsonPrimitive)?.booleanOrNull == true
            val trunc = (f["truncated"] as? JsonPrimitive)?.booleanOrNull == true
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp), modifier = Modifier.testTag("file-chip")) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp, end = if (onRemove != null) 0.dp else 10.dp, top = 6.dp, bottom = 6.dp)) {
                    Column(Modifier.widthIn(max = 240.dp)) {
                        Text("📄 $name", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(size?.let { FileText.formatSize(it) }, kind.uppercase().ifEmpty { null },
                            if (scanned) "扫描件·按图片发送" else null, if (trunc) "已截断" else null).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (onRemove != null) TextButton(onClick = { onRemove(i) }, contentPadding = SmallPad) { Text("✕") }
                }
            }
        }
    }
}

/**
 * 思考过程折叠块。
 *
 * 原来是一个裸 `clickable` 的 Surface：读屏听不出它能点、也不知道现在是开还是关，
 * 只能听到「思考过程 下三角」。现在补 Role.Button + 展开/收起状态描述，
 * 并把高度提到 36dp（原约 28dp）—— 这是整条消息里唯一的折叠开关，值得好点一点。
 * 「▴/▾」字形对读屏是噪音，用 stateDescription 替代。
 */
@Composable
private fun Reasoning(text: String, live: Boolean) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            color = com.aichat.app.ui.theme.Palette.Surface2,
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier.heightIn(min = 36.dp)
                .clickable(
                    role = androidx.compose.ui.semantics.Role.Button,
                    onClickLabel = if (open) "收起思考过程" else "展开思考过程",
                ) { open = !open }
                .semantics(mergeDescendants = true) {
                    stateDescription = if (open) "已展开" else "已收起"
                }
                .testTag("reasoning-toggle"),
        ) {
            Row(Modifier.fillMaxHeight().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (live) "思考中…" else "思考过程") + if (open) "  ▴" else "  ▾",
                    style = MaterialTheme.typography.labelMedium,
                    color = com.aichat.app.ui.theme.Palette.Fg2,
                    fontSize = 12.sp,
                )
            }
        }
        AnimatedVisibility(open || live) {
            Text(
                if (live && !open) text.takeLast(200) else text,
                style = MaterialTheme.typography.bodySmall,
                color = com.aichat.app.ui.theme.Palette.Fg2,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 2.dp),
            )
        }
    }
}

/**
 * 消息附图缩略图行。
 *
 * 修一个真实的溢出：4 张 × 96dp + 3 × 6dp 间距 = 402dp，而 360dp 宽的手机上
 * 助手消息可用宽度只有约 300dp（减去列表 14dp 边距、24dp 头像、8dp 间距），
 * 用户消息更少（还有 48dp 的左缩进）。Row 不可滚，超出部分直接被裁掉 ——
 * 第 4 张图看不见也点不到，而且没有任何提示说明右边还有东西。
 * 加 horizontalScroll 后可以滑到；缩略图尺寸不变。
 *
 * 顺带补语义：原来 4 张图的 contentDescription 全是「聊天图片」，
 * 读屏连播四遍同样的话；「+N」是纯文本，听不出那是「还有 N 张」。
 */
@Composable
private fun Images(images: List<String>, onImage: (String) -> Unit) {
    if (images.isEmpty()) return
    val shown = images.take(4)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(bottom = 4.dp).horizontalScroll(rememberScrollState()),
    ) {
        shown.forEachIndexed { i, src ->
            RemoteImage(
                src,
                Modifier.size(96.dp)
                    .clip(RoundedCornerShape(com.aichat.app.ui.theme.Radii.Tag.dp))
                    .clickable(onClickLabel = "查看大图") { onImage(src) },
                contentDescription = "消息图片，第 ${i + 1} 张，共 ${images.size} 张",
            )
        }
        if (images.size > shown.size) {
            Text(
                "+${images.size - shown.size}",
                color = Palette.Fg2,
                modifier = Modifier.align(Alignment.CenterVertically)
                    .semantics { contentDescription = "还有 ${images.size - shown.size} 张图片未显示" },
            )
        }
    }
}
