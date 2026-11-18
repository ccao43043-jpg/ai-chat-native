# 统一路线图（Master Plan）

> **状态**：✅ 活跃 · 唯一优先级来源 · 最后核实 **2026-11-17 22:20（`main @ 37590e7` / v1.5.2）**
> 最近一次全面核对：[ASSESS-2026-11-17.md](ASSESS-2026-11-17.md)（修正 11 处过期状态 + 新增批次 A/B/C）
> 文档索引见 [README.md](README.md)；算法与缓存两条线的细化见 [PLAN-ALGO-CACHE.md](PLAN-ALGO-CACHE.md)。

> **本文是唯一的执行优先级来源。** 它合并了五份文档的口径，冲突时以本文为准：
> - `REVIEW-2026-11-16.md`（工程评审 + §6 滚动进度表）
> - `ROADMAP.md`（功能缺口）
> - `OPTIMIZATION-ADVISORY.md`（战略评估 + 缓存算法专项 §10 + 改进方案映射 §11）
> - `structured-diff-plan.md`（2.2 持久化迁移四阶段）
> - 外部《AI-Chat 改进方案与关键算法》（算法线 D1–D11）
>
> 每完成一项，更新 `REVIEW-2026-11-16.md` §6 进度表；本文只维护优先级与里程碑。

---

## 1. 项目评估（2026-11-16，main @ `be6688c` / v1.3.0）

> **本节是 11-16 的历史快照，数值已过期**（单测 348→413、golden 53→121、instrumented 3 坏→12 全绿）。
> 最新状态只看 §5；口径细节见 [ASSESS-2026-11-17.md](ASSESS-2026-11-17.md) §1。

### 1.1 一句话

**技术底盘是工厂级，产品层是实验室级**：协议、流式、oplog 持久化、成本核算、缓存分层、RP 记忆检索都成熟且有 golden 锁定；短板在 UI/集成测试、状态粒度、以及"逐字节对齐旧 JS"遗留的架构利息。下一阶段的主旋律是**降每次改动的单位成本 + 提用户可感知的体验**，不是加功能。

### 1.2 质量基线

| 维度 | 现状（11-16 快照） | 判断 |
|---|---|---|
| 单测 | ~~348~~ → **413 例全绿**（11-17 实测） | 良好 |
| CI | android.yml 单测+编译；instrumented.yml 已接入且**已转绿**（KVM 真因修复，PR #92） | 良好；仍缺 lint/detekt 与 release 变体验证 |
| golden | ~~53~~ → **82 jsonl + 39 json**（11-17 实测） | 强项 |
| UI/集成测试 | ~~3 个用例已坏且不在 CI~~ → **12 例全绿并进 CI**；仍缺页面级测试 | 已从短板转为「基本可用」 |
| 架构 | 三套 Js 语义层、四个 600+ 行 god file；~~KeyOrder 三层补丁~~ **已删**（阶段 B/C 完成） | 仍有技术债 |

### 1.3 对 OPTIMIZATION-ADVISORY 的勘误（2026-11-16）

1. §3「CI 无（依赖本地执行）」**不成立**——CI 一直在跑（本日已验证 8+ 次）。真正的缺口是 lint/detekt、instrumented 接入、release 变体编译。
2. §11.1/§11.3「D1 未实现」**已过时**——D1 MMR 增量化已于 PR #67 落地（差分测试锁定输出不变）。
3. §11.2 的判断保持有效：原生版已**反向超越**改进方案之处（D11、mmr seed、Message.model、索引缓存、token 估算收口）不要重复实施。

---

## 2. 统一优先级（融合五源后的唯一清单）

> 标注：〔算法〕= 来自改进方案；〔工程〕= 来自评审/advisory；〔产品〕= 体验项；〔悬案〕
> 每项给工作量与前置依赖。
>
> **〔算法〕与缓存专项的细化执行计划见 [PLAN-ALGO-CACHE.md](PLAN-ALGO-CACHE.md)（2026-11-17 逐行核实版：16 项现状表 + P1–P8 改法 + B1–B4 批次）。**

