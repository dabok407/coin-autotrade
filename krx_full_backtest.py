#!/usr/bin/env python3
"""KRX Top30 Full Backtest: Opening / AllDay / MorningRush / Daily Gap Analysis"""
import csv
import sys
import itertools
from collections import defaultdict
from datetime import datetime, timedelta

# ──────────────────────────────────────────────────────────────────
# 1. Load data
# ──────────────────────────────────────────────────────────────────
def load_data(path):
    rows = []
    with open(path, 'r', encoding='utf-8') as f:
        reader = csv.DictReader(f)
        for r in reader:
            try:
                ts = datetime.strptime(r['timestamp'], '%Y-%m-%dT%H:%M:%S')
            except ValueError:
                continue
            rows.append({
                'symbol': r['symbol'],
                'name': r['name'],
                'ts': ts,
                'open': float(r['open']),
                'high': float(r['high']),
                'low': float(r['low']),
                'close': float(r['close']),
                'volume': float(r['volume']),
            })
    rows.sort(key=lambda x: (x['symbol'], x['ts']))
    by_sym = defaultdict(list)
    for r in rows:
        by_sym[r['symbol']].append(r)
    return by_sym

# ──────────────────────────────────────────────────────────────────
# 2. Technical indicators
# ──────────────────────────────────────────────────────────────────
def ema(values, period):
    if len(values) < period:
        return []
    k = 2.0 / (period + 1)
    result = [sum(values[:period]) / period]
    for v in values[period:]:
        result.append(v * k + result[-1] * (1 - k))
    return result

def rsi(closes, period=14):
    if len(closes) < period + 1:
        return []
    gains, losses = [], []
    for i in range(1, len(closes)):
        d = closes[i] - closes[i-1]
        gains.append(max(d, 0))
        losses.append(max(-d, 0))
    avg_gain = sum(gains[:period]) / period
    avg_loss = sum(losses[:period]) / period
    result = []
    for i in range(period - 1):
        result.append(50.0)
    if avg_loss == 0:
        result.append(100.0)
    else:
        result.append(100 - 100 / (1 + avg_gain / avg_loss))
    for i in range(period, len(gains)):
        avg_gain = (avg_gain * (period - 1) + gains[i]) / period
        avg_loss = (avg_loss * (period - 1) + losses[i]) / period
        if avg_loss == 0:
            result.append(100.0)
        else:
            result.append(100 - 100 / (1 + avg_gain / avg_loss))
    return result

def macd(closes, fast=12, slow=26, signal=9):
    ema_fast = ema(closes, fast)
    ema_slow = ema(closes, slow)
    if not ema_fast or not ema_slow:
        return [], [], []
    offset = slow - fast
    macd_line = [ema_fast[i + offset] - ema_slow[i] for i in range(len(ema_slow))]
    sig = ema(macd_line, signal)
    if not sig:
        return macd_line, [], []
    sig_offset = signal - 1
    hist = [macd_line[i + sig_offset] - sig[i] for i in range(len(sig))]
    return macd_line, sig, hist

def adx(candles, period=14):
    if len(candles) < period * 2 + 1:
        return []
    plus_dm_list = []
    minus_dm_list = []
    tr_list = []
    for i in range(1, len(candles)):
        h = candles[i]['high']
        l = candles[i]['low']
        ph = candles[i-1]['high']
        pl = candles[i-1]['low']
        pc = candles[i-1]['close']
        up = h - ph
        down = pl - l
        plus_dm_list.append(up if up > down and up > 0 else 0)
        minus_dm_list.append(down if down > up and down > 0 else 0)
        tr_list.append(max(h - l, abs(h - pc), abs(l - pc)))

    atr_val = sum(tr_list[:period]) / period
    plus_dm_s = sum(plus_dm_list[:period]) / period
    minus_dm_s = sum(minus_dm_list[:period]) / period

    dx_list = []
    for i in range(period, len(tr_list)):
        atr_val = (atr_val * (period - 1) + tr_list[i]) / period
        plus_dm_s = (plus_dm_s * (period - 1) + plus_dm_list[i]) / period
        minus_dm_s = (minus_dm_s * (period - 1) + minus_dm_list[i]) / period
        if atr_val == 0:
            dx_list.append(0)
        else:
            plus_di = 100 * plus_dm_s / atr_val
            minus_di = 100 * minus_dm_s / atr_val
            s = plus_di + minus_di
            dx_list.append(100 * abs(plus_di - minus_di) / s if s > 0 else 0)

    if len(dx_list) < period:
        return []
    adx_val = sum(dx_list[:period]) / period
    result = [adx_val]
    for i in range(period, len(dx_list)):
        adx_val = (adx_val * (period - 1) + dx_list[i]) / period
        result.append(adx_val)
    pad = len(candles) - len(result)
    return [0.0] * pad + result

