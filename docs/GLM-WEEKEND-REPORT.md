# EXPERIENCE-GUARD · 周末报告（2026-11-21/22）

> 基线：开工 `main @ 5eb5bc8`（= origin/main，已 fetch 确认）→ 分支 `feat/weekend-experience-guard`
> 收口：分支 HEAD 见「测试证据」一节 · 任务书：GLM-WEEKEND-TASK-2026-11-21.md
> 原则：只写可复现事实；没跑/没验的如实标注。

## 1. 结论

本轮完成了**只读审计 → Top 5 实现 → 测试保护网 → 对抗审查**全链：修复 3 个体验缺陷（发送失败丢输入、失败气泡无可就地恢复入口、思考胶囊被拒绝时撒谎），补 2 处 a11y（PluginErrorDot 无语义、消息操作图标 30dp 触控区），新增 **20 个单测 + 6 个 Compose UI 测试**（669 → **689** 例 0 失败）。计费主逻辑、默认值、缓存口径零改动。

## 2. 改动清单（7 个 commit）

| commit | 内容 |
|---|---|
| `docs: weekend experience audit` | 只读审计：17 项发现 + Top 5 实施清单（GLM-WEEKEND-AUDIT.md） |
| `ui: thinking pill no optimistic state without conversation` | 思考胶囊：无对话时点击只 toast 不乐观变蓝（U3，#121 自查发现） |
| `ui: recover failed sends with inline retry and input restore` | 失败气泡：淡红壳 + 错误文字 + 内联「重试 / 放回输入框」；`ChatController.restoreInputOf`（只读 helper）；消息操作图标触控区 30→44dp |
| `a11y: plugin error dot semantics` | PluginErrorDot 加 Role.Button + contentDescription |
| `test: add experience-guard test net (20 unit + 6 ui cases)` | 测试保护网（见 §4） |
| `docs: weekend experience report` | 本文 |

生产代码改动仅 5 个文件、全在 `ui/chat/`：ChatController（+11 行只读 helper）、ChatScreen（+2 行接线）、MessageItem、ModelPicker、PluginErrorsPanel。

## 3. 体验提升（用户能感知到的）

1. **发送失败不再丢输入**：失败气泡旁直接有「放回输入框」，一键把原文拿回来重发/改发。
2. **失败就地可重试**：错误文字旁就是「重试」，不用去下面操作栏找「重新生成」。
3. **错误一眼可辨**：失败轮有淡红底壳 + ⚠ + 文字（此前是无壳裸红字，失败且无正文时什么都没有）。
4. **思考等级不再"假选上"**：没打开对话时点档位只有 toast，胶囊不会蓝着骗你。
5. **消息操作不再误触**：复制/编辑/更多触控区 30→44dp；TalkBack 能读出插件错误条数。

## 4. 测试证据

命令（Windows Git Bash，等价任务书的 gradlew.bat）：

```
gradlew --no-daemon :app:testDebugUnitTest          → BUILD SUCCESSFUL，689 例 / 0 失败（基线 669 + 新增 20）
gradlew --no-daemon :app:assembleDebug              → BUILD SUCCESSFUL
gradlew --no-daemon :app:compileDebugAndroidTestKotlin → BUILD SUCCESSFUL（androidTest 编译通过）
git diff --check                                    → 干净
```

单测新增明细：`restoreInputOf` 5 分支、草稿 setter、流式中 regenerate 守卫；UsageStatsCore 的 record 去重/裁剪/不可变、makeEntry cache 语义、reconcileEntry 写 calib（**此前 record/reconcileEntry 零覆盖**）；TokenEstimator 边界（openrouter 子串陷阱、渐进式 JPEG SOF2、零尺寸、空白档）；autoBudget 窗口来源（未知模型 64k、meta.context 覆盖）。

**未执行（如实记录）**：`:app:connectedDebugAndroidTest` —— 本机 `adb devices` 无设备/模拟器，环境不具备。UI 测试已通过编译验证，真机/模拟器执行步骤：接设备后 `gradlew --no-daemon :app:connectedDebugAndroidTest`，或按 PLAN-WEEKEND-2026-11-22 §3.1 装包后手工走 ASSESS §2 的真机验收区。

## 5. 手动验收

未执行（无设备）。留给吴的 smoke 清单（装新包后 10 分钟）：

1. 发一条必然失败的请求（断网/错 Key）→ 失败气泡有淡红壳、「重试」「放回输入框」两个入口可用，重试不再弹确认框（失败-空-单版本路径直发）。
2. 「放回输入框」→ 输入框恢复原文。
3. 无对话状态打开模型弹层点思考档位 → 只 toast，胶囊不蓝。
4. 顶栏插件错误红点 → TalkBack 读出「插件错误 N 条」。
5. 消息操作（复制/编辑/更多）点击不再需要瞄准。

## 6. 未做项（审计已记录、本轮不做，含原因）

| 项 | 原因 |
|---|---|
| U4/U5 模型弹层 ✓ 容错精修、落盘失败反馈 | 等 flex 新包真机数据再动，避免在没数据时精修 UI 状态 |
| U7 发送禁用态非颜色提示 | 禁用态不是错误态，颜色 + 图标变淡已够；改动收益低 |
| M1 导航过渡统一到 Motion tokens / M2 animateItem | 动效偏好类，动它们有滚动锚点风险，本轮红线不碰 |
| M3 MarkdownReveal 逐帧重解析 | 性能红线区（流式高频路径），需要 profile 数据支撑，单独排 |
| A2 移除图片 28dp | 次级操作 + 与图片点击抢热区的既有取舍（InputBar.kt:158 注释），不强行放大 |
| A3/U8 硬编码色、生图取消与状态存续 | 下周末候选，见 §7 |
| WP5 的 FileText/ChunkRetrieval 边界 | ChunkRetrievalTest 已覆盖空查询/超长/无命中（15 例），新增空间不足 20 例配额，改投 UsageStatsCore（此前零覆盖） |

## 7. 风险与下一步

- **风险**：①内联重试走既有 `planRegenerate`，非最后一轮的失败消息会弹确认框（行为正确但文案对"重试"语境略长）；②UI 测试未经真机执行，首跑可能有无头环境适配小修（仿 AppRootUiTest 的加固约定写的，风险低）。
- **下周末建议**：B2 落代码（设计已备好）、真机全量验收 + instrumented 接入 CI 常规、U4/U5 模型弹层精修、ImageScreen 状态存续。

## 8. 基线 SHA

- 开工：`5eb5bc85700b74596f74d23ef34399318c225864`
- 收口：本分支 HEAD（推送后以 PR 头 commit 为准）

**最终判据自查**：用户能感受到的提升 = §3 的 5 点 ✓；自动化保护 = 失败恢复路径、Flex、预算窗口、账本口径共 +26 例 ✓；没做的 = §6 全部给了原因 ✓。