### P0 · 本周

> **状态（2026-11-17 12:40 复核）：下表 1–3 已全部完成，P0 清零。** 明细见 §5.4。

| # | 事项 | 量 | 状态 |
|---|---|---|---|
| 1 | 〔悬案〕PR #39（世界书编辑面板）rebase 到 main、重测、合并 | 0.5 天 | ✅ 已合并（2026-11-16 16:40） |
| 2 | 〔工程〕structured-diff **阶段 A 验收**：golden 逐条对比（ops 逐条一致 + oplog 体积不增） | 0.5 天 | ✅ PR #70 |
| 3 | 〔工程〕评审 #10：流式状态拆独立 flow，消除整屏重组 | 1 天 | ✅ PR #71 |
| 4 | 〔工程〕instrumented CI 从接入起 4 次全红，门禁形同虚设 | 0.5 天 | ✅ **PR #92**（KVM 真因修复 + 脚本外移 + 30s 超时 + 失败留语义树）；run 44088895019 `emu: success` |

### P1 · 1–2 周

| # | 事项 | 量 | 说明 |
|---|---|---|---|
| 4 | 〔算法〕统一 `TokenEstimator`（含按 provider 的图片公式） | 1 天 | ✅ **已完成**（PR #72 统一尺子 + PR #98 P1 把 provider 接进预算路径：`estimateItems`/`itemTokens`/`computeTrimWindow`/`applyTempTrim` 全透传，不传时仍 1000/张保 golden） |
| 5 | 〔工程〕structured-diff 阶段 **B → C → D**：去 `KeyOrder.align` → 去键删除补丁 → 清理 | ✅ 0 天 | **已完成**（阶段 B `#74`、阶段 C+D `#76`）：`Repositories.saveLocked` 已无 `KeyOrder.align` / `nullRemovedMessageKeys`，判等切 `semanticEqual` |
| 6 | 〔工程〕评审 #9：三套 Js 收敛为 1 核心 + 2 薄适配 | 2–3 天 | 净删 600+ 行，消灭 `"null"/"undefined"` 一类 bug 的土壤 |
| 7 | 〔算法〕摘要最优停止（§4.2） | ✅ 0 天 | **已实现**：`RpCacheCore.summaryTiming`（PR #78），枚举 t∈0..H 的折现总成本取 argmin |
| 7b | 〔算法〕分层预算精确背包 + 选择集粘滞（§4.3） | ⛔ 不做 | 用户已拍板关闭（§5.3 第 1 条：架构演进取代） |
| 7c | 〔算法〕预算系数能力表（替代 autoBudget 里 0.8/0.1/0.02/0.3/0.12 硬编码 + 200K 封顶） | 1 天 | 见 PLAN-ALGO-CACHE **P2**，开关默认 `legacy` 保持旧行为 |

### P2 · 2–4 周

