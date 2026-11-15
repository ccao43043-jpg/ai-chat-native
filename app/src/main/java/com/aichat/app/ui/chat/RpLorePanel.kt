package com.aichat.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.aichat.app.ui.theme.Palette
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.aichat.app.domain.model.Conversation
import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.rp.RpLoreCore
import com.aichat.app.plugins.rp.RpState
import kotlinx.serialization.json.JsonObject

/**
 * 世界书（lorebook）编辑：一整段文本，按旧版语法 `[条目名]` 分条。
 *
 * 触发/冻结/预算这一套逻辑早就在 `RpLoreCore` 里，但界面上没有入口 —— 用户只能靠单测证明它存在。
 * 旧版 RP 面板里对应的是同一张卡：改完保存，冻结片段要重建一次才按新文本走。
 */
@Composable
fun RpLoreDialog(
    conv: Conversation,
    onSaveState: (JsonObject) -> Unit,
    onDismiss: () -> Unit,
    onToast: (String) -> Unit,
) {
    val st = remember(conv.rpState) { rpStateOfLore(conv) }
    var text by remember(conv.rpState) { mutableStateOf((st["loreText"] as? String) ?: "") }
    val frozen = RpLoreCore.firedOf(st).size
    val editedWhileFrozen = st["loreEditedWhileFrozen"] == true
    val original = (st["loreText"] as? String) ?: ""

    fun apply(rebuild: Boolean) {
        var next: JObj = RpLoreCore.setLore(st, text, System.currentTimeMillis())
        if (rebuild) next = RpLoreCore.rebuildFrozen(next)
        onSaveState(Js.toJson(next) as? JsonObject ?: conv.rpState)
        val entries = RpLoreCore.loreOf(next).size
        onToast(
            if (rebuild) "已保存并重建：$entries 条，冻结已清空"
            else "已保存：$entries 条" + if (frozen > 0 && text != original) "（点「重建冻结」让新文本生效）" else ""
        )
    }

    Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
            Surface(color = Palette.Surface, shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("世界书", color = Palette.Fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("## 名字 | 触发词 | 属性。触发词写 * 或留空是常驻。", color = Palette.Fg3, fontSize = 12.sp)
                    if (frozen > 0) Text(
                        "已冻结 $frozen 条" + if (editedWhileFrozen) " · 改过还没重建" else "",
                        color = Palette.Accent, fontSize = 12.sp,
                    )
                    Surface(color = Palette.Surface2, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Box(Modifier.padding(12.dp).heightIn(min = 160.dp)) {
                            if (text.isEmpty()) Text("## 港口 | 船, 潮汐", color = Palette.Fg3, fontSize = 14.sp)
                            BasicTextField(
                                value = text, onValueChange = { text = it },
                                textStyle = androidx.compose.ui.text.TextStyle(color = Palette.Fg, fontSize = 14.sp),
                                cursorBrush = SolidColor(Palette.Accent),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp).testTag("rp-lore-text"),
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        TextButton(onClick = onDismiss) { Text("关闭", color = Palette.Fg2) }
                        TextButton(onClick = { apply(false) }, modifier = Modifier.testTag("rp-lore-save")) { Text("保存", color = Palette.Fg) }
                        Surface(onClick = { apply(true) }, enabled = frozen > 0, shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp), color = if (frozen > 0) Palette.Accent else Palette.Surface3, modifier = Modifier.testTag("rp-lore-rebuild")) {
                            Text("保存并重建", color = androidx.compose.ui.graphics.Color.White, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                        }
                    }
                    Spacer(Modifier.padding(4.dp))
                }
            }
        }
    }
}

private fun rpStateOfLore(conv: Conversation): JObj =
    RpState.normalizeState(Js.fromJson(conv.rpState) as? JObj ?: JObj())
