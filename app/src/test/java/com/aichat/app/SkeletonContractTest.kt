package com.aichat.app

import com.aichat.app.data.secret.SecretKeyVault
import com.aichat.app.data.store.JsonFileStore
import com.aichat.app.net.StreamDelta
import com.aichat.app.net.StreamParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SkeletonContractTest {
    @Test fun sseSplitsAcrossChunks() {
        // 原测 SseLineParser（已删，与 StreamParser 是同一件事的两种写法）；改为直接测真在用的实现。
        val p = StreamParser("openai")
        assertTrue(p.push("data: {\"choices\":[{\"delta\":{\"content\":\"a\"").isEmpty())
        val d = p.push("}}]}\r\n\r\n: ping\n\n")
        assertEquals(listOf("a"), d.filterIsInstance<StreamDelta.Text>().map { it.text })
        assertTrue(p.push("data: [DONE]\n\n").isEmpty())
        assertTrue(p.done)
    }

    @Test fun originMatchesLegacyRules() {
        assertEquals("https://openrouter.ai", SecretKeyVault.originOf("https://OpenRouter.ai:443/api/v1"))
        assertEquals("http://10.0.2.2:8080", SecretKeyVault.originOf("http://10.0.2.2:8080/v1"))
        assertNull(SecretKeyVault.originOf("https://user@host/x"))
        assertNull(SecretKeyVault.originOf("file:///x"))
    }

    @Test fun fileStoreKeepsBakAndSanitizes() {
        val dir = Files.createTempDirectory("store").toFile()
        val s = JsonFileStore(dir)
        assertTrue(s.set("settings.json", "{\"v\":1}"))
        assertTrue(s.set("settings.json", "{\"v\":2}"))
        assertEquals("{\"v\":1}", s.get("settings.json.bak"))
        assertTrue(s.append("a.jsonl", "1\n")); assertTrue(s.append("a.jsonl", "2\n"))
        assertEquals("1\n2\n", s.get("a.jsonl"))
        assertTrue(s.set("conv_a/b.json", "x")); assertTrue(s.exists("conv_a_b.json"))
        assertFalse(s.list().any { it.endsWith(".tmp") })
    }
}
