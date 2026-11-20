# 算法与缓存优化 · 详细改进计划

> **状态**：✅ 活跃（算法/缓存唯一细化文档；**优先级仍以 MASTER-PLAN 为准**）
> 版本：2026-11-17 首版；**2026-11-20 PST 复核 main `5e4502a` / v1.7.9**。
> 原始设计基线：`a25e309`（v1.5.2）；旧行号/工时/单测数是当时记录，不是当前计数。
> 当前执行状态见 [MASTER-PLAN.md](MASTER-PLAN.md) §2；逐项代码/测试/CI 证据见 [ASSESS-2026-11-20.md](ASSESS-2026-11-20.md)。
> 定位：本文是 [`MASTER-PLAN.md`](MASTER-PLAN.md) §2 中「〔算法〕」与「advisory 缓存专项」两条线的**细化执行计划**；
> 优先级仍以 MASTER-PLAN 为准，本文只回答「具体怎么改、改哪里、怎么验」。
> 关联：[`OPTIMIZATION-ADVISORY.md`](OPTIMIZATION-ADVISORY.md) §10/§11、[`REVIEW-2026-11-16.md`](REVIEW-2026-11-16.md)。

---

## 0. 一页结论

**这条线不是「缺功能」，是「口径不统一 + 决策点拍死」。**

已核实：过去几轮已经把大部分算法项做完了（统一 token 尺子、摘要最优停止、Beta 路由、索引缓存、自适应 TTL），
本文件按 B1–B6 保留实施细节（B1/B3/B5/B6 核心已落地；默认开关和验收边界另记，见下表与 §7）；仍在排队的只有 **B2 预算系数能力表、B4 路由埋点与探索**（B3 文件分块检索已于 v1.7.0 完成，PR #114）：

| 批次 | 内容 | 工时 | 出口 |
|---|---|---|---|
| **B1** | 图片按服务商计价接入预算路径、`Retry-After`、估算口径与过期注释清理 | 0.75 天 | ✅ **已完成**（PR #98，413 单测全绿）→ 已进入后续发布 |
| **B2** | 预算系数能力表参数化、`plan()` 单轮估算 memo | 1.5 天 | 待做，版本未指定（拟议开关，未实现） |
| **B3** | **D7 文件分块检索**（替代前缀截断） | 2–3 天 | 已发布 v1.7.0（#114） |
| **B4** | 路由埋点 → 数据两周 → 探索/衰减；校准冷启动先验 | 1–2 天 + 观察 | 视数据决定 |
| **B5** | 缓存路线接回（千问显式/Anthropic 真打点、单徽章、`rpSegments`）+ R8 发布包 + 落盘与卫生补课 | 2 天 | ✅ **已完成**（PR #106–#110）→ 已进入 v1.6.1 |
| **B6** | 召回纳入预算、路线一轮只算一次、跨来源软去重、`ContextTrace` 记录异常与说明 | 0.5 天 | ✅ **已完成**（PR #112，577 单测全绿） |

**本计划相对此前口头判断的更正**（重要，避免重复劳动）：
D8「摘要最优停止」**已实现**（`RpCacheCore.summaryTiming`，PR #78）；D2「Beta 折扣路由」**已实现**（`Router.chooseUpstream`）；
D1「索引增量」**已实现**（`buildIndex(list, prev)` 带签名缓存）；`GCache.adaptiveMinTokens` 与 `sessionId` **已实现**（PR #77/#84）。
B1 已修正「有意没搬 adaptiveSummary」的过期注释；不要再当作未实现项。

---

## 1. 现状核实（逐项带证据）

