// V129 trail_drop 2.0% vs 대안(래더링/V115복귀) 산술 시뮬레이션
// 입력: 23건 SPLIT 케이스 (수익 17 + 손실 6) — peak/trough/진입가 모두 로그에 정확히 기록됨
// 출력: 시나리오별 PnL 차이
const fs = require('fs');

// V129 split_1st armed_threshold = +1.5% (peak이 진입가 +1.5% 이상이면 1차 매도 자격)
// V129 split_1st_drop = 2.0% (armed 후 peak에서 -2.0% 떨어지면 1차 매도)
// V129 trail_drop_after_split = 2.0% (1차 후 또 peak 갱신, peak에서 -2.0% 시 2차 매도)
// 단순화: 각 거래의 (entry, peak, exit_avg) 3점만으로 시뮬

// 23건 SPLIT (수익 17 + 손실 6, peak가 정확히 기록된 건만)
// 데이터: trades_summary.md + parse_trades 결과에서 추출
const SPLIT_CASES = [
  // 손실 6건
  { mkt:'BIRB', date:'04-22', entry:212.00, peak:216.00, peak_pct:1.89, real_avg_pct:-0.71, real_pnl:-2247, qty_value:158400 }, // qty_value=진입금
  { mkt:'SOON', date:'04-23', entry:269.00, peak:272.00, peak_pct:1.12, real_avg_pct:-0.56, real_pnl:-1797, qty_value:160500 },
  { mkt:'PIEVERSE',date:'04-25', entry:1201.00, peak:1204.00, peak_pct:0.25, real_avg_pct:-0.96, real_pnl:-2954, qty_value:153500 },
  { mkt:'SOON', date:'04-25', entry:272.00, peak:276.00, peak_pct:1.47, real_avg_pct:-0.18, real_pnl:-1044, qty_value:153200 },
  { mkt:'RAY',  date:'04-26', entry:1058.00, peak:1065.00, peak_pct:0.66, real_avg_pct:-0.52, real_pnl:-1967, qty_value:160000 },
  { mkt:'API3', date:'04-27', entry:528.00, peak:535.00, peak_pct:1.33, real_avg_pct:-0.19, real_pnl:-1072, qty_value:152300 },
  // 수익 17건
  { mkt:'IN',     date:'04-20', entry:99.20, peak:102.00, peak_pct:2.82, real_avg_pct:0.81, real_pnl:1890, qty_value:152300 },
  { mkt:'SOON',   date:'04-20', entry:281.00, peak:288.00, peak_pct:2.49, real_avg_pct:1.25, real_pnl:2899, qty_value:160100 },
  { mkt:'ZBT',    date:'04-20', entry:178.00, peak:183.00, peak_pct:2.81, real_avg_pct:0.84, real_pnl:1840, qty_value:155400 },
  { mkt:'FLOCK',  date:'04-21', entry:91.46, peak:93.0, peak_pct:1.68, real_avg_pct:0.15, real_pnl:150, qty_value:128000 }, // BEV
  { mkt:'BIO',    date:'04-21', entry:41.70, peak:42.50, peak_pct:1.92, real_avg_pct:1.08, real_pnl:2272, qty_value:152400 },
  { mkt:'SENT',   date:'04-22', entry:27.20, peak:28.10, peak_pct:3.31, real_avg_pct:1.29, real_pnl:3182, qty_value:154500 },
  { mkt:'AXL',    date:'04-23', entry:93.90, peak:97.10, peak_pct:3.41, real_avg_pct:1.17, real_pnl:2642, qty_value:158300 }, // peak fixed
  { mkt:'FLOCK',  date:'04-23', entry:91.50, peak:94.80, peak_pct:3.61, real_avg_pct:1.29, real_pnl:3185, qty_value:152400 },
  { mkt:'CHIP',   date:'04-23', entry:79.20, peak:84.50, peak_pct:6.69, real_avg_pct:2.25, real_pnl:6052, qty_value:152400 },
  { mkt:'ENSO',   date:'04-24', entry:268.00, peak:277.90, peak_pct:3.69, real_avg_pct:0.78, real_pnl:2090, qty_value:153400 },
  { mkt:'KAT',    date:'04-24', entry:206.00, peak:221.00, peak_pct:7.28, real_avg_pct:4.57, real_pnl:11285, qty_value:248500 },
  { mkt:'PENGU',  date:'04-24', entry:6.91, peak:7.07, peak_pct:2.32, real_avg_pct:0.78, real_pnl:1425, qty_value:152600 },
  { mkt:'PIEVERSE',date:'04-25', entry:1191.00, peak:1183.00, peak_pct:-0.67, real_avg_pct:1.08, real_pnl:2045, qty_value:152400 }, // 데이터 일관성 안 맞을 수 있음 — exclude
  { mkt:'SPK',    date:'04-25', entry:158.00, peak:164.10, peak_pct:3.86, real_avg_pct:1.48, real_pnl:3811, qty_value:158300 },
  { mkt:'RVN',    date:'04-26', entry:25.30, peak:25.93, peak_pct:2.49, real_avg_pct:1.14, real_pnl:2263, qty_value:154800 },
  { mkt:'SONIC',  date:'04-26', entry:60.20, peak:63.16, peak_pct:4.92, real_avg_pct:1.75, real_pnl:4768, qty_value:154500 },
  { mkt:'SOMI',   date:'04-27', entry:283.00, peak:293.00, peak_pct:3.53, real_avg_pct:1.95, real_pnl:4820, qty_value:158900 },
  { mkt:'PENGU',  date:'04-27', entry:7.04, peak:7.40, peak_pct:5.11, real_avg_pct:2.92, real_pnl:7170, qty_value:152800 },
];

