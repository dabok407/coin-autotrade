/**
 * Strategy (b) 도달 시나리오 시뮬레이터
 * 목적: V130 22건 데이터 기반으로 (b)전략 — WR 50%+큰수익/작은손실 — 도달 경로 정량 분석
 * 생성: 2026-05-02
 */

'use strict';

const fs = require('fs');
const path = require('path');

// ──────────────────────────────────────────────
// 1. 원본 데이터 로드
// ──────────────────────────────────────────────
const trades19d = require('./trades_19d_parsed.json');
const preciseBacktest = require('./raw/precise_backtest_results.json');
const intraday5p = require('./raw/intraday_5p_times.json');

// V130 이후 유효 거래 (STILL_OPEN 제외)
const v130Trades = trades19d.pairs.filter(p =>
  p.v130_era === 'after' && p.exit_reason !== 'STILL_OPEN'
);

// ──────────────────────────────────────────────
// 2. 기초 통계
// ──────────────────────────────────────────────
function calcStats(trades) {
  const wins = trades.filter(t => t.pnl_total > 0);
  const losses = trades.filter(t => t.pnl_total <= 0);
  const grossWin = wins.reduce((s, t) => s + t.pnl_total, 0);
  const grossLoss = Math.abs(losses.reduce((s, t) => s + t.pnl_total, 0));
  const totalPnl = grossWin - grossLoss;
  const pf = grossLoss === 0 ? Infinity : grossWin / grossLoss;
  const avgWin = wins.length ? grossWin / wins.length : 0;
  const avgLoss = losses.length ? grossLoss / losses.length : 0;
  return {
    n: trades.length,
    wins: wins.length,
    losses: losses.length,
    wr: wins.length / trades.length,
    grossWin,
    grossLoss,
    totalPnl,
    pf,
    avgWin,
    avgLoss,
    dailyPnl: totalPnl / 4
  };
}

const baseline = calcStats(v130Trades);

// ──────────────────────────────────────────────
// 3. 전략 (a) vs (b) 정량 정의
// ──────────────────────────────────────────────
function buildStrategyDef(name, wr, avgWin, avgLoss, n) {
  const wins = Math.round(n * wr);
  const losses = n - wins;
  const grossWin = avgWin * wins;
  const grossLoss = avgLoss * losses;
  return {
    name,
    n, wins, losses, wr,
    avgWin, avgLoss,
    grossWin, grossLoss,
    totalPnl: grossWin - grossLoss,
    pf: grossWin / grossLoss,
    dailyPnl: (grossWin - grossLoss) / 4
  };
}

const strategyA = buildStrategyDef('(a) 고승률+작은수익', 0.67, 2398, 3000, 22);
const strategyB = buildStrategyDef('(b) 목표: 50%승률+큰수익/작은손실', 0.50, 8000, 3000, 22);

// ──────────────────────────────────────────────
// 4. 손실 건 분류 (차단 우선순위용)
// ──────────────────────────────────────────────
const lossTradesRaw = v130Trades.filter(t => t.pnl_total < 0);
const lossTradesSorted = [...lossTradesRaw].sort((a, b) => a.pnl_total - b.pnl_total);
// 손실 크기 순: -14714(DRIFT-OP), -7591(DRIFT-AD), -7235(RAY), -6776(SOON), -6573(MANTRA), ...

// ──────────────────────────────────────────────
// 5. 조합 A: 진입 필터 (손실 N건 차단)
// ──────────────────────────────────────────────
function simulateFilterBlock(n_remove) {
  const sortedLosses = lossTradesSorted.slice(0, n_remove).map(t => t.pnl_total);
  const removedLossAmt = Math.abs(sortedLosses.reduce((s, v) => s + v, 0));
  const remainTrades = v130Trades.filter(t => {
    if (t.pnl_total >= 0) return true;
    const idx = lossTradesSorted.findIndex(l => l === t);
    return idx >= n_remove;
  });
  const stats = calcStats(remainTrades);
  return {
    combo: 'A',
    n_remove,
    blocked_markets: lossTradesSorted.slice(0, n_remove).map(t => `${t.market}(${t.pnl_total})`),
    ...stats
  };
}

const comboA = [0, 2, 4, 5, 6, 8].map(n => simulateFilterBlock(n));

// ──────────────────────────────────────────────
// 6. 조합 B: Trail 폭 확대 (precise_backtest 시나리오 기반)
// ──────────────────────────────────────────────
// 22건 기준 환산: 25건 backtest의 SL_TIGHT 8건 동일 -> 22건 비율 = 22/25 = 0.88
const SCALE_22 = 22 / 25;

