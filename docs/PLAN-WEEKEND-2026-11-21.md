# 周末工作计划（2026-11-21 ~ 2026-11-22）

> **状态**：🎯 执行计划 · 两天
> 基线：`main @ 5e4502a`（= v1.7.9）/ `versionCode 179` / `versionName 1.7.9`
> 依据：账单分析（`openrouter_activity_2026-11-20 (1).csv`，284 条真实请求）
> 优先级口径：**先修有账单证据的漏钱点**，再谈工程债。本周不做大重构。

---

## 0 一页结论

**这个周末只干一件事：把账单上已经看得见的 37% + 13% 拿回来。**

三条依据（全部来自 CSV 成本反解，回归误差中位 `8.7e-19`）：

| 事实 | 数字 | 归因 |
|---|---|---|
| 1.7.2 缓存修复**真的生效了** | 命中率 72.7% → **99.7%**，$/请求 $0.00526 → **$0.00387** | 断点落点修对了，`cached ≈ prompt − 20~50` |
| Flex 档**从 11-19 03:35 起彻底消失** | 全月 115 条 flex（全在 AI Studio），之后 100% standard | `ModelPicker.kt:56` 的 `remember` 播种，重选模型时静默洗掉 `:flex` |
| 摘要类旁路调用吃掉 **36.2%** 账单 | B 段 7 条零缓存 = $0.0505 / $0.1393 | `summary_model` 默认空 → 摘要走主模型（Gemini $3.75 出参） |

**验收硬指标**（周日晚对账，格式与上一份账单一致）：

1. 新包连续使用 ≥ 1 天、≥ 40 条请求后导出 CSV，**flex 行占比 > 0**（当前 0）。
2. 同口径命中率 **≥ 99%**（当前 B 段 99.7%，不要退化）。
3. 每次请求成本 **≤ $0.0035**（当前 B 段 $0.00387；flex 全开约 $0.00187）。

---

## 1 时间安排

| 块 | 时段 | 内容 | 预估 |
|---|---|---|---|
| S1 | 周六 10:00–12:00 | **T1 Flex 持久化**（+ T2 档位可见） | 2.5h |
| S2 | 周六 14:00–17:00 | **T3 摘要省钱档** + **T4 15:26 全 miss 定位** | 3h |
| S3 | 周六 20:00–21:00 | 装包 + 提交 / 合并文档 PR；开始攒账单样本 | 1h |
| S4 | 周日 10:00–12:00 | **T5 账单验收 + 对账**（导出 CSV，跑同一套脚本） | 2h |
| S5 | 周日 14:00–17:00 | **T6 B2 预算系数能力表：只出设计文档**（不落代码） | 3h |
| S6 | 周日 20:00–21:00 | 收口：MASTER-PLAN / ROADMAP 回写、下周排期 | 1h |

合计约 **12.5h** 有效工作。留 3h 缓冲（周六晚不排硬任务，装包后观察即可）。

**兜底规则**：T1 必须做完（其它都可以顺延）；T4 是查证题，查不出来就**只记录不下结论**，不要为它熬夜改代码。

---

## 2 周六

### T1 · Flex 档持久化（P0，最高优先）

**为什么**：全月按 flex 结算是 $0.9044，实测 $1.4444 —— **多付 $0.54（37%）**；B 段按 flex 结算是 $0.0675，实测 $0.1393（**多付 51.6%**）。这是当前单笔最大、且证据最硬的漏钱点。

**根因**（已定位到行）：

```kotlin
// app/src/main/java/com/aichat/app/ui/chat/ModelPicker.kt:55-56
// Flex 只改变「点选时提交的模型串」；开关按当前选中播种，本次弹层内有效
var flexOn by remember { mutableStateOf(currentModel?.endsWith(":flex") == true) }
```

`remember` 无 key ⇒ 弹层每次重开都从「当前模型串」重新播种。而 `ModelPicker.kt:80-85` 里 `want = if (isOr && flexOn) m + ":flex" else m`，开关关着时点选同一个模型就把 `:flex` 洗成裸名。1.7.3~1.7.7 为了点「思考等级」反复开合这个弹层，时间线与 flex 消失（11-19 03:35 之后）完全吻合。

**改法**（照抄仓库已有的成熟模式，别发明新存储）：

