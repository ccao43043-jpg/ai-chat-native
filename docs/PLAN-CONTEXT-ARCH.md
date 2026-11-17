# 上下文管线与插件边界（结构性整顿方案）

> **状态**：✅ 已实施（M1–M7 代码完成 2026-11-18；M8 发版见文末 §8）
> **目标**：把「缓存 / 世界 / 本地资料库 / 结构化记忆 / 心情」这几块的**职责边界**定死，消除「多个插件干同一件事」。
> **不写代码**，本文只定结构。

---

## 0 一页结论

现在的问题不是「插件太多」，而是**没有定义「谁拥有请求的哪一段」**。

8 个插件里：**4 个都会改「最后一条 user 消息」**、**3 个会出缓存徽章**、**3 个在各自解释「世界」这个词**。每个插件单独看都是对的，合起来就是：同一段文本被四个人先后拼、三个数字互相打架、改一个插件会静默影响另一个的缓存命中率。

整顿方案一句话：**一条管线、五个槽位、一槽一主、一个只读观测者**。

| 槽位 | 内容 | 唯一所有者 |
|---|---|---|
| **S1 `system.stable`** | 模板 / 角色卡 / 世界七块 / 常驻世界书 / 长期事实账本 | `rpcache` |
| **S2 `system.delta`** | 章节摘要（低频追加） | `rpcache` |
| **S3 `recall`** | 资料库命中 + 世界书触发 + 记忆召回（**合成一段**） | `recallbus`（新） |
| **S4 `turn.tag`** | 心情 / 关系一行状态 | `affect` |
| **S5 `body`** | 协议层缓存标记（cache_control / 断点 / session） | `cacheplugin`（合并后） |
| （观测） | 账本 + 唯一徽章 | `cachemeter`（只读） |

配套三件事：**运行时断言一槽一主**（第二个来抢直接红）、**S3 统一预算与渲染**（不再三家各 splice）、**缓存插件 4 合 1**。

---

## 1 现状地图：谁碰哪儿

`PluginRegistryImpl.kt:145`

```kotlin
val ORDER = listOf("knowledge", "rpcache", "rpmemory", "usage", "gcache", "mmcache", "affect", "cachemeter")
```

| 插件 | 改 system | 改 messages | 改 body | 写的会话状态 | 出徽章 | 自带预算 |
|---|---|---|---|---|---|---|
| `knowledge` 本地资料库 | ✗ | ✓ 末条 user 尾部 `[LOCAL_KNOWLEDGE]` | ✗ | `meta.knowledge`（trace） | ✗ | `budget` / `MAX_BUDGET` |
| `rpcache` RP 缓存优化器 | ✓ **整段重建**（分层 Segment + 世界书 + 摘要） | ✓ 末条 user **重写** + 历史裁剪/冻结 | ✗ | `rpState`（七块/世界书/摘要/预算） | ✓ 「命中 X%」 | `trigger/maxCtx/chapterCap/memTokens` |
| `rpmemory` 结构化记忆 | ✗ | ✓ 末条 user **追加** `[MEMORY_RECALL]` | ✗ | `rp-memory.json`（条目库/账本） | ✗ | `memTokens 600` / `maxInject 8` |
| `affect` 心情 | ✓ 追加 `[AFFECT]` | ✓ 末条 user **前置** tag + 历史回放 + `<state>` 清理 | ✗ | `extras.affect` | ✓ 「心情 · 关系」 | — |
| `gcache` | ✗ | ✗ | ✓ `cache_control` / 断点 / session | `plugins.gcache.stat` | ✓ 「命中 X%」 | `ttl/rolling` |
| `mmcache` | ✗ | ✗ | ✓ 同构 | `plugins.mmcache.stat` | ✓ 「命中 X%」 | 同构 |
| `cachepolicy` 缓存策略 | ✗ | ✗ | ✗（只写 `ctx.meta.cachePlan`） | — | ✗ | — |
| `cachemeter` 缓存体检 | ✗ | ✗ | ✗ | `plugins.cachemeter.ledger` | ✓ 「缓存 N%」 | — |
| `usage` 用量 | ✗ | ✗ | ✗ | `usage-stats.jsonl` | ✓ token 数 | — |

`worlds` / `lore`（世界书）/ `autochar` **不是插件**，它们是 `rpcache` 域里的模块（`Worlds.kt:17` 的 `BLOCK_IDS`、`RpLoreCore`、`AutoChar`）。

---

## 2 六处「多人干一件事」（都有代码证据）