function extractScenario(key) {
  const s = preciseBacktest.scenario_agg[key];
  if (!s) return null;
  const nc = s.no_cap;
  const splitPnl = nc.byReason.SPLIT_2ND_TRAIL ? nc.byReason.SPLIT_2ND_TRAIL.pnl : 0;
  const splitN = nc.byReason.SPLIT_2ND_TRAIL ? nc.byReason.SPLIT_2ND_TRAIL.n : 0;
  const slTightPnl = nc.byReason.SL_TIGHT ? nc.byReason.SL_TIGHT.pnl : 0;

  // V130 22건 환산 (비율 적용, SL_TIGHT 손실은 고정)
  const pnl22 = nc.totalPnl * SCALE_22;
  return {
    combo: 'B',
    scenario: key,
    name: s.name,
    n25: nc.n,
    wins25: nc.wins,
    wr25: nc.wr,
    totalPnl25: nc.totalPnl,
    // 22건 환산
    totalPnl22: Math.round(pnl22),
    avgSplitWin: splitN > 0 ? Math.round(splitPnl / splitN) : 0,
    splitN,
    slTightPnl
  };
}

const comboB = ['S0','S1','S2','S4','S5','S6','S9'].map(k => extractScenario(k)).filter(Boolean);

// ──────────────────────────────────────────────
// 7. 조합 C: AD 스캐너 활성화
// ──────────────────────────────────────────────
// 근거: 4일 야간 5%+ 32건, AD 이론 47건 커버 가능, 실제 진입 2건뿐
const adScenarios = [
  { name: '보수 (WR40%, avgWin5000, avgLoss5000)', wr: 0.40, avgWin: 5000, avgLoss: 5000, addN: 16 },
  { name: '중립 (WR45%, avgWin6000, avgLoss4000)', wr: 0.45, avgWin: 6000, avgLoss: 4000, addN: 16 },
  { name: '낙관 (WR50%, avgWin8000, avgLoss3500)', wr: 0.50, avgWin: 8000, avgLoss: 3500, addN: 16 }
];

const comboC = adScenarios.map(sc => {
  const addWins = Math.round(sc.addN * sc.wr);
  const addLosses = sc.addN - addWins;
  const addGrossWin = addWins * sc.avgWin;
  const addGrossLoss = addLosses * sc.avgLoss;
  const addPnl = addGrossWin - addGrossLoss;
  const newGrossWin = baseline.grossWin + addGrossWin;
  const newGrossLoss = baseline.grossLoss + addGrossLoss;
  return {
    combo: 'C',
    scenario: sc.name,
    addN: sc.addN,
    addWins,
    addLosses,
    addPnl,
    newTotalPnl: baseline.totalPnl + addPnl,
    newPF: newGrossWin / newGrossLoss,
    newDailyPnl: (baseline.totalPnl + addPnl) / 4,
    newGrossWin,
    newGrossLoss
  };
});

// ──────────────────────────────────────────────
// 8. 조합 D: Split 30/70 단독 효과
// ──────────────────────────────────────────────
const S0 = preciseBacktest.scenario_agg.S0.no_cap;
const S9 = preciseBacktest.scenario_agg.S9.no_cap;
const splitImprovement = (S9.byReason.SPLIT_2ND_TRAIL.pnl - S0.byReason.SPLIT_2ND_TRAIL.pnl) * SCALE_22;

const comboD = {
  combo: 'D',
  name: 'Split 30/70 (S9 vs S0)',
  improvementAmt22: Math.round(splitImprovement),
  newTotalPnl: Math.round(baseline.totalPnl + splitImprovement),
  newPF: (baseline.grossWin + splitImprovement) / baseline.grossLoss,
  newDailyPnl: Math.round((baseline.totalPnl + splitImprovement) / 4),
  note: '단독 개선 효과 미미 (2%)—다른 조합과 결합 시 의미'
};

// ──────────────────────────────────────────────
// 9. 조합 E: A+B+C+D 결합 시나리오
// ──────────────────────────────────────────────
// 가정 조합:
//   A: 손실 3건 차단 (DRIFT-OP -14714, DRIFT-AD -7591, RAY -7235) → -29540 제거
//   B: Trail Ratchet (S6) → avgWin 2398→3500 (rough 환산, 22건 split wins=11)
//   C: AD 낙관 → +36000 PnL (win:+64000, loss:-28000)
//   D: Split 30/70 → B에 내재 (별도 추가 미미)

