package com.aichat.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * EXPERIENCE-GUARD（2026-11-21）：TokenEstimator 的边界补缺。
 * 现有 9 例已覆盖 gemini/anthropic/openai 主路径与坏 base64；这里补「openrouter 子串陷阱 / 渐进式 JPEG / 零尺寸 / 空白档」。
 */
class TokenEstimatorEdgeTest {

    /** 手工拼一段最小 JPEG：FFD8 + SOF 段（marker 可传 0xC0 基线 / 0xC2 渐进），宽高写进段内标准偏移。 */
    private fun jpegB64(sofMarker: Int, w: Int, h: Int): String {
        val raw = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), sofMarker.toByte(), 0x00, 0x0A, 0x08,
            (h shr 8).toByte(), (h and 0xFF).toByte(),
            (w shr 8).toByte(), (w and 0xFF).toByte(),
            0x01, 0x22, 0x00, 0x00, 0x00,
        )
        return "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(raw)
    }

    @Test fun openrouterIsNotOpenaiSoFallsBack() {
        // 子串匹配陷阱：openrouter 不含 "openai"，必须走 FALLBACK 而不是 512-tile 公式
        assertEquals(1000, TokenEstimator.image("openrouter", null))
    }

    @Test fun progressiveJpegReadsDimensions() {
        // SOF2（渐进式）同样要读到宽高：anthropic 按像素面积 704*704/750 = 660
        assertEquals(660, TokenEstimator.image("anthropic", jpegB64(0xC2, 704, 704)))
    }

    @Test fun zeroSizeJpegFallsBackTo1000() {
        assertEquals(1000, TokenEstimator.image("anthropic", jpegB64(0xC0, 0, 0)))
    }

    @Test fun textCountsTabsAndNewlinesAsWhitespace() {
        assertEquals(1, TokenEstimator.text("\t\n\r"))
        assertEquals(2, TokenEstimator.text("ab\tcd"))
    }
}
