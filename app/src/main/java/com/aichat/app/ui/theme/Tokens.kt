package com.aichat.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 版面尺度：只在这里定义，页面里不要写魔法数字。
 *
 * 规则（参考 DSH Go 的 page-design/polish-guide，只取与原生 Compose 相宜的部分）：
 * - 间距一律 4 的倍数；页面左右 [page]，分组之间 [group]，分组标题到卡片 [titleGap]；
 * - 圆角：卡片 [card]，弹层 [sheet]，小标签 [tag]；
 * - 行高：单行 [rowOne]、两行 [rowTwo]；卡片内的行之间不画分隔线，用行高与留白分开。
 */
object Space {
    val page = 16.dp
    val group = 24.dp
    val titleGap = 8.dp
    val row = 12.dp
    val tight = 4.dp

    /** 行首图标底圆直径 */
    val iconBox = 32.dp
    val icon = 18.dp

    val rowOne = 52.dp
    val rowTwo = 64.dp
}

object Radius {
    val card = 20.dp
    val sheet = 28.dp
    val tag = 8.dp
    val row = 14.dp
}

/**
 * 动效时长。反馈类要"感觉不到在等"，结构类要"看得出从哪来"。
 * 系统关掉动画时 [com.aichat.app.ui.common.rememberAnimationsOn] 返回 false，
 * 调用方据此跳过动画。
 */
object Motion {
    /** 按压缩放、最小停留 */
    const val press = 90

    /** 淡入淡出、图标切换 */
    const val quick = 160

    /** 小块展开收起 */
    const val standard = 240
}
