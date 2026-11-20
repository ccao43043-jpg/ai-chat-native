# Roadmap

> **历史快照提示（2026-11-20 PST 补）**：下文保留当时基线、判断与后续旧回写，不代表 v1.7.9 现状，也不再滚动更新。当前状态/优先级见 [MASTER-PLAN.md](MASTER-PLAN.md)，本轮代码与 CI 核对见 [ASSESS-2026-11-20.md](ASSESS-2026-11-20.md)。

> **状态**：🗄 **已归档，不再更新**（内容截至 2026-11-17）。
> 「某项功能到底做没做」「下一步做什么」一律以 [MASTER-PLAN.md](MASTER-PLAN.md) §2/§5 与 [PLAN-ALGO-CACHE.md](PLAN-ALGO-CACHE.md) 为准。
> 本文保留作「当时怎么一步步做过来的」记录；下面标了 ~~删除线~~ 的「没做」条目都在本文件之后补齐了，已在原行就地改写。

从旧仓库 `native/i2` 的起点继续。已完成的部分（CI、`recordUsage` 收口、`plugins` 单实例、
引擎接联网搜索 + `NetRetry`）不再重复。

## 1. 插件与缓存（收益最大）

`AppGraph.plugins` 现在是 `PluginRegistryImpl.createDefault()`，已生效：

- `knowledge`（本地资料库注入，三道闸：总闸 / 对话闸 / 世界）
- `usage`（Token 徽章）
- `gcache` / `mmcache`（缓存，命中率不再是 0）
- `rpcache`（RP 上下文：分层 system + 末轮 `[CURRENT_STATE]` 注入；只有对话真的填过 RP 块才自动生效）
  - 预算裁剪：超预算时二分出要丢的最旧前缀，记进 `overflow.tempStartId`（**不永久推进 window**，摘要失败也不能静默丢剧情）
  - 章节摘要：八分区模板 + 质量门 0.60 + 事务式 `commitChapter`（失败不动 state）
  - 世界书：常驻条目进静态层 `[LOREBOOK]`，命中的条目冻结在锚点消息后 `[LORE_TRIGGERED]`（sticky/cooldown/delay/prob/分组/预算，RNG 与 JS 逐位一致）
- `rpmemory`（结构化记忆：BM25 + MMR 召回，`[MEMORY_RECALL]` 拼进末条 user；按 `interval` 自动抽取，面板可「立即提取」）
- `affect`（好感度 / 心情：system 挂 `[AFFECT_RULE]` 协议、末条 user 前置短 tag、回复里的 `<state>` 解析后按 KF 推进并落盘、顶栏「心情 · 关系」徽章）

已完成（RP 深化这一轮）：

- `rpcache`：预算裁剪 + 章节摘要（八分区、质量门 0.60、事务式提交）+ 章节合并（`consolidate`，旧章节进 `archive`）+ 真实 `prompt_tokens` 校准（按 `provider|model` 分开记）+ 顶栏与每条回复的缓存徽章 + 「RP 检视」（分层 token、缓存分段与稳定前缀、六个预算输入）。
- `affect`：好感度面板（当前心情/关系/档位/好感、30 轮 sparkline、漏标率、护栏计数）+ 漏标补偿（没输出 `<state>` 时记 `stateMissing`，达阈值后用存盘好感值给最多 3 条历史助手消息补示 `<state>`，只进请求不入历史）。
- `rpmemory`：记忆面板（列表/置顶/停用/删除/立即提取）。
- 世界模板：设置 →「世界模板」新建/改名/改块/长按删除；「RP 状态 › 从世界模板填入」把块填进对话，并补开场白。
- 其它：生图参考图（相册多选 → 缩到长边 1024px → `image` / `input_references`）、用量页导出 CSV、余额页、知识库编辑页。

仍缺席：

