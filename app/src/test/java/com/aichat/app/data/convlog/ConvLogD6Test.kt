package com.aichat.app.data.convlog

import com.aichat.app.data.json.Js
import com.aichat.app.data.json.JsObj
import com.aichat.app.data.store.MemFileStore
import com.aichat.app.data.store.StoreJs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** D6（改进方案 §4.8）：commit 真追加——写放大从 O(全日志) 降到 O(本次 op)，崩溃注入（半行）可恢复。 */
class ConvLogD6Test {
    private val FIXED_NOW = 1700000000000L

    private fun engine() = JsConvLog(now = { FIXED_NOW }, random = { 0.5 })
    /** 原始文件视图：日志是 JSONL，不能用 StoreJs.get（会对 JSONL 做 Js.parse 触发 .corrupt 改名） */
    private val raw = MemFileStore(emptyMap())
    private fun memStore() = StoreJs(raw)

    private fun conv(id: String, vararg texts: String): JsObj {
        val msgs = texts.mapIndexed { i, t -> JsObj("id" to "${id}m$i", "role" to if (i % 2 == 0) "user" else "assistant", "content" to t, "createdAt" to (FIXED_NOW + i).toDouble()) }
        return JsObj("id" to id, "title" to "t", "updatedAt" to (FIXED_NOW + texts.size).toDouble(), "messages" to msgs.toList())
    }

    /** commit 后日志文件的字节增量 ≈ 新 op 的序列化长度（写放大 ≈ 1，而非整文件重写） */
    @Test
    fun commitAppendsInsteadOfRewriting() {
        val e = engine(); val st = memStore()
        val id = "c1"
        e.commit(st, conv(id, "第一句"))
        val logKey = JsConvLog.logKey(id)
        val before = raw.get(logKey) ?: ""
        assertTrue("首次提交应建立日志文件", before.isNotEmpty())

        e.commit(st, conv(id, "第一句", "第二句"))
        val after = raw.get(logKey) ?: ""
        // 真追加的性质：第二次提交不改写已有内容，只在尾部续写（写放大 = 本次 op 量）
        assertTrue("已有日志内容必须原样保留（追加而非重写）", after.startsWith(before))
        assertTrue("日志应增长", after.length > before.length)
    }

    /** 真追加后 load 语义不变：追加多次的重放 == 全量重放 */
    @Test
    fun appendedLogLoadsSameAsBefore() {
        val e = engine(); val st = memStore()
        val id = "c2"
        val c1 = conv(id, "第一句", "第二句")
        e.commit(st, c1)
        val c2 = conv(id, "第一句", "第二句", "第三句")
        e.commit(st, c2)
        e.clearSessions()
        val back = e.load(st, id)
        assertEquals(3, ((back?.get("messages")) as List<*>).size)
        assertEquals("第三句", (((back?.get("messages")) as List<*>)[2] as JsObj)["content"])
    }

    /** 崩溃注入：日志末尾半行（无换行的截断 JSON）→ load 跳过坏行不崩溃，后续 commit 继续追加 */
    @Test
    fun truncatedLastLineIsTolerated() {
        val e = engine(); val st = memStore()
        val id = "c3"
        e.commit(st, conv(id, "第一句"))
        e.commit(st, conv(id, "第一句", "第二句"))
        val logKey = JsConvLog.logKey(id)
        // 注入半行：截掉日志最后一个字符（只弄残末行 JSON；前面完整的 op 行保留）。
        // 注意走 MemFileStore 原始层——StoreJs.set 会把字符串再 stringify 成 JSON 字符串。
        raw.set(logKey, (raw.get(logKey) ?: "").dropLast(1))
        e.clearSessions()
        val back = e.load(st, id)
        assertTrue("截断后 load 不崩溃且仍能读出已落盘消息", ((back?.get("messages")) as List<*>).isNotEmpty())
        // 坏行被跳过后，新 commit 继续追加不破坏
        e.commit(st, conv(id, "第一句", "第二句", "第三句"))
        e.clearSessions()
        val back2 = e.load(st, id)
        assertEquals(3, ((back2?.get("messages")) as List<*>).size)
    }
}