| # | 项 | 结论 | 证据 |
|---|---|---|---|
| 1 | 统一文本 token 尺子 | ✅ 已完成 | `plugins/rp/RpCacheCore.kt:161` `fun estimateTokens(text: Any?): Int = TokenEstimator.text(Js.str(text))`；`plugins/TokenEstimator.kt:26` 逐码点分档（汉字 0.6 / 英文 0.3 / 数字 0.5 / 空白 0.1 / 其余 1.5） |
| 2 | 图片按服务商计价 | ✅ 已完成（P1，PR #98） | `TokenEstimator.kt:47-56` `image(provider, dataUrl)`：gemini 258 / anthropic `max(156,w*h/750)` / openai `85+170*tiles` / 其它 1000。现在 `RpCacheCore.estimateItems(items, provider: String? = null)`、`layerTokens(...)`、`computeTrimWindow(st, p, provider)`、`applyTempTrim(st, p, now, provider)`、`itemTokens(m, provider)` 全部透传；`RpCachePlugin` 传 `ctx.provider.presetId.ifEmpty { ctx.provider.id }`。不传 provider 时仍 `FALLBACK = 1000`（golden 字节级兼容） |
| 3 | 摘要最优停止（D8） | ✅ 已完成 | `RpCacheCore.kt:175-193` `fun summaryTiming(U, g, saved, fixed, limit, h=12, gamma=0.9): Int` 枚举 `t∈0..H` 的折现总成本取 argmin |
| 4 | 分层预算 `applyLagrangeBudget` | ⛔ 用户已决定不做 | `MASTER-PLAN.md` §5.3 第 1 条（架构演进取代）；当前范围以 MASTER-PLAN §5.3 的关闭决定为准，不把旧注释当作待实施入口 |
| 5 | 预算系数 | ⚠️ 硬编码 | `RpCacheCore.kt:365-372`：`usable = min(window, tier, 200_000)`、`maxCtx = usable*0.8`、`outputReserve = maxCtx*0.1 [4k,16k]`、`safetyMargin = maxCtx*0.02 [1k,4k]`、`triggerTokens = maxCtx*0.3`、`chapterCap = maxCtx*0.12 [4k,24k]` |
| 6 | 能力表参数化 | ❌ 未做 | 同上，`Cap` 只提供 `vendor/window/tier`，无「按 vendor 的预算档」 |
| 7 | MMR 检索 | ✅ 已完成 | `plugins/rp/RpMemoryCore.kt:581` `val lam = o.mmrLambda`（默认 0.7）、`:535 selectRelevant`、`:395 terms = RetrievalCore.grams`（中文 bigram） |
| 8 | BM25 索引缓存 | ✅ 已完成 | `RpMemoryCore.kt:437 fun buildIndex(list, prev: Index? = null)` + `:452 buildIndexUncached`（签名命中复用） |
| 9 | 路由 Beta 折扣 | ✅ 已完成 | `net/Router.kt:251 chooseUpstream`：`:266 hitQuantile(st.a, st.b, zPessimistic)`、`:286 stickyIdleMs`、`:291-296` 切换成本折现 |
| 10 | 路由探索 / 统计衰减 | ❌ 未做 | `chooseUpstream` 全程无 epsilon/UCB，`UpstreamStats.a/b` 无时间衰减（`lastAt` 只用于 idle 判定） |
| 11 | 重试退避 | ✅ 已完成（P4，PR #98） | `net/NetRetry.kt` `backoff` 保留（±20% 乘性 jitter，不是 Full Jitter），新增 `parseRetryAfter(headers, nowMs)` / `parseRetryAfterValue(raw, nowMs)`（认秒数与 HTTP-date）/ `delayFor(attempt, kind, retryAfterMs, rnd)` = `minOf(MAX_DELAY_MS = 60_000, maxOf(backoff, retryAfter))`；`domain/Contracts.kt` 的 `Error` 增 `retryAfterMs: Long? = null`；`DefaultProviderClient` 在 429/503 读 `Retry-After` 头；`DefaultChatEngine` 改用 `delayFor` |
| 12 | 文件上下文 | ✅ 分块检索（D7/B3，v1.7.0） | 入库上限随开关放大到 120k；engine `ChunkRetrieval` 滑窗切块（1200/重叠150）+ BM25F 挑选（复用 RetrievalCore）+【片段 n/N】注入，请求体压回 30000 预算；`files.chunkRetrieval` 缺省开、显式 false 回旧路径（逐字节一致，golden 未动） |
| 13 | 原“itemTokens 死代码”判断 | 已更正：不是死代码 | 复核后 `itemTokens` **不是**死代码：`computeTrimWindow` 的前缀和就靠它（`prefix[i + 1] = prefix[i] + itemTokens(un[i], provider)`）。P5 改为「与 `estimateItems` 同一把尺子」，**没有删除** |
| 14 | 单轮重复估算 | ⚠️ 少量（原判断有误，已更正） | 复核：`plan()` 内 `hist` 只算 1 次、`unsummarized` 只算 1 次，原写「hist 被算 3 次」不成立（`:402`/`:405` 在 `layerTokens`、`:598` 在裁剪段的不同列表上）。真正的重复是**同一条带图消息**被 `estimateItems`（预算）与 `itemTokens`（前缀和）各算一次 → 图片像素尺寸被反复解码，优化点应落在 `TokenEstimator.image` 的尺寸缓存 |
| 15 | 自适应 TTL / 阈值 / sessionId | ✅ 已完成 | `plugins/cache/GCache.kt:146 adaptiveMinTokens`、`:219 TTL_EFF_DEFAULT = 240`、`:16` 默认 `sessionId = true` |
| 16 | 成本有效价格学习（§4.6） | ⚠️ 仅单标量 | `domain/cost/CostCoreJs.kt` `reconcile`（scale + biasEwma），无按模型的价差回归 |

