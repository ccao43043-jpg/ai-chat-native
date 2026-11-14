package com.aichat.app.net

import com.aichat.app.domain.ImageClient
import com.aichat.app.domain.ImageRequest
import com.aichat.app.domain.KeyVault
import com.aichat.app.domain.model.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * [ImageClient] 实现：把 [ImageGen] 造出来的请求真的发出去。
 *
 * 认证走 [AuthInjector]（Key 只从 KeyVault 取，请求源必须与登记的 Base URL 一致），
 * 所以这里**不**手动拼 `Authorization` —— 旧 JS 是直接读 `provider.apiKey`，原生这边 Key 从不进内存模型。
 */
class DefaultImageClient(
    private val auth: AuthInjector,
    private val client: OkHttpClient = HttpClients.authed,
) : ImageClient {

    constructor(keys: KeyVault?) : this(AuthInjector(keys))

    override fun canGenerate(provider: ProviderConfig?, modelOverride: String?): Result<String> =
        ImageGen.canGenerate(provider, modelOverride)

    override suspend fun generate(req: ImageRequest): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val model = ImageGen.effectiveModel(req.provider, req.model)
            require(model.isNotEmpty()) { "缺少图片生成模型" }
            val built = ImageGen.buildImageRequest(req.provider, model, req.prompt, req.size, req.refImages)
            val authed = try {
                auth.authorize(req.provider.id, built)
            } catch (e: AuthInjector.Rejected) {
                throw IllegalStateException(e.message ?: "请求被拒绝")
            }
            val request = Request.Builder()
                .url(authed.url)
                .post(authed.body.toRequestBody("application/json".toMediaType()))
                .apply { authed.headers.forEach { (k, v) -> header(k, v) } }
                .build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IllegalStateException(ImageGen.errorText(resp.code, text))
                val images = ImageGen.parseImageResponse(text)
                if (images.isEmpty()) throw IllegalStateException("接口没有返回图片（响应里没有 data[].url / b64_json）")
                images
            }
        }
    }
}
