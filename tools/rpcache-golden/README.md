# rpcache golden 生成器

`app/src/main/assets/www/rpcache.js` 是 RP 上下文构建器的**行为基准**（见
`docs/NATIVE_REWRITE_SPEC.md`）。Kotlin 侧 `plugins/rp/RpCacheCore.kt` 是它的移植，
两者的等价性靠 golden 对照测试守住：

```
tools/rpcache-golden/gen-golden.cjs   →  app/src/test/resources/golden/rpcache/core.json
                                          ↕  RpCacheCoreTest
app/src/main/java/com/aichat/app/plugins/rp/RpCacheCore.kt
```

## 重新生成

改过 `rpcache.js`（或想让 golden 覆盖更多形状）后，在仓库根目录：

```bash
node tools/rpcache-golden/gen-golden.cjs
```

需要 Node 18+。脚本只读 `assets/www`，不联网、不写别处，输出一份 JSON。

## 什么时候必须重跑

- 改 `RpCacheCore.kt` 后测试红了：先确认是**移植错了**还是**JS 本来就变了**。
  只有后者才重跑生成器（重跑等于承认「新的 JS 行为是基线」）。
- 升级 `assets/www` 里的 JS（对齐旧版本）之后。

## 覆盖范围

| 用例 | 覆盖 |
|---|---|
| `f1` | 空状态：system / 分段 / 稳定前缀 / 末轮拼接 / Token 估算 / 历史序列化 |
| `f2` | 全静态块 + 两个章节 + 长期记忆 + 增量 + 状态：逐字比对 system 与末轮消息 |
| `capability` | 6 个服务商 × 7 个模型：vendor / kind / auto / 上下文窗口 / 价格档 |
| `budget` | 按上下文窗口推导预算（DeepSeek 走固定默认值） |

未覆盖（也尚未移植）：章节摘要生成 / 合并、世界书触发与冻结、lagrangian 裁剪、
指纹与诊断日志、面板 UI。
