package com.aichat.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aichat.app.ui.chat.PluginErrorDot
import com.aichat.app.ui.chat.PluginErrorsDialog
import com.aichat.app.ui.theme.AiChatTheme
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 插件错误可见性（只覆盖 UI 契约，不覆盖 registry 的 pushErr 逻辑）。
 *
 * 背景：`PluginRegistryImpl.safe()` 一直会把异常吞进 `errors`，但此前没有任何 UI 读它，
 * 插件静默失效（世界书整版不生效）时界面上完全看不出异常。
 */
@RunWith(AndroidJUnit4::class)
class PluginErrorsUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun errors() = listOf(
        buildJsonObject {
            put("plugin", JsonPrimitive("rpcache"))
            put("hook", JsonPrimitive("onBeforeRequest"))
            put("message", JsonPrimitive("IndexOutOfBounds"))
            put("count", JsonPrimitive(3))
        },
    )

    @Test fun dotIsHiddenWhenClean() {
        rule.setContent { AiChatTheme { PluginErrorDot(0, {}) } }
        rule.onNodeWithTag("plugin-errors").assertDoesNotExist()
    }

    @Test fun dotAndDialogShowCountAndHook() {
        var cleared = false
        rule.setContent {
            AiChatTheme {
                PluginErrorDot(1, {})
                PluginErrorsDialog(
                    errors = errors(),
                    onDismiss = {},
                    onClear = { cleared = true },
                    nameOf = { "缓存分层" },
                )
            }
        }
        rule.onNodeWithTag("plugin-errors").assertIsDisplayed()
        rule.onNodeWithText("插件错误（1）").assertIsDisplayed()
        rule.onNodeWithText("缓存分层", substring = true).assertIsDisplayed()
        rule.onNodeWithText("onBeforeRequest · ×3", substring = true).assertIsDisplayed()
        rule.onNodeWithText("IndexOutOfBounds", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("plugin-errors-clear").performClick()
        rule.runOnUiThread { assertTrue("清空要回调到 Registry.clearErrors", cleared) }
    }
}
