# AI-Chat 原生版：整体评估与优化建议

> 独立判断，不依赖某一时刻的本地文件状态。对应代码基线：Kotlin + Jetpack Compose 原生版（约 136 文件 / 25,392 行生产代码，49 个单测文件 / 6,589 行）。
> 与 `docs/REVIEW-2026-11-16.md`（具体问题清单）、`docs/ROADMAP.md`（执行进度）互为补充：本文回答"往哪走"，那两份回答"改了什么"。

---

## 1. 成熟度判断

这是一个**技术完成度远高于产品完成度**的项目。

- 协议层、流式、持久化（oplog + snapshot + 幂等迁移）、成本核算、缓存分层、RP 记忆检索，都是成熟实现；
- 产品层（界面、引导、错误反馈、可发现性）仍处在"能跑通"阶段；
- 测试呈明显的哑铃形：逻辑层有 49 个测试文件覆盖，**UI / 集成层几乎为零**（Compose 界面、迁移、备份全靠手测）。

**结论**：底盘是"工厂级"，界面是"实验室级"。下一阶段重心应从"补能力"转向"降成本 + 提体验"。

---

## 2. 架构评估

### 2.1 真正的结构性问题：三套 JS 语义层

- `data/json/Js`、`net/JsCompat`、`plugins/js/Js` 各自实现了一套 JS 语义（`stringify`、`localeCompare`、正则行为等）；
- 这是"逐字节对齐旧 WebView"留下的成本，历史使命已完成；
- 现状：任何跨层改动都要在 3 处确认语义一致，是最大的长期利息。

**建议**：收敛为 1 个 `Js` 核心 + 2 个薄适配层（命名空间转发，不重复实现）。预计净删 600+ 行，是所有后续改动的成本基数。

### 2.2 第二问题：god file 与职责耦合

- `ChatController`（约 733 行）混合了发送、版本、文件、搜索、RP 五种职责；
- `JsConvLog`（约 917 行）把 replay / diff / commit / 压缩塞进一个 class；
- 表现：改一处要读整文件，测试只能整类测。

**建议**：按"一个文件一个变更理由"拆：
- `ChatController` → `SendFlow` / `VersionOps` / `FilesOps` / `RpOps`（各依赖接口，不共享可变状态）；
- `JsConvLog` → `Replay` / `Diff` / `Commit` / `Compact`（共享 `Session` 数据类）。

### 2.3 第三问题：持久化仍被"字节对齐"绑架

- `KeyOrder.align` + `nullRemovedMessageKeys` + 固定字段序，三层补丁只为让 `JSON.stringify` 判等稳定；
- diff 判等切到 `semanticEqual` 是正确的第一步，但 `KeyOrder` 仍在；
- 这套机制已经限制了两轮字段改动（加 `Message.model` 前必须先证明 `null` 不落键）。

**建议**：按已验证的四阶段推进，最终目标是"结构相等即可"，让 data class 成为唯一真源。

---

## 3. 工程质量评估

| 维度 | 现状 | 判断 |
|---|---|---|
| 单测 | 49 文件 / 6,589 行，覆盖编解码、路由、成本、检索、RP、迁移 | 良好 |
| 集成 / UI 测试 | 无 instrumented，Compose 全靠手测 | **短板** |
| CI | 无（依赖本地执行） | **短板** |
| 静态检查 | 未见 detekt / ktlint 配置 | 缺失 |
| 可观测性 | 插件错误面板已补；缺性能埋点、崩溃上报 | 部分 |
| 代码规范 | `Space` / `Radius` token 已在设置页落地，聊天页仍有硬编码 | 不一致 |

**建议优先级**：
1. CI 至少跑 `testDebugUnitTest`（约半天，立刻减少回归风险）；
2. 3 个 Compose smoke test（空态 / 发送 / 流式），用 `createComposeRule`；
3. detekt + ktlint（一次配置，长期收益）。

---

## 4. 性能评估（按影响排序）

| 问题 | 影响 | 建议 |
|---|---|---|
| 流式输出触发整屏重组（`streams` 挂在根部 StateFlow） | 长会话帧率下降 | `streams` 拆独立 `StateFlow`，只有 live 消息订阅 |
| `commit()` 整文件重写 + 两次深拷贝 | 每轮写放大 | 真追加（`Store.append` 已就绪）、脏标记、结构化 diff |
| 文件对话前缀截断 | 长文档丢信息 | 分块检索（复用小节索引），只注入相关片段 |
| 检索 MMR 每次重算 3-gram | 知识库注入变慢 | 增量维护候选向量 |

BM25 索引每轮重建已修（`sig` + LRU 缓存），是正确方向。