- `rpmemory` 的账本部分（`upsertFact` / `renderLedger` / `checkSummaryAgainstLedger`）——只影响「摘要与账本冲突时降权并提示」，不影响注入。
- 生图历史落盘 / 保存到相册 / 聊天里「据此生图」。
- 用量页自定义起止日期区间、按服务商分组。

已完成（本仓库此前的两轮）：

- 世界模板：设置 →「世界模板」可新建 / 改名 / 改块 / 长按删除；对话里「RP 状态 › 从世界模板填入」把模板块填进当前对话。
  保存改成一次写多块（setRpBlocks），修掉「填了 world+character 只有 world 落盘」的覆盖问题。
- 记忆面板：对话溢出菜单 →「记忆」，列表 / 置顶 / 停用 / 删除 / 立即提取（写 `rp-memory.json`）。
- RP 面板：对话溢出菜单 →「RP 状态」，可编辑 7 个 L1 块（世界 / 角色 / 规则 / 输出格式 / 示例 / 禁止 / 风格）。
  修根因时发现 `ConversationCodec.RP_KEYS` 少了 `blocks` 和 `settings` 两个根键，导致 `rpState` 永远为空、
  `rpcache` 在设备上**从未生效且完全静默**——现已补上并加注释。
- `rpcache` 可见性三件套：每条回复的「缓存 67%」徽章（不支持统计时显示「缓存 不支持统计」，异常时 `⚠`）、
  顶栏「命中 67%」（点开有上下文用量）、被章节覆盖/超出预算的消息变灰并给出说明。
  判定口径照 `rp-plugin.js`：`pct` 一位小数、`tone` 0.7/0.3 分档、异常用前 5 次均值（≥50% 且低于均值 30 个点且不足其 60%）。

注册顺序固定：`knowledge → rpcache → rpmemory → usage → gcache → mmcache → affect`。

⚠️ `engine` 构造时捕获的是 `plugins` 引用本身，替换实现必须**重新构造 engine**（或改成传 provider），
否则会得到「UI 看到开关变了、引擎毫无反应」的另一种版本。

## 2. 搜索就绪判定有两份 ✅ 已修

引擎读 `settings.search.hasKeys[provider]`（配置快照），UI 读 `SearchConfig.ready(s, keys)`
（实时查 KeyVault）。两份不一致时会出现 UI 允许发、引擎静默跳过；而且 `SearchCore.keyId(cfg)`
也依赖 `hasKey`，所以只在闸门处放宽不够——必须让 `hasKeys` 反映真实 Key。

已做（`SearchKeySync` + `AppGraph.syncSearchKeys()`，启动迁移后对账一次，无差异不写盘）。

遗留：`ChatController.setSearchOn` 的提示仍是猜测文案；若要「点开关时立刻告知 Key 缺失」，
得让它同步查 `KeyVault`（现在是启动时已对账，所以实际不会误报）。

## 3. 页面（当年「未做」清单 + 后续补齐情况）

- 生图页 ✅ 已完成（`net/ImageGen.kt` + `net/DefaultImageClient.kt` + `ui/image/ImageScreen.kt`）
  - 协议照 `providers.js:721–823` 分三支：OpenRouter → `POST /images` + `resolution`/`aspect_ratio`；
    FLUX 3 / Seedream 档位模型 → 同样用档位，但路径是 `/images/generations`；
    其余 OpenAI 兼容 → `size`（硅基流动改 `image_size` 并去掉 `n`，`kolors` 再加 `batch_size`）
  - 响应解析兼容 `data[]` / `images[]`、`url` 与 `b64_json`（带 `media_type`/`mime_type`），
    既无 url 又无 b64 的项跳过；非 JSON 响应不抛异常
  - 图片渲染不引 Coil：`ui/common/RemoteImage.kt` 用 OkHttp + `BitmapFactory`，带 24 条 LRU
  - ~~没做~~ **已全部补齐**：参考图上传（PR #27，上限按服务商 10/16）、保存到相册（`ui/image/ImageSaver.kt`）、聊天里「据此生图」（PR #31）；生图历史仍只在内存（旧版 `imagegen.js` 同样不落盘，非缺口）
