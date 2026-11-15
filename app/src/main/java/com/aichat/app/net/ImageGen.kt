package com.aichat.app.net

import com.aichat.app.domain.BuiltRequest
import com.aichat.app.domain.model.Message
import com.aichat.app.domain.model.ProviderConfig
import com.aichat.app.domain.model.ProviderType
import com.aichat.app.domain.model.Role
import com.aichat.app.plugins.js.Js
import kotlinx.serialization.json.JsonPrimitive

/**
 * 生图协议层：`imagegen.js` + `providers.js` 里 `buildImageRequest` / `parseImageResponse` /
 * `isFluxTierModel` / `pixelSizeToTier` 的 Kotlin 移植（纯函数，无 IO，可单测）。
 *
 * 三个分支（对齐 JS，不要"顺手统一"）：
 *  1. **OpenRouter**：一律走新 Image API —— `POST /images`，`resolution` + `aspect_ratio`，
 *     参考图放 `input_references[{type,image_url{url}}]`，最多 16 张。
 *  2. **FLUX 3 / Seedream 档位模型**：同样用 resolution + aspect_ratio，但路径仍是 `/images/generations`，
 *     参考图最多 10 张（JS 的 maxRefs 即 10）。
 *  3. **其余 OpenAI 兼容**：老接口 `size`，参考图放单数 `image`；`siliconflow` 额外把 `size` 改名
 *     `image_size`、删掉 `n`，`kolors` 再加 `batch_size:1`。
 */
object ImageGen {

    val SIZES = listOf("1024x1024", "1024x1792", "1792x1024", "512x512", "768x768")
    const val DEFAULT_SIZE = "1024x1024"
    const val HISTORY_LIMIT = 10

    /** UI 选图上限：OpenRouter 收 16 张、其余 10 张，这里按较小的一边收。 */
    const val MAX_REF_IMAGES = 10
    /** 硬上限：OpenRouter 走新 Image API，最多 16 张（providers.js:781 `maxRefs = openrouter ? 16 : 10`）。 */
    const val MAX_REF_IMAGES_HARD = 16
    const val MSG_SLICE = 280

    /** providers.js 的 ASPECT_RATIOS（顺序影响"最接近"的取舍，别排序） */
    val ASPECT_RATIOS = listOf(
        "21:9", "2:1", "16:9", "3:2", "7:5", "4:3", "5:4", "1:1",
        "4:5", "3:4", "5:7", "2:3", "9:16", "1:2", "9:21",
    )

    data class Tier(val resolution: String, val aspectRatio: String)

    /** 手填模型优先，其次服务商配置的 imageModel */
    fun effectiveModel(provider: ProviderConfig?, modelOverride: String?): String {
        val o = modelOverride?.trim().orEmpty()
        if (o.isNotEmpty()) return o
        return provider?.imageModel?.trim().orEmpty()
    }

    /** 返回 Result.success(生效模型名) 或 Result.failure(可直接展示给用户的原因) */
    fun canGenerate(provider: ProviderConfig?, modelOverride: String?): Result<String> {
        if (provider == null) return Result.failure(IllegalStateException("请先选择服务商"))
        if (provider.type != ProviderType.OPENAI) return Result.failure(IllegalStateException("图片生成仅支持 OpenAI 兼容接口"))
        if (!provider.hasKey) return Result.failure(IllegalStateException("请先在设置中填写 API Key"))
        val model = effectiveModel(provider, modelOverride)
        if (model.isEmpty()) {
            return Result.failure(IllegalStateException("请先填写图片生成模型（或在设置中为「${provider.name.ifEmpty { "当前服务商" }}」配置 imageModel）"))
        }
        return Result.success(model)
    }

    fun normalizeSize(size: String?): String = size?.trim().orEmpty().ifEmpty { DEFAULT_SIZE }

    /** 可选作生图服务商的候选：只有 OpenAI 兼容 */
    fun listImageProviders(providers: List<ProviderConfig>): List<ProviderConfig> =
        providers.filter { it.type == ProviderType.OPENAI }