---

## 2. 分项设计与实施细节

> P2/P6/P7/P8 仍是提案；其中的代码草图、API 名称、开关与验收目标不代表已实现。已落地项在标题中标注。

### P1 · 图片 token 接入预算路径（B1，0.5 天）✅ 已完成（PR #98）

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

### P3 · D7 文件分块检索（B3，已落地；原估 2–3 天）——✅ 已完成（v1.7.0，PR #114；另有入库上限放大到 120k 的根因修正与 15 例单测，含干草堆回归）

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
`files.chunkRetrieval = true|false` 已实现，默认 `true`（缺省开，显式 false 回旧前缀路径）。**设置页「附件」组开关只是原设计，当前 UI 未实现**；现只能通过设置数据配置。120k 是新附件入库字符上限，旧已截断附件无法恢复丢失部分。`ChunkRetrieval.kt` 类注释「缺省关」已过期，以 `FileContext.chunkEnabled` 与测试为准（本次不改源码）。

**验收**（这三条必须全绿才算做完）：
- golden：新建 `app/src/test/resources/files-golden/chunks.json`，对一份固定 30KB 中文文本断言 chunk 数、首块、末块、重叠长度。
- 命中率：`FileTextTest.answersFromLatePart`——构造「答案在第 20 块」的文档 + 问题，断言答案串**出现在**拼出的上下文里（改造前必然失败）。
- 不劣化：短文档（< budgetChars）→ 输出与今天逐字节相同（不切块）。

---

### P4 · `Retry-After` 支持（B1，0.25 天）✅ 已完成（PR #98）

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

### P5 · 卫生：过期注释 + 尺子归一（B1，0.25 天）✅ 已完成（PR #98）——**原写的「删死代码」有误，见 §1 第 13 行更正**

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

**问题**（已按复核更正）：**不是**「`hist` 被算 3 次」——`plan()` 内 `hist`、`unsummarized` 各只算 1 次。真正的重复是：同一条带图消息会被 `estimateItems`（判断是否超预算）与 `itemTokens`（裁剪前缀和）各算一次，而 `TokenEstimator.dims()` 每次都 Base64 解码图片头部；长会话多图时这一次重复才值得省。
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

## 3. 分批与版本映射（2026-11-20 PST 复核）

| 批次 | 实际状态 / 版本 | 证据或限制 |
|---|---|---|
| B1 | 已落地，后续发布已包含 | #98；图片公式、Retry-After、估算口径卫生 |
| B2 | 待做，版本未指定 | P2 能力表未实现；P6 优化先测同图重复解码，不把 CachePolicy 单轮 memo 当成预算 memo 完成 |
| B3 | 已发布 v1.7.0 | #114；分块默认开，但设置 UI 未实现，真实长文档效果仍需验收 |
| B4 | 待做，先埋点再观察，版本未指定 | 已有 Beta 不等于有受控探索/时间衰减 |
| B5 | 已进入 v1.6.1 | #106–#110；缓存接线/R8/落盘与卫生；不是 AutoChar 全 UI 完成的代称 |
| B6 | 已发布 v1.6.2 | #112；统一召回的预算/软去重受 `recall.unified=false` 默认值限制，缓存路线 memo/trace 不与此混为一谈 |

原计划的「B2 铺路才能做 B3」与 v1.5.4/v1.6.0 预测已被实际交付顺序取代，不再作为依赖或发版承诺。

---

