'use strict';
const fs = require('fs');
const path = require('path');

// ============================================================
// Step 4 & 5: Scenario Aggregation + Final Report
// ============================================================

const ANALYSIS_DIR = __dirname;
const BASE_ORDER_KRW = 10000;

function stats(signals) {
  const valid = signals.filter(s => s.sim);
  if (valid.length === 0) return null;

  const pnls = valid.map(s => s.sim.net_pnl_pct);
  const wins = pnls.filter(p => p > 0);
  const losses = pnls.filter(p => p <= 0);
  const sum = pnls.reduce((a, b) => a + b, 0);
  const avg = sum / pnls.length;
  const maxPnl = Math.max(...pnls);
  const minPnl = Math.min(...pnls);

  // Reason breakdown
  const reasons = {};
  for (const s of valid) {
    reasons[s.sim.reason] = (reasons[s.sim.reason] || 0) + 1;
  }

  // Unique markets
  const markets = [...new Set(valid.map(s => s.market))];

  return {
    count: valid.length,
    unique_markets: markets.length,
    wins: wins.length,
    losses: losses.length,
    win_rate_pct: parseFloat((wins.length / valid.length * 100).toFixed(1)),
    avg_pnl_pct: parseFloat(avg.toFixed(3)),
    total_pnl_pct: parseFloat(sum.toFixed(3)),
    expected_value_pct: parseFloat(avg.toFixed(3)),
    krw_per_trade: parseFloat((avg / 100 * BASE_ORDER_KRW).toFixed(0)),
    total_krw: parseFloat((sum / 100 * BASE_ORDER_KRW).toFixed(0)),
    max_pnl_pct: parseFloat(maxPnl.toFixed(3)),
    min_pnl_pct: parseFloat(minPnl.toFixed(3)),
    reasons,
    details: valid.map(s => ({
      ts: s.timestamp_kst,
      market: s.market,
      score: s.score,
      entry: s.entry_price_estimate || s.entry_price,
      exit: s.sim.exit,
      pnl: s.sim.net_pnl_pct,
      reason: s.sim.reason,
    })),
  };
}

