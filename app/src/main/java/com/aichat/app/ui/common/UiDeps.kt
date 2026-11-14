package com.aichat.app.ui.common

import androidx.compose.runtime.staticCompositionLocalOf
import com.aichat.app.domain.ChatEngine
import com.aichat.app.domain.ChatRepository
import com.aichat.app.domain.KeyVault
import com.aichat.app.domain.LibraryRepository
import com.aichat.app.domain.MigrationService
import com.aichat.app.domain.PluginRegistry
import com.aichat.app.domain.SettingsRepository

/**
 * UI 层（工作流 A）的全部依赖——只有契约接口，不碰 net/data 实现类（spec §3.3）。
 * 由 MainActivity 组装：AppGraph 有真实实现就用真实的，否则用 ui/common/fake 的内存实现。
 */
data class UiDeps(
    val chats: ChatRepository,
    val settings: SettingsRepository,
    val library: LibraryRepository,
    val keys: KeyVault,
    val engine: ChatEngine,
    val plugins: PluginRegistry,
    val migration: MigrationService?,
    /** true = 当前用的是 fake 实现（顶栏显示「演示数据」提示） */
    val isFake: Boolean = false,
    /** C 的角色卡编解码（charcard.js）；未接入时为 null，助手库的导入 / 导出给出提示 */
    val charCard: com.aichat.app.domain.rp.CharCardCodec? = null,
    /** 用量统计（usage-stats.jsonl + meta）；为 null 时用量页显示空态而不是崩 */
    val usage: com.aichat.app.domain.UsageRepository? = null,
    /** 生图客户端；为 null 时生图页显示「未就绪」而不是崩 */
    val image: com.aichat.app.domain.ImageClient? = null,
    /** 记忆文件（rp-memory.json）；为 null 时记忆面板只读空态 */
    val rpMemory: com.aichat.app.data.repo.JsonDocStore? = null,
    /** 余额查询客户端（deepseek / siliconflow / moonshot / openrouter）；为 null 时余额页显示未就绪 */
    val balance: com.aichat.app.domain.cost.DefaultBalanceClient? = null,
    /** 知识库后端（knowledge_world_<worldId>.json）；为 null 时知识库页显示未就绪 */
    val knowledge: com.aichat.app.plugins.knowledge.Knowledge.Store? = null,
    /** 余额缓存（balance-cache.json） */
    val balanceCache: com.aichat.app.data.repo.JsonDocStore? = null,
)

val LocalUiDeps = staticCompositionLocalOf<UiDeps> { error("UiDeps 未提供") }
