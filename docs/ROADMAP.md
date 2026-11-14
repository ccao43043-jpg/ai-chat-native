# Roadmap

从旧仓库 `native/i2` 的起点继续。已完成的部分（CI、`recordUsage` 收口、`plugins` 单实例、
引擎接联网搜索 + `NetRetry`）不再重复。

## 1. 插件与缓存（收益最大）

`AppGraph.plugins` 现在是 `PluginRegistryImpl.createDefault()`，已生效：

- `knowledge`（本地资料库注入，三道闸：总闸 / 对话闸 / 世界）
- `usage`（Token 徽章）
- `gcache` / `mmcache`（缓存，命中率不再是 0）

仍缺席（代码已在 `plugins/`，缺 plugin 适配层）：

- `rpcache`：RP 状态机核心已有，但「经 transformRequest 的请求组装」未接
- `affect`：Kalman + CUSUM 已有，但响应钩子未接

注册顺序固定：`knowledge → rpcache → usage → gcache → mmcache → affect`。

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

- 生图页（`ImageClient` / `ImageScreen`）
- 用量页（`UsageStats` 核心已在，缺 UI）
- 成本与余额（`CostCoreJs` / `Balance` 已移植，未接入）

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
