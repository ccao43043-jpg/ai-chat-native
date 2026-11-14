package com.aichat.app.plugins.rp

import com.aichat.app.domain.model.AppSettings
import com.aichat.app.domain.model.Conversation
import com.aichat.app.domain.model.EmptyJson
import com.aichat.app.domain.model.Message
import com.aichat.app.domain.model.ProviderConfig
import com.aichat.app.domain.model.ProviderType
import com.aichat.app.domain.model.Role
import com.aichat.app.plugins.PluginHost
import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 面板上的手动「生成 / 合并章节」。
 *
 * 自动路径只在超预算或到触发阈值时才跑，用户想提前压缩长篇必须能手动触发；
 * 这里覆盖三条最容易出错的路径：没有模型 / 没有可总结的旧对话 / 质量不合格被拒。
 */
class RpChapterManualTest {

    private val provider = ProviderConfig(
        id = "p1", presetId = "deepseek", name = "DeepSeek",
        type = ProviderType.OPENAI, baseUrl = "https://api.deepseek.com", models = listOf("deepseek-chat"),
    )
    private val settings = AppSettings(providers = listOf(provider))

    private val host = object : PluginHost {
        override fun now(): Long = 1_700_000_000_000L
        override fun convData(conv: Conversation, id: String): JObj = JObj()
        override fun config(settings: AppSettings, id: String): JObj = JObj()
        override fun isActive(id: String, settings: AppSettings, conv: Conversation?): Boolean = true
        override fun notify(text: String) {}
    }

    /** 八分区齐全的摘要（关键区不为空，质量门会放行）；标题必须带冒号，与 FORMAT_RULES 一致 */
    private val goodSummary = """
        FACTS:
        - 玩家住在海边渔村
        - 村里有一盏红色灯塔

        CHARACTER_KNOWLEDGE:
        - 老船工熟悉潮汐，也知道灯塔的来历

        PLAYER_KNOWLEDGE:
        - 玩家想学划船，但还不敢夜里出海

        SECRET:
        - 灯塔的红光是有人故意调的

        UNRESOLVED:
        - 谁在夜里调灯塔

        RELATIONSHIPS:
        - 玩家与老船工互相试探，还没交底

        OBJECTS:
        - 红色灯塔、旧渔船

        LOCATION:
        - 港口、海边渔村
    """.trimIndent()

    private fun state(keepRecent: Int): JsonObject {
        val st = RpState.normalizeState(Js.fromJson(EmptyJson) as JObj)
        val blocks = st["blocks"] as? List<*> ?: emptyList<Any?>()
        val out = ArrayList<Any?>()
        for (b in blocks) {
            if (b is MutableMap<*, *>) {
                @Suppress("UNCHECKED_CAST")
                val m = b as MutableMap<String, Any?>
                if (m["id"] == "world") m["content"] = "北境，终年积雪。"
                out.add(m)
            } else out.add(b)
        }
        st["blocks"] = out
        val s = (st["settings"] as? JObj) ?: JObj()
        val sum = (s["summary"] as? JObj) ?: JObj()
        sum["keep_recent_turns"] = keepRecent
        s["summary"] = sum
        st["settings"] = s
        return Js.toJson(st) as? JsonObject ?: EmptyJson
    }

    private fun conv(keepRecent: Int = 1, extraRounds: Int = 1): Conversation {
        val msgs = ArrayList<Message>()
        var n = 0
        repeat(extraRounds + 1) { i ->
            msgs += Message(id = "u$i", role = Role.USER, content = "第 $i 轮提问")
            msgs += Message(id = "a$i", role = Role.ASSISTANT, content = "第 $i 轮回答")
            n++
        }
        return Conversation(
            id = "c-manual", title = "手动章节", providerId = "p1", model = "deepseek-chat",
            createdAt = 1L, updatedAt = 1L, messages = msgs, systemPrompt = "你是助手。",
            rpState = state(keepRecent),
        )
    }

    @Test
    fun withoutAskItReportsNoProvider() = runTest {
        val plugin = RpCachePlugin(host, ask = null)
        val r = plugin.summarizeNow(conv(), settings, merge = false)
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("没有可用的服务商"))
    }

    @Test
    fun emptyBatchExplainsWhyNothingHappens() = runTest {
        val plugin = RpCachePlugin(host) { _, _ -> goodSummary }
        // 保留轮数 10 > 实际轮数，batch 为空
        val c = conv(keepRecent = 10, extraRounds = 1)
        val r = plugin.summarizeNow(c, settings, merge = false)
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("没有可总结的旧对话"))
    }

    @Test
    fun manualGenerateAppendsOneChapterCoveringOldRounds() = runTest {
        var asked = 0
        val plugin = RpCachePlugin(host) { _, prompt ->
            asked++
            assertTrue("提示词要带最近对话", prompt.contains("最近对话") || prompt.contains("====="))
            goodSummary
        }
        val c = conv(keepRecent = 1, extraRounds = 1)
        val r = plugin.summarizeNow(c, settings, merge = false)
        assertTrue(r.exceptionOrNull()?.message ?: "", r.isSuccess)
        val m = r.getOrThrow()
        assertEquals(1, asked)
        assertEquals(1, m.no)
        assertEquals(false, m.merged)
        val st = RpState.normalizeState(Js.fromJson(Js.toJson(m.state) as JsonObject) as JObj)
        val chapters = RpCacheCore.chaptersOf(st)
        assertEquals(1, chapters.size)
        val covered = (chapters[0]["coveredIds"] as? List<*>)?.map { Js.str(it) }
        assertEquals(listOf("u0", "a0"), covered)
    }

    @Test
    fun mergeNeedsAtLeastTwoChapters() = runTest {
        val plugin = RpCachePlugin(host) { _, _ -> goodSummary }
        val r = plugin.summarizeNow(conv(), settings, merge = true)
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("不需要合并"))
    }

    @Test
    fun lowQualitySummaryIsRejectedWithReadableMessage() = runTest {
        val plugin = RpCachePlugin(host) { _, _ -> "只有一句话，没有分区。" }
        val r = plugin.summarizeNow(conv(keepRecent = 1, extraRounds = 1), settings, merge = false)
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("质量检查"))
    }
}
