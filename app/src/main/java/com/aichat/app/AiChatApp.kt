package com.aichat.app

import android.app.Application
import android.net.Uri
import com.aichat.app.data.DataGraph
import com.aichat.app.data.migration.StoreMigrationService
import com.aichat.app.data.secret.SecretKeyVault
import com.aichat.app.data.store.JsonFileStore
import com.aichat.app.domain.*
import com.aichat.app.plugins.PluginRegistryImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** 手写 DI 根（D 所有）。各工作流把实现挂在这里；不引入 Hilt，减少构建内存与 KSP。 */
class AiChatApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.startMigration()
    }
}

/**
 * 整合者裁定 #a：AppGraph 暴露 chats / settings / library / keys / engine / plugins / migration，以及 B/C 需要的小仓库。
 * engine、plugins 在 B/C 合并前是占位实现（[UnwiredChatEngine] / EmptyPluginRegistry），合并时由 D 在这里替换为真实实现。
 */
class AppGraph(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 旧版 NativeBridge 的 dataDir：filesDir/store —— 原地沿用，不搬家 */
    private val fileStore = JsonFileStore(File(app.filesDir, "store"))

    /** B：图片外置 CAS（Message.images 存 img:<sha1> 引用，字节落 store/blobs/） */
    private val blobs = com.aichat.app.data.store.BlobStore(fileStore)
    private val secretVault = SecretKeyVault(SecretStore(app))
    val data = DataGraph(
        store = fileStore, keys = secretVault, backupDir = File(app.filesDir, "backup"),
        uriIo = object : StoreMigrationService.UriIo {
            override fun openOut(uri: Uri) = app.contentResolver.openOutputStream(uri, "wt")
            override fun openIn(uri: Uri) = app.contentResolver.openInputStream(uri)
        },
        providerMigration = com.aichat.app.net.ProviderMigration.hook,   // B：Providers.migrateSettings（首启迁移里跑）
        fromVersionCode = { 0 }, // 旧版本号覆盖安装后已不可得；0 = 未知（报告里以 store 内容为准）。见 PLAN DEC-5
    )

    // ---- 数据（D） ----
    val store get() = data.store
    val chats: ChatRepository get() = data.chats
    val settings: SettingsRepository get() = data.settings
    val library: LibraryRepository get() = data.library
    val keys: KeyVault get() = secretVault
    val usage: UsageRepository get() = data.usage
    /** C 的 CharCardCodec 插槽（合并 C 时在这里赋值）；未接入为 null */
    var charCard: com.aichat.app.domain.rp.CharCardCodec?
        get() = data.charCard
        set(v) { data.charCard = v }
    val migration: MigrationService get() = data.migration
    val convLog get() = data.convLog
    val knowledgeStore get() = data.knowledge
    val rpLogStore get() = data.rpLog
    val routerStateStore get() = data.routerState
    val balanceCacheStore get() = data.balanceCache
    val rpMemoryStore get() = data.rpMemory

    /** knowledge_world_<worldId>.json 的 [com.aichat.app.plugins.knowledge.Knowledge.Store] 适配（C 的 Knowledge 只认 get/set） */
    val knowledge = com.aichat.app.plugins.knowledge.JsonKnowledgeStore(
        read = { worldId -> data.knowledge.load(worldId) },
        write = { worldId, doc -> data.knowledge.save(worldId, doc) },
    )

    // ---- 引擎 / 插件 ----
    /**
     * OPT-3：插件注册表**只允许一个实例**。engine 与 uiDeps() 共用同一个对象，
     * 否则引擎看到的插件状态和 UI 看到的是两份，开关会「点了没反应」。
     *
     * W2：换掉 EmptyPluginRegistry。注册顺序由 [PluginRegistryImpl.ORDER] 固定为
     * knowledge → rpcache → usage → gcache → mmcache → affect；目前有实现的是
     * knowledge / usage / gcache / mmcache，rpcache 与 affect 仍缺席（缺席即不生效，请求逐字节不变）。
     * 注意：engine 构造时捕获的是引用本身，构造之后再给 [plugins] 赋新对象**不会**传进引擎。
     */
    var plugins: PluginRegistry = PluginRegistryImpl.createDefault(
        knowledge = { host ->
            // JS 的面板用模块级 selectedWorldId；原生暂无面板 → 用「世界列表第一个」兜底（knowledge-plugin.js pickWorld）
            com.aichat.app.plugins.knowledge.KnowledgePlugin(host, knowledge) {
                data.library.worlds.value.firstOrNull()?.id
            }
        },
        // 结构化记忆：默认关（旧版 defaultEnabled:false）；开关在设置 → 插件里，打开后每 N 轮抽一次
        rpcache = { host ->
            com.aichat.app.plugins.rp.RpCachePlugin(
                host,
                ask = { conv, prompt, model -> askModel(conv, prompt, model, com.aichat.app.domain.cost.BypassTask.CHAPTER_SUMMARY) },
                facts = { conv -> factsOf(conv) },
                resolveImage = { ref -> blobs.dataUrl(ref) },
            )
        },
        memory = { host ->
            // W2（B3 修复）：第三参此前被丢弃——summary_model 省钱档对记忆抽取完全无效；现在透传
            com.aichat.app.plugins.rp.RpMemoryPlugin(host, data.rpMemory) { conv, prompt, model ->
                askModel(conv, prompt, model, com.aichat.app.domain.cost.BypassTask.MEMORY_EXTRACT)
            }
        },
    )

    /**
     * 本对话的事实账本（`rp-memory.json` 里 `ledger`），渲染成 `[MEMORY_LEDGER]` 行文本。
     *
     * 只读纯渲染，不做抽取；抽取在 `RpMemoryPlugin` 里按轮次触发。
     * 桶键与记忆插件同源（[com.aichat.app.plugins.rp.RpMemoryCore.charKey]）：
     * 有角色卡时跨对话共享事实，否则按对话隔离。
     */
    private fun factsOf(conv: com.aichat.app.domain.model.Conversation): String = try {
        val key = com.aichat.app.plugins.rp.RpMemoryCore.charKey(
            conv.id,
            conv.personaName?.takeIf { it.isNotEmpty() },
        )
        val bucket = data.rpMemory.load()[key] as? kotlinx.serialization.json.JsonObject
        val ledger = (com.aichat.app.plugins.js.Js.fromJson(bucket?.get("ledger")) as? List<*>)
            ?.mapNotNull { it as? com.aichat.app.plugins.js.JObj } ?: emptyList()
        com.aichat.app.plugins.rp.RpMemoryCore.renderLedger(ledger)
    } catch (e: Throwable) {
        ""
    }

    /**
     * 插件用的「顺手问一次模型」（旧版 host.callModel）：非流式、温度 0，
     * 出参上限 / 思考档 / 超时按 [task] 分档（PLAN-COST-OPTIMIZATION W1/W2：摘要 1200/off、记忆 800/off、AutoChar 2048/auto）。
     * 失败返回 null（调用方负责把 lastError 记下来并推进 lastTurn，避免每轮重试烧钱）。
     *
     * [overrideModel] 非空时用它代替对话模型（`settings.summary_model`，省钱档摘要/记忆）。
     *
     * W1（C1）：旁路 usage 此前被 filterIsInstance 丢弃、token 完全不入账（审计 A1/A5），
     * 现在经 [BypassUsageEntry.of] 独立入账——type = 任务类型、model = 实际使用的模型；
     * 失败 / 超时 / 无 usage 不记账、不伪造 0 成本。
     */
    private suspend fun askModel(
        conv: com.aichat.app.domain.model.Conversation,
        prompt: String,
        overrideModel: String? = null,
        task: com.aichat.app.domain.cost.BypassTask = com.aichat.app.domain.cost.BypassTask.CHAPTER_SUMMARY,
    ): String? = runCatching {
        val s = data.settings.settings.value
        val p = s.providers.firstOrNull { it.id == conv.providerId } ?: return@runCatching null
        val model = overrideModel?.takeIf { it.isNotEmpty() } ?: conv.model
        if (model.isEmpty()) return@runCatching null
        val req = providerClient.buildChatRequest(
            p, model,
            listOf(com.aichat.app.domain.WireMessage(com.aichat.app.domain.model.Role.USER, prompt)),
            "", com.aichat.app.domain.ChatOptions(
                stream = false, temperature = 0.0, maxTokens = task.maxTokens, thinkingLevel = task.thinkingLevel,
            ),
        )
        val (st, body) = providerClient.requestText(p, req, task.timeoutMs)
        if (st >= 400 || st == 0) return@runCatching null
        val deltas = com.aichat.app.net.Deltas.parseFullResponse(p.type.wire, body)
        val text = deltas.filterIsInstance<com.aichat.app.net.StreamDelta.Text>()
            .joinToString("") { it.text }
            .takeIf { it.isNotBlank() } ?: return@runCatching null
        deltas.filterIsInstance<com.aichat.app.net.StreamDelta.UsageDelta>().firstOrNull()?.let { du ->
            runCatching {
                val entry = com.aichat.app.domain.cost.BypassUsageEntry.of(task, model, p, conv, du) ?: return@let
                if (data.usage.meta().isEmpty()) {
                    data.usage.saveMeta(com.aichat.app.domain.cost.UsageStatsCore.metaOf(com.aichat.app.domain.cost.UsageStatsCore.emptyState()))
                }
                data.usage.append(entry)
            }
        }
        text
    }.getOrNull()
    val providerClient = com.aichat.app.net.DefaultProviderClient(secretVault)
    /** 生图客户端（providers.js 的 buildImageRequest / parseImageResponse） */
    val imageClient = com.aichat.app.net.DefaultImageClient(secretVault)
    /** 余额查询：deepseek / siliconflow / moonshot / openrouter 四家有接口，其余只给控制台链接 */
    val balanceClient = com.aichat.app.domain.cost.DefaultBalanceClient(com.aichat.app.net.AuthInjector(secretVault))
    /** OpenRouter 缓存亲和路由（app.js routerState，持久化在 router-affinity.json） */
    val affinityRouter = com.aichat.app.net.AffinityRouter(initial = data.routerState.load(), save = { data.routerState.save(it) })
    var engine: ChatEngine = com.aichat.app.engine.DefaultChatEngine(
        chats = data.chats, settingsRepo = data.settings, client = providerClient, plugins = plugins,
        router = affinityRouter, onUsage = { conv, provider, model, msg, usage -> recordUsage(conv, provider, model, msg, usage) },
        // W1：接联网搜索（app.js webSearch）。Key 由 AuthInjector 按 search:<provider> 注入；
        // 开关仍是 settings.searchOn，未配置 Key 时 ChatController 会先弹「去设置搜索」。
        search = com.aichat.app.net.DefaultSearchClient(secretVault),
        blobs = blobs,
    )

    init {
        // B：UI 的 ImageLoader 是 object 拿不到 graph；挂一个 img: → data: 解析器供气泡显示
        com.aichat.app.data.store.BlobGate.dataUrl = { ref -> blobs.dataUrl(ref) }
    }

    /**
     * app.js `recordUsage`：把一次请求的用量追加到 usage-stats.jsonl（meta 不存在时先写）。
     *
     * OPT-1：字段拼装 / 默认值 / 去重键格式全部交给 [UsageEntry] + [UsageStatsCore.makeEntry]，
     * 这里不再手写第二份（原来手写那份的 `key`、`id` 随机段、`actualCost` 空值处理都与 JS 各自独立实现，必然漂移）。
     */
    private suspend fun recordUsage(conv: com.aichat.app.domain.model.Conversation, p: com.aichat.app.domain.model.ProviderConfig,
                                    model: String, msg: com.aichat.app.domain.model.Message, u: com.aichat.app.domain.model.Usage) {
        val entry = com.aichat.app.domain.cost.UsageEntry.from(conv, p, model, msg, u) ?: return
        if (data.usage.meta().isEmpty()) {
            data.usage.saveMeta(com.aichat.app.domain.cost.UsageStatsCore.metaOf(com.aichat.app.domain.cost.UsageStatsCore.emptyState()))
        }
        data.usage.append(entry)
    }

    /** A 的 UI 依赖（真实实现） */
    fun uiDeps() = com.aichat.app.ui.common.UiDeps(chats = chats, settings = settings, library = library, keys = keys,
        engine = engine, plugins = plugins, migration = migration, isFake = false, charCard = charCard, usage = usage,
        image = imageClient, rpMemory = data.rpMemory, balance = balanceClient, balanceCache = data.balanceCache, knowledge = knowledge, client = providerClient,
        autoCharAsk = { conv, prompt -> askModel(conv, prompt, null, com.aichat.app.domain.cost.BypassTask.AUTO_CHAR) })

    // ---- 首次启动迁移：后台执行，不阻塞启动；报告给设置 →「数据与备份」 ----
    private val _migrationReport = MutableStateFlow<MigrationReport?>(null)
    val migrationReport: StateFlow<MigrationReport?> = _migrationReport

    fun startMigration() {
        appScope.launch {
            try {
                if (data.migration.needed()) {
                    _migrationReport.value = data.migration.run()
                    data.reloadAll()
                }
            } catch (e: Throwable) {
                _migrationReport.value = MigrationReport(0, 0, 0, emptyList(), null, listOf("迁移异常：${e.message}"))
            }
            // 迁移可能刚刚写入 search:<id> 的 Key，但不会写 hasKeys —— 这里统一校正
            syncSearchKeys()
        }
    }

    /**
     * 修 BUG「搜索就绪双源」：把 `settings.search.hasKeys` 与 KeyVault 的真实状态对齐。
     *
     * 引擎读缓存（`SearchCore.cfgOf`），设置页读 KeyVault（`SearchConfig.ready`）；
     * 不一致时表现为「设置页显示已就绪、发送时却静默不走搜索」，且 `SearchCore.keyId(cfg)`
     * 也为 null（请求不带 Authorization）。这里做一次性对账，engine / client / UI 三处自动一致。
     * 无差异时不写盘（[com.aichat.app.net.SearchKeySync.merged] 返回 null）。
     */
    suspend fun syncSearchKeys() {
        val s = data.settings.settings.value
        val merged = com.aichat.app.net.SearchKeySync.merged(s.search) { id -> keys.has("search:$id") } ?: return
        data.settings.update { it.copy(search = merged) }
    }
}
