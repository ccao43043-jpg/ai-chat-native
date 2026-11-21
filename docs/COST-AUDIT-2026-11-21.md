# 成本审计 · 第一轮只读（2026-11-21）

> 基线：`origin/main @ 70be70b`（已 fetch 确认，本地一致）· 分支 `feat/weekend-cost-guard`
> 任务书：PLAN-COST-OPTIMIZATION-WEEKEND-2026-11-21.md §7.1（禁改文件）· 五轮节奏第一轮
> 标注：全部为**已证实**（直接读码核对）除非标「推测」；优先级按成本影响排序。

## 0. 一页结论

**两条旁路（askModel 家族 + 搜索改写）的 token 在现有代码中完全不可见**——usage 在解析层已经可用（`parseFullResponse` 已产出 `UsageDelta`、`UsageCodec` 全 wire 支持），但被 `filterIsInstance<Text>` 丢弃、入账层 `recordUsage` 只挂主对话 onUsage。**C1 的最小实现不是"新增解析"，是"别再丢"**。另有一个计划书未预见的发现：**记忆旁路的模型覆盖参数被直接丢弃**（AiChatApp.kt:106 第三参 `_`），`summary_model` 省钱档对记忆抽取完全无效。

## 1. 审计发现

### A · 旁路归因（C1 支撑）

| ID | 发现 | 证据 | 成本影响 | 优先级 |
|---|---|---|---|---|
| A1 | `parseFullResponse` 对非流式整包 JSON **已解析 usage** 并产出 `UsageDelta`（anthropic :79-80 / gemini :106 / openai :130），但旁路取文本时被 `filterIsInstance<StreamDelta.Text>()` 过滤丢弃 | net/StreamParser.kt:139-145 + AiChatApp.kt:153-155 | 归因缺失的根因 | **P0** |
| A2 | `requestText` 返回 (状态码, **完整 body**)，原始 usage 一直在调用方手里 | net/DefaultProviderClient.kt:179-185 | 零网络改动 | **P0** |
| A3 | `UsageCodec.normalizeUsage` 全 wire 支持（含 OpenRouter cost/cache_discount、gemini thoughts、DeepSeek cache hit/miss）；`UsageCodec.toUsage` 与流式路径同一套函数可直接复用 | net/UsageCodec.kt:17-86, 101-113 | 零新解析代码 | **P0** |
| A4 | `UsageEntry.from()` 硬编码 `type="chat"`（:90）；`of()` 默认 "chat"（:52）；生产代码**没有任何调用点写入非 chat 类型**（`"summary"` 键存在但 0 写入） | domain/cost/UsageEntry.kt:52,90 | 无法按任务归因 | **P0** |
| A5 | `recordUsage` 唯一来源是引擎主对话回调（AiChatApp.kt:167,185-192）；`askModel` 与 `rewriteQuery` 都不经过 onUsage → **旁路 token 完全不入账** | DefaultChatEngine.kt:326；AiChatApp.kt:191（data.usage.append 全仓唯一调用点） | 账单黑箱 | **P0** |
| A6 | reasoning token 无独立字段：UsageCodec 把 gemini `thoughtsTokenCount` 折进 completion；本地账本无 reasoning/variant（CSV 有） | net/UsageCodec.kt:36-44 | B2 测量只能靠 CSV | P2 |
| A7 | `backfill`/`collectFromMessages`（含 `"image"` type 写入）全仓 main **零调用**，死代码 | UsageStatsCore.kt:220-271 | 卫生 | P3 |

### B · 旁路配置（C2 支撑）