| # | 事项 | 量 | 说明 |
|---|---|---|---|
| 8 | 〔算法〕op-log 真追加 + 脏标记（D6） | ✅ 0 天 | **已完成**（PR #80）：`JsConvLog.kt:920` 逐 op `store.append`，注释明确「不再 `readOps` + 整文件重写」，写放大 O(全日志)→O(本次) |
| 9 | 〔算法〕路由折扣 Beta + 受控探索（D2/D3） | 1–2 天 | 改进方案 §4.1 有完整算法与验收用例；**先埋点验证 D2/D3 真实发生率**再开（方案 Phase 0 原则），带开关默认关 |
| 10 | 〔工程〕ChatController 按职责拆 4 个（SendFlow/VersionOps/FilesOps/RpOps） | 1 天 | 纯移动 + 接口隔离 |
| 11 | 〔工程〕JsConvLog 按职责重构（Replay/Diff/Commit/Compact） | 2 天 | 917 行 god file；配 golden |
| 12 | 〔算法〕重试 Retry-After（Full Jitter 已有：`NetRetry.backoff` 带 ×(0.8+0.4·rnd)） | 0.25 天 | ✅ **已完成**（PR #98 P4）：`NetRetry.parseRetryAfterValue`/`delayFor` 封顶 60s，`Error.retryAfterMs` 透传到引擎。见 PLAN-ALGO-CACHE P4 |
| 13 | 〔工程〕detekt/ktlint + 3 个 Compose smoke test（空态/发送/流式） | 1 天 | 一次配置长期收益；instrumented 修 3 例后接 CI（仅 main + 手动） |
| 18 | 〔产品·RP〕**角色生成 Skill 按世界分档**：`AutoChar` 的 7 个入口全仓零调用（`shouldOffer`/`buildPrompt`/`toPromptAppend`/`fillLeftovers`/`fromUserText`/`hasCharacter`/`isRpTemplate`），`settings.autoCharMode` 无消费方；一旦接线，世界里会拿模型瞎编的世界观覆盖既有分层设定 | 1.5–2 天 | 见 [PLAN-AUTOCHAR-WORLD.md](PLAN-AUTOCHAR-WORLD.md)：世界里只写 `character`（可选 `fewshot`），`world/rules/style` 逐字节不变；专场保持 8 段 |

### P1.5 · 2026-11-17 核对新增（发布工程与数据安全）

> 来源：[ASSESS-2026-11-17.md](ASSESS-2026-11-17.md) §2/§4。这四项在旧计划里完全没有，但都属于「不修就会在关键时刻出事」的类型。

| # | 事项 | 量 | 说明 |
|---|---|---|---|
| 14 | 〔发布〕**release 变体从未验证**：`minifyEnabled false`、无 `proguard-rules.pro`、CI 只跑 `assembleDebug`、`lint { abortOnError false }` | 0.5 天 | 开 R8 + `shrinkResources` + keep 规则；CI 加一次 `assembleRelease`；lint 只拦 error。预期 APK 11.7 → 7–8 MB |
| 15 | 〔数据〕**关键路径零测试**：`data/files` 595 行 / `data/backup` 75 行 / `data/secret` 118 行，`@Test` 均为 0 | 0.5 天 | 补 `capText` 边界、备份导出→清空→导入往返、关掉 `unitTests.returnDefaultValues` |
| 16 | 〔数据〕**图片以 base64 内联进 oplog**：`Message.images: List<String>` 存 `data:image/jpeg;base64,…`，一轮 10 图 ≈ 1.4–2.7 MB 写进 `conv_*.log.jsonl` | 1–2 天 | 改存 `img_<sha1>.jpg` + 消息只留键，读路径懒迁移旧 `data:` 值；需幂等 + 向后兼容 |
| 17 | 〔工程〕卫生：8 处空 catch 中 4 处该报不报（`Affect.kt:163` / `JsConvLog.kt:714` / `PdfText.kt:109` / `ProvidersScreen.kt:195`）；`diag.yml` 一次性排障工作流仍带 `contents: write` | 0.5 天 | 加 warn 日志或 UI 提示；`diag.yml` 删掉或改成仅 `workflow_dispatch` |

### P3 · 产品体验与长线（穿插做）

- 〔产品〕抽屉信息架构分组（内容/工具/账户）、触控目标 ≥44dp、图标按钮补 `contentDescription`、模型切换首次提示
- 〔算法〕D7 文件分块检索替代前缀截断——**升级为 B3 / v1.6.0**（`FileText.capText` 是纯前缀截断，问长文档后半段必错；改法见 PLAN-ALGO-CACHE **P3**，带 `files.chunkRetrieval` 开关）
- 〔算法〕Token RLS 校准（§4.4）、成本有效价格学习（§4.6）——需线上日志回放验证，长线
- 〔工程〕性能埋点（首帧 <1.2s、流式帧率、检索耗时）进 eval 门禁

### 明确不做（advisory §8，维持）

