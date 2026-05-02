"""
거래 로그 파서: trades_8d.log → trade pairs (BUY → SPLIT_1ST/SPLIT_2ND/SELL) + 통계.
출력: .analysis/raw/trades_parsed.json + .analysis/raw/trades_summary.md
"""
import re, json, os
from collections import defaultdict, OrderedDict
from pathlib import Path

LOG = Path(".analysis/raw/trades_8d.log")
OUT_JSON = Path(".analysis/raw/trades_parsed.json")
OUT_MD = Path(".analysis/raw/trades_summary.md")

# Patterns
RE_TS = r"(?P<ts>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})"
RE_SCANNER_TAG = r"\[(?P<scanner>MorningRush|OpeningScanner|AllDayScanner|BreakoutDetector)\]"

re_buy = re.compile(
    RE_TS
    + r".*?"
    + RE_SCANNER_TAG
    + r" BUY (?P<market>KRW-[A-Z0-9]+) mode=(?P<mode>\w+) price=(?P<price>[0-9.E\-]+) qty=(?P<qty>[0-9.E\-]+)"
    + r"(?:.*?conf=(?P<conf>[0-9.]+))?"
    + r"(?:.*?reason=(?P<reason>\w+))?"
    + r"(?:.*?bo=\+?(?P<bo>[\-0-9.]+)%)?"
    + r"(?:.*?vol=(?P<vol>[0-9.]+)x)?"
    + r"(?:.*?rsi=(?P<rsi>[0-9]+))?"
    + r"(?:.*?qs=(?P<qs>[0-9.]+))?"
    + r"(?:.*?gap=\+?(?P<gap>[\-0-9.]+)%)?"
)

# Sell variants — capture pnl/roi/reason
re_sell = re.compile(
    RE_TS + r".*?" + RE_SCANNER_TAG
    + r" (?P<kind>SPLIT_1ST|SPLIT_2ND|SELL|TP_TRAIL SELL) (?:KRW-(?P<market>[A-Z0-9]+))"
    + r"(?:[^\n]*?price=(?P<price>[0-9.E\-]+))?"
    + r"(?:[^\n]*?pnl=(?P<pnl>[\-0-9]+))"
    + r"(?:[^\n]*?roi=(?P<roi>[\-0-9.]+)%)"
    + r"(?:[^\n]*?reason=(?P<reason>\w+))?"
    + r"(?:[^\n]*?peak=(?P<peak>[0-9.E\-]+))?"
    + r"(?:[^\n]*?drop=(?P<drop>[\-0-9.]+)%)?"
    + r"(?:[^\n]*?trough=(?P<trough>[0-9.E\-]+))?"
    + r"(?:[^\n]*?troughPnl=(?P<troughPnl>[\-0-9.]+)%)?"
)

# armed line
re_armed = re.compile(
    RE_TS + r".*? SPLIT_1ST trail armed: KRW-(?P<market>[A-Z0-9]+) pnl=\+?(?P<armPnl>[\-0-9.]+)% peak=(?P<peak>[0-9.E\-]+)"
)

# alt SELL line: e.g. "TP_TRAIL SELL KRW-CHIP price=119.0 pnl=6466 roi=2.59%"
re_sell_alt = re.compile(
    RE_TS + r".*?" + RE_SCANNER_TAG
    + r" TP_TRAIL SELL (?:KRW-(?P<market>[A-Z0-9]+))"
    + r" price=(?P<price>[0-9.E\-]+) pnl=(?P<pnl>[\-0-9]+) roi=(?P<roi>[\-0-9.]+)%"
)

# trough/peak/drop sometimes after reason in SELL line — additional capture if missed
re_peak_after = re.compile(r"peak=(?P<peak>[0-9.E\-]+)")
re_trough_after = re.compile(r"trough=(?P<trough>[0-9.E\-]+)")
re_troughPnl_after = re.compile(r"troughPnl=(?P<troughPnl>[\-0-9.]+)%")
re_drop_after = re.compile(r"drop=(?P<drop>[\-0-9.]+)%")
re_pnlpct_after = re.compile(r"pnl=(?P<pp>[\-0-9.]+)% (?:>=|<=|trough|$)")


