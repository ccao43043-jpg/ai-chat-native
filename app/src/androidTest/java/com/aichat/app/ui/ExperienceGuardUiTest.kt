package com.aichat.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aichat.app.domain.model.Message
import com.aichat.app.domain.model.ProviderConfig
import com.aichat.app.domain.model.ProviderType
import com.aichat.app.domain.model.Role
import com.aichat.app.ui.chat.MessageActions
import com.aichat.app.ui.chat.MessageItem
import com.aichat.app.ui.chat.ModelPicker
import com.aichat.app.ui.chat.PluginErrorDot
import com.aichat.app.ui.chat.PluginErrorsDialog
import com.aichat.app.ui.theme.AiChatTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * EXPERIENCE-GUARD（2026-11-21）的 UI 契约：失败气泡的就地恢复入口、思考胶囊的乐观态守卫、
 * PluginErrorDot 语义、ModelPicker 的 Flex 单价与开关播种。仿 PluginErrorsUiTest 直接 setContent 单个 Composable。
 */
@RunWith(AndroidJUnit4::class)
class ExperienceGuardUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    // ---- U1/U2：失败气泡（msg-error / retry / restore-input） ----

    @Test fun failedBubbleShowsRetryAndRestoreAndFiresCallbacks() {
        var retried = false
        var restored = false
        val m = Message("a-1", Role.ASSISTANT, "", 1, error = "HTTP 500：演示错误")
        rule.setContent {
            AiChatTheme {
                MessageItem(
                    m, streaming = false, badges = emptyList(), onToast = {}, onImage = {},
                    actions = MessageActions(
                        onRegenerate = { retried = true },
                        onRestoreInput = { restored = true },
                    ),
                )
            }
        }
        rule.onNodeWithTag("msg-error").assertExists()
        rule.onNodeWithTag("retry").performClick()
        rule.onNodeWithTag("restore-input").performClick()
        rule.runOnUiThread { assertTrue("重试要回调", retried); assertTrue("放回输入框要回调", restored) }
    }

    @Test fun healthyBubbleHidesRecoveryPills() {
        val m = Message("a-2", Role.ASSISTANT, "正常回复", 1)
        rule.setContent { AiChatTheme { MessageItem(m, streaming = false, badges = emptyList(), onToast = {}, onImage = {}) } }
        rule.onNodeWithTag("retry").assertDoesNotExist()
        rule.onNodeWithTag("restore-input").assertDoesNotExist()
    }

    // ---- U3：思考胶囊（enabled=false 时不乐观翻转） ----

    @Test fun thinkingPillDoesNotFlipWhenDisabled() {
        rule.setContent {
            AiChatTheme {
                ModelPicker(
                    providers = emptyList(), currentProvider = null, currentModel = null,
                    onPick = { _, _ -> }, onManage = {}, onDismiss = {},
                    currentThinking = "auto", onThinkingLevel = {}, thinkingEnabled = false,
                )
            }
        }
        rule.onNodeWithTag("thinking-auto").assertIsSelected()
        rule.onNodeWithTag("thinking-low").performClick()
        rule.onNodeWithTag("thinking-auto").assertIsSelected()      // 被拒绝：不乐观变蓝
        rule.onNodeWithTag("thinking-low").assertIsNotSelected()
    }

    @Test fun thinkingPillFlipsWhenEnabled() {
        rule.setContent {
            AiChatTheme {
                ModelPicker(
                    providers = emptyList(), currentProvider = null, currentModel = null,
                    onPick = { _, _ -> }, onManage = {}, onDismiss = {},
                    currentThinking = "auto", onThinkingLevel = {}, thinkingEnabled = true,
                )
            }
        }
        rule.onNodeWithTag("thinking-low").performClick()
        rule.onNodeWithTag("thinking-low").assertIsSelected()
    }

    // ---- U6：PluginErrorDot 语义 ----

    @Test fun pluginErrorDotAnnouncesCountAndFiresClick() {
        var clicked = false
        rule.setContent { AiChatTheme { PluginErrorDot(7, { clicked = true }) } }
        val node = rule.onNodeWithTag("plugin-errors")
        node.assertExists()
        val desc = node.fetchSemanticsNode().config[SemanticsProperties.ContentDescription]?.joinToString()
        assertTrue("要报出错误条数", desc?.contains("7") == true)
        node.performClick()
        rule.runOnUiThread { assertTrue(clicked) }
    }

    // ---- T2：ModelPicker 的 Flex 单价与开关播种 ----

    private fun orProvider(meta: String): Pair<ProviderConfig, JsonObject> {
        val m = Json.parseToJsonElement(meta) as JsonObject
        val p = ProviderConfig(
            "or", "openrouter", "OR", ProviderType.OPENAI, "https://openrouter.ai/api/v1",
            listOf("google/gemini-2.5-pro"), modelMeta = m,
        )
        return p to m
    }

    @Test fun modelPickerShowsFlexPriceForCurrentProvider() {
        val (p, _) = orProvider("""{"google/gemini-2.5-pro":{"price":{"prompt":0.6,"completion":3.0}}}""")
        rule.setContent {
            AiChatTheme {
                ModelPicker(
                    providers = listOf(p), currentProvider = "or", currentModel = "google/gemini-2.5-pro",
                    onPick = { _, _ -> }, onManage = {}, onDismiss = {},
                )
            }
        }
        rule.onNodeWithText("\$0.30/\$1.50 per 1M", substring = true).assertExists()
    }

    @Test fun modelPickerFlexToggleReflectsCurrentFlex() {
        val (p, _) = orProvider("{}")
        rule.setContent {
            AiChatTheme {
                ModelPicker(
                    providers = listOf(p), currentProvider = "or", currentModel = "google/gemini-2.5-pro",
                    onPick = { _, _ -> }, onManage = {}, onDismiss = {}, currentFlex = false, onFlexChange = {},
                )
            }
        }
        rule.onNodeWithTag("flex-toggle").assertIsOff()

        rule.setContent {
            AiChatTheme {
                ModelPicker(
                    providers = listOf(p), currentProvider = "or", currentModel = "google/gemini-2.5-pro:flex",
                    onPick = { _, _ -> }, onManage = {}, onDismiss = {}, currentFlex = true, onFlexChange = {},
                )
            }
        }
        rule.onNodeWithTag("flex-toggle").assertIsOn()
    }

    // ---- T3：插件错误弹窗空态 ----

    @Test fun pluginErrorsDialogEmptyHidesClearButton() {
        rule.setContent {
            AiChatTheme { PluginErrorsDialog(errors = emptyList(), onDismiss = {}, onClear = {}, nameOf = { it }) }
        }
        rule.onNodeWithText("暂时没有插件报错。").assertExists()
        rule.onNodeWithTag("plugin-errors-clear").assertDoesNotExist()
    }

    @Test fun pluginErrorsDialogWithErrorsShowsClear() {
        val errors = listOf(buildJsonObject {
            put("plugin", JsonPrimitive("rpcache"))
            put("hook", JsonPrimitive("onBeforeRequest"))
            put("message", JsonPrimitive("boom"))
            put("count", JsonPrimitive(1))
        })
        rule.setContent {
            AiChatTheme { PluginErrorsDialog(errors = errors, onDismiss = {}, onClear = {}, nameOf = { it }) }
        }
        rule.onNodeWithTag("plugin-errors-clear").assertExists()
    }
}
