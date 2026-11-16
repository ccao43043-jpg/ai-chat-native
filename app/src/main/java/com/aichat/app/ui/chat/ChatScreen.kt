package com.aichat.app.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aichat.app.ui.common.ConfirmDialog
import com.aichat.app.ui.common.EditorAction
import com.aichat.app.ui.common.FullScreenEditor
import com.aichat.app.ui.common.ImageViewer
import com.aichat.app.ui.library.PromptSheet
import com.aichat.app.ui.library.TemplateSheet
import com.aichat.app.ui.common.LocalUiDeps
import com.aichat.app.ui.conversation.ConversationDrawer
import com.aichat.app.ui.settings.SettingsDestination
import kotlinx.coroutines.launch
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.aichat.app.ui.theme.Palette

/**
 * 聊天页：顶栏（抽屉 / 标题 + 模型切换 / 更多）、消息列表（reverseLayout，流式时贴底）、输入区。
 * 返回键：看图 > 弹层/对话框（各自处理）> 抽屉 > 交给上层。
 */
/**
 * 空对话首屏。对齐旧版 .empty：64dp 渐变圆角块 + 24px 标题 + 副标题 + 一排建议 pill。
 * 建议点一下只把文字塞进输入框，不直接发送 —— 用户通常还要再改两个字。
 *
 * 没配服务商时不显示建议：那时候点建议也没用（发不出去），
 * 改成旧版 v0.29 那张「开始之前」三步引导卡。
 */
