package com.aichat.app.plugins.rp

import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.jobj
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 与旧版 JS 的 golden 对照（`app/src/test/resources/golden/rpcache/core.json`，
 * 由 `tools/rpcache-golden/gen-golden.cjs` 从 `www/rpcache.js` 生成）。
 *
 * 这些断言是「请求字节」级别的：system 文本、分段顺序、末轮用户消息的拼接方式，
 * 一旦漂移，缓存前缀就废了，所以用逐字比较而不是「大致相等」。
 */
class RpCacheCoreTest {

    private val golden: JsonObject by lazy {
        val stream = javaClass.getResourceAsStream("/golden/rpcache/core.json")
            ?: error("缺少 golden：app/src/test/resources/golden/rpcache/core.json")
        Json.parseToJsonElement(stream.bufferedReader().readText()).jsonObject
    }

    private fun case(name: String): JsonObject =
        golden["cases"]!!.jsonObject[name]!!.jsonObject

    private fun str(o: JsonObject, key: String): String = o[key]!!.jsonPrimitive.content
    private fun int(o: JsonObject, key: String): Int = o[key]!!.jsonPrimitive.content.toInt()

    private fun stateWith(vararg blocks: Pair<String, String>): JObj {
        var st = RpState.newState()
        blocks.forEach { (id, content) ->
            // JS 的 setBlock 返回 {state, changed}，这里保持一致
            st = RpState.setBlock(st, id, content, 1_700_000_000_000L)["state"] as JObj
        }
        return st
    }

    private val base = "你是一位角色扮演助手。"

    private fun f2State(): JObj {
        val st = stateWith(
            "world" to "蒸汽朋克港城，永夜，齿轮与锅炉。",
            "character" to "薇拉，机械师，19 岁，说话简短。",
            "rules" to "NPC 不能说破玩家的心思。",
            "memory" to "玩家在第三章救过薇拉。",
            "delta" to "薇拉刚刚提到她怕水。",
            "state" to "时间：第 4 天清晨｜地点：锅炉房｜情绪：戒备",
        )
        st["chapters"] = listOf(
            jobj("no" to 1, "content" to "第一章：玩家进城，与薇拉在废料场相遇。", "coveredIds" to listOf("m1", "m2")),
            jobj("no" to 2, "content" to "第二章：两人一起修好了升降机。", "coveredIds" to listOf("m3", "m4")),
        )
        return st
    }

    private val hist = listOf(
        jobj("id" to "m5", "role" to "user", "content" to "你会游泳吗？"),
        jobj("id" to "m6", "role" to "assistant", "content" to "薇拉皱眉：“在水边长大的人，未必喜欢水。”"),
        jobj("id" to "m7", "role" to "user", "content" to "那我们绕开码头走。"),
    )
    private val input = jobj("id" to "m8", "role" to "user", "content" to "你怕水是因为那次事故吗？")

    @Test
    fun emptyStateMatchesJs() {
        val g = case("f1")
        val st = RpState.newState()
        assertEquals(str(g, "system"), RpCacheCore.buildSystem(st, base))
        assertEquals(str(g, "stablePrefix"), RpCacheCore.systemStablePrefix(st, base))
        assertEquals(str(g, "finalUser"), RpCacheCore.finalUserContent(st, "你好"))
        assertEquals(1, RpCacheCore.buildSystemSegments(st, base).size)

        val tokens = g["tokens"]!!.jsonObject
        assertEquals(int(tokens, "zh"), RpCacheCore.estimateTokens("你好，世界"))
        assertEquals(int(tokens, "en"), RpCacheCore.estimateTokens("hello world"))
        assertEquals(int(tokens, "mix"), RpCacheCore.estimateTokens("第 4 天清晨，锅炉房 hello"))
        assertEquals(int(tokens, "empty"), RpCacheCore.estimateTokens(""))

        assertEquals(str(g, "history"), RpCacheCore.serializeHistory(hist, null))
    }