    /** 从对话里提炼可编辑的生图提示词（跳过生图消息 / 错误 / pending，取最近 limit 条） */
    fun buildPromptFromMessages(messages: List<Message>, limit: Int = HISTORY_LIMIT, slice: Int = MSG_SLICE): String {
        val list = messages.filter { m ->
            m.role != Role.SYSTEM &&
                m.error == null &&
                m.content.isNotBlank() &&
                // imageGen 标记在 extras 里（JS 的 m.imageGen）
                !(m.extras["imageGen"]?.let { it is JsonPrimitive && it.content == "true" } ?: false)
        }
        val recent = list.takeLast(limit)
        if (recent.isEmpty()) return ""
        val lines = recent.map { m ->
            val who = if (m.role == Role.USER) "用户" else "助手"
            val text = m.content.replace(Regex("\\s+"), " ").trim().let { if (it.length > slice) it.substring(0, slice) else it }
            "$who：$text"
        }
        return "根据以下对话内容，生成一张配图：\n" + lines.joinToString("\n")
    }

    // ---------------- 档位映射 ----------------

    private val FLUX3 = Regex("flux[-._]?3", RegexOption.IGNORE_CASE)
    private val BFL = Regex("black-forest-labs/", RegexOption.IGNORE_CASE)
    private val FLUX_ANY = Regex("flux", RegexOption.IGNORE_CASE)
    private val SEEDREAM = Regex("seedream", RegexOption.IGNORE_CASE)

    fun isFluxTierModel(model: String?, provider: ProviderConfig?): Boolean {
        val id = model?.lowercase().orEmpty()
        if (id.isEmpty()) return false
        if (FLUX3.containsMatchIn(id)) return true
        if (BFL.containsMatchIn(id) && FLUX_ANY.containsMatchIn(id)) return true
        if (ProviderRules.isOpenRouterProvider(provider) && SEEDREAM.containsMatchIn(id)) return true
        return false
    }

    private val PIXEL = Regex("^(\\d+)\\s*[x×]\\s*(\\d+)$", RegexOption.IGNORE_CASE)

    fun parsePixelSize(size: String?): Pair<Int, Int>? {
        val m = PIXEL.find(size?.trim().orEmpty()) ?: return null
        val w = m.groupValues[1].toIntOrNull() ?: return null
        val h = m.groupValues[2].toIntOrNull() ?: return null
        if (w <= 0 || h <= 0) return null
        return w to h
    }

    fun resolutionFromLongEdge(maxEdge: Int): String = when {
        maxEdge <= 512 -> "512"
        maxEdge <= 768 -> "768"
        maxEdge <= 1280 -> "1K"
        maxEdge <= 1792 -> "1.5K"
        maxEdge <= 2560 -> "2K"
        else -> "4K"
    }

    fun closestAspectRatio(w: Int, h: Int): String {
        val r = w.toDouble() / h
        var best = "1:1"
        var bestDiff = Double.MAX_VALUE
        for (a in ASPECT_RATIOS) {
            val parts = a.split(":")
            val ar = parts[0].toDouble() / parts[1].toDouble()
            val d = Math.abs(ar - r)
            if (d < bestDiff) { bestDiff = d; best = a }
        }
        return best
    }

    /** 像素 WxH → 档位；解析不了时按 1K + 1:1（JS 同） */
    fun pixelSizeToTier(size: String?): Tier {
        val wh = parsePixelSize(size) ?: return Tier("1K", "1:1")
        return Tier(resolutionFromLongEdge(maxOf(wh.first, wh.second)), closestAspectRatio(wh.first, wh.second))
    }

    /** 生图页尺寸下拉的文案：FLUX 档位模型显示「1K · 1:1」这种更好读的形式 */
    fun sizeOptionLabel(size: String, fluxTier: Boolean): String {
        if (!fluxTier) return size
        val t = pixelSizeToTier(size)
        return t.resolution + " · " + if (t.aspectRatio == "1:1") "正方形" else t.aspectRatio
    }

    // ---------------- 请求 / 响应 ----------------