function buildCombinedScenario(filterN, trailAvgWin, adScenario) {
  // A: 필터
  const filterStats = simulateFilterBlock(filterN);

  // B: trail 개선 후 avgWin
  const newGrossWin_base = trailAvgWin * filterStats.wins;

  // C: AD 추가
  const ad = adScenarios.find(s => s.name.includes(adScenario)) || adScenarios[2];
  const addWins = Math.round(ad.addN * ad.wr);
  const addLosses = ad.addN - addWins;
  const addGrossWin = addWins * ad.avgWin;
  const addGrossLoss = addLosses * ad.avgLoss;

  const totalGrossWin = newGrossWin_base + addGrossWin;
  const totalGrossLoss = filterStats.grossLoss + addGrossLoss;
  const totalPnl = totalGrossWin - totalGrossLoss;
  const totalN = filterStats.n + ad.addN;
  const totalWins = filterStats.wins + addWins;
  const totalLosses = filterStats.losses + addLosses;

  return {
    combo: 'E',
    label: `A(filter-${filterN}) + B(trail-${trailAvgWin}) + C(AD-${adScenario})`,
    filter_n: filterN,
    trail_avgWin: trailAvgWin,
    ad_scenario: adScenario,
    total_n: totalN,
    total_wins: totalWins,
    total_losses: totalLosses,
    wr: totalWins / totalN,
    avgWin: totalGrossWin / totalWins,
    avgLoss: totalGrossLoss / totalLosses,
    totalGrossWin,
    totalGrossLoss,
    totalPnl,
    pf: totalGrossWin / totalGrossLoss,
    dailyPnl: totalPnl / 4
  };
}

const comboE = [
  buildCombinedScenario(3, 3500, '낙관'),  // 현실적 목표
  buildCombinedScenario(5, 4500, '낙관'),  // 중기 목표
  buildCombinedScenario(3, 5000, '낙관'),  // 이상적 (trail 대폭 확대)
  buildCombinedScenario(5, 5000, '중립'),  // 균형 시나리오
];

// ──────────────────────────────────────────────
// 10. (b) 전략 도달 경로 로드맵
// ──────────────────────────────────────────────
const roadmap = {
  target_strategy_b: {
    wr: '50%',
    avgWin: '8,000원+',
    avgLoss: '3,000원 이하',
    pf: '2.67',
    daily_pnl: '+13,750원/일 (22건/4일 기준)'
  },
  phases: [
    {
      phase: 1,
      label: 'Phase 1 — 즉시 (1주 이내)',
      changes: [
        'Trail Ratchet 도입: SPLIT_2ND_TRAIL drop 고정(2%) → Ratchet(0.7/1.0/2.5/4.0/5.0%)',
        'Split 비율 변경: 50/50 → 30/70 (2차 홀딩 비중 확대)',
        'DRIFT류 SL_WIDE 진입 조건 강화: qs 점수 최소 3.5 이상, vol 5.0 이상 강제'
      ],
      expected_metrics: {
        avgWin: '3,000~3,500원',
        avgLoss: '5,500~6,000원 (SL 구조 미변경 시)',
        pf: '0.50~0.55',
        daily_pnl: '-6,000 ~ -4,000원/일',
        note: 'Trail 확대만으로 avgWin 증가, avgLoss는 아직 그대로'
      }
    },
    {
      phase: 2,
      label: 'Phase 2 — 백테 후 2주',
      changes: [
        'SL_TIGHT 재설계: 현재 2.5% → 진입 시 변동성(ATR 기반) 동적 SL (1.5~3.5% 범위)',
        '진입 필터 강화: SL_WIDE 손실 공통 패턴 (qs 낮은 종목, rsi>75 진입) 차단',
        'AD 스캐너 HC 점수 9.4 → 9.0 완화 + 19:00~21:00 집중 시간대 허용',
        '거래당 기준금 검토: 손실폭 제한을 위해 1만원 → 7,500원 (SL_TIGHT 시 손실 -4,500원으로 감소)'
      ],
      expected_metrics: {
        avgWin: '4,000~5,000원',
        avgLoss: '4,000~4,500원',
        pf: '0.90~1.10',
        daily_pnl: '-1,000 ~ +1,500원/일 (손익분기 수준)',
        note: 'avgLoss 6,180 → 4,000 감소가 PF 1.0 돌파의 핵심'
      }
    },
    {
      phase: 3,
      label: 'Phase 3 — 안정 후 1개월',
      changes: [
        'AD 스캐너 실질 활성화 (야간 5%+ 32건/4일 → 실진입 10~16건)',
        'Trail Tiered SL v2: peak 5%+ 달성 시 BEV 보장 + drop 4~5% 적용',
        '승리 케이스 수익 상한 제거 (현재 SPLIT_2ND_TRAIL이 너무 빠른 청산)',
        '큰추세 포지션 분리: peak 5%+ 도달 시 1/3만 청산, 나머지 큰 Trail로 보존'
      ],
      expected_metrics: {
        avgWin: '7,000~9,000원',
        avgLoss: '3,000~3,500원',
        pf: '2.0~2.7',
        daily_pnl: '+8,000~+14,000원/일',
        note: '(b) 전략 완전 도달 구간. 샘플 최소 100건 이상 필요'
      }
    }
  ]
};