    @Test
    fun fullStateMatchesJs() {
        val g = case("f2")
        val st = f2State()

        assertEquals(str(g, "staticText"), RpCacheCore.staticText(st, base))
        assertEquals(str(g, "summaryText"), RpCacheCore.summaryText(st))
        assertEquals(str(g, "memoryText"), RpCacheCore.memoryText(st))
        assertEquals(str(g, "deltaText"), RpCacheCore.deltaText(st))
        assertEquals(str(g, "stateText"), RpCacheCore.stateText(st))
        assertEquals(str(g, "system"), RpCacheCore.buildSystem(st, base))
        assertEquals(str(g, "stablePrefix"), RpCacheCore.systemStablePrefix(st, base))
        assertEquals(str(g, "finalUser"), RpCacheCore.finalUserContent(st, "你怕水是因为那次事故吗？"))

        val wantSegs = g["segments"]!!.jsonArray.map {
            val o = it.jsonObject
            listOf(str(o, "layer"), str(o, "kind"), str(o, "title"), str(o, "gl"))
        }
        assertEquals(wantSegs, RpCacheCore.buildSystemSegments(st, base).map { listOf(it.layer, it.kind, it.title, it.gl) })

        assertEquals(int(g, "estimateItems"), RpCacheCore.estimateItems(hist + input))
        assertEquals(str(g, "history"), RpCacheCore.serializeHistory(hist, 2))

        val assembled = g["assembled"]!!.jsonObject
        assertEquals(str(assembled, "system"), RpCacheCore.buildSystem(st, base))
        val wantMsgs = assembled["messages"]!!.jsonArray.map {
            val o = it.jsonObject
            str(o, "role") to str(o, "content")
        }
        val built = RpCacheCore.assemble(RpCacheCore.buildSystem(st, base), hist, input, st)
        assertEquals(wantMsgs, built.messages)

        val wantTokens = assembled["tokens"]!!.jsonObject
        val got = RpCacheCore.layerTokens(st, base, hist + input)
        listOf("static", "memory", "summary", "history", "delta", "state", "input").forEach { k ->
            assertEquals("layer $k", int(wantTokens, k), (got[k] as? Number)?.toInt())
        }
    }

    @Test
    fun capabilityAndBudgetMatchJs() {
        val list = golden["cases"]!!.jsonObject["capability"]!!.jsonArray
        assertTrue("golden 里至少要有一批服务商组合", list.size > 20)
        list.forEach { el ->
            val o = el.jsonObject
            val want = o["cap"]!!.jsonObject
            val info = o["info"]!!.jsonObject
            val got = RpCacheCore.capability(
                presetId = str(o, "provider"),
                baseUrl = str(o, "baseUrl"),
                type = str(o, "type"),
                model = str(o, "model"),
            )
            val tag = "${str(o, "provider")}/${str(o, "model")}"
            assertEquals("vendor for $tag", str(want, "vendor"), got.vendor)
            assertEquals("kind for $tag", str(want, "kind"), got.kind)
            assertEquals("auto for $tag", want["auto"]!!.jsonPrimitive.content.toBoolean(), got.auto)
            val ci = RpCacheCore.contextInfo(str(o, "model"))
            assertEquals("window for $tag", int(info, "window"), ci.window)
            assertEquals("tier for $tag", int(info, "tier"), ci.tier)
        }
    }

    @Test
    fun budgetMatchesJs() {
        val list = golden["cases"]!!.jsonObject["budget"]!!.jsonArray
        list.forEach { el ->
            val o = el.jsonObject
            val model = str(o, "model")
            val want = o["budget"]!!.jsonObject
            val got = RpCacheCore.autoBudget(RpCacheCore.capability("deepseek", null, "openai", model), model)
            assertEquals("max_context for $model", int(want, "max_context"), got.maxContext)
            assertEquals("output_reserve for $model", int(want, "output_reserve"), got.outputReserve)
            assertEquals("safety_margin for $model", int(want, "safety_margin"), got.safetyMargin)
            assertEquals("trigger_tokens for $model", int(want, "trigger_tokens"), got.triggerTokens)
            assertEquals("chapter_cap_tokens for $model", int(want, "chapter_cap_tokens"), got.chapterCap)
            assertEquals("window for $model", int(want, "window"), got.window)
            assertEquals("source for $model", str(want, "source"), got.source)
        }
    }

