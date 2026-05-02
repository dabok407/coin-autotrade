'use strict';
const fs = require('fs');
const path = require('path');

// ============================================================
// Step 1: Extract blocked signals from AllDay scanner logs
// Target: LOW_SCORE signals in range [5.5, current_threshold)
// Also capture HC_BREAK actual entries for sanity check
// ============================================================

const LOG_DIR = path.dirname(__filename);
const LOG_FILES = [
  'autotrade.log.2026-04-11.0',
  'autotrade.log.2026-04-12.0',
  'autotrade.log.2026-04-13.0',
  'autotrade.log.2026-04-14.0',
  // 04-15 skipped (anomalous)
  'autotrade.log.2026-04-16.0',
  'autotrade.log.2026-04-17.0',
  'autotrade.log.2026-04-18',
];

// Regex for LOW_SCORE blocked signals (both NO_SIGNAL and WS_SURGE_NO_SIGNAL paths)
// Pattern: [AllDayScanner] KRW-XXX BUY SKIPPED ... LOW_SCORE N.N<T.T vol=...
const BLOCKED_RE = /^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}) \[allday-scanner\].*\[AllDayScanner\] (KRW-\w+) BUY SKIPPED.*LOW_SCORE ([\d.]+)<([\d.]+) vol=([\d.]+)x day=([+-][\d.]+)% rsi=(\d+) ema21=(\S+) bo=([\d.]+)% surge=([\d.]+)% body=(\d+)%/;

// Regex for HC_BREAK actual buy entries
const HC_BREAK_RE = /^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}) \[allday-scanner\].*\[AllDayScanner\] BUY (KRW-\w+) mode=\w+ price=([\d.]+) .*conf=([\d.]+) reason=HC_BREAK.*score=([\d.]+) vol=([\d.]+)x day=([+-][\d.]+)% rsi=(\d+) ema21=(\S+) bo=([\d.]+)% surge=([\d.]+)% body=(\d+)%/;

const MIN_SCORE = 5.5;

const blockedSignals = [];
const hcBreakEntries = [];

// Track recent events per market to deduplicate (30s window)
const recentByMarket = {};

function toEpoch(kstStr) {
  // "2026-04-11 10:45:05" => epoch ms (KST = UTC+9)
  const [date, time] = kstStr.split(' ');
  const [y, mo, d] = date.split('-').map(Number);
  const [h, m, s] = time.split(':').map(Number);
  return Date.UTC(y, mo - 1, d, h - 9, m, s); // subtract 9h for UTC
}

let totalLines = 0;

for (const filename of LOG_FILES) {
  const filepath = path.join(LOG_DIR, filename);
  if (!fs.existsSync(filepath)) {
    console.log(`SKIP (not found): ${filename}`);
    continue;
  }
  const content = fs.readFileSync(filepath, 'utf8');
  const lines = content.split('\n');
  totalLines += lines.length;

  for (const line of lines) {
    // Check blocked signals
    const bm = line.match(BLOCKED_RE);
    if (bm) {
      const [, ts, market, scoreStr, threshStr, volStr, dayStr, rsiStr, ema21, boStr, surgeStr, bodyStr] = bm;
      const score = parseFloat(scoreStr);
      const threshold = parseFloat(threshStr);

      // Only collect scores >= 5.5 (the lowest scenario we test)
      if (score < MIN_SCORE) continue;

      // Dedup: same market within 30s
      const epoch = toEpoch(ts);
      const lastTs = recentByMarket[market] || 0;
      if (epoch - lastTs < 30000) continue;
      recentByMarket[market] = epoch;

      blockedSignals.push({
        timestamp_kst: ts,
        market,
        score,
        threshold,
        vol_ratio: parseFloat(volStr),
        day_pct: parseFloat(dayStr),
        rsi: parseInt(rsiStr),
        ema21,
        bo_pct: parseFloat(boStr),
        surge_pct: parseFloat(surgeStr),
        body_pct: parseInt(bodyStr),
        entry_price_estimate: null,
        candles_after: null,
        log_file: filename,
      });
    }

    // Check HC_BREAK actual entries
    const hm = line.match(HC_BREAK_RE);
    if (hm) {
      const [, ts, market, priceStr, confStr, scoreStr, volStr, dayStr, rsiStr, ema21, boStr, surgeStr, bodyStr] = hm;
      hcBreakEntries.push({
        timestamp_kst: ts,
        market,
        entry_price: parseFloat(priceStr),
        conf: parseFloat(confStr),
        score: parseFloat(scoreStr),
        vol_ratio: parseFloat(volStr),
        day_pct: parseFloat(dayStr),
        rsi: parseInt(rsiStr),
        ema21,
        bo_pct: parseFloat(boStr),
        surge_pct: parseFloat(surgeStr),
        body_pct: parseInt(bodyStr),
        candles_after: null,
        log_file: filename,
      });
    }
  }
}

// Sort by timestamp
blockedSignals.sort((a, b) => a.timestamp_kst.localeCompare(b.timestamp_kst));
hcBreakEntries.sort((a, b) => a.timestamp_kst.localeCompare(b.timestamp_kst));

console.log(`\n=== EXTRACTION SUMMARY ===`);
console.log(`Total log lines scanned: ${totalLines.toLocaleString()}`);
console.log(`Blocked signals (score >= ${MIN_SCORE}): ${blockedSignals.length}`);
console.log(`HC_BREAK actual entries: ${hcBreakEntries.length}`);

// Score distribution
const bands = [
  [5.5, 6.0],
  [6.0, 6.5],
  [6.5, 7.0],
  [7.0, 9.4],
];
console.log(`\nBlocked signal score distribution:`);
for (const [lo, hi] of bands) {
  const count = blockedSignals.filter(s => s.score >= lo && s.score < hi).length;
  console.log(`  ${lo}~${hi}: ${count} events`);
}

// Per-day counts
console.log(`\nBlocked signals per log file:`);
const byFile = {};
for (const s of blockedSignals) {
  byFile[s.log_file] = (byFile[s.log_file] || 0) + 1;
}
for (const [f, c] of Object.entries(byFile)) {
  console.log(`  ${f}: ${c}`);
}

console.log(`\nHC_BREAK entries per log file:`);
const hcByFile = {};
for (const s of hcBreakEntries) {
  hcByFile[s.log_file] = (hcByFile[s.log_file] || 0) + 1;
}
for (const [f, c] of Object.entries(hcByFile)) {
  console.log(`  ${f}: ${c}`);
}

fs.writeFileSync(path.join(LOG_DIR, 'blocked_signals.json'), JSON.stringify(blockedSignals, null, 2));
fs.writeFileSync(path.join(LOG_DIR, 'hc_break_entries.json'), JSON.stringify(hcBreakEntries, null, 2));
console.log(`\nSaved: blocked_signals.json (${blockedSignals.length} entries)`);
console.log(`Saved: hc_break_entries.json (${hcBreakEntries.length} entries)`);