// 시나리오 정의
// 각 케이스를 다음 룰로 시뮬:
// 1. peak < 1.5%: armed 안 됨. SL_WIDE/TIGHT까지 보유 — peak가 +1.5%까지 못 가면 SL 발동까지 보유 (실제 일어난 trough_pct를 SL로 가정 — 실제 데이터)
//    실제로는 SPLIT_2ND_TRAIL로 청산됐는데, V129이 drop=2.0%로 너무 늦은 것
// 2. peak >= 1.5%: armed. drop % 적용 → 청산.
//    1차 ratio=40%, 2차 ratio=60%
//    각각의 청산 가격을 계산, 합산 PnL 산출
//
// 단순화: 각 거래의 PnL을 split 비중 적용한 가중평균 ROI로 환산.
// PnL_simulated = qty_value * avg_roi_pct / 100

function applyRule(c, rule) {
  // rule = { name, drop1Of, drop2Of, sl1Min }
  // drop1Of(peak_pct): peak%에 따라 1차 drop% 반환 (래더링 가능)
  // drop2Of(peak_pct): 2차 drop% (1차 후 peak는 일반적으로 약간 떨어진 후 다시 올라가므로 동일 peak 가정 단순화)
  const ENTRY_FEE_RATE = 0.0005;
  const ARMED_THR = 1.5; // armed +1.5%
  const peakPct = c.peak_pct;
  const peakPrice = c.peak;
  const entry = c.entry;
  const RATIO_1ST = 0.40;
  const RATIO_2ND = 0.60;

  if (peakPct < ARMED_THR) {
    // armed 안됨. SL_WIDE 가능. 실제 trough를 SL 트리거로 가정 — 단 실제 데이터에서 SPLIT_2ND_TRAIL로 청산된 케이스만 들어옴
    // 이 케이스는 "armed 안 됐는데 SPLIT_2ND_TRAIL 발동" = V115/V129 이전 BEV 룰 → V129에서는 trail drop 적용
    // V129: peak에서 drop% 시 SPLIT_2ND_TRAIL 발동 (전체 100% 매도)
    // 대안: armed 미달 시 다른 청산 방식
    const drop1 = rule.drop1Of(peakPct);
    const exitPrice = peakPrice * (1 - drop1 / 100);
    const exitPctRaw = (exitPrice - entry) / entry * 100;
    const exitPctNet = exitPctRaw - 0.10; // round-trip fee 0.10%
    return { roi: exitPctNet, exit: exitPrice, branch: 'NO_ARM_DROP_FULL' };
  }

  // armed: peak >= 1.5%
  const drop1 = rule.drop1Of(peakPct);
  const drop2 = rule.drop2Of(peakPct);
  // 1차: peak에서 -drop1% 떨어진 가격 (peak는 실제 도달한 가격)
  const exit1Price = peakPrice * (1 - drop1 / 100);
  // 2차: 1차 후 peak가 약간 떨어졌다가 다시 trough → 단순화: 2차 청산 가격도 peak 기준 -drop2%로 가정
  // 더 보수적 가정: 실제 데이터에서 trough를 따라가는 케이스가 있으므로, 2차 청산은 peak에서 -(drop1+drop2)/2 로 잡음
  // 가장 보수적: 2차 청산 = peak에서 -(drop1 + drop2)% (peak에서 drop1만큼 떨어진 후 다시 +x 올랐다가 -drop2 떨어짐 가정)
  // 실용적: 2차도 peak 갱신 가정 없이 peak * (1 - drop2/100)
  const exit2Price = peakPrice * (1 - drop2 / 100);
  const exit1Pct = (exit1Price - entry) / entry * 100;
  const exit2Pct = (exit2Price - entry) / entry * 100;
  const avgPct = (RATIO_1ST * exit1Pct + RATIO_2ND * exit2Pct) - 0.10; // round-trip fee
  return { roi: avgPct, exit1: exit1Price, exit2: exit2Price, branch: 'ARM_SPLIT' };
}

