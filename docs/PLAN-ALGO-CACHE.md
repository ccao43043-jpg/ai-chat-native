# 算法与缓存优化 · 详细改进计划

> **状态**：✅ 活跃（算法/缓存唯一细化文档；**优先级仍以 MASTER-PLAN 为准**）
> 版本：2026-11-17 首版
> 基线：`main @ a25e309`（v1.5.2）
> 定位：本文是 [`MASTER-PLAN.md`](MASTER-PLAN.md) §2 中「〔算法〕」与「advisory 缓存专项」两条线的**细化执行计划**；
> 优先级仍以 MASTER-PLAN 为准，本文只回答「具体怎么改、改哪里、怎么验」。
> 关联：[`OPTIMIZATION-ADVISORY.md`](OPTIMIZATION-ADVISORY.md) §10/§11、[`REVIEW-2026-11-16.md`](REVIEW-2026-11-16.md)。

---

## 0. 一页结论

**这条线不是「缺功能」，是「口径不统一 + 决策点拍死」。**

已核实：过去几轮已经把大部分算法项做完了（统一 token 尺子、摘要最优停止、Beta 路由、索引缓存、自适应 TTL），
**真正剩下的只有 5 件实事 + 3 件卫生工作**，合计约 **5–7 个工作日**：

| 批次 | 内容 | 工时 | 出口 |
|---|---|---|---|
| **B1** | 图片按服务商计价接入预算路径、`Retry-After`、死代码与过期注释清理 | 0.75 天 | 可发 v1.5.3（纯改善，无行为突变） |
| **B2** | 预算系数能力表参数化、`plan()` 单轮估算 memo | 1.5 天 | 可发 v1.5.4（带开关，默认保守） |
| **B3** | **D7 文件分块检索**（替代前缀截断） | 2–3 天 | 可发 v1.6.0（用户可见，收益最大） |
| **B4** | 路由埋点 → 数据两周 → 探索/衰减；校准冷启动先验 | 1–2 天 + 观察 | 视数据决定 |

**本计划相对此前口头判断的更正**（重要，避免重复劳动）：
D8「摘要最优停止」**已实现**（`RpCacheCore.summaryTiming`，PR #78）；D2「Beta 折扣路由」**已实现**（`Router.chooseUpstream`）；
D1「索引增量」**已实现**（`buildIndex(list, prev)` 带签名缓存）；`GCache.adaptiveMinTokens` 与 `sessionId` **已实现**（PR #77/#84）。
`RpCacheCore.kt:419-421` 那句「有意没搬 adaptiveSummary」是**过期注释**，必须改掉，否则下一个人会照着它重复做。

---

## 1. 现状核实（逐项带证据）

