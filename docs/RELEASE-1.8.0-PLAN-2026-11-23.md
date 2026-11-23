# 1.8.0 发布计划与验收记录

> **状态**：🟡 **构建与验签完成 · 真机验收未做 · 未发布**
> **撰写**：2026-11-24（UTC+8）· 承接 [HANDOVER-1.8.0-2026-11-23.md](HANDOVER-1.8.0-2026-11-23.md) §5.5；本轮执行 lint / Release / 验签 / 模拟器 smoke
> **基线**：`release/1.8.0 @ 29a349f`（相对 `origin/main @ 2e7794d`：25 文件，+1365 / −157）
> **口径**：代码完成 / 自动化通过 / 构建与验签 / 真机验收 / 已发布 —— **五项分开记，不合并成一个百分比**。

---

## 0. 一页结论

| # | 口径 | 状态 | 证据 |
|---|---|---|---|
| 1 | 代码完成 | ✅ | `af21539` 已推送；`43393ff` / `29a349f` 为交接文档 |
| 2 | 自动化通过 | ✅ | 单测 **698 例 / 0 失败 / 0 错误 / 0 跳过**；lint 无新增（87 = main 基线 87） |
| 3 | 构建与验签 | ✅ | `assembleRelease` 成功；证书 SHA-256 `a228…2f52` 与固定证书一致；`com.aichat.app` / 180 / 1.8.0 |
| 4 | 真机验收 | ❌ **未执行** | 无一连接设备；本轮只做模拟器 smoke，**不等于真机通过** |
| 5 | 已发布 | ❌ **未发布** | 无 git tag、无 GitHub Release；只产出 APK |

**一句话**：1.8.0 可以构建出可覆盖安装的正式签名包，静态与自动化证据齐；**用户可见的关键路径（真实模型请求、安全拦截、AutoChar、角色书批次）还没有在真机上验过**，因此不能宣称"1.8.0 已验收"。

---

## 1. 产物

| 项 | 值 |
|---|---|
| 交付路径 | `dist/ai-chat-native-1.8.0.apk`（源：`app/build/outputs/apk/release/app-release.apk`） |
| 大小 | 2,366,858 字节（≈2.26 MiB） |
| SHA-256 | `d804fc8e8510fea1dad460ef245b9a5fccf3e487017c9424da5c6c1f86f7dcc8` |
| 包名 | `com.aichat.app` |
| 版本 | `versionCode 180` / `versionName 1.8.0` / `compileSdk 34` |
| 签名证书 | `CN=ai-chat-native, OU=Android, O=ai-chat, L=Beijing, ST=Beijing, C=CN` |
| 证书 SHA-256 | `A2:28:0D:C1:59:AC:F5:58:A5:AB:D0:19:A3:EF:A0:07:92:28:6D:6F:D7:FE:E0:71:C4:F5:EC:E2:E8:AC:2F:52` |
| 证书 SHA-1 | `E2:74:E2:01:1F:49:20:DE:6C:5F:76:EC:B5:44:BA:4B:0D:16:A8:66` |
| R8 | 已启用（`minifyReleaseWithR8` / `shrinkReleaseRes` 执行） |

证书 SHA-256 与 [SIGNING.md](SIGNING.md) 记录的固定证书一致，满足"覆盖安装不卸载旧版"的前提。

`*.apk` 被 `.gitignore` 忽略，产物不进版本库；`dist/` 只在本机交付。

---

## 2. 构建环境

| 项 | 值 |
|---|---|
| JDK | Temurin 17.0.21+9（`D:\android-dev\jdk\jdk-17.0.21+9`） |
| Android SDK | `D:\android-dev\sdk`（build-tools 34.0.0） |
| `GRADLE_USER_HOME` | `D:\android-dev\gradle-home` |
| AGP / Gradle | 8.5.2 / 8.7 |
| 模式 | `--no-daemon --offline --console=plain` |
| 签名配置 | `keystore.properties`（git 忽略，含密码，不入库、不写入本文） |

