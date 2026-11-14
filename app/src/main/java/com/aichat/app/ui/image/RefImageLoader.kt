package com.aichat.app.ui.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * 参考图 → `data:image/jpeg;base64,...`。
 *
 * 生图接口只要 url 字符串（OpenAI 兼容收 data URL，OpenRouter / flux 收 `image_url.url`），
 * 所以不落盘、不上传：选完就地转成 data URL 塞进请求体。
 *
 * 必须压：手机相册里一张 4000×3000 的图 base64 之后接近 8MB，多数服务商 413 或者直接
 * 超时。这里按长边缩到 1024、JPEG 0.82，单张通常 100–300KB。
 */
object RefImageLoader {
    /** 长边上限（像素）。 */
    const val MAX_EDGE = 1024

    /** JPEG 质量（0–100）。 */
    const val QUALITY = 82

    /** 明文上限：超过就不收（避免把请求体撑爆）。 */
    const val MAX_BYTES = 4 * 1024 * 1024

    /**
     * 读 uri → 缩放 → 压缩 → base64。任何一步失败都返回 null（调用方负责提示）。
     */
    fun toDataUrl(context: Context, uri: Uri, maxEdge: Int = MAX_EDGE): String? = runCatching {
        val raw = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: return null
        val scaled = scaleDown(raw, maxEdge)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        if (scaled !== raw) scaled.recycle()
        raw.recycle()
        val bytes = out.toByteArray()
        if (bytes.size > MAX_BYTES) return null
        "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }.getOrNull()

    /** 只在长边超过 [maxEdge] 时等比缩小；否则原样返回（调用方不要再 recycle 原图）。 */
    fun scaleDown(src: Bitmap, maxEdge: Int = MAX_EDGE): Bitmap {
        val longEdge = max(src.width, src.height)
        if (longEdge <= maxEdge) return src
        val ratio = maxEdge.toFloat() / longEdge
        val w = max(1, (src.width * ratio).toInt())
        val h = max(1, (src.height * ratio).toInt())
        return Bitmap.createScaledBitmap(src, w, h, true)
    }
}