def scanner_short(s):
    return {"MorningRush": "MR", "OpeningScanner": "OP", "AllDayScanner": "AD", "BreakoutDetector": "OP"}.get(s, s)


def parse():
    buys = []          # entries
    exits = []         # closes (SELL or SPLIT)
    armeds = []
    skipped = 0
    with LOG.open(encoding="utf-8", errors="replace") as f:
        for line in f:
            # Skip BLOCKED / blocking diagnostic lines
            if "BUY BLOCKED" in line:
                continue
            m = re_buy.search(line)
            if m:
                d = m.groupdict()
                d["scanner"] = scanner_short(d["scanner"])
                buys.append(d)
                continue
            m = re_armed.search(line)
            if m:
                armeds.append(m.groupdict())
                continue
            # SELL / SPLIT
            if "BLOCKED" in line:
                continue
            m = re_sell.search(line)
            if m:
                d = m.groupdict()
                d["scanner"] = scanner_short(d["scanner"])
                # back-fill from line if peak/trough/drop/troughPnl missed by main regex
                for f2, rgx in (("peak", re_peak_after), ("trough", re_trough_after), ("troughPnl", re_troughPnl_after), ("drop", re_drop_after)):
                    if not d.get(f2):
                        m2 = rgx.search(line)
                        if m2:
                            d[f2] = m2.group(1) if rgx.groups == 1 else list(m2.groups())[-1]
                exits.append(d)
                continue
            m = re_sell_alt.search(line)
            if m:
                d = m.groupdict()
                d["scanner"] = scanner_short(d["scanner"])
                d["kind"] = "SELL"
                d["reason"] = "TP_TRAIL"
                # peak/trough may appear later
                for f2, rgx in (("peak", re_peak_after), ("trough", re_trough_after), ("troughPnl", re_troughPnl_after), ("drop", re_drop_after)):
                    m2 = rgx.search(line)
                    if m2:
                        d[f2] = m2.group(1)
                exits.append(d)
                continue
            # otherwise skip
            if any(tok in line for tok in [" SELL KRW-", " SPLIT_1ST KRW-", " SPLIT_2ND KRW-"]):
                skipped += 1
    return buys, exits, armeds, skipped