| # | 项 | 结论 | 证据 |
|---|---|---|---|
| 1 | 统一文本 token 尺子 | ✅ 已完成 | `plugins/rp/RpCacheCore.kt:161` `fun estimateTokens(text: Any?): Int = TokenEstimator.text(Js.str(text))`；`plugins/TokenEstimator.kt:26` 逐码点分档（汉字 0.6 / 英文 0.3 / 数字 0.5 / 空白 0.1 / 其余 1.5） |
| 2 | 图片按服务商计价 | ⚠️ **算法有、预算路径没接** | `TokenEstimator.kt:47-56` `image(provider, dataUrl)`：gemini 258 / anthropic `max(156,w*h/750)` / openai `85+170*tiles` / 其它 1000；但 `RpCacheCore.kt:164` `fun estimateItems(items: List<Any?>): Int = TokenEstimator.messages(items)` **不传 provider** → 走 `TokenEstimator.kt:72` `FALLBACK = 1000` |
| 3 | 摘要最优停止（D8） | ✅ 已完成 | `RpCacheCore.kt:175-193` `fun summaryTiming(U, g, saved, fixed, limit, h=12, gamma=0.9): Int` 枚举 `t∈0..H` 的折现总成本取 argmin |
| 4 | 分层预算 `applyLagrangeBudget` | ⛔ 用户已决定不做 | `MASTER-PLAN.md` §5.3 第 1 条（架构演进取代）；注释仍留在 `RpCacheCore.kt:419`，需登记为「明确不做」 |
| 5 | 预算系数 | ⚠️ 硬编码 | `RpCacheCore.kt:365-372`：`usable = min(window, tier, 200_000)`、`maxCtx = usable*0.8`、`outputReserve = maxCtx*0.1 [4k,16k]`、`safetyMargin = maxCtx*0.02 [1k,4k]`、`triggerTokens = maxCtx*0.3`、`chapterCap = maxCtx*0.12 [4k,24k]` |
| 6 | 能力表参数化 | ❌ 未做 | 同上，`Cap` 只提供 `vendor/window/tier`，无「按 vendor 的预算档」 |
| 7 | MMR 检索 | ✅ 已完成 | `plugins/rp/RpMemoryCore.kt:581` `val lam = o.mmrLambda`（默认 0.7）、`:535 selectRelevant`、`:395 terms = RetrievalCore.grams`（中文 bigram） |
| 8 | BM25 索引缓存 | ✅ 已完成 | `RpMemoryCore.kt:437 fun buildIndex(list, prev: Index? = null)` + `:452 buildIndexUncached`（签名命中复用） |
| 9 | 路由 Beta 折扣 | ✅ 已完成 | `net/Router.kt:251 chooseUpstream`：`:266 hitQuantile(st.a, st.b, zPessimistic)`、`:286 stickyIdleMs`、`:291-296` 切换成本折现 |
| 10 | 路由探索 / 统计衰减 | ❌ 未做 | `chooseUpstream` 全程无 epsilon/UCB，`UpstreamStats.a/b` 无时间衰减（`lastAt` 只用于 idle 判定） |
| 11 | 重试退避 | ⚠️ 有 jitter、无 `Retry-After` | `net/NetRetry.kt:35 backoff`（`base·2^attempt`，429 起步 2s，上限 15s，`×(0.8+0.4·rnd)`）；`:43` 重试条件；全仓 `retryAfter` 零命中 |
| 12 | 文件上下文 | ❌ 纯前缀截断（D7） | `data/files/FileText.kt:167 fun capText(text, max)` = `text.substring(0, max)`；`:174-181 buildFileContext` 按 `remain` 依次截断 |
| 13 | 死代码 | ⚠️ 存在 | `RpCacheCore.kt:437-440 fun itemTokens(m)` 已无生产调用（估算统一走 `estimateItems`） |
| 14 | 单轮重复估算 | ⚠️ 存在 | `plan()` 内 6 处 `estimateItems(...)`：`:402 hist`、`:405 input`、`:500 hist`、`:503 input`、`:531 unsummarized`、`:598 hist`，每次 O(全部字数) 逐码点扫描 |
| 15 | 自适应 TTL / 阈值 / sessionId | ✅ 已完成 | `plugins/cache/GCache.kt:146 adaptiveMinTokens`、`:219 TTL_EFF_DEFAULT = 240`、`:16` 默认 `sessionId = true` |
| 16 | 成本有效价格学习（§4.6） | ⚠️ 仅单标量 | `domain/cost/CostCoreJs.kt` `reconcile`（scale + biasEwma），无按模型的价差回归 |

---

## 2. 待做项详解

### P1 · 图片 token 接入预算路径（B1，0.5 天）

**问题**：同一句话「这轮会不会超预算」，文本用了精确尺子，图片却仍是 1000/张。
一张 1024×1024 图：Gemini 真实 ≈258（高估 3.9×）→ 提前裁剪，白丢历史、命中率下降；
Anthropic 真实 ≈1398（低估 40%）→ 该裁不裁，撞窗口边界。

**改法**（三处签名 + 调用点透传，全部带默认值以保 golden）：

