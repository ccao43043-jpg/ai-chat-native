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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun effectiveModelIsTheSingleFlexNormalizer() {
        assertEquals("m:flex", ProviderRules.effectiveModel("m", true))
        assertEquals("m", ProviderRules.effectiveModel("m", false))
        assertEquals("m:flex", ProviderRules.effectiveModel("m:flex:flex", true))
        assertEquals("m", ProviderRules.effectiveModel("m:flex:flex", false))
        assertEquals("m", ProviderRules.effectiveModel("m", false))
        // 其它 OpenRouter 变体必须保留，只处理末尾 flex
        assertEquals("m:nitro", ProviderRules.effectiveModel("m:nitro", false))
        assertEquals("m:nitro:flex", ProviderRules.effectiveModel("m:nitro", true))
        assertEquals("m:nitro:flex", ProviderRules.effectiveModel("m:nitro:flex:flex", true))
        // 大小写归一 + 空白处理
        assertEquals("m:flex", ProviderRules.effectiveModel("  m:FLEX  ", true))
        assertEquals("m", ProviderRules.effectiveModel("  m:FLEX  ", false))
        assertNull(ProviderRules.effectiveModel(null, true))
        assertNull(ProviderRules.effectiveModel("   ", true))
    }

    // ---- flexProviders 存储纯函数（按服务商；T1 持久化的底层） ----

    @Test fun flexStorageRoundTrips() {
        val extras = meta("""{"thinkingLevels":{"a|m":"high"},"keep":"me"}""")
        assertNull("没设过 → null（区别于显式关）", ProviderRules.flexOf(extras, "or"))
        assertNull(ProviderRules.flexOf(extras, null))

        val on = ProviderRules.withFlex(extras, "or", true)
        assertEquals(true, ProviderRules.flexOf(on, "or"))
        val off = ProviderRules.withFlex(on, "or", false)
        assertEquals("显式 false 也留痕", false, ProviderRules.flexOf(off, "or"))
        // 无关键不丢
        assertEquals("high", ((on["thinkingLevels"] as JsonObject)["a|m"] as JsonPrimitive).content)
        assertEquals("me", (on["keep"] as JsonPrimitive).content)
    }

    @Test fun resolveFlexPrefersSettingThenModelString() {
        // 设置优先：显式关压过带 :flex 的模型串（这是修复旧 bug 的关键）
        val off = ProviderRules.withFlex(JsonObject(emptyMap()), "or", false)
        assertEquals(false, ProviderRules.resolveFlex(off, "or", "google/gemini-2.5-pro:flex"))
        // 从没设过 → 回落当前模型串（升级用户升级前手开的 Flex 靠它保住）
        assertEquals(true, ProviderRules.resolveFlex(JsonObject(emptyMap()), "or", "google/gemini-2.5-pro:flex"))
        assertEquals(false, ProviderRules.resolveFlex(JsonObject(emptyMap()), "or", "google/gemini-2.5-pro"))
        assertEquals(false, ProviderRules.resolveFlex(JsonObject(emptyMap()), "or", null))
        // 设过的不受模型串影响
        assertEquals(true, ProviderRules.resolveFlex(ProviderRules.withFlex(JsonObject(emptyMap()), "or", true), "or", "m"))
        assertFalse(ProviderRules.resolveFlex(off, "or", "m"))
    }

    @Test fun flexPriceHintFormatsHalfOfBase() {
        assertEquals("—", ModelCatalog.flexPriceHint(null))
        assertEquals("—", ModelCatalog.flexPriceHint(JsonObject(emptyMap())))
        assertEquals("缺 completion 价目 → —", "—", ModelCatalog.flexPriceHint(meta("""{"price":{"prompt":0.6}}""")))
        assertEquals("图片模型 → —", "—", ModelCatalog.flexPriceHint(meta("""{"price":{"image":0.01}}""")))
        assertEquals("$0.30/$1.50 per 1M", ModelCatalog.flexPriceHint(meta("""{"price":{"prompt":0.6,"completion":3.0}}""")))
        assertEquals("priceFloor 优先于 price", "$0.15/$0.75 per 1M",
            ModelCatalog.flexPriceHint(meta("""{"price":{"prompt":0.6,"completion":3.0},"priceFloor":{"prompt":0.3,"completion":1.5}}""")))
    }
}