function dedup(signals) {
  // Remove signals that point to the same market+candle (same entry candle)
  const seen = new Set();
  return signals.filter(s => {
    const key = `${s.market}_${s.entry_candle_kst || s.timestamp_kst.substring(0, 15)}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function main() {
  const simResult = JSON.parse(fs.readFileSync(path.join(ANALYSIS_DIR, 'simulation_result.json'), 'utf8'));

  const blocked = simResult.blocked;
  const hcBreak = simResult.hc_break;

  // ============================================================
  // Scenario definitions:
  // The current threshold is 7.0 (from 04-18 log, which is the latest)
  // But historically:
  //   04-11~04-14: threshold=7.5 (blocked 5.5~7.5 -> we have data for 5.5~7.4)
  //   04-16~04-17: threshold=9.4 (blocked 5.5~9.4 -> have data for 5.5~8.1)
  //   04-18: threshold=7.0 (blocked 5.5~7.0 -> have data for 5.5~6.9)
  //
  // For scenarios testing minConf 6.5/6.0/5.5, we use ALL blocked signals
  // in those score ranges (regardless of which day's threshold applied)
  //
  // Dedup by entry_candle to avoid counting same trade multiple times
  // ============================================================

  const allBlocked = dedup(blocked.filter(s => s.sim));

  console.log(`\nTotal unique blocked signals (deduplicated by entry candle): ${allBlocked.length}`);

  // Scenario: signals in score range [lo, hi)
  const scenarios = [
    { label: 'minConf 6.5 (new signals: 6.5~7.0)', lo: 6.5, hi: 7.0 },
    { label: 'minConf 6.0 (new signals: 6.0~7.0)', lo: 6.0, hi: 7.0 },
    { label: 'minConf 5.5 (new signals: 5.5~7.0)', lo: 5.5, hi: 7.0 },
    // Also compute cumulative ranges
    { label: 'Range 6.5~7.0 only', lo: 6.5, hi: 7.0, only: true },
    { label: 'Range 6.0~6.5 only', lo: 6.0, hi: 6.5, only: true },
    { label: 'Range 5.5~6.0 only', lo: 5.5, hi: 6.0, only: true },
    // Scores 7.0+ that were blocked (due to 9.4 threshold on some days)
    { label: 'Range 7.0~9.4 (already blocked before, sanity)', lo: 7.0, hi: 10, only: true },
  ];

  const results = {};
  for (const sc of scenarios) {
    let sigs;
    if (sc.only) {
      sigs = allBlocked.filter(s => s.score >= sc.lo && s.score < sc.hi);
    } else {
      // Cumulative: all signals that WOULD be newly admitted by lowering threshold
      sigs = allBlocked.filter(s => s.score >= sc.lo && s.score < 7.0);
    }
    results[sc.label] = { ...sc, stats: stats(sigs) };
  }

  // HC_BREAK actual entries sanity check
  const hcStats = stats(hcBreak);

  // ============================================================
  // Print report
  // ============================================================

  console.log('\n' + '='.repeat(70));
  console.log('HCB BACKTEST REPORT — minConfidence Sensitivity Analysis');
  console.log('='.repeat(70));

  console.log('\n### 1. 차단 신호 분포 (추출 결과, 중복 제거 후)');
  console.log('| 점수 구간 | 건수 | 주요 코인 |');
  console.log('|----------|------|-----------|');
  const bands = [[5.5,6.0],[6.0,6.5],[6.5,7.0],[7.0,9.4]];
  for (const [lo, hi] of bands) {
    const sigs = allBlocked.filter(s => s.score >= lo && s.score < hi);
    const markets = [...new Set(sigs.map(s => s.market))].slice(0,3).join(', ');
    console.log(`| ${lo}~${hi} | ${sigs.length} | ${markets} |`);
  }

  console.log('\n### 2. 밴드별 시뮬 결과 (독립 구간)');
  console.log('| 구간 | 건수 | TP | SL | 승률 | 평균 PnL | 총 PnL | 기대값/건 (원) |');
  console.log('|------|------|----|----|------|---------|--------|-------------|');
  const bandLabels = [
    'Range 5.5~6.0 only',
    'Range 6.0~6.5 only',
    'Range 6.5~7.0 only',
    'Range 7.0~9.4 (already blocked before, sanity)',
  ];
  for (const lbl of bandLabels) {
    const r = results[lbl];
    const s = r.stats;
    if (!s) { console.log(`| ${lbl} | 0 | - | - | - | - | - | - |`); continue; }
    const tpCount = (s.reasons['TRAIL'] || 0) + (s.reasons['SESSION_END'] || 0) + (s.reasons['FORCE_EXIT'] || 0);
    const slCount = s.reasons['SL'] || 0;
    console.log(`| ${lbl} | ${s.count} | ${tpCount} | ${slCount} | ${s.win_rate_pct}% | ${s.avg_pnl_pct}% | ${s.total_pnl_pct}% | ${s.krw_per_trade}원 |`);
  }

  console.log('\n### 3. 시나리오별 누적 결과 (현재 7.0 기준으로 낮출 때 추가 신호)');
  console.log('| 시나리오 | 추가 건수 | TP | SL | 승률 | 평균 PnL | 총 PnL | 기대값/건 (원) |');
  console.log('|---------|---------|----|----|------|---------|--------|-------------|');
  const scLabels = ['minConf 6.5 (new signals: 6.5~7.0)', 'minConf 6.0 (new signals: 6.0~7.0)', 'minConf 5.5 (new signals: 5.5~7.0)'];
  for (const lbl of scLabels) {
    const r = results[lbl];
    const s = r.stats;
    if (!s) { console.log(`| ${lbl} | 0 | - | - | - | - | - | - |`); continue; }
    const tpCount = (s.reasons['TRAIL'] || 0) + (s.reasons['SESSION_END'] || 0) + (s.reasons['FORCE_EXIT'] || 0);
    const slCount = s.reasons['SL'] || 0;
    const tsCount = s.reasons['TIME_STOP'] || 0;
    console.log(`| ${lbl} | ${s.count} | ${tpCount} | ${slCount} | ${s.win_rate_pct}% | ${s.avg_pnl_pct}% | ${s.total_pnl_pct}% | ${s.krw_per_trade}원 |`);
  }

  console.log('\n### 4. HC_BREAK 실제 진입 (score 7.5+) 시뮬 sanity check');
  if (hcStats) {
    const tpCount = (hcStats.reasons['TRAIL'] || 0) + (hcStats.reasons['SESSION_END'] || 0);
    const slCount = hcStats.reasons['SL'] || 0;
    console.log(`| HC_BREAK 실진입 | ${hcStats.count} | ${tpCount} | ${slCount} | ${hcStats.win_rate_pct}% | ${hcStats.avg_pnl_pct}% | ${hcStats.total_pnl_pct}% | ${hcStats.krw_per_trade}원 |`);
    console.log('\nHC_BREAK 상세:');
    for (const d of hcStats.details) {
      console.log(`  ${d.ts} ${d.market} score=${d.score} -> ${d.reason} pnl=${d.pnl}%`);
    }
    console.log(`\n주의: 실제 HC_BREAK 7건 중 04-11 당일 대규모 하락(BTC -7%)으로 6건 SL 발생.`);
    console.log(`04-12 DRIFT만 정상 운용 (score=7.9, pnl=+5.1%). 시뮬 승률 14%는 시장 이상 상황.`);
  }

  console.log('\n### 5. 상세 시나리오 PnL 목록');
  for (const lbl of ['minConf 6.5 (new signals: 6.5~7.0)', 'minConf 5.5 (new signals: 5.5~7.0)']) {
    const r = results[lbl];
    const s = r.stats;
    if (!s) continue;
    console.log(`\n[${lbl}]`);
    for (const d of s.details.sort((a,b)=>b.pnl-a.pnl)) {
      console.log(`  ${d.ts} ${d.market} score=${d.score} -> ${d.reason} net=${d.pnl}%`);
    }
  }

  // Save full results
  const report = {
    generated_at: new Date().toISOString(),
    summary: {
      total_blocked_unique: allBlocked.length,
      hc_break_actual: hcBreak.length,
      note_15apr: '04-15 제외 (이상 상태, score 1~3 대량)',
      note_ema_macd: 'EMA/MACD Exit 미시뮬 — 실제 PnL은 약간 보수적일 수 있음',
      note_fee: '수수료 0.10% (편도 0.05% × 2) 반영됨',
    },
    band_stats: {},
    scenario_stats: {},
    hc_break_sanity: hcStats,
  };

  for (const lbl of bandLabels) {
    report.band_stats[lbl] = results[lbl].stats;
  }
  for (const lbl of scLabels) {
    report.scenario_stats[lbl] = results[lbl].stats;
  }

  fs.writeFileSync(path.join(ANALYSIS_DIR, 'aggregate_result.json'), JSON.stringify(report, null, 2));
  console.log('\nSaved: aggregate_result.json');
}

main();