```kotlin
// plugins/rp/RpCacheCore.kt
fun estimateItems(items: List<Any?>, provider: String? = null): Int = TokenEstimator.messages(items, provider)

fun plan(
    st0: JObj, baseSystem: String, items: List<JObj>,
    calibOverride: Double? = null, meta: JObj? = null,
    provider: String? = null,          // 新增：presetId，用于图片计价
): Plan
```

```kotlin
// plugins/rp/RpCachePlugin.kt
val p0 = RpCacheCore.plan(stFired, base, itemsLore, meta = calKey, provider = calKey-provider)
```

- `TokenEstimator.messages(items, provider)` 已支持 provider（`TokenEstimator.kt:62-70`），无需改估算器。
- `provider` 传 `ctx.provider.presetId.ifEmpty { ctx.provider.id }`，与 `calibKey` 同源（`RpCachePlugin.kt:93`）。
- **golden 安全**：`RpCacheCoreTest` 调 `plan`/`estimateItems` 不传 provider → 默认 null → 1000/张，字节不变。
- 已知局限：`TokenEstimator.image` 对「未知 vendor」仍回落 1000，属预期。

**验收**：新增 `RpCacheCoreTest.imageTokensUseProviderFormula`——同样一条带 1 张 1024×1024 PNG 的消息，
`estimateItems(listOf(m), "gemini") == 258 + text + 4`、`(…, "anthropic") == 1398 + text + 4`、`(…, null) == 1000 + text + 4`。
真机口径：带图对话的 `rpcache` trace 里 `tokens.raw_total` 与响应 `prompt_tokens` 偏差应从 >30% 收敛到 <15%。

---

### P2 · 预算系数能力表（B2，1 天）

**问题**：`autoBudget` 的 0.8/0.1/0.02/0.3/0.12 与 `min(…, 200_000)` 对所有 vendor 一刀切。
DeepSeek 64K 窗口用 0.8 合理；Gemini 1M 窗口被 200K 封顶等于自废 80%。

**改法**：给 `RpCacheCore` 加一张**只描述差异**的表，默认行与现有常量逐位相同：

```kotlin
data class BudgetProfile(
    val ctxRatio: Double = 0.8, val hardCap: Int = 200_000,
    val outRatio: Double = 0.1, val outMin: Int = 4_000, val outMax: Int = 16_000,
    val marginRatio: Double = 0.02, val marginMin: Int = 1_000, val marginMax: Int = 4_000,
    val triggerRatio: Double = 0.3,
    val chapterRatio: Double = 0.12, val chapterMin: Int = 4_000, val chapterMax: Int = 24_000,
)

fun profileOf(vendor: String?): BudgetProfile = when (vendor?.lowercase()) {
    "gemini"   -> DEFAULT.copy(hardCap = 400_000, triggerRatio = 0.35, chapterMax = 48_000)
    "anthropic","claude" -> DEFAULT.copy(hardCap = 200_000, triggerRatio = 0.32)
    else -> DEFAULT                      // deepseek / openai / 未知：与今天完全一致
}
```

- `autoBudget(cap, model, meta)` 里 `val pf = profileOf(cap?.vendor)`，五个系数改为读 `pf`。
- **开关**：`rpcache.budgetProfile = "legacy" | "auto"`（默认 `legacy`）。RP 面板加一行下拉。
  `legacy` 走 `profileOf(null)`，**与今天逐位一致**，保证回归可复现。
- `hardCap` 提升会提高单轮成本上限，故默认不改行为，需用户显式打开；打开后 `outputReserve` 仍按 10% 且封 16K，不会失控。

**验收**：`autoBudgetLegacyMatchesToday`（对 4 组 window/tier 断言与常量公式逐位相同）+ `autoBudgetGeminiUsesBiggerCap`（1M 窗口 → `maxContext = 400k×0.8` 取整）。

---

### P3 · D7 文件分块检索（B3，2–3 天，本轮收益最大）

