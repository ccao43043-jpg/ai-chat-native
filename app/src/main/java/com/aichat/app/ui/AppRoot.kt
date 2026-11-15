package com.aichat.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.aichat.app.ui.theme.Palette
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
        // 退出动画期间还要拿得到目的地，否则内容先被卸掉会闪一帧
        var shown by remember { mutableStateOf(settings) }
        if (settings != null) shown = settings

        Box(Modifier.fillMaxSize()) {
            Scaffold(containerColor = Palette.Bg) {
                Box(Modifier.fillMaxSize().padding(it).consumeWindowInsets(it)) {
                    if (showImage) {
                        ImageScreen(onBack = { showImage = false })
                    } else {
                        ChatScreen(
                            chatVm.controller,
                            onOpenSettings = { settings = it },
                            onOpenImage = { p -> ImageDraft.pending = p; showImage = true },
                            onOpenImageTab = { ImageDraft.pending = null; showImage = true },
                        )
                    }
                }
            }
            val ease = FastOutSlowInEasing
            AnimatedVisibility(
                visible = settings != null,
                enter = slideInHorizontally(tween(280, easing = ease)) { it } + fadeIn(tween(160)),
                exit = slideOutHorizontally(tween(240, easing = ease)) { it } + fadeOut(tween(140)),
            ) {
                Box(Modifier.fillMaxSize().background(Palette.Bg)) {
                    SettingsScreen(
                        shown ?: SettingsDestination.Main,
                        onClose = { settings = null },
                        onStartPersona = { p -> showImage = false; chatVm.controller.startFromPersona(p) },
                    )
                }
            }
        }
    }
}
