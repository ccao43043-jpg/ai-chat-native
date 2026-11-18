package com.aichat.app.net

import com.aichat.app.domain.ChatOptions
import com.aichat.app.domain.model.Role
import com.aichat.app.domain.WireMessage
import com.aichat.app.domain.model.ProviderConfig
import com.aichat.app.domain.model.ProviderType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** OpenRouter `:flex` 等 :variant 后缀 —— modelMeta 回退查找（maxOutput 钳制 / 亲和路由）+ wire 逐字透传 */
class FlexVariantTest {
    private fun provider(modelMeta: JsonObject = JsonObject(emptyMap())) =
        ProviderConfig("or", "openrouter", "OR", ProviderType.OPENAI, "https://openrouter.ai/api/v1", listOf("google/gemini-2.5-pro"), modelMeta = modelMeta)
    private fun meta(json: String) = Js.json.parseToJsonElement(json) as JsonObject

    @Test fun baseSlugStripsVariants() {
        assertEquals("google/gemini-2.5-pro", ProviderRules.baseSlug("google/gemini-2.5-pro:flex"))
        assertEquals("deepseek/deepseek-r1", ProviderRules.baseSlug("deepseek/deepseek-r1:nitro"))
        assertEquals("m", ProviderRules.baseSlug("m"))
        assertNull(ProviderRules.baseSlug(null))
        assertNull(ProviderRules.baseSlug("  "))
        assertNull(ProviderRules.baseSlug(":flex"))
    }

    @Test fun resolveMaxTokensVariantUsesBaseMeta() {
        val p = provider(meta("""{"google/gemini-2.5-pro":{"maxOutput":65536}}"""))
        // 变体串与裸 slug 行为完全一致（cap 命中 + 推理默认 32768）
        assertEquals(ProviderRules.resolveMaxTokens(p, "google/gemini-2.5-pro", null),
            ProviderRules.resolveMaxTokens(p, "google/gemini-2.5-pro:flex", null))
        assertEquals(32768, ProviderRules.resolveMaxTokens(p, "google/gemini-2.5-pro:flex", null))
        // 精确键优先：真带冒号且有专属 meta 的模型 id 不受剥后缀影响
        val q = provider(meta("""{"custom:model":{"maxOutput":1024}}"""))
        assertEquals(1024, ProviderRules.resolveMaxTokens(q, "custom:model", null))
        // 无 meta 的变体串 → 与裸 slug 同样回落推理默认
        assertEquals(32768, ProviderRules.resolveMaxTokens(provider(), "google/gemini-2.5-pro:flex", null))
    }

    @Test fun endpointsForVariantFallsBackToBase() {
        val p = provider(meta("""{"google/gemini-2.5-pro":{"_endpoints":[{"tag":"deepinfra"}]}}"""))
        // flex 变体沿用基础模型的端点价 → 缓存亲和钉 upstream 与 :flex 可叠加
        assertEquals(OpenRouterRouting.endpointsFor(p, "google/gemini-2.5-pro"), OpenRouterRouting.endpointsFor(p, "google/gemini-2.5-pro:flex"))
        assertEquals(JsonArray(emptyList()), OpenRouterRouting.endpointsFor(p, "none/here:flex"))
    }

    @Test fun wireModelIsVerbatim() {
        val body = OpenAiCompat.buildBody(provider(), "google/gemini-2.5-pro:flex", listOf(WireMessage(Role.USER, "hi")), "", ChatOptions(stream = false))
        assertEquals("google/gemini-2.5-pro:flex", (body["model"] as JsonPrimitive).content)
    }
}