- 存储键与写入：仿 `app/src/main/java/com/aichat/app/net/ProviderRules.kt:254-289` 的 `thinkingKey / levelsOf / levelFor / withLevel`，在**同一个 `settings.extras`** 里加 `flexProviders`（`{ providerId: true }`，按服务商记 —— flex 只对 OpenRouter 有意义，按模型记太碎）。
- 即时覆盖层：仿 `app/src/main/java/com/aichat/app/ui/chat/ChatController.kt:68-74` 的 `_thinking` StateFlow，加 `_flex` + `flexOnOf(providerId)` + `setFlex(providerId, on)`。
- UI 播种改成从持久化读，落到 `ModelPicker.kt:56`：

```kotlin
var flexOn by remember(currentProvider) { mutableStateOf(flexOnOf(currentProvider)) }
```

  并在 `Switch` 的 `onCheckedChange`（`ModelPicker.kt:76`）里同时调 `onFlexChange(it)`，`ChatScreen.kt:610-618` 加一个 `onFlexChange` 参数透传到 `controller.setFlex(...)`。
- **关键：点选模型时不要再用「开关状态」反推**。点选回调 `onPick(p.id, if (isOr && flexOn) m + ":flex" else m)` 保持不变，但 `ModelPicker.kt:79-86` 的 `sel` 判断要能容忍 `currentModel` 已带 `:flex` 的状态，避免「✓ 消失」的错觉。

**验收**（缺一不可）：

1. 新增单测，补进 `app/src/test/java/com/aichat/app/net/FlexVariantTest.kt`：`withFlex` 往返 + `flexOnOf` 为真 + 未设过的服务商回落 false。
2. 新增持久化测试（参考 `app/src/test/java/com/aichat/app/ui/ThinkingLevelPersistTest.kt` 走 `FakeDeps.create`）：开 flex → 选模型 → 关弹层重开 → 仍是 on。
3. 手工真机：装包 → 开 flex → 选 `google/gemini-3.8-flash` → 重开弹层 3 次 → 每次都是 `:flex`。
4. `assembleDebug` + 全量 `@Test` 通过（661 例不许掉数）。

**风险 / 回退**：最坏情况是新包不生效，账单退回现在的 standard 计费 —— 无功能风险。**不要**顺手改 `ProviderRules.baseSlug`（`ProviderRules.kt:105`）的剥变体逻辑，它是路由和元数据回退的共用入口。

---

### T2 · 档位单价可见（P0 附带，20 分钟就做）

**为什么**：这次"flex 丢了 6 天没人发现"，本质是**界面上看不见自己在用哪个价目**。加一行字，比写十份文档有用。

- `app/src/main/java/com/aichat/app/net/OpenRouterRouting.kt:89` 的注释说明端点价已解析，在 `ModelPicker` 的 Flex 行副标题（`ModelPicker.kt:73`）后面并入当前档的 `prompt / completion` 单价，例如 `约半价 · 响应较慢 · $0.375/$1.875 per 1M`。
- 缓存体检面板加一句：`本周 N 次请求走了 standard 档，按 flex 价多付 $X`。若历史用量不落库，先只显示"当前对话档位"即可，别为它加新表。

**验收**：真机能看见单价；单测断言格式化函数（空元数据不崩、缺价目显示"—"）。

---

### T3 · 摘要省钱档（P1，周六下午第一件）

**为什么**：B 段 7 条零缓存 = $0.0505，占 **36.2%** 的账单。其中 3 条（11-19 15:37/15:38）是典型旁路调用：`p=2,114 → out=2,109`、`p=586 → out=1,453`、`p=602 → out=488` —— 小输入大输出、零缓存，单条 $0.0095 / $0.0059 / $0.0023，合计 12.7% 的账单。换 GLM/Wafer（$0.0699 in / $1.40 out）几乎归零。

**现状**：`summary_model` 的读取点是真的（1.7.2 的 C2 修过）：`app/src/main/java/com/aichat/app/plugins/rp/RpCachePlugin.kt:115-119` 读 `settings.summary_model`，`:298/316/342/361` 传给 `ask(overrideModel)`；`AiChatApp.kt:135` 有注释。但默认值是空（`app/src/main/java/com/aichat/app/plugins/rp/RpState.kt:30` 的 `"summary_model" to ""`），且**设置页没有可点的地方** —— 用户永远不知道有这个开关。

**改法**：

- RP 设置区块加「省钱档」小节：输入框 + 「推荐」按钮，按钮把 `summary_model` 填成 `z-ai/glm-5.3`，副标题写清 `$0.0699 in / $1.40 out / $0.0689 cache`（对比 Gemini $0.75/$3.75，约 10×）。
- **不硬改默认值**：用户可能只配了 Google key，默认指向 GLM 会让摘要 404。空 = 走主模型，保持不变。
- 校验：填的模型名经 `ProviderRules.baseSlug` 剥变体后要能在当前服务商的 `models` 里找到，否则黄字提示「这个服务商的模型列表里没有它，摘要会回落到主模型」。