`local.properties`（`sdk.dir`）与 `keystore.properties` 均被 `.gitignore` 忽略，新克隆必须本地补；否则构建脚本会退化为 debug 证书，见 §7。

---

## 3. 本轮验证记录（2026-11-24）

### 3.1 编译

```powershell
.\gradlew.bat --no-daemon --offline --console=plain :app:compileDebugKotlin
```

`BUILD SUCCESSFUL in 8s`。

### 3.2 单元测试

```powershell
.\gradlew.bat --no-daemon --offline --console=plain :app:testDebugUnitTest
```

`BUILD SUCCESSFUL in 11s`；结果 XML 汇总：

```text
tests=698 failures=0 errors=0 skipped=0
```

> 交接文档记的是 PR #122 分支 689 例、main 669 例；本分支现为 **698**。

### 3.3 Lint

```powershell
.\gradlew.bat --no-daemon --offline --console=plain :app:lintDebug
```

`BUILD SUCCESSFUL in 52s`。**注意**：`app/build.gradle` 仍为 `lint { checkReleaseBuilds false; abortOnError false }`，任务成功不等于零问题，因此逐条统计并与 `main` 基线对照。

| 严重级别 | `release/1.8.0` | `main @ 2e7794d`（临时 worktree 同命令） |
|---|---|---|
| Error | **87** | **87** |
| Warning | 18 | — |
| Information | 10 | — |

**87 条 Error 全部是 `NewApi`，且两边按文件分布逐项相同**：

| 文件 | 条数 |
|---|---|
| `UsageStatsCore.kt` | 44 |
| `BlobStore.kt` | 11 |
| `FileStore.kt` | 7 |
| `Affect.kt` | 6 |
| `DefaultChatEngine.kt` | 6 |
| `JsConvLog.kt` | 4 |
| `NetRetry.kt` | 4 |
| `CharCard.kt` | 2 |
| `TokenEstimator.kt` | 2 |
| `GCache.kt` | 1 |

结论：**1.8 范围未新增 lint 错误**，交接文档 §5.2 的静态质量门判据（"1.8 范围内不得新增"）**成立**。

Warning 构成：`DefaultLocale` 12、`ApplySharedPref` 4、`MutableCollectionMutableState` 1、`DataExtractionRules` 1；Information 为 `AutoboxingStateCreation` 10。

### 3.4 Release 构建

```powershell
.\gradlew.bat --no-daemon --offline --console=plain :app:assembleRelease
```

`BUILD SUCCESSFUL in 1m 17s`（47 tasks）。`validateSigningRelease` 通过，使用固定证书；`stripReleaseDebugSymbols` 对 `libandroidx.graphics.path.so` 无法剥离，按原样打包（非阻断）。

编译告警与交接文档一致：`ChatScreen.kt:550/561` 三条 "Condition is always 'true'"，其余为既有 unchecked cast，均非阻断。

### 3.5 验签与包信息

```powershell
java -jar build-tools\34.0.0\lib\apksigner.jar verify --print-certs app-release.apk
aapt dump badging app-release.apk
sha256sum app-release.apk
```

结果见 §1。三项均符合预期：证书指纹 = 固定证书、包名与版本正确、哈希已记录。

### 3.6 模拟器 smoke（**不等于真机验收**）

| 项 | 值 |
|---|---|
| AVD | `dsh34` |
| 系统镜像 | `android-34 / google_apis / x86_64` |
| 显示 | 1080×2340 @ 440dpi |
| 安装 | `adb install -r dist/ai-chat-native-1.8.0.apk` → `Success`（覆盖安装） |
| 启动 | `MainActivity` 正常 resume，logcat 无 `FATAL EXCEPTION`，无进程崩溃 |

逐屏核对（截图后删除，未入库）：

