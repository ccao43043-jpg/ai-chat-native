package com.aichat.app.plugins

import com.aichat.app.domain.model.AppSettings
import com.aichat.app.domain.model.Conversation

/**
 * 「这个插件在本会话里到底跑没跑」（PLAN-CONTEXT-ARCH §3.3 的可见性部分）。
 *
 * 为什么需要它：插件页的开关绑的是**全局** `settings.plugins[id].enabled`，
 * 而插件是否执行由 `isGloballyEnabled && (perConversation ? autoActive : ...)` 决定。
 * 于是出现「开关亮着，但原生 Gemini 上的 gcache 一行都没执行」这种不诚实的界面
 * ——用户问了两次「为什么两个缓存插件一起开」，根因就在这里。
 *
 * 做法：注册表在真实请求时把判定结果记一份**进程内**快照（不落盘：这是「这次运行的事实」，
 * 持久化反而会在重启后骗人），设置页只读快照。没有快照时界面明说「还没发过请求」。
 */
object PluginActivation {
    data class Row(val pluginId: String, val active: Boolean, val reason: String)

    data class Snapshot(
        val convId: String,
        val convTitle: String,
        val model: String,
        val at: Long,
        val rows: List<Row>,
        /** 本会话缓存路线（来自 CachePolicy）：gcache / mmcache / prefix / none，以及一句话原因 */
        val cacheOwner: String = "",
        val cacheReason: String = "",
    )

    @Volatile
    var last: Snapshot? = null
        private set

    /** 纯记录入口（单测走这条，不依赖注册表） */
    fun record(
        convId: String,
        convTitle: String,
        model: String,
        now: Long,
        rows: List<Row>,
        cacheOwner: String = "",
        cacheReason: String = "",
    ) {
        last = Snapshot(
            convId = convId,
            convTitle = convTitle.ifBlank { "未命名对话" },
            model = model,
            at = now,
            rows = rows,
            cacheOwner = cacheOwner,
            cacheReason = cacheReason,
        )
    }

    fun record(
        conv: Conversation,
        model: String,
        settings: AppSettings,
        registry: PluginRegistryImpl,
        now: Long,
        cacheOwner: String = "",
        cacheReason: String = "",
    ) {
        val rows = registry.plugins.map { p ->
            val active = registry.isActive(p.id, settings, conv)
            Row(
                pluginId = p.id,
                active = active,
                reason = if (active) "" else registry.inactiveReason(p, conv, settings),
            )
        }
        last = Snapshot(
            convId = conv.id,
            convTitle = conv.title.ifBlank { "未命名对话" },
            model = model,
            at = now,
            rows = rows,
            cacheOwner = cacheOwner,
            cacheReason = cacheReason,
        )
    }

    fun rowOf(pluginId: String): Row? = last?.rows?.firstOrNull { it.pluginId == pluginId }

    fun reset() {
        last = null
    }
}