**建议**：先做流式状态拆分，再做文件分块检索——两者都是用户可感知的。

---

## 5. UI / 产品评估

### 做得好的

- 空态引导（建议 pills / 三步卡片）有明确的"下一步动作"；
- 消息操作行区分用户 / 助手，符合对话习惯；
- 插件错误已可见（顶栏角标 + 面板），从"静默失败"走到"可诊断"。

### 真正的体验债

1. **设置入口过多且层级扁平**：抽屉里 8 个入口混排（设置 / 插件 / 生图 / 助手 / 世界 / 模板 / 用量 / 余额），认知负担高。
   → 建议分「内容」「工具」「账户」三组，设置单独置顶。
2. **模型切换的可发现性**：默认态只是小字，用户不知道能点。
   → 建议首次使用时轻提示一次。
3. **长按 / 手势缺乏一致性**：会话行长按菜单、消息长按复制、附件长按无操作。
   → 建议建立"长按 = 上下文菜单"的统一约定。
4. **触控目标偏小**：消息操作行约 28dp、顶栏按钮 40dp。
   → 视觉不变，扩大点击区（附件删除按钮已示范）。
5. **无障碍缺失**：图片已补描述，图标按钮、状态徽章仍多为 `null`。
   → 批量补 `contentDescription`，成本低。

---

## 6. 风险清单

| 风险 | 严重度 | 处置 |
|---|---|---|
| 改动累积但未编译验证 | 高 | 每轮结束本地跑一次 `testDebugUnitTest`，通过再发版 |
| diff 判等切换影响 oplog 生成 | 高 | golden 逐条对比，确认 ops 不变再做下一阶段 |
| 无 CI | 中 | 半天接入 |
| 三套 `Js` 语义漂移 | 中 | 收敛为 1 核心 + 2 适配 |
| `JsConvLog` 917 行 | 中 | 按职责重构，配 golden |

---

## 7. 优化建议（按 ROI 排序）

### 立即（1 天内）

1. 本地验证并发布补丁版本；
2. 把 `testDebugUnitTest` 接进 CI；
3. 完成判等切换后的 golden 对比。

### 短期（1 周）

4. 流式状态拆分（帧率，用户可感知）；
5. 抽 3 个 Compose smoke test；
6. 抽屉信息架构分组 + 触控目标统一。

### 中期（2–3 周）

7. 合并三套 `Js`（净删 600+ 行）；
8. `ChatController` 按职责拆 4 个文件；
9. 文件对话分块检索。

### 长期（1 个月+）

10. `JsConvLog` 重构 + 去 `KeyOrder`；
11. detekt / ktlint + 覆盖率门槛；
12. 性能埋点（首帧、流式帧率、检索耗时）。

---

## 8. 不建议做的事

- **不建议继续扩功能**（搜索 / 书签 / 分支）：当前功能已超出用户规模需要，边际收益低；
- **不建议重写 Compose 层**：UI 结构没问题，问题在细节与一致性；
- **不建议在无 CI 的情况下继续大重构**：风险会累积到无法定位；
- **不建议长期保留 `KeyOrder`**：它已经限制了两轮字段改动，且每次都要额外验证。

---

## 9. 验收指标（建议纳入 CI）

| 指标 | 目标 |
|---|---|
| `testDebugUnitTest` | 全绿，且单测数不减少 |
| golden 回归 | ops 逐条一致，oplog 体积不增 |
| 流式帧率 | 长会话（100+ 条）滚动无明显掉帧 |
| 首帧 | 冷启动到可输入 < 1.2s |
| 编译告警 | 新增未使用 import / 未使用变量清零 |

---

## 10. 缓存优化算法专项（2026-11-16）

审查范围：`plugins/rp/RpCacheCore.kt`（594 行）、`plugins/rp/RpCachePlugin.kt`（430）、`plugins/cache/GCache.kt`（620）、`MmCache.kt`（71）、`CacheCommonImpl.kt`（76）。

### 10.1 整体判断

算法主干是正确且成熟的：静态前缀分层（static → memory → summary → lore → delta → state）、动态内容后置、预算合计、二分裁剪窗口、临时裁剪不永久推进、校准按 provider/model/modality 隔离。这套结构没有方向性问题。

问题集中在三处：**尺子不统一、多模态估算粗糙、两处 JS 版优化刻意没搬**。

### 10.2 三个实质问题

**① 两套 token 估算尺子（影响最大）**

| 实现 | 中文系数 | 英文 | 数字 |
|---|---|---|---|
| `RpCacheCore.estimateTokens` | 0.6 / 字 | 0.3 / 字符 | 0.5 |
| `CacheCommonImpl.estTokens` | 1.0 / 字 | 0.25 / 字符 | 0.25 |

