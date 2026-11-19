package com.aichat.app.plugins.cache

import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断点落点不能被 hybrid 绑架。
 *
 * rpcache 接管历史时会关掉 hybrid（历史归 rpcache 管，不允许 Zip 再改写一遍），但历史本身
 * 「整段稳定、只追加」，正是最该进缓存的东西。以前 `autoHist` 硬绑 `hybrid != null`，于是
 * RP 对话一律退回 `decide()` + `msgs[k-1]`（system）断点：**只有 system 进缓存**。线上实测
 * 的 cached 值恒为 3385/4012/4015/5014/5021（= system 长度）且与 prompt 增长无关，命中率被
 * 钉在 43.7%，而关掉 RP 优化后立刻回升——就是这个分叉。
 *
 * 现在调用方可以用 `extra.histBreak` 声明「历史由我负责且稳定」，与 hybrid 解耦。
 */
class GcacheHistBreakTest {

    private val sys = "【RP 规则】保持人物一致性，不替玩家做决定。\n".repeat(200)
    private val line = "历史里的一段很长的正文，用来把前缀堆到门槛之上。".repeat(120)

    private fun msg(role: String, text: String) = jobj("role" to role, "content" to text)

    private fun body() = jobj(
        "model" to "google/gemini-3.8-flash",
        "messages" to jarr(
            msg("system", sys),
            msg("user", line), msg("assistant", line),
            msg("user", line), msg("assistant", line),
            msg("user", "本轮输入"),
        ),
    )

    private fun req(b: JObj) = jobj(
        "url" to "https://openrouter.ai/api/v1/chat/completions",
        "headers" to jobj(),
        "body" to Js.stringify(b),
    )

    private val cfg = jobj("mode" to "auto")

    /** 先跑一轮拿到 static_hash，模拟「上一轮固定层没变」。 */
    private fun prevHash(extra: Map<String, Any?>): Any? =
        GCache.applyToRequest(req(body()), cfg, extra)["layers"]["static_hash"]

    @Test
    fun histBreakPutsBreakpointBeforeLastUserWhenHybridIsOff() {
        val extra = mapOf<String, Any?>("adapter" to "openrouter", "histBreak" to true)
        val r = GCache.applyToRequest(req(body()), cfg, extra + mapOf("prevStaticHash" to prevHash(extra)))

        assertTrue("应走 decideHistory", r["decision"]["explicit"].truthy())
        assertTrue("应标记 history", r["decision"]["history"].truthy())
        // [0] system, [1] u, [2] a, [3] u, [4] a, [5] 本轮 user → 断点落在 4（本轮 user 之前最后一条有文本的消息）
        assertEquals(4, r["applied"]["index"].d().toInt())
        assertTrue(r["applied"]["history"].truthy())
    }

    @Test
    fun withoutHistBreakTheBreakpointFallsBackToSystem() {
        // 旧行为：没有 hybrid、也没有 histBreak → autoHist=false → decide() → 断点退回 msgs[k-1]
        val extra = mapOf<String, Any?>("adapter" to "openrouter")
        val r = GCache.applyToRequest(req(body()), cfg, extra + mapOf("prevStaticHash" to prevHash(extra)))

        assertTrue("system 够长，显式断点仍然成立", r["decision"]["explicit"].truthy())
        assertEquals("断点落在 system 上 = 历史整段不命中", 0, r["applied"]["index"].d().toInt())
        assertNull(r["decision"]["history"])
    }

    @Test
    fun hybridAloneStillEnablesHistoryBreakpoint() {
        // 非 RP 对话的老路径：hybrid 有状态 → autoHist=true，行为不变
        val extra = mapOf<String, Any?>("adapter" to "openrouter", "hybrid" to GCache.emptyHybrid())
        val r = GCache.applyToRequest(req(body()), cfg, extra + mapOf("prevStaticHash" to prevHash(extra)))

        assertTrue(r["decision"]["history"].truthy())
        assertEquals(4, r["applied"]["index"].d().toInt())
    }

    @Test
    fun histBreakIsIgnoredWhenModeIsOff() {
        val r = GCache.applyToRequest(req(body()), jobj("mode" to "off"), mapOf<String, Any?>("adapter" to "openrouter", "histBreak" to true))
        assertNull("mode=off 一律不动 body", r["applied"])
    }
}