def sma_vol(volumes, period):
    result = []
    for i in range(len(volumes)):
        if i < period - 1:
            result.append(sum(volumes[:i+1]) / (i+1) if i > 0 else volumes[0])
        else:
            result.append(sum(volumes[i-period+1:i+1]) / period)
    return result

# ──────────────────────────────────────────────────────────────────
# 3. Precompute indicators per symbol (cached)
# ──────────────────────────────────────────────────────────────────
_indicator_cache = {}

def precompute(candles, sym):
    if sym in _indicator_cache:
        return _indicator_cache[sym]

    closes = [c['close'] for c in candles]
    volumes = [c['volume'] for c in candles]
    n = len(candles)

    ema8_raw = ema(closes, 8)
    ema21_raw = ema(closes, 21)
    ema50_raw = ema(closes, 50)

    def align(arr):
        pad = n - len(arr)
        return [None] * pad + arr

    ema8 = align(ema8_raw)
    ema21 = align(ema21_raw)
    ema50 = align(ema50_raw)

    rsi_vals = rsi(closes, 14)
    rsi_aligned = align(rsi_vals)

    _, _, hist = macd(closes, 12, 26, 9)
    hist_aligned = [None] * (n - len(hist)) + list(hist)

    vol_sma = sma_vol(volumes, 20)
    adx_vals = adx(candles, 14)

    result = {
        'ema8': ema8, 'ema21': ema21, 'ema50': ema50,
        'rsi': rsi_aligned, 'macd_hist': hist_aligned,
        'vol_sma': vol_sma, 'adx': adx_vals,
    }
    _indicator_cache[sym] = result
    return result

# ──────────────────────────────────────────────────────────────────
# 4. MultiConfirm score
# ──────────────────────────────────────────────────────────────────
def calc_score(candle, ind, idx, vol_mult):
    e8 = ind['ema8'][idx]
    e21 = ind['ema21'][idx]
    e50 = ind['ema50'][idx]
    r = ind['rsi'][idx]
    mh = ind['macd_hist'][idx]
    vs = ind['vol_sma'][idx]
    adx_v = ind['adx'][idx] if idx < len(ind['adx']) else 0

    if any(v is None for v in [e8, e21, e50, r, mh]):
        return None

    if not (e8 > e21 > e50):
        return None
    if not (45 <= r <= 75):
        return None
    if candle['close'] <= candle['open']:
        return None
    if mh <= 0:
        return None
    if vs <= 0 or candle['volume'] < vs * vol_mult:
        return None

    score = 5.0
    vol_ratio = candle['volume'] / vs if vs > 0 else 0
    score += min((vol_ratio - vol_mult) * 0.5, 1.5)
    body = abs(candle['close'] - candle['open'])
    hl = candle['high'] - candle['low']
    if hl > 0:
        body_ratio = body / hl
        score += min(body_ratio, 1.0)
    gap = (e8 - e50) / e50 * 100 if e50 > 0 else 0
    score += min(gap * 0.2, 1.0)
    if adx_v > 20:
        score += min((adx_v - 20) * 0.04, 1.2)
    return score

# ──────────────────────────────────────────────────────────────────
# 5. Group candles by date per symbol
# ──────────────────────────────────────────────────────────────────
_date_cache = {}

def group_by_date(candles, sym):
    if sym in _date_cache:
        return _date_cache[sym]
    by_date = defaultdict(list)
    for i, c in enumerate(candles):
        by_date[c['ts'].date()].append((i, c))
    _date_cache[sym] = by_date
    return by_date

# ──────────────────────────────────────────────────────────────────
# 6. Strategy 1 & 2: MultiConfirmMomentum (Opening / AllDay)
# ──────────────────────────────────────────────────────────────────
FEE = 0.0049

