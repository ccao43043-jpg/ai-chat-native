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

CI 见 `.github/workflows/android.yml`：每次推送编译 + 跑单元测试，失败上传报告。

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
- 附件：PDF / DOCX / PPTX / XLSX 文本抽取（40MB 上限）
- 联网搜索（Tavily / Bocha / Brave / SearXNG）
- 本地资料库（BM25F + MMR 检索，注入到当前消息）
- 用量统计：区间汇总、按模型、最近 14 天，费用按价目表实时计算
- 生图：OpenAI 兼容 / OpenRouter / FLUX 档位模型，尺寸档位、结果预览
- 缓存插件（gcache / mmcache）、Token 徽章
- 旧版数据就地迁移、整库备份导出 / 导入

## 还不能用的

- RP（角色扮演）状态面板、章节摘要、世界书面板
- 好感度（affect）插件未接入请求链路
- 生图的参考图上传、历史落盘、保存到相册
- 成本与余额页面（顶栏只显示 token）
- 插件设置界面（各插件的开关目前走数据文件）

## 数据

- 业务数据：`/data/data/com.aichat.app/files/store/`（settings.json、convs.json、conv_<id>.json、
  usage-stats.jsonl、personas/templates/prompts/worlds.json、rplog_*、knowledge_* 等）
- 密钥：SharedPreferences `aichat_secrets`，Keystore 别名 `aichat_secrets_v1`（AES-256-GCM）
- 包名固定 `com.aichat.app`：从旧版**覆盖安装**时原地继承数据，不做搬家

## 状态

构建与单元测试在 CI 上通过；功能对齐仍在进行。待办见 [`docs/ROADMAP.md`](docs/ROADMAP.md)。
