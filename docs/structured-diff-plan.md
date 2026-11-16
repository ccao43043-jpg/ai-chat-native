# 2.2 去字节对齐：结构化 diff 迁移方案

> 对应 `docs/REVIEW-2026-11-16.md` §2.2。本方案只描述改法与验收，不改代码。
> 目标版本：v1.3.x（2–3 个 PR）。

---

## 1. 现状（为什么有这两段逻辑）

`FileChatRepository.saveLocked()`（`data/repo/Repositories.kt:98-115`）每次保存都：

```kotlin
var json = ConversationCodec.encode(c)          // 全量编码整个对话
val prev = codec.engine.session(store, c.id)?.last ?: codec.loadJs(fs, c.id)
if (prev != null) {
    json = KeyOrder.align(json, Js.toJson(prev))   // 键序对齐上次结果
    json = nullRemovedMessageKeys(json.jsonObject, prev)  // 补回「上次有值、这次缺键」的 null
}
codec.commitJs(fs, Js.fromJson(json) as JsObj)
```

`JsConvLog.commit()`（`data/convlog/JsConvLog.kt:863-900`）再用 `diff(s.last, conv)` 生成 op，
整段重写 `conv_<id>.log.jsonl`（`existing + applied`），达到 200 op / 1 MB 时压缩。

这两段是「与旧 JS 逐字节对齐」的产物：
- **KeyOrder.align**：让 `JSON.stringify` 判等稳定，避免每次 save 都产生无意义的 `msg.edit`
- **nullRemovedMessageKeys**：修 JS 回放 bug——`Object.assign` 合并不会删除缺键，`delete m.truncated` 后重启会复活

> **前提修正（2026-11-16）**：`Js.equal` 是**键序敏感**的——`Js.kt:359-363` 用 `stringify(a) == stringify(b)`，而 `JsConvLog.diff()` 全程用它判等。因此 `KeyOrder.align` **不能先删**，否则键序变化会被判成字段变化，oplog 立刻膨胀。正确顺序是：先把 diff 判等改成键序无关的 `Js.semanticEqual`，再删 `KeyOrder.align`。

代价：
- 每次 save 多一次全量 `encode` + 一次 `prev` 读取/解析 + 一次 O(n) 键序对齐
- 所有字段改动都要先证明「不破坏字节」（例如 2.3 加 `Message.model` 时必须确认 `Writer.put(null)` 不落键）

## 2. 为什么现在可以去掉

1. 原生仓库已不包含 WebView 前端（`assets.srcDirs = []`），www 只作 golden 数据源，**没有第二个写入方**
2. 迁移已完成：v0.24 → v0.28.7 → 原生的覆盖安装回归夹具已存在（`app/src/test/resources/regression/`）
3. 结构化判等可以用 data class 的 `equals`（`Message` 已是 data class），不需要 `JSON.stringify`

## 3. 目标形态

- 持久化格式不变（仍 `format = "oplog-v1"` + snapshot + append-only ops）
- `saveLocked` 不再读 `prev`，也不再调用 `KeyOrder.align` / `nullRemovedMessageKeys`
- 「键被删除」由 diff 显式表达：`msg.edit` 的 payload 带上 `deleted: [字段名]`，`applyOp` 按名单删除
- `KeyOrder` 工具类保留一个版本周期后删除

## 4. 分阶段迁移（每阶段可独立回滚）

### 阶段 A：diff 判等改为键序无关（前置，低风险）— 代码已完成（2026-11-16）

- 已落地：`JsConvLog.diff()` 内 10 处 `equal(...)` → `Js.semanticEqual(...)`（`Js.kt:364-366`，`canonical` 排序键后比较）；`import Js.semanticEqual` 已加
- diff 外 2 处 `equal`（stamp 判等 / `isConflict`）保留
- 已补用例：`JsSemanticTest`（键序无关 / `Undef` ≠ `null` / `Undef` 值等价缺键 / 数组顺序敏感）
- 待验证：golden 逐条对比（ops 与改动前一致）、oplog 体积不增
- 验收：golden 回归产出的 ops 与改动前**逐条一致**；oplog 体积不增；`Undef` vs `null` 语义单独用例确认
- 性能：每次比较多一次键排序，对话对象为 O(消息数 × 字段数)，开销可忽略