@Composable
private fun EmptyHero(hasProvider: Boolean, onSetup: () -> Unit, onPick: (String) -> Unit) {
    val suggestions = remember {
        listOf("解释量子计算", "写一封感谢信", "推荐一个周末可以去的地方")
    }
    Box(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(88.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Palette.Grad),
                contentAlignment = Alignment.Center,
            ) {
                // 用启动图标而不是某个通用 material 图标：空态是品牌位，
                // 星星/闪光/灯泡这类通用符号换成任何 app 都成立，反而更廉价。
                Image(
                    painterResource(com.aichat.app.R.mipmap.ic_launcher_foreground),
                    null,
                    Modifier.size(52.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "有什么可以帮你？",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "我可以回答问题、提供建议或帮你生成创意",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            if (hasProvider) {
                suggestions.forEach { s ->
                    Surface(
                        onClick = { onPick(s) },
                        shape = RoundedCornerShape(999.dp),
                        color = Palette.Surface2,
                        border = BorderStroke(1.dp, Palette.Line),
                        // 不再 fillMaxWidth：建议是「选项」不是「按钮条」，
                        // 撑满整行会让人以为那是三个并列的大按钮。按文字收口、居中。
                        modifier = Modifier.padding(vertical = 4.dp).widthIn(max = 300.dp),
                    ) {
                        Text(
                            s,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                        )
                    }
                }
            } else {
                SetupCard(onSetup)
            }
        }
    }
}

/**
 * 没配服务商时的引导卡（对齐旧版 v0.29 的「开始之前」三步）。
 * 空态放三条建议是没意义的：点进去也发不出去，用户只会以为坏了。
 */
@Composable
private fun SetupCard(onSetup: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Palette.Surface,
        border = BorderStroke(1.dp, Palette.Line),
        modifier = Modifier.fillMaxWidth().testTag("empty-setup-card"),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("开始之前", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            listOf(
                "打开设置，选择一个服务商",
                "粘贴你的 API Key 并保存",
                "回到这里，点顶部切换模型即可聊天",
            ).forEachIndexed { i, line ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(line, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(14.dp))
            Surface(
                onClick = onSetup,
                shape = RoundedCornerShape(12.dp),
                color = Palette.Accent,
                modifier = Modifier.fillMaxWidth().testTag("empty-setup"),
            ) {
                Text(
                    "去设置 API Key",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                )
            }
        }
    }
}

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
    // 插件错误：以前只有一个没人读的 StateFlow，插件静默失效时用户完全无感（世界书整版不生效就是这么漏的）
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

    // 新消息 / 切对话时回到底部。
    //
    // 旧版是无条件 `scrollToItem(0)`：只要消息条数变化（新消息、删一条、编辑重发）就把列表弹回底部。
    // 用户往上翻历史时，这几件事都会把他正在读的位置弹走，所以这里只在「本来就在底部附近」时才滚。
    // `reverseLayout = true` → item 0 是最新消息，离底 ≈ firstVisibleItemIndex == 0。
    var lastConvId by remember { mutableStateOf(st.convId) }
    LaunchedEffect(st.convId, messages.size) {
        if (messages.isEmpty()) return@LaunchedEffect
        val switched = lastConvId != st.convId
        lastConvId = st.convId
        val atBottom = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 200
        if (switched || atBottom) listState.animateScrollToItem(0)
    }

    // 抽屉底部各行的计数（旧版 v0.29 也展示这些）
    val personas by deps.library.personas.collectAsState()
    val prompts by deps.library.prompts.collectAsState()
    val templates by deps.library.templates.collectAsState()
    val worlds by deps.library.worlds.collectAsState()
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
                val hbadges = if (conv != null) deps.plugins.headerBadges(settings, conv) else emptyList()
                val modelLabel = when {
                    settings.providers.isEmpty() -> "未配置"
                    else -> target?.second ?: "选择模型"
                }
                Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                .background(Palette.Surface2)
                                .clickable { scope.launch { drawer.open() } }
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
                            Text(
                                modelLabel,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 12.sp,
                                color = Palette.Fg2,
                                modifier = Modifier.padding(start = 8.dp).testTag("model-switch")
                                    .clickable { if (settings.providers.isEmpty()) onOpenSettings(SettingsDestination.Providers) else picker = true },
                            )
                        }
                        Box {
                            Box(
                                Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(Palette.Surface2)
                                    .clickable { menu = true },
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
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (messages.isEmpty()) {
                        EmptyHero(
                        hasProvider = settings.providers.isNotEmpty(),
                        onSetup = { onOpenSettings(SettingsDestination.Providers) },
                        onPick = { input = it },
                    )
                    } else {
                        // 性能：流式增量只改 live 这一条；stable 列表实例在整个流期间不变，已有条目不重组。
                        // 插件的 messageState / badges（RP 插件里是 O(n) 扫描）按消息 + 对话缓存，并且异常不外泄。
                        val stable = remember(st.conv?.messages, st.streaming?.base, st.streaming?.pendingUser) { st.stable }
                        val rev = remember(stable) { stable.asReversed() }
                        val liveMsg = st.live
                        val busy = st.isStreaming
                        val lastId = liveMsg?.id ?: stable.lastOrNull()?.id
                        val row: @Composable (com.aichat.app.domain.model.Message, Boolean) -> Unit = { m, live ->
                            val isUser = m.role == com.aichat.app.domain.model.Role.USER
                            val ms = remember(m, conv, settings, live) { if (conv != null && !live) runCatching { deps.plugins.messageState(m, settings, conv) }.getOrNull() else null }
                            val badges = remember(m, conv, settings, live) { if (conv != null && !live) runCatching { deps.plugins.messageBadges(m, settings, conv) }.getOrDefault(emptyList()) else emptyList() }
                            val isLast = lastId == m.id
                            val headName = remember(m.extras, conv?.personaName, conv?.personaEmoji) { if (isUser) null else {
                                val pn = (m.extras["personaName"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: conv?.personaName
                                val pe = (m.extras["personaEmoji"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: conv?.personaEmoji
                                val model = (m.extras["model"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                                listOfNotNull(pn?.let { listOfNotNull(pe, it).joinToString(" ") }, model).joinToString(" · ").ifEmpty { null }
                            } }
                            val canCont = !isUser && !live && controller.canContinue(m)
                            val actions = remember(m, live, isUser, canCont) { if (live) MessageActions() else MessageActions(
                                onEdit = if (isUser || m.content.isNotEmpty()) ({ editing = m.id }) else null,
                                onRegenerate = if (isUser) null else ({ plan = controller.planRegenerate(m.id) }),
                                onContinue = if (canCont) ({ controller.continueGeneration(m.id) }) else null,
                                onVersion = { k -> plan = controller.planSwitch(m.id, k) },
                                onVersionMenu = { if (controller.state.value.isStreaming) controller.toast("请先停止当前生成") else versionMenu = m.id },
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
                        LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.fillMaxSize().testTag("messages"),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Bottom)) {
                            if (liveMsg != null) item(key = liveMsg.id, contentType = "live") { row(liveMsg, true) }
                            items(rev, key = { it.id }, contentType = { it.role }) { m -> row(m, m.id == ChatController.PENDING_USER_ID) }
                        }
                    }
                }
                ChatMounts.headerExtras?.invoke(conv, settings)
                val tplName = conv?.templateName?.takeIf { it.isNotEmpty() }
                val tplEmoji = conv?.templateEmoji.orEmpty()
                InputBar(input, { input = it }, st.draftImages, controller::setDraftImages, st.isStreaming,
                    onSend = { if (controller.send(input, st.draftImages, st.draftFiles)) { input = ""; controller.setDraftImages(emptyList()); controller.setDraftFiles(emptyList()) } },
                    onStop = controller::stop, onToast = controller::toast, modifier = Modifier.imePadding(),
                    files = st.draftFiles, onFiles = controller::setDraftFiles,
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
        onDismiss = { picker = false })
    viewing?.let { ImageViewer(it) { viewing = null } }
}


private val MenuItemPad = PaddingValues(horizontal = 16.dp, vertical = 0.dp)

@Composable
private fun MenuSection(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = Palette.Fg3,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun MenuEntry(label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyMedium, color = Palette.Fg) },
        onClick = onClick,
        contentPadding = MenuItemPad,
        modifier = Modifier.height(40.dp),
    )
}

@Composable
private fun ChatOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    conv: com.aichat.app.domain.model.Conversation?,
    extraMenu: List<Pair<String, () -> Unit>>,
    onSettings: () -> Unit,
    onExport: () -> Unit,
    onConvPlugins: () -> Unit,
    onRp: () -> Unit,
    onMemory: () -> Unit,
    onInspect: () -> Unit,
    onChapter: () -> Unit,
    onLore: () -> Unit,
    onImage: () -> Unit,
    onAffect: () -> Unit,
    onBackup: () -> Unit,
) {
    DropdownMenu(expanded, onDismiss) {
        MenuSection("对话")
        if (conv != null) MenuEntry("导出本对话", onExport)
        if (conv != null) MenuEntry("本对话插件", onConvPlugins)
        if (conv != null) MenuEntry("据此生图", onImage)
        androidx.compose.material3.HorizontalDivider(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            color = Palette.Line,
        )
        MenuSection("RP")
        MenuEntry("RP 状态", onRp)
        MenuEntry("记忆", onMemory)
        MenuEntry("RP 检视", onInspect)
        MenuEntry("RP 章节", onChapter)
        MenuEntry("世界书", onLore)
        MenuEntry("好感度", onAffect)
        androidx.compose.material3.HorizontalDivider(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            color = Palette.Line,
        )
        MenuSection("其他")
        MenuEntry("设置", onSettings)
        MenuEntry("数据与备份", onBackup)
        extraMenu.forEach { (label, act) -> MenuEntry(label) { onDismiss(); act() } }
    }
}
