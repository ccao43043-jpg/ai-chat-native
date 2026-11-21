# docs 索引

> **活跃入口 · 最后核对：2026-11-21（周末收口回写），main `5eb5bc8` / v1.7.9 + 省钱收口。**

## 一眼规则

1. 当前状态/优先级只看 [MASTER-PLAN.md](MASTER-PLAN.md) §2；代码完成、自动化验证、已发布、真机验收分开记。
2. 具体设计看三个专项 PLAN；标成「原方案/拟议」的 API、开关、UI 不表示已经可用。源码与文档冲突时先核对，不能凭勾选宣称实现。
3. 契约看 [NATIVE_REWRITE_SPEC.md](NATIVE_REWRITE_SPEC.md)（起点是旧重写规格，文首有当前覆盖说明）；签名只按 [SIGNING.md](SIGNING.md)，发版操作按 [RELEASE.md](RELEASE.md)。

## 当前入口

| 文件 | 类型 / 本次核对状态 | 用途 |
|---|---|---|
| [MASTER-PLAN.md](MASTER-PLAN.md) | 活跃 · 唯一执行清单 | 当前基线、完成项、剩余任务与优先级 |
| [PLAN-WEEKEND-2026-11-22.md](PLAN-WEEKEND-2026-11-22.md) | 活跃执行计划 | 本周末收口：真机验收 / T5 对账 / 文档回写的时段、判据与装机记录 |
| [ASSESS-2026-11-21.md](ASSESS-2026-11-21.md) | 执行记录 · **T5 待回填** | T4 关账结论（旁路误认）、四指标表、真机验收记录区 |
| [PLAN-ALGO-CACHE.md](PLAN-ALGO-CACHE.md) | 活跃专项 · B1/B3/B5/B6 已落地；B2 设计已细化（参数化+接线）、B4 待做 | 算法/缓存细节与已实现、拟议开关的区别 |
| `tools/billing/`（仓库根，不在 docs） | 对账工具 · fixture 自测通过 | OpenRouter CSV 对账三件套（shape/phase/flex），口径三条内建，见其 README |
| [PLAN-CONTEXT-ARCH.md](PLAN-CONTEXT-ARCH.md) | 实施记录 + 剩余范围 | M1–M7 核心已落地；统一召回默认 false；AutoChar 完整 S3 另记欠账，不等于原设计全部完成 |
| [PLAN-AUTOCHAR-WORLD.md](PLAN-AUTOCHAR-WORLD.md) | 部分完成 · **不是待批准，也不是 S3 全完成** | S1/S2、服务层与首条提示条已落地；RP 独立按钮、三档设置/交互闭环待做 |
| [ASSESS-2026-11-20.md](ASSESS-2026-11-20.md) | 上轮审计快照，非执行清单 | v1.7.9 的代码/测试/CI/发布证据、勘误及未确认边界 |
| [GLM-WEEKEND-AUDIT.md](https://github.com/ccao43043-jpg/ai-chat-native/pull/122/files) / [GLM-WEEKEND-REPORT.md](https://github.com/ccao43043-jpg/ai-chat-native/pull/122/files) | 随 PR #122 合并后转正式条目 | EXPERIENCE-GUARD 只读审计（17 项）与周末报告 |
| [NATIVE_REWRITE_SPEC.md](NATIVE_REWRITE_SPEC.md) | 契约 + 历史起点 | 行为对齐与 §3.5 差异登记；旧证书和旧里程碑不是当前发布指令 |
| [SIGNING.md](SIGNING.md) | 现行手册 | 固定指纹与覆盖安装前提；私钥/密码永不入库 |
| [RELEASE.md](RELEASE.md) | 现行手册 | debug/release 区别、R8、签名门与装机 smoke；仅编译不代表运行验收 |

## 历史与参照（保留原路径）

| 文件 | 快照 / 状态 | 用途 |
|---|---|---|
| [ASSESS-2026-11-17.md](ASSESS-2026-11-17.md) | v1.5.2 核对快照 | 保留当时的发现、评分与证据；不是当前欠账 |
| [REVIEW-2026-11-16.md](REVIEW-2026-11-16.md) | 历史评审，含后续回写 | 查历史问题根因；§6 不再滚动维护 |
| [OPTIMIZATION-ADVISORY.md](OPTIMIZATION-ADVISORY.md) | 历史战略/算法评估 | 当时的 D1–D11 映射与建议；实现状态看总计划 |
| [ROADMAP.md](ROADMAP.md) | 已归档 | 早期功能追赶过程，不恢复为活跃路线 |
| [structured-diff-plan.md](structured-diff-plan.md) | 实现已落地，历史方案归档 | 原案与实际显式 null 方案、验证边界；真机清单不自动勾选 |
| [UI_DIFF.md](UI_DIFF.md) | 历史逐屏对照 | v0.29 与原生 v1.0.0 的参照 |
| [plugins/README.md](plugins/README.md) 及同目录设计稿 | 旧 JS 插件历史参照 | 不能当作 Kotlin 插件接口或当前启用状态；当前边界看 CONTEXT，算法看 ALGO-CACHE |

## 维护约定

- 完成项同时记录实现提交、测试范围、发布版本与未验部分，不再用混合百分比。
- 历史评估不重写成今天的结果；补顶部跳转与后续更正即可，避免断旧链接。
- 新增/整理文档同步本索引；完成状态回写 MASTER-PLAN，不要求再回写历史 REVIEW §6。
- 变更后检查本地相对链接、锚点与 `git diff --check`；检查文档不等于重跑功能测试。
