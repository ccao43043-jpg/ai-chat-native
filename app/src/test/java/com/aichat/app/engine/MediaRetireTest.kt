package com.aichat.app.engine

import com.aichat.app.domain.WireMessage
import com.aichat.app.domain.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 成本守卫（W3/C3）：历史图片请求侧退役的窗口语义与开关关闭等价性。 */
class MediaRetireTest {

    private fun img(n: String) = "data:image/jpeg;base64,$n"

    private fun turn(id: String, images: List<String>, content: String = id) =
        WireMessage(Role.USER, content, images)

    @Test fun keepsLastTwoUserTurnsAndRetiresOlder() {
        val wire = listOf(
            turn("u1", listOf(img("a"))),                                   // 退役（第 3 新）
            WireMessage(Role.ASSISTANT, "答"),
            turn("u2", listOf(img("b"))),                                   // 保留（第 2 新）
            turn("u3", listOf(img("c"), img("d"))),                         // 保留（最新）
        )
        val out = MediaRetire.retire(wire)
        assertEquals("最新两轮图片原样", img("b"), out[2].images.joinToString())
        assertEquals(listOf(img("c"), img("d")).joinToString(), out[3].images.joinToString())
        assertEquals("更早轮次图片从请求缺席", "", out[0].images.joinToString())
        assertTrue("占位说明要点明张数与重新附加", out[0].content.contains("【历史图片已省略：1 张"))
    }

    @Test fun placeholderDoesNotTouchOtherMessages() {
        val wire = listOf(
            turn("u1", listOf(img("a"))),
            WireMessage(Role.ASSISTANT, "答"),
            turn("u2", listOf(img("b"))),
        )
        val out = MediaRetire.retire(wire)
        assertEquals("u2 内容不变", "u2", out[2].content)
        assertEquals("助手消息不动", "答", out[1].content)
        assertEquals("助手图片（若有）不退役", img("b"), out[2].images.joinToString())
    }

    @Test fun fewerUserTurnsThanKeepIsIdentity() {
        val wire = listOf(turn("u1", listOf(img("a"))), WireMessage(Role.ASSISTANT, "答"))
        assertSame("用户轮不足 N：原列表原样返回", wire, MediaRetire.retire(wire))
    }

    @Test fun noImagesAtAllIsIdentity() {
        val wire = listOf(turn("u1", emptyList()), WireMessage(Role.ASSISTANT, "答"), turn("u2", emptyList()))
        assertSame("无图会话零行为变化", wire, MediaRetire.retire(wire))
    }

    @Test fun nonPositiveKeepDisablesRetire() {
        val wire = listOf(turn("u1", listOf(img("a"))), turn("u2", listOf(img("b"))), turn("u3", listOf(img("c"))))
        assertSame(wire, MediaRetire.retire(wire, keepLastUserTurns = 0))
    }

    @Test fun enabledReadsPluginsSwitch() {
        fun plugins(v: String?) = kotlinx.serialization.json.buildJsonObject {
            put("media", kotlinx.serialization.json.buildJsonObject {
                if (v != null) put("retireOldImages", kotlinx.serialization.json.JsonPrimitive(v))
            })
        }
        assertEquals(false, MediaRetire.enabled(plugins(null)))
        assertEquals(false, MediaRetire.enabled(plugins("false")))
        assertEquals(true, MediaRetire.enabled(plugins("true")))
    }
}