- 不扩张功能（会话内搜索/书签/分支）——功能已超出用户规模需要
- 不重写 Compose 层、不做视觉重构
- **不做 i18n / 多语言**：`res/values/strings.xml` 只有 1 条，生产代码 1,630 处硬编码中文；抽字符串是 1,600+ 处改动且零用户价值
- **不关闭 `usesCleartextTraffic`**：会破坏「用户自填 http 网关」这条真实用法，改在文档里做安全提示
- **不批量消 `!!`**：140 处已逐个核对均有前置非空判断，纯风格改动不划算
- 无验证不开大重构；所有算法改动走「开关默认关 → 灰度 → 转正」

---

## 3. 里程碑

| 版本 | 内容 | 出口标准 |
|---|---|---|
| v1.3.x | #39 清理、阶段 A 验收、流式拆分（P0 全部） | 长会话流式不掉帧；golden 逐条一致 |
| v1.4.x | TokenEstimator、diff B/C/D、三套 Js 合并（P1） | 单一 Js 核心；KeyOrder 删除；单测不减少 |
| v1.5.x | 摘要+背包、op-log 真追加、路由（P2 算法主体） | totalPromptTokens 回放不劣化；oplog 写放大 ≈1 |
| 之后 | 体验与观测（P3） | 检索 MRR/首帧/帧率进 CI 门禁 |

---

## 4. 验收指标（进 CI / eval 门禁）

| 指标 | 目标 |
|---|---|
| `testDebugUnitTest` | 全绿且单测数不减少 |
| golden 回归 | ops 逐条一致，oplog 体积不增 |
| 流式帧率 | 长会话（100+ 条）滚动无明显掉帧 |
| 首帧 | 冷启动到可输入 < 1.2s |
| 新增编译告警 | 0（unused import / variable） |

---

## 5. 完成度盘点与验收口径修正（2026-11-17 01:00 盘点，滚动更新）

### 5.1 各线完成度

| 线 | 完成度（11-17 22:20 重算） | 已完成 | 欠账 |
|---|---|---|---|
| 改进方案（算法线 D1–D11） | **~78%**（旧 ~40%） | D1、D2、D6、D8、D11 ✅；D4/D5 已关闭；D9/D10 不适用 | D3 受控探索（1 天，先埋点）、D7 文件分块（2–3 天） |
| 工程评审 11 项 | **~88%**（旧 ~75%） | 10 项 ✅（含阶段 C/D、instrumented CI 转绿） | 三套 Js 收口（3–5 天，需值模型迁移） |
| advisory 缓存专项 | **~78%**（旧 62%） | TokenEstimator 统一 + 图片公式接预算路径 + `Retry-After`（PR #98）+ 缓存策略/体检/前缀稳定化（B0） | 能力表参数化（B2，1.5 天）/ 文件分块（B3，2–3 天）/ 路由埋点（B4） |
| 发布工程（新增） | **~30%** | 固定签名 + 校验流程（`SIGNING.md`） | R8/keep 规则、release 变体验证、lint 门槛（#14，0.5 天） |
| 测试分布（新增） | **~70%** | 逻辑层 413 例 + 12 例 instrumented 全绿 | `data/files`/`data/backup`/`data/secret` 与 UI 页面级测试（#15，0.5 天起） |
| 产品体验（advisory §5） | 0% | — | 抽屉分组 / 触控目标 / 无障碍 / 提示（3–4 天，穿插） |

**总账**：核心承诺（方案 P0+P1 + 评审 P0/P1）已达 **~85%**；补齐 #14+#15（1 天）后可发「发布与数据都靠得住」的版本；#16+B2+B3 约 5–7 天到 v1.6.0。含长线 P2 仍约 3–4 周。

### 5.2 验收口径修正（三项降级/调整）

1. **D2/D3 路由探索降为先埋点**：方案自述"需线上数据验证"。先加发生率埋点跑两周，有真实数据再决定实施。
2. **RLS token 校准（§4.4）降为长线观察**：calib EWMA（按 provider|model|modality 实测校准）配合 TokenEstimator 统一尺子后已够用；RLS 的增量收益存疑。
3. **知识库检索升级（§4.5.2）先补评测**：MRR 0.60 的 golden 集偏简单（方案承认评测盲区），先补"同义改写/指代/跨条目"困难用例，指标可信后再谈 PRF/RRF。

