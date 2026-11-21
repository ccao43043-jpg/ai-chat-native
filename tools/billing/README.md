# tools/billing · OpenRouter 账单对账三件套

`shape.py`（按日形态）/ `phase.py`(按装机时刻分段) / `flex.py`（反解计费档）。
纯 Python 标准库，无第三方依赖；**只读**，不改任何数据。

> 历史注记：此前分析脚本放在 `D:\deep seek harness\_an\`，2026-11-21 发现连脚本带 CSV 全部消失
> （该目录是应用安装区，不可靠）。自此改入仓库本目录，口径固化在这里，"同一套脚本不改口径"才有载体。

## 口径（三脚本一致，不改）

1. **成本只用 `cost_total`**。`cost_cache` 列是负数（"省下的钱"的独立记录项），**不参与任何成本计算或抵扣**——上次对账踩过的坑。
2. **`created_at` 是 UTC**。文档/计划里写的时刻（如"B 段 15:26"）是 UTC+8 标签；`phase.py --split` 必须传换算后的 UTC（北京时间 -8h）。
3. **分段界 = 新包装机时刻**（换算成 UTC），装机后记录一次，对账时不再改。

## 用法

```bash
# 形态：按日回归 cached~prompt（斜率≈0.99、截距≈-20~-50 = 断点形态健康）+ 零缓存占比
python -I shape.py --csv openrouter_activity_2026-11-22.csv

# 分段：装机时刻前后对比（命中率高、$/请求下降 = 新包有效）
python -I phase.py --csv openrouter_activity_2026-11-22.csv --split "2026-11-21 03:00"

# 反解计费档：standard/flex 条数 + 若全按 flex 结算的反事实成本
python -I flex.py --csv openrouter_activity_2026-11-22.csv [--model gemini-3.8-flash]
```

`flex.py` 的价格假设默认按 **Google gemini-3.8-flash**（2026-11-20 账单实测反解）：
standard `in $0.75 / out $3.75 / cacheRead $0.075` per 1M，flex 全部减半。
**其它模型价格不同**——混跑必须 `--model <子串>` 过滤并显式传价，否则会被算进 unresolved（脚本不猜）。
反解残差 > `--tol`（默认 1e-6 美元）一律 unresolved，不强行归类。

## fixture 自测

`fixture.csv` 是 12 行合成数据（价格常量下逐条手算），改动脚本后回归：

```bash
python -I shape.py --csv fixture.csv
python -I phase.py --csv fixture.csv --split "2026-11-21 12:00"
python -I flex.py --csv fixture.csv
```

人工核对锚点：

- `shape`：2026-11-21 行 n=9、zero-cache=3（33.3%）、$/req≈0.00418；2026-11-20 行 n=3、zero-cache=1、$/req≈0.00411。
- `phase`（split=2026-11-21 12:00）：A 段 n=9、Σprompt=86000、Σcached=56260、hit≈65.4%、zero=3、$/req≈0.00407；B 段 n=3、hit≈65.2%、$/req≈0.00443。
- `flex`：standard=10、flex=2、unresolved=0；variant 列与反解不一致=0；actual=$0.04993；若全按 flex=$0.02788、多付 $0.02205（79.1%）。

## 对账流程（周日晚，照 PLAN-WEEKEND-2026-11-22 §4.4）

1. OpenRouter 网页导出 activity CSV（旧文件已不在磁盘，必须重新导出）。
2. `shape.py` 看断点形态没退化 → `phase.py` 按装机时刻分段 → `flex.py` 反解 flex 占比与反事实成本。
3. 实测值回填 `docs/ASSESS-2026-11-21.md` §2 四指标表；样本 <40 条只写趋势。
