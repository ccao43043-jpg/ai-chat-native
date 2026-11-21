#!/usr/bin/env python3
"""phase.py —— A/B 分段对账：--split（新包装机时刻，UTC）之前 vs 之后。

比对两段的请求数、聚合命中率（Σcached/Σprompt）、零缓存条数、$/请求。
分段界一旦记录就不改口径；装机时刻记得从本地时区换算成 UTC（北京时间 -8h）。

口径（三脚本一致，详见 README.md）：成本只用 cost_total；created_at 为 UTC。
用法：python -I phase.py --csv openrouter_activity_2026-11-22.csv --split "2026-11-21 03:00"
"""
import argparse
import csv
from datetime import datetime


def fnum(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return 0.0


def parse_dt(s):
    for fmt in ("%Y-%m-%d %H:%M:%S.%f", "%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M"):
        try:
            return datetime.strptime(s.strip(), fmt)
        except ValueError:
            pass
    return None


def main():
    ap = argparse.ArgumentParser(description="按装机时刻分段对账")
    ap.add_argument("--csv", required=True, help="OpenRouter activity CSV 路径")
    ap.add_argument("--split", required=True, help="分段界，UTC 'YYYY-MM-DD HH:MM'（= 新包装机时刻换算后）")
    a = ap.parse_args()
    split = datetime.strptime(a.split, "%Y-%m-%d %H:%M")
    with open(a.csv, newline="", encoding="utf-8") as fh:
        rows = list(csv.DictReader(fh))

    segs = {"A (< split)": [], "B (>= split)": []}
    for r in rows:
        t = parse_dt(r.get("created_at") or "")
        segs["A (< split)" if (t and t < split) else "B (>= split)"].append(r)

    print(f"split (UTC): {split:%Y-%m-%d %H:%M}   rows: {len(rows)}")
    print(f"{'segment':<15}{'n':>5}{'Σprompt':>10}{'Σcached':>10}{'hit':>8}{'zero':>6}{'total$':>11}{'$/req':>11}")
    for name, rs in segs.items():
        n = len(rs)
        if n == 0:
            print(f"{name:<15}{0:>5}{'—':>10}{'—':>10}{'—':>8}{'—':>6}{'—':>11}{'—':>11}")
            continue
        prompt = sum(fnum(r.get("tokens_prompt")) for r in rs)
        cached = sum(fnum(r.get("tokens_cached")) for r in rs)
        cost = sum(fnum(r.get("cost_total")) for r in rs)
        z = sum(1 for r in rs if fnum(r.get("tokens_cached")) == 0)
        hit = cached / prompt if prompt else 0.0
        print(f"{name:<15}{n:>5}{prompt:>10.0f}{cached:>10.0f}{hit:>8.1%}{z:>6}{cost:>11.5f}{cost / n:>11.5f}")


if __name__ == "__main__":
    main()