### R1 · 最后一条 user 消息被四个插件先后改写

按 `ORDER` 的执行顺序，末条 user 的最终形态是四步叠加：

```kotlin
// 1. knowledge（KnowledgePlugin.kt:78-81）
val spliced = Knowledge.spliceIntoUserContent(user0, inj) ?: return out    // 追加 [LOCAL_KNOWLEDGE]

// 2. rpcache（RpCachePlugin.kt:121）—— 用上一步的结果做底，整段重写
mid == inputId -> m.copy(content = RpCacheCore.finalUserContent(st2, inputBase.ifEmpty { m.content }))

// 3. rpmemory（RpMemoryPlugin.kt:97）—— 再追加召回
return ctx.copy(messages = msgs, meta = meta)

// 4. affect（AffectPlugin.kt:100）—— 最前面再插一行
m.role == Role.USER && i == lastIdx -> out[i] = m.copy(content = tag + "\n" + m.content)
```

问题不在顺序，而在**没有任何人管总账**：三家各自有预算（knowledge 的 `budget`、rpcache 的 `memTokens`、rpmemory 的 `memTokens 600`），加起来可以轻松吃掉上下文 10–15%，而 `rpcache` 的裁剪决策只看自己那部分。

更要命的是**互相覆盖**：`rpcache` 的 `finalUserContent(st, inputBase)` 是「重建」，一旦某个插件的注入没进 `inputBase`，就被吞掉。（这个坑已经踩过一次：世界书触发曾因为用 `p.input.content` 之外的底而被覆盖，见 `RpCachePlugin.kt:121` 那一行的注释。）

### R2 · 缓存标记/决策分散在四处

- `gcache`（`CachePlugins.kt:33-178`）只认 OpenRouter + Gemini；
- `mmcache`（`:182-249`）与 gcache **结构同构、代码近似重复**；
- `rpcache` 自己有一份 `capability()`（`RpCacheCore.kt:256-280`）判断 deepseek/qwen/anthropic 的隐式/显式能力，**但不产生任何标记**；
- `cachepolicy` 是「什么都不改的插件」，只写 `ctx.meta.cachePlan` 供别人读。

结果：一个「这轮该不该打缓存标记」的问题，要在四个文件里各读一半才能回答；千问的显式缓存因为没人接 `placeMarkers` 而**从未生效**（`ChatOptions.cacheControl` / `WireMessage.cacheMark` 全仓零写入点）。

### R3 · 三套记忆 + 一本不用的账

| 机制 | 存储 | 进请求的位置 | 谁写 |
|---|---|---|---|
| L2 手写长期记忆块 | `rpState.blocks.memory` | system（改了就破前缀） | 用户手写 / RP 面板 |
| 记忆增量 | `rpState`（`deltaText`） | 末条 user（不破前缀） | `rpcache` |
| 章节摘要 | `rpState.summaries` | system 中段（只追加） | `rpcache`（`RpChapterCore`） |
| 结构化记忆条目 | `rp-memory.json` | 末条 user（`[MEMORY_RECALL]`） | `rpmemory` |
| 事实账本（三元组/撤回/冲突） | `rp-memory.json` 的 `ledger` | **从不进请求** | `rpmemory` |

同一个「角色爱吃鱼」的事实，用户可以在 L2 手写块里写一遍、模型抽取后又在条目库里存一遍、摘要里再压一遍——三处都可能同时被注入，也可能互相矛盾。

### R4 · 三个徽章、三套口径、三个不同数字

| 来源 | 口径 | 代码 |
|---|---|---|
| `rpcache` | **上一轮**那条消息的 `cache.rate` | `RpCachePlugin.kt:328-341` |
| `gcache`/`mmcache` | 滚动近 8 次的平均 | `CachePlugins.kt:171-178` / `:242-249` |
| `cachemeter` | 最近 N 条**按 token 加权** | `CacheMeterPlugin.kt:108-123` |

同一屏三个数字，用户第一反应是「坏了」。

### R5 · `worldId` 一物三义

| 用途 | 存储 | 代码 |
|---|---|---|
| RP 世界模板（七块分层设定） | `worlds.json` → 写进 `rpState.blocks` | `Worlds.kt:17` / `:116-121` |
| 本地资料库分区 | `knowledge_world_<worldId>.json` | `Knowledge.kt:32` |
| 世界书（Lore） | **不在世界里**，存 `rpState.lore` 文本 | `RpLoreCore` |
| 资料库注入闸门 | 读 `conv.extras.worldId`，回落到 `fallbackWorldId` | `KnowledgePlugin.kt:23` 注释 |

