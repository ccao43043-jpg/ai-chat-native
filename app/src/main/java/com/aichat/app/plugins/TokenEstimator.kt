package com.aichat.app.plugins

import com.aichat.app.plugins.js.JObj
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.js.jobj
import kotlin.math.ceil

/**
 * 统一 token 估算（评审 3.2 / OPTIMIZATION-ADVISORY §10.2①）。
 *
 * 此前有两把尺子：RP 用逐码点分档（`RpCacheCore.estimateTokens`，DeepSeek 官方换算），
 * cache 插件用 CJK 1 字 ≈ 1 Token（`CacheCommonImpl.estTokens`，cache-common.js 移植）——
 * 同一段中英混排差约 40%，预算判断与命中率诊断口径不一致。现收口为唯一实现，
 * 三处入口（RpCacheCore / CacheCommonImpl / 本对象）同值（见 TokenEstimatorTest）。
 *
 * 图片计价按服务商：Gemini 每图 258；Anthropic (w×h)/750（下限 156）；
 * OpenAI 系 85 + 170×512px tile 数；其他或解析失败沿用旧口径 1000/张
 * （与 `RpCacheCore.estimateItems` 的 `imgN * 1000` 一致，rpcache golden 兼容）。
 *
 * 预算路径的系统性偏差由 `RpCacheCore.calibrate`（按 provider|model|modality 的
 * 真实 prompt_tokens EWMA）自动修正，静态系数只做首估。
 */
object TokenEstimator {

    /** 逐码点分档：汉字/假名/谚文/全角 0.6、英文字母 0.3、数字 0.5、空白 0.1、其它 <0x3000 0.6、其余 1.5，向上取整。 */
    fun text(s: String): Int {
        var n = 0.0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            n += when {
                (cp in 0x3000..0x9fff) || (cp in 0xac00..0xd7af) || (cp in 0xff00..0xffef) ||
                    (cp in 0xf900..0xfaff) || (cp in 0x20000..0x2fa1f) -> 0.6
                cp in 65..90 || cp in 97..122 -> 0.3
                cp in 48..57 -> 0.5
                cp == 32 || cp == 9 || cp == 10 || cp == 13 -> 0.1
                cp < 128 -> 0.6
                cp < 0x3000 -> 0.6
                else -> 1.5
            }
        }
        return ceil(n).toInt()
    }

    /** 图片按服务商计价。provider 取 presetId（openai / anthropic / gemini / deepseek…），含糊匹配子串。 */
    fun image(provider: String?, dataUrl: String?): Int {
        val p = provider?.lowercase() ?: return FALLBACK
        return when {
            // Gemini 按图平价，不需要图片内容
            "gemini" in p -> 258
            "anthropic" in p || "claude" in p -> dataUrl?.let { dims(it)?.let { (w, h) -> maxOf(156, w * h / 750) } } ?: FALLBACK
            "openai" in p -> dataUrl?.let { dims(it)?.let { (w, h) -> 85 + 170 * ceil(w / 512.0).toInt() * ceil(h / 512.0).toInt() } } ?: FALLBACK
            else -> FALLBACK
        }
    }

    /**
     * 消息序列估算：每条 = 正文 + 4（角色/分隔开销）+ 图片。
     * provider 为 null 时图片按旧口径 1000/张（与 `RpCacheCore.estimateItems` 完全一致，golden 兼容）。
     */
    fun messages(items: List<Any?>, provider: String? = null): Int = items.sumOf { m ->
        val o = m as? JObj ?: jobj()
        val imgs = o["images"] as? List<*>
        text(Js.str(o["content"])) + 4 + when {
            imgs.isNullOrEmpty() -> 0
            provider == null -> imgs.size * FALLBACK
            else -> imgs.sumOf { image(provider, Js.str(it)) }
        }
    }

    private const val FALLBACK = 1000

    /** 从 data URL 解析图片尺寸：PNG 读 IHDR（头 24 字节），JPEG 走 SOF 标记。只解码头部，大图不整体解码。 */
    private fun dims(dataUrl: String): Pair<Int, Int>? {
        val b64 = dataUrl.substringAfter("base64,", "")
        if (b64.isEmpty()) return null
        val usable = b64.length - (b64.length % 4)
        val raw = try {
            java.util.Base64.getDecoder().decode(b64.take(usable.coerceAtMost(200_000)))
        } catch (_: IllegalArgumentException) {
            return null
        }
        // PNG：8 字节签名 + IHDR（宽 16..19，高 20..23，大端）
        if (raw.size >= 24 && raw[0] == 0x89.toByte() && raw[1] == 0x50.toByte() && raw[2] == 0x4E.toByte() && raw[3] == 0x47.toByte()) {
            val w = be32(raw, 16); val h = be32(raw, 20)
            return if (w > 0 && h > 0) w to h else null
        }
        // JPEG：FFD8 开头，逐段走标记，SOF0-CF（除 C4/C8/CC）里读高/宽
        if (raw.size > 4 && raw[0] == 0xFF.toByte() && raw[1] == 0xD8.toByte()) {
            var i = 2
            while (i + 9 < raw.size) {
                if (raw[i] != 0xFF.toByte()) { i++; continue }
                val marker = raw[i + 1].toInt() and 0xff
                if (marker == 0xFF || marker == 0xD8 || marker in 0x01..0xBF) { i += 2; continue }
                val len = ((raw[i + 2].toInt() and 0xff) shl 8) or (raw[i + 3].toInt() and 0xff)
                if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    if (i + 9 >= raw.size) return null
                    val h = ((raw[i + 5].toInt() and 0xff) shl 8) or (raw[i + 6].toInt() and 0xff)
                    val w = ((raw[i + 7].toInt() and 0xff) shl 8) or (raw[i + 8].toInt() and 0xff)
                    return if (w > 0 && h > 0) w to h else null
                }
                i += 2 + len
            }
        }
        return null
    }

    private fun be32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xff) shl 24) or ((b[off + 1].toInt() and 0xff) shl 16) or
            ((b[off + 2].toInt() and 0xff) shl 8) or (b[off + 3].toInt() and 0xff)
}