| 屏 | 观察 |
|---|---|
| 空态 | 标题「有什么可以帮你？」、三步配置引导、「去设置 API Key」、底部「联网搜索 / 提示词 / 模板」与输入框 |
| 服务商页 | 中文标题、返回与「添加」入口正常 |
| 设置根页 | 服务商 / 对话默认参数 / 联网搜索 / 插件 / 助手库 / 角色生成（显示"直接生成"）/ 提示词库 |
| **角色生成页** | **三档「不自动 / 先询问 / 直接生成」齐全**，`直接生成` 说明"首条用户消息满足条件时直接生成，不显示询问条；换世界后会重新判断"；下方「生成方式：按字段分成 2–4 个短批次」及失败保留说明 |
| 抽屉 | 新对话 / 设置 / 插件（已启用 5）/ 生图 / 助手 / 世界 / 模板（10）/ 用量 / 余额 / 历史会话搜索 |

已验证的是**界面接线与渲染**：AutoChar 三档（S3 设置项）与分批角色书说明在 UI 上可达。**未验证**：真实模型请求、流式与停止、安全拦截样本、AutoChar 真实生成、角色书批次进度/失败重跑、换世界重询、备份迁移 —— 模拟器无 API Key，这些全部留给真机。

---

## 4. 1.8.0 改动范围（`af21539`）

| 块 | 内容 | 自动化覆盖 |
|---|---|---|
| Flex 统一生效 | `ProviderRules.effectiveModel` 单一入口；开关同步当前模型 / `lastModel` / Provider 偏好；加载旧会话清理重复后缀；顶栏「实际使用：xxx:flex」 | `FlexPersistTest`、`FlexVariantTest` |
| 安全拦截独立结果 | `FinishReasons.SAFETY_BLOCKED` + `TurnOutcome`（success/stopped/truncated/safety_blocked/error）；不计成功率、不重试、不换模型；保留草稿 + 可折叠信息条 | `TruncationTest` |
| 旁路成本入账 | 非流式 `requestText()` 返回结构化结果；任务类型 `chat/image/summary/memory/search/autochar/character_book`；失败不伪造费用；总成本只读 `cost_total` | `BypassTaskGuardTest` |
| AutoChar S3 | 设置页 `off/ask/auto`；RP 面板手动入口；按世界作用域保存 `asked/dismissed/completed`；批次进度 `current/total`；只重跑失败批次 | 覆盖未齐（见 §6） |
| 分批角色书 | 世界作用域 2 批、全作用域 4 批；单批失败不清空成功字段；开场白不混入后续批次；`onProgress` 可即时落盘检查点 | 覆盖未齐（见 §6） |
| 动画与感知 | 状态切换 120–200ms，遵循系统"减少动态效果"；安全提示为信息条而非弹窗 | 无自动化 |

**边界声明**（交接文档 §3.6 原文）：未做全仓 Compose 视觉重写，未拆 `ChatController` / `JsConvLog`，未合并三套 `Js`，未做全仓 lint 清理。安全改动**只改善正常内容生成体验**，不含任何审核规避实现。

---

## 5. 合并的既有工作（此前三条线）

1.8.0 不是孤立改动，发布时需同时交代它汇入的三条线：