同一段中文，RP 插件与 cache 插件估出的值差约 40%。后果：预算判断、摘要触发、命中率诊断的口径不一致。

→ 建议：抽 `TokenEstimator`，按 model 选系数（DeepSeek 偏 0.6，Gemini / OpenAI 偏 1.0），图片公式也放进来。

**② 图片 token 固定 1000**

`estimateItems` 里 `imgN * 1000` 是唯一口径。实际差异很大：Gemini 约 258 / 图、Anthropic 约 (w×h)/750、OpenAI 按 512px tile。

→ 建议：按 provider 选公式，尺寸从 data URL / JPEG 头解析一次后缓存进 `images` 元数据。

**③ adaptiveSummary 与分层预算没搬**

`RpCacheCore` 注释明确说"有意没搬 `applyLagrangeBudget` 与 `adaptiveSummary`"。结果：

- 摘要时点只有 `budget` / `trigger` 两档，没有"现在摘 vs 推迟 H 轮"的折现比较；
- 预算只有"合计 ≤ limit"，没有分层分配。

→ 建议：补 `adaptiveSummary`（H=12、γ=0.9、质量门 0.6），但**不要照搬 JS 版的 D4/D5**（无粘滞、密度贪心），直接用精确背包 + 选择集粘滞。

### 10.3 算法细节上的可优化点

| 位置 | 现状 | 建议 |
|---|---|---|
| `plan()` | 对 static/memory/summary/history/delta/state 分别估算，`estimateItems` 多次调用 | 一次遍历估算 + 按层缓存（内容 hash → tokens） |
| `computeTrimWindow` | `itemTokens` 每条重算，再建前缀和 | 缓存每条 token，前缀和复用 |
| `autoBudget` | 0.8 / 0.1 / 0.02 系数硬编码 | 收进按 provider 的能力表，便于新模型调参 |
| `calibrate` | 按 provider/model/modality 隔离，键过细 | model 无样本时回退 provider 级，冷启动更快 |
| `GCache.adaptiveMinTokens` | 样本 ≥4 就调整门槛 | 提到 ≥8（与 `ROLL_N` 一致），减少抖动 |
| Hybrid TTL | 统一 240s | pro / lite 分档（隐式缓存 TTL 实际不同） |
| `sessionId` | GCache 用 `aichat-` 前缀，MmCache 用标准 UUID | 统一 `SessionIds.of(convId, adapter)`，避免误用 |
| 诊断 | 只有 good / ok / low 三档 | 增加"连续 N 次低于前值一半"的骤降告警 |

### 10.4 不建议改的

- 静态分层的顺序与"动态后置"原则——这是缓存命中的根，动了收益为负；
- `applyTempTrim` 的"只改本轮、不永久推进"——这是防剧情丢失的关键设计；
- `calibrate` 的 EWMA 0.7/0.3 + 夹 0.5–2.5——参数合理，无需调整。

### 10.5 优先级

1. 统一 `TokenEstimator`（含图片公式）——直接影响预算与摘要判断；
2. 补 `adaptiveSummary` + 分层预算（精确背包 + 粘滞）；
3. 估算结果缓存与 `plan` 去重；
4. 诊断骤降告警、sessionId 统一、能力表参数化。

---

## 11. 与《AI-Chat 改进方案与关键算法》的对接（2026-11-16）

> 对象：`AI-Chat-改进方案与关键算法.md`（616 行，写于 JS 版 v0.29.0 时期，针对 `www/` 前端与 `rpcache.js` 等）。
> 本项目是它的 Kotlin 原生重写版。下面把文档里的 11 个算法与 D1–D11 逐项映射到原生版现状。

### 11.1 逐项映射

