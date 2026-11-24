package com.aichat.app.net

import com.aichat.app.domain.model.ProviderConfig
import com.aichat.app.domain.model.ProviderType
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 截断可见性（v1.5.2）：旧版 Gemini 不读 finishReason、OpenRouter 流内嵌 error 被吞、推理型模型默认
 * 上限 8192 被思考 token 吃光 —— 三种情况下用户都只看到「半句话」却没有任何提示。
 */
class TruncationTest {
    private fun parser(type: String, sse: String): List<StreamDelta> {
        val p = StreamParser(type)
        val out = mutableListOf<StreamDelta>()
        out += p.push(sse)
        out += p.end()
        return out
    }
    private fun reasons(ds: List<StreamDelta>) = ds.filterIsInstance<StreamDelta.Finish>().map { it.reason }
    private fun texts(ds: List<StreamDelta>) = ds.filterIsInstance<StreamDelta.Text>().map { it.text }

    @Test fun geminiMaxTokensBecomesLength() {
        val ds = parser("gemini", "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"半截\"}]},\"finishReason\":\"MAX_TOKENS\"}]}\n\n")
        assertEquals(listOf("半截"), texts(ds))
        assertEquals(listOf("length"), reasons(ds))
        assertFalse(FinishReasons.isNormal(reasons(ds)[0]))
    }

    @Test fun geminiStopBecomesStop() {
        val ds = parser("gemini", "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"完\"}]},\"finishReason\":\"STOP\"}]}\n\n")
        assertEquals(listOf("stop"), reasons(ds))
        assertTrue(FinishReasons.isNormal(reasons(ds)[0]))
    }

    @Test fun geminiSafetyBlockIsNotNormal() {
        val ds = parser("gemini", "data: {\"candidates\":[{\"finishReason\":\"SAFETY\"}]}\n\n")
        assertEquals(listOf(FinishReasons.SAFETY_BLOCKED), reasons(ds))
        assertFalse(FinishReasons.isNormal(reasons(ds)[0]))
        assertTrue(FinishReasons.isSafetyBlocked(reasons(ds)[0]))
    }

    /** OpenRouter 流中途报错时把 error 嵌在 choices[0] 里（顶层没有 error），旧版直接吞掉 */
    @Test fun openRouterNestedChoiceErrorIsReported() {
        val ds = parser("openai", "data: {\"choices\":[{\"delta\":{},\"error\":{\"message\":\"upstream overloaded\"}}]}\n\n")
        assertEquals(listOf("upstream overloaded"), ds.filterIsInstance<StreamDelta.Error>().map { it.text })
    }

    @Test fun openAiMaxTokensAliasIsNormalized() {
        val ds = parser("openai", "data: {\"choices\":[{\"delta\":{\"content\":\"a\"},\"finish_reason\":\"MAX_TOKENS\"}]}\n\n")
        assertEquals(listOf("length"), reasons(ds))
    }

    @Test fun normalReasonClassification() {
        for (r in listOf("stop", "end_turn", "eos", "tool_calls", "function_call", "stop_sequence", "", null)) {
            assertTrue("$r 应算正常结束", FinishReasons.isNormal(r))
        }
        for (r in listOf("length", "incomplete", "error", "content_filter", "safety", "recitation")) {
            assertFalse("$r 应算截断", FinishReasons.isNormal(r))
        }
    }

    @Test fun safetyAliasesMapToOneOutcome() {
        for (r in listOf(
            "PROHIBITED_CONTENT",
            "content_filter",
            "SAFETY",
            "blocked",
            "safety_blocked",
            "content_policy_violation",
            "harmful_content",
        )) {
            assertEquals("$r 必须归一到安全拦截", FinishReasons.SAFETY_BLOCKED, FinishReasons.normalize(r))
            assertTrue("$r 必须识别为安全拦截", FinishReasons.isSafetyBlocked(r))
        }
    }

    // ---------------- 默认输出上限：按模型分层，而不是全局 8192 ----------------

    private fun provider(modelMeta: JsonObject = JsonObject(emptyMap())) =
        ProviderConfig("p", "custom", "P", ProviderType.OPENAI, "https://x/v1", listOf("m"), modelMeta = modelMeta)

    private fun meta(json: String) = Js.json.parseToJsonElement(json) as JsonObject

    @Test fun reasoningModelsGetBiggerDefault() {
        for (m in listOf("google/gemini-2.5-pro", "gemini-2.5-flash", "google/gemini-3.8-flash", "o3-mini", "o1", "deepseek-reasoner", "deepseek-r1")) {
            assertEquals("$m 默认上限", 32768, ProviderRules.resolveMaxTokens(provider(), m, null))
        }
        for (m in listOf("deepseek-chat", "gpt-4o", "qwen-max", "glm-4", "m", "other")) {
            assertEquals("$m 默认上限", 8192, ProviderRules.resolveMaxTokens(provider(), m, null))
        }
    }

    @Test fun modelCapStillWins() {
        // 模型自报上限更小时以模型为准（Gemini 2.0 Flash 这类 8192 的模型不会被抬到 32768）
        val p = provider(meta("""{"google/gemini-2.5-pro":{"maxOutput":8192}}"""))
        assertEquals(8192, ProviderRules.resolveMaxTokens(p, "google/gemini-2.5-pro", null))
        // 模型自报上限更大时，推理型仍按 32768 发给上游（避免 8192 被思考 token 吃光）
        val q = provider(meta("""{"google/gemini-2.5-pro":{"maxOutput":65536}}"""))
        assertEquals(32768, ProviderRules.resolveMaxTokens(q, "google/gemini-2.5-pro", null))
    }

    @Test fun userSettingAlwaysWins() {
        val p = provider(meta("""{"google/gemini-2.5-pro":{"maxOutput":65536}}"""))
        assertEquals(2000, ProviderRules.resolveMaxTokens(p, "google/gemini-2.5-pro", 2000))
        // 用户填得比模型上限大 → 仍被模型钳住
        assertEquals(65536, ProviderRules.resolveMaxTokens(p, "google/gemini-2.5-pro", 200000))
    }
}
