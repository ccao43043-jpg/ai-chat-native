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
    val Fg3 = Color(0xFF71717A)
    val Line = Color(0x14FFFFFF)
    val Line2 = Color(0x24FFFFFF)
    val Accent = Color(0xFF7C3AED)
    val Accent2 = Color(0xFFD946EF)
    val Accent3 = Color(0xFFA78BFA)
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