- 用量页 ✅ 已完成（`ui/settings/UsageScreen.kt` + `domain/cost/UsageReport.kt`）
  - 区间切换（今天 / 7 天 / 30 天 / 全部）、汇总卡、按模型、最近 14 天
  - 费用是**展示时**按 `usage-stats-meta.json` 的价目实时算的（`CostCoreJs.costOfEntry`），
    不是写入时算的 —— 换价目表后历史费用会跟着变，与旧 JS 一致
  - 已知边界：`peak` 折扣会让「auto」模式下的费用是标价的 0.5 倍（非高峰），
    所以数字比"单价×用量"低一半是正常的
  - ~~没做~~ **已补齐**：自定义区间（日期选择）、CSV 导出（PR #30，`ui/settings/CsvExport.kt`，UTF-8 BOM 兼容 Excel）；按服务商/对话分组仍只有按模型
- ~~未接入界面~~ **已完成**：余额页（PR #21，`ui/settings/BalanceScreen.kt`）、顶栏费用 chip（PR #8，`domain/cost/UsageReport.ofMessage`）

## 4. 发版门

- 版本：当前 `versionName 1.5.1` / `versionCode 124`（只增不减）
- **签名：已改为固定证书**（2026-11-17，见 [`SIGNING.md`](SIGNING.md)）。`debug` 与 `release` 共用
  `signingConfigs.stable`（证书 SHA-256 `A2:28:0D:C1…2F:52`），因此**从 1.5.1 起所有版本都能直接覆盖安装、数据保留**。
  - 私钥在本机 `D:\android-dev\keystore\ai-chat-release.jks`，不进 Git，必须备份；丢了就再也无法覆盖升级。
  - 没有 `keystore.properties` 的机器构建出来的包**不能**给用户装（会 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。
  - 历史包袱：1.3.0 / 1.4.0 / 1.5.0 三版是三张不同的 debug 证书，所以 1.5.0 → 1.5.1 仍需**最后一次**卸载
    （走「导出整库 zip → 卸载 → 安装 → 导入」，Key 不在 zip 里需重填）。
- 打 release 包前 `lint { checkReleaseBuilds false }`（离线环境拉不到 lint-gradle 依赖，会直接 BUILD FAILED）
- Release 前必须在模拟器上跑一遍：发送 / 流式 / 停止 / 重生成 / 编辑重发 / 版本 / 附件 / 旧对话迁移 / 备份导出导入

## 5. 测试覆盖的空白

- `ChatControllerTest.filesGoToEngineAsAttachments` 只剩注释、没有断言
  → files → engine 这条链路在 UI 层是零覆盖
- `DefaultUsageStats.makeEntry` 丢 `convTitle` / `providerName`，与 JS `recordUsage` 不一致
- 无 instrumentation 测试：Compose 界面、迁移、备份全部靠手测

## 6. 评审改进进度（2026-11-16 起滚动更新）

基线：`docs/REVIEW-2026-11-16.md`（main @ `88ddbe9` / v1.1.9）。每完成一项就更新本表，不再另开进度文档。

