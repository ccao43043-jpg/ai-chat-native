# 角色生成 Skill 分档：有世界 vs 无世界

> **状态**：📝 计划 · 待批准 · 2026-11-18
> **触发**：用户观察 —— 「这个角色卡在专场聊天的时候有用，但是在世界观已经设定好的那个世界里面，它就没有用处了。」

---

## 0 一页结论

`AutoChar`（旧版 `www/autochar.js` 的移植）现在只有一个模式：**一次生成全套 8 段设定**（世界观 + 角色卡 + RP 规则 + 输出格式 + 示例对白 + 禁止事项 + 写作风格 + 开场白），然后把整套拼进系统提示词。

这在「专门开一场聊天」时是对的——那时候什么都没有，就该一次给全。

但在**已经套用世界模板**的对话里，世界观、规则、文风已经在分层设定里写好了；这时再让模型输出 8 段，等于**用模型瞎编的内容覆盖用户辛苦写好的世界**，比不生成更糟。世界里真正缺的通常只有一样：**一个符合这个世界的角色**。

所以要做的不是「修 bug」，而是**给角色生成加一个作用域**：

| 场景 | 该生成什么 | 该写哪块 |
|---|---|---|
| 专场（无世界） | 全套 8 段 | 七块全写（保持现状） |
| 有世界 | **只生成角色卡**（可含 2–4 个 NPC 与示例对白） | 只写 `character`（可选 `fewshot`），世界/规则/文风**一个字不动** |

工时 **1.5–2 天**，收益是「世界里终于能一键出角色，且不会毁掉世界」。

---

## 1 现状：代码就是这么写的

### 1.1 触发条件只看「是不是 RP 模板 + 有没有角色」

`app/src/main/java/com/aichat/app/plugins/rp/AutoChar.kt:47-55`

```kotlin
fun shouldOffer(conv: Any?, templates: List<Any?>?, settings: Any?): Boolean {
    if (!Js.truthy(conv) || mode(settings) == "off") return false
    if (Js.truthy(conv["autoChar"]) && Js.truthy(conv["autoChar"]["asked"])) return false
    val msgs = (Js.or(conv["messages"], ArrayList<Any?>()) as? List<*>) ?: emptyList<Any?>()
    val us = msgs.filter { it["role"] == "user" && !Js.truthy(it["error"]) && !Js.truthy(it["imageGen"]) }
    if (us.size != 1 || Js.trim(s(us[0]["content"])).isEmpty()) return false
    if (msgs.any { it["role"] == "assistant" && ... }) return false
    return isRpTemplate(conv, templates) && !hasCharacter(conv)   // ← 只看这两条
}
```

`hasCharacter`（`AutoChar.kt:28-40`）只看 `rpState.blocks.character` 有没有内容，**不看世界有没有设定**。

### 1.2 提示词硬性要求输出 8 段

`AutoChar.kt:57` + `:58-65`

```kotlin
const val FORMAT = "【世界观】\n【角色卡】\n【RP规则】\n【输出格式】\n【示例对白】\n【禁止事项】\n【写作风格】\n【开场白】"

fun buildPrompt(firstText: Any?, systemPrompt: Any?, skillContent: Any?): String {
    ...
    return rules + "\n\n---\n下面是一场角色扮演的设定上下文，请据此直接输出完整 8 段设定。\n硬性要求：…" + story + "\n" + FORMAT + ...
}
```

### 1.3 结果回填也是 8 段

`AutoChar.kt:99-101`

```kotlin
val TITLES = linkedMapOf("world" to "世界观", "character" to "角色卡", "rules" to "RP规则",
    "format" to "输出格式", "fewshot" to "示例对白", "prohibit" to "禁止事项", "style" to "写作风格")
fun toPromptAppend(fields: Any?): String =
    "\n\n=== 自动生成的角色卡 ===\n" + TITLES.keys.filter { Js.truthy(fields[it]) }
        .joinToString("\n\n") { "【" + TITLES[it] + "】\n" + Js.str(fields[it]) }
```

### 1.4 而「世界里」这些东西本来就有

`app/src/main/java/com/aichat/app/plugins/rp/Worlds.kt`

```kotlin
val BLOCK_IDS = listOf("world", "character", "rules", "format", "fewshot", "prohibit", "style")   // :17
const val WORLD_START_TEMPLATE_ID = "t-rp-immersive"                                              // :102

fun conversationFromWorld(world, providers, fallback, api): JObj {                                // :108-115
    ... "worldId" to w["id"], "worldName" to w["name"], "worldEmoji" to w["emoji"],
        "title" to ..., immersiveFields(api)      // ← 套用世界时已经把「沉浸式 RP」设为系统提示词
}

fun applyWorldToState(state, world, at, setBlock): JObj {                                         // :116-121
    BLOCK_IDS.forEach { id -> setBlock(st, id, w["blocks"][id], at) }   // ← 七块整批写入
}
```

