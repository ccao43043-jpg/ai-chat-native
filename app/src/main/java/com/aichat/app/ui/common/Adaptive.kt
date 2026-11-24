package com.aichat.app.ui.common

import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 大屏（平板 / 折叠屏展开态 / 桌面窗口）适配。
 *
 * 现状问题：工程里每个页面都是 `fillMaxWidth`。手机竖屏没问题，但 8 英寸平板
 * 或折叠屏展开后，一行能排 60 个以上汉字，读一行要横着扫完整屏；输入条被拉到
 * 二十厘米宽，光标和拇指离得很远；顶栏的标题和「更多」按钮分居屏幕两端。
 *
 * 这里只给**内容宽度**设上限，不改配色、字号、间距、圆角 —— 手机上
 * （宽度 < [breakpoint]）返回的 Modifier 是空的，布局与改动前逐像素一致。
 *
 * 用 [LocalConfiguration] 而不是 material3-window-size-class：后者要新增依赖，
 * 而本项目离线构建，依赖表里没有的 artifact 拉不下来。
 */
object Adaptive {
    /** 屏宽达到这个值就不再当手机竖屏处理（dp） */
    const val breakpoint = 600

    /** 聊天正文与输入区的舒适行长：约 45–75 个汉字宽 */
    val contentMax = 720.dp

    /** 抽屉、设置这类卡片列，可以比正文略宽 */
    val paneMax = 840.dp
}

/** 当前是否处于需要收口的大屏形态 */
@Composable
fun rememberIsWideScreen(): Boolean =
    LocalConfiguration.current.screenWidthDp >= Adaptive.breakpoint

/**
 * 大屏时把宽度收口到 [max]；小屏时不加任何约束。
 *
 * 只负责限宽，**不负责居中** —— 居中要由外层容器的 `contentAlignment` 决定，
 * 否则限宽后的内容会贴左边，比不限宽更难看。
 */
@Composable
fun Modifier.readableWidth(max: Dp = Adaptive.contentMax): Modifier =
    if (rememberIsWideScreen()) this.widthIn(max = max) else this
