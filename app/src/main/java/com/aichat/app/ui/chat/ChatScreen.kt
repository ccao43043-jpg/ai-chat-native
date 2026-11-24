package com.aichat.app.ui.chat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aichat.app.plugins.rp.AutoChar
import com.aichat.app.plugins.rp.AutoCharService
import com.aichat.app.domain.model.Conversation
import com.aichat.app.net.ProviderRules
import com.aichat.app.ui.common.ConfirmDialog
import com.aichat.app.ui.common.EditorAction
import com.aichat.app.ui.common.FullScreenEditor
import com.aichat.app.ui.common.ImageViewer
import com.aichat.app.ui.library.PromptSheet
import com.aichat.app.ui.library.TemplateSheet
import com.aichat.app.ui.common.LocalUiDeps
import com.aichat.app.ui.common.Adaptive
import com.aichat.app.ui.common.pressScale
import com.aichat.app.ui.common.rememberIsWideScreen
import com.aichat.app.domain.Badge
import androidx.compose.ui.semantics.Role
import com.aichat.app.ui.common.rememberAnimationsOn
import com.aichat.app.ui.conversation.ConversationDrawer
import com.aichat.app.data.files.FileText
import com.aichat.app.engine.ChunkRetrieval
import com.aichat.app.engine.FileContext
import com.aichat.app.ui.settings.SettingsDestination
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.text.font.FontWeight
import com.aichat.app.ui.theme.Motion
import com.aichat.app.ui.theme.Palette
import com.aichat.app.plugins.js.Js
import com.aichat.app.plugins.js.JObj
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject

/**
 * 聊天页：顶栏（抽屉 / 标题 + 模型切换 / 更多）、消息列表（reverseLayout，流式时贴底）、输入区。
 * 返回键：看图 > 弹层/对话框（各自处理）> 抽屉 > 交给上层。
 */