| 线 | 来源 | 状态 |
|---|---|---|
| 省钱收口 T1–T4 | PR [#120](https://github.com/ccao43043-jpg/ai-chat-native/pull/120)（Flex 持久化 + 单价）、[#121](https://github.com/ccao43043-jpg/ai-chat-native/pull/121)（摘要省钱档 + T4 关账） | 代码与自动化完成；**T5 账单四指标待回填** |
| 成本守卫周末包 C1–C5 | PR [#123](https://github.com/ccao43043-jpg/ai-chat-native/pull/123)：旁路归因/分档、图片退役、自适应检索、B2 接线 | 代码完成；四个开关默认关或 `legacy`；见 [COST-OPTIMIZATION-REPORT-2026-11-22.md](COST-OPTIMIZATION-REPORT-2026-11-22.md) |
| 体验守卫 EXPERIENCE-GUARD | PR [#122](https://github.com/ccao43043-jpg/ai-chat-native/pull/122)：失败恢复入口、思考胶囊守卫、a11y、测试保护网 | 合并状态见远端；本轮未重复实现 |
| 1.8.0 发布线 | PR [#124](https://github.com/ccao43043-jpg/ai-chat-native/pull/124) 合并后，`af21539` 继续推进 Flex 即时生效与安全拦截 | 本文记录 |

T4 结论（[ASSESS-2026-11-21.md](ASSESS-2026-11-21.md)）：那条 $0.01076 全 miss 是**摘要旁路穿着主对话字号**，主对话缓存未破，不为它改代码。T5 四个硬指标（Flex 行占比 > 0、同口径命中率 ≥ 99%、$/请求 ≤ $0.0035、摘要旁路 $/次 ≤ $0.002）**仍待回填**。

---

## 6. 未完成与已知边界

### 6.1 未完成

| # | 项 | 说明 |
|---|---|---|
| 1 | `AutoCharServiceTest` 回复队列 | 现为单次固定回复，需改队列并断言每批独立输入输出 |
| 2 | `AutoCharControllerTest` | `rpState` / `extras.autoChar` 写回、开场白去重、extras 不被覆盖、结束取消 Job |
| 3 | Compose / androidTest | `model-in-use`、`safety-banner-toggle`、`autochar-mode-*`、`rolebook-*` 系列、换世界重询 |
| 4 | T5 账单对账 | 需装包 + ≥1 天样本 + 重新导出 CSV，用 `tools/billing/` 回填 ASSESS §2 |
| 5 | 真机 smoke | 至少覆盖 §3.6 里"未验证"的全部路径 |
| 6 | tag / GitHub Release | **需另行明确授权**，本轮不做 |
| 7 | 回写 [MASTER-PLAN.md](MASTER-PLAN.md) | 按维护约定把本文五项状态并入 P0/P1 |

### 6.2 已知边界（不影响本轮结论，但发布时要知道）

- **`NewApi` 87 条是存量问题**：全部落在 **API 26+ 的接口**（`java.time.*`、`java.nio.file.*`、`java.util.Base64`、`StandardCopyOption`），而 `minSdk = 24`。这意味着 **API 24/25 设备上这些路径可能抛 `NoClassDefFoundError`**。两条路线：启用 core library desugaring（当前未启用，离线缓存也没有该依赖）、或把 `minSdk` 提到 26。本轮未改，属于既有欠账，1.8.0 未加重。
- **lint 没有门**：`abortOnError false` 且 CI 的 `android.yml` 不跑 lint，所以 lint 结果只能靠人工对照基线，不能靠流水线守。
- **CI 绿 ≠ 运行验收**：CI 跑单测与编译，不覆盖真实服务商、release 变体运行时。
- **开关默认值**：C3 图片退役、C4 自适应检索默认关；`rpcache.budgetProfile` 默认 `legacy`（逐字节与旧行为一致）；`recall.unified` 默认 `false`。宣传时不能按"默认生效"讲。
- **模拟器 smoke 的局限**：无 API Key，只验渲染与导航；不是功能验收。

---

## 7. 发布动作边界

1. **只交付 APK**。不自动打 git tag，不自动创建 GitHub Release。
2. 缺少 `keystore.properties` 时必须**停止**，不得用 debug 证书冒充正式包（构建脚本会打印告警并退回 debug 证书）。
3. 覆盖安装前提：证书指纹与已装版本完全相同 + `versionCode` 不小于已装版本。本次 180 > 179，满足。
4. 真机验收未完成前，对外表述只能是"构建与验签完成、真机待验"。

---

## 8. 建议顺序

1. 补 `AutoCharServiceTest` 回复队列与批次级断言。
2. 新增 `AutoCharControllerTest`。
3. 补 Compose 测试；无设备时只声明"测试代码完成"。
4. 真机装包，按 §3.6 未验证清单逐项 smoke。
5. 触发摘要 / 记忆 / 角色生成，检查 `usage-stats.jsonl` 出现对应任务类型与实际模型。
6. 累计 ≥1 天样本后导出 CSV，用 `tools/billing/` 回填 T5 四指标。
7. 回写 MASTER-PLAN 与本文状态。
8. tag / Release 待明确授权后执行。

---

## 附录 A · 可复制给外部 Agent（GLM / ZCode）的提示词

> 通用约束（三个提示词都适用）：**外部生成的代码与测试必须由主执行方复核后合入**。不允许"看起来通过"就宣布通过；没跑的命令、没有的设备、不足的样本，一律写"未验证"。

### A.1 只读审计

```text
仓库：ccao43043-jpg/ai-chat-native（Android / Kotlin + Compose，minSdk 24）
任务：只读审计，**禁止修改任何生产代码**，禁止提交。

范围（按顺序）：
1. UI 状态：空 / 加载 / 无配置 / 生成中 / 失败 / 部分成功 是否都有明确呈现与可恢复入口。
2. 可访问性：可点击元素是否有 Role 与 contentDescription；触控区是否 ≥ 44dp。
3. 测试缺口：现有 app/src/test 与 app/src/androidTest 未覆盖的回归风险点。
4. 静态风险：是否存在 API 26+ 接口在 minSdk 24 上无守卫（已有 87 条 NewApi 存量，不要重复报，只报**新增**）。

输出格式：编号 / 文件:行号 / 现象 / 影响 / 最小改法 / 预估收益。
硬要求：每条必须给出行号；不确定的标注"未确认"，不要推测。
禁止：改代码、改默认值、开 abortOnError、批量消警告、做重构。
```

### A.2 有限实现 + 测试

```text
仓库：ccao43043-jpg/ai-chat-native
分支：从 origin/main 切 feat/<主题>
任务：只实现审计中**已被选中**的高收益项，逐项提交。

硬边界：
- 不改计费主逻辑（Flex、summary_model、provider 默认值、cost_total 口径）。
- 不拆 ChatController / JsConvLog，不合并三套 Js，不新增依赖。
- 不开 abortOnError，不批量消警告，不改 minSdk。
- 不提交密钥、Cookie、CSV 原文。
- 每个改动必须有用户可见收益或回归保护价值。

验证（必须贴真实输出，不能写"应该通过"）：
  .\gradlew.bat --no-daemon --offline --console=plain :app:compileDebugKotlin
  .\gradlew.bat --no-daemon --offline --console=plain :app:testDebugUnitTest
  .\gradlew.bat --no-daemon --offline --console=plain :app:lintDebug

提交：小写英文 conventional commit（如 test: cover autochar batch failure）。
报告：每项写 代码完成 / 自动化通过 / 未验证，缺一不可。
```

### A.3 独立对抗审查

```text
角色：独立审查者。不要修改代码，不要复用实现者的结论。
输入：分支 diff（或 PR 链接）。
目标：找出**会让用户丢数据、丢输入、误认为成功**的问题。

必查：
1. 失败路径：请求失败 / 超时 / 空响应 / 安全拦截，用户输入是否还在、状态是否如实。
2. 状态一致性：开关与模型是否同步、重启后是否恢复、旧数据加载是否清理。
3. 计费：失败是否被计为成功、费用是否被伪造、旁路是否漏记或重复记。
4. 并发：协程 Job 是否取消、重复触发是否去重、写盘是否事务化。
5. 回归：新改动是否改变了既有默认行为（尤其 rpcache / 缓存口径）。

输出：严重度（阻断 / 高 / 中 / 低）+ 文件:行号 + 触发条件 + 复现步骤。
规则：没有复现路径的判断标"推测"；不要为了凑数报风格问题。
```

---

## 附录 B · 本文的证据边界

- 本轮**重新执行**：`compileDebugKotlin`、`testDebugUnitTest`、`lintDebug`（含 main 基线对照）、`assembleRelease`、`apksigner`、`aapt`、模拟器安装与逐屏截图。
- 本轮**未执行**：真机装包、真实服务商请求、账单 CSV 对账、UI 自动化测试、备份/迁移回归、tag 与 GitHub Release。
- 交接文档（2026-11-23）中标注的完成项，本文只沿用其"代码已推送"的事实，其余结论均以本轮实际输出为准。