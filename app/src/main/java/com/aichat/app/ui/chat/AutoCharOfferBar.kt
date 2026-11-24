package com.aichat.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aichat.app.ui.theme.Palette
import com.aichat.app.ui.theme.Radii

/**
 * 「要不要用 AI 生成角色设定」的提示条。
 *
 * 旧版这套逻辑写了却从没调用过（AutoChar 七个入口零引用），所以新 RP 会话里用户永远等不到这句问话。
 * 世界里只补角色卡、**不动世界设定** —— 这句话必须写在脸上，否则用户不敢点。
 */
@Composable
fun AutoCharOfferBar(
    inWorld: Boolean,
    busy: Boolean,
    onGenerate: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = if (inWorld) "这个世界的设定已经有了" else "还没有角色设定"
    val body = if (inWorld) {
        "要我按这个世界生成一张角色卡吗？只写角色卡，世界设定一个字都不动。"
    } else {
        "要我按你刚才那句话生成一套角色设定吗？世界观、角色卡、规则一次写完。"
    }
    Surface(
        color = Palette.Surface2,
        shape = RoundedCornerShape(Radii.Md.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag("auto-char-offer"),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 4.dp)) {
            Text(title, color = Palette.Fg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(body, color = Palette.Fg3, fontSize = 12.sp, lineHeight = 17.sp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("auto-char-no")) {
                    Text("不用", color = Palette.Fg3, fontSize = 12.5.sp)
                }
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp).testTag("auto-char-busy"),
                        strokeWidth = 2.dp,
                        color = Palette.Accent,
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                } else {
                    TextButton(onClick = onGenerate, modifier = Modifier.testTag("auto-char-yes")) {
                        // Accent(#7C3AED) 当文字只有 3.49:1，12.5sp 的按钮字低于 AA；
                        // AccentText 是同色相浅档，8.87:1。转圈指示器那处 Accent 保留
                        // —— 那是图形元素，标准是 3:1，本来就过。
                        Text("生成", color = Palette.AccentText, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}