修正后"核心完成"口径 ≈ **8–10 个工作日**，达成时以方案 §7 验收指标为准（totalPromptTokens 回放不劣化、选择集翻转 ↓80%、oplog 写放大 ≈1、文件问答后半段命中），而非逐项打卡。

### 5.3 夜间执行发现（2026-11-17 03:30，阻塞项与范围修正）

1. **§4.3 精确背包 + 粘滞：✅ 关闭（2026-11-17 用户确认，被架构演进取代）。** 决策依据：用户实际使用以 **AI 自动摘取记忆**为主（rpmemory 插件：BM25+MMR 条目召回，注入**末条 user 消息**尾部，不碰 system 前缀）——D4 的「选择集漂移毒化 system 缓存」在原生架构下不存在（system 的 memory 层是静态手写块，不编辑就不变；动态召回在尾部，选什么都不伤缓存）；600 token 尾注入场景下贪心 pickByBudget 与背包差异可忽略。若后续观察到召回条目来回跳导致 AI 行为不一致，再考虑轻量粘滞（同分时偏向近轮已召回条目）。**对用户实际使用真正有影响的是 §4.5.2 检索质量**（同义改写 MRR 0.5 的弱点同样作用于记忆召回，同一套 RetrievalCore）。
2. **三套 Js 收敛：范围上修。** `net.Js`（JsonElement 值模型）实际消费方 7 个文件——cost 域 5 个（CostCoreJs / UsageStatsCore / Balance / FloorPrices / UsageReport）+ engine 2 个，远超评审估的 2 处。cost 域深度绑定 JsonElement，收敛 = 值模型迁移 = 3–5 天（原估 2–3 天），且 net-golden 与 secure-golden 双侧锁定。建议拆两步：先共享纯 JS 语义核心（numberToString / localeCompare / round / i32 的算法层），值模型迁移单独立项。
3. **已完成 ✅（本夜）**：阶段 C/D（#76）、sessionId 收口 + 骤降告警（#77）、§4.2 摘要最优停止开关灰度（#78）、完成度盘点（#75）。
4. **2026-11-17 早批次**：#86 输入草稿丢失真 bug（AppRoot if/else 切屏丢 rememberSaveable → SaveableStateProvider 修复）+ instrumented 10/10 全绿 + 接 CI（#87/#88）；#89 检索体检 + keywords 编辑字段（§4.5.2 配套，桥接用例验收）；v1.5.0 发版。
5. **已知问题：CI 模拟器 2 次失败**（ShellCommandUnresponsive + 用例 15s 超时——CI 机器慢，本地全绿代码无问题；#88 加固后仍一次失败）。待选：调大 waitUntil 超时、或降级为仅手动触发。

### 5.4 2026-11-17 复核：P0 清零、签名修复、CI 稳定性

**1. P0 全部完成**（§2 已同步）：#39 世界书面板（11-16 16:40 合并）、阶段 A 验收（#70）、流式状态拆独立 flow（#71）。里程碑 v1.3.0 → v1.4.0 → v1.5.0 均已发版。

**2. 固定签名（#91，v1.5.1）——发版门新增硬约束。**
过去每次发版都用构建机的临时 debug 证书，实测 v1.3.0 / v1.4.0 / v1.5.0 三份 APK 是**三张不同证书**，导致用户每次升级都被 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 挡下，只能卸载重装、数据全丢。

