package com.aichat.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 输入框预填 `https://` 时，粘贴完整 URL 会拼成 `https://http://…`。
 * [collapseScheme] 负责把这个重复 scheme 压掉，但**不能**误伤正常域名。
 */
class ProvidersScreenTest {

    @Test fun collapsesDuplicatedSchemeFromPaste() {
        assertEquals("http://10.0.2.2:8787/v1", collapseScheme("https://", "https://http://10.0.2.2:8787/v1"))
        assertEquals("https://api.example.com/v1", collapseScheme("https://", "https://https://api.example.com/v1"))
        assertEquals("http://api.example.com", collapseScheme("https://", "https://http://api.example.com"))
    }

    @Test fun leavesNormalTypingAlone() {
        assertEquals("https://", collapseScheme("", "https://"))
        assertEquals("https://api.example.com", collapseScheme("https://", "https://api.example.com"))
        assertEquals("https://api.example.com/v1", collapseScheme("https://api.example.com", "https://api.example.com/v1"))
        // 域名里带 https 不是 scheme
        assertEquals("https://httpsproxy.example.com", collapseScheme("https://", "https://httpsproxy.example.com"))
        assertEquals("", collapseScheme("https://", ""))
    }

    @Test fun noOpWhenValueUnchanged() {
        val v = "https://http://a"
        assertEquals(v, collapseScheme(v, v))
    }
}