**问题**：`FileText.kt:167` 的 `capText` 是**从头上砍一刀**。一份 30 页 PDF 上传后，模型只看到前 1/3；
用户问「第 20 页那个参数是多少」必然答错，且模型会自信地编。这是**功能性错误**，不是精度问题。

**改法**（复用已有的 `RetrievalCore`，不再新写检索）：

```kotlin
// data/files/FileText.kt
fun chunk(text: String, size: Int = 1_200, overlap: Int = 150): List<String>
fun pickRelevantChunks(text: String, question: String, budgetChars: Int): Pair<String, Boolean>
```

1. `chunk`：按段落边界切（`\n\n` 优先，其次句号/换行），目标 1200 字符、重叠 150，避免答案跨段被切碎。
2. `pickRelevantChunks`：
   - 以 `RetrievalCore.grams(question)` 对每个 chunk 做 BM25（索引用 `RetrievalCore.buildIndex`，带签名缓存），取 top-k（k 使总长 ≤ budgetChars，最少 1 块）；
   - **保底**：始终带上第 1 块（标题/摘要通常在开头），若 top-k 覆盖 < 30% 总长则补第一块；
   - 拼装时给每块加标记 `【片段 3/27 · 第 12–14 页】`，让模型知道上下文有缺口，而不是以为文档就这么多。
3. `buildFileContext` 改造：`question` 非空 → 走检索；`question` 为空（纯附件无提问）→ 保持前缀截断（此时没有查询词，检索无意义）。
4. 扫描版 PDF（`FileText` 已有的提示分支）不变。

**风险与开关**：用户可见行为变化（贴进上下文的内容变了）。
`files.chunkRetrieval = true|false`，默认 `true`，但设置页「附件」组加一行「长文档智能截取（推荐）」可关；关掉即回到今天的前缀截断，便于对比与回滚。

**验收**（这三条必须全绿才算做完）：
- golden：新建 `app/src/test/resources/files-golden/chunks.json`，对一份固定 30KB 中文文本断言 chunk 数、首块、末块、重叠长度。
- 命中率：`FileTextTest.answersFromLatePart`——构造「答案在第 20 块」的文档 + 问题，断言答案串**出现在**拼出的上下文里（改造前必然失败）。
- 不劣化：短文档（< budgetChars）→ 输出与今天逐字节相同（不切块）。

---

### P4 · `Retry-After` 支持（B1，0.25 天）

**问题**：`NetRetry.kt:35` 的退避是「猜的」（429 起步 2s）。服务端说「30 秒后再来」，我们 2s 就重试，
`MAX_RETRIES = 2` 的两次机会瞬间烧光，最后给用户一个假失败。

**改法**：

```kotlin
// net/NetRetry.kt
fun parseRetryAfter(headers: Map<String, String>, nowMs: Long = System.currentTimeMillis()): Long?   // 毫秒
fun delayFor(attempt: Int, kind: String?, retryAfterMs: Long?, rnd: Double = Math.random()): Long =
    min(MAX_DELAY_MS, maxOf(backoff(attempt, kind, rnd), retryAfterMs ?: 0L))
```

- `parseRetryAfter` 同时吃两种格式：秒数（`"30"`）与 HTTP-date（`"Wed, 21 Oct 2015 07:28:00 GMT"`）。
- 上限统一 60s（`MAX_DELAY_MS`）：比 60s 更久的等待不如直接报错让用户决定。
- 调用点：`DefaultProviderClient` 的 429/503 分支把 `response.headers["Retry-After"]` 传进 `delayFor`。
- 只在 **429/503** 生效；其它错误沿用现有退避，避免被奇怪的头拖住。

**验收**：`NetRetryTest.retryAfterSecondsWins`（`backoff=0.8s`、头 `30` → `delayFor = 30_000`）、
`retryAfterHttpDate`、`retryAfterClampedTo60s`、`noHeaderFallsBackToBackoff`。

---

### P5 · 卫生：死代码 + 过期注释（B1，0.25 天）

