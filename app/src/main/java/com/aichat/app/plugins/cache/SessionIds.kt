package com.aichat.app.plugins.cache

import com.aichat.app.plugins.js.Js

/**
 * 统一 session_id 入口（advisory §10.3）：GCache 的 `aichat-` 前缀派生与 cache-common 的
 * 标准 UUID 派生此前散在两处、易误用；收口到一个对象按 adapter 选择。
 * **生成值保持逐字节不变**——session_id 已进入 provider 的粘性机制，换派生会让全部会话
 * 一次性重置缓存（OpenRouter sticky / Gemini 显式缓存都要重新命中）。
 */
object SessionIds {
    /** gcache（Gemini 显式缓存 / OpenRouter）：`aichat-` 前缀 + UUIDv4 形状（gcache.js 同款，勿改派生） */
    fun gcache(convId: Any?): String {
        if (convId == null || convId == "") return ""
        val s = Js.str(convId)
        val h = CacheCommonImpl.hash(s) + CacheCommonImpl.hash("a$s") + CacheCommonImpl.hash("b$s") + CacheCommonImpl.hash("c$s")
        return "aichat-" + h.substring(0, 8) + "-" + h.substring(8, 12) + "-4" + h.substring(13, 16) + "-8" + h.substring(17, 20) + "-" + h.substring(20, 32)
    }

    /** mmcache / 通用：cache-common 的标准 UUID 派生（sessionIdFor(convId, ns)） */
    fun common(convId: String, ns: String): String = CacheCommonImpl.sessionIdFor(convId, ns)
}