| # | 评审项 | 状态 | 版本 / 证据 |
|---|---|---|---|
| 1 | 3.1【P0】rpmemory 中文分词退化成整句匹配 | ✅ | v1.2.0：`terms()` 委托 `RetrievalCore.grams`；用例 `chineseSentenceQueryRetrievesBySubstring` |
| 2 | 5.2【P1】自动滚底打断阅读 | ✅ | v1.2.0：仅 `switched \|\| atBottom` 时滚（offset < 200） |
| 3 | 4.2【P1】插件失败静默 | ✅ | v1.2.0：`errors: StateFlow`、`pushErr` 合并计数（上限 50）、顶栏数字角标、`PluginErrorsPanel.kt`（142 行） |
| 4 | 3.2【P1】两套 token 估算互相打架 | ✅ | v1.2.0：`estTokens` 改用 `RpCacheCore.estimateTokens`，`calib` 由 `RpMemoryPlugin` 传入（夹 0.5–2.5） |
| 5 | 3.3【P1】BM25 索引每轮重建 | ✅ | 本轮：`Index.sig` + LRU(4) 缓存、`buildIndex(list, prev)`、`clearIndexCache()`；用例 `buildIndexReusesWhenContentUnchanged` / `buildIndexRebuildsWhenContentChanges` |
| 6 | 2.3【P1】`Message.extras` 兜底关键字段 | ✅ | 本轮：`model` 提为一等字段（Codec 编解码 + 引擎写入 + 两处读取回落旧 `extras.model`）；旧数据缺键仍省略、字节不变；用例 `messageModelIsFirstClassAndLegacyBytesUnchanged`。`providerId` 暂不加（全仓无 `extras.providerId` 写入/读取点，无消费者；真需要按条记服务商时再加） |
| 7 | 2.2【P1】字节对齐旧 JS 的持久化复杂度 | ◐ | 本轮：方案修正为 4 阶段；**阶段 A 代码已落地**——`JsConvLog.diff()` 内 10 处 `equal` 改为 `semanticEqual`（冲突检测等 2 处保留 `equal`）；新增 `JsSemanticTest`（4 用例）；待 golden 逐条对比验证；B/C/D 未动 |
| 8 | 5.3【P1】三个 god file | ◐ | 本轮：ChatScreen 713 → 528（`EmptyHero.kt` / `ChatMenus.kt`）；ChatController 793 → 733（`ChatUiState.kt` 拆出 Streaming / FilePatch / Plan / ChatUiState）；JsConvLog 865 未拆——无独立顶层类型，全部耦合在 class 内，纯移动不可行，需按职责重构（建议下轮） |
| 9 | 2.1【P0】三套 JS 语义层 | ⏳ | 待做：合并到一套（净删 600+ 行） |
| 10 | 5.1【P0】流式输出整屏重组 | ⏳ | 待做：`ChatUiState.streams` 拆独立 flow |
| 11 | 6.2 死代码 `net/Sse.kt` | ✅ | 本轮：已删；用例 `sseSplitsAcrossChunks` 迁到真在用的 `StreamParser`（评审写「零引用」不准确，`SkeletonContractTest` 引用过它） |

### 本轮验证

- 代码：`RpMemoryCore.buildIndex` 加签名短路 + LRU 缓存；`RpMemoryCoreTest` 加 2 个用例。
- 代码：`Message.model` 提为一等字段（Codec / 引擎 / 两处读取）；`CodecRoundTripTest` 加旧数据字节不变用例。
- 代码：删除 `net/Sse.kt`，`SkeletonContractTest` 的 SSE 用例迁到 `StreamParser`。
- 代码：拆分 god file——`ChatScreen` 713→523（`EmptyHero.kt` / `ChatMenus.kt`，含删 17 个未使用 import）、`ChatController` 793→733（`ChatUiState.kt`）；新增 `ChatUiStateTest`（3 个用例）。
- 代码：2.2 阶段 A——`JsConvLog.diff()` 内 10 处 `equal` → `semanticEqual`；新增 `JsSemanticTest`（4 个用例）；B/C/D 未动。
- 文档：新增 `docs/structured-diff-plan.md`（2.2 四阶段方案：A 判等 → B 去 `KeyOrder` → C 去 `nullRemovedKeys` → D 清理，带开关与回滚）。
- ~~本会话 Gradle 无法复跑~~：该问题已解决，现在本机可正常构建（`gradle -p <repo> --no-daemon --offline --console=plain :app:testDebugUnitTest` 约 1 分钟、`:app:assembleDebug` 约 15 秒），CI 亦为绿。

## 7. UI / 前端优化（2026-11-16 晚）