1. 删 `RpCacheCore.kt:437-440` `fun itemTokens(m: JObj)`（无生产调用，只在注释里被引用）；
   同时改 `TokenEstimator.kt:18` 与 `:60` 两处「与 `RpCacheCore.estimateItems` 的 `imgN * 1000` 一致」描述（`estimateItems` 已委托 TokenEstimator，不再是独立实现）。
2. 改写 `RpCacheCore.kt:419-421` 过期注释：

```kotlin
// 对应 rpcache.js 的 plan / computeTrimWindow / temporaryTrimPlan。
// 摘要时点不再用固定阈值：见 summaryTiming()（§4.2，PR #78）。
// applyLagrangeBudget（分层预算）按 MASTER-PLAN §5.3 第 1 条**明确不做**（架构演进取代）。
```

**验收**：`grep itemTokens` 为 0；单测全绿（无行为变化）。

---

### P6 · `plan()` 单轮估算 memo（B2，0.5 天）

**问题**：`plan()` 内 6 处 `estimateItems(...)`，其中 `hist` 被算 3 次（`:402`、`:500`、`:598`）。
每个 token 都是逐码点循环，长会话 200 条消息 × 3 遍 ≈ 多扫几十万字符，一次发送白烧 30–80ms（低端机更明显）。

**改法**：`plan()` 开头一次性算出并复用：

```kotlin
val histTok = estimateItems(hist, provider)
val inputTok = if (input != null) estimateItems(listOf(input), provider) + 6 else 0
val unsumTok = estimateItems(unsummarized, provider)
```

子函数（`computeTrimWindow` / `temporaryTrimPlan` 对应段）改为接收已算好的值，而不是各自重算。
`Plan.tokens` 的键与数值保持不变（只是少算，不多算）。

**验收**：`planIsIdempotentAndFast`——同一输入调两次 `plan`，`tokens` 逐键相等；
加一条「200 条消息 plan 耗时 < 20ms」的宽松基准（JVM 上，防回归即可，不做严格性能门禁）。

---

### P7 · 路由探索与统计衰减（B4，先埋点）

**现状**：`Router.chooseUpstream` 已经有 Beta 后验分位、冷启动、切换迟滞与切换成本折现，**质量很高**；
缺的是「不确定性利用」与「旧数据过期」：

- 无探索：某个上游一次失败后 `fails` 累加，若不衰减，一周后仍压着它 → 价格优势吃不到。
- 无衰减：`UpstreamStats.a/b` 是历史累计，行为漂移跟不上。

**做法（分两步，别一步到位）**：
1. **埋点（1 天，先做）**：`Router.record` 里把每次决策落一条 `router-stats.jsonl`
   （`ts / model / chosen / candidates[slug,pe,score,h,obs] / switched / savedPerTurn / cost`），
   面板加「路由诊断」只读页。**不带任何算法改动。**
2. **算法（等两周数据）**：`a,b = a,b × exp(-Δt/τ)`（τ=7 天，只在 `chooseUpstream` 入口做一次惰性衰减）；
   探索用 ε-greedy（ε=0.05，仅当 `obs < minObs*2` 时对第 2 名采样），受 `router.explore = true` 开关控制，默认关。

**验收**：埋点阶段只要「诊断页能显示最近 20 次决策」；
算法阶段用回放：固定一份 500 条决策序列，断言开启衰减后的累计成本 ≤ 关闭时的 98%（有收益才转正）。

---

### P8 · 校准冷启动先验（B4，0.5 天）

**问题**：`calibKey = provider|model|modality`（`RpCacheCore.kt:198-202`），新模型首轮没有校准值，
只能吃静态系数；而同一 provider 下老模型的实测比率其实可以借用。

**改法**：`calibOf(key)` 找不到精确键时，回落「同 provider 下所有 model 的 `calib` 中位数」，
再回落 1.0；命中回落时在 trace 里标 `calib_source = "provider-prior"`。

