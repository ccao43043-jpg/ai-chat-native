package com.aichat.app.plugins.cache

import com.aichat.app.domain.Slot
import com.aichat.app.domain.model.AppSettings
import com.aichat.app.domain.model.Conversation
import com.aichat.app.plugins.PluginHost
import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.js.a
import com.aichat.app.plugins.js.d
import com.aichat.app.plugins.js.jobj
import com.aichat.app.plugins.js.truthy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓存插件公共骨架的行为（M7 从 gcache/mmcache 里抽出来的那部分）。
 *
 * 抽这一层的意义就是让「前缀变了几次」「滚动窗口多长」只有一份实现——
 * 之前两个类各写一遍，改一处忘另一处就会出现「一个插件算得出前缀变化、另一个永远是 0」。
 */
class CachePluginBaseTest {
    private class Host : PluginHost {
        val data = HashMap<String, JObj>()
        override fun notify(text: String) {}
        override fun now(): Long = 0L
        override fun convData(conv: Conversation, id: String): JObj = data.getOrPut("${conv.id}|$id") { jobj() }
        override fun config(settings: AppSettings, id: String): JObj = jobj()
        override fun isActive(id: String, settings: AppSettings, conv: Conversation?): Boolean = false
    }

    private class Probe(host: PluginHost) : CachePluginBase(host) {
        override val id = "probe"
        override val name = "探针"
        override val defaultEnabled = true
        fun stat(conv: Conversation): JObj = host.convData(conv, id)
        fun prefix(conv: Conversation, p: String) = trackPrefix(
            conv.id, p, stat(conv), { s -> s.hashCode().toString(16) }, { x, y -> jobj("a" to x, "b" to y) },
        )
        fun body(conv: Conversation, b: String) = trackBody(
            conv.id, b, stat(conv), { prev, cur -> if (cur.isEmpty()) 0.0 else 1.0 * prev.length / cur.length },
        )
        fun roll(conv: Conversation, n: Int) = pushRoll(stat(conv), 10.0, 2.0, true, n)
        fun gap(conv: Conversation, g: Double) = pushGap(stat(conv), g)
    }

    private val host = Host()
    private val conv = Conversation(
        id = "c1", title = "t", providerId = "p1", model = "m1",
        createdAt = 1L, updatedAt = 1L, messages = emptyList(),
    )

    @Test
    fun firstPrefixIsRecordedButNotCountedAsAChange() {
        val p = Probe(host)
        p.stat(conv)["prefixChanges"] = 0.0
        p.prefix(conv, "system")
        assertEquals(0.0, p.stat(conv)["prefixChanges"].d(), 0.0)
        assertEquals("system".hashCode().toString(16), Js.str(p.stat(conv)["prefix_hash"]))
        assertEquals(6, Js.i32(p.stat(conv)["prefixLen"]))
    }

    @Test
    fun changedPrefixCountsOnceAndKeepsFirstDiff() {
        val p = Probe(Host())
        p.prefix(conv, "aaa")
        p.prefix(conv, "bbb")
        assertEquals(1.0, p.stat(conv)["prefixChanges"].d(), 0.0)
        assertTrue(p.stat(conv)["lastDiff"].truthy())
        assertEquals("aaa", Js.str((p.stat(conv)["lastDiff"] as JObj)["a"]))
    }

    @Test
    fun samePrefixDoesNotCountAgain() {
        val p = Probe(Host())
        p.prefix(conv, "same")
        p.prefix(conv, "same")
        p.prefix(conv, "same")
        assertEquals(0.0, p.stat(conv)["prefixChanges"].d(), 0.0)
    }

    @Test
    fun firstBodyHasNoLcpAndSecondComparesWithIt() {
        val p = Probe(Host())
        assertNull(p.body(conv, "abcd"))
        assertEquals(1.0, p.body(conv, "abzz")!!, 0.0001)
    }

    @Test
    fun rollAndGapWindowsAreCapped() {
        val p = Probe(Host())
        repeat(12) { p.roll(conv, 5) }
        assertEquals(5, p.stat(conv)["roll"].a()!!.size)
        repeat(60) { p.gap(conv, it.toDouble()) }
        assertEquals(50, p.stat(conv)["gaps"].a()!!.size)
        assertEquals(59.0, Js.toNumber(p.stat(conv)["gaps"].a()!!.last()), 0.0)
    }

    @Test
    fun cachePluginsDeclareTheSameSlotAndStayPerConversation() {
        val p = Probe(Host())
        assertTrue(p.perConversation)
        assertEquals(listOf(Slot.S5_BODY), p.slots())
        assertTrue(p.headerBadges(conv).isEmpty())
    }
}
