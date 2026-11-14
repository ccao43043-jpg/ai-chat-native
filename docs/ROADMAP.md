# Roadmap

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

## 3. 未做的页面

- 生图页 ✅ 已完成（`net/ImageGen.kt` + `net/DefaultImageClient.kt` + `ui/image/ImageScreen.kt`）
  - 协议照 `providers.js:721–823` 分三支：OpenRouter → `POST /images` + `resolution`/`aspect_ratio`；
    FLUX 3 / Seedream 档位模型 → 同样用档位，但路径是 `/images/generations`；
    其余 OpenAI 兼容 → `size`（硅基流动改 `image_size` 并去掉 `n`，`kolors` 再加 `batch_size`）
  - 响应解析兼容 `data[]` / `images[]`、`url` 与 `b64_json`（带 `media_type`/`mime_type`），
    既无 url 又无 b64 的项跳过；非 JSON 响应不抛异常
  - 图片渲染不引 Coil：`ui/common/RemoteImage.kt` 用 OkHttp + `BitmapFactory`，带 24 条 LRU
  - 没做：参考图上传、历史落盘（现在只在内存里，最多 10 条）、保存到相册、聊天里「据此生图」
- 用量页 ✅ 已完成（`ui/settings/UsageScreen.kt` + `domain/cost/UsageReport.kt`）
  - 区间切换（今天 / 7 天 / 30 天 / 全部）、汇总卡、按模型、最近 14 天
  - 费用是**展示时**按 `usage-stats-meta.json` 的价目实时算的（`CostCoreJs.costOfEntry`），
    不是写入时算的 —— 换价目表后历史费用会跟着变，与旧 JS 一致
  - 已知边界：`peak` 折扣会让「auto」模式下的费用是标价的 0.5 倍（非高峰），
    所以数字比"单价×用量"低一半是正常的
  - 没做：自定义区间、按服务商/对话分组、CSV 导出（`UsageStatsCore.toCSV` 已就绪）
- 成本与余额（`CostCoreJs` / `Balance` 已移植，未接入界面；顶栏 chip 仍只显示 token）

## 4. 发版门

- `versionName 1.0.0`、`versionCode 100`（不可回退）
- 覆盖安装必须同一张调试证书：本机构建的 `~/.android/debug.keystore` 与盒子不同，
  换机器构建会导致无法覆盖安装（只能卸载 = 清数据）
- Release 前必须在模拟器上跑一遍：发送 / 流式 / 停止 / 重生成 / 编辑重发 / 版本 / 附件 / 旧对话迁移

## 5. 测试覆盖的空白

- `ChatControllerTest.filesGoToEngineAsAttachments` 只剩注释、没有断言
  → files → engine 这条链路在 UI 层是零覆盖
- `DefaultUsageStats.makeEntry` 丢 `convTitle` / `providerName`，与 JS `recordUsage` 不一致
- 无 instrumentation 测试：Compose 界面、迁移、备份全部靠手测
