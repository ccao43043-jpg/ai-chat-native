package com.aichat.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.aichat.app.ui.AppRoot
import com.aichat.app.ui.theme.AiChatTheme

/** 组件名保持 com.aichat.app.MainActivity，桌面图标/快捷方式覆盖安装后不失效 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 应用固定深色（对齐旧版），所以状态栏/导航栏图标必须显式用浅色，
        // 否则系统可能按"浅色主题"给出深色图标，在 #09090B 背景上几乎看不见。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val deps = (application as AiChatApp).graph.uiDeps()
        setContent { AiChatTheme { AppRoot(deps) } }
    }
}
