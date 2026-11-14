package com.aichat.app.net

import com.aichat.app.domain.BuiltRequest
import com.aichat.app.domain.model.Message
import com.aichat.app.domain.model.ProviderConfig
import com.aichat.app.domain.model.ProviderType
import com.aichat.app.domain.model.Role
import com.aichat.app.plugins.js.Js
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生图协议层（providers.js:721–823 的移植）。
 *
 * 重点守住三个分支的**差异**：OpenRouter 走 `/images` + resolution；FLUX 档位模型也用 resolution，
 * 但路径仍是 `/images/generations`；普通 OpenAI 兼容走 `size`。硅基流动那套改名/删字段是第三层。
 *
 * 断言走 `Js.parse` 的返回（Map / List / String / Double），与 `buildImageRequest` 用的是同一套
 * JS 语义表示 —— 不要在这里混用 `JsonObject`，那是两套表示。
 */
class ImageGenTest {

    private fun p(
        preset: String = "custom",
        id: String = "p1",
        base: String = "https://api.example.com/v1",
        hasKey: Boolean = true,
        imageModel: String = "",
        models: List<String> = listOf("gpt-image-1"),
    ) = ProviderConfig(
        id = id,
        presetId = preset,
        name = preset,
        type = ProviderType.OPENAI,
        baseUrl = base,
        models = models,
        imageModel = imageModel,
        hasKey = hasKey,
    )

    private fun bodyOf(req: BuiltRequest): Map<*, *> = Js.parse(req.body) as Map<*, *>

    @Test fun plainOpenAiUsesSizeAndGenerationsPath() {
        val req = ImageGen.buildImageRequest(p(), "gpt-image-1", "a cat", "1024x1792")
        assertEquals("https://api.example.com/v1/images/generations", req.url)
        val body = bodyOf(req)
        assertEquals("gpt-image-1", body["model"])
        assertEquals("a cat", body["prompt"])
        assertEquals("1024x1792", body["size"])
        assertEquals(1.0, (body["n"] as Number).toDouble(), 0.0)
        assertNull(body["resolution"])
        assertNull(body["aspect_ratio"])
        assertNull(body["input_references"])
    }

    @Test fun openRouterUsesImagesPathWithResolutionAndReferences() {
        val req = ImageGen.buildImageRequest(
            p(preset = "openrouter", base = "https://openrouter.ai/api/v1"),
            "google/gemini-3-pro-image",
            "a dog",
            "1792x1024",
            refImages = listOf("https://x/1.png", "https://x/2.png"),
        )
        assertEquals("https://openrouter.ai/api/v1/images", req.url)
        val body = bodyOf(req)
        assertEquals("1.5K", body["resolution"])
        assertEquals("16:9", body["aspect_ratio"])
        assertNull(body["size"])
        val refs = body["input_references"] as List<*>
        assertEquals(2, refs.size)
        assertEquals("image_url", (refs[0] as Map<*, *>)["type"])
        assertEquals("https://x/1.png", ((refs[0] as Map<*, *>)["image_url"] as Map<*, *>)["url"])
    }

    @Test fun fluxTierKeepsGenerationsPathButUsesTiers() {
        val req = ImageGen.buildImageRequest(p(), "black-forest-labs/flux-3-schnell", "x", "768x768")
        assertEquals("https://api.example.com/v1/images/generations", req.url)
        val body = bodyOf(req)
        assertEquals("768", body["resolution"])
        assertEquals("1:1", body["aspect_ratio"])
        assertNull(body["size"])
    }

    @Test fun siliconflowRenamesSizeAndDropsN() {
        val body = bodyOf(ImageGen.buildImageRequest(p(preset = "siliconflow"), "Qwen/Qwen-Image", "x"))
        assertEquals("1024x1024", body["image_size"])
        assertNull(body["size"])
        assertNull(body["n"])
    }

    @Test fun siliconflowKolorsAddsBatchSize() {
        val body = bodyOf(ImageGen.buildImageRequest(p(preset = "siliconflow"), "Kwai-Kolors/Kolors", "x"))
        assertEquals(1.0, (body["batch_size"] as Number).toDouble(), 0.0)
    }

    @Test fun singleReferenceGoesToImageFieldForPlainProviders() {
        val body = bodyOf(ImageGen.buildImageRequest(p(), "gpt-image-1", "x", refImages = listOf("https://x/1.png", "https://x/2.png")))
        assertEquals("https://x/1.png", body["image"])
        assertNull(body["input_references"])
    }

