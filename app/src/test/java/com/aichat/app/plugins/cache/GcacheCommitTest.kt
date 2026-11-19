package com.aichat.app.plugins.cache

import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C4：章节提交轮也要写断点。
 *
 * 提交一章后 `[CHAPTER_SUMMARY #nnn]` 追加进 system 静态层 ⇒ `static_hash` 必变 ⇒
 * `decide`/`decideHistory` 的「固定层刚发生变化，本轮不写显式缓存」会把这一轮变成
 * **零缓存全价**。但前缀这一刻本来就变了、旧条目已用不上，写一个新断点从下一轮起
 * 就能命中新前缀 —— 所以本轮照写才对。rpcache 通过 `meta.rpCommit` 把「刚提交」传下来。
 */
class GcacheCommitTest {

    private val sys = "【RP 规则】保持人物一致性，不替玩家做决定。\n".repeat(200)
    private val chapter = "\n\n[CHAPTER_SUMMARY #001]\n第一章：玩家进城，与薇拉在废料场相遇。"
    private val line = "历史里的一段很长的正文，用来把前缀堆到门槛之上。".repeat(120)

    private fun msg(role: String, text: String) = jobj("role" to role, "content" to text)

    private fun body(system: String = sys) = jobj(
        "model" to "google/gemini-3.8-flash",
        "messages" to jarr(
            msg("system", system),
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

    private fun prevHash(extra: Map<String, Any?>): Any? =
        GCache.applyToRequest(req(body()), cfg, extra)["layers"]["static_hash"]

    @Test
    fun commitRoundStillWritesBreakpoint() {
        val extra = mapOf<String, Any?>("adapter" to "openrouter", "histBreak" to true, "commit" to true)
        val prev = prevHash(mapOf("adapter" to "openrouter", "histBreak" to true))
        // 本轮 system 多了一章 ⇒ static_hash 与上一轮不同
        val r = GCache.applyToRequest(req(body(sys + chapter)), cfg, extra + mapOf("prevStaticHash" to prev))

        assertTrue("提交轮照样写断点", r["decision"]["explicit"].truthy())
        assertTrue("历史仍然进缓存", r["decision"]["history"].truthy())
        assertEquals(4, r["applied"]["index"].d().toInt())
    }

    @Test
    fun changedPrefixWithoutCommitSkipsTheRound() {
        // 对照组：同样是「固定层变了」，但没有 commit（例如用户改了角色卡）→ 不写断点
        val extra = mapOf<String, Any?>("adapter" to "openrouter", "histBreak" to true)
        val prev = prevHash(extra)
        val r = GCache.applyToRequest(req(body(sys + chapter)), cfg, extra + mapOf("prevStaticHash" to prev))

        assertTrue("仍然 explicit=false", !r["decision"]["explicit"].truthy())
        assertNull("不动 body", r["applied"])
    }

    @Test
    fun unchangedPrefixWithCommitKeepsNormalPath() {
        // commit=true 但前缀没变：走原来的「稳定」分支，不该被 commit 改坏
        val extra = mapOf<String, Any?>("adapter" to "openrouter", "histBreak" to true, "commit" to true)
        val prev = prevHash(extra)
        val r = GCache.applyToRequest(req(body()), cfg, extra + mapOf("prevStaticHash" to prev))
        assertTrue(r["decision"]["explicit"].truthy())
        assertTrue(r["decision"]["history"].truthy())
        assertEquals(4, r["applied"]["index"].d().toInt())
    }
}