也就是说：**从世界开聊 = 系统提示词（沉浸式 RP 规则）+ 七块分层设定（含 world/rules/style）**。这时再跑一次 `buildPrompt`，模型会把已经写好的世界观当成「参考」重写一遍，写回 `character` 之外还污染其他块。

### 1.5 还有一个前提问题：现在根本没接线

`AutoChar` 的 7 个入口函数全仓零调用（`shouldOffer` / `buildPrompt` / `toPromptAppend` / `fillLeftovers` / `fromUserText` / `hasCharacter` / `isRpTemplate`：main=0、test=0），`settings.autoCharMode`（`Models.kt:71`，默认 `"auto"`）只有编解码与迁移、没有消费方，设置页也没有这个开关。

所以本计划实际是**两件事一起做**：

1. 把 `AutoChar` 接进对话流程（首条消息后询问 / RP 面板手动按钮）；
2. 接线时**必须**带作用域，否则一接上就会在世界里出事故。

---

## 2 目标 / 非目标

**目标**

- 世界里生成角色：**只写 `character`（可选 `fewshot`）**，`world` / `rules` / `style` / `format` / `prohibit` **逐字节不变**。
- 生成的角色必须与世界相容（时代、地点、势力、规则、基调）。
- 专场行为保持现状（8 段全写），不改老用户预期。
- `autoCharMode` 真正生效（`auto` / `ask` / `off`），且能在设置页关掉。

**非目标**

- 不做「世界冲突自动检测」（见 §7）。
- 不改 `t-rp-cardgen`（角色卡 Skill）模板正文——它是手动兜底路线，已经能跑。
- 不做 NPC 批量生成、不做角色关系图。

---

## 3 设计

### 3.1 判定「在不在世界里」：`WorldContext`

```kotlin
// AutoChar.kt 新增
data class WorldContext(
    val worldId: String?,        // conv.worldId
    val worldName: String?,      // conv.worldName
    val worldBlock: String,      // rpState.blocks.world 的正文
    val constantLore: String,    // RpLoreCore.constantLoreText(st)：常驻世界书条目
) {
    val inWorld: Boolean get() = !worldId.isNullOrEmpty() || worldBlock.isNotBlank()
}

fun worldContext(conv: Any?, rp: RpState?): WorldContext
```

判据用三条任一成立：`conv.worldId` 非空 / `rpState.blocks.world` 非空 / `conv.worldRef` 有内容。
（`worldRef` 由 `Worlds.makeWorldRef` 写入，`worldPending` 非空说明正在套世界，也算「世界里」。）

### 3.2 两种作用域

```kotlin
enum class Scope { FULL, CHARACTER_ONLY }

fun scopeOf(conv: Any?, templates: List<Any?>?, rp: RpState?): Scope =
    if (worldContext(conv, rp).inWorld) Scope.CHARACTER_ONLY else Scope.FULL
```

### 3.3 提示词分档：世界当「只读约束」喂进去

`buildPrompt` 增参 `scope: Scope` 与 `world: WorldContext`：

- `FULL`：**完全不动**现有函数体（保证专场行为可逐字节回归）。
- `CHARACTER_ONLY`：换一段新 prompt，核心差别三条：

```kotlin
val FORMAT_CHARACTER = "【角色卡】\n【示例对白】"   // 只这两段

fun buildCharacterPrompt(firstText, world, systemPrompt): String = buildString {
    append("你是角色卡生成器，工作在一部已经写好的作品里。\n")
    append("下面是这个世界的既有设定，它是**只读**的：\n\n")
    append("【世界观】\n").append(Js.slice(world.worldBlock, 0, 6000)).append("\n\n")
    if (world.constantLore.isNotEmpty()) append(world.constantLore).append("\n\n")
    append("硬性要求：\n")
    append("1. 只输出【角色卡】与【示例对白】两段，不要输出【世界观】【RP规则】【写作风格】【禁止事项】【输出格式】【开场白】；\n")
    append("2. 角色的时代、地点、身份、称谓、能力边界必须与上面世界相容，不得引入与世界冲突的设定；\n")
    append("3. 用户的身份由用户决定，不要替用户设定行动；\n")
    append("4. 用户首条消息里给出的名字、身份、关系、场景原样保留，只补全缺失部分；\n")
    append("5. 【角色卡】写具体可扮演：外貌、性格优缺点、说话方式与口头禅、背景经历、与用户的关系、秘密、动机；\n")
    append("6. 世界需要 NPC 时，可在角色卡之后列出 2–4 个重要配角（姓名、身份、与主角关系、动机）；\n")
    append("7. 不要寒暄、不要解释、不要提问，直接从【角色卡】开始输出。\n\n")
    append("【用户首条消息】\n").append(Js.slice(Js.str(firstText), 0, 6000))
}
```

