# AI Chat Native 1.8.0 工作交接（2026-11-23）

## 1. 当前交接点

- 仓库：`C:\Users\Administrator\Documents\Codex\2026-11-21\bnag\work\ai-chat-native`
- 分支：`release/1.8.0`
- 功能实现提交：`af21539`（`feat(1.8.0): Flex 即时生效、安全拦截状态、旁路成本与分批角色书`）`n- 交接文档：以远端 `release/1.8.0` 当前 HEAD 为准
- 远端分支：`origin/release/1.8.0`
- 远端基线：`eb0cb42`，其代码树与 `origin/main` 的 `2e7794d` 一致
- 版本：`versionCode 180` / `versionName 1.8.0`
- 发布动作边界：只交付 APK；不自动打 Git tag，不自动创建 GitHub Release

本次已完成源码提交并推送。原有未跟踪文件继续保留，未纳入提交：

- `docs/GLM-WEEKEND-TASK-2026-11-21.md`
- `docs/PLAN-COST-OPTIMIZATION-WEEKEND-2026-11-21.md`
- 根目录 0 字节文件 `$null`

## 2. 完成度口径

不要用一个百分比混合表达。当前应分别记录：

| 口径 | 状态 | 说明 |
|---|---|---|
| 代码完成 | 核心 1.8 改动已实现 | Flex、安全拦截、旁路成本入账、AutoChar S3、分批角色书、动画与前端状态已接线 |
| Debug 编译 | 通过 | `:app:compileDebugKotlin` 成功 |
| 既有单元测试 | 通过 | `:app:testDebugUnitTest` 成功；新增专项覆盖仍待补 |
| Lint | 未执行 | 尚未运行 `:app:lintDebug` |
| Release 构建 | 未执行 | 当前仓库没有本地 `keystore.properties`，不得用 debug 证书冒充正式包 |
| APK 验签 | 未执行 | 尚未产出正式 APK |
| 真机验收 | 未执行 | 无设备/AVD 结论记录；不能写成“真机通过” |
| 已发布 | 否 | 没有 tag、GitHub Release 或正式安装包交付 |

准确表述：**代码已推送，Debug 编译和既有单测通过；Release、验签、真机验收与发布均未完成。**

## 3. 本轮已实现内容

### 3.1 Flex 统一生效

- `ProviderRules.effectiveModel(baseModel, flexEnabled)` 成为 OpenRouter 模型后缀的唯一规范化入口。
- 最多保留一个 `:flex`，先清理重复后缀再统一添加；非 OpenRouter Provider 保持原模型。
- 开关会同时更新当前模型、`lastModel` 和 Provider 偏好，重启后可恢复实际状态。
- 新建、发送、重新生成、续写和插件旁路统一经过该入口。
- 顶栏显示“实际使用：xxx:flex”，并加入轻量切换动画。
- 加载旧会话时修复历史重复后缀及开关与模型不一致问题。

### 3.2 安全拦截成为独立结果

- 将 `PROHIBITED_CONTENT`、`content_filter`、`SAFETY`、`BLOCKED` 等结束原因归一为 `FinishReasons.SAFETY_BLOCKED`。
- 引入统一 `TurnOutcome`：`success | stopped | truncated | safety_blocked | error`。
- 安全拦截即使 HTTP 2xx 也不计入成功率，不自动重试、不自动换模型、不继续生成相同内容。
- 保留已生成部分为草稿，消息区显示可折叠信息条“内容安全策略拦截，草稿已保留”。
- 详情区说明是否记录有效用量、上游可能已计费、修改后可主动重新发送。
- 没有实现“绕过继续”按钮、拆字、编码、同义替换、切片重发或提示词注入等审核规避。

### 3.3 旁路请求成本入账

- 非流式 `requestText()` 返回结构化结果：`status / text / usage / finishReason`。
- 摘要、记忆、搜索改写、AutoChar、角色书等旁路请求读取 OpenAI 兼容 usage。
- 记录输入、缓存输入、输出、实际费用和任务类型；失败、超时、空响应不得伪造费用或成功次数。
- 任务类型固定为：`chat / image / summary / memory / search / autochar / character_book`。
- 流式聊天与非流式旁路共用记账入口，避免漏记和重复记。
- 总成本只读取 `cost_total`；缓存命中率单独展示，不把 `cost_cache` 负数节省混入总成本。
- 各旁路任务使用集中配置的低思考/低输出预算，Provider 不支持 reasoning 参数时安全回退。

### 3.4 AutoChar S3 与三档模式

- 设置页提供 `off / ask / auto`：
  - `off`：不自动询问，RP 面板仍可手动生成。
  - `ask`：首条用户消息后显示询问条。
  - `auto`：条件满足时直接生成，不显示询问条。
- RP 面板始终提供“用 AI 生成角色卡 / 重新生成角色卡”手动入口。
- 询问状态按世界作用域保存：`worldId / asked / dismissed / completed`；换世界后重新评估。
- 生成中显示当前批次、`current/total` 进度和进度条。
- 面板分别展示已写入字段、已丢弃字段和失败批次；支持只重跑失败批次。
- `saveAutoChar()` 写回 `rpState` 和 `conv.extras.autoChar`，检查点可恢复。
- 开场白按正文去重插入 assistant 消息；不重复插入，不覆盖其他 extras。
- 默认不覆盖已有角色卡；手动重新生成需要显式 `overwrite=true`。

### 3.5 分批角色书

- 世界作用域：2 批
  - 角色卡
  - 示例对白
- 全作用域：4 批
  - 世界观 + 角色卡
  - RP 规则 + 输出格式
  - 示例对白 + 禁止事项
  - 写作风格 + 开场白
- 每批只携带本批标题、相关世界摘要和必要上下文。
- 单批失败不清空已成功字段；失败批次可用户修订后重跑。
- 开场白不混入后续批次上下文。
- 世界块保持只读，角色书批次不得改写世界设定。
- `onProgress` 在每批开始和结束时回调，调用方可即时落盘检查点。

### 3.6 动画与前端体验

- 实际模型状态切换、流式状态、安全拦截提示、角色书批次进度使用 120–200ms 轻量动画。
- 遵循系统“减少动态效果”设置；关闭动画时退化为即时状态。
- 安全提示为可折叠信息条，避免全屏弹窗打断编辑。
- AutoChar 面板使用“已写入 / 已丢弃 / 待处理 / 失败批次”明确标签。
- 未进行全仓 Compose 视觉重写，未拆分 `ChatController`、`JsConvLog`，未合并三套 `Js`，未启动全仓 lint 清理。

## 4. 已验证结果

在本轮提交前执行：

```powershell
$env:GRADLE_USER_HOME='D:\android-dev\gradle-home'
.\gradlew.bat --no-daemon --offline --console=plain :app:compileDebugKotlin
.\gradlew.bat --no-daemon --offline --console=plain :app:testDebugUnitTest
```

结果：

- `compileDebugKotlin`：`BUILD SUCCESSFUL`
- `testDebugUnitTest`：`BUILD SUCCESSFUL`（约 1 分钟）
- 仍存在非阻断警告：
  - `ChatScreen.kt` 约 550/561 行有“条件始终为 true”提示，可后续简化。
  - 若干 unchecked cast 警告，不影响编译。

注意：`testDebugUnitTest` 是既有测试集通过，不等于 1.8 新增专项覆盖已经齐备。

## 5. 尚未完成的工作

### 5.1 测试补齐

1. `AutoCharServiceTest`
   - 当前 helper 仍是一次请求返回固定回复；建议改成回复队列，验证每批独立输入输出。
   - 需覆盖单字段批次解析、开场白解析、额外标题过滤、部分批次失败保留成功字段。
   - 需覆盖 `onProgress` 检查点、`only` 只重跑失败批次、开场白不混入后续批次。
   - 需覆盖已有角色不覆盖、`overwrite=true` 覆盖、后续失败不清空前面成功字段。

2. 新增 `AutoCharControllerTest`
   - `rpState` 写回。
   - `conv.extras["autoChar"]` 写回。
   - 开场白插入 assistant 消息。
   - 重复开场白不重复插入。
   - 其他 extras 不被覆盖。
   - 测试结束必须取消协程 Job。

3. Compose / androidTest
   - `model-in-use` 实际模型标签。
   - 安全提示折叠与 `safety-banner-toggle`。
   - 设置页 `autochar-mode-off/ask/auto`。
   - RP 面板 `rolebook-generate` 手动入口。
   - `rolebook-progress`、`rolebook-applied`、`rolebook-dropped`、`rolebook-failed`。
   - 换世界后的重新询问行为。

### 5.2 静态质量门

待执行：

```powershell
$env:GRADLE_USER_HOME='D:\android-dev\gradle-home'
.\gradlew.bat --no-daemon --offline --console=plain :app:lintDebug
```

要求：1.8 范围内不得新增 lint 错误。当前尚未执行，不能声称静态质量门通过。

### 5.3 Release 构建与验签

当前 `keystore.properties` 不在仓库中。正式构建前必须在本地创建该文件，并确保它被 `.gitignore` 忽略。文档中不得写入密码明文。

固定证书信息：

- keystore：`D:\android-dev\keystore\ai-chat-release.jks`
- alias：`aichat`
- 证书 SHA-256：`A2:28:0D:C1:59:AC:F5:58:A5:AB:D0:19:A3:EF:A0:07:92:28:6D:6F:D7:FE:E0:71:C4:F5:EC:E2:E8:AC:2F:52`
- 密码：由用户本地安全提供，见 `docs/SIGNING.md`

`keystore.properties` 格式：

```properties
storeFile=D:/android-dev/keystore/ai-chat-release.jks
storePassword=<本地提供>
keyAlias=aichat
keyPassword=<本地提供>
```

构建：

```powershell
$env:GRADLE_USER_HOME='D:\android-dev\gradle-home'
.\gradlew.bat --no-daemon --offline --console=plain :app:assembleRelease
```

验收：

- `apksigner verify --print-certs` 核对固定证书。
- `aapt dump badging` 确认包名 `com.aichat.app`、`versionCode 180`、`versionName 1.8.0`。
- 输出 APK SHA-256。
- 交付路径：
  - `app/build/outputs/apk/release/ai-chat-native-1.8.0.apk`
  - `dist/ai-chat-native-1.8.0.apk`

缺少签名凭据时必须停止，不得以 debug 证书替代正式包。

### 5.4 真机验收

设备可用时执行：

```powershell
adb install -r app/build/outputs/apk/release/ai-chat-native-1.8.0.apk
```

至少检查：

- 启动、登录/Provider 配置。
- Flex 开关即时切换与重启恢复。
- 流式聊天、停止、重新生成、续写。
- 安全拦截提示、草稿保留、详情折叠。
- AutoChar `off / ask / auto` 三档行为。
- RP 面板手动生成、重新生成、只重跑失败批次。
- 角色书分批中途失败恢复，重启后检查点恢复。
- 换世界后询问状态重新评估。

无设备或 AVD 时只能记录：“构建与验签通过、真机验收待完成”。

### 5.5 发布文档

还需创建 `docs/RELEASE-1.8.0-PLAN-2026-11-23.md`，合并此前问题、成本优化、周末任务和本轮改动，并分别记录：

- 代码完成
- 自动化通过
- 构建验签
- 真机验收
- 已发布

文档中需保留可复制给 GLM / ZCode 的审计、实现和对抗审查提示词附录，并注明外部生成代码和测试必须由主执行方复核后合入。

## 6. 项目仍未完成的主账

1.8 发布不等于整个项目全部完成。仍需关注：

- B2 接线之外的持续验证与线上观测。
- B4 路由观测。
- 静态质量门和新增测试覆盖。
- 数据保护、备份恢复和迁移回归。
- 真实设备与 release 运行验收。
- 成本策略上线后的真实账单对照与缓存命中率评估。
- 安全拦截统计口径和误拦截率需要真实样本验证。

## 7. 安全与合规边界

本项目的安全改动只允许改善**正常内容**的生成体验：

- 允许字段级短批次、必要上下文裁剪、检查点恢复和保留草稿。
- 允许用户看到失败原因后自行修改并重新发送。
- 禁止拆字、编码、同义替换、切片重发违规内容、提示词注入、自动换模型反复重试。
- 禁止任何“绕过审核”“强制继续”按钮或实现。

后续接手者不得把“降低误拦截”扩展成审核规避。

## 8. 建议的下一步顺序

1. 补 `AutoCharServiceTest` 回复队列与批次级断言。
2. 新增 `AutoCharControllerTest`。
3. 补 Compose 测试；无设备时只声明测试代码完成，不声明真机通过。
4. 运行 `:app:lintDebug`，修复 1.8 范围内新增问题。
5. 本地配置 `keystore.properties`，执行 `:app:assembleRelease`。
6. `apksigner` / `aapt` / SHA-256 验收并复制到 `dist/`。
7. 设备可用时做真机 smoke；不可用则如实记录待完成。
8. 创建 `docs/RELEASE-1.8.0-PLAN-2026-11-23.md` 并回写五项状态。
9. 发布动作止于 APK 交付；tag 与 GitHub Release 需用户另行明确授权。