def to_pairs(buys, exits, armeds):
    """Match BUY → exits chronologically per market."""
    by_mkt_buys = defaultdict(list)
    for b in buys:
        by_mkt_buys[b["market"]].append(b)
    by_mkt_armeds = defaultdict(list)
    for a in armeds:
        by_mkt_armeds["KRW-" + a["market"]].append(a)
    pairs = []
    by_mkt_exits = defaultdict(list)
    for e in exits:
        mkt = "KRW-" + e["market"] if not e["market"].startswith("KRW-") else e["market"]
        by_mkt_exits[mkt].append(e)

    for mkt, blist in by_mkt_buys.items():
        elist = by_mkt_exits.get(mkt, [])
        alist = by_mkt_armeds.get(mkt, [])
        # Each BUY consumes exits until SELL or SPLIT_2ND (full close).
        ei = 0
        ai = 0
        for b in blist:
            entry_ts = b["ts"]
            # collect exits after entry until full close
            split1 = None
            split2 = None
            sell = None
            armed = None
            while ai < len(alist) and alist[ai]["ts"] >= entry_ts:
                if alist[ai]["ts"] >= entry_ts:
                    # only keep first armed after entry
                    if armed is None and alist[ai]["ts"] >= entry_ts:
                        armed = alist[ai]
                    ai += 1
                    break
            while ei < len(elist) and elist[ei]["ts"] < entry_ts:
                ei += 1
            while ei < len(elist):
                e = elist[ei]
                if e["kind"] == "SPLIT_1ST" and split1 is None:
                    split1 = e
                    ei += 1
                    continue
                if e["kind"] == "SPLIT_2ND" and split2 is None:
                    split2 = e
                    ei += 1
                    break
                if e["kind"] == "SELL" and sell is None:
                    sell = e
                    ei += 1
                    break
                # Not matching — break to avoid eating next BUY's exit
                break
            pair = {
                "market": mkt,
                "scanner": b["scanner"],
                "entry_ts": entry_ts,
                "entry_price": float(b["price"]) if b.get("price") else None,
                "qty": float(b["qty"]) if b.get("qty") else None,
                "conf": float(b["conf"]) if b.get("conf") else None,
                "entry_reason": b.get("reason"),
                "bo": float(b["bo"]) if b.get("bo") else None,
                "vol": float(b["vol"]) if b.get("vol") else None,
                "rsi": int(b["rsi"]) if b.get("rsi") else None,
                "qs": float(b["qs"]) if b.get("qs") else None,
                "gap": float(b["gap"]) if b.get("gap") else None,
                "armed_pnl_pct": float(armed["armPnl"]) if armed and armed.get("armPnl") else None,
                "armed_peak": float(armed["peak"]) if armed and armed.get("peak") else None,
                "split1": split1,
                "split2": split2,
                "sell": sell,
            }
            # Compute aggregate PnL
            pnl = 0
            count = 0
            roi_parts = []
            for x in (split1, split2, sell):
                if x and x.get("pnl") is not None:
                    try:
                        pnl += int(x["pnl"])
                        count += 1
                        roi_parts.append(float(x["roi"]))
                    except Exception:
                        pass
            pair["pnl_total"] = pnl
            pair["close_count"] = count
            pair["roi_segments"] = roi_parts
            # Determine final outcome label
            if split2:
                pair["outcome_kind"] = "SPLIT_FULL"
                pair["exit_reason"] = split2.get("reason") or "?"
                pair["exit_ts"] = split2["ts"]
            elif sell:
                pair["outcome_kind"] = "SELL_ONLY"
                pair["exit_reason"] = sell.get("reason") or "?"
                pair["exit_ts"] = sell["ts"]
            elif split1:
                pair["outcome_kind"] = "SPLIT1_OPEN"
                pair["exit_reason"] = "OPEN_POSITION"
                pair["exit_ts"] = split1["ts"]
            else:
                pair["outcome_kind"] = "OPEN"
                pair["exit_reason"] = "STILL_OPEN"
                pair["exit_ts"] = None
            # peak / trough best info
            for x in (sell, split2, split1):
                if x:
                    if x.get("peak"):
                        pair["best_peak_price"] = float(x["peak"])
                    if x.get("trough"):
                        pair["worst_trough_price"] = float(x["trough"])
                    if x.get("troughPnl") is not None:
                        try:
                            pair["worst_trough_pnl_pct"] = float(x["troughPnl"])
                        except Exception:
                            pass
                    break
            pair["armed_flag"] = "Y" if armed else "N"
            pairs.append(pair)
    return pairs