    fun buildImageRequest(
        provider: ProviderConfig,
        model: String,
        prompt: String,
        size: String = DEFAULT_SIZE,
        refImages: List<String> = emptyList(),
    ): BuiltRequest {
        require(model.isNotEmpty()) { "缺少图片生成模型" }
        val openrouter = ProviderRules.isOpenRouterProvider(provider)
        val tier = isFluxTierModel(model, provider)
        val useImageApi = openrouter || tier
        val maxRefs = if (openrouter) 16 else 10
        val images = refImages.filter { it.isNotEmpty() }.take(maxRefs)

        // 用 JS 语义的 Map 造 body（Js.stringify 负责序列化）：
        // providers.js 是 `const body = {...}` 然后 `delete body.size`，用 Map 才能照抄这套增删。
        val bag = LinkedHashMap<String, Any?>()
        bag["model"] = model
        bag["prompt"] = prompt
        bag["n"] = 1
        if (useImageApi) {
            val mapped = pixelSizeToTier(size)
            bag["resolution"] = mapped.resolution
            bag["aspect_ratio"] = mapped.aspectRatio
            if (images.isNotEmpty()) {
                bag["input_references"] = images.map { url ->
                    linkedMapOf<String, Any?>("type" to "image_url", "image_url" to linkedMapOf<String, Any?>("url" to url))
                }
            }
        } else {
            bag["size"] = normalizeSize(size)
            if (provider.presetId == "siliconflow") {
                // 硅基流动用 image_size，且不接受 n
                bag["image_size"] = normalizeSize(size)
                bag.remove("size")
                bag.remove("n")
                if (model.contains("kolors", ignoreCase = true)) bag["batch_size"] = 1
            }
            if (images.isNotEmpty()) bag["image"] = images[0]
        }

        val path = if (openrouter) "/images" else "/images/generations"
        return BuiltRequest(
            url = trimSlash(provider.baseUrl) + path,
            method = "POST",
            headers = mapOf("Content-Type" to "application/json"),
            body = Js.stringify(bag),
        )
    }

    /**
     * 解析图片响应：`data[]` 或 `images[]` 里的 `url` / `b64_json`（带 media_type 时保留 mime）。
     * 与 JS 一致地**跳过**既无 url 又无 b64 的项（`{}`、`{"url":""}` 都算没有）。
     */
    fun parseImageResponse(text: String): List<String> {
        // 服务端偶尔回非 JSON（网关 HTML / 空体），这里不能把解析异常抛给 UI
        val obj = runCatching { Js.parse(text) }.getOrNull() as? Map<*, *> ?: return emptyList()
        val list = (obj["data"] as? List<*>) ?: (obj["images"] as? List<*>) ?: return emptyList()
        return list.mapNotNull { el ->
            val it = el as? Map<*, *> ?: return@mapNotNull null
            val url = present(it["url"])
            if (url.isNotEmpty()) return@mapNotNull url
            val b64 = present(it["b64_json"])
            if (b64.isEmpty()) return@mapNotNull null
            val mt = present(it["media_type"]).ifEmpty { present(it["mime_type"]) }.ifEmpty { "image/png" }
            "data:$mt;base64,$b64"
        }
    }

    /** JS 里 `obj.k` 为 undefined / 空串都算「没有」；Js.str 会把 undefined 变成字符串 "undefined"，这里挡掉 */
    private fun present(v: Any?): String {
        if (v == null || v === com.aichat.app.plugins.js.Undefined) return ""
        val s = Js.str(v)
        return if (s == "undefined") "" else s
    }

    /** `/images/generations` 没有真实百分比，用耗时阶段 + HTTP 信号给出人话 */
    fun progressStage(elapsedMs: Long, receiving: Boolean = false, httpStarted: Boolean = false): String {
        if (receiving) return "正在接收…"
        if (httpStarted) return "等待结果…"
        val s = (elapsedMs.coerceAtLeast(0)) / 1000.0
        return when {
            s < 1.2 -> "请求已发送…"
            s < 8 -> "生成中…"
            s < 22 -> "等待结果…"
            else -> "仍在生成，请稍候…"
        }
    }

    fun formatElapsed(elapsedMs: Long): String = "${(elapsedMs.coerceAtLeast(0)) / 1000} 秒"

    /** 非 2xx 的提示：沿用聊天侧的 errorText 口径，但补上"可能是模型不支持该尺寸" */
    fun errorText(status: Int, body: String): String {
        val msg = errorMessage(body)
        return if (msg.isNotEmpty()) "图片生成失败（$status）：$msg" else "图片生成失败（HTTP $status）"
    }

    private fun errorMessage(body: String): String {
        val obj = runCatching { Js.parse(body) as? Map<*, *> }.getOrNull() ?: return ""
        val err = obj["error"]
        if (err is Map<*, *>) {
            val m = present(err["message"])
            if (m.isNotEmpty()) return m
        }
        val m = present(obj["message"])
        if (m.isNotEmpty()) return m
        return ""
    }

    private fun trimSlash(s: String): String = s.trimEnd('/')
}
