# 统一路线图（Master Plan）

> **状态：活跃，唯一执行优先级与当前状态来源。** 核对日期 **2026-11-20 PST**。
> 基线：[`main @ 5e4502a`](https://github.com/ccao43043-jpg/ai-chat-native/commit/5e4502a2f39a7372a1e552d3026ccdb8bc215ce6) = `v1.7.9`，versionCode **179**；不是旧 `ai-chat-app` 的原生分支。
> 证据与本轮勘误见 [ASSESS-2026-11-20.md](ASSESS-2026-11-20.md)；入口见 [文档索引](README.md)。

**维护规则**：本文维护优先级、完成状态与待办；专项计划保存设计、实现细节和局部验收依据。历史 REVIEW / ASSESS / ADVISORY 不再承担滚动进度表职责。完成一项时回写本文，并同步专项计划与索引；不能只更新发布说明。

## 1. 项目评估与当前基线

| 维度 | 已核实事实 | 不能据此推出 |
|---|---|---|
| 版本 | main 与 v1.7.9 tag 同为 `5e4502a`；Release 发布时间 **2026-11-18 23:12 PST** | 已在所有设备验收 |
| JVM 测试 | 最新 [android CI](https://github.com/ccao43043-jpg/ai-chat-native/actions/runs/44477755495) 的 `testDebugUnitTest` 成功；当前源码有 **661 个 `@Test` 声明**（95 个含注解的文件） | 控制台未列执行总数，不能把源码计数冒充本轮执行结果；Release 的「661 / 97 类」是历史发布口径 |
| 编译 | 同一 CI 的 `assembleDebug`、`assembleRelease`（R8）成功 | release 的运行时行为、固定发布签名已经在 CI 验证 |
| 模拟器 | 同一 SHA 的 [instrumented CI](https://github.com/ccao43043-jpg/ai-chat-native/actions/runs/44477755550) 报告 **12 tests / 0 failures / 0 errors / 0 skipped** | 真机、真实付费服务商、release 变体全流程已验收 |
| 本次文档整理 | 读源码、测试、提交与 CI 日志/报告，检查 Markdown 链接与差异 | 本次没有重跑功能测试、构建、模拟器或覆盖安装 |

不再使用「85% / 89%」等混合百分比：代码落地、自动化验证、发布、真机验收是四个不同维度。

## 2. 统一优先级与状态

原有编号保留，避免旧文档引用失效。下面的优先级是现有待办的整理，不是本次恢复功能开发或新增发版承诺。

### P0 · 当前阻塞与验收

- 原 P0 世界书面板（PR #39）、structured-diff A（#70）、流式状态拆 flow（#71）、instrumented CI 修复（#92）均已合入；当前两条 CI 绿，**未发现新的已证实 P0 阻塞**。
- 固定签名仍为发布硬门：[SIGNING.md](SIGNING.md)。规格旧证书 `6054…` **不是**独立仓库 v1.5.1+ 的发布证书；现为 `a228…2f52`。
- 真机覆盖安装、真实模型请求、R8 release 运行时仍需每次发版单独验收，不随「CI 绿」自动完成。

### P1 · 先补齐既有承诺与保护网

| 编号 / 批次 | 当前状态 | 证据与剩余出口 |
|---|---|---|
| #18 / AUTOCHAR S3 | **部分完成，不能写 S1–S3 全部完成** | S1/S2 与首条消息提示条、请求/保存已接入：`AutoChar.kt`、`AutoCharService.kt`、`ChatScreen.kt`；`AutoCharWorldTest` / `AutoCharServiceTest`。缺 RP 面板独立生成按钮、设置页三档、auto/ask 行为区分、dismiss/asked 与换世界重询的持久化闭环、丢弃字段明细展示；真机/真实模型待验。见 [专项现状](PLAN-AUTOCHAR-WORLD.md#当前实现核对2026-11-20-pst) |
| #14 发布工程 | **R8 与 release 编译已完成；质量门未全完成** | PR [#108](https://github.com/ccao43043-jpg/ai-chat-native/pull/108)，`app/build.gradle`、`app/proguard-rules.pro`、`android.yml`；仍 `lint { checkReleaseBuilds false; abortOnError false }`，无 lint/detekt 门；release 编译不是运行验收 |
| #15 数据保护 | **不再是「三个目录零测试」；仍有缺口** | PR [#109](https://github.com/ccao43043-jpg/ai-chat-native/pull/109)；`FileTextTest`、`FileStoreTest`、`MigrationTest`、`BlobStoreTest`，CI 含 `DeviceRegressionTest` 备份往返。真实 Android Keystore/SecretStore 仍无专门测试，`returnDefaultValues=true` 仍在；导入中断/磁盘写失败恢复需补证，不能因正常往返通过宣称事务安全 |
| CONTEXT M4 / B6 验收 | **代码已合入发布，统一召回默认未启用** | PR [#112](https://github.com/ccao43043-jpg/ai-chat-native/pull/112)，`PluginRegistryImpl.assembleRecall`、`RecallBudgetTest`、`RecallBusSoftDedupeTest`；`recall.unified` 默认 false，跨来源预算/去重不能宣传为所有会话默认生效。先完成显式开启/关闭的端到端验证，再另行决定是否转正 |

### P2 · 算法与工程存量

| 编号 / 批次 | 当前状态 | 证据与剩余出口 |
|---|---|---|
| #4 / B1 | **已完成并随版本发布** | 统一 `TokenEstimator` 与 provider 图片公式进入 RP 预算，PR #72 / #98；`TokenEstimatorTest`、`RpCacheCoreTest` |
| #5 / structured-diff B/C/D | **代码完成，已发布；真机检查不在本次证据内** | PR #74 / #76；`Repositories.saveLocked` 无键序/缺键补丁，`JsConvLog.diff` 使用语义判等与显式 null；`ConvLogPhaseATest` / `ConvLogPhaseCTest` |
| #6 三套 Js | **未收敛** | `data/json/Js.kt`、`plugins/js/Js.kt`、`net/JsCompat.kt` 仍独立；先共享纯语义算法，值模型迁移另评估，不能按旧 2 天估计承诺 |
| #7 / D8 摘要最优停止 | **已实现** | PR #78；`RpCacheCore.summaryTiming`、`RpCacheCoreAdaptiveSummaryTest`；线上节费幅度未独立实测 |
| #7b / D4/D5 背包与粘滞 | **关闭，不排队** | 历史范围决定见 §5.3；不要重新列成欠账 |
| #7c / B2 | **待做** | `RpCacheCore.autoBudget` 仍有 200K 上限与固定系数；`budgetProfile` 尚非可用配置。同一图片重复尺寸解析/memo 仅保留为优化候选，先测性能 |
| #8 / D6 op-log 真追加 | **已完成** | PR #80；`JsConvLog.commit` 用 `store.append`；`ConvLogD6Test` |
| #9 / B4 路由 | **Beta 已有，埋点/受控探索/统计衰减仍待做** | `Router.chooseUpstream` 已有 Beta 估计、idle sticky、切换成本；无计划中的 epsilon/UCB 与时间衰减。先埋点、拿到观察数据，再决定是否做算法 |
| #10 ChatController / #11 JsConvLog 拆分 | **未完成** | 当前分别 811 / 947 行，未形成原计划 SendFlow/VersionOps/FilesOps/RpOps 与 Replay/Diff/Commit/Compact 拆分；避免把「工程评审 10/11 完成」当作包含这些新增任务 |
| #12 / B1 重试 | **Retry-After 已完成** | PR #98；`NetRetry.delayFor`、`RetryAfterTest`；退避为 ±20% 乘性 jitter，**不是 Full Jitter**；不声称线上重试成功率 >90% 已达标 |
| #13 静态检查 / Compose smoke | **部分完成** | 12 例 instrumented 在 CI（发送、停止、版本、页面与错误面板等）；detekt/ktlint 未接，页面覆盖不等于全量产品验收 |
| #16 图片外置 / B3 文件检索 | **已完成并发布 v1.7.0** | PR [#114](https://github.com/ccao43043-jpg/ai-chat-native/pull/114) / [#115](https://github.com/ccao43043-jpg/ai-chat-native/pull/115)；`ChunkRetrieval`、`BlobStore`、引擎/输入栏接线；`ChunkRetrievalTest` / `BlobStoreTest`。120k 是入库字符上限，不是无限长文保证；未引用 blob 回收未接调用方 |
| #17 卫生 | **已处理原批次** | PR [#110](https://github.com/ccao43043-jpg/ai-chat-native/pull/110) 补日志、移除 `diag.yml`；不表示再无静默失败。`android.yml` 仍因失败日志推分支持有 `contents: write`，最小权限作为待评估项 |

### P3 · 原有产品/长线观察（不扩大路线）

- 抽屉分组、触控目标、无障碍与提示：已有局部改动，未有整套验收记录，不再写「产品体验 0%」。
- 生图已有参考图与相册保存入口；**历史仍仅当前页面会话内存，不是持久化图库**（`ImageScreen`）。
- 检索困难集/同义改写/指代/跨条目评测、首帧/流式帧率/检索耗时门禁仍待证据；RLS token 校准与有效价格学习保留长线观察，不先改算法。
- [规格 §3.5](NATIVE_REWRITE_SPEC.md#35-行为对齐政策与有意差异契约变更-l) 的续写 system 后缀、provider.order 与 session sticky 诊断仍是后续候选，不视为已完成。

### 明确不做（沿用原决定）

不新增会话搜索/书签/分支、不重写 Compose 视觉层、不做 i18n、不关闭用户自填 HTTP 网关所需的 cleartext、不批量消 `!!`；无验证不开大重构。开关转正需要独立依据，不能把拟议开关写成已实现配置。

## 3. 里程碑：已发布事实与未承诺部分

| 版本 | 已进入该版本及后续版本的内容 | 依据 |
|---|---|---|
| v1.6.0 | 上下文槽位/trace、统一召回可选路径、AutoChar 作用域与首条提示条、事实账本、缓存公共骨架 | `19b1ff9`，CONTEXT §8；不代表所有原设计 UI 已完成 |
| v1.6.1 | 缓存接线补课、R8、落盘测试与卫生 | #106–#111 |
| v1.6.2 | B6 召回预算/路由单轮 memo/软去重/异常体检 | #112–#113；统一召回仍受开关约束 |
| v1.7.0 | B3 文件分块 + 图片 CAS + 含 blobs 的备份 | #114–#116 |
| v1.7.1–v1.7.9 | OpenRouter Flex、Gemini 缓存修复、按模型思考等级持久化、消息操作栏横滑 | #117/#118、`05dfd09`、`30d1d26`–`6d482c4`、`3ff85f1` / `5e4502a` |
| 下一版本 | 未指定 | 不沿用旧计划的「B2=v1.5.4、B3=v1.6.0」预测；待选择 §2 存量任务后另定 |

## 4. 验收口径

1. **代码完成**：有真实消费入口与写回；纯函数和单测存在不等于功能接通。
2. **自动化通过**：明确 SHA、run、变体、实际执行结果；源码注解数仅作统计。golden 锁定已覆盖样例，不证明线上节费/语义质量指标。
3. **已发布**：有 tag/Release 且包含相应提交；不能由文档勾选推断。
4. **待真机验收**：固定证书覆盖安装、备份恢复、真实服务商、release/R8 运行时等，没有独立记录就保留待验。
5. 目标仍保留：长会话流式不明显掉帧、冷启动可输入 <1.2s、检索质量可回放；目前不是全部已接入 CI 的门禁。

## 5. 盘点、范围决定与追溯

### 5.1 各线完成度

不再给混合百分比。算法/工程状态统一看 §2；专项设计分别看 [算法缓存](PLAN-ALGO-CACHE.md)、[上下文架构](PLAN-CONTEXT-ARCH.md)、[角色生成](PLAN-AUTOCHAR-WORLD.md)。代码与自动化已经比 v1.5.2 完整很多，但 S3 UI、静态门禁、默认关闭路径验收与真实设备验收不能抹掉。

### 5.2 验收范围决定（历史决定，继续适用）

路由探索先埋点观察；RLS 降为长线；知识/记忆检索先补困难评测再谈 PRF/RRF。原定工时与旧版本目标不再滚动沿用。

### 5.3 背包关闭与 Js 收敛范围（保留决定依据）

- **精确背包 + 选择集粘滞已在 2026-11-17 的记录中关闭**：动态记忆主要注入末条 user，而非 system 前缀；小额召回预算下尚无证据支持复杂背包。若后续出现行为漂移，先评估轻量粘滞，不恢复原方案全套。
- 三套 Js 收口涉及 cost 域的 JsonElement 值模型，不是简单删适配器；先共享纯语义核心，值模型迁移单独评估。原记录已把 2–3 天修正为 3–5 天，仍只是历史估计。

### 5.4 发布与 CI 历史

固定证书自 v1.5.1 起使用；早期临时证书导致不能覆盖安装的根因与迁移提示保留在 [SIGNING.md](SIGNING.md)。CI 的 KVM/PM 就绪问题已修复，当前绿灯不撤销每版的签名与装机门。

### 5.5 算法与缓存细化

B1/B3/B5/B6 已合入，B2/B4 尚有存量；详细证据与局部限制见 [PLAN-ALGO-CACHE.md](PLAN-ALGO-CACHE.md)。B5 不能混指所有 CONTEXT/AutoChar UI 承诺。

### 5.6 历史核对与本轮变化

- [ASSESS-2026-11-17.md](ASSESS-2026-11-17.md)：v1.5.2 快照，保留当时的风险与依据。
- [ASSESS-2026-11-20.md](ASSESS-2026-11-20.md)：本轮 v1.7.9 代码/CI/发布核对；纠正「做了没回写」和「部分做了却标全完成」。
- 本文被整理前的完整滚动记录仍可在 [v1.7.9 固定版本](https://github.com/ccao43043-jpg/ai-chat-native/blob/5e4502a2f39a7372a1e552d3026ccdb8bc215ce6/docs/MASTER-PLAN.md) 查阅；没有搬移或删除历史评估文件。
