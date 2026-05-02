'use strict';
const fs = require('fs');
const path = require('path');

// ============================================================
// Step 3: HCB Exit Simulation
// Rules (from DB config):
//   slPct = 1.5
//   trailActivatePct = 0.5
//   trailDist = 1.5% of entry (simplified: max(0.8*ATR, 0.015*entry) -> fixed 1.5%)
//   timeStopCandles = 12, timeStopMinPnl = 0.3%
//   sessionEndHour = 8 (KST 08:00~10:00 => SESSION_END)
//   EMA/MACD exit: NOT simulated (noted in output)
// ============================================================

const ANALYSIS_DIR = __dirname;
const SL_PCT = 1.5;
const TRAIL_ACTIVATE_PCT = 0.5;
const TRAIL_DIST_PCT = 1.5; // simplified: fixed 1.5% of entry
const TIME_STOP_CANDLES = 12;
const TIME_STOP_MIN_PNL = 0.3;
const FEE_RATE = 0.0005; // 0.05% each side => 0.10% round-trip

function getKstHour(kstStr) {
  // "2026-04-11 08:15:00" => 8
  return parseInt(kstStr.split(' ')[1].split(':')[0]);
}

function simulate(entry, candlesAfter) {
  if (!candlesAfter || candlesAfter.length === 0) {
    return { exit: entry, exit_pnl_pct: 0, reason: 'NO_DATA', candles_traversed: 0 };
  }

  let peak = entry;
  let trailActive = false;

  for (let i = 0; i < candlesAfter.length; i++) {
    const c = candlesAfter[i];
    const { open, high, low, close, time_kst } = c;

    // Update peak
    if (high > peak) peak = high;

    // Calculate PnL at various points
    const pnlLow = (low - entry) / entry * 100;
    const pnlHigh = (high - entry) / entry * 100;
    const pnlClose = (close - entry) / entry * 100;
    const pnlPeak = (peak - entry) / entry * 100;

    // 1. Session End check (KST 08:00 ~ 10:00)
    const kstHour = getKstHour(time_kst);
    if (kstHour >= 8 && kstHour < 10) {
      return {
        exit: close,
        exit_pnl_pct: parseFloat(pnlClose.toFixed(4)),
        reason: 'SESSION_END',
        candles_traversed: i + 1,
        exit_candle_kst: time_kst,
      };
    }

    // 2. Hard SL check: if low <= entry * (1 - SL_PCT/100)
    const slLevel = entry * (1 - SL_PCT / 100);
    if (low <= slLevel) {
      const slExit = slLevel; // assume filled at SL level
      const slPnl = (slExit - entry) / entry * 100;
      return {
        exit: parseFloat(slExit.toFixed(6)),
        exit_pnl_pct: parseFloat(slPnl.toFixed(4)),
        reason: 'SL',
        candles_traversed: i + 1,
        exit_candle_kst: time_kst,
      };
    }

    // 3. Trailing stop: activate if peak pnl >= TRAIL_ACTIVATE_PCT
    if (pnlPeak >= TRAIL_ACTIVATE_PCT) {
      trailActive = true;
    }

    if (trailActive) {
      const trailStop = peak * (1 - TRAIL_DIST_PCT / 100);
      if (low <= trailStop) {
        const trailPnl = (trailStop - entry) / entry * 100;
        return {
          exit: parseFloat(trailStop.toFixed(6)),
          exit_pnl_pct: parseFloat(trailPnl.toFixed(4)),
          reason: 'TRAIL',
          candles_traversed: i + 1,
          exit_candle_kst: time_kst,
        };
      }
    }

    // 4. Time Stop: after 12 candles, if pnlClose < 0.3%
    if (i + 1 >= TIME_STOP_CANDLES) {
      if (pnlClose < TIME_STOP_MIN_PNL) {
        return {
          exit: close,
          exit_pnl_pct: parseFloat(pnlClose.toFixed(4)),
          reason: 'TIME_STOP',
          candles_traversed: i + 1,
          exit_candle_kst: time_kst,
        };
      }
    }
  }

  // Force exit at last candle
  const lastC = candlesAfter[candlesAfter.length - 1];
  const lastPnl = (lastC.close - entry) / entry * 100;
  return {
    exit: lastC.close,
    exit_pnl_pct: parseFloat(lastPnl.toFixed(4)),
    reason: 'FORCE_EXIT',
    candles_traversed: candlesAfter.length,
    exit_candle_kst: lastC.time_kst,
  };
}

function applyFee(pnlPct) {
  // Subtract round-trip fee (0.10%)
  return parseFloat((pnlPct - FEE_RATE * 100 * 2).toFixed(4));
}

async function main() {
  const priceData = JSON.parse(fs.readFileSync(path.join(ANALYSIS_DIR, 'price_data.json'), 'utf8'));

  const blockedResults = [];
  let skipCount = 0;

  for (const sig of priceData.blocked) {
    if (!sig.entry_price_estimate || !sig.candles_after || sig.candles_after.length === 0) {
      skipCount++;
      blockedResults.push({ ...sig, sim: null });
      continue;
    }
    const sim = simulate(sig.entry_price_estimate, sig.candles_after);
    sim.net_pnl_pct = applyFee(sim.exit_pnl_pct);
    blockedResults.push({ ...sig, sim });
  }

  const hcResults = [];
  for (const sig of priceData.hc_break) {
    // For HC_BREAK entries, use actual entry_price from log (not candle close)
    const entry = sig.entry_price || sig.entry_price_estimate;
    if (!entry || !sig.candles_after || sig.candles_after.length === 0) {
      hcResults.push({ ...sig, sim: null });
      continue;
    }
    const sim = simulate(entry, sig.candles_after);
    sim.net_pnl_pct = applyFee(sim.exit_pnl_pct);
    hcResults.push({ ...sig, sim });
  }

  const result = { blocked: blockedResults, hc_break: hcResults };
  fs.writeFileSync(path.join(ANALYSIS_DIR, 'simulation_result.json'), JSON.stringify(result, null, 2));

  console.log('=== SIMULATION RESULTS ===\n');
  console.log(`Blocked signals simulated: ${blockedResults.filter(s => s.sim).length} (skipped: ${skipCount})`);
  console.log(`HC_BREAK simulated: ${hcResults.filter(s => s.sim).length}`);

  console.log('\n--- Blocked Signal Results (sample) ---');
  for (const s of blockedResults.filter(s => s.sim).slice(0, 10)) {
    console.log(`${s.timestamp_kst} ${s.market} score=${s.score} entry=${s.entry_price_estimate} -> ${s.sim.reason} pnl=${s.sim.net_pnl_pct}%`);
  }

  console.log('\n--- HC_BREAK Actual Entry Results ---');
  for (const s of hcResults.filter(s => s.sim)) {
    console.log(`${s.timestamp_kst} ${s.market} score=${s.score} entry=${s.entry_price} -> ${s.sim.reason} pnl=${s.sim.net_pnl_pct}%`);
  }

  // Quick stats for sanity check
  const hcPnls = hcResults.filter(s => s.sim).map(s => s.sim.net_pnl_pct);
  if (hcPnls.length > 0) {
    const hcAvg = hcPnls.reduce((a, b) => a + b, 0) / hcPnls.length;
    const hcWins = hcPnls.filter(p => p > 0).length;
    console.log(`\nHC_BREAK sanity: ${hcPnls.length} trades, WR=${(hcWins/hcPnls.length*100).toFixed(0)}%, avg PnL=${hcAvg.toFixed(2)}%`);
  }

  console.log('\nSaved: simulation_result.json');
}

main().catch(e => { console.error(e); process.exit(1); });
