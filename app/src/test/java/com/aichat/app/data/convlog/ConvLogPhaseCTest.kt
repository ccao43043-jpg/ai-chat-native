package com.aichat.app.data.convlog

import com.aichat.app.data.json.Js
import com.aichat.app.data.json.JsObj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * structured-diff 阶段 C 验收（docs/structured-diff-plan.md §4.C）：
 * 「键被删除」由 diff 的 msg.edit payload 显式 null 表达（Object.assign 合并下 JS 回放等价），
 * encode 层的 nullRemovedMessageKeys 补丁移除（PR #74 后已无必要）。
 * 计划书原案 payload 带 `deleted: [...]` 数组不兼容 JS 回放（assign 会把数组当字段），故改显式 null。
 */
class ConvLogPhaseCTest {
    private val C = JsConvLog.Companion

    private fun msg(vararg pairs: Pair<String, Any?>): JsObj {
        val m = JsObj("id" to "m1", "role" to "user", "content" to "正文")
        pairs.forEach { (k, v) -> m[k] = v }
        return m
    }
    private fun conv(vararg messages: JsObj): JsObj = JsObj("id" to "c1", "updatedAt" to 1.0, "messages" to messages.toList())

    /** truncated true→缺键、error "e"→缺键、vi 1→缺键：payload 显式 null（§4.C 验收用例） */
    @Test
    fun removedKeysExpressedAsNullInEditPayload() {
        val prev = conv(msg("truncated" to true, "error" to "e", "vi" to 1.0))
        val next = conv(msg())
        val ops = C.diff(prev, next)
        val edit = ops.firstOrNull { (it as JsObj)["op"] == "msg.edit" } as? JsObj
        assertTrue("应有 msg.edit：${Js.stringify(ops)}", edit != null)
        val p = edit!!["payload"] as JsObj
        assertNull("truncated 应显式 null", p["truncated"])
        assertNull("error 应显式 null", p["error"])
        assertNull("vi 应显式 null", p["vi"])
        assertEquals("正文", p["content"])
    }

    /** 回放后字段不复活：truncated 假值、error 清空、vi 0（读法 = JS 真值判断 / vi|0） */
    @Test
    fun replayClearsRemovedKeys() {
        val prev = conv(msg("truncated" to true, "error" to "e", "vi" to 1.0))
        val next = conv(msg())
        val ops = C.diff(prev, next)
        // 模拟 commit 的打戳流程：diff 产出的 partial op 由 commit 包上 lamport/dev 后 applyOp
        val snap = C.takeSnapshot(prev, false)
        var clock = Js.toNumber(snap["clock"])
        val stamped = ops.map { o ->
            clock += 1
            o as JsObj
            JsObj("lamport" to clock, "dev" to "test", "op" to o["op"], "payload" to o["payload"])
        }
        val state = C.replay(snap, stamped)
        val m = (state["messages"] as List<*>)[0] as JsObj
        assertFalse("truncated 不复活", Js.truthy(m["truncated"]))
        assertTrue("error 已清", !Js.truthy(m["error"]))
        assertEquals("vi 归零", 0.0, Js.toNumber(m["vi"]), 0.0)
        assertEquals("正文不变", "正文", m["content"])
    }

    /** null ≡ 缺键：回放物化的 null 与下一次编码的缺键之间不再产生幻影 msg.edit（oplog 体积不增） */
    @Test
    fun nullVsAbsentDoesNotPhantomEdit() {
        val prev = conv(msg("error" to null, "truncated" to null))
        val next = conv(msg())
        assertEquals("null 与缺键合并语义等价，不应产生 op", 0, C.diff(prev, next).size)
    }

    /** 真实内容变化仍产生 op（放宽不漏报） */
    @Test
    fun contentChangeStillEmitsEdit() {
        val prev = conv(msg())
        val next = conv(msg("content" to "改了"))
        val ops = C.diff(prev, next)
        assertTrue(ops.any { (it as JsObj)["op"] == "msg.edit" })
    }
}
