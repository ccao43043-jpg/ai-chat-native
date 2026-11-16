package com.aichat.app.plugins

import com.aichat.app.plugins.cache.CacheCommonImpl
import com.aichat.app.plugins.js.jobj
import com.aichat.app.plugins.rp.RpCacheCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** 统一 token 估算（advisory §10.2①）：文本尺子唯一化 + 图片按服务商计价。 */
class TokenEstimatorTest {

    // ---------------- 文本：与原 RpCacheCore.estimateTokens 逐档一致 ----------------

    @Test
    fun textClassesMatchLegacyRuler() {
        assertEquals(0, TokenEstimator.text(""))
        assertEquals(1, TokenEstimator.text("中"))          // 0.6 → ceil
        assertEquals(2, TokenEstimator.text("中中"))        // 1.2 → ceil
        assertEquals(1, TokenEstimator.text("a"))           // 0.3 → ceil
        assertEquals(2, TokenEstimator.text("123"))         // 3 × 0.5 = 1.5 → ceil
        assertEquals(1, TokenEstimator.text("a b"))         // 0.3+0.1+0.3 = 0.7 → ceil
        assertEquals(1, TokenEstimator.text("。"))          // U+3002 落 CJK 档 0.6
        assertEquals(1, TokenEstimator.text("！"))          // U+FF01 落全角档 0.6
        assertEquals(2, TokenEstimator.text("😀"))          // 代理对按 1 码点算，else 档 1.5 → ceil
        assertEquals(1, TokenEstimator.text("é"))           // <0x3000 非字母数字 → 0.6
        assertEquals(3, TokenEstimator.text("undefined"))   // 9 字母 × 0.3 = 2.7 → ceil
    }

    /** 统一后三处入口必须同值（同一段中英混排两把旧尺子差约 40%，现在只有一把） */
    @Test
    fun singleRulerAcrossRpAndCache() {
        val s = "海港城的灯塔是红色的，The lighthouse is red. 12345。"
        assertEquals(RpCacheCore.estimateTokens(s), TokenEstimator.text(s))
        assertEquals(CacheCommonImpl.estTokens(s), TokenEstimator.text(s))
        assertEquals(CacheCommonImpl.estTokens(s), RpCacheCore.estimateTokens(s))
    }

    // ---------------- 图片：按服务商计价 ----------------

    /** 1024×1024 PNG（IHDR 里写宽高；CRC 解析端不校验，填 0 即可） */
    private fun pngB64(w: Int, h: Int): String {
        val b = java.io.ByteArrayOutputStream()
        b.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        b.write(byteArrayOf(0, 0, 0, 0x0D)); b.write("IHDR".toByteArray())
        b.write(byteArrayOf((w shr 24).toByte(), (w shr 16).toByte(), (w shr 8).toByte(), w.toByte()))
        b.write(byteArrayOf((h shr 24).toByte(), (h shr 16).toByte(), (h shr 8).toByte(), h.toByte()))
        b.write(byteArrayOf(8, 2, 0, 0, 0)); b.write(byteArrayOf(0, 0, 0, 0))
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(b.toByteArray())
    }

    /** 最小 JPEG：SOI + SOF0（里写宽高），SOF0 之后的段省略 */
    private fun jpegB64(w: Int, h: Int): String {
        val b = java.io.ByteArrayOutputStream()
        b.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        b.write(byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0, 0x11, 8))
        b.write(byteArrayOf((h shr 8).toByte(), h.toByte(), (w shr 8).toByte(), w.toByte()))
        b.write(byteArrayOf(3, 1, 0x22, 0, 2, 1, 1, 0, 0, 0, 0, 0, 0, 0))
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(b.toByteArray())
    }

    @Test
    fun geminiIsFlat258() {
        assertEquals(258, TokenEstimator.image("gemini", pngB64(1024, 1024)))
        assertEquals(258, TokenEstimator.image("google-gemini", null))
    }

    @Test
    fun anthropicUsesPixelArea() {
        // (1024×1024)/750 = 1398.09 → 1398（整数除法向下），下限 156
        assertEquals(1398, TokenEstimator.image("anthropic", pngB64(1024, 1024)))
        assertEquals(1398, TokenEstimator.image("claude", jpegB64(1024, 1024)))
        assertEquals(156, TokenEstimator.image("anthropic", pngB64(320, 320)))   // 136.5 → 下限托底
    }

    @Test
    fun openaiUses512Tiles() {
        // ceil(1024/512)=2 × 2 tiles → 85 + 170×4 = 765
        assertEquals(765, TokenEstimator.image("openai", pngB64(1024, 1024)))
        // ceil(513/512)=2 × 1 → 85 + 170×2 = 425
        assertEquals(425, TokenEstimator.image("openai-compatible", jpegB64(513, 300)))
    }

    @Test
    fun unknownOrBrokenFallsBackTo1000() {
        assertEquals(1000, TokenEstimator.image(null, pngB64(1024, 1024)))
        assertEquals(1000, TokenEstimator.image("deepseek", pngB64(1024, 1024)))
        assertEquals(1000, TokenEstimator.image("openai", "data:image/png;base64,!!!not-base64!!!"))
        assertEquals(1000, TokenEstimator.image("openai", "not-a-data-url"))
        assertEquals(1000, TokenEstimator.image("openai", null))
    }

    // ---------------- 消息序列：正文 + 4/条 + 图片 ----------------

    @Test
    fun messagesSumContentAndImages() {
        val m1 = jobj("role" to "user", "content" to "中中中", "images" to listOf(pngB64(1024, 1024)))
        val m2 = jobj("role" to "assistant", "content" to "hi")
        // provider 未知 → 旧口径 1000/张：ceil(1.8)=2 + 4 + 1000 = 1006；m2 = ceil(0.6)+4 = 5 → 1011
        assertEquals(1011, TokenEstimator.messages(listOf(m1, m2)))
        // gemini：2 + 4 + 258 = 264；m2 5 → 269
        assertEquals(269, TokenEstimator.messages(listOf(m1, m2), "gemini"))
    }

    /** 与原 estimateItems 在「provider 未知」口径下完全一致（golden 兼容前提） */
    @Test
    fun messagesMatchesLegacyEstimateItemsWithoutProvider() {
        val items = listOf(
            jobj("role" to "user", "content" to "海边有个渔村，灯塔是红色的"),
            jobj("role" to "assistant", "content" to "好", "images" to listOf(pngB64(64, 64), jpegB64(64, 64))),
            "not-an-object",
        )
        assertEquals(RpCacheCore.estimateItems(items), TokenEstimator.messages(items))
    }

    @Test
    fun largeImageOnlyDecodesHead() {
        // 合法 PNG 头 + 2MB 乱码尾部：尺寸解析只看头部 24 字节，不应解码整个 base64
        val head = pngB64(1024, 1024).substringAfter("base64,")
        val big = "data:image/png;base64," + head + Base64.getEncoder().encodeToString(ByteArray(2_000_000) { (it % 251).toByte() })
        assertEquals(1398, TokenEstimator.image("anthropic", big))
    }
}