// ──────────────────────────────────────────────
// 11. 최종 결과 조립
// ──────────────────────────────────────────────
const result = {
  meta: {
    generated: new Date().toISOString(),
    base_trades: v130Trades.length,
    period: '2026-04-29 ~ 2026-05-02 (4일)',
    warning: '표본 22건/4일 한계: 신뢰구간 넓음. 방향성만 참고, 수치는 추정값'
  },
  baseline: {
    ...baseline,
    avgWin: Math.round(baseline.avgWin),
    avgLoss: Math.round(baseline.avgLoss),
    pf: parseFloat(baseline.pf.toFixed(3)),
    dailyPnl: Math.round(baseline.dailyPnl)
  },
  strategy_definitions: {
    current: {
      name: 'V130 현재',
      wr: '50%', avgWin: 2398, avgLoss: 6180, pf: 0.39,
      totalPnl: -41603, dailyPnl: -10401
    },
    strategy_a: {
      name: strategyA.name,
      wr: '67%', avgWin: 2398, avgLoss: 3000,
      pf: parseFloat(strategyA.pf.toFixed(2)),
      totalPnl: strategyA.totalPnl, dailyPnl: Math.round(strategyA.dailyPnl)
    },
    strategy_b: {
      name: strategyB.name,
      wr: '50%', avgWin: 8000, avgLoss: 3000,
      pf: parseFloat(strategyB.pf.toFixed(2)),
      totalPnl: strategyB.totalPnl, dailyPnl: Math.round(strategyB.dailyPnl)
    }
  },
  combo_a: comboA.map(r => ({
    n_remove: r.n_remove,
    blocked: r.blocked_markets,
    wr: parseFloat((r.wr * 100).toFixed(1)),
    pf: parseFloat(r.pf.toFixed(2)),
    totalPnl: r.totalPnl,
    dailyPnl: Math.round(r.dailyPnl)
  })),
  combo_b: comboB,
  combo_c: comboC.map(r => ({
    scenario: r.scenario,
    addN: r.addN,
    addPnl: r.addPnl,
    newTotalPnl: r.newTotalPnl,
    newPF: parseFloat(r.newPF.toFixed(2)),
    newDailyPnl: Math.round(r.newDailyPnl)
  })),
  combo_d: comboD,
  combo_e: comboE.map(r => ({
    label: r.label,
    wr: parseFloat((r.wr * 100).toFixed(1)) + '%',
    avgWin: Math.round(r.avgWin),
    avgLoss: Math.round(r.avgLoss),
    pf: parseFloat(r.pf.toFixed(2)),
    totalPnl: Math.round(r.totalPnl),
    dailyPnl: Math.round(r.dailyPnl)
  })),
  roadmap
};

// ──────────────────────────────────────────────
// 12. 출력
// ──────────────────────────────────────────────
const outPath = path.join(__dirname, 'raw', 'strategy_b_results.json');
fs.writeFileSync(outPath, JSON.stringify(result, null, 2));
console.log('저장 완료:', outPath);

// 콘솔 요약
console.log('\n===== 핵심 요약 =====');
console.log('현재 V130 22건: WR 50%, PF 0.39, 4일 -41,603원');
console.log('');
console.log('--- 조합 A: 진입 필터 ---');
comboA.forEach(r => {
  if ([0,2,4,5,6].includes(r.n_remove)) {
    console.log(`  차단 ${r.n_remove}건: WR ${(r.wr*100).toFixed(0)}%, PF ${r.pf.toFixed(2)}, 4일 ${r.totalPnl}원`);
  }
});
console.log('');
console.log('--- 조합 B: Trail 확대 (25건 backtest 기준, 22건 환산) ---');
comboB.forEach(r => {
  console.log(`  ${r.scenario}(${r.name.substr(0,25)}): 22환산 ${r.totalPnl22}원`);
});
console.log('');
console.log('--- 조합 C: AD 스캐너 ---');
comboC.forEach(r => {
  console.log(`  ${r.scenario}: 추가PnL ${r.addPnl > 0 ? '+' : ''}${r.addPnl}, 합산 ${r.newTotalPnl}원, PF ${r.newPF.toFixed(2)}`);
});
console.log('');
console.log('--- 조합 E: 결합 ---');
comboE.forEach(r => {
  console.log(`  ${r.label}: PF ${r.pf.toFixed(2)}, 4일 ${Math.round(r.totalPnl)}원`);
});
