package com.aichat.app.net

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 「搜索就绪」双源修复。
 *
 * 背景：引擎侧的闸门是 `SearchCore.ready(SearchCore.cfgOf(settings))`，而 `cfgOf` 只读
 * `settings.search.hasKeys[provider]` 这份**缓存**；设置页的闸门是 `SearchConfig.ready(settings, keys)`，
 * 实时问 KeyVault。两份数据源一旦不一致（典型来源：迁移体检只补了 `search:<id>` 的 Key、没补 hasKeys；
 * 或用户从未打开过「联网搜索设置」页），就会出现「设置页允许发送、引擎静默跳过搜索」。
 *
 * 更麻烦的是 `SearchCore.keyId(cfg)` 也依赖 `cfg.hasKey`：hasKey 为假时请求根本不会带
 * Authorization，即使把闸门放宽也会 401。所以只在闸门处判断不够，必须让
 * `settings.search.hasKeys` 反映真实的 Key 存在状态。
 *
 * 本对象只做「算」：给定当前 search 配置与「某 provider 是否真的有 Key」，返回修正后的 search；
 * **结果与现有 hasKeys 完全相等时返回 null**，避免每次启动都写盘。
 */
object SearchKeySync {
    /**
     * @param search 当前的 `AppSettings.search`
     * @param hasKey 传入 provider id（tavily / bocha / brave / searxng），返回 KeyVault 里是否真有 `search:<id>`
     * @return 修正后的 search（hasKeys 含全部已知 provider）；无需修改时返回 null
     */
    fun merged(search: JsonObject, hasKey: (String) -> Boolean): JsonObject? {
        val cur = search["hasKeys"] as? JsonObject
        // 先继承已有条目（含用户手键的自定义键），再用真实 Key 状态覆盖已知 provider
        val next = LinkedHashMap<String, JsonElement>()
        cur?.forEach { (k, v) -> next[k] = v }
        for (p in SearchCore.SEARCH_PROVIDERS) {
            next[p.id] = JsonPrimitive(hasKey(p.id))
        }
        val normalized = JsonObject(next)
        // JsonObject 的 equals 不关心键序；相等说明没任何差异，不写盘
        if (normalized == cur) return null
        val out = LinkedHashMap<String, JsonElement>()
        search.forEach { (k, v) -> out[k] = v }
        out["hasKeys"] = normalized
        return JsonObject(out)
    }
}