### 阶段 B：去掉 `KeyOrder.align`（低风险，依赖阶段 A）

- 在 `ConversationCodec.encode` 里按**固定字段顺序**输出（与 `MessageCodec`/`ConversationCodec` 声明顺序一致）
- `saveLocked` 删除 `KeyOrder.align` 调用
- `KeyOrder` 类暂留（`ConvMetaCodec` 等仍在用）
- 验收：`CodecRoundTripTest`（语义相等）+ `RepositoryTest` + golden 回归；真机编辑消息后重启，oplog 不出现成对的 `msg.edit` 抖动

### 阶段 C：去掉 `nullRemovedMessageKeys`（中风险）

- `diff()` 对消息字段增加 removed-keys 采集：上次有值、这次缺失 → payload `deleted: [...]`
- `applyOp("msg.edit")` 支持 `deleted`
- 删除 `saveLocked` 里的 `nullRemovedMessageKeys`
- 验收：新增用例覆盖 `truncated:true → false`、`error:"x" → null`、`vi:1 → 0`，断言回放后字段为默认值且不复活

### 阶段 D：清理（低风险）

- `saveLocked` 不再读取 `prev`（省一次 IO + 解析）
- 删除 `KeyOrder` 与相关注释
- 更新 `NATIVE_REWRITE_SPEC.md` 的持久化章节
- 验收：全量单测 + 覆盖安装夹具 + 真机冷启动

## 5. 风险与回滚

| 风险 | 缓解 |
|---|---|
| `semanticEqual` 与 `equal` 在 `Undef` / `null` 上语义可能不同 | 阶段 A 先跑 golden 逐条对比；对 `Undef` 与 `null` 分别断言，不一致则该分支保留 `equal` |
| 键序变化导致 golden 字节比较失败 | golden 比较用**语义相等**（`Js.semanticEqual`），不是字符串；阶段 B 前先跑一遍确认无依赖 |
| 旧数据回放差异（缺键 vs null） | 阶段 2 的双向用例；保留一个版本周期的兼容读法（`vi|0`、真假判断已等价） |
| 真机数据损坏 | oplog append-only；回滚只需恢复旧代码，snapshot/ops 格式未变 |
| 与 JS 源数据交叉 | 迁移完成后已无交叉；导出仍走 `exportLegacy`，不受影响 |

**开关**：加常量 `BuildConfig`/`AppSettings` 层开关 `structuralDiff`（默认关），灰度一个版本后转正。

## 6. 不做什么

- 不改 snapshot 格式、不改 lamport/冲突语义
- 不删 `exportLegacy`（导出旧格式仍需要）
- 不动 www golden 数据源
- 不在本方案里做 2.1（合并三套 `Js`）

## 7. 验收清单

- [ ] `CodecRoundTripTest` 全绿（含 `messageModelIsFirstClassAndLegacyBytesUnchanged`）
- [ ] `RepositoryTest.createSaveLoadAndIndex`
- [ ] `regression/` 覆盖安装夹具通过
- [ ] 新增「删除键回放」用例通过
- [ ] 真机：编辑 / 重生成 / 续写后重启，oplog 无重复 op、无键序抖动
- [ ] 连续 200 轮后压缩阈值行为不变（`COMPACT_OPS` / `COMPACT_BYTES`）

## 8. 工时估算

| 阶段 | 内容 | 估算 |
|---|---|---|
| A | diff 判等改 `semanticEqual` | 0.5 天 |
| B | 固定字段序 + 去 KeyOrder.align | 0.5 天 |
| C | diff 采集 deleted + applyOp 支持 | 1 天 |
| D | 清理 + 文档 | 0.5 天 |
| 验证 | 单测 + 覆盖安装 + 真机 | 1 天 |

合计约 3.5 天（比原估多 0.5 天：多了一个必须先做的判等切换阶段）。