    @Test fun parsesUrlAndBase64AndSkipsEmptyEntries() {
        val a = ImageGen.parseImageResponse("""{"data":[{"url":"https://x/1.png"},{"b64_json":"QUJD"},{},{"url":""}]}""")
        assertEquals(listOf("https://x/1.png", "data:image/png;base64,QUJD"), a)

        val b = ImageGen.parseImageResponse("""{"images":[{"url":"https://y/2.png"}]}""")
        assertEquals(listOf("https://y/2.png"), b)

        assertTrue(ImageGen.parseImageResponse("not json").isEmpty())
        assertTrue(ImageGen.parseImageResponse("""{"data":[]}""").isEmpty())
    }

    @Test fun base64UsesMediaTypeWhenPresent() {
        val out = ImageGen.parseImageResponse("""{"data":[{"b64_json":"QUJD","media_type":"image/jpeg"}]}""")
        assertEquals(listOf("data:image/jpeg;base64,QUJD"), out)
        val webp = ImageGen.parseImageResponse("""{"data":[{"b64_json":"QUJD","mime_type":"image/webp"}]}""")
        assertEquals(listOf("data:image/webp;base64,QUJD"), webp)
    }

    @Test fun tierMappingFollowsLongEdgeBuckets() {
        assertEquals(ImageGen.Tier("512", "1:1"), ImageGen.pixelSizeToTier("512x512"))
        assertEquals(ImageGen.Tier("768", "1:1"), ImageGen.pixelSizeToTier("768x768"))
        assertEquals(ImageGen.Tier("1K", "1:1"), ImageGen.pixelSizeToTier("1024x1024"))
        assertEquals(ImageGen.Tier("2K", "21:9"), ImageGen.pixelSizeToTier("2560x1097"))
        assertEquals(ImageGen.Tier("4K", "1:1"), ImageGen.pixelSizeToTier("4096x4096"))
        // 认不出来的尺寸回落到 1K / 1:1（JS 的默认分支）
        assertEquals(ImageGen.Tier("1K", "1:1"), ImageGen.pixelSizeToTier("nonsense"))
    }

    @Test fun canGenerateRejectsWrongProviderTypeMissingKeyAndMissingModel() {
        assertTrue(ImageGen.canGenerate(null, "m").isFailure)
        val anth = ProviderConfig("a", "anthropic", "A", ProviderType.ANTHROPIC, "https://a/v1", listOf("m"))
        assertEquals("图片生成仅支持 OpenAI 兼容接口", ImageGen.canGenerate(anth, "m").exceptionOrNull()?.message)
        assertEquals("请先在设置中填写 API Key", ImageGen.canGenerate(p(hasKey = false), "m").exceptionOrNull()?.message)
        val noModel = ImageGen.canGenerate(p(imageModel = ""), "")
        assertTrue(noModel.isFailure)
        assertTrue(noModel.exceptionOrNull()!!.message!!.startsWith("请先填写图片生成模型"))
        assertTrue(ImageGen.canGenerate(p(imageModel = "gpt-image-1"), "").isSuccess)
    }

    @Test fun fluxDetectionMatchesJsRules() {
        assertTrue(ImageGen.isFluxTierModel("FLUX.3-pro", p()))
        assertTrue(ImageGen.isFluxTierModel("black-forest-labs/flux-1.1-pro", p()))
        assertTrue(ImageGen.isFluxTierModel("seedream-4", p(preset = "openrouter")))
        assertFalse(ImageGen.isFluxTierModel("gpt-image-1", p()))
        assertFalse(ImageGen.isFluxTierModel(null, p()))
    }

    @Test fun promptIsBuiltFromRecentMessages() {
        val msgs = listOf(
            Message("1", Role.USER, "第一句"),
            Message("2", Role.ASSISTANT, "第二句"),
            Message("3", Role.USER, "第三句"),
        )
        val out = ImageGen.buildPromptFromMessages(msgs)
        assertTrue(out.contains("用户：第一句"))
        assertTrue(out.contains("助手：第二句"))
        assertTrue(out.indexOf("第三句") > out.indexOf("第二句"))
    }

    @Test fun errorTextPullsMessageOut() {
        assertEquals("图片生成失败（400）：bad size", ImageGen.errorText(400, """{"error":{"message":"bad size"}}"""))
        assertEquals("图片生成失败（401）：nope", ImageGen.errorText(401, """{"message":"nope"}"""))
        assertEquals("图片生成失败（HTTP 500）", ImageGen.errorText(500, "oops"))
    }
}