「世界」在用户脑子里是一个东西（一个设定的容器），在代码里是三个互不同步的存储，且世界书还挂在「会话状态」上而不是「世界」上——所以删了世界，世界书还在；换个世界聊，资料库可能还指着旧世界。

### R6 · 两套检索索引 + 两套 token 尺子

- `RetrievalCore.grams`（中文 bigram）被 `Knowledge.selectForInject` 和 `RpMemoryCore.buildIndex` **各建一次索引**，各自缓存在自己的对象里；
- 预算用两种口径：`RpCacheCore.estimateTokens`（分服务商图片真价）vs `RpMemoryCore` 的条目估算——B1 刚把 rpmemory 接到 rpcache 的尺子上（PR #98），但 knowledge 的 `budget` 仍是自己的整数预算，没有和 rpcache 的 `maxCtx` 对账。

---

## 3 目标结构

### 3.1 五个槽位（这就是「谁拥有什么」的契约）

```
请求 = ┌ S1 system.stable ────────────── rpcache   （几乎不变 → 缓存前缀，最值钱）
       │ S2 system.delta  ────────────── rpcache   （低频追加：章节摘要）
       │ S3 recall        ────────────── recallbus（每轮变，统一段、统一预算）
       │ S4 turn.tag      ────────────── affect    （一行心情/关系）
       │ S5 body          ────────────── cacheplugin（协议层标记；只有它能改 body）
       └ messages[...]    ────────────── （只读投影：S1–S4 的产物按历史顺序还原）
```

**S3 的关键**：资料库、世界书触发、记忆召回**不再是三个插件各写一段**，而是三个「源」把候选交给 `RecallBus`，由它**统一排序、统一去重、统一预算、统一渲染**成一段：

```
[RECALL]
· 资料：xxx（来源：资料库 / 图书馆 / 第 3 节）
· 世界：港口在雨季会封航（来源：世界书 · 地理）
· 记忆：[事实] 老周欠你一条船（来源：结构化记忆 · 3 轮前）
```

这样「这一轮到底塞了多少东西进去」有一个数字，面板也能显示三条源各占多少。

### 3.2 一槽一主：运行时断言（最重要的一条）

`PluginRegistry` 加一个极小的机制：

```kotlin
class SlotRegistry {
    private val owners = HashMap<Slot, String>()
    fun claim(slot: Slot, pluginId: String) {
        val prev = owners.putIfAbsent(slot, pluginId)
        require(prev == null || prev == pluginId) { "槽位 $slot 已被 $prev 占用，$pluginId 不许再改" }
    }
    fun ownerOf(slot: Slot): String? = owners[slot]
}
```

- 单测/调试构建：冲突**直接抛**；
- release：记 `warn` + 计入 `cachemeter` 的异常计数（不崩用户）。

这条断言的价值：**以后任何「新插件偷偷改 system」都会在 CI 变红**，而不是三个月后靠人肉发现缓存命中率掉了。（世界书触发那次静默失效，就是没有这种断言。）

### 3.3 缓存：4 合 1 + 1 个观测者

| 现在 | 之后 |
|---|---|
| `cachepolicy`（插件，只决策） | 降级为 `CachePlugin` 内部模块（不再占 ORDER 位） |
| `gcache`（OpenRouter+Gemini） | 合并进 `CachePlugin`，成为 `adapter = OR_GEMINI` |
| `mmcache`（MiniMax） | 合并进 `CachePlugin`，成为 `adapter = MINIMAX` |
| `rpcache.capability` 的 qwen/anthropic 判断 | 供给 `CachePlugin` 选 `QWEN_EXPLICIT` / `ANTHROPIC_BREAKPOINTS` / `PREFIX_ONLY` / `NONE` |
| `cachemeter` | 保留（只读 + 账本 + **唯一**徽章） |

硬约束不变：**只有 `adapter ∈ {OR_GEMINI, MINIMAX, QWEN_EXPLICIT}` 时允许改 body**；`PREFIX_ONLY` / `NONE` 路线请求逐字节不变（已有 `PLAN-ALGO-CACHE` §4.4 的约束与单测）。

### 3.4 记忆：四层职责写死