def run_mcm(by_sym, entry_start_h, entry_start_m, entry_end_h, entry_end_m,
            session_end_h, session_end_m, min_conf, vol_mult, tp_pct, sl_pct, quick_tp_pct):
    tp = tp_pct / 100.0
    sl = sl_pct / 100.0
    qtp = quick_tp_pct / 100.0
    entry_start = entry_start_h * 60 + entry_start_m
    entry_end = entry_end_h * 60 + entry_end_m
    sess_end = session_end_h * 60 + session_end_m
    trades = []
    for sym, candles in by_sym.items():
        ind = precompute(candles, sym)
        dates = group_by_date(candles, sym)
        for dt in sorted(dates.keys()):
            day_candles = dates[dt]
            entered = False
            entry_price = 0
            for idx, c in day_candles:
                t = c['ts']
                tm = t.hour * 60 + t.minute

                if not entered and entry_start <= tm <= entry_end:
                    sc = calc_score(c, ind, idx, vol_mult)
                    if sc is not None and sc >= min_conf:
                        entry_price = c['close']
                        entered = True
                        continue

                if entered:
                    high_pct = (c['high'] - entry_price) / entry_price
                    low_pct = (c['low'] - entry_price) / entry_price

                    if high_pct >= qtp:
                        pnl = qtp - FEE
                        trades.append({'sym': sym, 'date': dt, 'entry': entry_price,
                                       'exit_type': 'QTP', 'pnl_pct': pnl})
                        entered = False
                        continue
                    if high_pct >= tp:
                        pnl = tp - FEE
                        trades.append({'sym': sym, 'date': dt, 'entry': entry_price,
                                       'exit_type': 'TP', 'pnl_pct': pnl})
                        entered = False
                        continue
                    if low_pct <= -sl:
                        pnl = -sl - FEE
                        trades.append({'sym': sym, 'date': dt, 'entry': entry_price,
                                       'exit_type': 'SL', 'pnl_pct': pnl})
                        entered = False
                        continue
                    if tm >= sess_end:
                        exit_pct = (c['close'] - entry_price) / entry_price
                        pnl = exit_pct - FEE
                        trades.append({'sym': sym, 'date': dt, 'entry': entry_price,
                                       'exit_type': 'TIME', 'pnl_pct': pnl})
                        entered = False
                        continue

            if entered:
                last_c = day_candles[-1][1]
                exit_pct = (last_c['close'] - entry_price) / entry_price
                pnl = exit_pct - FEE
                trades.append({'sym': sym, 'date': dt, 'entry': entry_price,
                               'exit_type': 'EOD', 'pnl_pct': pnl})
    return trades

# ──────────────────────────────────────────────────────────────────
# 7. Strategy 3: Morning Rush (gap-up)
# ──────────────────────────────────────────────────────────────────
def run_morning_rush(by_sym, gap_pct, vol_mult, tp_pct, sl_pct):
    tp = tp_pct / 100.0
    sl = sl_pct / 100.0
    gap_req = gap_pct / 100.0
    trades = []
    for sym, candles in by_sym.items():
        dates = group_by_date(candles, sym)
        sorted_dates = sorted(dates.keys())
        for di, dt in enumerate(sorted_dates):
            if di == 0:
                continue
            prev_dt = sorted_dates[di - 1]
            prev_candles = dates[prev_dt]
            day_candles = dates[dt]
            if not prev_candles or not day_candles:
                continue

            prev_close = prev_candles[-1][1]['close']
            prev_volumes = [c[1]['volume'] for c in prev_candles if c[1]['volume'] > 0]
            prev_avg_vol = sum(prev_volumes) / len(prev_volumes) if prev_volumes else 1

            first_candle = None
            first_idx_in_day = 0
            for j, (idx, c) in enumerate(day_candles):
                if c['ts'].hour == 9 and c['ts'].minute == 0:
                    first_candle = c
                    first_idx_in_day = j
                    break

            if first_candle is None:
                continue

            gap = (first_candle['open'] - prev_close) / prev_close
            if gap < gap_req:
                continue
            if first_candle['volume'] < prev_avg_vol * vol_mult:
                continue

            entry_price = first_candle['close']
            entered = True

            for j in range(first_idx_in_day + 1, len(day_candles)):
                idx, c = day_candles[j]
                tm = c['ts'].hour * 60 + c['ts'].minute

                high_pct = (c['high'] - entry_price) / entry_price
                low_pct = (c['low'] - entry_price) / entry_price

                if high_pct >= tp:
                    pnl = tp - FEE
                    trades.append({'sym': sym, 'date': dt, 'entry': entry_price,
                                   'exit_type': 'TP', 'pnl_pct': pnl, 'gap': gap*100})
                    entered = False
                    break
                if low_pct <= -sl:
                    pnl = -sl - FEE
                    trades.append({'sym': sym, 'date': dt, 'entry': entry_price,
                                   'exit_type': 'SL', 'pnl_pct': pnl, 'gap': gap*100})
                    entered = False
                    break
                if tm >= 600:
                    exit_pct = (c['close'] - entry_price) / entry_price
                    pnl = exit_pct - FEE
                    trades.append({'sym': sym, 'date': dt, 'entry': entry_price,
                                   'exit_type': 'TIME', 'pnl_pct': pnl, 'gap': gap*100})
                    entered = False
                    break

            if entered:
                last_c = day_candles[-1][1]
                exit_pct = (last_c['close'] - entry_price) / entry_price
                pnl = exit_pct - FEE
                trades.append({'sym': sym, 'date': dt, 'entry': entry_price,
                               'exit_type': 'EOD', 'pnl_pct': pnl, 'gap': gap*100})
    return trades