## 4. 验收目标与已验证边界

> 下表保留首版目标与历史基准，不表示已接入 CI 或已达标。当前 CI 结果见总计划 §1。
> 已有 `ChunkRetrievalTest.haystackNeedleAtEnd` / `wiringLegacyByteExact`、`RetryAfterTest`、`TokenEstimatorTest`；尚不能据此声称真实模型长文问答「必对」、token 偏差 <15% 或线上重试成功率 >90%。

| 指标 | 现状 | 目标 | 归属 |
|---|---|---|---|
| 带图对话 token 估算偏差 | 可达 30–40% | < 15% | P1 |
| 429 场景重试成功率 | 2s 即重试，易烧光 | 遵守 `Retry-After`，成功率 > 90% | P4 |
| 长文档问答（答案在后 2/3） | 已有干草堆与接线回归；真实模型待验 | 固定资料/问题验证尾部命中与回答质量，不作「必对」保证 | P3 |
| `plan()` 单次耗时（200 条消息） | 未测 | < 20ms | P6 |
| 单测数 | 413（B1 后） | 只增不减 | 全部 |
| golden | 全绿 | 全绿（`estimateItems`/`plan` 默认入参不变） | P1/P2/P6 |

---

## 5. 已实现开关与拟议开关（不要混用）

| 配置 | 实现状态 / 默认 | 效果与回滚 |
|---|---|---|
| `files.chunkRetrieval` | **已实现，true** | false 回到前缀截断；当前设置 UI 尚无专门入口 |
| `recall.unified` | **已实现，false** | true 才走 RecallBus 单点装配及预算/软去重；false 走兼容路径 |
| `rpcache.budgetProfile` | **拟议，未实现** | P2 设计的 legacy/auto，不能按现成功能填写 |
| `router.explore` / `router.decayDays` | **拟议，未实现** | P7 设计的探索/衰减；当前 Router 不消费这些设置 |

行为变化须有明确回归和转正依据；B3 已随 #114 默认开启，不能再笼统写「所有开关默认保持旧行为」。本次只核对，不改变默认值。

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
| 2026-11-20 PST | 复核 v1.7.9；补 B3 实际发布、B2/B4 未实现开关、统一召回默认关闭与设置 UI 缺口；纠正 Full Jitter 术语。当前状态只维护在 MASTER-PLAN。 |
| 2026-11-17 | 首版。核实 16 项现状，识别 5 件实事（P1/P2/P3/P4/P6 主体）+ 3 件卫生/长线（P5/P7/P8），排入 B1–B4 |
| 2026-11-17 | **B1 完成**（PR #98）：P1 图片 token 接入预算路径（`estimateItems`/`layerTokens`/`computeTrimWindow`/`applyTempTrim`/`itemTokens` 全部透传 provider，`RpCachePlugin` 传 presetId）；P4 `Retry-After`（秒数 + HTTP-date，封顶 60s）；P5 过期注释与 `TokenEstimator` 说明。单测 403 → **413**，golden 零改动。同时更正两处判断错误：`itemTokens` 不是死代码（§1 第 13 行）、`plan()` 里 `hist` 只算一次（§1 第 14 行 / §2 P6） |
| 2026-11-17 | **B0 完成**（新增 §8）：缓存策略选择 + 缓存体检数据收集 + 前缀稳定化算法，单测 413 → **438** |
| 2026-11-18 | **B5 完成**（PR #106 / #107 / #108 / #110）：千问显式缓存的执行者接回（`RpCachePlugin.autoActive` 认 `owner=rpcache`）、缓存徽章收口为单一口径 + 「断点未打出」原因、世界书前导空白容忍与「保存文本/要正文」提示、Anthropic 断点改由 `rpcache` 真打、`meta["rpSegments"]` 发布、TTL 建议与「不值得写」显示；release 包开 R8（11.70 MB → 2.23 MB、冷启动 3523 → 1177 ms）；落盘层补测并修 `FileStore` 覆盖写在 Windows 上静默失败。单测 438 → **558** |
| 2026-11-18 | **B6 完成**（本轮，PR #112）：按第三方报告核实后做 4 件——召回纳入预算（`Plan.remainingTokens` → `meta["rpBudget"]` → `assembleRecall` 封顶）、缓存路线一轮只算一次（`CachePolicy.beginTurn/planOf` + 删 gcache 两道自查）、`RecallBus` 跨来源软去重（Jaccard ≥ 0.8 且数字/否定不冲突）、`ContextTrace` 记录插件异常与装配说明并在「上下文体检」面板展示。单测 558 → **577**；顺手修 `DeviceRegressionTest` 一条只在「迁移后立刻跑」才成立的断言 |

