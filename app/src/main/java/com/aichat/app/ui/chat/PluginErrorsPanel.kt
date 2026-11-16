package com.aichat.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aichat.app.ui.theme.Palette
import com.aichat.app.ui.theme.Radius
import com.aichat.app.ui.theme.Space
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * 插件错误面板。
 *
 * 为什么需要它：`PluginRegistryImpl.safe()` 把插件抛出的异常吞掉塞进 `errors`，这个设计本身是对的
 * （一个插件崩了不该拖垮整轮对话），但**此前没有任何 UI 读这个列表** —— 插件静默失效时用户完全无感。
 * 世界书「写了不生效」整整一个版本没被发现，根因就是这个黑洞：`RpCachePlugin.onBeforeRequest` 的
 * 闸门直接 `return ctx`，请求逐字节不变，界面上一切正常。
 *
 * 这里只解决「让人看见」：列出最近 [MAX] 条（同 plugin+hook+message 已在 pushErr 里合并计数），一键清空。
 */
@Composable
fun PluginErrorsDialog(
    errors: List<JsonObject>,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    /** 插件 id → 显示名（拿不到就显示 id） */
    nameOf: (String) -> String = { it },
) {
    fun str(e: JsonObject, k: String): String =
        (e[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("plugin-errors-dialog"),
        title = { Text("插件错误（${errors.size}）") },
        text = {
            if (errors.isEmpty()) {
                Text("暂时没有插件报错。", style = MaterialTheme.typography.bodyMedium, color = Palette.Fg2)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Space.tight)) {
                    Text(
                        "插件出错不影响对话本身，只是这一轮对应的功能没生效（请求会原样发出去）。",
                        style = MaterialTheme.typography.labelMedium,
                        color = Palette.Fg2,
                    )
                    LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = 380.dp).testTag("plugin-errors-list"),
                        verticalArrangement = Arrangement.spacedBy(Space.tight),
                    ) {
                        items(errors.asReversed()) { e ->
                            val n = (e["count"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: 1
                            Column(
                                Modifier.fillMaxWidth()
                                    .background(Palette.Surface2, RoundedCornerShape(Radius.tag))
                                    .padding(horizontal = Space.row, vertical = 8.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        nameOf(str(e, "plugin")),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Palette.Fg,
                                    )
                                    Text(
                                        " · ${str(e, "hook")}" + if (n > 1) " · ×$n" else "",
                                        fontSize = 11.sp,
                                        color = Palette.Fg3,
                                    )
                                }
                                Text(
                                    str(e, "message").ifEmpty { "（无消息）" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Palette.Fg2,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = if (errors.isEmpty()) null else {
            { TextButton(onClick = onClear, modifier = Modifier.testTag("plugin-errors-clear")) { Text("清空") } }
        },
    )
}

/**
 * 顶栏那个红色数字气泡：只在有错误时出现，点开就是上面的面板。
 * 用数字而不是纯圆点 —— 「1 个错误」和「12 个错误」的处理意愿完全不同。
 */
@Composable
fun PluginErrorDot(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (count <= 0) return
    val src = remember { MutableInteractionSource() }
    Box(
        modifier
            .testTag("plugin-errors")
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.background(Palette.Danger, RoundedCornerShape(999.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        ) {
            Text(
                if (count > 99) "99+" else count.toString(),
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
    }
}
