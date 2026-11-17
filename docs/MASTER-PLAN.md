# 统一路线图（Master Plan）

> **状态**：✅ 活跃 · 唯一优先级来源 · 最后核实 2026-11-17（`main @ d4ebd1c` / v1.5.2）
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

### 1.1 一句话

**技术底盘是工厂级，产品层是实验室级**：协议、流式、oplog 持久化、成本核算、缓存分层、RP 记忆检索都成熟且有 golden 锁定；短板在 UI/集成测试、状态粒度、以及"逐字节对齐旧 JS"遗留的架构利息。下一阶段的主旋律是**降每次改动的单位成本 + 提用户可感知的体验**，不是加功能。

### 1.2 质量基线

| 维度 | 现状 | 判断 |
|---|---|---|
| 单测 | 348 例全绿（今日 +8） | 良好 |
| CI | android.yml：每次 PR + main push 跑单测 + 编译（今日 10+ 次运行） | 良好；缺 lint/detekt 与 instrumented 接入 |
| golden | 53 个 jsonl + 覆盖安装夹具，双实现防漂移 | 强项 |
| UI/集成测试 | Compose 全靠手测，3 个 instrumented 用例已坏且不在 CI | **短板** |
| 架构 | 三套 Js 语义层、两个 700+ 行 god file、KeyOrder 三层补丁 | **主要技术债** |

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
| 4 | 〔工程〕instrumented CI 从接入起 4 次全红，门禁形同虚设 | 0.5 天 | 🔄 PR 见 §5.4（超时/滚动/收键盘 + 失败留语义树） |

### P1 · 1–2 周

| # | 事项 | 量 | 说明 |
|---|---|---|---|
| 4 | 〔算法〕统一 `TokenEstimator`（含按 provider 的图片公式） | 1 天 | ✅ **已完成**（PR #72 统一尺子 + PR #98 P1 把 provider 接进预算路径：`estimateItems`/`itemTokens`/`computeTrimWindow`/`applyTempTrim` 全透传，不传时仍 1000/张保 golden） |
| 5 | 〔工程〕structured-diff 阶段 **B → C → D**：去 `KeyOrder.align` → 去键删除补丁 → 清理 | 2.5 天 | 按 `structured-diff-plan.md` §4 执行，带 `structuralDiff` 开关灰度；⚠️ 碰数据兼容，验收清单 §7 必须全过 |
| 6 | 〔工程〕评审 #9：三套 Js 收敛为 1 核心 + 2 薄适配 | 2–3 天 | 净删 600+ 行，消灭 `"null"/"undefined"` 一类 bug 的土壤 |
| 7 | 〔算法〕摘要最优停止（§4.2） | ✅ 0 天 | **已实现**：`RpCacheCore.summaryTiming`（PR #78），枚举 t∈0..H 的折现总成本取 argmin |
| 7b | 〔算法〕分层预算精确背包 + 选择集粘滞（§4.3） | ⛔ 不做 | 用户已拍板关闭（§5.3 第 1 条：架构演进取代） |
| 7c | 〔算法〕预算系数能力表（替代 autoBudget 里 0.8/0.1/0.02/0.3/0.12 硬编码 + 200K 封顶） | 1 天 | 见 PLAN-ALGO-CACHE **P2**，开关默认 `legacy` 保持旧行为 |

### P2 · 2–4 周

| # | 事项 | 量 | 说明 |
|---|---|---|---|
| 8 | 〔算法〕op-log 真追加 + 脏标记（D6） | 1 天 | `Store.append` 已就绪；commit 从 O(会话) 降到 O(变更)，写放大消失 |
| 9 | 〔算法〕路由折扣 Beta + 受控探索（D2/D3） | 1–2 天 | 改进方案 §4.1 有完整算法与验收用例；**先埋点验证 D2/D3 真实发生率**再开（方案 Phase 0 原则），带开关默认关 |
| 10 | 〔工程〕ChatController 按职责拆 4 个（SendFlow/VersionOps/FilesOps/RpOps） | 1 天 | 纯移动 + 接口隔离 |
| 11 | 〔工程〕JsConvLog 按职责重构（Replay/Diff/Commit/Compact） | 2 天 | 917 行 god file；配 golden |
| 12 | 〔算法〕重试 Retry-After（Full Jitter 已有：`NetRetry.backoff` 带 ×(0.8+0.4·rnd)） | 0.25 天 | ✅ **已完成**（PR #98 P4）：`NetRetry.parseRetryAfterValue`/`delayFor` 封顶 60s，`Error.retryAfterMs` 透传到引擎。见 PLAN-ALGO-CACHE P4 |
| 13 | 〔工程〕detekt/ktlint + 3 个 Compose smoke test（空态/发送/流式） | 1 天 | 一次配置长期收益；instrumented 修 3 例后接 CI（仅 main + 手动） |

