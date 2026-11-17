# docs 索引

> **状态**：✅ 活跃 · 本文是 `docs/` 的唯一入口，新增/改写文档时请同步这张表。

## 一眼规则（先看这三条）

1. **「现在做到哪」只查两处**：[`MASTER-PLAN.md`](MASTER-PLAN.md) §2（优先级 + 状态列）与 §5（完成度盘点）；算法/缓存细化查 [`PLAN-ALGO-CACHE.md`](PLAN-ALGO-CACHE.md) §1 现状表。
2. **评估类文档不是计划。** `REVIEW-2026-11-16.md` / `OPTIMIZATION-ADVISORY.md` 是当时的判断快照，里面的「现状」列可能滞后于代码；有冲突时以代码 + MASTER-PLAN 为准。
3. **改行为前先查 [`NATIVE_REWRITE_SPEC.md`](NATIVE_REWRITE_SPEC.md) §3.5。** 那里登记了所有「有意偏离旧 JS 版」的决定；新增偏离必须在该表加一行（D 编号递增）。

## 索引

| 文件 | 类型 | 状态 | 最后核实 | 什么时候看它 |
|---|---|---|---|---|
| [`MASTER-PLAN.md`](MASTER-PLAN.md) | 计划 | ✅ 活跃 · **唯一优先级来源** | 2026-11-17 | 决定「下一步做什么」、查某项完成没 |
| [`PLAN-ALGO-CACHE.md`](PLAN-ALGO-CACHE.md) | 计划（专项） | ✅ 活跃 | 2026-11-17 | 算法线 / 缓存专项的具体改法、工时、验收、开关 |
| [`NATIVE_REWRITE_SPEC.md`](NATIVE_REWRITE_SPEC.md) | 规格（契约） | ✅ 活跃 · 参照物 | 2026-11-17 | 某个功能「本来应该怎样」、偏离登记（§3.5） |
| [`SIGNING.md`](SIGNING.md) | 手册 | ✅ 活跃 | 2026-11-17 | 打包、证书、为什么能覆盖安装 |
| [`REVIEW-2026-11-16.md`](REVIEW-2026-11-16.md) | 评估 | 📸 快照 · 非计划 | 2026-11-17 回写 | 查具体问题的证据与代码行号（§2–§6） |
| [`OPTIMIZATION-ADVISORY.md`](OPTIMIZATION-ADVISORY.md) | 评估 | 📸 快照 · 非计划 | 2026-11-16 | 战略判断、缓存算法专项（§10）、D1–D11 映射（§11） |
| [`ROADMAP.md`](ROADMAP.md) | 计划（历史） | 🗄 已归档 · 不再更新 | 2026-11-17 | **只看历史**：当年怎么一步步做过来的 |
| [`structured-diff-plan.md`](structured-diff-plan.md) | 计划（历史） | ✅ 已完成 · 归档 | 2026-11-17 | 持久化去字节对齐的改法与回滚存档 |
| [`UI_DIFF.md`](UI_DIFF.md) | 对照 | 🗄 已归档 | 2026-11-15 | 旧版 v0.29 与原生 1.0.0 的逐屏对照（历史参照） |
| [`plugins/`](plugins/) | 设计稿 | 📚 参照 | — | 各缓存插件的原始设计文档（rp-cache / gemini / minimax 等 7 份） |

## 维护约定

- **一份事实只写一处**：功能状态写在 MASTER-PLAN §2/§5；算法细节写在 PLAN-ALGO-CACHE；契约写在 NATIVE_REWRITE_SPEC。其它文档引用它们，不复制状态表。
- **写日期**：任何「现状 / 完成度」类陈述都要带「截至 YYYY-MM-DD」，否则视为不可信。
- **归档而不是删除**：过期的计划把状态头改成 `🗄 已归档`，保留内容供追溯；只有当内容完全被合并进活跃文档时才删。
- **改完跑一遍**：涉及代码行为的计划条目，落地后回到 MASTER-PLAN §2 改状态列，并在本文「最后核实」更新日期。