# ──────────────────────────────────────────────────────────────────
# 8. Analysis 4: Daily gap / opening momentum
# ──────────────────────────────────────────────────────────────────
def daily_analysis(by_sym):
    results = []
    for sym, candles in by_sym.items():
        dates = group_by_date(candles, sym)
        sorted_dates = sorted(dates.keys())
        for di, dt in enumerate(sorted_dates):
            if di == 0:
                continue
            prev_dt = sorted_dates[di - 1]
            prev_close = dates[prev_dt][-1][1]['close']
            day_candles = dates[dt]

            first = None
            for idx, c in day_candles:
                if c['ts'].hour == 9 and c['ts'].minute == 0:
                    first = c
                    break
            if first is None:
                continue

            day_open = first['open']
            gap_pct = (day_open - prev_close) / prev_close * 100
            day_high = max(c[1]['high'] for c in day_candles)
            opening_momentum = (day_high - day_open) / day_open * 100 if day_open > 0 else 0

            rush_high = day_open
            for idx, c in day_candles:
                if 540 <= c['ts'].hour * 60 + c['ts'].minute <= 550:
                    rush_high = max(rush_high, c['high'])
            rush_pct = (rush_high - day_open) / day_open * 100 if day_open > 0 else 0

            results.append({
                'sym': sym, 'date': dt, 'gap': gap_pct,
                'momentum': opening_momentum, 'rush': rush_pct
            })
    return results

# ──────────────────────────────────────────────────────────────────
# 9. Summary helper
# ──────────────────────────────────────────────────────────────────
def summarize(trades):
    if not trades:
        return {'count': 0, 'wins': 0, 'winrate': 0, 'total_pnl': 0, 'avg_pnl': 0,
                'max_win': 0, 'max_loss': 0, 'profit_factor': 0}
    wins = [t for t in trades if t['pnl_pct'] > 0]
    losses = [t for t in trades if t['pnl_pct'] <= 0]
    total_pnl = sum(t['pnl_pct'] for t in trades) * 100
    gross_win = sum(t['pnl_pct'] for t in wins) * 100 if wins else 0
    gross_loss = abs(sum(t['pnl_pct'] for t in losses) * 100) if losses else 0.001
    return {
        'count': len(trades),
        'wins': len(wins),
        'winrate': len(wins) / len(trades) * 100,
        'total_pnl': total_pnl,
        'avg_pnl': total_pnl / len(trades),
        'max_win': max(t['pnl_pct'] for t in trades) * 100,
        'max_loss': min(t['pnl_pct'] for t in trades) * 100,
        'profit_factor': gross_win / gross_loss,
    }