## 8. 缓存策略与体检（2026-11-17 落地）

问题不是「没有缓存功能」，而是「缓存相关的 5 个组件各改各的请求（外加 1 个只读观测器），且只有 OpenRouter/Gemini 这一条路真的生效；其它服务商一分钱没省，也没人知道为什么没省」。

于是拆成三件事，全部是**纯函数 + 一个只读插件**，不改任何现有插件的改写逻辑：

| 文件 | 作用 | 关键 API |
|---|---|---|
| `plugins/cache/CachePolicy.kt` | 每个会话选**唯一**一条缓存路线；一轮只算一次（`beginTurn`/`planOf`/`clearTurn`） | `pick(provider, model, settings, conv): CachePlan` |
| `plugins/cache/CacheLedger.kt` | 每轮记一条样本 + 6 类 miss 归因 | `sample(...)` / `attribute(...)` / `hitRate(...)` / `summary(...)` |
| `plugins/cache/CacheMeterPlugin.kt` | 只读插件：算路线、写账本、出入口令徽章 | `headerBadges` → 「缓存 68%」 |
| `plugins/cache/PrefixStabilizer.kt` | 段级稳定性 → 期望命中率、断点位置、TTL 建议、值不值得写 | `stability` / `expectedLcp` / `breakpoints` / `CacheCostModel` |

路线规则（`CachePolicy.pick`，自上而下第一条命中；**2026-11-18 已更新为现状**）：

| 顺序 | 条件 | owner | 是否改请求 |
|---|---|---|---|
| 0 | `conv.extras.plugins.cachepolicy.owner` 有值（`gcache`/`mmcache`/`rpcache`/`prefix`/`none` 都合法） | 会话指定 | 看指定值 |
| 1 | 设置里关掉 `cachepolicy` | `none` | 否 |
| 2 | `cacheAdapter=none`（用户明确关） | `none` | 否 |
| 3 | MiniMax | `mmcache` | 是 |
| 4 | OpenRouter / openai-compat 上的 Gemini | `gcache` | 是（全量改写） |
| 5 | 千问显式缓存（`presetId∈{qwen,qwen-intl}` 或 baseUrl 含 `dashscope`/`aliyuncs`，且非 flash-character） | `rpcache`（`breakpoints`，打 `cache_control`） | 是 |
| 6 | 原生 Anthropic | `rpcache`（`breakpoints`，打 `cache_control` + 历史断点） | 是 |
| 7 | 原生 Gemini | `prefix`（`auto`，隐式缓存，动一下前缀就重建） | 否 |
| 8 | 其它 OpenAI 兼容（OpenAI/DeepSeek/Moonshot…，服务端自动前缀缓存） | `prefix`（`auto`，只观测） | 否 |

配套改动：

- `GCachePluginImpl` / `MmCachePluginImpl` 由「常开 + 自己内部再判」改成 **`perConversation = true` + `autoActive` 读 `CachePolicy.planOf`**——插件要么真的在干活，要么干净地不出现，不再有「在跑但什么都没做」的假象。
- **路线判定只有一处**：gcache/mmcache 的 `onBeforeRequest` 删掉了各自的 `isOpenRouterGemini` / `isOpenRouterMiniMax` 自查（两处判断不一致时插件会静默空转）；协议闸留在 `GCache.resolveCacheAdapter`。
- `RpCachePlugin.autoActive` 也认 `owner == rpcache`——千问显式与 Anthropic 的断点都由它执行，纯聊天（没有 RP 块）时它也必须上班。


归因优先级：换模型 > 换服务商 > 空闲超 TTL > 前缀变短 > LCP 低 > 未知；**首轮不归因**（缓存还没建立，记成 unknown 会污染统计）。

命中率口径：按 **token 加权**（`Σhit / Σprompt`），且只统计 `known` 样本（服务商回了 `hit` 或 `cache_supported`）；中转吞掉缓存字段时宁可显示「无数据」，也不把「不知道」算成「没命中」。

