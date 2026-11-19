package com.aichat.app.net

import com.aichat.app.domain.ChatOptions
import com.aichat.app.domain.WireMessage
import com.aichat.app.domain.model.ProviderConfig
import com.aichat.app.domain.model.ProviderType
import com.aichat.app.domain.model.Role
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思考等级（auto/off/low/medium/high）。
 *
 * 三条硬约束：
 *  1. `auto`（默认）必须让请求体**逐字节**与旧版一致 —— 缓存前缀、golden 夹具都靠这个；
 *  2. 认不出「会思考」的模型一律不下发（否则部分网关直接 400，比档位不生效更糟）；
 *  3. 同一档位在不同线上落到不同字段：OpenRouter 走 `reasoning`、原生 Gemini 走
 *     `generationConfig.thinkingConfig`、其余 OpenAI 兼容走 `reasoning_effort`。
 */
class ThinkingLevelTest {

    private fun or(model: String = "google/gemini-3.8-flash-20260902") = ProviderConfig(
        "or", "openrouter", "OR", ProviderType.OPENAI, "https://openrouter.ai/api/v1", listOf(model))

    private fun openai(model: String = "o3-mini") = ProviderConfig(
        "oai", "openai", "OpenAI", ProviderType.OPENAI, "https://api.openai.com/v1", listOf(model))

    private fun qwen(model: String = "qwen3-max") = ProviderConfig(
        "qw", "qwen", "百炼", ProviderType.OPENAI, "https://dashscope.aliyuncs.com/compatible-mode/v1", listOf(model))

    private fun gemini(model: String = "gemini-2.5-flash") = ProviderConfig(
        "gm", "gemini", "Gemini", ProviderType.GEMINI, "https://generativelanguage.googleapis.com/v1beta", listOf(model))

    private fun oaBody(p: ProviderConfig, model: String, level: String?, thinking: Boolean? = null): JsonObject =
        OpenAiCompat.buildBody(p, model, listOf(WireMessage(Role.USER, "hi")), "",
            ChatOptions(stream = false, thinkingLevel = level, thinking = thinking))

    private fun gmBody(p: ProviderConfig, model: String, level: String?): JsonObject =
        GeminiCompat.buildBody(p, model, listOf(WireMessage(Role.USER, "hi")), "",
            ChatOptions(stream = false, thinkingLevel = level))

    // ---------------- 契约 1：auto 不动请求体 ----------------

    @Test
    fun autoIsByteIdenticalToSendingNoLevel() {
        val p = or()
        assertEquals(Js.stringify(oaBody(p, "google/gemini-3.8-flash-20260902", null)),
            Js.stringify(oaBody(p, "google/gemini-3.8-flash-20260902", "auto")))
        assertFalse("auto 不该写 reasoning", oaBody(p, "google/gemini-3.8-flash-20260902", "auto").containsKey("reasoning"))
    }

    @Test
    fun unknownLevelFallsBackToAuto() {
        assertEquals("auto", ProviderRules.normalizeThinkingLevel("ULTRA"))
        assertEquals("auto", ProviderRules.normalizeThinkingLevel(""))
        assertEquals("auto", ProviderRules.normalizeThinkingLevel(null))
        assertFalse(oaBody(or(), "google/gemini-3.8-flash-20260902", "ULTRA").containsKey("reasoning"))
    }

    // ---------------- 契约 2：OpenRouter 统一 reasoning ----------------

    @Test
    fun openRouterSendsEffort() {
        listOf("low", "medium", "high").forEach { lv ->
            val b = oaBody(or(), "google/gemini-3.8-flash-20260902", lv)
            assertEquals(lv, b["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun openRouterOffDisablesReasoning() {
        val r = oaBody(or(), "google/gemini-3.8-flash-20260902", "off")["reasoning"]!!.jsonObject
        assertEquals("false", r["enabled"]!!.jsonPrimitive.content)
        assertFalse("off 是开关，不是 effort", r.containsKey("effort"))
    }

    @Test
    fun nonReasoningModelIsLeftAlone() {
        // deepseek-chat 不思考，任何档位都不能往它身上塞 reasoning —— 白送 400
        assertFalse(oaBody(or("deepseek/deepseek-chat"), "deepseek/deepseek-chat", "high").containsKey("reasoning"))
        assertTrue("但 gemini 3 认",
            oaBody(or(), "google/gemini-3.8-flash-20260902", "high").containsKey("reasoning"))
    }

    // ---------------- 契约 3：其余 OpenAI 兼容走 reasoning_effort ----------------

    @Test
    fun openAiReasoningModelGetsEffort() {
        assertEquals("medium", oaBody(openai(), "o3-mini", "medium")["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun openAiOffSendsNothing() {
        // 那边没有可靠的「关闭」字段，硬发 minimal 会被老模型 400 —— 宁可什么都不发
        assertFalse(oaBody(openai(), "o3-mini", "off").containsKey("reasoning_effort"))
        assertFalse(oaBody(openai(), "gpt-4o", "high").containsKey("reasoning_effort"))
    }

    @Test
    fun qwenMapsToItsOwnSwitch() {
        assertEquals("true", oaBody(qwen(), "qwen3-max", "high")["enable_thinking"]!!.jsonPrimitive.content)
        assertEquals("false", oaBody(qwen(), "qwen3-max", "off")["enable_thinking"]!!.jsonPrimitive.content)
        // auto 时不接管 provider 上的老开关
        val inherited = oaBody(qwen(), "qwen3-max", null, thinking = true)
        assertEquals("true", inherited["enable_thinking"]!!.jsonPrimitive.content)
    }

    // ---------------- 契约 4：原生 Gemini ----------------

    @Test
    fun gemini3UsesThinkingLevel() {
        val b = gmBody(gemini("gemini-3.8-flash"), "gemini-3.8-flash", "high")
        val tc = b["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
        assertEquals("high", tc["thinkingLevel"]!!.jsonPrimitive.content)
        // Gemini 3 关不掉思考：off 退化成 low，而不是 thinkingBudget=0
        val off = gmBody(gemini("gemini-3.8-flash"), "gemini-3.8-flash", "off")
        assertEquals("low", off["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content)
    }

    @Test
    fun gemini25UsesThinkingBudget() {
        fun budget(lv: String?): String? =
            gmBody(gemini(), "gemini-2.5-flash", lv)["generationConfig"]?.jsonObject
                ?.get("thinkingConfig")?.jsonObject?.get("thinkingBudget")?.jsonPrimitive?.content
        assertEquals("0", budget("off"))
        assertEquals("1024", budget("low"))
        assertEquals("8192", budget("medium"))
        assertEquals("24576", budget("high"))
        assertNull("auto 不该有 thinkingConfig", budget("auto"))
    }

    @Test
    fun geminiAutoOmitsThinkingConfigEntirely() {
        // auto 时连 generationConfig 都不该凭空冒出来（这里没温度、没 maxTokens，本就该没有这个键）
        val b = gmBody(gemini(), "gemini-2.5-flash", null)
        val gc = b["generationConfig"]?.jsonObject
        assertTrue("auto 不该写出 generationConfig", gc == null || !gc.containsKey("thinkingConfig"))
    }
}