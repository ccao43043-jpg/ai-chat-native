package com.aichat.app.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 搜索就绪双源修复的纯函数测试：只验证「算得对」，不碰 KeyVault 与文件。 */
class SearchKeySyncTest {

    private fun hasKeys(o: JsonObject): Map<String, Boolean> =
        (o["hasKeys"] as JsonObject).mapValues { (it.value as JsonPrimitive).booleanOrNull ?: false }

    @Test
    fun fillsMissingHasKeys() {
        val search = JsonObject(mapOf("provider" to JsonPrimitive("tavily"), "count" to JsonPrimitive(5)))
        val out = SearchKeySync.merged(search) { it == "tavily" }
        assertNotNull(out)
        val hk = hasKeys(out!!)
        assertEquals(true, hk["tavily"])
        assertEquals(false, hk["bocha"])
        assertEquals(false, hk["brave"])
        assertEquals(false, hk["searxng"])
        // 其它键原样保留
        assertEquals("tavily", (out["provider"] as JsonPrimitive).content)
        assertEquals("5", (out["count"] as JsonPrimitive).content)
    }

    @Test
    fun noChangeReturnsNull() {
        val search = JsonObject(
            mapOf(
                "hasKeys" to JsonObject(
                    mapOf(
                        "tavily" to JsonPrimitive(true),
                        "bocha" to JsonPrimitive(false),
                        "brave" to JsonPrimitive(false),
                        "searxng" to JsonPrimitive(false),
                    ),
                ),
            ),
        )
        assertNull(SearchKeySync.merged(search) { it == "tavily" })
    }

    @Test
    fun clearsStaleTrueWhenKeyGone() {
        val search = JsonObject(
            mapOf(
                "hasKeys" to JsonObject(
                    mapOf(
                        "tavily" to JsonPrimitive(true),
                        "bocha" to JsonPrimitive(true),
                        "brave" to JsonPrimitive(false),
                        "searxng" to JsonPrimitive(false),
                    ),
                ),
            ),
        )
        val out = SearchKeySync.merged(search) { it == "bocha" }
        assertNotNull(out)
        val hk = hasKeys(out!!)
        assertEquals(false, hk["tavily"])
        assertEquals(true, hk["bocha"])
    }

    @Test
    fun keepsUnknownEntries() {
        val search = JsonObject(
            mapOf(
                "hasKeys" to JsonObject(
                    mapOf(
                        "tavily" to JsonPrimitive(true),
                        "x-custom" to JsonPrimitive(true),
                    ),
                ),
            ),
        )
        val out = SearchKeySync.merged(search) { it == "tavily" }
        assertNotNull(out)
        val hk = hasKeys(out!!)
        assertEquals(true, hk["x-custom"])
        assertEquals(true, hk["tavily"])
        assertEquals(false, hk["bocha"])
    }
}