**验收**：

1. 单测：照 `app/src/test/java/com/aichat/app/plugins/rp/RpChapterManualTest.kt:125-131` 的写法补一例 —— 设了 `summary_model` 时摘要请求体的 `model` 是它，没设时是主模型。
2. 真机：设成 GLM → 触发一次摘要 → 日志 / 体检面板能看到走 GLM。
3. 对账时那 3 条 $0.0059~$0.0095 的旁路调用应降到 $0.001 量级。

**风险**：换模型摘要质量可能下降。**只对摘要/合并生效，不动主对话**；回退就是清空输入框（一个动作）。

---

### T4 · 15:26 那条 602-token 全 miss 定位（P1，查证题）

**现象**（B 段唯一"不该发生"的 miss）：

| 时间 | prompt | cached | out | cost |
|---|---|---|---|---|
| 11-19 15:25 | 11,313 | 11,285 | 918 | $0.00431 |
| 11-19 15:26 | **11,915** | **0** | 485 | **$0.01076** |
| 11-19 15:30 | 11,313 | 11,285 | 658 | $0.00381 |

同一供应商（Google）、间隔 1 分钟、同 TTL 窗口内、只多 602 token，却整段失效。**不是 TTL，也不是新会话首轮** —— 最可能是前缀中段被改写（记忆槽 / 时间戳 / `metaJs` 插值在 system 提示中段变化），断点之前全部作废。

**查法**（按顺序，出结论就停）：

1. 看 `GCache.kt:570` 的 `autoHist` / `histBreak` 分支：这条是否走了「历史断点」而上一轮历史文本被重写。
2. 看 `ContextSlots` 的槽位拼装顺序，找**任何会把时间/随机量/排序不稳的东西**放进前缀中段的位置。重点排查 `trace.conflict`（当前只记不阻断）。
3. 把 `trace.conflict` 从「只记录」升级成「记录 + 标明是哪个槽、哪一段偏移变了」—— **本周唯一要改的代码**，其余只出结论。
4. 一小时内定位不到就只写进 ASSESS 当已知缺口，不硬改。单条成本 $0.01076，量化上限很低，别为它加班。

**验收**：`docs/ASSESS-2026-11-21.md` 有「是什么 / 怎么定位 / 值不值得修」一节，或有一个带测试的 `trace.conflict` 增强 commit。

---

### S3 · 周六收口（1h）

- 装包（按 `docs/SIGNING.md` 现状），真机跑一轮：flex / 思考等级 / 摘要省钱档三项都点一遍。
- **T1+T2 一个 PR**（`feat(model): 档位按服务商持久化 + 选择器显示单价`），T3 单独一个 PR，别混。
- 顺手把 #119（纯文档，16 个 .md）**合掉** —— 挂太久会和 MASTER-PLAN 继续脱节。
- 开始攒账单样本（这是周日晚对账的输入，越早开始越好）。

---

## 3 周日

### T5 · 账单验收 + 对账（P0，周日第一件）

**做法**：从 OpenRouter 导出新的 activity CSV（`openrouter_activity_2026-11-22.csv`），**用同一套脚本**跑，别改口径：

- `D:\deep seek harness\_an\shape.py` —— 按日回归 `cached ~ prompt` 的斜率/截距、零缓存数（验证断点形态没退化）。
- `D:\deep seek harness\_an\phase.py` —— A/B 分段（这次的界改成「新包装机时刻」）。
- `D:\deep seek harness\_an\flex.py` —— 逐条反解计费档（回归误差 < 1e-6 才可靠），输出各档条数与「若全按 flex 结算」的反事实成本。

**注意口径**（上次踩过的坑）：`cost_cache` 列是**负数**，是"省下的钱"的独立记录项，不是抵扣错位；分析成本**只用 `cost_total`**，不要再加减它。

**交付**：`docs/ASSESS-2026-11-21.md` 加一节「验收结果」，直接贴 §0 那三条硬指标的实测值。**没达标就写没达标**，别粉饰 —— 这份文档的价值在于可信。

---

### T6 · B2 预算系数能力表：只出设计（P1，周日下午）

**为什么**：出参（含 reasoning）占 B 段账单 **60.3%**，其中 reasoning 占 completion 的 **46.6%** —— 光"思考"就是账单的 28%。缓存已 99.7%，这条路到头了；**下一个大杠杆在"什么时候不思考"**。1.7.3~1.7.7 已把档位做成按模型持久化，但还缺"按场景自动选档"。