const RULES = {
  V129: {
    name: 'V129 (current)',
    drop1Of: () => 2.0,
    drop2Of: () => 2.0,
  },
  V115_legacy: {
    name: 'V115 (drop1=0.5, drop2=1.2)',
    drop1Of: () => 0.5,
    drop2Of: () => 1.2,
  },
  ladder1: {
    // peak 구간별 래더링 — 보수적
    name: 'Ladder A (peak<2:0.5/<3:1.0/<5:1.5/>=5:2.0)',
    drop1Of: (peak) => peak < 2 ? 0.5 : peak < 3 ? 1.0 : peak < 5 ? 1.5 : 2.0,
    drop2Of: (peak) => peak < 2 ? 1.0 : peak < 3 ? 1.2 : peak < 5 ? 1.5 : 2.0,
  },
  ladder2: {
    // 더 공격적 래더링
    name: 'Ladder B (peak<2:0.4/<3:0.8/<5:1.2/>=5:1.6)',
    drop1Of: (peak) => peak < 2 ? 0.4 : peak < 3 ? 0.8 : peak < 5 ? 1.2 : 1.6,
    drop2Of: (peak) => peak < 2 ? 0.8 : peak < 3 ? 1.0 : peak < 5 ? 1.2 : 1.6,
  },
};

// 시뮬 실행
const results = {};
for (const [key, rule] of Object.entries(RULES)) {
  let total = 0;
  let wins = 0;
  let cases = [];
  for (const c of SPLIT_CASES) {
    if (c.peak_pct < 0) continue; // 데이터 이상치 제외
    const r = applyRule(c, rule);
    const sim_pnl = Math.round(c.qty_value * r.roi / 100);
    total += sim_pnl;
    if (sim_pnl > 0) wins++;
    cases.push({
      mkt: c.mkt, date: c.date, peak: c.peak_pct, real_avg: c.real_avg_pct, real_pnl: c.real_pnl,
      sim_roi: +r.roi.toFixed(2), sim_pnl, branch: r.branch
    });
  }
  results[key] = { name: rule.name, total, wins, n: cases.length, cases };
}

// 출력
console.log('='.repeat(80));
console.log('Trail Drop 시나리오 비교 (23건 SPLIT 거래)');
console.log('='.repeat(80));
for (const [key, r] of Object.entries(results)) {
  console.log(`\n[${key}] ${r.name}`);
  console.log(`  총 PnL: ${r.total.toLocaleString('ko-KR')} KRW (실제 데이터 ${r.cases.reduce((s,c)=>s+c.real_pnl,0).toLocaleString('ko-KR')})`);
  console.log(`  승: ${r.wins}/${r.n}`);
}

// 케이스별 V129 vs Ladder1 비교 표
console.log('\n' + '='.repeat(80));
console.log('Per-case 비교: V129 (실제) vs Ladder A vs Ladder B');
console.log('='.repeat(80));
console.log('mkt    | date  | peak% | real(V129) | LadderA  | LadderB  | Δ_A    | Δ_B   ');
console.log('-'.repeat(80));
const realByKey = Object.fromEntries(SPLIT_CASES.map(c => [c.mkt+'-'+c.date, c]));
const v129 = results.V129.cases;
const lA = results.ladder1.cases;
const lB = results.ladder2.cases;
for (let i=0; i < v129.length; i++) {
  const v = v129[i], a = lA[i], b = lB[i];
  const delta_a = a.sim_pnl - v.real_pnl;
  const delta_b = b.sim_pnl - v.real_pnl;
  console.log(
    `${v.mkt.padEnd(7)}| ${v.date} | ${(''+v.peak).padStart(5)} | ${(''+v.real_pnl).padStart(10)} | ${(''+a.sim_pnl).padStart(8)} | ${(''+b.sim_pnl).padStart(8)} | ${(delta_a>=0?'+':'')+delta_a.toString().padStart(6)} | ${(delta_b>=0?'+':'')+delta_b.toString().padStart(5)}`
  );
}
const totReal = SPLIT_CASES.reduce((s,c)=>s+c.real_pnl,0);
const totA = lA.reduce((s,c)=>s+c.sim_pnl,0);
const totB = lB.reduce((s,c)=>s+c.sim_pnl,0);
console.log('-'.repeat(80));
console.log(`Real total = ${totReal} | LadderA = ${totA} (Δ ${totA-totReal>=0?'+':''}${totA-totReal}) | LadderB = ${totB} (Δ ${totB-totReal>=0?'+':''}${totB-totReal})`);

fs.writeFileSync('.analysis/raw/sim_trail_results.json', JSON.stringify(results, null, 2));
console.log('\nSaved: .analysis/raw/sim_trail_results.json');