    @Test
    fun baseUrlDecidesVendorWhenPresetMissing() {
        // 旧版按「预设 / 接口地址 / 模型名」三条线索判定；只给地址也要能认出来
        assertEquals("deepseek", RpCacheCore.capability(null, "https://api.deepseek.com/v1", "openai", "whatever").vendor)
        assertEquals("qwen", RpCacheCore.capability(null, "https://dashscope.aliyuncs.com/compatible-mode/v1", "openai", "whatever").vendor)
        assertEquals("gemini", RpCacheCore.capability(null, "https://openrouter.ai/api/v1", "openai", "google/gemini-2.0-flash").vendor)
        assertEquals("other", RpCacheCore.capability(null, "http://10.0.2.2:8787/v1", "openai", "mock-1").vendor)
    }

    @Test
    fun oldestBatchKeepsRecentTurns() {
        assertEquals(emptyList<JObj>(), RpCacheCore.oldestBatch(hist, 5))
        assertEquals(hist.take(2), RpCacheCore.oldestBatch(hist, 1))
    }

    @Test
    fun segmentsJoinBackToSystem() {
        val st = f2State()
        val segs = RpCacheCore.buildSystemSegments(st, base)
        assertEquals(RpCacheCore.buildSystem(st, base), segs.joinToString("\n\n") { it.text })
        // 稳定前缀必须是 system 的前缀（否则第二个断点没意义）
        assertTrue(RpCacheCore.buildSystem(st, base).startsWith(RpCacheCore.systemStablePrefix(st, base)))
    }

    @Test
    fun jsonRoundTripOfHistory() {
        val json = RpCacheCore.serializeHistory(listOf(hist[1]), null)
        val arr = Json.parseToJsonElement(json).jsonArray
        assertEquals(1, arr.size)
        val row = arr[0].jsonArray
        assertEquals("assistant", row[0].jsonPrimitive.content)
        assertEquals(hist[1]["content"], row[1].jsonPrimitive.content)
        assertEquals(0, (row[2] as JsonArray).size)
    }

    @Test
    fun calibDefaultsToOneAndIsPerModel() {
        val st = jobj()
        val meta = jobj("provider" to "deepseek", "model" to "m1")
        assertEquals(1.0, RpCacheCore.calibOf(st, meta), 0.0)

        val once = RpCacheCore.calibrate(st, 1000, 1500L, meta)
        assertEquals("首轮 0.7*1 + 0.3*1.5", 1.15, (once["calib"] as Number).toDouble(), 0.0001)
        assertEquals(1.15, RpCacheCore.calibOf(once, meta), 0.0001)
        // 另一个模型不继承这个系数（跨模型污染是旧版明确要避免的）
        assertEquals(1.0, RpCacheCore.calibOf(once, jobj("provider" to "deepseek", "model" to "m2")), 0.0)
    }

    @Test
    fun calibrateUsesExponentialAverageAndClampsRatio() {
        val meta = jobj("provider" to "p", "model" to "m")
        val once = RpCacheCore.calibrate(jobj(), 1000, 2000L, meta)
        assertEquals("首轮 ratio 夹在 2.0", 1.3, (once["calib"] as Number).toDouble(), 0.0001)

        val twice = RpCacheCore.calibrate(once, 1000, 2000L, meta)
        assertEquals("第二轮 0.7*1.3 + 0.3*2.0", 1.51, (twice["calib"] as Number).toDouble(), 0.0001)
        assertEquals("计数要累加", 2, (twice["calibN"] as Number).toInt())
        val nBy = twice["calibNBy"] as Map<*, *>
        assertEquals(2, (nBy["p|m|text"] as Number).toInt())

        // 离谱的比例被夹到 2.5，不会把系数带飞
        val crazy = RpCacheCore.calibrate(jobj(), 1000, 100000L, meta)
        assertEquals(1.45, (crazy["calib"] as Number).toDouble(), 0.0001)
    }

    @Test
    fun calibrateSkipsTinyEstimates() {
        val meta = jobj("provider" to "p", "model" to "m")
        val st = jobj("calib" to 1.0)
        val same = RpCacheCore.calibrate(st, 120, 900L, meta)
        assertTrue("估算 ≤200 不校准（固定开销占比过高会带偏系数）", same === st)
        val noPrompt = RpCacheCore.calibrate(st, 5000, 0L, meta)
        assertTrue("没有真实 token 也不校准", noPrompt === st)
    }
}
