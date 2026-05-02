// 1분봉 실가격 백테스트 시뮬레이터 (Split-Exit + SL + 시간만료 + L1 지연 진입)
// 입력: .analysis/raw/entry_candles.json + .analysis/raw/trades_parsed.json
// 출력: 시나리오별 결과
const fs = require('fs');

const CANDLES = JSON.parse(fs.readFileSync('.analysis/raw/entry_candles.json', 'utf8'));
const PARSED = JSON.parse(fs.readFileSync('.analysis/raw/trades_parsed.json', 'utf8'));
const ENTRIES = PARSED.pairs;

// 청산 룰 파라미터
const ARMED_THR = 1.5;    // peak >= entry * (1 + 1.5%) 면 armed
const RATIO_1ST = 0.40;
const RATIO_2ND = 0.60;
const COOLDOWN_MIN = 1;   // 60초 = 1 캔들
const ROUND_FEE_PCT = 0.10; // round-trip 슬리피지+수수료 합산

// SL by scanner (V125)
const SL_PCT = { OP: 2.8, MR: 2.8, AD: 3.0 };  // WIDE 기준

// 시간 만료 (KST)
function timeExitMinKst(scanner, entryDate) {
  if (scanner === 'MR') return setKst(entryDate, 10, 0);
  if (scanner === 'OP') return setKst(entryDate, 12, 0);
  if (scanner === 'AD') {
    const d = new Date(entryDate);
    d.setDate(d.getDate() + 1);
    return setKst(d, 8, 59);
  }
  return setKst(entryDate, 23, 0);
}
function setKst(d, h, m) {
  const dt = new Date(d);
  dt.setHours(h, m, 0, 0);
  return dt;
}
function parseKst(ts) { return new Date(ts.replace(' ', 'T') + '+09:00'); }

// Drop 룰들
const RULES = {
  V129: {
    name: 'V129 baseline (drop1=2.0, drop2=2.0)',
    drop1: () => 2.0,
    drop2: () => 2.0,
  },
  V115: {
    name: 'V115 legacy (drop1=0.5, drop2=1.2)',
    drop1: () => 0.5,
    drop2: () => 1.2,
  },
  ladderA: {
    name: 'Ladder A (peak<2:0.5/<3:1.0/<5:1.5/>=5:2.0)',
    drop1: (peak) => peak < 2 ? 0.5 : peak < 3 ? 1.0 : peak < 5 ? 1.5 : 2.0,
    drop2: (peak) => peak < 2 ? 1.0 : peak < 3 ? 1.2 : peak < 5 ? 1.5 : 2.0,
  },
  ladderB: {
    name: 'Ladder B (peak<2:0.4/<3:0.8/<5:1.2/>=5:1.6)',
    drop1: (peak) => peak < 2 ? 0.4 : peak < 3 ? 0.8 : peak < 5 ? 1.2 : 1.6,
    drop2: (peak) => peak < 2 ? 0.8 : peak < 3 ? 1.0 : peak < 5 ? 1.2 : 1.6,
  },
};