| 层 | 是什么 | 谁写 | 进哪个槽 | 变化频率 |
|---|---|---|---|---|
| **L0 设定** | 世界观/角色卡/规则/文风/示例/禁止（七块） | 用户（手写 / 世界模板 / 角色生成） | S1 前段 | 几乎不变 |
| **L1 事实** | 抽取的稳定事实 + 三元组账本（可撤回、可冲突） | 机器抽 + 用户可改 | S1 **尾部**（随摘要时点重建，不每轮改） | 低频 |
| **L2 召回** | 与当前这几条消息相关的记忆 | 机器选 | **S3** | 每轮 |
| **L3 压缩** | 章节摘要（只追加） | `RpChapterCore` | S2 | 每章一次 |

关键改动两条：
1. **账本进 S1 尾部**（现在完全没用）——它稳定、可压缩、且比「每轮召回」更省钱；
2. **记忆增量（`deltaText`）并入 L2**，交给 `RecallBus`，不再由 `rpcache` 单独拼在末条 user 里。

### 3.5 世界：把它变成一个真正的根对象（分两步，先只统一读路径）

```
World(root)  { id, name, emoji, blocks[7], lore, knowledgeRef, updatedAt }
   ├ blocks        → rpcache S1（现在的 rpState.blocks 变成「会话级覆盖」）
   ├ lore          → rpcache S1 尾部（常驻）+ S3（触发条目）
   └ knowledgeRef  → 资料库（去掉「一个世界一个文件」的隐式约定，允许「全局资料库」）
```

**一期只做读路径统一**：`worldId` 仍是外键，但读的时候统一走一个 `WorldResolver.resolve(conv)`，返回 (world, lore, knowledgeDoc)，三个模块都从它取，不再各自猜。数据不搬、格式不迁移——先消灭「删了世界世界书还在」这类不一致。

**二期**（可选）才把 `rpState.lore` 迁进 `worlds.json`，且必须带迁移脚本 + 回滚。

---

## 4 执行顺序（每步都能单独发版、单独回滚）

| 步 | 内容 | 工时 | 用户能感知什么 |
|---|---|---|---|
| **P1** | `SlotRegistry` + 「上下文体检」面板（只读）：一次请求 S1–S5 各占多少 token、谁注入的、为谁服务 | 0.5 天 | 第一次能看见「这一轮到底塞了什么」 |
| **P2** | 徽章收口：只留 `cachemeter` 一个；`gcache`/`mmcache` 数字挪进各自面板；`cachepolicy` 从插件降级为模块 | 1 天 | 顶栏只一个「缓存 N%」，数字不再互相打架 |
| **P3** | `RecallBus` 上线（先并存：新旧都算一遍，面板显示差异），随后切默认；千问显式缓存接回（`placeMarkers`） | 1.5–2 天 | 召回段落统一、可控，千问开始真的省 |
| **P4** | 记忆四层归位（账本进 S1、增量并入 S3）+ 世界 `WorldResolver` 读路径统一 | 2–3 天 | 「为什么它忘了 / 为什么它记错了」有据可查 |
| **P5** | `gcache` + `mmcache` 合并为 `CachePlugin`（删掉 ~200 行近似重复） | 1 天 | 无感（纯内部） |
| | **合计** | **6–7.5 天** | 分 5 次发版 |

顺序的理由：**先可见（P1）→ 再收口（P2）→ 再统一（P3）→ 再归位（P4）→ 最后删重复（P5）**。P1/P2 是纯收益、零行为风险，可以先做；P3 才开始动请求内容，需要 golden 对照。

---

## 5 验收（可机器判定）

| # | 断言 | 意义 |
|---|---|---|
| 1 | `duplicateSlotOwnerThrows` | 一槽一主生效 |
| 2 | `recallBudgetIsSplitByPriority` | S3 总预算 = 资料 + 世界 + 记忆，且按优先级分配 |
| 3 | `recallDedupesAcrossSources` | 同一句事实不会既从记忆又从资料库注一遍 |
| 4 | `onlyCachePluginMutatesBody` | 只有 `CachePlugin` 能改 body |
| 5 | **`systemStableHashUnchangedWhenOnlyRecallChanges`** | **召回变化不影响 S1 → 前缀缓存不被打断**（这条最值钱） |
| 6 | 面板数字自洽 | S1–S5 五项之和 = 估算总 token（误差 < 3%） |
| 7 | golden 不动 | rpcache 七块 / 世界书 / 摘要四套 golden 逐字节不变 |

---

## 6 不做（明确记下来）