def summarize(pairs):
    closed = [p for p in pairs if p["outcome_kind"] in ("SPLIT_FULL", "SELL_ONLY")]
    open_ = [p for p in pairs if p["outcome_kind"] in ("OPEN", "SPLIT1_OPEN")]

    total = len(pairs)
    closed_n = len(closed)
    pnl_sum = sum(p["pnl_total"] for p in closed)
    wins = [p for p in closed if p["pnl_total"] > 0]
    losses = [p for p in closed if p["pnl_total"] <= 0]
    winrate = len(wins) / closed_n * 100 if closed_n else 0

    # By scanner
    by_sc = defaultdict(lambda: {"n": 0, "wins": 0, "pnl": 0, "armed": 0})
    for p in closed:
        sc = p["scanner"]
        by_sc[sc]["n"] += 1
        if p["pnl_total"] > 0:
            by_sc[sc]["wins"] += 1
        by_sc[sc]["pnl"] += p["pnl_total"]
        if p["armed_flag"] == "Y":
            by_sc[sc]["armed"] += 1

    # By exit reason
    by_reason = defaultdict(lambda: {"n": 0, "pnl": 0, "wins": 0})
    for p in closed:
        r = p["exit_reason"] or "?"
        by_reason[r]["n"] += 1
        by_reason[r]["pnl"] += p["pnl_total"]
        if p["pnl_total"] > 0:
            by_reason[r]["wins"] += 1

    # Peak distribution for losses
    losses_breakdown = []
    for p in losses:
        bp = p.get("best_peak_price")
        ep = p.get("entry_price")
        peak_pct = None
        if bp and ep:
            peak_pct = (bp - ep) / ep * 100
        losses_breakdown.append({
            "market": p["market"],
            "entry_ts": p["entry_ts"],
            "scanner": p["scanner"],
            "entry_reason": p["entry_reason"],
            "conf": p["conf"],
            "vol": p["vol"],
            "rsi": p["rsi"],
            "qs": p["qs"],
            "bo": p["bo"],
            "armed": p["armed_flag"],
            "outcome_kind": p["outcome_kind"],
            "exit_reason": p["exit_reason"],
            "pnl_total": p["pnl_total"],
            "roi_segments": p["roi_segments"],
            "peak_pct": peak_pct,
            "worst_trough_pnl_pct": p.get("worst_trough_pnl_pct"),
            "trough_reached_zero": (p.get("worst_trough_pnl_pct") is not None and p["worst_trough_pnl_pct"] <= -0.01),
            "peak_above_zero": (peak_pct is not None and peak_pct > 0),
            "peak_above_one": (peak_pct is not None and peak_pct > 1.0),
            "peak_above_oneFive": (peak_pct is not None and peak_pct > 1.5),
        })

    # wins detail
    wins_detail = []
    for p in wins:
        bp = p.get("best_peak_price")
        ep = p.get("entry_price")
        peak_pct = (bp - ep) / ep * 100 if (bp and ep) else None
        # final ROI weighted
        final_roi = None
        if p["roi_segments"]:
            final_roi = sum(p["roi_segments"]) / len(p["roi_segments"])
        wins_detail.append({
            "market": p["market"],
            "entry_ts": p["entry_ts"],
            "scanner": p["scanner"],
            "entry_reason": p["entry_reason"],
            "conf": p["conf"],
            "vol": p["vol"],
            "rsi": p["rsi"],
            "qs": p["qs"],
            "bo": p["bo"],
            "armed": p["armed_flag"],
            "outcome_kind": p["outcome_kind"],
            "exit_reason": p["exit_reason"],
            "pnl_total": p["pnl_total"],
            "roi_segments": p["roi_segments"],
            "peak_pct": peak_pct,
            "avg_roi_pct": final_roi,
            "peak_minus_avg": (peak_pct - final_roi) if (peak_pct is not None and final_roi is not None) else None,
        })

    return {
        "totals": {
            "buys": total,
            "closed": closed_n,
            "open_now": len(open_),
            "wins": len(wins),
            "losses": len(losses),
            "winrate_pct": round(winrate, 2),
            "pnl_total_krw": pnl_sum,
        },
        "by_scanner": {k: dict(v, winrate=round(v["wins"]/v["n"]*100,2) if v["n"] else 0) for k, v in by_sc.items()},
        "by_exit_reason": {k: dict(v, winrate=round(v["wins"]/v["n"]*100,2) if v["n"] else 0) for k, v in by_reason.items()},
        "losses_detail": losses_breakdown,
        "wins_detail": wins_detail,
        "open_positions": [{"market": p["market"], "scanner": p["scanner"], "entry_ts": p["entry_ts"], "outcome": p["outcome_kind"]} for p in open_],
    }


