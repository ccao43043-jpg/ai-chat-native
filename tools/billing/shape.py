#!/usr/bin/env python3
"""shape.py —— 按日看请求形态：cached ~ prompt 回归（斜率/截距）+ 零缓存占比 + 分供应商统计。

斜率 ≈ 0.99、截距 ≈ -20~-50 说明缓存断点形态没退化；零缓存行突增说明前缀被打断。

口径（三脚本一致，详见 README.md）：
- 成本只用 cost_total（cost_cache 是负数的「省下钱」独立记录项，不参与）；
- created_at 为 UTC；
- 只读，不改任何数据。

用法：python -I shape.py --csv openrouter_activity_2026-11-22.csv
"""
import argparse
import csv
from collections import defaultdict


def fnum(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return 0.0


def lsq(pairs):
    """最小二乘 y ~ x，返回 (slope, intercept)；样本不足或 x 无方差返回 None。"""
    n = len(pairs)
    if n < 2:
        return None
    mx = sum(x for x, _ in pairs) / n
    my = sum(y for _, y in pairs) / n
    sxx = sum((x - mx) ** 2 for x, _ in pairs)
    if sxx == 0:
        return None
    sxy = sum((x - mx) * (y - my) for x, y in pairs)
    slope = sxy / sxx
    return slope, my - slope * mx


def main():
    ap = argparse.ArgumentParser(description="按日请求形态与缓存断点回归")
    ap.add_argument("--csv", required=True, help="OpenRouter activity CSV 路径")
    a = ap.parse_args()
    with open(a.csv, newline="", encoding="utf-8") as fh:
        rows = list(csv.DictReader(fh))

    days = defaultdict(list)
    for r in rows:
        days[(r.get("created_at") or "")[:10]].append(r)

    print(f"file: {a.csv}   rows: {len(rows)}")
    print(f"{'day':<12}{'n':>5}{'zero-cache':>12}{'pct':>7}{'slope':>10}{'intercept':>12}{'$/req':>11}")
    for day in sorted(days):
        rs = days[day]
        pairs = [(fnum(r.get("tokens_prompt")), fnum(r.get("tokens_cached"))) for r in rs]
        fit = lsq(pairs)
        z = sum(1 for _, c in pairs if c == 0)
        cost = sum(fnum(r.get("cost_total")) for r in rs)
        slope_s = f"{fit[0]:.4f}" if fit else "—"
        inter_s = f"{fit[1]:.1f}" if fit else "—"
        print(f"{day or '—':<12}{len(rs):>5}{z:>12}{z / len(rs):>7.1%}{slope_s:>10}{inter_s:>12}{cost / len(rs):>11.5f}")

    provs = defaultdict(lambda: [0, 0.0])
    for r in rows:
        e = provs[r.get("provider_name") or "—"]
        e[0] += 1
        e[1] += fnum(r.get("cost_total"))
    print("\nby provider_name:")
    for name, (n, cost) in sorted(provs.items(), key=lambda kv: -kv[1][0]):
        print(f"  {name:<28} n={n:<5} total=${cost:.5f}")


if __name__ == "__main__":
    main()