@OptIn(ExperimentalMaterial3Api::class)@Composable
fun ChatScreen(
    controller: ChatController,
    onOpenSettings: (SettingsDestination) -> Unit,
    /** 顶栏「更多」菜单的挂载点：B/C 可以在这里追加项（用量、RP 面板等） */
    extraMenu: List<Pair<String, () -> Unit>> = emptyList(),
    /** 「据此生图」：把对话提炼出的提示词带去生图页 */
    onOpenImage: (String) -> Unit = {},
    /** 抽屉里的「生图」入口：不带提示词，直接进生图页 */
    onOpenImageTab: () -> Unit = {},
) {
    val deps = LocalUiDeps.current
    val st by controller.state.collectAsStateWithLifecycle()
    val index by controller.index.collectAsStateWithLifecycle()
    // 思考等级单独订阅：它不会被 conv 快照回写覆盖，弹层高亮以它为准
    val thinking by controller.thinking.collectAsStateWithLifecycle()
    // Flex 档同理单独订阅（按服务商）：弹层开关以「覆盖层 → settings → 当前模型串」为准
    val flex by controller.flex.collectAsStateWithLifecycle()
    val settings by controller.settings.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var input by rememberSaveable { mutableStateOf("") }
    var picker by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var plan by remember { mutableStateOf<Plan?>(null) }
    var versionMenu by rememberSaveable { mutableStateOf<String?>(null) }
    var panel by rememberSaveable { mutableStateOf<String?>(null) }
    var sheet by rememberSaveable { mutableStateOf(0) }          // 1 = 提示词库，2 = 模板
    var convPlugins by remember { mutableStateOf(false) }
    var rpPanel by remember { mutableStateOf(false) }
    var rpMemoryPanel by remember { mutableStateOf(false) }
    var rpInspect by remember { mutableStateOf(false) }
    var rpChapter by remember { mutableStateOf(false) }
    var rpLore by remember { mutableStateOf(false) }
    var affectPanel by remember { mutableStateOf(false) }
    var pluginErrPanel by remember { mutableStateOf(false) }
    var ctxInspect by remember { mutableStateOf(false) }
    // 用 AI 生成角色设定：AutoChar 那套纯函数一直没接线，新 RP 会话里用户从没被问过
    var autoOffer by remember(st.convId) { mutableStateOf(false) }
    var autoBusy by remember(st.convId) { mutableStateOf(false) }
    var autoProgress by remember(st.convId) { mutableStateOf<AutoCharService.Progress?>(null) }
    var autoOutcome by remember(st.convId) { mutableStateOf<AutoCharService.Outcome?>(null) }
    val autoChar = remember(deps) { deps.autoCharAsk?.let { AutoCharService(it) } }
    val roleChar = remember(deps) { (deps.roleBookAsk ?: deps.autoCharAsk)?.let { AutoCharService(it) } }
    val pluginErrs by deps.plugins.errors.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var exportId by remember { mutableStateOf<String?>(null) }
    val exporter = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val id = exportId; exportId = null
        if (uri == null || id == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = runCatching {
                val json = controller.exportJson(id)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)!!.use { it.write(json.toByteArray()) }
                }
            }
            controller.toast(if (r.isSuccess) "已导出对话" else "导出失败：${r.exceptionOrNull()?.message}")
        }
    }
    val exportConv: (String) -> Unit = { id ->
        exportId = id
        exporter.launch(controller.titleOf(id).replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(40) + ".json")
    }
    val listState = rememberLazyListState()

    LaunchedEffect(st.toast) { st.toast?.let { snack.showSnackbar(it); controller.consumeToast() } }
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

    val target = controller.currentTarget(settings)
    val messages = st.messages
    val conv = st.conv
    // 忙/闲只翻转「流开始 / 结束」两个沿——根部收集它，流式增量 tick 不再触发整屏重组（评审 §5.1）
    val busy by remember(st.convId) { controller.busyOf(st.convId) }.collectAsStateWithLifecycle(initialValue = false)

    // 新消息 / 切对话时回到底部。
    //
    // 旧版无条件 `scrollToItem(0)`，翻历史时会被弹走；上一版改成「只在底部时才滚」，
    // 结果最该贴底的场景反而失效：自己发出一条消息后，列表往往还停在首屏最上面
    // （`reverseLayout = true`，item 0 是最新消息，首屏 = 顶部），守卫判定「不在底部」→ 不跟随。
    // 现在分四种情况：
    //   1. 切对话：无条件贴底，并恢复跟随；
    //   2. 自己发出消息（条数变多且最新一条是 user）：无条件贴底，并恢复跟随；
    //   3. 其他增长（插件开场白、删除、编辑重发）：只在跟随状态下贴底；
    //   4. 用户手动往上翻（拖动中且不在底部附近）→ 停止跟随，直到他翻回底部或再发一条。
    var follow by remember(st.convId) { mutableStateOf(true) }
    var lastConvId by remember { mutableStateOf(st.convId) }
    var lastCount by remember { mutableStateOf(messages.size) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                // 只认用户拖动：程序化 animateScrollToItem 期间 isScrollInProgress 也为真，
                // 但那时 index/offset 一路趋近 0，不会误判成「翻走了」。
                if (!listState.isScrollInProgress) return@collect
                follow = index == 0 && offset < 80
            }
    }

    LaunchedEffect(st.convId, messages.size) {
        if (messages.isEmpty()) return@LaunchedEffect
        val switched = lastConvId != st.convId
        val grew = messages.size > lastCount
        val mine = grew && messages.lastOrNull()?.role == com.aichat.app.domain.model.Role.USER
        lastConvId = st.convId
        lastCount = messages.size
        if (switched) {
            follow = true
            listState.scrollToItem(0)
            return@LaunchedEffect
        }
        if (mine) follow = true
        if (follow) listState.animateScrollToItem(0)
    }

    // 抽屉底部各行的计数（旧版 v0.29 也展示这些）
    val personas by deps.library.personas.collectAsState()
    val prompts by deps.library.prompts.collectAsState()
    val templates by deps.library.templates.collectAsState()
    val worlds by deps.library.worlds.collectAsState()
    // 重启后先把上一次的检查点恢复成 Outcome，RP 面板才能显示已写入/失败批次并只重跑失败批。
    LaunchedEffect(st.convId, conv == null) {
        autoOutcome = conv?.let { restoreAutoOutcome(it, roleChar ?: autoChar) }
    }

    /**
     * AutoChar 的唯一执行入口：自动询问、手动生成、覆盖生成和失败批重跑都走这里。
     * 每批结束先落 rpState 检查点，最终再写状态与开场白；异常只在当前调用内失败，不会卡死 busy。
     */
    fun startAutoChar(
        svc: AutoCharService?,
        c: Conversation,
        automatic: Boolean,
        overwrite: Boolean = false,
        only: List<String>? = null,
    ) {
        if (svc == null) {
            controller.toast("角色生成未就绪：请先配置可用的服务商")
            return
        }
        if (autoBusy) return
        autoBusy = true
        autoOffer = false
        autoProgress = null
        if (!automatic && only == null) autoOutcome = null
        if (!automatic) controller.toast("正在分批生成角色书…")
        scope.launch {
            try {
                controller.saveAutoChar(
                    c.id,
                    meta = autoMetaJson(c, svc, status = "running", asked = true),
                )
                val result = svc.generate(
                    c, templates, settings, svc.skillOf(templates),
                    overwrite = overwrite,
                    only = only,
                    onProgress = { progress, checkpoint ->
                        autoProgress = progress
                        controller.saveAutoChar(
                            c.id,
                            rpState = Js.toJson(checkpoint) as? JsonObject,
                            meta = autoMetaJson(
                                c, svc, status = "running", asked = true, progress = progress,
                            ),
                        )
                    },
                )
                autoOutcome = result
                controller.saveAutoChar(
                    c.id,
                    rpState = result.state?.let { Js.toJson(it) as? JsonObject },
                    meta = autoMetaJson(
                        c, svc, status = result.status, asked = true, outcome = result,
                        completed = result.status == "done",
                    ),
                    opening = result.opening,
                )
                val tail = if (result.failedBatches.isNotEmpty()) {
                    "；${result.failedBatches.size} 个批次失败，可在 RP 面板只重跑失败批次"
                } else ""
                controller.toast(result.message + tail)
                controller.select(c.id)
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                autoOutcome = null
                controller.saveAutoChar(
                    c.id,
                    meta = autoMetaJson(
                        c, svc, status = "failed", asked = true,
                        opening = "",
                    ),
                )
                controller.toast("角色生成失败：${t.message ?: "未知错误"}")
            } finally {
                autoBusy = false
                autoProgress = null
            }
        }
    }
    val drawerSummary = remember(settings, personas, prompts, templates, worlds) {
        com.aichat.app.ui.conversation.DrawerSummary(
            enabledPlugins = deps.plugins.plugins.count { com.aichat.app.ui.settings.PluginToggles.enabled(settings, it) },
            personaCount = personas.size,
            promptCount = prompts.size,
            templateCount = templates.size,
            worldCount = worlds.size,
            hasBalanceKey = settings.providers.any { it.hasKey },
        )
    }

    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ConversationDrawer(index, st.convId,
            onSelect = { controller.select(it); scope.launch { drawer.close() } },
            onNew = { controller.newConversation(); scope.launch { drawer.close() } },
            onRename = controller::rename,
            onDelete = controller::delete,
            onOpenSettings = { onOpenSettings(SettingsDestination.Main) },
            onExport = exportConv,
            onOpenPersonas = { onOpenSettings(SettingsDestination.Personas) },
            summary = drawerSummary,
            onOpenPlugins = { onOpenSettings(SettingsDestination.Plugins) },
            onOpenWorlds = { onOpenSettings(SettingsDestination.Worlds) },
            onOpenTemplates = { onOpenSettings(SettingsDestination.Templates) },
            onOpenUsage = { onOpenSettings(SettingsDestination.Usage) },
            onOpenBalance = { onOpenSettings(SettingsDestination.Balance) },
            onOpenImage = { scope.launch { drawer.close() }; onOpenImageTab() })
    }) {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            // 底部没有 Tab 栏了，Snackbar 默认会压住输入条，抬到它上面
            snackbarHost = { SnackbarHost(snack, modifier = Modifier.padding(bottom = 112.dp)) },
            topBar = {
                // headerBadges 会走插件 hook（RP 插件里是 O(n) 扫描消息），
                // 而 topBar 这个 lambda 在流式期间每帧都可能重组。
                // 不 remember 的话，打字/流式时顶栏会反复触发全量扫描。
                val hbadges = remember(conv, settings) {
                    if (conv != null) deps.plugins.headerBadges(settings, conv) else emptyList<Badge>()
                }
                val modelLabel = when {
                    settings.providers.isEmpty() -> "未配置"
                    else -> target?.let { "实际使用：" + it.second } ?: "选择模型"
                }
                val modelMotion = if (rememberAnimationsOn()) Motion.quick else 0
                Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        val drawerPress = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        Box(
                            Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                .background(Palette.Surface2)
                                .pressScale(drawerPress)
                                .clickable(interactionSource = drawerPress, indication = null, role = Role.Button,
                                    onClickLabel = "打开对话列表") { scope.launch { drawer.open() } }
                                .testTag("open-drawer"),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Filled.Menu, "对话列表") }
                        Row(
                            Modifier.weight(1f).padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                conv?.title ?: "新对话",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            // 模型是这里最常切的开关，但原来只是一段 12sp 灰字：看起来像副标题、不像可点。
                            // 加下拉箭头 + 按压反馈，让"能点"这件事有视觉依据。
                            val modelPress = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                            Row(
                                Modifier.padding(start = 8.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
                                    .pressScale(modelPress)
                                    .clickable(
                                        interactionSource = modelPress,
                                        indication = null,
                                        role = Role.Button,
                                        onClickLabel = "切换模型",
                                    ) {
                                        if (settings.providers.isEmpty()) onOpenSettings(SettingsDestination.Providers) else picker = true
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                    .testTag("model-switch"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AnimatedContent(
                                    targetState = modelLabel,
                                    transitionSpec = { fadeIn(tween(modelMotion)) togetherWith fadeOut(tween(modelMotion)) },
                                    label = "model-in-use",
                                    modifier = Modifier.testTag("model-in-use"),
                                ) { label ->
                                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = Palette.Fg2)
                                }
                                Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(16.dp), tint = Palette.Fg3)
                            }
                        }
                        Box {

                            val morePress = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                            Box(
                                Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(Palette.Surface2)
                                    .pressScale(morePress)
                                    .clickable(interactionSource = morePress, indication = null, role = Role.Button,
                                        onClickLabel = "更多操作") { menu = true },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Filled.MoreVert, "更多") }
                            PluginErrorDot(
                                count = pluginErrs.size,
                                onClick = { pluginErrPanel = true },
                                modifier = Modifier.align(Alignment.TopEnd),
                            )
                            ChatOverflowMenu(
                                expanded = menu,
                                onDismiss = { menu = false },
                                conv = conv,
                                // 有插件错误时，菜单第一项就是入口（不依赖顶栏那个小气泡被发现）
                                extraMenu = if (pluginErrs.isEmpty()) extraMenu else
                                    listOf("插件错误（${pluginErrs.size}）" to { menu = false; pluginErrPanel = true }) + extraMenu,
                                onSettings = { menu = false; onOpenSettings(SettingsDestination.Main) },
                                onExport = { menu = false; conv?.let { exportConv(it.id) } },
                                onConvPlugins = { menu = false; convPlugins = true },
                                onContext = { menu = false; ctxInspect = true },
                                onRp = { menu = false; controller.ensureConversation(); rpPanel = true },
                                onMemory = { menu = false; controller.ensureConversation(); rpMemoryPanel = true },
                                onInspect = { menu = false; controller.ensureConversation(); rpInspect = true },
                                onChapter = { menu = false; controller.ensureConversation(); rpChapter = true },
                                onLore = { menu = false; controller.ensureConversation(); rpLore = true },
                                onImage = {
                                    menu = false
                                    val c = conv ?: return@ChatOverflowMenu
                                    val p = com.aichat.app.net.ImageGen.buildPromptFromMessages(c.messages)
                                    if (p.isBlank()) controller.toast("这段对话还没内容可提炼") else onOpenImage(p)
                                },
                                onAffect = { menu = false; controller.ensureConversation(); affectPanel = true },
                                onBackup = { menu = false; onOpenSettings(SettingsDestination.DataBackup) },
                            )
                        }
                    }
                    if (hbadges.isNotEmpty()) Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 2.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            Modifier.weight(1f).horizontalScroll(rememberScrollState()).testTag("header-badges"),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            hbadges.forEach { b ->
                                BadgeChip(b, prefix = when (b.plugin) { "affect" -> "💓 "; else -> "" },
                                    onClick = { if (b.action != null) panel = b.action else b.detail?.let(controller::toast) })

                            }
                        }
                        ChatMounts.usage?.invoke(conv, settings) ?: DefaultUsageChip(conv, settings)
                    }
                }
            },
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                if (deps.isFake) Text("演示模式：数据层 / 网络层尚未接入，回复由 fake 引擎生成，不会保存",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
                // fillMaxWidth 不能省：Column 默认 Start 对齐，Box 只按内容宽度测量，
                // 空对话时里面的「有什么可以帮你？」会被挤到左边贴边，而不是居中。
                // 大屏限宽：平板/折叠屏展开态下，消息列表和输入条会被拉到全宽，
                // 一行六十多个汉字读起来很费劲，输入条也宽到拇指够不着发送键。
                // 这里把两者收口到 720dp 并居中；手机（<600dp）走 else 分支，
                // Modifier 与改动前完全一致，布局逐像素不变。
                val wide = rememberIsWideScreen()
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = if (wide) Alignment.TopCenter else Alignment.TopStart,
                ) {
                    if (messages.isEmpty()) {
                        EmptyHero(
                        hasProvider = settings.providers.isNotEmpty(),
                        onSetup = { onOpenSettings(SettingsDestination.Providers) },
                        onPick = { input = it },
                    )
                    } else {
                        // 性能：流式增量只改 live 气泡（该项内部订阅 controller.streamOf）；st.messages 稳定层
                        // 在整个流期间实例不变，已有条目不重组。插件的 messageState / badges（RP 插件里是
                        // O(n) 扫描）按消息 + 对话缓存，并且异常不外泄。
                        val rev = remember(st.messages) { st.messages.asReversed() }
                        val liveKey = st.stream?.liveId
                        val lastId = liveKey ?: st.messages.lastOrNull()?.id
                        val row: @Composable (com.aichat.app.domain.model.Message, Boolean) -> Unit = { m, live ->
                            val isUser = m.role == com.aichat.app.domain.model.Role.USER
                            val ms = remember(m, conv, settings, live) { if (conv != null && !live) runCatching { deps.plugins.messageState(m, settings, conv) }.getOrNull() else null }
                            val badges = remember(m, conv, settings, live) { if (conv != null && !live) runCatching { deps.plugins.messageBadges(m, settings, conv) }.getOrDefault(emptyList()) else emptyList() }
                            val isLast = lastId == m.id
                            val headName = remember(m.extras, conv?.personaName, conv?.personaEmoji) { if (isUser) null else {
                                val pn = (m.extras["personaName"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: conv?.personaName
                                val pe = (m.extras["personaEmoji"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: conv?.personaEmoji
                                val model = m.model ?: (m.extras["model"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                                listOfNotNull(pn?.let { listOfNotNull(pe, it).joinToString(" ") }, model).joinToString(" · ").ifEmpty { null }
                            } }
                            val canCont = !isUser && !live && controller.canContinue(m)
                            val actions = remember(m, live, isUser, canCont) { if (live) MessageActions() else MessageActions(
                                onEdit = if (isUser || m.content.isNotEmpty()) ({ editing = m.id }) else null,
                                onRegenerate = if (isUser) null else ({ plan = controller.planRegenerate(m.id) }),
                                onContinue = if (canCont) ({ controller.continueGeneration(m.id) }) else null,
                                onVersion = { k -> plan = controller.planSwitch(m.id, k) },
                                onVersionMenu = { if (controller.isStreaming()) controller.toast("请先停止当前生成") else versionMenu = m.id },
                                onDelete = { controller.deleteMessage(m.id) },
                                onShareCard = if (isUser) null else ({
                                                                        ShareCards.shareCard(ctx, conv?.templateName ?: conv?.title.orEmpty(),
                                        conv?.personaName ?: "角色", conv?.personaEmoji ?: "✦", m.content)
                                }),
                                onShareScene = if (isUser) null else ({
                                                                        val all = conv?.messages.orEmpty()
                                    val i = all.indexOfFirst { it.id == m.id }.let { if (it < 0) all.lastIndex else it }
                                    val window = all.subList((i - 5).coerceAtLeast(0), (i + 1).coerceAtMost(all.size))
                                    ShareCards.shareScene(ctx, conv?.templateName ?: conv?.title.orEmpty(),
                                        conv?.personaName ?: "角色", conv?.personaEmoji ?: "✦", window)
                                }),
                                onBadge = { b -> if (b.action != null) panel = b.action else b.detail?.let(controller::toast) },
                            ) }
                            MessageItem(m, streaming = live && !isUser, badges = badges,
                                onToast = controller::toast, onImage = { viewing = it },
                                view = MessageView(busy = busy, dim = ms?.dim == true, label = ms?.label, isLast = isLast, headName = headName),
                                actions = actions)
                        }
                        LazyColumn(state = listState, reverseLayout = true,
                            modifier = Modifier.fillMaxSize()
                                .let { if (wide) it.widthIn(max = Adaptive.contentMax) else it }
                                .testTag("messages"),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Bottom)) {
                            if (liveKey != null) item(key = liveKey, contentType = "live") {
                                // live 气泡是唯一订阅流式热数据的地方：每次 flush 只重组这一个 item
                                val s by remember(controller, st.convId) { controller.streamOf(st.convId) }.collectAsStateWithLifecycle(initialValue = null)
                                s?.toLiveMessage()?.let { row(it, true) }
                            }
                            items(rev, key = { it.id }, contentType = { it.role }) { m -> row(m, m.id == ChatController.PENDING_USER_ID) }
                        }
                        // 「回到底部」浮标。列表是 reverseLayout，index 0 = 最新一条，
                        // 所以 follow=false 意味着用户往上翻历史了。
                        //
                        // 原来没有任何回到底部的入口：往上翻几十条之后，想回到最新
                        // 消息只能一路手动滑回去；更糟的是流式生成期间用户根本不知道
                        // 新内容正在下面追加。这里补一个 44dp 的浮标，点一下回到最新。
                        // 用 animateFloatAsState + graphicsLayer 而不是 AnimatedVisibility：
                        // 这个 Box 嵌在 Column 里，AnimatedVisibility 会解析到
                        // ColumnScope 的重载，和 Modifier.align(BoxScope) 打架。
                        val fabAlpha by animateFloatAsState(
                            targetValue = if (follow) 0f else 1f,
                            animationSpec = tween(Motion.quick),
                            label = "scroll-bottom",
                        )
                        if (fabAlpha > 0f) {
                            val press = remember { MutableInteractionSource() }
                            Box(
                                Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                                    .graphicsLayer { alpha = fabAlpha }
                                    .size(44.dp).clip(CircleShape)
                                    .background(Palette.Surface2)
                                    .border(1.dp, Palette.Line, CircleShape)
                                    .pressScale(press)
                                    .clickable(interactionSource = press, indication = null,
                                        role = Role.Button, onClickLabel = "回到最新消息") {
                                        follow = true
                                        scope.launch { listState.animateScrollToItem(0) }
                                    }
                                    .testTag("scroll-bottom"),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Filled.KeyboardArrowDown, "回到最新消息",
                                    Modifier.size(24.dp), tint = Palette.Fg2)
                            }
                        }
                    }
                }
                ChatMounts.headerExtras?.invoke(conv, settings)
                val autoWorldKey = conv?.let { c -> autoChar?.worldKey(c) }.orEmpty()
                LaunchedEffect(
                    st.convId,
                    conv?.messages?.size,
                    settings.autoCharMode,
                    autoWorldKey,
                    autoBusy,
                ) {
                    val c = conv
                    val svc = autoChar
                    if (c == null || svc == null) {
                        autoOffer = false
                        return@LaunchedEffect
                    }
                    if (autoBusy) return@LaunchedEffect
                    when (svc.modeOf(settings)) {
                        "off" -> autoOffer = false
                        "ask" -> autoOffer = svc.offer(c, templates, settings)
                        else -> {
                            autoOffer = false
                            if (svc.offer(c, templates, settings)) startAutoChar(svc, c, automatic = true)
                        }
                    }
                }
                if (autoOffer && autoChar != null && conv != null) {
                    val inWorld = com.aichat.app.plugins.rp.AutoChar.scopeOf(autoChar.convJs(conv)) ==
                        com.aichat.app.plugins.rp.AutoChar.Scope.CHARACTER_ONLY
                    AutoCharOfferBar(
                        inWorld = inWorld,
                        busy = autoBusy,
                        onDismiss = {
                            autoOffer = false
                            val c = conv
                            val svc = autoChar
                            if (c != null && svc != null) scope.launch {
                                controller.saveAutoChar(
                                    c.id,
                                    meta = autoMetaJson(
                                        c, svc, status = "dismissed", asked = true, dismissed = true,
                                    ),
                                )
                            }
                        },
                        onGenerate = {
                            val c = conv
                            if (c != null) startAutoChar(autoChar, c, automatic = false)
                        },
                    )
                }
                val tplName = conv?.templateName?.takeIf { it.isNotEmpty() }
                val tplEmoji = conv?.templateEmoji.orEmpty()
                InputBar(input, { input = it }, st.draftImages, controller::setDraftImages, busy,
                    onSend = { if (controller.send(input, st.draftImages, st.draftFiles)) { input = ""; controller.setDraftImages(emptyList()); controller.setDraftFiles(emptyList()) } },
                    onStop = controller::stop, onToast = controller::toast,
                    // 与消息列表同宽居中，否则大屏上正文收口了、输入条还横贯全屏
                    modifier = Modifier.imePadding().align(Alignment.CenterHorizontally)
                        .let { if (wide) it.widthIn(max = Adaptive.contentMax) else it },
                    files = st.draftFiles, onFiles = controller::setDraftFiles,
                    fileSourceMax = if (FileContext.chunkEnabled(settings.plugins)) ChunkRetrieval.SOURCE_MAX else FileText.DEFAULT_FILE_MAX,
                    tools = InputTools(onPrompts = { sheet = 1 }, onTemplates = { sheet = 2 },
                        templateLabel = tplName?.let { listOfNotNull(tplEmoji.takeIf { e -> e.isNotEmpty() }, it).joinToString(" ") },
                        searchOn = settings.searchOn, onSearch = controller::setSearchOn))
            }
        }
    }

    editing?.let { id ->
        val m = conv?.messages?.firstOrNull { it.id == id }
        if (m == null) editing = null else {
            val user = m.role == com.aichat.app.domain.model.Role.USER
            val (hint, warn) = controller.editHint(id) ?: (null to false)
            FullScreenEditor(
                title = if (user) "编辑消息" else "编辑 AI 回复",
                initial = m.content,
                actions = if (user) listOf(EditorAction("save", "保存"), EditorAction("resend", "保存并重新发送", primary = true))
                    else listOf(EditorAction("save", "保存", primary = true)),
                hint = hint, warn = warn, placeholder = if (user) "输入消息…" else "回复内容…",
                onResult = { key, text ->
                    val ok = if (key == "resend") controller.editAndResend(id, text) else controller.editMessage(id, text)
                    if (ok) editing = null
                },
                onDismiss = { editing = null })
        }
    }
    when (val p = plan) {
        is Plan.Toast -> LaunchedEffect(p) { controller.toast(p.text); plan = null }
        is Plan.Ask -> ConfirmDialog(p.title, p.body, p.okText, danger = p.danger, onOk = { plan = null; p.run() }, onDismiss = { plan = null })
        else -> {}
    }
    versionMenu?.let { id ->
        val m = conv?.messages?.firstOrNull { it.id == id }
        if (m == null || Versions.count(m) < 2) versionMenu = null else {
            val n = Versions.count(m); val cur = Versions.index(m)
            androidx.compose.material3.AlertDialog(onDismissRequest = { versionMenu = null },
                title = { Text((if (m.role == com.aichat.app.domain.model.Role.USER) "消息" else "回复") + "版本 ${cur + 1} / $n") },
                text = { Column(Modifier.testTag("version-menu")) {
                    (0 until n).filter { it != cur }.forEach { k ->
                        TextButton(onClick = { versionMenu = null; plan = controller.planSwitch(id, k) }) {
                            Text("切换到第 ${k + 1} 个：" + Versions.versionContent(m, k).replace(Regex("\\s+"), " ").take(24), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    TextButton(onClick = { versionMenu = null; plan = controller.planKeepOnly(id) }) { Text("只保留当前版本（第 ${cur + 1} 个）") }
                    TextButton(onClick = { versionMenu = null; plan = controller.planDeleteVersion(id, cur) }) {
                        Text("删除当前版本", color = MaterialTheme.colorScheme.error)
                    }
                } },
                confirmButton = { TextButton(onClick = { versionMenu = null }) { Text("取消") } })
        }
    }
    if (st.askSearchSetup) ConfirmDialog("还没有配置联网搜索", "联网搜索需要一个搜索服务。推荐 Tavily（注册即送免费额度）或国内的博查 Bocha，在设置中填入 API Key 即可。", "去设置",
        onOk = { controller.consumeAskSearch(); onOpenSettings(SettingsDestination.Search) }, onDismiss = controller::consumeAskSearch)
    panel?.let { id ->
        val page = com.aichat.app.ui.settings.PluginPanels.get(id) ?: com.aichat.app.ui.settings.PluginPanels.get(id.substringBefore('.'))
        androidx.compose.ui.window.Dialog(onDismissRequest = { panel = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
                if (page != null) page { panel = null }
                else com.aichat.app.ui.settings.SettingsPageScaffold(id, { panel = null }) {
                    Text("这个面板还不能打开", Modifier.padding(20.dp))
                }
            }
        }
    }
    if (sheet == 1) PromptSheet(onPick = { t -> input = if (input.isEmpty()) t else input + (if (input.endsWith("\n")) "" else "\n") + t; sheet = 0 },
        onManage = { sheet = 0; onOpenSettings(SettingsDestination.Prompts) }, onDismiss = { sheet = 0 })
    if (sheet == 2) TemplateSheet(
        currentTemplateId = conv?.templateId,
        hasSystem = !conv?.systemPrompt.isNullOrEmpty(),
        onApply = { t, text -> sheet = 0; plan = controller.planApplyTemplate(t?.id, t?.name, t?.emoji, text, t?.skill == true) },
        onManage = { sheet = 0; onOpenSettings(SettingsDestination.Templates) }, onDismiss = { sheet = 0 })
    if (convPlugins) {
        val perConv = deps.plugins.plugins.filter { it.perConversation }
        androidx.compose.material3.AlertDialog(onDismissRequest = { convPlugins = false }, title = { Text("本对话插件") },
            text = { Column {
                if (perConv.isEmpty()) Text("没有可按对话开关的插件")
                perConv.forEach { p ->
                    val on = conv?.let { com.aichat.app.ui.settings.PluginToggles.convEnabled(it.extras, p.id) }
                        ?: (conv != null && deps.plugins.isActive(p.id, settings, conv))
                    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, Modifier.weight(1f))
                        androidx.compose.material3.Switch(on, { controller.setConversationPlugin(p.id, it) }, enabled = conv != null)
                    }
                }
            } },
            confirmButton = { TextButton(onClick = { convPlugins = false }) { Text("完成") } })
    }
    if (rpMemoryPanel && conv != null) {
        RpMemoryPanelDialog(
            conv = conv,
            settings = settings,
            store = deps.rpMemory,
            plugin = deps.plugins.plugins.firstOrNull { it.id == "rpmemory" } as? com.aichat.app.plugins.rp.RpMemoryPlugin,
            onDismiss = { rpMemoryPanel = false },
            onToast = { controller.toast(it) },
        )
    }
    if (rpPanel && conv != null) {
        RpPanelDialog(
            conv = conv,
            worlds = deps.library.worlds.collectAsState().value,
            onDismiss = { rpPanel = false },
            onSave = { blocks -> controller.setRpBlocks(conv.id, blocks) },
            // 选世界时顺手补开场白（旧版 applyWorld 的行为）；块仍由上面的「保存」统一写
            onPickWorld = { w -> controller.applyWorld(conv.id, w) },
            busy = autoBusy,
            progress = autoProgress,
            outcome = autoOutcome,
            onGenerate = { overwrite, only ->
                startAutoChar(roleChar, conv, automatic = false, overwrite = overwrite, only = only)
            },
        )
    }
    if (rpChapter && conv != null) {
        RpChapterDialog(
            conv = conv,
            settings = settings,
            plugin = deps.plugins.plugins.firstOrNull { it.id == "rpcache" } as? com.aichat.app.plugins.rp.RpCachePlugin,
            onSaveState = { controller.setRpStateJson(conv.id, it) },
            onDismiss = { rpChapter = false },
            onToast = controller::toast,
        )
    }
    if (rpLore && conv != null) {
        RpLoreDialog(
            conv = conv,
            onSaveState = { controller.setRpStateJson(conv.id, it) },
            onDismiss = { rpLore = false },
            onToast = controller::toast,
        )
    }
    if (rpInspect && conv != null) {
        RpInspectDialog(
            conv = conv,
            settings = settings,
            onDismiss = { rpInspect = false },
            onSaveSettings = { values ->
                controller.setRpSettings(conv.id, values)
                rpInspect = false
            },
        )
    }
    if (affectPanel && conv != null) {
        AffectPanelDialog(conv, onDismiss = { affectPanel = false })
    }
    if (ctxInspect) {
        ContextInspectorDialog(conv, onDismiss = { ctxInspect = false })
    }
    if (pluginErrPanel) {
        PluginErrorsDialog(
            errors = pluginErrs,
            onDismiss = { pluginErrPanel = false },
            onClear = { deps.plugins.clearErrors(); pluginErrPanel = false },
            nameOf = { id -> deps.plugins.plugins.firstOrNull { it.id == id }?.name ?: id },
        )
    }

    if (picker) ModelPicker(settings.providers, target?.first?.id, target?.second,
        onPick = { p, m -> controller.setModel(p, m); picker = false },
        onManage = { picker = false; onOpenSettings(SettingsDestination.Providers) },
        onDismiss = { picker = false },
        // 档位属于「模型」：按 服务商+模型 查，跨对话共享，所以切对话不会变回自动。
        // 先看即时覆盖层（点一下立刻变蓝），再回落到 settings 里存的值
        currentThinking = thinking[ProviderRules.thinkingKey(target?.first?.id, target?.second)]
            ?: controller.thinkingLevelOf(target?.first?.id, target?.second),
        onThinkingLevel = { controller.setThinkingLevel(it) },
        currentFlex = flex[target?.first?.id ?: ""]
            ?: controller.flexOnOf(target?.first?.id, target?.second),
        onFlexChange = { on -> controller.setFlexOn(target?.first?.id, on) })
    viewing?.let { ImageViewer(it) { viewing = null } }
}


/** 读取 conv.extras.autoChar，把上次检查点还原成 UI 可展示的 Outcome。 */
private fun restoreAutoOutcome(
    conv: Conversation,
    svc: AutoCharService?,
): AutoCharService.Outcome? {
    val meta = conv.extras["autoChar"] as? JsonObject ?: return null
    val rawStatus = (meta["status"] as? JsonPrimitive)?.content.orEmpty()
    if (rawStatus !in setOf("running", "done", "partial", "already", "failed")) return null
    val scope = svc?.let { AutoChar.scopeOf(it.convJs(conv)) } ?: AutoChar.Scope.FULL
    val applied = jsonStrings(meta, "applied")
    val dropped = jsonStrings(meta, "dropped")
    val failed = jsonStrings(meta, "failedBatches")
    val status = if (rawStatus == "running") {
        if (applied.isNotEmpty()) "partial" else "failed"
    } else rawStatus
    val specs = svc?.batchesOf(scope).orEmpty().associateBy { it.key }
    val batches = failed.map { key ->
        AutoCharService.BatchResult(
            key = key,
            title = specs[key]?.title ?: key,
            status = "failed",
            reason = "上次生成未完成",
        )
    }
    val message = when {
        rawStatus == "running" -> "上次生成未完成，已写入的字段已保留"
        status == "partial" -> "部分批次失败，已保留成功字段"
        status == "already" -> "这个世界的角色卡已经有了，没有覆盖"
        status == "done" -> "已写入角色设定"
        else -> "上次生成失败"
    }
    return AutoCharService.Outcome(
        ok = status == "done" || status == "partial" || status == "already",
        scope = scope,
        status = status,
        applied = applied,
        dropped = dropped,
        failedBatches = failed,
        state = Js.fromJson(conv.rpState) as? JObj,
        opening = (meta["opening"] as? JsonPrimitive)?.content.orEmpty(),
        batches = batches,
        message = message,
    )
}

/**
 * conv.extras.autoChar 的统一形状。检查点只追加已有进度，不覆盖 rpState；
 * 最终结果再带 opening，由 ChatController.saveAutoChar 按正文去重插入。
 */
private fun autoMetaJson(
    conv: Conversation,
    svc: AutoCharService?,
    status: String,
    asked: Boolean,
    dismissed: Boolean = false,
    completed: Boolean = false,
    progress: AutoCharService.Progress? = null,
    outcome: AutoCharService.Outcome? = null,
    opening: String? = null,
): JsonObject {
    val base = conv.extras["autoChar"] as? JsonObject ?: JsonObject(emptyMap())
    val batches = outcome?.batches ?: progress?.batches.orEmpty()
    val applied = when {
        outcome != null -> outcome.applied
        progress != null -> batches.flatMap { it.applied }
        else -> jsonStrings(base, "applied")
    }.distinct()
    val dropped = when {
        outcome != null -> outcome.dropped
        progress != null -> batches.flatMap { it.dropped }
        else -> jsonStrings(base, "dropped")
    }.distinct()
    val failed = when {
        outcome != null -> outcome.failedBatches
        progress != null -> batches.filter { it.status == "failed" }.map { it.key }
        else -> jsonStrings(base, "failedBatches")
    }.distinct()
    val failedTitles = batches.filter { it.status == "failed" }.map { it.title }.distinct()
    val openingText = opening ?: (base["opening"] as? JsonPrimitive)?.content.orEmpty()
    val worldId = svc?.worldKey(conv)
        ?: (base["worldId"] as? JsonPrimitive)?.content.orEmpty()
    return buildJsonObject {
        base.forEach { (key, value) -> put(key, value) }
        put("worldId", JsonPrimitive(worldId))
        put("asked", JsonPrimitive(asked))
        put("dismissed", JsonPrimitive(dismissed))
        put("completed", JsonPrimitive(completed))
        put("status", JsonPrimitive(status))
        put("opening", JsonPrimitive(openingText))
        put("running", JsonPrimitive(if (status == "running") progress?.running.orEmpty() else ""))
        put("current", JsonPrimitive(progress?.current ?: 0))
        put("total", JsonPrimitive(progress?.total ?: 0))
        put("applied", JsonArray(applied.map { JsonPrimitive(it) }))
        put("dropped", JsonArray(dropped.map { JsonPrimitive(it) }))
        put("failedBatches", JsonArray(failed.map { JsonPrimitive(it) }))
        put("failedTitles", JsonArray(failedTitles.map { JsonPrimitive(it) }))
    }
}

private fun jsonStrings(obj: JsonObject, key: String): List<String> =
    (obj[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
