# EXPERIENCE-GUARD · 只读审计（2026-11-21）

> 基线：`main @ 5eb5bc8`（origin/main 最新）· 单测基线 **669 例 0 失败**
> 角色：GLM/ZCode 只读审计 · 本文是 WP1 交付（无生产代码改动）
> 方法：逐面静态走查 + 证据定位；优先级按「会花钱 / 会丢数据 / 会回归」排序

## 1. 审计发现（U = UI 状态，M = 动效，A = 触控/a11y，T = 测试缺口）

| ID | 面 | 现象 | 用户影响 | 证据 | 修复成本 | 风险 | 优先级 |
|---|---|---|---|---|---|---|---|
| U1 | 聊天页 | 发送失败丢输入：`onSend` 乐观清空（ChatScreen.kt:457），引擎失败后原文只存在于历史里，无恢复路径 | 重打一大段话 | ChatScreen.kt:457 + ChatController.kt:465-482（send 乐观返回 true） | 低 | 低 | **P0** |
| U2 | 消息项 | 失败呈现裸奔：错误只是气泡外一行红字（MessageItem.kt:193），失败且无正文时连气泡壳都没有；重试只能从下方操作栏的「重新生成」发现（267） | 失败了不知道能干嘛 | MessageItem.kt:174-193 | 低 | 低 | **P0** |
| U3 | 模型弹层 | 思考胶囊乐观变蓝不回滚：`picked` 先变（ModelPicker.kt:112/114），`setThinkingLevel` 无对话时只 toast 拒绝（ChatController.kt:316-318）——胶囊已蓝但不落盘，无回滚 | 以为选上了 | ModelPicker.kt:112-127 + ChatController.kt:307-321 | 低 | 低 | **P1** |
| U4 | 模型弹层 | ✓ 容错两向漏：flex 关 + 模型串带 :flex → 基础行仍 ✓（显示未提交状态）；flexOn 开 + 未重选 → 当前在用模型的 ✓ 消失 | 选中态误导 | ModelPicker.kt:94 | 低 | 低 | P2 |
| U5 | 模型弹层 | 落盘失败静默丢翻转：settings 写失败时覆盖层照撤（ChatController.kt:344-345），本地翻转无反馈丢失 | 开关自己弹回去 | ChatController.kt:339-347 | 中 | 中 | P2（已知缺口，本轮不修） |
| U6 | 顶栏 | PluginErrorDot 整个可点区域无 contentDescription / Role（TalkBack 读不出） | 无障碍 | PluginErrorsPanel.kt:119-141 | 极低 | 极低 | **P1** |
| U7 | 聊天页 | 发送禁用态纯颜色：渐变变灰 + 图标变淡（InputBar.kt:287/292），无图标/文案差异 | 轻微 | InputBar.kt:287-292 | 低 | 低 | P2 |
| A1 | 消息项 | 消息操作图标触控区 30dp（ActionIcon/ActionIconPainter），低于 44dp 主操作标准；同文件 ActionPill 28dp 高 | 误触 | MessageItem.kt:337-354 | 低 | 低（行高 +14dp） | **P1** |
| A2 | 输入栏 | 移除图片触控区 28dp（视觉 20dp）——次级操作，注释自认权衡（158） | 误触 | InputBar.kt:157-162 | 低 | 中（与图片点击抢热区） | P2（已知取舍，本轮不动） |
| M1 | 全局 | Motion tokens 存在（Tokens.kt:40-49：90/160/240）但导航过渡硬编码 280/240/160/140（AppRoot.kt:81-82）、260/220（SettingsScreen.kt:99-101） | 不一致（非 bug） | Tokens.kt:40-49 | 低 | 低 | P3（本轮不动） |
| M2 | 消息流 | 全仓 0 处 `animateItem`，新消息瞬时出现；流式靠 50ms DeltaBuffer 节流 + live 气泡隔离，无每 token 布局动画问题 | 体验偏好 | grep animateItem=0；ChatController.kt:729-773 | 低 | 中（滚动锚点） | P3（本轮不动） |
| M3 | 性能 | MarkdownReveal 每帧以增长 substring 重设 `remember(text)` → 逐帧重解析尾段（MarkdownView.kt:74-89，注释称分段缓存缓解） | 长回复低端机风险 | MarkdownView.kt:74-89 | 中 | 中 | 观察项，本轮不动 |
| T1 | 测试 | `UsageStatsCoreTest` 不存在（任务文档点名有误）；实际是 `UsageReportTest`(11) / `UsageEntryTest`；账单聚合口径无单测保护 | 回归风险 | grep 全仓 | 低 | 低 | **P1** |
| T2 | 测试 | `FileTextTest` 同名两份（data/files 10 例 + ui 8 例）——本身是卫生问题 | 维护混乱 | 两个路径 | 低 | 低 | P3（本轮不动） |
| T3 | 测试 | Flex 用例任务文档要求的 `withFlex` 往返 / provider 回落 / 弹层重开保持 **已存在**（FlexVariantTest 7 + FlexPersistTest 3，2026-11-21 加），勿重复 | — | 两个测试类 | — | — | 已覆盖 |
| A3 | 抽屉 | 「新对话」按钮硬编码 `Color(0xFF18181B)` + White（ConversationDrawer.kt:125-141）；tone 色 0xFF16A34A/0xFFC27803 硬编码（MessageItem.kt:229-230） | 未来浅色皮肤逃逸点 | 两处 | 低 | 低 | P3（本轮不动） |
| U8 | 生图 | 生成中无取消按钮；busy/error/notice/results 裸 remember，进程死亡画廊全丢 | 中 | ImageScreen.kt:86-92,333-347 | 中 | 中 | P3（本轮不动） |

