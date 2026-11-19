package com.aichat.app.data

import com.aichat.app.data.secret.FakeKeyVault
import com.aichat.app.data.store.JsonFileStore
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 落盘往返（真磁盘 + 结构化增量日志 + 快照压缩）必须保住「未建模的对话级设置」。
 *
 * 背景：用户报「思考等级选完，关闭弹层再打开就变回自动」。内存版仓库（MemFileStore）测不出来，
 * 这里换成 [JsonFileStore] 真文件、并把对话写长到触发压缩快照（COMPACT_OPS/BYTES），
 * 模拟真实使用中的长对话。
 */
class ConvLogExtrasTest {

    private fun newGraph(tag: String): DataGraph {
        val dir = File(System.getProperty("java.io.tmpdir"), "aichat-convlog-$tag-" + System.nanoTime())
        dir.mkdirs()
        val backup = File(dir, "backup").also { it.mkdirs() }
        return DataGraph(JsonFileStore(dir), FakeKeyVault(), backup)
    }

    @Test
    fun customKeySurvivesLongLogAndCompaction() = runBlocking {
        val g = newGraph("extras")
        var conv = g.chats.create("openrouter", "google/gemini-3.8-flash-20260902")

        // 写长：每次都改标题（进 meta diff），累计到超过 COMPACT_OPS=200 触发压缩
        repeat(260) { i ->
            conv = conv.copy(title = "对话 $i")
            g.chats.save(conv, touch = true)
        }

        // 真正的场景：用户在这条长对话上选了「高」
        val lv = "high"
        conv = conv.copy(extras = JsonObject(conv.extras + ("thinkingLevel" to JsonPrimitive(lv))))
        g.chats.save(conv, touch = false)

        val back = g.chats.load(conv.id)
        assertNotNull(back)
        assertEquals("真磁盘 + 压缩后仍要读得到", lv, back!!.extras["thinkingLevel"]?.let { (it as? JsonPrimitive)?.content })
        assertEquals("标题也在", "对话 259", back.title)
    }

    @Test
    fun customKeySurvivesRepeatedSavesWithoutCompaction() = runBlocking {
        val g = newGraph("extras2")
        var conv = g.chats.create("openrouter", "google/gemini-3.8-flash-20260902")
        conv = conv.copy(extras = JsonObject(conv.extras + ("thinkingLevel" to JsonPrimitive("medium"))))
        g.chats.save(conv, touch = false)

        repeat(5) { i ->
            conv = conv.copy(title = "标题 $i")
            g.chats.save(conv, touch = true)
        }

        val back = g.chats.load(conv.id)!!
        assertEquals("medium", (back.extras["thinkingLevel"] as? JsonPrimitive)?.content)
    }
}