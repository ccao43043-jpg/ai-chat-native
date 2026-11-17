package com.aichat.app.plugins.rp

import com.aichat.app.plugins.js.JObj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 世界书解析：空白容错 + 「只有标题不算条目」。
 *
 * 实测事故：在面板里手输 `## Harbor | boats,harbor` 保存后 `lore` 是空数组 ——
 * 因为 `parseLore` 末尾有 `if (content.isNotEmpty())`，只有标题没有正文的条目会被静默丢弃，
 * 而界面还报「已保存」。行首缩进（从文档粘过来）同样会让 `^##` 匹配不上。
 */
class LoreWhitespaceTest {

    private fun entries(text: String): List<JObj> = Lore.parseLore(text).filterIsInstance<JObj>()

    @Test
    fun plainEntryParses() {
        val arr = entries("## Harbor | boats,harbor\nThe harbor is cold in November.")
        assertEquals(listOf("Harbor"), arr.map { it["name"] })
        assertEquals(listOf("boats", "harbor"), arr[0]["keys"] as List<*>)
    }

    @Test
    fun leadingSpacesAreTolerated() {
        assertEquals(listOf("Harbor"), entries("  ## Harbor | boats,harbor\n  Cold.").map { it["name"] })
        assertEquals(listOf("Harbor"), entries("\t## Harbor | boats,harbor\n\tCold.").map { it["name"] })
    }

    @Test
    fun indentedMultiEntryStillParses() {
        val text = "  ## Harbor | boats,harbor\n  The harbor is cold in November.\n   ## Chapel | chapel\n   Nobody goes there at night.\n"
        val arr = entries(text)
        assertEquals(listOf("Harbor", "Chapel"), arr.map { it["name"] })
        assertTrue((arr[0]["content"] as String).contains("The harbor is cold in November."))
    }

    @Test
    fun headingWithoutBodyIsDropped() {
        assertEquals(0, entries("## Harbor | boats,harbor").size)
    }

    @Test
    fun textWithoutHeadingYieldsNoEntries() {
        assertEquals(0, entries("just some prose without a heading").size)
    }
}