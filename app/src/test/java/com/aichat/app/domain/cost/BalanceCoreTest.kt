package com.aichat.app.domain.cost

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 余额查询的纯函数层，期望值全部取自旧版 `app/src/main/assets/www/balance.js` 的实跑结果
 * （见 tools/balance-golden/README.md 的生成命令），不是手写推演。
 */
class BalanceCoreTest {

    private fun JsonObject.s(k: String): String = (this[k] as? JsonPrimitive)?.content.orEmpty()

    private fun JsonObject.item(label: String, text: String) = JsonObject(linkedMapOf("label" to JsonPrimitive(label), "text" to JsonPrimitive(text)))

    @Test
    fun deepseekBalanceParsesPrimaryAndDetails() {
        val text = """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"88.80","granted_balance":"8.80","topped_up_balance":"80.00"}]}"""
        val r = BalanceCore.parseBalanceResponse("deepseek", 200, text)
        assertEquals("true", r.s("ok"))
        val primary = r["primary"] as JsonObject
        assertEquals("总余额", primary.s("label"))
        assertEquals("¥88.80", primary.s("text"))
        assertEquals("CNY", r.s("currency"))
        val details = r["details"] as JsonArray
        assertEquals(4, details.size)
        assertEquals("CNY 赠送", ((details[1] as JsonObject).s("label")))
        assertEquals("¥80.00", ((details[2] as JsonObject).s("text")))
        assertEquals("https://platform.deepseek.com/usage", r.s("consoleUrl"))
    }

    @Test
    fun emptyBalanceListIsAnError() {
        val r = BalanceCore.parseBalanceResponse("deepseek", 200, """{"is_available":true,"balance_infos":[]}""")
        assertEquals("false", r.s("ok"))
        assertEquals("未返回余额信息", r.s("error"))
    }

    @Test
    fun unauthorizedKeyText() {
        val r = BalanceCore.parseBalanceResponse("deepseek", 401, """{"error":{"message":"invalid key"}}""")
        assertEquals("false", r.s("ok"))
        assertEquals("API Key 无效或无权限（HTTP 401）", r.s("error"))
    }

    @Test
    fun siliconflowMoonshotOpenrouter() {
        val sf = BalanceCore.parseBalanceResponse(
            "siliconflow", 200, """{"code":200,"data":{"totalBalance":"12.3456","balance":"10.0000","chargeBalance":"2.3456"}}""",
        )
        assertEquals("¥12.35", (sf["primary"] as JsonObject).s("text"))
        assertEquals(3, (sf["details"] as JsonArray).size)

        val ms = BalanceCore.parseBalanceResponse(
            "moonshot", 200, """{"data":{"available_balance":5.5,"voucher_balance":1.5,"cash_balance":4.0}}""",
        )
        assertEquals("可用余额", (ms["primary"] as JsonObject).s("label"))
        assertEquals("¥5.50", (ms["primary"] as JsonObject).s("text"))

        val or = BalanceCore.parseBalanceResponse(
            "openrouter", 200, """{"data":{"label":"sk","limit":10,"limit_remaining":7.25,"usage":2.75}}""",
        )
        assertEquals("$7.25", (or["primary"] as JsonObject).s("text"))
        assertEquals("USD", or.s("currency"))
        assertEquals(4, (or["details"] as JsonArray).size)
    }

    @Test
    fun unsupportedProviderSaysSoInsteadOfPretending() {
        val r = BalanceCore.parseBalanceResponse("custom", 200, "{}")
        assertEquals("false", r.s("ok"))
        assertEquals("true", r.s("unsupported"))
        assertTrue(r.s("error").contains("未提供可用的余额查询接口"))
    }

    @Test
    fun requestUrlsMatchLegacy() {
        fun url(preset: String, base: String) = requireNotNull(BalanceCore.buildBalanceRequest(preset, base, hasKey = true))
        assertEquals("https://api.deepseek.com/user/balance", url("deepseek", "https://api.deepseek.com").url)
        assertEquals("https://api.siliconflow.cn/v1/user/info", url("siliconflow", "https://api.siliconflow.cn/v1/").url)
        assertEquals("https://api.moonshot.cn/v1/users/me/balance", url("moonshot", "https://api.moonshot.cn/v1").url)
        assertEquals("https://openrouter.ai/api/v1/key", url("openrouter", "https://openrouter.ai/api/v1").url)
        assertEquals("GET", url("deepseek", "https://api.deepseek.com").method)
        assertNull(BalanceCore.buildBalanceRequest("custom", "http://x", hasKey = true))
        assertNull(BalanceCore.buildBalanceRequest("deepseek", "https://api.deepseek.com", hasKey = false))
    }

    @Test
    fun deepseekRootStripsVersionSuffix() {
        assertEquals("https://api.deepseek.com", BalanceCore.deepseekRoot("https://api.deepseek.com/v1/"))
        assertEquals("https://api.deepseek.com", BalanceCore.deepseekRoot("https://api.deepseek.com"))
        // 代理站常见的 /v1 尾巴也要吃掉，否则会拼成 /v1/user/balance
        assertEquals("https://proxy.example.com/ds", BalanceCore.deepseekRoot("https://proxy.example.com/ds/v1"))
    }

    @Test
    fun supportInfoCoversFourVendors() {
        listOf("deepseek", "siliconflow", "moonshot", "openrouter").forEach {
            assertTrue(it, BalanceCore.supportInfo(it).supported)
        }
        assertTrue(!BalanceCore.supportInfo("custom").supported)
        assertTrue(!BalanceCore.supportInfo(null).supported)
        assertNotNull(BalanceCore.supportInfo("deepseek").consoleUrl)
    }
}