// 시뮬레이션 1건
function simulate(entry, candles, rule, options = {}) {
  // options: { delayEntryMin: number | null }
  // entry: { market, entry_ts, entry_price, qty, scanner }
  if (!candles || candles.length === 0) return { skipped: true, reason: 'NO_CANDLES' };

  let entryPrice = entry.entry_price;
  let firstCandleIdx = 0;

  // L1 지연 진입
  if (options.delayEntryMin != null) {
    const N = options.delayEntryMin;
    if (candles.length <= N) return { skipped: true, reason: 'INSUFFICIENT_DATA' };
    const checkCandle = candles[N - 1] || candles[N];
    // Re-entry 조건: N분 후 close >= 원래 진입가 (모멘텀 유지)
    if (checkCandle.c < entry.entry_price) {
      return { skipped: true, reason: 'L1_NO_MOMENTUM', original_entry: entry.entry_price, post_close: checkCandle.c };
    }
    // 진입가는 원본 유지 (signal 시점), 청산 시작은 N분 이후 캔들부터
    // (실전에선 N분 후 시점에 시장가 매수하므로 entryPrice도 그 시점 close로 갱신해야 정확)
    entryPrice = checkCandle.c;
    firstCandleIdx = N;
  }

  let peak = entryPrice;
  let armed = false;
  let split1 = null;
  let split2 = null;
  let cooldownUntilIdx = -1;
  const slPct = SL_PCT[entry.scanner] || 2.8;
  const slLevel = entryPrice * (1 - slPct / 100);
  const timeExit = timeExitMinKst(entry.scanner, parseKst(entry.entry_ts));

  for (let i = firstCandleIdx; i < candles.length; i++) {
    const c = candles[i];
    const cTime = new Date(c.ts_kst.replace('T', ' ') + '+09:00').getTime();
    const high = c.h, low = c.l, close = c.c;

    // peak 갱신
    if (high > peak) peak = high;
    const peakPct = (peak - entryPrice) / entryPrice * 100;

    // 1) Time exit
    if (cTime >= timeExit.getTime()) {
      return finalizeExit(entry, entryPrice, close, split1, split2, peak, 'TIME_EXIT', i);
    }

    // 2) SL check (low가 SL level 이하 도달)
    // SL은 split1 발동 전에만 체크 (split1 후엔 SPLIT_2ND_TRAIL이 작동)
    if (!split1 && low <= slLevel) {
      // SL 청산 (전량)
      return finalizeExit(entry, entryPrice, slLevel, null, null, peak, 'SL_WIDE', i);
    }

    // 3) Armed check
    if (!armed && peakPct >= ARMED_THR) armed = true;

    // 4) SPLIT_1ST check (armed 상태에서 peak 대비 drop1% 떨어지면)
    if (armed && !split1) {
      const drop1 = rule.drop1(peakPct);
      const target1 = peak * (1 - drop1 / 100);
      if (low <= target1) {
        split1 = { price: target1, idx: i };
        cooldownUntilIdx = i + COOLDOWN_MIN;
      }
    }

    // 5) SPLIT_2ND check (split1 후 cooldown 지나면 peak 대비 drop2%)
    if (split1 && i >= cooldownUntilIdx && !split2) {
      const drop2 = rule.drop2(peakPct);
      const target2 = peak * (1 - drop2 / 100);
      if (low <= target2) {
        split2 = { price: target2, idx: i };
        return finalizeExit(entry, entryPrice, null, split1, split2, peak, 'SPLIT_2ND_TRAIL', i);
      }
    }
  }

  // 시뮬 종료 — 캔들 부족, 마지막 close로 청산 가정
  const last = candles[candles.length - 1];
  return finalizeExit(entry, entryPrice, last.c, split1, split2, peak, 'END_OF_DATA', candles.length - 1);
}

function finalizeExit(entry, entryPrice, finalPrice, split1, split2, peak, reason, idx) {
  const qty = entry.qty;
  let pnl = 0;
  let ratio_executed = 0;
  let segments = [];
  if (split1) {
    const seg1 = { ratio: RATIO_1ST, price: split1.price, roi_pct: (split1.price - entryPrice) / entryPrice * 100 };
    segments.push(seg1);
    ratio_executed += RATIO_1ST;
  }
  if (split2) {
    const seg2 = { ratio: RATIO_2ND, price: split2.price, roi_pct: (split2.price - entryPrice) / entryPrice * 100 };
    segments.push(seg2);
    ratio_executed += RATIO_2ND;
  }
  // 남은 비율은 finalPrice로 청산
  const remainRatio = 1 - ratio_executed;
  if (remainRatio > 0.001 && finalPrice != null) {
    const segR = { ratio: remainRatio, price: finalPrice, roi_pct: (finalPrice - entryPrice) / entryPrice * 100 };
    segments.push(segR);
  }
  // weighted PnL (KRW)
  const entryValue = entry.entry_price * (entry.qty || 1);
  let avgRoi = 0;
  for (const s of segments) avgRoi += s.ratio * s.roi_pct;
  avgRoi -= ROUND_FEE_PCT; // round-trip fee
  const pnlKrw = entryValue * avgRoi / 100;
  return {
    market: entry.market, scanner: entry.scanner, entry_ts: entry.entry_ts,
    entry_price: entryPrice, peak, peak_pct: (peak - entryPrice) / entryPrice * 100,
    segments, avg_roi_pct: avgRoi, pnl_krw: Math.round(pnlKrw),
    final_reason: reason, exit_idx: idx,
    split1_price: split1 ? split1.price : null, split2_price: split2 ? split2.price : null,
    final_price: finalPrice,
  };
}

