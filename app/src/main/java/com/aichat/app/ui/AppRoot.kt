package com.aichat.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aichat.app.ui.chat.ChatScreen
import com.aichat.app.ui.chat.ChatViewModel
import com.aichat.app.ui.common.LocalUiDeps
import com.aichat.app.ui.common.UiDeps
import com.aichat.app.ui.image.ImageDraft
import com.aichat.app.ui.image.ImageScreen
import com.aichat.app.ui.settings.SettingsDestination
import com.aichat.app.ui.settings.SettingsScreen

/**
 * 导航壳。**没有底部 Tab**（按用户要求去掉）：聊天是唯一主界面，生图从抽屉进入、
 * 用左上角返回退出。
 *
 * 返回键优先级（与旧版 onAndroidBack 一致：先关浮层，再关页面，最后退出）：
 *   对话框 / 看图 / 底部弹层（各自处理）> 设置子页 > 设置 > 生图页 > 抽屉 > 退出
 */
@Composable
fun AppRoot(deps: UiDeps) {
    CompositionLocalProvider(LocalUiDeps provides deps) {
        val chatVm: ChatViewModel = viewModel(factory = ChatViewModel.Factory(deps))
        var showImage by rememberSaveable { mutableStateOf(false) }
        var settings by rememberSaveable { mutableStateOf<SettingsDestination?>(null) }

        BackHandler(enabled = settings == null && showImage) { showImage = false }

        if (settings != null) {
            SettingsScreen(
                settings!!,
                onClose = { settings = null },
                onStartPersona = { p -> showImage = false; chatVm.controller.startFromPersona(p) },
            )
            return@CompositionLocalProvider
        }
        Scaffold {
            Box(Modifier.fillMaxSize().padding(it).consumeWindowInsets(it)) {
                if (showImage) {
                    ImageScreen(onBack = { showImage = false })
                } else {
                    ChatScreen(
                        chatVm.controller,
                        onOpenSettings = { settings = it },
                        // 提示词走 ImageDraft 信箱：生图页进出会重建，靠参数传会丢
                        onOpenImage = { p -> ImageDraft.pending = p; showImage = true },
                        onOpenImageTab = { ImageDraft.pending = null; showImage = true },
                    )
                }
            }
        }
    }
}