`systemPrompt` 不再整段塞进去（世界里它就是沉浸式 RP 的规则，模型已经遵守），只在末尾附 600 字摘要避免重复。

### 3.4 回填：白名单 + 守卫

```kotlin
private val CHARACTER_ONLY_KEYS = listOf("character", "fewshot")

/** 只把允许的块写回 state；返回写入了哪些、丢弃了哪些（用于诊断与面板提示） */
fun mergeIntoState(st: JObj, fields: JObj, scope: Scope, at: Long?, rp: RpState): MergeOutcome
```

- `FULL`：七块全写（等价现在的 `toPromptAppend` 行为，可保留旧路径）。
- `CHARACTER_ONLY`：
  - 只写 `character`；`fewshot` 仅在模型确实给出且 `format` 未占用时写（可配置，默认写）。
  - 模型若仍输出 `【世界观】/【RP规则】/【写作风格】…`，**全部丢弃**，块名记进 `conv.autoChar.dropped`。
  - `character` 已有内容时**不覆盖**，返回 `Outcome.AlreadyExists`，由 UI 提示「已有角色卡：替换 / 追加（作为配角）/ 取消」。
- 写入方式复用 `RpState.setBlock`（与 `Worlds.applyWorldToState` 同一入口，保证 `baseHash` / `staticVersion` 行为一致）。

### 3.5 世界书联动

`RpLoreCore.constantLoreText(st)` 生成的 `[LOREBOOK]` 常驻条目直接作为只读上下文喂进 prompt（见 3.3 第 2 段），这样：「这个世界里魔法要付代价」这类硬设定不会在角色卡里被写反。

### 3.6 数据结构

```jsonc
// conv.autoChar
{
  "mode": "auto|ask|off",        // 冗余快照，真正开关读 settings.autoCharMode
  "scope": "full|character",     // 本次生成用的作用域
  "worldId": "w...",             // 生成时所在世界；与当前 worldId 不同则允许重新询问
  "asked": true,
  "status": "done|failed|skipped",
  "at": 1794…,
  "dropped": ["world", "rules", "style"]   // 被守卫丢弃的块，便于「为什么没写进去」的自证
}
```

`shouldOffer` 增加两条：`scope` 决定文案；`worldId` 变化时忽略旧的 `asked`（换世界可以再问一次）。

---

## 4 交互

### 4.1 首条消息后询问（`mode = ask`）

沿用现在的时机（只有 1 条用户消息、还没有助手回复），文案分档：

- 专场：「想让我先把设定写好吗？（世界观 + 角色卡 + 规则，一次生成后就能开演）」
- 世界：「《海边渔村》的世界观已经有了。要我生成一个符合这个世界的角色吗？只写角色卡，不动世界设定。」

三个按钮：**生成** / **跳过** / **不再询问**（写 `asked=true`）。

### 4.2 主动入口（`mode = auto` 或已跳过）

RP 面板 → 分层设定 → 底部新增一行按钮：**「用 AI 生成角色卡（限定本世界）」**。
它与 4.1 走同一条 `scope=CHARACTER_ONLY` 逻辑，不依赖「首条消息」这个时机——用户写到一半想加个角色也能用。

### 4.3 设置页

「插件」组内新增一行「自动生成角色卡」三档（自动 / 先问 / 关闭），绑 `settings.autoCharMode`。
默认 `auto`（与 `Models.kt:71` 一致），但 **`auto` 在世界里的含义是「自动生成角色卡」而不是「自动生成全套设定」**——这点写进副标题。

---

## 5 测试清单（`app/src/test/java/com/aichat/app/plugins/rp/AutoCharScopeTest.kt`）

| # | 用例 | 断言 |
|---|---|---|
| 1 | `scopeIsCharacterOnlyWhenWorldSet` | `conv.worldId` 非空 → `CHARACTER_ONLY`；`blocks.world` 非空 → `CHARACTER_ONLY`；两者都空 → `FULL` |
| 2 | `characterOnlyPromptCarriesWorldConstraints` | prompt 含 `blocks.world` 原文；含「只输出【角色卡】」；**不含** `FORMAT` 全表 |
| 3 | `characterOnlyDropsOtherSections` | 模型返回 8 段 → 写入后 `blocks.world/rules/style` 与写入前逐字节相等，`dropped` 含三项 |
| 4 | `characterOnlyKeepsUserGivenName` | 首条消息写「我叫阿岚，是这里的学徒」→ 生成的 `character` 含「阿岚」 |
| 5 | `characterOnlyRespectsExistingCharacter` | `character` 非空 → 返回 `AlreadyExists`，不覆盖 |
| 6 | `fullScopeStillWritesAllBlocks` | 专场回归：七块写入，与旧 `toPromptAppend` 结果一致 |
| 7 | `shouldOfferUsesScopeCopy` | 世界/专场两种文案不同；`asked=true` 后不再 offer |
| 8 | `worldSwitchReopensOffer` | `autoChar.worldId` 与当前 `conv.worldId` 不同 → 重新 offer |
| 9 | `modeOffNeverOffers` | `settings.autoCharMode="off"` → `shouldOffer=false` |