// 시나리오 실행
function runScenario(scenarioName, rule, options = {}) {
  const results = [];
  let skippedL1 = 0;
  for (const e of ENTRIES) {
    const cacheKey = `${e.market}|${e.entry_ts}`;
    const candles = CANDLES[cacheKey];
    const r = simulate(e, candles, rule, options);
    if (r.skipped) {
      if (r.reason === 'L1_NO_MOMENTUM') skippedL1++;
      continue;
    }
    results.push(r);
  }
  const closedN = results.length;
  const wins = results.filter(r => r.pnl_krw > 0);
  const losses = results.filter(r => r.pnl_krw <= 0);
  const totalPnL = results.reduce((s, r) => s + r.pnl_krw, 0);
  // by reason
  const byReason = {};
  for (const r of results) {
    byReason[r.final_reason] = byReason[r.final_reason] || { n: 0, pnl: 0, wins: 0 };
    byReason[r.final_reason].n++;
    byReason[r.final_reason].pnl += r.pnl_krw;
    if (r.pnl_krw > 0) byReason[r.final_reason].wins++;
  }
  // by scanner
  const byScanner = {};
  for (const r of results) {
    byScanner[r.scanner] = byScanner[r.scanner] || { n: 0, pnl: 0, wins: 0 };
    byScanner[r.scanner].n++;
    byScanner[r.scanner].pnl += r.pnl_krw;
    if (r.pnl_krw > 0) byScanner[r.scanner].wins++;
  }
  return {
    scenario: scenarioName, rule_name: rule.name,
    options, n_total: ENTRIES.length, n_traded: closedN, n_skipped_L1: skippedL1,
    wins: wins.length, losses: losses.length, winrate_pct: closedN ? +(wins.length / closedN * 100).toFixed(2) : 0,
    total_pnl_krw: totalPnL, byReason, byScanner, results,
  };
}

const SCENARIOS = [
  { key: 'V129_baseline',  rule: RULES.V129,    options: {} },
  { key: 'LadderA',        rule: RULES.ladderA, options: {} },
  { key: 'LadderB',        rule: RULES.ladderB, options: {} },
  { key: 'V115_legacy',    rule: RULES.V115,    options: {} },
  { key: 'L1_60s_V129',    rule: RULES.V129,    options: { delayEntryMin: 1 } },
  { key: 'L1_60s_LadderA', rule: RULES.ladderA, options: { delayEntryMin: 1 } },
  { key: 'L1_120s_V129',   rule: RULES.V129,    options: { delayEntryMin: 2 } },
  { key: 'L1_120s_LadderA',rule: RULES.ladderA, options: { delayEntryMin: 2 } },
];

const allResults = {};
for (const sc of SCENARIOS) {
  allResults[sc.key] = runScenario(sc.key, sc.rule, sc.options);
}

// 출력
console.log('='.repeat(95));
console.log('1분봉 실가격 백테스트 결과 (8일, 73건 진입)');
console.log('='.repeat(95));
console.log(' Scenario                     |  Trad |  Skip |  Win | Loss | WR%   |  Total PnL (KRW)');
console.log('-'.repeat(95));
for (const [k, r] of Object.entries(allResults)) {
  console.log(
    ` ${k.padEnd(28)} |  ${String(r.n_traded).padStart(3)}  |  ${String(r.n_skipped_L1).padStart(3)}  |  ${String(r.wins).padStart(3)} |  ${String(r.losses).padStart(3)} | ${String(r.winrate_pct).padStart(5)} | ${String(r.total_pnl_krw.toLocaleString('ko-KR')).padStart(15)}`
  );
}
console.log('-'.repeat(95));

// V129 baseline 사유별
const base = allResults.V129_baseline;
console.log('\nV129 baseline — 사유별:');
for (const [k, v] of Object.entries(base.byReason)) {
  console.log(`  ${k.padEnd(20)}: n=${String(v.n).padStart(3)}, wins=${String(v.wins).padStart(3)}, pnl=${v.pnl.toLocaleString('ko-KR').padStart(12)}`);
}

// 시나리오별 차이 (LadderA - V129)
console.log('\n시나리오별 vs V129 차이 (Δ PnL):');
for (const [k, r] of Object.entries(allResults)) {
  if (k === 'V129_baseline') continue;
  console.log(`  ${k.padEnd(28)}: Δ ${(r.total_pnl_krw - base.total_pnl_krw).toLocaleString('ko-KR').padStart(10)} (${r.total_pnl_krw - base.total_pnl_krw >= 0 ? '+' : ''}${((r.total_pnl_krw - base.total_pnl_krw) / Math.abs(base.total_pnl_krw) * 100).toFixed(1)}%)`);
}

// 저장
fs.writeFileSync('.analysis/raw/backtest_v2_results.json', JSON.stringify(allResults, null, 2));
console.log('\nSaved: .analysis/raw/backtest_v2_results.json');
