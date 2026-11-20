# ai-chat-native

AI 聊天 Android 应用的原生实现（Kotlin + Jetpack Compose）。多服务商（OpenAI 兼容 / Claude / Gemini），
API Key 只存在本机（Android Keystore 加密），不经过任何中间服务器。

本仓库是 [ai-chat-app](https://github.com/ccao43043-jpg/ai-chat-app) 原生重写线的干净起点：
**不含** WebView 前端、**不含**旧 JS 测试，历史从一次初始提交开始。旧仓库保留原样，作为行为基准
（`docs/NATIVE_REWRITE_SPEC.md` 里逐条记录了以 JS v0.28.7 为准的语义）与 golden 数据来源。

## 构建

```bash
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:assembleDebug
```

- AGP 8.5.2 / Gradle 8.7 / JDK 17
- compileSdk = targetSdk = 34，minSdk = 24
- Kotlin 2.0.21 + Compose BOM 2024.09.03，OkHttp 4.12.0，kotlinx-serialization 1.7.3
- 不引入 Room / Hilt / KSP（构建内存与启动速度优先）

CI 见 [android.yml](.github/workflows/android.yml)：main 推送/PR 跑单测与 debug/release 编译；
[instrumented.yml](.github/workflows/instrumented.yml) 跑模拟器测试（触发范围见工作流）。CI 临时证书产物不作为用户覆盖安装包。
发布构建与签名见 [RELEASE.md](docs/RELEASE.md) / [SIGNING.md](docs/SIGNING.md)。

## 目录

```
app/src/main/java/com/aichat/app/
  data/     仓库、codec（无损往返）、conv-log、迁移、备份、密钥存储
  domain/   契约、模型、cost（用量统计）、rp（角色卡）
  engine/   DefaultChatEngine（流式、重试、搜索、插件钩子）
  net/      三协议请求构造与流式解析、路由、搜索、重试、预设
  ui/       Compose 界面（chat / conversation / library / settings / image）
app/src/test/          单元测试 + golden 对照（纯 JVM，不依赖模拟器）
```

## 能用的

- 多服务商：OpenAI 兼容（含 OpenRouter / DeepSeek / 硅基流动 / 百炼等预设）、Anthropic Claude、Google Gemini
- 流式回复、停止（保留半截内容并标记「已中断」）、重新生成 / 继续生成 / 编辑重发 / 多版本切换
- 对话列表与抽屉、助手库、提示词库与模板、模板系统提示词
- 附件：PDF / DOCX / PPTX / XLSX 文本抽取（40MB 上限）；长文档分块检索（入库上限 120k 字符，BM25F 挑选相关片段注入，`files.chunkRetrieval` 可关）
- 联网搜索（Tavily / Bocha / Brave / SearXNG）
- 本地资料库（BM25F + MMR 检索，注入到当前消息）
- 用量统计：区间汇总、按模型、最近 14 天，费用按价目表实时计算
- 生图：OpenAI 兼容 / OpenRouter / FLUX 档位模型，尺寸档位、结果预览
- 缓存插件（gcache / mmcache）、Token 徽章
- 旧版数据就地迁移、整库备份导出 / 导入；聊天图片外置存储（`img:<sha1>` 引用 + `store/blobs/` 去重，旧图懒迁移，备份含 blobs）

## 已接入的进阶功能与边界

截至 **2026-11-20 PST**，main `5e4502a` / [v1.7.9](https://github.com/ccao43043-jpg/ai-chat-native/releases/tag/v1.7.9)：

- RP 分层设定、章节/世界书/结构化记忆面板，好感度插件，请求链与插件设置入口均已接入；用量与余额页面也已接入。
- 生图有参考图上传与保存相册入口；结果历史仅页面会话内存，尚未持久化。
- 角色生成已有「有世界只补角色卡」的服务层与首条消息提示条；三档设置、RP 面板独立入口及完整交互闭环仍未做完。
- 上下文统一召回代码已落地，但 `recall.unified` 默认关闭；不能把可选新路径宣传为默认行为。
- OpenRouter Flex、按模型保存思考等级、消息操作栏横向滑动已进入当前版本。

以上是代码接入/发布状态，不代表所有真实服务商、真机与 release 场景都已验收。
当前剩余项与优先级见 [总计划](docs/MASTER-PLAN.md)，证据及边界见 [本轮审计](docs/ASSESS-2026-11-20.md)。

## 数据

- 业务数据：`/data/data/com.aichat.app/files/store/`（settings.json、convs.json、conv_<id>.json、
  usage-stats.jsonl、personas/templates/prompts/worlds.json、rplog_*、knowledge_* 等）
- 密钥：SharedPreferences `aichat_secrets`，Keystore 别名 `aichat_secrets_v1`（AES-256-GCM）
- 包名固定 `com.aichat.app`：同证书且 versionCode 不降时可覆盖并保留数据；旧版不同签名不能仅凭包名覆盖，先看 [签名说明](docs/SIGNING.md)，不要为尝试升级直接卸载

## 状态

当前入口：[文档索引](docs/README.md) → [唯一总计划](docs/MASTER-PLAN.md)。`ROADMAP.md` 已归档，不再作为待办来源。