---

## 6 分期

| 阶段 | 内容 | 工时 | 可独立验证 |
|---|---|---|---|
| **S1** | `WorldContext` + `Scope` + `buildCharacterPrompt` + `scopeOf` | 0.5 天 | 单测 1、2 |
| **S2** | `mergeIntoState` 白名单与守卫 + `dropped` 记录 | 0.5 天 | 单测 3、4、5、6 |
| **S3** | 接线：首条消息询问 + RP 面板按钮 + `shouldOffer` 分档 + 设置页开关 | 0.5–1 天 | 单测 7、8、9 + 真机走查 |
| 合计 | | **1.5–2 天** | 可并入 v1.6.0 |

S1/S2 是纯函数，不需要动 UI，可以先合并；S3 才碰 `ChatController` / `RpPanel` / `SettingsScreen`。

---

## 7 不做（明确记下来，免得以后又提）

1. **不做世界冲突自动检测**。「角色设定与世界矛盾」是语义判断，规则判不准，误报比漏报更烦人；改为在 prompt 里约束 + 生成后让用户自己看一眼。
2. **不改 `t-rp-cardgen` 正文**。它是「先想清楚再生成」的手动路线，与自动生成并存：自动失败（模型不听话、输出不成 8 段）时用户仍可用它 + 「整段粘贴自动分段」。
3. **不做 NPC 批量生成 / 角色关系图**。一次一个角色，用户要配角就再点一次。
4. **不迁移旧数据**。`autoChar` 是新字段，缺省视为 `{asked:false, scope:auto}`，不需要迁移脚本。

---

## 8 验收口径

- 世界里点「生成角色」→ `rpState.blocks` 里 **只有 `character` 变化**，`world`/`rules`/`style`/`format`/`prohibit` 的 hash 与生成前一致。
- 专场点「生成设定」→ 七块全写，行为与 1.5.3 一致（可拿一个固定假模型跑 golden）。
- 世界里生成的角色卡**不出现**与 `blocks.world` 冲突的时代/地点词（人工抽查 3 例）。
- `autoCharMode=off` → 任何时机都不弹、不请求（不产生额外 token 花费）。
- 顶栏/面板可见「本次只写了角色卡，丢弃了世界观/规则」这类提示（`dropped` 非空时）。

---

## 9 代码位置索引

| 文件 | 作用 | 本计划要改 |
|---|---|---|
| `app/src/main/java/com/aichat/app/plugins/rp/AutoChar.kt` | 生成/解析/回填（127 行，零调用） | ✅ 主体：加 `WorldContext`/`Scope`/`buildCharacterPrompt`/`mergeIntoState` |
| `app/src/main/java/com/aichat/app/plugins/rp/Worlds.kt` | 世界模板、七块、`applyWorldToState`、`makeWorldRef` | 读 `BLOCK_IDS`、`worldId` 判据；不改 |
| `app/src/main/java/com/aichat/app/plugins/rp/RpState.kt` | `getBlock` / `setBlock` / `normalizeState` | 复用写入入口 |
| `app/src/main/java/com/aichat/app/plugins/rp/RpLoreCore.kt` | `constantLoreText` 常驻世界书 | 只读引用 |
| `app/src/main/java/com/aichat/app/ui/chat/ChatController.kt` | `planApplyTemplate:274`、`setConversationSystem:295` | ✅ 加 `planAutoChar(scope)` 与结果落盘 |
| `app/src/main/java/com/aichat/app/ui/chat/ChatScreen.kt` | 首条消息后的询问入口 | ✅ 接 `shouldOffer` + 分档文案 |
| `app/src/main/java/com/aichat/app/ui/chat/RpPanel.kt` | 分层设定编辑 | ✅ 加「用 AI 生成角色卡」按钮 |
| `app/src/main/java/com/aichat/app/ui/settings/SettingsScreen.kt` | 设置页 | ✅ 加 `autoCharMode` 三档 |
| `app/src/main/resources/aichat/builtins.json` | 10 个内置模板（含 `t-rp-immersive` 517 字、`t-rp-cardgen` 1369 字 skill） | 不改 |
