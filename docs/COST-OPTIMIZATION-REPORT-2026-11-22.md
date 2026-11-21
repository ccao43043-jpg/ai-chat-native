# 成本优化周末报告（PLAN-COST-OPTIMIZATION-WEEKEND-2026-11-21）

> 基线：开工 `main @ 70be70b`（fetch 确认）→ 分支 `feat/weekend-cost-guard`
> 收口：分支 HEAD = 报告 commit（推送后以 PR 为准）
> 四维口径：**代码完成 / 自动化通过 / 已发布 / 真机验收** 分开记；合并不等于发布，CI 绿不等于真机通过。

## 1. 结论

- **主对话缓存**：零改动（99.7% 基线不动，请求组装主路径未碰）——这是刻意保守，不是遗漏。
- **旁路成本**：C1 归因 + C2 分档已落地。旁路 usage 从「解析后被丢弃」变为独立入账（type = 任务类型）；摘要/记忆/AutoChar/搜索改写各自有出参上限与显式思考档；**修复了审计新发现的 B3**（记忆旁路丢弃模型覆盖参数，`summary_model` 省钱档此前对记忆无效）。
- **图片/附件**：C3 图片请求侧退役（开关默认关）；C4 自适应检索预算（开关默认关）。
- **B2**：从「设计完成」推进到「**接线完成**」——`rpcache.budgetProfile = legacy|auto`（默认 legacy 逐字节一致），auto 走能力表，gemini 1M 窗口不再被 200k 封顶。
- **体验**：EXPERIENCE-GUARD 沿用 PR #122，未重复实现。

## 2. 改动清单（6 commits）

| commit | 内容 | 四维状态 |
|---|---|---|
| `docs: cost audit round 1` | 30 条发现全带行号（A 归因/B 配置/C 媒体/D 检索/E B2），含**新发现 B3** | 只读 |
| `feat(cost): bypass task attribution and per-task profiles (c1+c2)` | BypassTask 档位、UsageDelta 捕获入账、B3 修复、TYPE_CN、rewriteQuery 关思考 | 代码 ✅ / 自动化 ✅（8 例）/ 未发布 / 真机待验 |
| `feat(cost): adaptive attachment retrieval budget (c4/w4)` | 三档 profile（6/12k、10/24k、12/30k），开关 `files.adaptiveBudget` 默认关 | 代码 ✅ / 自动化 ✅（5 例）/ 未发布 / 真机待验 |
| `feat(cost): media retirement (c3)` | 保留最近 2 个用户轮图片，更早的请求侧退役 + 占位说明；开关 `media.retireOldImages` 默认关 | 代码 ✅ / 自动化 ✅（6 例）/ 未发布 / 真机待验 |
| `feat(cost): b2 budget profile wiring (c5/w5)` | BudgetProfile + profileOf + plan() 接线 + RpInspect 接管提示；开关 `rpcache.budgetProfile` 默认 legacy | 代码 ✅ / 自动化 ✅（5 例）/ 未发布 / 真机待验 |
| `docs: cost optimization report` | 本文 | — |

## 3. 用户能感知什么（与不能承诺的）

- **能**：用量页将按「对话/章节摘要/记忆抽取/搜索改写/角色生成」分账（C1）；摘要/记忆/搜索改写显著变快（低思考+小上限，C2）；长多模态会话开启退役后每轮请求轻 1.3–3.3MB 量级（C3）；大窗口模型 opt-in 后真正用满上下文（C5）。
- **不能承诺**：所有服务商都支持完全关思考（不支持时 wire 层降级最低档）；图片退役后追问旧图需重新附加（UI 占位已明示）；C4 检索不保证永不漏尾部答案（长文 profile 保底全量档）。

## 4. 测试证据

```
gradlew --no-daemon :app:testDebugUnitTest          → 693 例 / 0 失败（基线 669 + 新增 24）
gradlew --no-daemon :app:assembleDebug              → BUILD SUCCESSFUL
gradlew --no-daemon :app:compileDebugAndroidTestKotlin → BUILD SUCCESSFUL
git diff --check                                    → 干净
```

新增 24 例分布：归因与分档 8（BypassTaskGuardTest）、图片退役 6（MediaRetireTest）、自适应预算 5（ChunkRetrievalAdaptiveTest）、B2 接线 5（RpCacheCoreBudgetWiringTest）。审计发现的测试修正：`recordIgnoresEntriesWithoutPrompt` 需手拼 JsonNull 条目（makeEntry 永远产出 prompt=0，守卫针对手拼路径）、round1k 是四舍五入不是向下取整。

## 5. 真机与账单（未执行，如实记录）

- `connectedDebugAndroidTest`：本机无设备，未执行；替代步骤 = 装包后按任务书 §6.2/6.3 清单手测。
- **T5 四指标**：无数据——依赖装包与 ≥1 天样本，周日晚导出 CSV 后用 `tools/billing/` 回填 ASSESS-2026-11-21 §2。旁路归因的验证方法：触发一次摘要/记忆抽取 → `usage-stats.jsonl` 出现 `chapter_summary` / `memory_extract` 类型与实际模型。
- 真机 smoke 顺序：C2 摘要速度 → C3 旧图追问提示 → C5 auto 档位提示（需手动开 `rpcache.budgetProfile=auto`）。

## 6. 未做项（与原因）

| 项 | 原因 |
|---|---|
| C6 经济模式 UI | 依赖 C1/C2 稳定 + 真机数据，任务书明确「时间不够则下周」 |
| rewriteQuery 的 usage 入账 | 引擎侧无 Message/挂点，需要回调签名变更；已设 thinking=off 控成本，归因留下周 |
| 附件退役（C3 后半） | 任务书明确本轮只做图片，不同时改两套行为 |
| 死设置键删除（budget_mode 等） | normalizeState 形状被 MigrationGoldenTest 覆盖，删除需另跑迁移核对，留 B2 收尾 |
| LoreCore 世界书预算同源 | 需要把 provider/model 上下文传进三处只收 st 的函数，plumbing 超出本轮，记欠账 |
| meta.context 请求侧覆盖 | plan 已留 modelMeta 参数位，本轮传 null（CONTEXT_TABLE 已覆盖 gemini/claude/gpt/deepseek 主力） |

## 7. 风险与回退

| 风险 | 回退 |
|---|---|
| C2 关思考导致摘要质量下降 | 开关粒度 = 单会话 `summary_model` 清空即回退模型；thinking 降级链 off→low 由 wire 层兜底 |
| C3 用户追问旧图 | UI 占位明示「请重新附加」；`media.retireOldImages` 一键关回逐字节旧行为 |
| C4 漏尾部答案 | 长文/总结意图强制全量档；开关默认关 |
| C5 auto 抬高单轮成本 | opt-in（默认 legacy）；RP 检视有接管提示；revert 单 commit |
| 旁路 usage 双记 | 仅 2xx 且有 UsageDelta 时记一次；失败/超时/空响应不记账（有测试锁定） |