- `MessageItem`：气泡圆角统一到 `Radii.Bubble` / `Radii.BubbleTail`（原 4 处硬编码 18/6）；用户气泡加 `pressScale` 按压反馈（长按复制不变）；消息信息行去掉冗余 `fontSize = 11.sp`。
- `InputBar`：附件删除按钮触控区 28dp（视觉仍是 20dp 圆）；附件缩略图圆角改用 `Radii.Sm`。
- `RemoteImage`：新增可选 `contentDescription`；聊天图片与待发送图片补无障碍描述。
- 未动（建议下轮）：设置 / 生图 / 抽屉等页面的间距与圆角仍有硬编码，可逐步收敛到 `Space` / `Radius`；JS 版 `www` 已不参与构建，本轮未改。

### UI 合理性审查（2026-11-16 晚·第二轮）

审查结论（合理，不改）：
- 删除会话**已有确认**：`ConvRow` 菜单 → `deleting` → `AlertDialog`，不存在误删直通。
- 「新对话」纯白按钮与旧版 `#drawer-new` 默认样式一致（旧版窄屏才切渐变），保留。
- 消息列表已有 `key = { it.id }` + `contentType`；状态栏/导航栏已 `SystemBarStyle.dark(TRANSPARENT)`；输入区 IME 感知与显隐动画完整。
- 设置页已用 `Space` / `Radius` token + `Pressable`，是本项目的规范样板。

已改（可发现性 / 一致性）：
- 顶栏模型切换：由「12sp 灰字 + 裸 `clickable`」改为胶囊 + `ArrowDropDown` + `pressScale`，明确"可以点"。
- 顶栏「对话列表 / 更多」两个 40dp 圆按钮：补 `pressScale` 按压反馈。
- 抽屉功能行（设置/插件/生图/助手/世界/模板/用量/余额）：裸 `clickable` 改为 `Pressable`，与设置页手感一致。

未改（有依据或风险高）：
- 助手操作行 12sp 文本触控区约 28dp：仿旧版紧凑设计，改动会影响视觉密度。
- 抽屉功能入口顺序：与旧版一致，不重排。
- 设置 / 生图页硬编码间距：需逐页替换并逐页回归，留待有编译验证时做。

## 8. 整体评估（2026-11-16 晚）

| 指标 | 数值 |
|---|---|
| 生产代码 | 136 文件 / 25,392 行 |
| 单测 | 49 文件 / 6,589 行（≈26%） |
| 版本 | v1.2.0（本轮改动未 bump） |
| 进度 | 11 项：6 完成 / 2 部分 / 3 未开始 |

- **已完成**：3.1 中文分词、5.2 自动滚底、4.2 插件失败可见、3.2 token 估算、2.3 `Message.model`、6.2 删 `Sse.kt`。
- **部分完成**：5.3（ChatScreen 523 / ChatController 733，JsConvLog 未拆）、2.2（阶段 A 已落地）。
- **未开始**：2.1 合并三套 `Js`（P0）、5.1 流式状态拆分（P0）、instrumented 测试 + CI。

**风险**

1. 本轮 8 个生产文件 + 3 个测试文件未编译验证（沙盒无法写 `gradle-home`）。
2. 2.2 阶段 A 改变 oplog 生成路径，必须用 golden 逐条对比确认 ops 不变。
3. 无 CI 门禁，Compose 层改动基本靠手测。
4. `JsConvLog` 917 行仍未拆，且耦合 replay/diff/commit/压缩。

**建议**

1. 立即本地跑 `./gradlew --no-daemon :app:testDebugUnitTest`，重点 `JsSemanticTest` / `CodecRoundTripTest` / golden；通过后建议发 v1.2.1。
2. 下一轮优先 **2.1**（P0，净删 600+ 行），再做 **5.1**。
3. 并行把 `testDebugUnitTest` 接进 CI。
4. 2.2 阶段 B 等 golden 结果，不叠加未验证改动。
> 独立评估与优化建议（不依赖本地文件状态、按 ROI 排序）见 `docs/OPTIMIZATION-ADVISORY.md`。