- 现在 `debug` 与 `release` 统一挂 `keystore.properties` 指向的固定 keystore（`D:\android-dev\keystore\ai-chat-release.jks`，证书 SHA-256 `A2:28:0D:C1…2F:52`）
- 缺失该文件时构建仍可跑，但会 `logger.warn` 明确警告「产物不能覆盖安装到用户设备」
- **发版门新增：发布前必须 `apksigner verify --print-certs` 核对证书指纹**，详见 `SIGNING.md`
- ⚠️ keystore 丢失 = 所有已装设备永久无法覆盖升级。已备份到 `D:\android-dev\keystore\备份-请勿删除\`

**3. instrumented CI 稳定性（P0 #4）。** 症状：接入起 4 次运行 4 次全红，本地同 commit 10/10 全绿。日志实证（run 44007789586）：

```
versionMenuOpens           ComposeTimeoutException: Condition still not satisfied after 15000 ms
regenerateVersionsAndEdit  ComposeTimeoutException: Condition still not satisfied after 15000 ms
sendThenStop               AssertionError: Assert failed: The component is not displayed!
```

根因判断（按证据强度排序）：① CI 模拟器软件渲染慢，一帧数百毫秒 → 15s 不够；② 输入文字后软键盘不收起，视口缩半 → 消息动作行被推出可视区、`regen` 点击落空；③ `assertIsDisplayed` 在节点被键盘遮挡时假红（该用例本地此前就是这么修的）。

对策（`AppRootUiTest.kt` 顶部写死了四条约定）：
- 一律用 `waitUntilExists` 判「出现」，超时统一 **30s**；只有真要看布局才用 `assertIsDisplayed`
- 点消息动作行之前 `performScrollTo()`
- `performTextInput` 之后 `closeKeyboard()`（用 `InputMethodManager` 直接收，不引 Espresso）
- `TestWatcher` 在失败时把语义树打进 logcat（tag `UITEST`），workflow 第一次失败先落盘 `logcat-first-failure.txt` / `uitest-tree.txt` 再重试，并 `always()` 上传

**教训（值得写进任何 UI 测试）：`assertIsDisplayed` 不是「出现了」的断言，它是「此刻可见」的断言。在屏幕尺寸/键盘状态不确定的环境里，它只会制造假红。**

**4. instrumented 一直红的第二层真因：runner 没有 KVM。** 改完测试稳定性后 CI 仍然红，三次 dispatch 才挖到底（2026-11-17 下午）：

| 次数 | run | 现象 | 结论 |
|---|---|---|---|
| 1 | 44026412656 | `/usr/bin/sh: Syntax error: end of file unexpected (expecting "}")` | inline 多行 `\|\| { … }` 被 android-emulator-runner 拼成一行 → 逻辑搬进 `scripts/ci/instrumented.sh` |
| 2 | 44029807873 | `Finished 0 tests`；装 APK `Broken pipe (32)` / `Can't find service: package` | `boot_completed=1` 早于 package manager 就绪 → 脚本加 `wait_for_pm()`（要求 `pm path android` 成功） |
| 3 | 44032387166 | 42 分钟未结束（已取消）；日志 `ProbeKVM: This user doesn't have permissions to use KVM (/dev/kvm)` + `Disabling Linux hardware acceleration.` | **真因**：模拟器一直在做纯软件 x86_64 模拟，首启 411 秒，system_server 起不来 |

修法：workflow 加「开启 KVM 加速」步骤（写 `99-kvm4all.rules` + `udevadm control --reload-rules` + `udevadm trigger`），镜像回到启动更快的 AOSP `default`，`cores`/`ram-size` 改用 action 自带输入而非塞进 `emulator-options`。

**教训：UI 测试的 flake 必须先区分「测试写错」和「环境根本没跑起来」。** 前两次失败的日志全在装 APK，跟测试代码毫无关系；`Finished 0 tests` 是最关键的信号——看到它就不要再改断言了，去查设备。

### 5.5 算法与缓存细化计划（2026-11-17）

细化执行计划已单独立文：[`PLAN-ALGO-CACHE.md`](PLAN-ALGO-CACHE.md)。要点：

