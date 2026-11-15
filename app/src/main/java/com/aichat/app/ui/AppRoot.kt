package com.aichat.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aichat.app.ui.chat.ChatScreen
import com.aichat.app.ui.chat.ChatViewModel
import com.aichat.app.ui.common.LocalUiDeps
import com.aichat.app.ui.common.UiDeps
import com.aichat.app.ui.image.ImageScreen
import com.aichat.app.ui.settings.SettingsDestination
import com.aichat.app.ui.settings.SettingsScreen
import com.aichat.app.ui.theme.Palette

/** 底部固定两个 Tab（需求 4）。顺序与文案不可改：聊天 / 生图。 */
enum class MainTab(val label: String, val icon: ImageVector) {
    Chat("聊天", Icons.Filled.Edit),
    Image("生图", Icons.Filled.Face),
}

/**
 * 导航壳。返回键优先级（与旧版 onAndroidBack 一致：先关浮层，再关页面，最后退出）：
 *   对话框 / 看图 / 底部弹层（各自处理）> 设置子页 > 设置 > 抽屉 > 生图 Tab 回聊天 Tab > 退出
 * 切 Tab 时用 SaveableStateHolder 保留各 Tab 的界面状态；聊天数据和进行中的流在 Activity 级 ViewModel 里。
 */
@Composable
fun AppRoot(deps: UiDeps) {
    CompositionLocalProvider(LocalUiDeps provides deps) {
        val chatVm: ChatViewModel = viewModel(factory = ChatViewModel.Factory(deps))
        var tab by rememberSaveable { mutableStateOf(MainTab.Chat) }
        var settings by rememberSaveable { mutableStateOf<SettingsDestination?>(null) }

        val holder = rememberSaveableStateHolder()

        BackHandler(enabled = settings == null && tab != MainTab.Chat) { tab = MainTab.Chat }

        if (settings != null) {
            SettingsScreen(settings!!, onClose = { settings = null },
                onStartPersona = { p -> tab = MainTab.Chat; chatVm.controller.startFromPersona(p) })
            return@CompositionLocalProvider
        }
        Scaffold(
            bottomBar = {
                // 旧版 v0.29 的底栏只有 56dp：图标 + 11sp 文字，选中态是图标后面的胶囊。
                // Material 的 NavigationBar 默认 80dp（含 32dp 图标与 12sp 文字），整体比旧版高一半。
                Surface(color = Palette.Surface) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().height(56.dp)) {
                        MainTab.entries.forEach { t ->
                            val sel = tab == t
                            Column(
                                Modifier.weight(1f).fillMaxHeight()
                                    .clickable { tab = t }
                                    .testTag("tab-${t.name}"),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Box(
                                    Modifier.size(width = 44.dp, height = 26.dp)
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(if (sel) Palette.AccentSoft else Color.Transparent),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(t.icon, t.label, Modifier.size(18.dp), tint = if (sel) Palette.Accent else Palette.Fg2)
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(t.label, fontSize = 11.sp, color = if (sel) Palette.Accent else Palette.Fg2)
                            }
                        }
                    }
                }
            },
        ) { inner ->
            Box(Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner)) {
                holder.SaveableStateProvider(tab.name) {
                    when (tab) {
                        MainTab.Chat -> ChatScreen(
                            chatVm.controller,
                            onOpenSettings = { settings = it },
                            // 提示词走 ImageDraft 信箱：生图页切 Tab 会重建，靠参数传会丢
                            onOpenImage = { p -> com.aichat.app.ui.image.ImageDraft.pending = p; tab = MainTab.Image },
                        )
                        MainTab.Image -> ImageScreen()
                    }
                }
            }
        }
    }
}