数据落在 `conv.extras.plugins.cachemeter`：`plan` / `summary` / `ledger`（最近 40 条）/ `stab`（段稳定度与断点建议）/ `gaps` / `ttlSuggest` / `worthWrite`。这些都是后续调参的输入，不参与决策。

## 9. 运行时顺序与预算传导（2026-11-18 落地）

一轮请求里「缓存相关」到底按什么顺序跑、谁改了什么、数据落在哪 —— 代码里散在 6 个文件，这里按**调用顺序**串一遍（行号见各文件）。

```
ChatEngine.turn()
  └─ PluginRegistry.runBeforeRequest(ctx)
       CachePolicy.beginTurn(ctx)                    // 本轮路线只算一次，存 turnMemo[convId]
       for (p in activePlugins)                      // ORDER: knowledge → rpcache → rpmemory → usage → gcache → mmcache → affect → cachemeter
            p.onBeforeRequest(ctx)
              knowledge   : fragments += S3_RECALL（资料库命中）
              rpcache     : 重写 system（S1 稳定前缀 + S2 低频增量）；落 CacheMarkers → opts.cacheControl / cacheSystemSplit / 末条 user.cacheMark
                            meta["rpcache"] + meta["rpBudget"]{limit,used,remaining} + meta["rpcacheMarks"] + meta["rpSegments"]
              rpmemory    : fragments += S3_RECALL（记忆召回）
              gcache/mmcache : 仅在 owner 命中时改 body（OpenRouter/Gemini、MiniMax）
              affect      : fragments += S4_TAG
              cachemeter  : 读 planOf() 与 ctx.trace 快照 → convData["lastTrace"]（不改请求）
       assembleRecall(ctx)                            // 只有 recall.unified=true 才装配
            budget = min(剩余预算 or 1200, 1200) - 状态行 tokens
            RecallBus.render(budget) → 去重 / 软去重 / 按优先级截断
            ContextAssembler.assembleLastUser(原文, tag, recall) → 写回末条 user（其余消息不动）
       finishTrace(ctx)                              // systemTokens / lastUserTokens / stableHash
       CachePolicy.clearTurn(convId)
  └─ DefaultProviderClient.stream()                   // 此时请求已定稿；缓存断点在这一刻已经写在 headers/body 里
  └─ PluginRegistry.runAfterResponse()
       cachemeter : CacheLedger.sample(...) → ledger/summary/ttlSuggest/worthWrite
```

三条设计不变量（有局部自动化覆盖，不等于全服务商端到端验收；第 1/3 条统一召回装配受 `recall.unified` 控制）：

1. **只改末条 user** —— 召回与状态行都拼在最后一条用户消息上，历史消息与 system 前缀不动。`systemPromptByteEqualWhenOnlyRecallChanges` / `onlyRecallChangesKeepsSystemByteEqual` / `emptyConversationStaysByteIdenticalEvenWhenRouteIsRpcache` 三条断言守着这件事。
2. **只有 owner 改 body** —— `CachePlan.mutates` 只对 `gcache`/`mmcache`/`rpcache` 为真；`prefix`/`none` 路线的请求必须逐字节不变（`memorizes` 不是借口，改了就是我错了）。
3. **预算在一处收口** —— 剩余预算只有 `Plan.remainingTokens` 一个来源，经 `meta["rpBudget"]` 传给召回装配；召回不够时**内容整段跳过并留说明**，绝不静默截断半条资料。

数据落点一览：

| 位置 | 内容 | 谁写 |
|---|---|---|
| `conv.extras.plugins.cachemeter` | `plan` / `ledger`（最近 40 条）/ `summary` / `stab` / `gaps` / `ttlSuggest` / `worthWrite` / `lastTrace` | cachemeter（只读观测） |
| `conv.extras.plugins.cachepolicy` | `enabled` / `owner`（本会话覆盖） | 设置页 |
| `conv.extras.plugins.gcache`（或 `mmcache`） | `stat` / `hist` / `session_id` / `decision` / `diag` | 各自的缓存插件 |
| `meta.rpBudget` | 本轮剩余预算（一次性，不落盘） | rpcache |
| `ctx.trace` | 每个插件的槽位声明、动了多少 token、异常与说明 | registry + 各插件 |
