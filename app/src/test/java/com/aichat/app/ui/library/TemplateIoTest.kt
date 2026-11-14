package com.aichat.app.ui.library

import com.aichat.app.domain.model.PromptTemplate
import com.aichat.app.plugins.js.Js
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模板导入导出。对照旧版 `templates.js` 的 exportJSON / importJSON：
 * 导出带 format/version，导入兼容裸数组与 {templates:[…]}，空内容跳过、名+内容重复跳过、
 * 导入的一律变成新自定义模板（builtin = false），id 由调用方生成（这里传固定种子便于断言）。
 */
class TemplateIoTest {

    private fun tpl(name: String, content: String, tags: List<String> = emptyList(), pinned: Boolean = false) =
        PromptTemplate(id = "t_$name", name = name, emoji = "📝", content = content, tags = tags,
            pinned = pinned, builtin = false, skill = false)

    @Test
    fun exportCarriesFormatAndVersion() {
        val json = TemplateIo.exportJson(listOf(tpl("总结", "请总结：", listOf("常用"), pinned = true)))
        val obj = Js.parse(json) as Map<*, *>
        assertEquals(TemplateIo.FORMAT, obj["format"])
        assertEquals(TemplateIo.VERSION.toDouble(), (obj["version"] as Number).toDouble(), 0.0)
        val list = obj["templates"] as List<*>
        assertEquals(1, list.size)
        val one = list[0] as Map<*, *>
        assertEquals("总结", one["name"])
        assertEquals("请总结：", one["content"])
        assertEquals(listOf("常用"), (one["tags"] as List<*>).map { it.toString() })
        assertEquals(true, one["pinned"])
    }

    @Test
    fun importAcceptsBareArrayAndWrappedObject() {
        val bare = """[{"name":"A","content":"a"}]"""
        val wrapped = """{"format":"ai-chat-templates","version":1,"templates":[{"name":"B","content":"b"}]}"""
        assertEquals(setOf("A"), TemplateIo.importJson(bare, emptyList()).list.map { it.name }.toSet())
        assertEquals(setOf("B"), TemplateIo.importJson(wrapped, emptyList()).list.map { it.name }.toSet())
    }

    @Test
    fun importSkipsEmptyContentAndDuplicates() {
        val existing = listOf(tpl("总结", "请总结："))
        val text = """[
            {"name":"空","content":"  "},
            {"name":"总结","content":"请总结："},
            {"name":"新的","content":"新内容"}
        ]"""
        val r = TemplateIo.importJson(text, existing)
        assertEquals(1, r.added)
        assertEquals(2, r.skipped)
        assertEquals(listOf("总结", "新的"), r.list.map { it.name })
    }

    @Test
    fun importedTemplatesBecomeCustomAndGetFreshIds() {
        val text = """[{"name":"X","content":"x","tags":"a、b","pinned":true}]"""
        val r = TemplateIo.importJson(text, emptyList())
        val one = r.list.single()
        assertTrue("导入的模板不能是内置标记", !one.builtin)
        assertEquals("id 必须由原生重新生成，不能沿用旧版", false, one.id.isBlank())
        assertEquals(listOf("a", "b"), one.tags)
        assertTrue(one.pinned)
    }
}