| ID | 发现 | 证据 | 成本影响 | 优先级 |
|---|---|---|---|---|
| B1 | `askModel` 固定 `maxTokens=2048`、温度 0、60s 超时——摘要/记忆/AutoChar 三类共享同一档 | AiChatApp.kt:149,151 | 摘要/记忆的出参上限过大 | **P0** |
| B2 | `askModel` 的 `ChatOptions` **未设 thinkingLevel**（默认 null ≙ auto=不发任何思考字段）——旁路是否产生 thinking 完全交给服务商默认（推测：Gemini 2.5/Qwen3 默认会思考）；T4 实测那条旁路 reasoning=438 印证 | AiChatApp.kt:149 + Contracts.kt:93-96；推测部分已标注 | reasoning 占 completion 46.6% 的主消耗点之一 | **P0** |
| B3 | **记忆旁路的模型覆盖参数被丢弃**：注入处 `{ conv, prompt, _ -> askModel(conv, prompt) }` 第三参 `_`——`summary_model` 省钱档对记忆抽取**完全无效**（计划书 W2 建议的 `aux_model` 之前，先修这个） | AiChatApp.kt:106 + RpMemoryPlugin.kt:41,147 | 记忆旁路每次都烧主模型 | **P0（新发现）** |
| B4 | 搜索改写是独立旁路：不走 askModel，自带 maxTokens=256/20s（合理），但同样无 usage 入账、固定主对话模型、无 thinking | DefaultChatEngine.kt:305-313, :200 | 可见性缺失 | P1 |
| B5 | 摘要 prompt 无上限增长（全部旧章节 + 本批 transcript + 长期记忆）；T4 实测 11.9k 单条 | RpChapterCore.kt:126,148 | 旁路输入随章节数线性涨 | P1 |
| B6 | AutoChar 走 askModel（质量敏感），与摘要共享 2048/60s 档 | AiChatApp.kt:198 + AutoCharService.kt:106 | 若 B1/B2 改档需独立配置，不能一刀切 | P1 |

### C · 历史媒体（C3 支撑）

| ID | 发现 | 证据 | 成本影响 | 优先级 |
|---|---|---|---|---|
| C3-1 | **每轮请求遍历全部历史消息并把所有 images 解析成完整 data URL 进请求**，无任何 N 轮窗口/年龄过滤（过滤项只有 bot/error/imageGen/pending 四个） | DefaultChatEngine.kt:172-175 | 多模态长会话的主成本源 | **P0/P1** |
| C3-2 | 全仓唯一 `takeLast` 窗口在生图与搜索改写文本——聊天主链路无图片窗口机制（缺无证实） | grep takeLast：ImageGen.kt:77 / Search.kt:167 | — | 佐证 |
| C3-3 | 图片入库时压缩为最长边 1600 q85（单图原始 200-500KB → base64 后 270-660KB）；5 图 10 轮会话第 11 轮 payload ≈ **1.3-3.3MB，且与第 6 轮完全相同**（字节推测、结构已证实） | Images.kt:71-79 + DefaultChatEngine.kt:175 + OpenAiCompat.kt:64-67 | 每轮白付 | **P0/P1** |
| C3-4 | 各 wire 把 images 内嵌进请求体（openai image_url / anthropic source.base64 / gemini inline_data），`filterMessages` 只按角色/非空过滤，历史全保留 | OpenAiCompat.kt:29-30,64-67；AnthropicGeminiCompat.kt:96-104,153-156 | — | 佐证 |
| C3-5 | files（附件全文）**每轮无条件展开**进历史 user 消息 content（`injectSearch` 只管搜索注入）；非分块旧路径按 80k 总预算截断 | FileContext.kt:61-77,19 | 附件长会话同样线性涨 | P1 |
| C3-6 | `chunkEnabled` 缺键时**缺省开**（FileContext.kt:49 return true），与 ChunkRetrieval.kt:14 注释「缺省关」**互相矛盾**（行为以实现为准） | 两处 | 卫生 + 用户误解风险 | P2 |
| C3-7 | RP 带图会话每轮同一图片被 3-5 处重复估算，`dims()` 无 memoization（每图每轮 Base64 解码 3-4 次）；超预算轮再 +2 | RpCacheCore.kt:194,498-499,580,611,652,686 + RpCachePlugin.kt:213-231；TokenEstimator.kt:76-108 | 纯 CPU/电量（不影响 payload） | P2（= PLAN-ALGO-CACHE #7c 的 memo 候选，有数据了） |
| C3-8 | 引擎已解析 data URL 后，RP 插件 L139 再过一遍 `blobs.dataUrl`（对 data: 原样返回，无二次磁盘读，但每轮两遍全量遍历） | RpCachePlugin.kt:139 + BlobStore.kt:75 | 微 | P3 |

### D · 附件检索预算（C4 支撑）

| ID | 发现 | 证据 | 成本影响 | 优先级 |
|---|---|---|---|---|
| D1 | `TOP_K=12` / `BUDGET_CHARS=30000` / 跨文件 `DEFAULT_TOTAL_MAX=80000` 全部固定常量，**无任何按问题长度/任务形态调整的机制**（全文件通读证实） | ChunkRetrieval.kt:18-22,56-72,120 | 短问题也吃满预算 | **P1** |
| D2 | 检索触发条件 = 长文档 + 非空问题；小文件全文原样（不走挑选） | ChunkRetrieval.kt:96-101 | — | 佐证 |
| D3 | `SOURCE_MAX=120000` 只管**入库抽取**上限；请求侧单文件预算仍是 30000——两个数字容易误读 | ChunkRetrieval.kt:22 + ChatScreen.kt:460 | 认知混淆 | P3 |
| D4 | 首块保底 + BM25F 相关性挑选 + 预算贪心已存在（C4 自适应只需改参数选择，不需要新算法） | ChunkRetrieval.kt:56-72 | C4 实现成本低 | 佐证 |

