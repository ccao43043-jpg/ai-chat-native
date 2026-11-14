package com.aichat.app.plugins.rp

import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.js.jobj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 世界书文本编辑：解析、哈希、冻结标记与重建（对齐 rpcache.js 的 setLore / 手动重建） */
class RpLoreEditTest {

    private fun emptyState(): JObj = RpState.normalizeState(jobj())

    // 旧版格式：`## 名字 | 触发词（* = 常驻）| attr=..;attr=..`
    private val textA = """
        ## 港口 | * | priority=7
        灯塔每十五秒转一圈红光。

        ## 潮汐 | 潮汐, 大潮 | depth=2
        每月十五大潮，船要在潮头前回港。
    """.trimIndent()

    @Test
    fun setLoreParsesEntriesAndRemembersHash() {
        val next = RpLoreCore.setLore(emptyState(), textA, 1L)
        val lore = RpLoreCore.loreOf(next)
        assertEquals(2, lore.size)
        assertEquals("港口", Js.str(lore[0]["name"]))
        assertTrue("`*` 应解析成常驻条目", lore[0]["mode"] == "constant")
        assertEquals(textA, Js.str(next["loreText"]))
        assertNotNull(next["loreTextHash"])
        // 第一次设置不算"改过"
        assertNull(next["loreEditedWhileFrozen"])
    }

    @Test
    fun editingWhileFrozenMarksIt() {
        val withFrozen = LinkedHashMap<String, Any?>(RpLoreCore.setLore(emptyState(), textA, 1L)).also {
            it["loreFired"] = arrayListOf<Any?>(jobj("name" to "港口", "anchorId" to "u1"))
        }
        val next = RpLoreCore.setLore(withFrozen, textA + "\n\n[新条目]\n后加的。", 2L)
        assertTrue("改过文本且已冻结时应打标记", next["loreEditedWhileFrozen"] == true)
        assertEquals(1, RpLoreCore.firedOf(next).size)
    }

    @Test
    fun rebuildClearsFrozenAndPendingFlag() {
        val withFrozen = LinkedHashMap<String, Any?>(RpLoreCore.setLore(emptyState(), textA, 1L)).also {
            it["loreFired"] = arrayListOf<Any?>(jobj("name" to "港口"))
            it["loreEditedWhileFrozen"] = true
            it["loreNeedsRebuild"] = true
        }
        val next = RpLoreCore.rebuildFrozen(withFrozen)
        assertEquals(0, RpLoreCore.firedOf(next).size)
        assertEquals(false, next["loreEditedWhileFrozen"])
        assertEquals(false, next["loreNeedsRebuild"])
        // 条目本身不动
        assertEquals(2, RpLoreCore.loreOf(next).size)
    }

    @Test
    fun emptyTextClearsEntries() {
        val st = RpLoreCore.setLore(emptyState(), textA, 1L)
        val next = RpLoreCore.setLore(st, "", 2L)
        assertEquals(0, RpLoreCore.loreOf(next).size)
        assertFalse(RpLoreCore.constantLoreText(next).contains("[港口]"))
    }
}