**验收**：`calibFallsBackToProviderMedian`。收益小，放在 B4，不抢 B1–B3 的工位。

---

## 3. 分批与版本映射

| 批次 | 项 | 工时 | 版本 | 风险 |
|---|---|---|---|---|
| **B1** | P1 图片计价 · P4 Retry-After · P5 卫生 | 0.75 天 | v1.5.3 | 低（全部带默认值，golden 不动） |
| **B2** | P2 能力表（默认 legacy）· P6 memo | 1.5 天 | v1.5.4 | 中低（开关默认关，行为不变） |
| **B3** | P3 文件分块检索 | 2–3 天 | v1.6.0 | 中（用户可见，需 golden + 三条验收） |
| **B4** | P7 埋点 → 观察 → 算法 · P8 | 1–2 天 + 观察 | v1.6.x | 低（埋点）/ 中（算法带开关） |

**排序理由**：B1 全是「只改口径不改流程」的确定性收益，当天可交付；
B2 为 B3 铺路（B3 要在更大的上下文里挑块，预算得先准）；B3 是唯一用户能直接说出「变好了」的项，值得独占一个版本；
B4 在没有数据前做算法等于瞎调参，先埋点。

---

## 4. 验收指标（进 CI / eval）

| 指标 | 现状 | 目标 | 归属 |
|---|---|---|---|
| 带图对话 token 估算偏差 | 可达 30–40% | < 15% | P1 |
| 429 场景重试成功率 | 2s 即重试，易烧光 | 遵守 `Retry-After`，成功率 > 90% | P4 |
| 长文档问答（答案在后 2/3） | 必错 | 必对（3 条固定用例） | P3 |
| `plan()` 单次耗时（200 条消息） | 未测 | < 20ms | P6 |
| 单测数 | 403 | 只增不减 | 全部 |
| golden | 全绿 | 全绿（`estimateItems`/`plan` 默认入参不变） | P1/P2/P6 |

---

## 5. 开关、灰度与回滚

| 开关 | 默认 | 打开的效果 | 回滚方式 |
|---|---|---|---|
| `rpcache.budgetProfile` | `legacy` | 按 vendor 放大上下文/触发比例 | 改回 `legacy`，无需重启（读设置） |
| `files.chunkRetrieval` | `true` | 长文档按问题检索切片 | 关掉即回前缀截断 |
| `router.explore` | `false` | 允许 ε-greedy 探索 | 关掉 |
| `router.decayDays` | `0`（不衰减） | > 7 启用惰性衰减 | 置 0 |

原则（沿用 MASTER-PLAN §2「明确不做」）：**所有行为变化的算法项都必须有开关、默认保持旧行为**，先灰度再转正。

---

## 6. 与既有文档的关系

| 文档 | 关系 |
|---|---|
| `MASTER-PLAN.md` §2 | 优先级唯一来源；本计划的 P1–P8 对应其中〔算法〕条目，落地后回写状态列 |
| `OPTIMIZATION-ADVISORY.md` §10.2 | 三个实质问题（两把尺子 / 系数硬编码 / 摘要时点）——本计划确认「尺子已统一、摘要时点已解、剩系数与图片口径」 |
| `OPTIMIZATION-ADVISORY.md` §11 | D1–D11 映射表；本计划更正其中「D8 未做 / D2 未做 / D1 未做」的过期判断 |
| `NATIVE_REWRITE_SPEC.md` §3.5 | 契约偏离登记；P1/P2/P3 若改变默认行为，需在 §3.5 增记条目（D12/D13…） |
| `MASTER-PLAN.md` §5.3 | 「§4.3 精确背包 + 粘滞：明确不做」的来源；本计划 P2 只做「系数表」，**不碰背包** |

---

## 7. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-11-17 | 首版。核实 16 项现状，识别 5 件实事（P1/P2/P3/P4/P6 主体）+ 3 件卫生/长线（P5/P7/P8），排入 B1–B4 |