# ──────────────────────────────────────────────────────────────────
# MAIN
# ──────────────────────────────────────────────────────────────────
def main():
    print("=" * 80)
    print("KRX TOP30 FULL BACKTEST (5min, 3months, 29 symbols)")
    print("Fee: 0.49% round-trip")
    print("=" * 80)

    print("\nLoading data...")
    by_sym = load_data('/tmp/krx_top30_5min_yahoo.csv')
    print("  Loaded %d candles for %d symbols" % (sum(len(v) for v in by_sym.values()), len(by_sym)))

    # Precompute indicators for all symbols once
    print("  Precomputing indicators...")
    for sym, candles in by_sym.items():
        precompute(candles, sym)
        group_by_date(candles, sym)
    print("  Done.")

    # Current settings
    CURRENT_OPENING = {'min_conf': 6.5, 'vol_mult': 1.5, 'tp': 2.0, 'sl': 2.0, 'qtp': 0.7}
    CURRENT_ALLDAY  = {'min_conf': 9.4, 'vol_mult': 1.5, 'tp': 2.0, 'sl': 1.5, 'qtp': 0.7}
    CURRENT_RUSH    = {'gap': 3.0, 'vol_mult': 3.0, 'tp': 2.0, 'sl': 2.0}

    # ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    # ANALYSIS 1: Opening (09:05~09:55, close 14:30)
    # ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    print("\n" + "=" * 80)
    print("ANALYSIS 1: OPENING STRATEGY (MultiConfirmMomentum)")
    print("  Entry: 09:05~09:55 KST, Session end: 14:30 KST")
    print("=" * 80)

    mc_grid = [6.0, 6.5, 7.0, 7.5, 8.0, 8.5, 9.0]
    vm_grid = [1.5, 2.0, 2.5, 3.0]
    tp_grid = [1.5, 2.0, 3.0]
    sl_grid = [1.5, 2.0, 2.8]
    qtp_grid = [0.8, 1.0, 1.5]

    results_opening = []
    total_combos = len(mc_grid) * len(vm_grid) * len(tp_grid) * len(sl_grid) * len(qtp_grid)
    print("  Grid: %d combinations" % total_combos)
    done = 0
    for mc in mc_grid:
        for vm in vm_grid:
            for tp in tp_grid:
                for sl in sl_grid:
                    for qtp in qtp_grid:
                        trades = run_mcm(by_sym, 9, 5, 9, 55, 14, 30,
                                         mc, vm, tp, sl, qtp)
                        s = summarize(trades)
                        s['params'] = {'mc': mc, 'vm': vm, 'tp': tp, 'sl': sl, 'qtp': qtp}
                        results_opening.append(s)
                        done += 1
                        if done % 100 == 0:
                            sys.stdout.write("\r  Progress: %d/%d" % (done, total_combos))
                            sys.stdout.flush()
    print("\r  Progress: %d/%d DONE" % (done, total_combos))

    results_opening.sort(key=lambda x: x['total_pnl'], reverse=True)

    print("\n  TOP 10 by Total PnL%:")
    print("  %-5s %-8s %-8s %-6s %-6s %-6s %-7s %-5s %-7s %-10s %-9s %-6s" %
          ('Rank', 'minConf', 'volMult', 'TP%', 'SL%', 'QTP%', 'Trades', 'Wins', 'WinR%', 'TotalPnL%', 'AvgPnL%', 'PF'))
    print("  " + "-" * 85)
    for i, r in enumerate(results_opening[:10]):
        p = r['params']
        print("  %-5d %-8.1f %-8.1f %-6.1f %-6.1f %-6.1f %-7d %-5d %-7.1f %-10.2f %-9.2f %-6.2f" %
              (i+1, p['mc'], p['vm'], p['tp'], p['sl'], p['qtp'],
               r['count'], r['wins'], r['winrate'], r['total_pnl'], r['avg_pnl'], r['profit_factor']))

    # Current settings result
    trades = run_mcm(by_sym, 9, 5, 9, 55, 14, 30,
                     CURRENT_OPENING['min_conf'], CURRENT_OPENING['vol_mult'],
                     CURRENT_OPENING['tp'], CURRENT_OPENING['sl'], CURRENT_OPENING['qtp'])
    curr_opening = summarize(trades)
    curr_opening['params'] = CURRENT_OPENING

    print("\n  CURRENT SETTINGS: minConf=%.1f, volMult=%.1f, TP=%.1f%%, SL=%.1f%%, QTP=%.1f%%" %
          (CURRENT_OPENING['min_conf'], CURRENT_OPENING['vol_mult'],
           CURRENT_OPENING['tp'], CURRENT_OPENING['sl'], CURRENT_OPENING['qtp']))
    print("    Trades=%d, Wins=%d, WinRate=%.1f%%, TotalPnL=%.2f%%, AvgPnL=%.2f%%, PF=%.2f" %
          (curr_opening['count'], curr_opening['wins'], curr_opening['winrate'],
           curr_opening['total_pnl'], curr_opening['avg_pnl'], curr_opening['profit_factor']))

    best_op = results_opening[0]
    bp = best_op['params']
    print("\n  BEST SETTINGS: minConf=%.1f, volMult=%.1f, TP=%.1f%%, SL=%.1f%%, QTP=%.1f%%" %
          (bp['mc'], bp['vm'], bp['tp'], bp['sl'], bp['qtp']))
    print("    Trades=%d, Wins=%d, WinRate=%.1f%%, TotalPnL=%.2f%%, AvgPnL=%.2f%%, PF=%.2f" %
          (best_op['count'], best_op['wins'], best_op['winrate'],
           best_op['total_pnl'], best_op['avg_pnl'], best_op['profit_factor']))

    # ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    # ANALYSIS 2: AllDay (10:05~13:30, close 14:45)
    # ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    print("\n" + "=" * 80)
    print("ANALYSIS 2: ALLDAY STRATEGY (MultiConfirmMomentum)")
    print("  Entry: 10:05~13:30 KST, Session end: 14:45 KST")
    print("=" * 80)

    results_allday = []
    done = 0
    for mc in mc_grid:
        for vm in vm_grid:
            for tp in tp_grid:
                for sl in sl_grid:
                    for qtp in qtp_grid:
                        trades = run_mcm(by_sym, 10, 5, 13, 30, 14, 45,
                                         mc, vm, tp, sl, qtp)
                        s = summarize(trades)
                        s['params'] = {'mc': mc, 'vm': vm, 'tp': tp, 'sl': sl, 'qtp': qtp}
                        results_allday.append(s)
                        done += 1
                        if done % 100 == 0:
                            sys.stdout.write("\r  Progress: %d/%d" % (done, total_combos))
                            sys.stdout.flush()
    print("\r  Progress: %d/%d DONE" % (done, total_combos))

    results_allday.sort(key=lambda x: x['total_pnl'], reverse=True)

    print("\n  TOP 10 by Total PnL%:")
    print("  %-5s %-8s %-8s %-6s %-6s %-6s %-7s %-5s %-7s %-10s %-9s %-6s" %
          ('Rank', 'minConf', 'volMult', 'TP%', 'SL%', 'QTP%', 'Trades', 'Wins', 'WinR%', 'TotalPnL%', 'AvgPnL%', 'PF'))
    print("  " + "-" * 85)
    for i, r in enumerate(results_allday[:10]):
        p = r['params']
        print("  %-5d %-8.1f %-8.1f %-6.1f %-6.1f %-6.1f %-7d %-5d %-7.1f %-10.2f %-9.2f %-6.2f" %
              (i+1, p['mc'], p['vm'], p['tp'], p['sl'], p['qtp'],
               r['count'], r['wins'], r['winrate'], r['total_pnl'], r['avg_pnl'], r['profit_factor']))

    # Current allday
    trades = run_mcm(by_sym, 10, 5, 13, 30, 14, 45,
                     CURRENT_ALLDAY['min_conf'], CURRENT_ALLDAY['vol_mult'],
                     CURRENT_ALLDAY['tp'], CURRENT_ALLDAY['sl'], CURRENT_ALLDAY['qtp'])
    curr_allday = summarize(trades)

    print("\n  CURRENT SETTINGS: minConf=%.1f, volMult=%.1f, TP=%.1f%%, SL=%.1f%%, QTP=%.1f%%" %
          (CURRENT_ALLDAY['min_conf'], CURRENT_ALLDAY['vol_mult'],
           CURRENT_ALLDAY['tp'], CURRENT_ALLDAY['sl'], CURRENT_ALLDAY['qtp']))
    print("    Trades=%d, Wins=%d, WinRate=%.1f%%, TotalPnL=%.2f%%, AvgPnL=%.2f%%, PF=%.2f" %
          (curr_allday['count'], curr_allday['wins'], curr_allday['winrate'],
           curr_allday['total_pnl'], curr_allday['avg_pnl'], curr_allday['profit_factor']))

    best_ad = results_allday[0]
    bp = best_ad['params']
    print("\n  BEST SETTINGS: minConf=%.1f, volMult=%.1f, TP=%.1f%%, SL=%.1f%%, QTP=%.1f%%" %
          (bp['mc'], bp['vm'], bp['tp'], bp['sl'], bp['qtp']))
    print("    Trades=%d, Wins=%d, WinRate=%.1f%%, TotalPnL=%.2f%%, AvgPnL=%.2f%%, PF=%.2f" %
          (best_ad['count'], best_ad['wins'], best_ad['winrate'],
           best_ad['total_pnl'], best_ad['avg_pnl'], best_ad['profit_factor']))

    # ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    # ANALYSIS 3: Morning Rush
    # ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    print("\n" + "=" * 80)
    print("ANALYSIS 3: MORNING RUSH (Gap-up Detection)")
    print("  Entry: 09:00~09:05 KST, Session end: 10:00 KST")
    print("=" * 80)

    gap_grid = [2.0, 2.5, 3.0, 4.0, 5.0]
    rush_vm_grid = [2.0, 3.0, 4.0, 5.0]
    rush_tp_grid = [1.0, 1.5, 2.0, 3.0]
    rush_sl_grid = [1.0, 1.5, 2.0, 3.0]

    results_rush = []
    rush_combos = len(gap_grid) * len(rush_vm_grid) * len(rush_tp_grid) * len(rush_sl_grid)
    print("  Grid: %d combinations" % rush_combos)
    done = 0
    for gp in gap_grid:
        for vm in rush_vm_grid:
            for tp in rush_tp_grid:
                for sl in rush_sl_grid:
                    trades = run_morning_rush(by_sym, gp, vm, tp, sl)
                    s = summarize(trades)
                    s['params'] = {'gap': gp, 'vm': vm, 'tp': tp, 'sl': sl}
                    results_rush.append(s)
                    done += 1
    print("  Progress: %d/%d DONE" % (done, rush_combos))

    results_rush.sort(key=lambda x: x['total_pnl'], reverse=True)

    print("\n  TOP 10 by Total PnL%:")
    print("  %-5s %-7s %-8s %-6s %-6s %-7s %-5s %-7s %-10s %-9s %-6s" %
          ('Rank', 'Gap%', 'volMult', 'TP%', 'SL%', 'Trades', 'Wins', 'WinR%', 'TotalPnL%', 'AvgPnL%', 'PF'))
    print("  " + "-" * 75)
    for i, r in enumerate(results_rush[:10]):
        p = r['params']
        print("  %-5d %-7.1f %-8.1f %-6.1f %-6.1f %-7d %-5d %-7.1f %-10.2f %-9.2f %-6.2f" %
              (i+1, p['gap'], p['vm'], p['tp'], p['sl'],
               r['count'], r['wins'], r['winrate'], r['total_pnl'], r['avg_pnl'], r['profit_factor']))

    # Current rush
    trades = run_morning_rush(by_sym, CURRENT_RUSH['gap'], CURRENT_RUSH['vol_mult'],
                               CURRENT_RUSH['tp'], CURRENT_RUSH['sl'])
    curr_rush = summarize(trades)

    print("\n  CURRENT SETTINGS: Gap=%.1f%%, volMult=%.1f, TP=%.1f%%, SL=%.1f%%" %
          (CURRENT_RUSH['gap'], CURRENT_RUSH['vol_mult'], CURRENT_RUSH['tp'], CURRENT_RUSH['sl']))
    print("    Trades=%d, Wins=%d, WinRate=%.1f%%, TotalPnL=%.2f%%, AvgPnL=%.2f%%, PF=%.2f" %
          (curr_rush['count'], curr_rush['wins'], curr_rush['winrate'],
           curr_rush['total_pnl'], curr_rush['avg_pnl'], curr_rush['profit_factor']))

    best_rush = results_rush[0]
    bp = best_rush['params']
    print("\n  BEST SETTINGS: Gap=%.1f%%, volMult=%.1f, TP=%.1f%%, SL=%.1f%%" %
          (bp['gap'], bp['vm'], bp['tp'], bp['sl']))
    print("    Trades=%d, Wins=%d, WinRate=%.1f%%, TotalPnL=%.2f%%, AvgPnL=%.2f%%, PF=%.2f" %
          (best_rush['count'], best_rush['wins'], best_rush['winrate'],
           best_rush['total_pnl'], best_rush['avg_pnl'], best_rush['profit_factor']))

    # ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    # ANALYSIS 4: Daily Gap / Opening Momentum
    # ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    print("\n" + "=" * 80)
    print("ANALYSIS 4: DAILY GAP / OPENING MOMENTUM ANALYSIS")
    print("=" * 80)

    da = daily_analysis(by_sym)
    if da:
        gaps = [d['gap'] for d in da]
        momenta = [d['momentum'] for d in da]
        rushes = [d['rush'] for d in da]

        print("\n  Total day-symbol pairs: %d" % len(da))
        print("\n  Gap (Open vs Prev Close):")
        print("    Mean: %.3f%%" % (sum(gaps)/len(gaps)))
        print("    Median: %.3f%%" % sorted(gaps)[len(gaps)//2])
        print("    Positive gaps (>0%%): %d (%.1f%%)" % (sum(1 for g in gaps if g > 0), sum(1 for g in gaps if g > 0)/len(gaps)*100))
        print("    Gaps > 2%%: %d (%.1f%%)" % (sum(1 for g in gaps if g > 2), sum(1 for g in gaps if g > 2)/len(gaps)*100))
        print("    Gaps > 3%%: %d (%.1f%%)" % (sum(1 for g in gaps if g > 3), sum(1 for g in gaps if g > 3)/len(gaps)*100))
        print("    Gaps > 5%%: %d (%.1f%%)" % (sum(1 for g in gaps if g > 5), sum(1 for g in gaps if g > 5)/len(gaps)*100))

        print("\n  Opening Momentum (DayHigh - Open) / Open:")
        print("    Mean: %.3f%%" % (sum(momenta)/len(momenta)))
        print("    Median: %.3f%%" % sorted(momenta)[len(momenta)//2])

        print("\n  09:00~09:10 Rush (RushHigh - Open) / Open:")
        print("    Mean: %.3f%%" % (sum(rushes)/len(rushes)))
        print("    Median: %.3f%%" % sorted(rushes)[len(rushes)//2])

        da.sort(key=lambda x: x['gap'], reverse=True)
        print("\n  TOP 15 Gap-up Days:")
        print("  %-12s %-8s %-8s %-12s %-8s" % ('Date', 'Symbol', 'Gap%', 'Momentum%', 'Rush%'))
        print("  " + "-" * 50)
        for d in da[:15]:
            print("  %-12s %-8s %-8.2f %-12.2f %-8.2f" % (str(d['date']), d['sym'], d['gap'], d['momentum'], d['rush']))

        print("\n  Gap-up Follow-through Analysis:")
        for threshold in [2.0, 3.0, 5.0]:
            gapups = [d for d in da if d['gap'] >= threshold]
            if gapups:
                avg_mom = sum(d['momentum'] for d in gapups) / len(gapups)
                avg_rush = sum(d['rush'] for d in gapups) / len(gapups)
                continued = sum(1 for d in gapups if d['momentum'] > 1.0)
                print("    Gap >= %.1f%%: %d cases, avg momentum %.2f%%, avg rush %.2f%%, continued(>1%%) %d (%.0f%%)" %
                      (threshold, len(gapups), avg_mom, avg_rush, continued, continued/len(gapups)*100))

    # ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    # RECOMMENDATIONS
    # ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    print("\n" + "=" * 80)
    print("RECOMMENDATIONS")
    print("=" * 80)

    # Opening
    best = results_opening[0]
    bp = best['params']
    print("\n  [Opening Strategy]")
    print("    Current: minConf=%.1f, volMult=%.1f, TP=%.1f%%, SL=%.1f%%, QTP=%.1f%%" %
          (CURRENT_OPENING['min_conf'], CURRENT_OPENING['vol_mult'], CURRENT_OPENING['tp'], CURRENT_OPENING['sl'], CURRENT_OPENING['qtp']))
    print("      -> %d trades, PnL=%.2f%%, WinRate=%.1f%%" % (curr_opening['count'], curr_opening['total_pnl'], curr_opening['winrate']))
    print("    Optimal: minConf=%.1f, volMult=%.1f, TP=%.1f%%, SL=%.1f%%, QTP=%.1f%%" %
          (bp['mc'], bp['vm'], bp['tp'], bp['sl'], bp['qtp']))
    print("      -> %d trades, PnL=%.2f%%, WinRate=%.1f%%" % (best['count'], best['total_pnl'], best['winrate']))
    reasonable = [r for r in results_opening if r['count'] >= 5]
    if reasonable:
        reasonable.sort(key=lambda x: x['total_pnl'], reverse=True)
        print("    Top 3 (>=5 trades):")
        for i, r in enumerate(reasonable[:3]):
            p = r['params']
            print("      #%d: mc=%.1f, vm=%.1f, TP=%.1f%%, SL=%.1f%%, QTP=%.1f%% -> %dt, PnL=%.2f%%, WR=%.1f%%, PF=%.2f" %
                  (i+1, p['mc'], p['vm'], p['tp'], p['sl'], p['qtp'], r['count'], r['total_pnl'], r['winrate'], r['profit_factor']))

    # AllDay
    best = results_allday[0]
    bp = best['params']
    print("\n  [AllDay Strategy]")
    print("    Current: minConf=%.1f, volMult=%.1f, TP=%.1f%%, SL=%.1f%%, QTP=%.1f%%" %
          (CURRENT_ALLDAY['min_conf'], CURRENT_ALLDAY['vol_mult'], CURRENT_ALLDAY['tp'], CURRENT_ALLDAY['sl'], CURRENT_ALLDAY['qtp']))
    print("      -> %d trades, PnL=%.2f%%, WinRate=%.1f%%" % (curr_allday['count'], curr_allday['total_pnl'], curr_allday['winrate']))
    print("    Optimal: minConf=%.1f, volMult=%.1f, TP=%.1f%%, SL=%.1f%%, QTP=%.1f%%" %
          (bp['mc'], bp['vm'], bp['tp'], bp['sl'], bp['qtp']))
    print("      -> %d trades, PnL=%.2f%%, WinRate=%.1f%%" % (best['count'], best['total_pnl'], best['winrate']))
    reasonable = [r for r in results_allday if r['count'] >= 5]
    if reasonable:
        reasonable.sort(key=lambda x: x['total_pnl'], reverse=True)
        print("    Top 3 (>=5 trades):")
        for i, r in enumerate(reasonable[:3]):
            p = r['params']
            print("      #%d: mc=%.1f, vm=%.1f, TP=%.1f%%, SL=%.1f%%, QTP=%.1f%% -> %dt, PnL=%.2f%%, WR=%.1f%%, PF=%.2f" %
                  (i+1, p['mc'], p['vm'], p['tp'], p['sl'], p['qtp'], r['count'], r['total_pnl'], r['winrate'], r['profit_factor']))

    # Morning Rush
    best = results_rush[0]
    bp = best['params']
    print("\n  [Morning Rush Strategy]")
    print("    Current: Gap=%.1f%%, volMult=%.1f, TP=%.1f%%, SL=%.1f%%" %
          (CURRENT_RUSH['gap'], CURRENT_RUSH['vol_mult'], CURRENT_RUSH['tp'], CURRENT_RUSH['sl']))
    print("      -> %d trades, PnL=%.2f%%, WinRate=%.1f%%" % (curr_rush['count'], curr_rush['total_pnl'], curr_rush['winrate']))
    print("    Optimal: Gap=%.1f%%, volMult=%.1f, TP=%.1f%%, SL=%.1f%%" %
          (bp['gap'], bp['vm'], bp['tp'], bp['sl']))
    print("      -> %d trades, PnL=%.2f%%, WinRate=%.1f%%" % (best['count'], best['total_pnl'], best['winrate']))
    reasonable = [r for r in results_rush if r['count'] >= 3]
    if reasonable:
        reasonable.sort(key=lambda x: x['total_pnl'], reverse=True)
        print("    Top 3 (>=3 trades):")
        for i, r in enumerate(reasonable[:3]):
            p = r['params']
            print("      #%d: gap=%.1f%%, vm=%.1f, TP=%.1f%%, SL=%.1f%% -> %dt, PnL=%.2f%%, WR=%.1f%%, PF=%.2f" %
                  (i+1, p['gap'], p['vm'], p['tp'], p['sl'], r['count'], r['total_pnl'], r['winrate'], r['profit_factor']))

    print("\n" + "=" * 80)
    print("DONE")
    print("=" * 80)

if __name__ == '__main__':
    main()