### P3 · 产品体验与长线（穿插做）

- 〔产品〕抽屉信息架构分组（内容/工具/账户）、触控目标 ≥44dp、图标按钮补 `contentDescription`、模型切换首次提示
- 〔算法〕D7 文件分块检索替代前缀截断——**升级为 B3 / v1.6.0**（`FileText.capText` 是纯前缀截断，问长文档后半段必错；改法见 PLAN-ALGO-CACHE **P3**，带 `files.chunkRetrieval` 开关）
- 〔算法〕Token RLS 校准（§4.4）、成本有效价格学习（§4.6）——需线上日志回放验证，长线
- 〔工程〕性能埋点（首帧 <1.2s、流式帧率、检索耗时）进 eval 门禁

### 明确不做（advisory §8，维持）

- 不扩张功能（会话内搜索/书签/分支）——功能已超出用户规模需要
- 不重写 Compose 层、不做视觉重构
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

| 线 | 完成度 | 已完成 | 欠账 |
|---|---|---|---|
| 改进方案（算法线 D1–D11） | ~40% | D11、D1、D9/D10 不适用部分、§5.2、§6 | D2/D3（1–2 天）、D8+D4/D5（3–4 天）、D6（1 天）、D7（2–3 天）、P2 长线（5–8 天） |
| 工程评审 11 项 | ~75% | 8 项 ✅ | 阶段 C/D（1.5 天）、三套 Js（2–3 天）、JsConvLog 拆分（2 天）、ChatController 细拆（1 天）、instrumented/detekt/smoke（1.5 天） |
| advisory 缓存专项 | ~62% | TokenEstimator 统一 + 图片公式**已接预算路径**（PR #98）+ `Retry-After` | 能力表参数化（P2，1 天）/ 文件分块检索（P3，2–3 天） |
| 产品体验（advisory §5） | 0% | — | 抽屉分组 / 触控目标 / 无障碍 / 提示（3–4 天，穿插） |

**总账**：核心承诺（方案 P0+P1 + 评审 P0/P1）约 **8–11 个工作日**；含长线 P2 约 4–5 周。

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
| B1 | 图片 token 接入预算路径 · `Retry-After` · 过期注释清理 | 0.75 天 | ✅ 已完成（PR #98，413 单测）→ v1.5.3 |
| B2 | 预算系数能力表（默认 legacy）· `plan()` 单轮估算 memo | 1.5 天 | v1.5.4 |
| B3 | D7 文件分块检索（替代前缀截断） | 2–3 天 | v1.6.0 |
| B4 | 路由埋点 → 观察两周 → 探索/衰减；校准冷启动先验 | 1–2 天 + 观察 | v1.6.x |

**更正三条过期判断**（此前口头/文档说法与代码不符，避免重复劳动）：
1. **D8 摘要最优停止已实现**（`RpCacheCore.kt:175 summaryTiming`，PR #78）——`RpCacheCore.kt:419` 那句「有意没搬 adaptiveSummary」是过期注释，B1 一并改掉。
2. **D2 Beta 折扣路由已实现**（`net/Router.kt:251 chooseUpstream`：`hitQuantile(st.a, st.b, z)` + stickyIdle + 切换成本折现）；真正缺的是**探索**与**统计衰减**（B4 先埋点）。
3. **D1 索引缓存已实现**（`RpMemoryCore.kt:437 buildIndex(list, prev)` 签名复用）；`GCache.adaptiveMinTokens` 与 `sessionId` 也已落地（advisory §10.3 已完成）。