| 文档章节 | 算法 | 原生版对应 | 现状 |
|---|---|---|---|
| §4.1（D2/D3） | 缓存亲和路由：折扣 Beta + 受控探索 | `net/Router.kt`（prior 6/4、minObs 5、dwell 8、switchMargin 0.97、`ripe` 过滤 `cold`、observe 只累加） | ❌ 未实现（与 JS 版同构：无衰减、无探索） |
| §4.2（D8） | 摘要时点最优停止（now vs late 折现） | `RpCacheCore.plan()`（只有 `budget` / `trigger` 两档） | ❌ 未实现（代码注释明确"有意没搬"） |
| §4.3（D4/D5） | 层间预算精确背包 + 粘滞 | 无 `applyLagrangeBudget` | ❌ 未实现 |
| §4.4 | Token 分类特征 RLS | `RpCacheCore.calibrate()`（EWMA 0.7/0.3，单系数） | ❌ 未实现；且存在"两套尺子不一致"（见 §10.2①） |
| §4.5（D1/D11） | MMR 增量、seed、归一化 | `Knowledge` 已修 **D11**（置顶分不参与 `maxS`，`mmr` 传 seed）；`RetrievalCore.mmr` 支持 seed；`RpMemoryCore` 内联 MMR 无 seed | ◐ **部分**：D11 ✅、D1 ❌（每轮全量算 3-gram） |
| §4.6 | 成本"有效价格学习" | `CostCoreJs.reconcile()`（单标量 scale + biasEwma，ratio 无 clamp） | ❌ 未实现 |
| §4.7 | affect：Kalman + CUSUM | `Affect.kt`（Q 0.04 / R 0.30 / CUSUM 0.15/0.45/D4 / 护栏 / 多角色混合） | ✅ **已实现** |
| §4.8（D6） | op-log 真追加 + 脏标记 | `JsConvLog.commit()`（整文件重写 + 两次深拷贝） | ❌ 未实现（`Store.append` 已就绪但未用于 commit） |
| §4.9（D10） | 流式渲染 / 虚拟滚动 | Compose `LazyColumn`（`reverseLayout` + `key` + `contentType`） | ➖ **不适用**；但流式整屏重组仍在（对应 5.1） |
| §4.10（D9） | Android 流式桥埋点 | 原生网络层，已无 JS 桥 | ➖ **不适用** |
| §4.11（D9） | 网络重试：Retry-After / Full Jitter | `NetRetry`（MAX_RETRIES 2、±20%、无 Retry-After） | ❌ 未实现 |
| §5.1 | 拆 `app.js`（5105 行） | `ChatScreen` 523 / `ChatController` 733 | ◐ **部分**（已拆 EmptyHero / ChatMenus / ChatUiState） |
| §5.2 | 类型与契约 | Kotlin data class + Codec | ✅ **已满足** |
| §5.3（D10） | 评测与 CI | 49 单测 + golden | ◐ **部分**（无 CI） |
| §6 | 与 Kotlin 原生版融合 | 本项目即该原生版 | ✅ **已实现** |
| D7 | 文件分块检索 | `FileText.capText` 前缀截断 | ❌ 未实现 |

### 11.2 原生版"反向超越"文档的地方

文档写于 JS 版时期，原生版在移植中做了几处文档没要求、或要求了但 JS 版没做的修复：

- **D11 已修**：`Knowledge.kt:294-303` 明确注释"偏离 JS 行为"——置顶条目强制先入、取满分 1，且不参与归一化，普通命中分不再被压到 ~1e-5；
- **`mmr` 增加 seed 参数**：置顶先入、普通条目仍要避开与置顶重复的内容（文档 §4.5.1 的建议，原生版已落地）；
- **`Message.model` / `estimateTokens` 收口 / BM25 索引缓存**：对应评审 2.3 / 3.2 / 3.3，均为原生版新增。

### 11.3 仍未实现（按文档优先级）

1. 路由折扣 Beta + 受控探索（§4.1，D2/D3）
2. 摘要最优停止（§4.2，D8）
3. 层间预算背包 + 粘滞（§4.3，D4/D5）
4. Token RLS / 统一估算（§4.4 + §10.2①）
5. 成本有效价格学习（§4.6）
6. op-log 真追加 + 脏标记（§4.8，D6）
7. 重试 Retry-After / Full Jitter（§4.11，D9）
8. 文件分块检索（D7）
9. MMR 增量（D1）

### 11.4 建议的落地顺序（与 §10 合并）

| 顺序 | 事项 | 理由 |
|---|---|---|
| 1 | 统一 `TokenEstimator`（§10.2①） | 它是 §4.2 / §4.3 / §4.4 的共同前提 |
| 2 | 摘要最优停止 + 分层预算（§4.2 / §4.3） | 直接决定长对话的 token 成本 |
| 3 | op-log 真追加（§4.8） | 写放大是每轮固定成本 |
| 4 | 路由折扣 + 探索（§4.1） | 需要线上样本，放在有埋点后 |
| 5 | 重试 Retry-After（§4.11） | 低风险、可独立做 |
| 6 | 文件分块（D7）、MMR 增量（D1） | 取决于用户是否常用长文档 / 大知识库 |

### 11.5 一句话

> 这份方案对原生版的适用性是"**大部分未做、少数已反向超越**"：算法建议仍然有效，但要注意它针对的是 JS 版的结构；原生版已修的 D11、seed、`Message.model`、索引缓存，不要重复实施。

---

## 一句话总结

> 这个项目的下一步不是"加能力"，而是**降低每次改动的单位成本**：先 CI，再合并 Js 层，然后拆控制器，最后优化流式与检索。UI 上只做"可发现性 + 一致性"，不追求视觉重构。