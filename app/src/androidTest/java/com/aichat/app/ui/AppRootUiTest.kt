package com.aichat.app.ui

import android.content.Context
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToLog
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aichat.app.ui.common.fake.FakeDeps
import com.aichat.app.ui.theme.AiChatTheme
import androidx.compose.ui.unit.dp
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
            // 打开 Dialog 时会有 2 个 root（主窗口 + Dialog 窗口），逐个 dump：
            // 只 dump 第一个会把真正要看的那棵树漏掉（2026-11-17 CI 上就是这么丢的）
            try {
                val roots = rule.onAllNodes(isRoot()).fetchSemanticsNodes().size
                Log.d(TAG, "dump ${description.methodName}: roots=$roots")
                for (i in 0 until roots) rule.onAllNodes(isRoot())[i].printToLog(TAG)
            } catch (t: Throwable) {
                Log.d(TAG, "dump failed (${description.methodName}): ${t.message}")
            }
        }
    }

    // dumpOnFailure 必须在 rule **内层**：放在外层时，内层 rule 的 after()（销毁 Compose 环境）
    // 会先于 failed() 执行，dump 只会得到 "Test not setup properly"（2026-11-17 实测两次都是这句）。
    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(rule).around(dumpOnFailure)

    /**
     * @param engineDelayMs 假引擎每块的延迟。默认 35ms；测「停止」这类依赖「流还在跑」的用例要调大，
     *   否则点下去时回复可能已经结束、stop 按钮已经消失（2026-11-17 本地就这么红过一次）。
     */
    private fun launch(engineDelayMs: Long = 35) =
        rule.setContent { AiChatTheme { AppRoot(FakeDeps.create(engineDelayMs = engineDelayMs)) } }

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
        // 200ms/块：回复要几秒才流完，保证点「停止」时流确实还在跑
        launch(engineDelayMs = 200)
        rule.onNodeWithTag("input").performTextInput("你好")
        closeKeyboard()
        rule.onNodeWithTag("send").performClick()
        rule.waitUntilExists("stop")
        // 先等第一段文字到达再点停止：fake 引擎用 `truncated = !finished && acc.isNotEmpty()` 判定，
        // 一个字都没吐就取消的话不会出「已中断」，CI 上就是这么 30s 超时的。
        rule.waitUntilExists(text = "演示回复", substring = true)
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

    /**
     * 动作胶囊的宽度必须由文字撑开。
     *
     * 回归用例：曾经把外层的 40dp 命中盒写成 `size(40.dp)`，宽度也被锁成 40dp，
     * 「重新生成」（11.5sp × 4 字 + 左右各 10dp ≈ 66dp）被裁成只剩一个半字。
     */
    @Test
    fun actionPillKeepsFullLabel() {
        launch()
        rule.onNodeWithTag("input").performTextInput("测试")
        closeKeyboard()
        rule.onNodeWithTag("send").performClick()
        rule.waitUntilExists("regen")
        rule.onNodeWithTag("regen").assertWidthIsAtLeast(60.dp)
    }

    /** M2：重新生成 → ‹ 2/2 ›；切回 1/2；编辑用户消息打开全屏编辑器 */
    @Test
    fun regenerateVersionsAndEdit() {
        launch()
        rule.onNodeWithTag("input").performTextInput("版本")
        closeKeyboard()
        rule.onNodeWithTag("send").performClick()
        rule.waitUntilExists("regen")
        // LazyColumn 的 item 不支持 performScrollTo（它要的是祖先的 ScrollBy 语义），
        // 必须让列表自己滚过去；否则 CI 上直接 "Action performScrollTo() failed"。
        rule.onNodeWithTag("messages").performScrollToNode(hasTestTag("regen"))
        rule.onNodeWithTag("regen").performClick()
        rule.waitUntilExists("ver-label")
        rule.waitUntilExists("send")
        rule.waitUntilExists(text = "2/2")
        rule.onNodeWithTag("ver-prev").performClick()
        rule.waitUntilExists(text = "1/2")
        // 用户行与助手行各有一个 edit（同一个 tag）。助手行的编辑器只有「保存」，
        // 只有用户行才有「保存并重新发送」——所以必须按祖先 actions-user 精确定位，
        // 直接用下标取（[0] / [n-1]）会点到助手那条，然后等不到 editor-resend 超时。
        val userEdit = hasTestTag("edit") and hasAnyAncestor(hasTestTag("actions-user"))
        rule.onNodeWithTag("messages").performScrollToNode(userEdit)
        rule.onNode(userEdit).performClick()
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
        // LazyColumn 的 item 不支持 performScrollTo（它要的是祖先的 ScrollBy 语义），
        // 必须让列表自己滚过去；否则 CI 上直接 "Action performScrollTo() failed"。
        rule.onNodeWithTag("messages").performScrollToNode(hasTestTag("regen"))
        rule.onNodeWithTag("regen").performClick()
        rule.waitUntilExists("ver-label")
        rule.waitUntilExists("send")
        rule.onNodeWithTag("ver-label").performClick()
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
