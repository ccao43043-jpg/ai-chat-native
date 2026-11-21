#!/usr/bin/env python3
"""flex.py —— 逐条反解计费档（standard / flex），并输出「若全按 flex 结算」的反事实成本。

价格假设是显式常量（默认按 Google gemini-3.8-flash 从 2026-11-20 账单实测反解：
standard in $0.75 / out $3.75 / cacheRead $0.075 per 1M；flex = 三价全部减半）。
其它模型用 --model 过滤子串 + 显式传价。反解残差 > 阈值一律计入 unresolved，不猜。

口径（三脚本一致，详见 README.md）：成本只读 cost_total；created_at 为 UTC。
用法：python -I flex.py --csv openrouter_activity_2026-11-22.csv [--model gemini-3.8-flash]
"""
import argparse
import csv


def fnum(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return 0.0


def main():
    ap = argparse.ArgumentParser(description="逐条反解计费档与 flex 反事实成本")
    ap.add_argument("--csv", required=True, help="OpenRouter activity CSV 路径")
    ap.add_argument("--in-price", type=float, default=0.75, help="standard 输入价 $/1M（默认 gemini-3.8-flash 实测）")
    ap.add_argument("--out-price", type=float, default=3.75, help="standard 输出价 $/1M")
    ap.add_argument("--cache-price", type=float, default=0.075, help="standard 缓存读价 $/1M")
    ap.add_argument("--model", default=None, help="只分析 model_permaslug 含此子串的行（价格随模型变，混跑必须过滤）")
    ap.add_argument("--tol", type=float, default=1e-6, help="反解残差阈值（美元），超过即 unresolved")
    a = ap.parse_args()
    m = 1_000_000.0
    inp, outp, crp = a.in_price / m, a.out_price / m, a.cache_price / m
    with open(a.csv, newline="", encoding="utf-8") as fh:
        rows = list(csv.DictReader(fh))
    if a.model:
        rows = [r for r in rows if a.model in (r.get("model_permaslug") or "")]

    counts = {"standard": 0, "flex": 0, "unresolved": 0}
    mism = 0          # 反解档位与 variant 列不一致的条数
    actual = 0.0      # 实际总成本（只用 cost_total）
    std_total = 0.0   # 所有行按 standard 价的理论成本（flex 反事实的一半）
    for r in rows:
        p = fnum(r.get("tokens_prompt"))
        c = fnum(r.get("tokens_cached"))
        out = fnum(r.get("tokens_completion"))
        cost = fnum(r.get("cost_total"))
        if p <= 0 and out <= 0:
            counts["unresolved"] += 1
            continue
        std = (p - c) * inp + c * crp + out * outp
        flex = std / 2.0  # flex = 三价全部减半
        actual += cost
        std_total += std
        d_std, d_flex = abs(cost - std), abs(cost - flex)
        if d_std <= a.tol and d_flex <= a.tol:
            tier = "standard" if d_std <= d_flex else "flex"
        elif d_std <= a.tol:
            tier = "standard"
        elif d_flex <= a.tol:
            tier = "flex"
        else:
            tier = "unresolved"
        counts[tier] += 1
        col = (r.get("variant") or "").strip().lower()
        if tier != "unresolved" and col in ("standard", "flex") and col != tier:
            mism += 1

    cf = std_total / 2.0  # 反事实：全部请求按 flex 价结算
    print(f"rows analyzed: {len(rows)}   (tol={a.tol:g}, prices in/out/cache = "
          f"{a.in_price:g}/{a.out_price:g}/{a.cache_price:g} per 1M)")
    print(f"reversed tiers: standard={counts['standard']}  flex={counts['flex']}  unresolved={counts['unresolved']}")
    print(f"variant 列与反解不一致: {mism} 条")
    print(f"actual total:     ${actual:.5f}")
    print(f"若全按 flex 结算: ${cf:.5f}")
    if cf > 0:
        print(f"多付:             ${actual - cf:.5f}（{(actual - cf) / cf:.1%}）")


if __name__ == "__main__":
    main()
