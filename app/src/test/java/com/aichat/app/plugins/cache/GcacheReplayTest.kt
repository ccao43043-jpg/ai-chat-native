package com.aichat.app.plugins.cache

import com.aichat.app.plugins.js.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D 组：sent 回放与 Zip 压缩解耦。
 *
 * rpcache 接管历史时会关掉 hybrid（历史不能被 Zip 改写），但「上一轮实际送出的字节」必须
 * 回放成本轮的历史 —— 否则本条 user 从「带 [现在]/动态层的版本」退回原文，
 * 本轮前缀在第一条历史处就断，缓存从 system 末尾开始全部作废。
 */
class GcacheReplayTest {

    private val sys = "[WORLD]\n北境，终年积雪。"

    /** 带动态层（【当前状态】是 LAYER G）的 system：会被外置到末条 user 之前 */
    private val sysDyn = "[WORLD]\n北境，终年积雪。\n\n【当前状态】\n第三幕，码头"

    private fun req(vararg msgs: Pair<String, String>): JObj {
        val arr = msgs.map { jobj("role" to it.first, "content" to it.second) }
        return jobj("url" to "https://openrouter.ai/api/v1/chat/completions", "headers" to jobj("Authorization" to "Bearer x"),
            "body" to Js.stringify(jobj("model" to "google/gemini-3.8-flash-20260902", "messages" to arr)))
    }

    private fun body(r: JObj): JObj = Js.parse(r["req"]["body"].jstr()!!) as JObj

    /** 上一轮 uA 实际送出的是「动态层 + 原文」，状态里记着；本轮历史里的 uA 仍是原文 */
    private fun stateWithSent(): JObj {
        val st = GCache.emptyHybrid()
        GCache.hwRecordSent(st, "i:uA", "原文", "[CURRENT_STATE]\n第三幕，码头\n\n原文")
        return st
    }

    private fun extra(st: JObj, compress: Boolean, replay: Boolean, ids: List<Any?> = listOf("sA", "uA", "aA", "uB")): JObj = jobj(
        "adapter" to "openrouter", "hybrid" to st, "histBreak" to true,
        "compress" to compress, "replay" to replay,
        "ids" to ids.toMutableList<Any?>(),
        "sessionId" to "sess-1", "prevStaticHash" to "sha256:0",
    )

    @Test
    fun sentReplayHappensWhenCompressionIsOff() {
        val r = GCache.applyToRequest(
            req("system" to sys, "user" to "原文", "assistant" to "答", "user" to "现在"),
            GCache.DEFAULTS, extra(stateWithSent(), compress = false, replay = true),
        )
        assertEquals("[CURRENT_STATE]\n第三幕，码头\n\n原文", body(r)["messages"].a()!![1]["content"])
        assertNull("压缩关掉时不该产出 hybrid 状态（否则 decideHistory 会被误触发）", r["hybrid"])
        assertTrue("回放标记要给面板", r["replay"]["on"].truthy())
        assertEquals(false, r["replay"]["compress"].truthy())
    }

    @Test
    fun withoutReplayHistoryKeepsOriginalBytes() {
        val r = GCache.applyToRequest(
            req("system" to sys, "user" to "原文", "assistant" to "答", "user" to "现在"),
            GCache.DEFAULTS, extra(stateWithSent(), compress = false, replay = false),
        )
        val out = body(r)["messages"].a()!!
        assertEquals("原文", out[1]["content"])
        assertEquals("[WORLD]\n北境，终年积雪。", out[0]["content"])
    }

    @Test
    fun compressionStillRunsWhenBothAreOn() {
        val r = GCache.applyToRequest(
            req("system" to sys, "user" to "原文", "assistant" to "答", "user" to "现在"),
            GCache.DEFAULTS, extra(stateWithSent(), compress = true, replay = true),
        )
        assertTrue("压缩开着时 hybrid 状态要回来", r["hybrid"].truthy())
        assertEquals(4, body(r)["messages"].a()!!.size)
    }

    @Test
    fun recordedDynIsReplayedNextRound() {
        // 第 1 轮：CURRENT_STATE 被外置到末条 user 之前（实际送出 = 动态层 + 原文），状态里记下实际字节
        val st = GCache.emptyHybrid()
        val r1 = GCache.applyToRequest(
            req("system" to sysDyn, "user" to "旧1", "assistant" to "答1", "user" to "现在"),
            GCache.DEFAULTS, extra(st, compress = false, replay = true, ids = listOf("sA", "u1", "a1", "u2")),
        )
        val sent1 = body(r1)["messages"].a()!!.last()["content"] as String
        assertTrue("动态层应被前置到末条 user：$sent1", sent1.startsWith("【当前状态】"))
        assertTrue(r1["replay"]["sent"].i() >= 1)

        // 第 2 轮：那条 user 变成历史，原文还是「现在」——必须回放成第 1 轮实际送出的字节
        val r2 = GCache.applyToRequest(
            req("system" to sysDyn, "user" to "旧1", "assistant" to "答1", "user" to "现在", "assistant" to "答2", "user" to "再来"),
            GCache.DEFAULTS, extra(st, compress = false, replay = true, ids = listOf("sA", "u1", "a1", "u2", "a2", "u3")),
        )
        assertEquals(sent1, body(r2)["messages"].a()!![3]["content"])

        // 历史被改过（哈希对不上）时不替换：宁可发原文，也不发与记录不一致的字节
        val r3 = GCache.applyToRequest(
            req("system" to sysDyn, "user" to "旧1", "assistant" to "答1", "user" to "现在被改过", "assistant" to "答2", "user" to "再来"),
            GCache.DEFAULTS, extra(st, compress = false, replay = true, ids = listOf("sA", "u1", "a1", "u2", "a2", "u3")),
        )
        assertEquals("现在被改过", body(r3)["messages"].a()!![3]["content"])
    }

    @Test
    fun noStateMeansNoReplay() {
        val r = GCache.applyToRequest(
            req("system" to sys, "user" to "原文", "user" to "现在"),
            GCache.DEFAULTS, jobj("adapter" to "openrouter", "histBreak" to true, "ids" to mutableListOf<Any?>("uA", "uB")),
        )
        assertEquals("原文", body(r)["messages"].a()!![1]["content"])
        assertEquals(false, r["replay"]["on"].truthy())
    }
}
