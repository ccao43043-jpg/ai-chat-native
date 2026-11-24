package com.aichat.app.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.aichat.app.ui.theme.AiChatTheme
import com.aichat.app.ui.theme.Palette
import org.junit.Rule
import org.junit.Test

/**
 * 对话分层的稳定视觉契约。
 *
 * 不截图比对像素（机型字体不同必然漂移），只检查每类声音都真的进入了 Compose
 * 树，并且短台词不会在空消息或异常输入下崩掉。
 */
class DialogueDesignTest {

    @get:Rule
    val rule = createComposeRule()

    private val sample = """
林墨：*把热茶推过去，杯底在木桌上磕出一声轻响*「先喝一口。外面雨这么大。」

*他在你对面坐下，指尖还搭在杯沿上。*

苏晚：（他今天不太对劲。）「我不冷。」

「那就当陪我。」*她笑了一下，目光落回窗外。*

雨声把两个人的沉默填满了。远处有人在关窗，木板发出很轻的一声。
    """.trimIndent()

    @Test
    fun rendersAllFourDialogueLayers() {
        rule.setContent { Frame { DialogueBody(sample, defaultSpeaker = "林墨") } }
        rule.onNodeWithText("先喝一口。外面雨这么大。").assertIsDisplayed()
        rule.onNodeWithText("把热茶推过去，杯底在木桌上磕出一声轻响").assertIsDisplayed()
        rule.onNodeWithText("（他今天不太对劲。）").assertIsDisplayed()
        rule.onNodeWithText("雨声把两个人的沉默填满了。远处有人在关窗，木板发出很轻的一声。").assertIsDisplayed()
    }

    @Test
    fun rendersSameSpeakerWithoutRepeatedIdentity() {
        val text = "林墨：「开。这场雨不停，我就不关。」\n\n*他抬眼看窗外。*\n\n「苏晚，过来坐。」"
        rule.setContent { Frame { DialogueBody(text, defaultSpeaker = "林墨") } }
        rule.onNodeWithText("开。这场雨不停，我就不关。").assertIsDisplayed()
        rule.onNodeWithText("苏晚，过来坐。").assertIsDisplayed()
        rule.onNodeWithText("他抬眼看窗外。").assertIsDisplayed()
    }

    @Test
    fun emptyDialogueDoesNotCrash() {
        rule.setContent { Frame { DialogueBody("") } }
        rule.onNodeWithText("不存在").assertDoesNotExist()
    }

    @Composable
    private fun Frame(content: @Composable () -> Unit) {
        AiChatTheme {
            Surface(color = Palette.Bg, modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Column(Modifier.width(360.dp).verticalScroll(rememberScrollState())) { content() }
                }
            }
        }
    }
}