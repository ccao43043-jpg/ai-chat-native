package com.aichat.app.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * 每个角色一套固定配色。
 *
 * 为什么是「哈希到固定盘」而不是让用户逐个配：RP 一场戏里出现三五个人物是常态，
 * 手配太啰嗦，而且同一角色跨消息必须同色 —— 一旦换色，读者会以为是另一个人在说话。
 * 用名字的 hashCode 取模，就同时满足「零配置」和「跨消息、跨会话稳定」。
 * SillyTavern 的 Dialogue Colorizer 走的是从头像取主色，pixelchat 是给每个角色
 * 存一份颜色；本仓库没有头像资源（助手头像是统一的渐变圆），所以取稳定哈希。
 *
 * 颜色全部按深色底校对过：都落在 #18181B 上 ≥ 4.5:1 的区间，做名字和「」装饰不糊。
 * 底色是这些颜色加很低的 alpha 铺出来的，正文始终用 Fg(#F4F4F5)，
 * 所以无论抽到哪一套，正文对比度都不变 —— 花的是边框和底色的花样，不是文字。
 */

/** 气泡的四种"花样"：同色系下换洗法，让不同角色不只是换个色相 */
enum class BubbleTreatment {
    /** 实心淡底 + 左侧 3dp 竖条：最像"有人在说话" */
    Rail,

    /** 描边式：透明底 + 亮边，轻盈，适合旁白感强的角色 */
    Outline,

    /** 渐变底：左浓右淡，像一束打在台词上的光 */
    Gradient,

    /** 实心淡底 + 大圆角，最安静的一种 */
    Quiet,
}

data class SpeakerSkin(val color: Color, val treatment: BubbleTreatment)

object Speakers {

    /**
     * 8 支色相，都做过深底可读性检查（对 #18181B 的对比度见每行注释）。
     * 顺序刻意打散色相，相邻哈希不会落在相邻色上，同屏两个角色一眼能分开。
     */
    private val COLORS = listOf(
        Color(0xFFB4A0FB), // 紫  7.8:1  —— 沿用主题里的 AccentText
        Color(0xFF7DD3FC), // 天蓝 10.7:1
        Color(0xFF6EE7B7), // 薄荷 11.9:1
        Color(0xFFFCD34D), // 琥珀 12.4:1
        Color(0xFFFDA4AF), // 珊瑚 8.9:1
        Color(0xFF67E8F9), // 青  12.6:1
        Color(0xFFF9A8D4), // 樱粉 9.0:1
        Color(0xFF93C5FD), // 雾蓝 9.6:1
    )

    private val TREATMENTS = BubbleTreatment.values()

    /**
     * 这个名字对应的配色。
     *
     * `Int.mod` 而不是 `%`：hashCode 是负数时 `%` 会返回负数，下标就越界了。
     */
    fun skin(name: String?): SpeakerSkin {
        val n = name?.trim()?.lowercase().orEmpty()
        if (n.isEmpty()) return SpeakerSkin(COLORS[0], BubbleTreatment.Outline)
        val i = n.hashCode().mod(COLORS.size)
        return SpeakerSkin(COLORS[i], TREATMENTS[i % TREATMENTS.size])
    }

    /** 换洗法的气泡底色（Rail / Quiet / Outline 用它铺底，Gradient 单独走画刷） */
    fun tint(skin: SpeakerSkin): Color = skin.color.copy(alpha = TINT_ALPHA[skin.treatment] ?: 0.14f)

    /**
     * 每种花样的底色浓度。
     *
     * 参考图（手机截图取色）里黑底 #111111 上的对话块是 #3D1D22 / #3D311B 这种
     * 「一眼看得出被染过」的程度，所以这里按同样的可感知度校：叠在助手气泡
     * #1B1B1D 上，最终底色落在 #2F2A1C ~ #3F3226 一带 —— 看得出换了底，
     * 又不会被当成"高亮警告"。Outline 仍然最淡：它靠亮边而不是底色说话。
     */
    val TINT_ALPHA = mapOf(
        BubbleTreatment.Rail to 0.15f,
        BubbleTreatment.Outline to 0.09f,
        BubbleTreatment.Gradient to 0.18f,
        BubbleTreatment.Quiet to 0.13f,
    )
}