**现状**（`app/src/main/java/com/aichat/app/plugins/rp/RpCacheCore.kt:autoBudget`）：全是硬编码魔数 —— `usable = min(window, tier, 200_000)`、`maxCtx*0.8`、`*0.1`、`*0.02`、`*0.3`、`*0.12`。

**本次只做设计，不落代码**（理由：系数需要真机跑数据才能定，周末拍脑袋写出来的数只会变成下一批魔数）：

- 在 `docs/PLAN-ALGO-CACHE.md` 的 B2 下补一节：能力表要哪些维度（模型 / 上下文窗口 / 出参上限 / 思考档位 / 任务类型）、每个系数的**默认值来源**（从 1.7.9 的硬编码搬，标注"待实测"）。
- 定义"任务类型"怎么判（闲聊 / 检索 / 长文 / 摘要 / 工具调用），以及**在哪一层判**（`Router` 还是 `ContextSlots`）—— 只写判定依据，不写代码。
- 写清**怎么测**：需要哪些真机样本、每条记录哪些字段（对应 CSV 的 `tokens_reasoning` / `variant` / `cost_total`），才能把系数从"猜"变成"算"。

**验收**：`docs/PLAN-ALGO-CACHE.md` 的 B2 从 3 行扩到能直接开工的细度，且**没有一行代码改动**。

---

### S6 · 收口（1h）

- `docs/MASTER-PLAN.md` + `docs/ROADMAP.md` 回写本周末实际完成，照 #119 定的四维口径：代码完成 / 自动化通过 / 已发布 / 真机验收 —— **不要**退回混合百分比。
- 下周排期：把没做完的（B2 落代码、`BlobStore.sweep` 零调用、`FileText` 双份、`ImageGen` 内联 base64）排进去。
- 一篇三段式复盘：**哪些数字兑现了 / 哪些没兑现 / 下次别做什么**。

---

## 4 明确不做（周末禁做清单）

| 不做 | 原因 |
|---|---|
| `ChatController` 811 行 / `JsConvLog` 947 行的拆分 | 纯重构，零账单收益，且会制造大量冲突 |
| 三套 `Js`（`data/json/Js.kt`、`plugins/js/Js.kt`、`net/JsCompat.kt`）收敛 | 同上，属于"敢动才敢动"的活 |
| 开 `lint { abortOnError true }` / 上 detekt-ktlint | 会一次性喷出几百条，两天填不完，会把整个周末拖成告警清理 |
| 换 CI key / 修签名链路 | 与账单无关，属于发布工程，单独排 |
| 改 `ProviderRules.baseSlug` 的剥变体逻辑 | T1 的共用入口，动它会把路由和元数据回退一起带塌 |
| 为 T4 那条 $0.01076 的单条 miss 大改架构 | 量化上限太低，查证即可 |
| 改 `summary_model` 的**默认值** | 用户可能只配了 Google key，默认指 GLM 会让摘要 404 |

---

## 5 三项风险的提前预案

| 风险 | 征兆 | 预案 |
|---|---|---|
| Flex 在服务端不稳定（响应慢 / 排队超时） | 新包开启后首字延迟 > 8s 或频繁重试 | flex 保持"用户手动开"，不做成默认；T2 的单价显示要能一眼看出"这是半价所以慢" |
| 摘要换 GLM 后质量掉 | 长篇对话的记忆总结开始丢细节 | 输入框清空即回退；先只在**一个**对话上试，别全局开 |
| 账单样本不够（一天不到 40 条） | 周日晚导出只有十几行 | 只报"趋势"不报"结论"；完整对账顺延到下周末，文档里写明样本量不足 |

---

## 6 完成定义（Definition of Done）

周末结束时，下面每一条要么打勾，要么在 `docs/ASSESS-2026-11-21.md` 里写清"没做 / 为什么"：

- [ ] T1 Flex 持久化：代码 + 单测 + 真机验证，PR 已开
- [ ] T2 单价可见：真机能看见当前档位单价
- [ ] T3 摘要省钱档：设置页可点，真机走通一次摘要
- [ ] T4 15:26 全 miss：给出结论（或明确记录为已知缺口）
- [ ] T5 账单验收：三条硬指标有实测值（样本不足则注明）
- [ ] T6 B2 设计文档：细到能直接开工
- [ ] #119 已合并
- [ ] MASTER-PLAN / ROADMAP 已回写

**一句话判据**：周日晚的账单里**能看见 flex 行**，且 `$/请求` 从 **$0.00387** 掉到 **$0.0035 以下**。