def main():
    buys, exits, armeds, skipped = parse()
    pairs = to_pairs(buys, exits, armeds)
    summary = summarize(pairs)
    OUT_JSON.write_text(json.dumps({"pairs": pairs, "summary": summary}, indent=2, ensure_ascii=False), encoding="utf-8")

    s = summary
    md = []
    md.append("# Trades Summary (8 days, 2026-04-20 ~ 2026-04-27)\n")
    md.append(f"- BUY entries: {s['totals']['buys']}")
    md.append(f"- Closed: {s['totals']['closed']}, Open now: {s['totals']['open_now']}")
    md.append(f"- Wins: {s['totals']['wins']}, Losses: {s['totals']['losses']}, **Winrate: {s['totals']['winrate_pct']}%**")
    md.append(f"- **Total PnL: {s['totals']['pnl_total_krw']:,} KRW**\n")
    md.append("## By Scanner")
    md.append("| Scanner | N | Wins | Winrate | PnL(KRW) | Armed |")
    md.append("|---|---:|---:|---:|---:|---:|")
    for k, v in s["by_scanner"].items():
        md.append(f"| {k} | {v['n']} | {v['wins']} | {v['winrate']}% | {v['pnl']:,} | {v['armed']} |")
    md.append("")
    md.append("## By Exit Reason")
    md.append("| Reason | N | Wins | Winrate | PnL(KRW) |")
    md.append("|---|---:|---:|---:|---:|")
    for k, v in sorted(s["by_exit_reason"].items(), key=lambda x: -x[1]["n"]):
        md.append(f"| {k} | {v['n']} | {v['wins']} | {v['winrate']}% | {v['pnl']:,} |")
    md.append("")
    md.append("## Losses Detail")
    md.append("| ts | mkt | sc | reason | exit | pnl | roi% | peak% | trough% | armed |")
    md.append("|---|---|---|---|---|---:|---|---:|---:|---|")
    for p in s["losses_detail"]:
        peak = f"{p['peak_pct']:.2f}" if p["peak_pct"] is not None else "?"
        trough = f"{p['worst_trough_pnl_pct']:.2f}" if p["worst_trough_pnl_pct"] is not None else "?"
        roi = ",".join(f"{x:.2f}" for x in p["roi_segments"]) if p["roi_segments"] else "?"
        md.append(f"| {p['entry_ts'][5:]} | {p['market']} | {p['scanner']} | {p['entry_reason']} | {p['exit_reason']} | {p['pnl_total']:,} | {roi} | {peak} | {trough} | {p['armed']} |")
    md.append("")
    md.append("## Wins Detail")
    md.append("| ts | mkt | sc | reason | exit | pnl | avg_roi% | peak% | peak-avg | armed |")
    md.append("|---|---|---|---|---|---:|---:|---:|---:|---|")
    for p in s["wins_detail"]:
        peak = f"{p['peak_pct']:.2f}" if p["peak_pct"] is not None else "?"
        avg = f"{p['avg_roi_pct']:.2f}" if p["avg_roi_pct"] is not None else "?"
        diff = f"{p['peak_minus_avg']:.2f}" if p["peak_minus_avg"] is not None else "?"
        md.append(f"| {p['entry_ts'][5:]} | {p['market']} | {p['scanner']} | {p['entry_reason']} | {p['exit_reason']} | {p['pnl_total']:,} | {avg} | {peak} | {diff} | {p['armed']} |")
    md.append("")
    md.append(f"## Open Positions (still active)")
    for p in s["open_positions"]:
        md.append(f"- {p['market']} ({p['scanner']}, {p['entry_ts']}, {p['outcome']})")

    OUT_MD.write_text("\n".join(md), encoding="utf-8")
    print(f"Pairs: {len(pairs)}, Closed: {s['totals']['closed']}, Skipped: {skipped}")
    print(f"Winrate: {s['totals']['winrate_pct']}%, Total PnL: {s['totals']['pnl_total_krw']:,} KRW")
    print(f"Outputs: {OUT_JSON}, {OUT_MD}")


if __name__ == "__main__":
    main()