| 批次 | 内容 | 工时 | 版本 |
|---|---|---|---|
| B0 | 缓存策略选择（每会话唯一 owner）· 缓存体检账本与 6 类 miss 归因 · 前缀稳定化与成本模型 | 0.5 天 | ✅ 已完成（438 单测）→ v1.5.3 |
| B1 | 图片 token 接入预算路径 · `Retry-After` · 过期注释清理 | 0.75 天 | ✅ 已完成（PR #98，413 单测）→ v1.5.3 |
| B2 | 预算系数能力表（默认 legacy）· `plan()` 单轮估算 memo | 1.5 天 | v1.5.4 |
| B5 | 上下文管线结构性整顿（槽位契约 / 召回总线 / 单一缓存 owner / 角色生成分档 / 记忆四层 / 缓存插件骨架），见 [PLAN-CONTEXT-ARCH.md](PLAN-CONTEXT-ARCH.md) §8 | 已实施 M1–M7 | **v1.6.0** |
| B3 | D7 文件分块检索（替代前缀截断） | 2–3 天 | v1.6.0 |
| B4 | 路由埋点 → 观察两周 → 探索/衰减；校准冷启动先验 | 1–2 天 + 观察 | v1.6.x |
| B6 | 召回纳入预算 · 缓存路线一轮只算一次 · 跨来源软去重 · 体检面板显示插件异常与装配说明 | 0.5 天 | ✅ 已完成（PR #112，577 单测） |

**更正三条过期判断**（此前口头/文档说法与代码不符，避免重复劳动）：
1. **D8 摘要最优停止已实现**（`RpCacheCore.kt:175 summaryTiming`，PR #78）——`RpCacheCore.kt:419` 那句「有意没搬 adaptiveSummary」是过期注释，B1 一并改掉。
2. **D2 Beta 折扣路由已实现**（`net/Router.kt:251 chooseUpstream`：`hitQuantile(st.a, st.b, z)` + stickyIdle + 切换成本折现）；真正缺的是**探索**与**统计衰减**（B4 先埋点）。
3. **D1 索引缓存已实现**（`RpMemoryCore.kt:437 buildIndex(list, prev)` 签名复用）；`GCache.adaptiveMinTokens` 与 `sessionId` 也已落地（advisory §10.3 已完成）。

### 5.6 2026-11-17 22:20 全面核对（详见 ASSESS-2026-11-17.md）

**方法**：不看文档看代码——逐条把 §1/§2 的状态声明拿 `grep` + 读实现核对，再补一轮「文档里根本没有」的扫描（构建配置、测试分布、空 catch、硬编码文案、APK 组成）。

**结论一：11 处状态不符，全是「做完了没回写」**（详见 ASSESS §1.2）。已在本轮全部修正：#4 instrumented、#5 阶段 B/C/D、#8 D6、§1 单测/golden/UI 测试/架构四行、§5.1 三行完成度、文首核实时点。

**结论二：发布工程是最大空白**（新增 #14）。`release { minifyEnabled false }`、无 `proguard-rules.pro`、CI 只 `assembleDebug`、`lint { abortOnError false }` —— 也就是说**至今所有发布包其实都是 debug 构建**。固定签名解决了「能不能覆盖安装」，但没解决「产物是不是发布产物」。

**结论三：用户数据路径两处硬伤**（新增 #15/#16）。
- `data/files`（文件截断，D7 的核心）、`data/backup`（整库导出/导入）、`data/secret`（KeyVault）三个目录 **0 单测**——而备份导入会整库替换，写错一次就是数据丢失。
- 图片以 `data:image/jpeg;base64,…` 内联进消息（`Message.images`），一轮 10 图 ≈ 1.4–2.7 MB 进 `conv_*.log.jsonl`，长图会话文件可达数十 MB，且每次 commit 都要把它重新序列化。

**结论四：明确不做的三件事写死**（i18n、关 cleartext、批量消 `!!`），避免以后反复讨论——判断依据见 ASSESS §2.4/§2.5。

**流程教训**：上一轮文档收敛只给每份文档加了「身份标签」，但没有机制保证**状态列**回写。这次的做法是把「核对」本身变成定期动作（本节的日期 + main sha），并且**状态只有一个列**（§2 的「状态」列 + §5.1 的完成度），其他文档一律指向它。
