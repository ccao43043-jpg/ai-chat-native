package com.aichat.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aichat.app.ui.theme.Palette

/**
 * 空对话首屏。对齐旧版 .empty：64dp 渐变圆角块 + 24px 标题 + 副标题 + 一排建议 pill。
 * 建议点一下只把文字塞进输入框，不直接发送 —— 用户通常还要再改两个字。
 *
 * 没配服务商时不显示建议：那时候点建议也没用（发不出去），
 * 改成旧版 v0.29 那张「开始之前」三步引导卡。
 */
@Composable
internal fun EmptyHero(hasProvider: Boolean, onSetup: () -> Unit, onPick: (String) -> Unit) {
    val suggestions = remember {
        listOf("解释量子计算", "写一封感谢信", "推荐一个周末可以去的地方")
    }
    // 旧写法是 `Box(fillMaxSize().verticalScroll(), contentAlignment = Center)`。
    // 这两者凑在一起有个经典陷阱：内容比视口高时，Box 把子项按「视口高度」居中，
    // 溢出部分同时从**上下两端**跑出去，而滚动只能往下滚 —— 顶部那块永远滚不到。
    // 横屏（可用高度约 300dp，内容约 420dp）或系统大字号时必现：
    // 88dp 的品牌图标和标题上半截被切掉，用户只看到三个建议 pill。
    //
    // 改成 BoxWithConstraints 拿到视口高度，内层 Column 用 heightIn(min = 视口高) +
    // verticalArrangement = Center：内容矮时照样垂直居中，内容高时自然撑开、
    // 从顶部开始完整可滚。
    //
    // 注意 BoxWithConstraints 必须在 verticalScroll **外面**：滚动修饰符会把传给
    // 子节点的 maxHeight 改成 Infinity，写在里面的话 `maxHeight` 拿到的是无穷大，
    // heightIn(min = Infinity) 直接量崩。外层这层只读约束、不产生滚动。
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = maxHeight
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = viewport).padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size(88.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Palette.Grad),
                contentAlignment = Alignment.Center,
            ) {
                // 用启动图标而不是某个通用 material 图标：空态是品牌位，
                // 星星/闪光/灯泡这类通用符号换成任何 app 都成立，反而更廉价。
                Image(
                    painterResource(com.aichat.app.R.mipmap.ic_launcher_foreground),
                    null,
                    Modifier.size(52.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "有什么可以帮你？",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "我可以回答问题、提供建议或帮你生成创意",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            if (hasProvider) {
                suggestions.forEach { s ->
                    Surface(
                        onClick = { onPick(s) },
                        shape = RoundedCornerShape(999.dp),
                        color = Palette.Surface2,
                        border = BorderStroke(1.dp, Palette.Line),
                        // 不再 fillMaxWidth：建议是「选项」不是「按钮条」，
                        // 撑满整行会让人以为那是三个并列的大按钮。按文字收口、居中。
                        modifier = Modifier.padding(vertical = 4.dp).widthIn(max = 300.dp),
                    ) {
                        // 命中区提到 44dp。不能直接在 Surface 上写 heightIn(min=44)：
                        // Surface 会把 min 约束传播给子节点，Text 被拉到 44dp 高后
                        // 文字贴顶，胶囊里的字就偏上了。用居中 Box 兜住。
                        Box(Modifier.heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
                            Text(
                                s,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            } else {
                SetupCard(onSetup)
            }
        }
        }
    }
}

/**
 * 没配服务商时的引导卡（对齐旧版 v0.29 的「开始之前」三步）。
 * 空态放三条建议是没意义的：点进去也发不出去，用户只会以为坏了。
 */
@Composable
internal fun SetupCard(onSetup: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Palette.Surface,
        border = BorderStroke(1.dp, Palette.Line),
        modifier = Modifier.fillMaxWidth().testTag("empty-setup-card"),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("开始之前", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            listOf(
                "打开设置，选择一个服务商",
                "粘贴你的 API Key 并保存",
                "回到这里，点顶部切换模型即可聊天",
            ).forEachIndexed { i, line ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(line, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(14.dp))
            Surface(
                onClick = onSetup,
                shape = RoundedCornerShape(12.dp),
                color = Palette.Accent,
                modifier = Modifier.fillMaxWidth().testTag("empty-setup"),
            ) {
                Text(
                    "去设置 API Key",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                )
            }
        }
    }
}