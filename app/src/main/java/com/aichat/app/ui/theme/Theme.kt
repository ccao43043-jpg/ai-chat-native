package com.aichat.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/*
 * 配色与圆角直接照旧版 `www/app.css` 的 :root 变量来 —— 旧版只有一套深色皮肤
 * （`color-scheme: dark`），所以这里也固定深色，不做浅色反转，避免"两套皮肤都半像"。
 *
 *   --bg #09090b  --surface #18181b  --surface-2 #27272a  --surface-3 #3f3f46
 *   --fg #f4f4f5  --fg-2 #a1a1aa  --fg-3 #71717a
 *   --accent #7c3aed  --accent-2 #d946ef  --grad 135deg #7c3aed → #c026d3
 *   --line rgba(255,255,255,.08)  --line-2 rgba(255,255,255,.14)
 */
object Palette {
    val Bg = Color(0xFF09090B)
    val Surface = Color(0xFF18181B)
    val Surface2 = Color(0xFF27272A)
    val Surface3 = Color(0xFF3F3F46)
    val Fg = Color(0xFFF4F4F5)
    val Fg2 = Color(0xFFA1A1AA)

    /**
     * 三级文字（时间戳、用量、占位符、副标题）。
     *
     * 旧版直接照抄 app.css 的 `--fg-3 #71717a`，在 `--bg #09090b` 上只有 **4.12:1**，
     * 在 `--surface-2 #27272a` 上只有 **3.08:1** —— 都低于 WCAG AA 对正文要求的 4.5:1。
     * 而这个 token 恰恰全用在 11–12sp 的小字上，是最需要对比度的地方。
     * 提到 #8E8E98 后：Bg 上 6.13:1、Surface2 上 4.59:1，双双达标；
     * 与 Fg2(#A1A1AA) 仍有明显层级差，不会把三级文字读成二级。
     */
    val Fg3 = Color(0xFF8E8E98)

    /**
     * 真正「不可用」的元素：版本胶囊到头时的 ‹ ›、没有内容可发时的发送钮。
     *
     * 单独留一个 token 而不是复用 Fg3 —— Fg3 提亮之后，可用/禁用就分不开了。
     * 这里保留旧 Fg3 的暗度，禁用态依旧明显退后。
     */
    val Disabled = Color(0xFF71717A)
    val Line = Color(0x14FFFFFF)
    val Line2 = Color(0x24FFFFFF)
    val Accent = Color(0xFF7C3AED)
    val Accent2 = Color(0xFFD946EF)
    val Accent3 = Color(0xFFA78BFA)

    /**
     * 强调色**当文字/图标用**时的颜色。
     *
     * Accent(#7C3AED) 做按钮底 + 白字是 5.70:1，没问题；但当文字直接画在深底上只有
     * **3.49:1**，画在 AccentSoft 胶囊底上更低到 **2.98:1** —— 版本号「1/2」、
     * 「生成」、世界书 Pill 这些 11–13sp 的紫字全都看不清。
     * 浅一档的 #B4A0FB：Bg 上 8.87:1、Surface2 上 6.64:1、AccentSoft 上 7.58:1，
     * 色相不变（还是同一支紫），只是亮度抬到可读区间。
     */
    val AccentText = Color(0xFFB4A0FB)
    val AccentSoft = Color(0x387C3AED)
    val UserBubble = Color(0xFF7C3AED)
    val AssistantBubble = Color(0x9927272A)
    val Danger = Color(0xFFFB7185)
    val DangerSoft = Color(0x1FFB7185)
    val Ok = Color(0xFF34D399)

    /** 旧版 `--grad`：头像、空态图标、主按钮用 */
    val Grad = Brush.linearGradient(listOf(Accent, Color(0xFFC026D3)))
}

/** 圆角：旧版 --r-lg 22 / --r-md 14 / --r-sm 10，气泡另有 16 与 4 的"尖角" */
object Radii {
    val Lg = 22
    val Md = 14
    val Sm = 10
    val Bubble = 16
    val BubbleTail = 4
    /** 小缩略图 / 徽标圆角（消息图片 96dp 缩略图），与旧版 8px 一致 */
    val Tag = 8
}

private val Dark = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2C1B4D),
    onPrimaryContainer = Color(0xFFE9D5FF),
    secondary = Palette.Accent2,
    onSecondary = Color.White,
    secondaryContainer = Palette.AccentSoft,
    onSecondaryContainer = Palette.Accent3,
    tertiary = Palette.Accent3,
    background = Palette.Bg,
    onBackground = Palette.Fg,
    surface = Palette.Bg,
    onSurface = Palette.Fg,
    surfaceVariant = Palette.Surface2,
    onSurfaceVariant = Palette.Fg2,
    surfaceContainerLowest = Color(0xFF050506),
    surfaceContainerLow = Palette.Surface,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.Surface2,
    surfaceContainerHighest = Palette.Surface3,
    outline = Palette.Line2,
    outlineVariant = Palette.Line,
    error = Palette.Danger,
    onError = Color(0xFF24060A),
    errorContainer = Palette.DangerSoft,
    onErrorContainer = Palette.Danger,
    inverseSurface = Palette.Fg,
    inverseOnSurface = Palette.Bg,
)

@Composable
fun AiChatTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Dark, typography = Typography(), content = content)
}