### E · B2 接线（C5 支撑，已在本周末 B2 设计中完整记录，此处只列锚点）

| ID | 发现 | 证据 |
|---|---|---|
| E1 | `autoBudget` 无生产调用方；plan() 读手填 settings | RpCacheCore.kt:591-594（详见 PLAN-ALGO-CACHE.md P2） |
| E2 | 死设置键四个 | RpState.kt:30-31 |
| E3 | deepseek 特判 + meta.context 覆盖 + CONTEXT_TABLE 19 条 | RpCacheCore.kt:389-394,326-345 |

## 2. Top 10 最小改法（第二轮输出）

| # | 改法 | 支撑 | 测试 | 回滚 |
|---|---|---|---|---|
| 1 | askModel 不再 `filterIsInstance<Text>`：捕获 `UsageDelta`，经 `UsageCodec.toUsage` 转 `Usage` | A1/A2/A3 | 成功只记一次 / 失败不记 | revert |
| 2 | `UsageEntry.of(..., type = task.usageType)`：新增 `BypassTask` 枚举（chapter_summary/memory_extract/search_rewrite/auto_char），TYPE_CN 同步补键 | A4 | type 断言 + 主对话仍 chat | revert |
| 3 | askModel 末尾（2xx 且解析出 usage）调 `recordUsage` 等价入口；失败/超时/无 usage 记 `usage_missing` 诊断不伪造 0 成本 | A5 | failedBypassDoesNotRecord / recordsExactlyOnce | revert |
| 4 | 修 B3：记忆注入处透传第三参（null 保留），`RpMemoryPlugin` 支持读 `summary_model` 或新增 `aux_model` | B3 | memoryUsesIndependentUsageType | revert |
| 5 | `BypassProfile(task, model, thinking, maxTokens, timeout)`：摘要 1200/off-low、记忆 800/off-low、搜索 256/off（改 writeQuery）、AutoChar 独立配置 | B1/B2/B6 | 每任务参数断言；不支持关闭思考的服务商降级 low | revert |
| 6 | thinking 显式下发：ChatOptions 设 thinkingLevel（复用 ProviderRules.thinkingPatch/geminiThinkingConfig——链路现成） | B2 | wire 断言 thinking 字段存在/缺省 | revert |
| 7 | 图片退役：`MediaRetirementPolicy`（只改请求 payload，保留最近 N 用户轮图片，更早的占位文本）；开关 `media.retireOldImages` 默认关 | C3-1/2/3/4 | 开关关=逐字节一致；最近轮完整 | 开关 + revert |
| 8 | 附件退役同法（下一小步，本轮只做图片） | C3-5 | — | — |
| 9 | 自适应预算：按问题长度/任务形态选 (topK, budget) 三档 profile，开关 `files.adaptiveBudget` 默认关 | D1/D4 | 短问题降档 / 长文保底 12/30k | 开关 + revert |
| 10 | B2 接线（设计已备好）：开关 legacy 默认 | E1-E3 | budgetProfileWiringLegacyByteExact | 开关 + revert |

## 3. Top 5（第三轮实现范围，按任务书降级顺序）

**C1（#1-#4 归因）→ C2（#5-#6 旁路配置）→ C3（#7 图片退役，单会话开关）→ W4（#9 自适应预算，时间够）→ W5（#10 B2 接线，opt-in）**。C1/C2 不可砍（任务书 §4）；W6 经济模式依赖 C1/C2 稳定，本轮不启。

## 4. 与既有工作的衔接

- EXPERIENCE-GUARD（#122）全部沿用不重复；B2 设计（PLAN-ALGO-CACHE P2）= W5 的直接输入；tools/billing = W8 的现成工具；PLAN-ALGO-CACHE §1#14 的"重复计价"发现本轮升级为 C3-7（带数据了，memo 候选成立）。
- 新增证据修正计划书一处：W2 表里「MEMORY_EXTRACT 用 aux_model → summary_model」之前，先修 B3 的参数丢弃——否则 aux_model 也传不进去。
