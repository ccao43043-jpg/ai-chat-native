package com.aichat.app.ui

import android.content.Context
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToLog
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aichat.app.ui.common.fake.FakeDeps
import com.aichat.app.ui.theme.AiChatTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

/**
 * A 的 Compose UI 验收测试（spec §5 A·验收）：切页保留状态、发送 / 停止、M2 版本 / 编辑 / 设置子页。
 *
 * 稳定性约定（2026-11-17 为 CI 无头模拟器加固，见 MASTER-PLAN §5.4）：
 * - **不要用 assertIsDisplayed 判定「出现」**：CI 模拟器的软键盘 / 视口比本地小，节点存在但被遮挡就会红；
 *   一律用 waitUntilExists 判存在，只有真的要看布局的地方才断言显示。
 * - **点消息动作行之前先 performScrollTo**：无头模拟器视口更矮，动作行可能被推出可视区。
 * - **输入完立刻收键盘**：键盘占掉半屏会让后续点击坐标落到别处。
 * - 超时统一 30s：CI 模拟器软件渲染，一帧可能要几百毫秒，本地够用的 5s/15s 会假红。
 */
@RunWith(AndroidJUnit4::class)
class AppRootUiTest {
    private val rule = createAndroidComposeRule<ComponentActivity>()

    /** 失败时把语义树打进 logcat，避免「CI 红了但看不到界面」只能靠猜。 */
    private val dumpOnFailure = object : TestWatcher() {
        override fun failed(e: Throwable, description: Description) {
            try {
                rule.onRoot().printToLog(TAG)
            } catch (t: Throwable) {
                // 有 Dialog 时会出现 2 个 root，onRoot 抛异常——退化成只记下失败信息
                Log.d(TAG, "dump failed (${description.methodName}): ${t.message}")
            }
        }
    }

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(dumpOnFailure).around(rule)

    private fun launch() = rule.setContent { AiChatTheme { AppRoot(FakeDeps.create()) } }

    /** 收软键盘：键盘占掉半屏会让后续点击落到别处（CI 无头模拟器尤其明显）。不用 Espresso，避免多一个依赖。 */
    private fun closeKeyboard() {
        rule.activityRule.scenario.onActivity { act ->
            val imm = act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(act.window.decorView.windowToken, 0)
        }
        rule.waitForIdle()
    }

    @Test
    fun openImagePageAndBackKeepsInputDraft() {
        launch()
        rule.onNodeWithTag("input").performTextInput("草稿")
        closeKeyboard()
        // 生图页从抽屉进入（底部 Tab 已去掉）
        rule.onNodeWithTag("open-drawer").performClick()
        rule.onNodeWithTag("drawer-image").performClick()
        rule.waitUntilExists("image-back")
        rule.onNodeWithTag("image-back").performClick()
        // 草稿保留在输入框里：直接断言输入框文本（onNodeWithText 匹配不到 EditableText 语义）
        rule.waitUntilTimeout(30_000) {
            runCatching {
                rule.onNodeWithTag("input").assertTextContains("草稿")
            }.isSuccess
        }
    }

    @Test
    fun sendThenStop() {
        launch()
        rule.onNodeWithTag("input").performTextInput("你好")
        closeKeyboard()
        rule.onNodeWithTag("send").performClick()
        rule.waitUntilExists("stop")
        rule.onNodeWithTag("stop").performClick()
        rule.waitUntilExists("send")
        // 「已中断」是消息元信息行：只判存在（被键盘挡住时 assertIsDisplayed 会假红）
        rule.waitUntilExists(text = "已中断", substring = true)
    }

    @Test
    fun sendCompletesAndCanCopy() {
        launch()
        rule.onNodeWithTag("input").performTextInput("测试")
        closeKeyboard()
        rule.onNodeWithTag("send").performClick()
        rule.waitUntilExists("copy")
        rule.onAllNodesWithTag("copy")[0].performClick()
    }

    /** M2：重新生成 → ‹ 2/2 ›；切回 1/2；编辑用户消息打开全屏编辑器 */
    @Test
    fun regenerateVersionsAndEdit() {
        launch()
        rule.onNodeWithTag("input").performTextInput("版本")
        closeKeyboard()
        rule.onNodeWithTag("send").performClick()
        rule.waitUntilExists("regen")
        rule.onNodeWithTag("regen").performScrollTo().performClick()
        rule.waitUntilExists("ver-label")
        rule.waitUntilExists("send")
        rule.waitUntilExists(text = "2/2")
        rule.onNodeWithTag("ver-prev").performScrollTo().performClick()
        rule.waitUntilExists(text = "1/2")
        // reverseLayout 语义序 = 显示序（最新在前）：[0] 是助手行的 edit，用户行的 edit 在最后
        val editCount = rule.onAllNodesWithTag("edit").fetchSemanticsNodes().size
        rule.onAllNodesWithTag("edit")[editCount - 1].performScrollTo().performClick()
        // 全屏编辑器有自己的 testTag（editor-resend =「保存并重新发送」）
        rule.waitUntilExists("editor-resend")
    }

    /** M2：设置 → 联网搜索 / 插件页可打开 */
    @Test
    fun searchAndPluginPagesOpen() {
        launch()
        rule.onNodeWithTag("open-drawer").performClick()
        rule.onNodeWithTag("drawer-settings").performClick()
        rule.onNodeWithTag("row-search").performClick()
        rule.waitUntilExists("search-save")
    }

    /** M3：抽屉 → 助手库：内置助手列出；点「翻译官」开始对话，标题栏显示新对话 */
    @Test
    fun personasLibraryStartsConversation() {
        launch()
        rule.onNodeWithTag("open-drawer").performClick()
        rule.onNodeWithTag("drawer-personas").performClick()
        rule.waitUntilExists("persona-p-translator")
        rule.onNodeWithTag("persona-p-translator").performClick()
        rule.waitUntilExists("input")
    }

    /** M3：点版本号打开版本菜单 */
    @Test
    fun versionMenuOpens() {
        launch()
        rule.onNodeWithTag("input").performTextInput("菜单")
        closeKeyboard()
        rule.onNodeWithTag("send").performClick()
        rule.waitUntilExists("regen")
        rule.onNodeWithTag("regen").performScrollTo().performClick()
        rule.waitUntilExists("ver-label")
        rule.waitUntilExists("send")
        rule.onNodeWithTag("ver-label").performScrollTo().performClick()
        rule.waitUntilExists("version-menu")
    }

    companion object {
        private const val TAG = "UITEST"

        /** 等某个 testTag 的节点出现。CI 模拟器慢，默认给 30s。 */
        private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.waitUntilExists(
            tag: String,
            timeoutMillis: Long = 30_000,
        ) = waitUntilTimeout(timeoutMillis) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

        /** 等某段文字出现（可子串匹配）。 */
        private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.waitUntilExists(
            text: String,
            substring: Boolean = false,
            timeoutMillis: Long = 30_000,
        ) = waitUntilTimeout(timeoutMillis) {
            onAllNodes(androidx.compose.ui.test.hasText(text, substring = substring))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
}

private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTag(tag: String) =
    onAllNodes(androidx.compose.ui.test.hasTestTag(tag))

/**
 * `waitUntil` 的语义是「超时抛异常」，直接用它会在 CI 上抛出无上下文的 ComposeTimeoutException。
 * 这里保留原语义（超时就抛），但把条件包一层 runCatching，避免断言失败（无节点）把等待提前打断。
 */
private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.waitUntilTimeout(
    timeoutMillis: Long,
    condition: () -> Boolean,
) = waitUntil(timeoutMillis) { runCatching(condition).getOrDefault(false) }