## 2. Top 5 实施清单（本轮落代码范围）

> 排序依据 = 任务文档 WP2 优先序「发送失败不丢输入 > 防重复发送 > 错误可重试 > 空态/加载态 > 动画统一 > 触控区」。
> 防重复发送已核实有三道闸（InputBar.kt:224 canSend / :275 按钮变停止 / ChatController.kt:467 isStreaming 守卫），**无需新增**，不计入 Top 5。

### ① 发送失败不丢输入（U1）· `ui: restore failed send input`
- **为什么**：P0 唯一「会丢用户数据」项；失败后原文在历史里但没有入口拿回来。
- **最小改法**：`ChatController.restoreInputOf(assistantId): String?`（读当前会话、找失败助手消息**前一条 USER 消息**的 content，找到 toast「已放回输入框」）；`MessageActions` 加 `onRestoreInput`；失败气泡内联「放回输入框」胶囊 → ChatScreen 把返回值写进 `input`。纯展示层 + 只读 helper，不动发送状态机。
- **涉及文件**：MessageItem.kt（MessageActions + 错误块）、ChatController.kt（只读 helper）、ChatScreen.kt（接线）。
- **验收**：单测 3 例（找到/无前置 USER/会话缺失）；androidTest 点击胶囊回调触发。
- **回滚**：revert 单个 commit，无数据迁移。

### ② 失败气泡内联重试 + 错误态样式（U2）· 与①同一 commit
- **为什么**：重试能力已存在（操作栏「重新生成」）但可发现性差；失败轮就地在错误旁给「重试」。
- **最小改法**：错误块包进淡红底 Surface（error 8% + 边框 25%），内联 `重试`（→ `a.onRegenerate`，走既有 `planRegenerate`，失败-空-单版本路径自动直发）与①的胶囊；testTag：`msg-error` / `retry` / `restore-input`。
- **验收**：androidTest 断言失败消息渲染出两个胶囊且回调触发；「⚠ + 文字」非纯颜色。
- **回滚**：同①。

### ③ 思考胶囊回滚（U3）· `ui: thinking pill no optimistic state without conversation`
- **为什么**：这是 #121 自己的审计发现——乐观 UI 在被拒绝时撒谎。
- **最小改法**：`ThinkingLevelRow(level, onPick, enabled=true)`：`enabled=false` 时点击不翻 `picked` 只回调（落到 setThinkingLevel 的 toast）；ChatScreen 传 `thinkingEnabled = conv != null`。
- **涉及文件**：ModelPicker.kt、ChatScreen.kt。
- **验收**：androidTest：enabled=false 点击后胶囊仍显示原档位。
- **回滚**：revert 单个 commit。

### ④ PluginErrorDot 语义（U6）· `a11y: plugin error dot semantics`
- **最小改法**：可点 Box 加 `semantics { role = Role.Button; contentDescription = "插件错误 N 条，点按查看" }`。
- **验收**：androidTest 断言 contentDescription 存在；TalkBack 手动项写入报告（无设备则记未执行）。

### ⑤ 消息操作图标 44dp 触控区（A1）· `a11y: 44dp hit area for message actions`
- **为什么**：消息操作是高频主操作，30dp 低于标准；沿用 InputBar「触控区大于视觉」的既有模式（InputBar.kt:158 注释）。
- **最小改法**：`ActionIcon` / `ActionIconPainter` 的 Box 30dp→44dp（图标视觉 16/15dp 不变），clip 圆角随盒。
- **验收**：androidTest 断言操作按钮可点；人工核对操作栏不溢出（有 horizontalScroll 兜底）。
- **回滚**：revert 单个 commit。

## 3. 本轮明确不动（记入报告「未做项」）

U4/U5（模型弹层 ✓ 精修与落盘失败反馈——等 flex 新包跑出真机数据再动）、U7（禁用态非颜色提示）、M1/M2/M3（动效统一与 MarkdownReveal——性能红线区，不碰）、A2（remove-image 28dp 次级操作取舍）、A3/T2（硬编码色与同名测试类卫生）、U8（生图取消与状态存续——中成本，排下周末候选）。

## 4. 测试缺口 → WP5 计划（≥20 单测 + ≥5 UI 测试，不重复已有 669 例）

- 单测：`restoreInputOf` 3 例、UsageReport/UsageEntry 聚合与口径 3-4 例、ChatController 失败重试不重复建消息 2-3 例、ChatUiState 状态转换 2-3 例、TokenEstimator 边界 3 例、FileText 分块边界 3 例、其余补缺到 ≥20。
- UI（androidTest，仿 AppRootUiTest 无头约定）：失败气泡两胶囊、模型弹层 Flex 单价与重开播种、思考胶囊 enabled=false 不变蓝、PluginErrorDot 语义、插件错误空态隐藏清空按钮。
