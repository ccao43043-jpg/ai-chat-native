package com.aichat.app.plugins.rp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RpMemoryOpsTest {

    private fun empty() = JsonObject(emptyMap())

    @Test
    fun bucketKeyUsesPersonaWhenPresent() {
        assertEquals("conv:c1", RpMemoryOps.bucketKey("c1", null))
        assertEquals("conv:c1", RpMemoryOps.bucketKey("c1", ""))
        assertEquals("persona:小明", RpMemoryOps.bucketKey("c1", "小明"))
    }

    @Test
    fun addThenListRoundTrip() {
        val key = RpMemoryOps.bucketKey("c1", null)
        var doc = RpMemoryOps.add(empty(), key, "fact", "海边有个渔村", listOf("渔村", "海边"), 1000L)
        doc = RpMemoryOps.add(doc, key, "detail", "灯塔是红色的", listOf("灯塔"), 2000L)

        val rows = RpMemoryOps.sorted(RpMemoryOps.entries(doc, key))
        assertEquals(2, rows.size)
        assertEquals("事实", RpMemoryCore.TYPE_CN[(rows[0]["type"] as JsonPrimitive).content])
        assertEquals("海边有个渔村", (rows[0]["text"] as JsonPrimitive).content)
        val kws = (rows[0]["keywords"] as kotlinx.serialization.json.JsonArray).map { (it as JsonPrimitive).content }
        assertEquals(listOf("渔村", "海边"), kws)
        assertEquals("1", (rows[0]["seq"] as JsonPrimitive).content)
        assertEquals("2", (rows[1]["seq"] as JsonPrimitive).content)
    }

    @Test
    fun pinAndDisableAndRemove() {
        val key = RpMemoryOps.bucketKey("c1", null)
        var doc = RpMemoryOps.add(empty(), key, "fact", "a", emptyList(), 1L)
        val id = (RpMemoryOps.entries(doc, key)[0]["id"] as JsonPrimitive).content

        doc = RpMemoryOps.setPinned(doc, key, id, true)
        assertTrue((RpMemoryOps.entries(doc, key)[0]["pinned"] as JsonPrimitive).content == "true")

        doc = RpMemoryOps.setDisabled(doc, key, id, true)
        assertTrue((RpMemoryOps.entries(doc, key)[0]["disabled"] as JsonPrimitive).content == "true")

        doc = RpMemoryOps.remove(doc, key, id)
        assertTrue(RpMemoryOps.entries(doc, key).isEmpty())
    }

    @Test
    fun unknownKeyOrIdIsNoOp() {
        val doc = RpMemoryOps.add(empty(), "conv:c1", "fact", "a", emptyList(), 1L)
        assertEquals(doc, RpMemoryOps.setPinned(doc, "conv:other", "x", true))
        assertEquals(doc, RpMemoryOps.setDisabled(doc, "conv:c1", "nope", true))
        assertEquals(doc, RpMemoryOps.remove(doc, "conv:c1", "nope"))
    }

    @Test
    fun docSurvivesJsonRoundTrip() {
        val key = RpMemoryOps.bucketKey("c9", "阿离")
        val doc = RpMemoryOps.add(empty(), key, "relation", "同门", listOf("师兄"), 5L)
        val text = Json.encodeToString(JsonObject.serializer(), doc)
        val back = Json.parseToJsonElement(text).jsonObject
        val row = back[key]!!.jsonObject["entries"]!!.jsonArray[0].jsonObject
        assertFalse((row["pinned"] as JsonPrimitive).content == "true")
        assertEquals("同门", (row["text"] as JsonPrimitive).content)
    }
}