1. **不做自动权重学习**：S3 的优先级先写死（世界书 > 资料库 > 记忆），等账本积累 200+ 轮再谈。
2. **不搬数据**（世界根对象一期只做读路径），除非另行批准。
3. **不引入新依赖**（不引 DI 框架、不引向量库）。
4. **不动 `usage`**：它是纯记账，边界已经清楚。
5. **不为「插件」这个词做面子工程**：如果合并后只剩 5 个插件，就 5 个。

---

## 7 风险与对策

| 风险 | 说明 | 对策 |
|---|---|---|
| ORDER 顺序即行为 | 末条 user 的拼接顺序会变（RecallBus 提前统一） | P3 先「并存对比」，面板显示新旧差异；拿 3 个真实 RP 会话逐轮比对 |
| `conv.extras` 里三处 state 并存 | `rpcache` / `gcache` / `affect` / `cachemeter` 都写在 `extras.plugins` | 不迁移，只加版本号字段；旧对话读时按缺省补 |
| 缓存前缀被打断 | S1 一旦被 L1 账本改变，前缀就变 | L1 只在**摘要时点**重建（与 S2 同频），且 `systemStableHash` 有单测守住 |
| 一步做太大 | 6–7.5 天里有 2 天是重构 | 五步各自可发版、可回滚；P3 带开关 `recall.unified` |

---

## 8 一句话总结

**把「请求」当成一个有明确分段的文档来管：S1 稳定前缀、S2 低频摘要、S3 每轮召回、S4 状态行、S5 协议标记；每段只有一个所有者，第二个来抢就红。**

缓存、世界、资料库、记忆、心情这五件事，全部落在这五段里，各自有位置，不再互相踩。

---

## 8 实施记录（2026-11-18，M1–M7）

集成在一个分支上（`feat/ctx-pipeline-1.6`），每步单测全绿才继续；全程未改版本号、未发版，最后统一发 1.6.0。

| 阶段 | 内容 | 落地 | 单测 |
|---|---|---|---|
| M1 | 槽位契约 `Slot`/`Fragment`/`TurnFragments`/`ContextTrace` + `SlotRegistry`（一槽一主，冲突只记账不抛）+ 上下文体检面板 | `domain/ContextSlots.kt`、`plugins/SlotRegistry.kt`、`ui/chat/ContextInspectorDialog.kt` | 438 → 449 |
| M2 | 缓存徽章收口到 cachemeter；插件页改说「本会话真实状态」（运行中 / 未运行+原因 / 已关闭）+ 缓存策略行 | `plugins/PluginActivation.kt`、`ui/settings/PluginsScreen.kt` | 449 → 452 |
| M3 | `AutoChar` 作用域：有世界只写角色卡，世界块逐字节不动 | `AutoChar.WorldContext/Scope/filterFields/applyToState` | 452 → 465 |
| M4 | `RecallBus`（去重/优先级/预算/渲染）+ `ContextAssembler.assembleLastUser` 单点装配 + `CacheMarkers`（千问显式断点）；knowledge/rpmemory/affect 改交片段 | `domain/RecallBus.kt`、`domain/ContextAssembler.kt`、`plugins/cache/CacheMarkers.kt` | 465 → 492 |
| M5 | 角色生成接线（`AutoCharService` + 提示条）：修两个真 bug（存储形状≠运行时形状、`normalizeState` 静默返回空状态） | `plugins/rp/AutoCharService.kt`、`ui/chat/AutoCharOfferBar.kt` | 492 → 499 |
| M6 | 记忆四层：事实账本进 system 静态段末尾（`[ESTABLISHED_FACTS]`，400 token 封顶、按行丢）；世界读法收敛到 `WorldResolver`（绑定优先于内容） | `plugins/rp/WorldResolver.kt`、`RpCacheCore.factsText/plan(facts=…)` | 499 → 511 |
| M7 | `gcache`/`mmcache` 抽出公共骨架 `CachePluginBase`（只并「一字不差的共同部分」，协议差异留子类） | `plugins/cache/CachePluginBase.kt` | 511 → 517 |

**没做的（明确记账）**：
- `CachePolicy` 仍返回「owner 插件 id」（gcache/mmcache），没有降级成「adapter」——因为两个 id 已被 `settings.plugins.gcache/mmcache.enabled` 与面板引用，改名要迁移数据，收益不抵风险。
- 插件页仍是「gcache 一行、mmcache 一行」，靠三态文案说清哪个在本会话跑；没合并成单行（合并要动 tag 与面板结构，留到 1.7）。
- `recall.unified` 开关默认 **false**：新装配路径已可用，但默认仍走旧路径逐字节兼容，观察一两个版本再翻。