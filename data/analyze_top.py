import csv

results = []
with open('C:/workspace/upbit-autotrade-java8/upbit-autotrade/data/optimize_results.csv') as f:
    reader = csv.DictReader(f)
    for row in reader:
        fc = float(row['final_capital'])
        roi = (fc - 1000000) / 10000
        results.append({
            'roi': roi,
            'strat': row['strategies'],
            'coin': row['coin'],
            'interval': row['interval'],
            'tp': row['tp_pct'],
            'sl': row['sl_pct'],
            'conf': row['min_confidence'],
            'trades': row['trades'],
            'wr': row['win_rate'],
            'fc': fc
        })

# Only profitable
profitable = [r for r in results if r['roi'] > 0]
profitable.sort(key=lambda x: -x['roi'])

# Deduplicate (same strat+coin+interval+tp+sl, different conf)
seen = set()
print('=== TOP 20 PROFITABLE COMBOS (deduplicated) ===')
count = 0
for r in profitable:
    key = (r['strat'], r['coin'], r['interval'], r['tp'], r['sl'])
    if key in seen:
        continue
    seen.add(key)
    count += 1
    print('ROI +%.2f%% | %s | %s %sm | TP%s SL%s | %s trades WR%s%%' % (
        r['roi'], r['strat'], r['coin'], r['interval'], r['tp'], r['sl'], r['trades'], r['wr']))
    if count >= 20:
        break

print()
print('=== BEST PER COIN ===')
for coin in ['KRW-BTC', 'KRW-ETH', 'KRW-XRP', 'KRW-SOL', 'KRW-ADA']:
    coin_results = [r for r in profitable if r['coin'] == coin]
    if coin_results:
        best = coin_results[0]
        print('%s: ROI +%.2f%% | %s | %sm | TP%s SL%s | %s trades WR%s%%' % (
            coin, best['roi'], best['strat'], best['interval'], best['tp'], best['sl'], best['trades'], best['wr']))
    else:
        print('%s: No profitable combo' % coin)

print()
print('=== BEST PER STRATEGY ===')
strat_best = {}
for r in profitable:
    s = r['strat']
    if s not in strat_best or r['roi'] > strat_best[s]['roi']:
        strat_best[s] = r
for s, r in sorted(strat_best.items(), key=lambda x: -x[1]['roi']):
    print('ROI +%.2f%% | %s | %s %sm | TP%s SL%s | %s trades WR%s%%' % (
        r['roi'], s, r['coin'], r['interval'], r['tp'], r['sl'], r['trades'], r['wr']))
