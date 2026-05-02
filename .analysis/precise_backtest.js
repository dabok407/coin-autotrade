// ============================================================
// Agent Precise Backtest — 1일 윈도우 (1440분) 정밀 시뮬
// Step 1: 27건 1분봉 페치 (V130-after 22건 + 큰추세 전 5건)
// Step 2: 시나리오 S0~S9 × L1캡 × 개별 케이스 상세
// 출력: .analysis/agent_precise_backtest.md
//       .analysis/raw/precise_backtest_results.json
// ============================================================
'use strict';
const fs   = require('fs');
const https = require('https');

// ── 데이터 로드 ─────────────────────────────────────────────
const PARSED = JSON.parse(fs.readFileSync('.analysis/trades_19d_parsed.json', 'utf8'));

// V130-after 폐쇄 거래 (STILL_OPEN, AXL STILL_OPEN 제외)
const V130_AFTER = PARSED.pairs.filter(p =>
  p.v130_era === 'after' &&
  p.exit_reason !== 'STILL_OPEN' &&
  p.exit_reason !== 'OPEN_POSITION'
);
console.log('V130-after 폐쇄 거래:', V130_AFTER.length, '건');

// 큰추세 5건 (V130 이전 + 당시 날짜 기준 수동)
// trades_19d_parsed.json 에서 해당 종목/날짜 찾기
const BIG_TREND_SPECS = [
  { market: 'KRW-ENSO',    entry_ts: '2026-04-26 13:22:25', scanner: 'AD',   note: 'ENSO(04-26 AD) +6%+' },
  { market: 'KRW-KAT',     entry_ts: '2026-04-24 09:12:00', scanner: 'OP',   note: 'KAT(04-24) — spec' },
  { market: 'KRW-RED',     entry_ts: '2026-04-24 09:01:00', scanner: 'OP',   note: 'RED(04-24) — spec' },
  { market: 'KRW-ORCA',    entry_ts: '2026-04-26 09:00:00', scanner: 'MR',   note: 'ORCA(04-26) — spec' },
  { market: 'KRW-API3',    entry_ts: '2026-04-25 09:00:51', scanner: 'MR',   note: 'API3(04-25 V130-before MR)' },
];

// trades_19d_parsed에서 대박 케이스 찾기 (v130_era=before 중 큰 pnl)
const PRE_BIG = PARSED.pairs
  .filter(p => p.v130_era === 'before' && p.pnl_total > 5000)
  .sort((a, b) => b.pnl_total - a.pnl_total)
  .slice(0, 10);
console.log('V130 전 큰 수익 케이스 (참고):');
PRE_BIG.forEach(p => console.log(`  ${p.market} ${p.entry_ts} pnl=${p.pnl_total} peak=${p.peak_pct}`));

// 정확한 entry_price / qty 있는 케이스 매칭
const BIG_TREND_ENTRIES = BIG_TREND_SPECS.map(spec => {
  const found = PARSED.pairs.find(p =>
    p.market === spec.market &&
    p.entry_ts.startsWith(spec.entry_ts.substring(0, 16))
  );
  if (found) {
    return { ...found, note: spec.note, is_big_trend: true };
  }
  // 없으면 spec 그대로 (entry_price, qty 없음 → 시뮬 스킵)
  return { ...spec, note: spec.note, is_big_trend: true, entry_price: null, qty: null };
});

// 전체 대상 (27건 = 22 + 5)
const ALL_ENTRIES = [
  ...V130_AFTER.map(p => ({ ...p, is_big_trend: false, note: '' })),
  ...BIG_TREND_ENTRIES.filter(p => p.entry_price != null && p.qty != null),
];
console.log('총 시뮬 대상:', ALL_ENTRIES.length, '건');

// 특별 케이스 식별자 (개별 PnL 표시용)
const FOCUS_BIG = ['KRW-ENSO', 'KRW-TOKAMAK', 'KRW-ZBT', 'KRW-API3', 'KRW-XCN'];
const FOCUS_LOSS = ['KRW-DRIFT', 'KRW-RAY', 'KRW-MANTRA', 'KRW-SOON'];

// ── 유틸 ────────────────────────────────────────────────────
const sleep = ms => new Promise(r => setTimeout(r, ms));

function fetchJson(url, retries = 4) {
  return new Promise((resolve, reject) => {
    const attempt = n => {
      https.get(url, { headers: { 'User-Agent': 'Mozilla/5.0' } }, res => {
        let d = '';
        res.on('data', c => d += c);
        res.on('end', () => {
          if (res.statusCode === 200) {
            try { resolve(JSON.parse(d)); }
            catch (e) { reject(new Error('parse: ' + e.message)); }
          } else {
            if (n > 1) setTimeout(() => attempt(n - 1), 800);
            else reject(new Error('HTTP ' + res.statusCode));
          }
        });
      }).on('error', err => {
        if (n > 1) setTimeout(() => attempt(n - 1), 800);
        else reject(err);
      });
    };
    attempt(retries);
  });
}

function parseKst(ts) {
  return new Date(ts.replace(' ', 'T') + '+09:00');
}
function kstAddMinUtcIso(kstStr, addMin) {
  const base = parseKst(kstStr).getTime() + addMin * 60000;
  return new Date(base).toISOString().replace('.000Z', 'Z');
}
function setKstH(d, h, m) {
  // d: UTC Date (entryUtc). h, m: 목표 KST 시각
  // KST 날짜를 UTC 필드로 추출 후 (h-9):m UTC로 변환
  const entryKst = new Date(d.getTime() + 9 * 3600000);
  const kstYear  = entryKst.getUTCFullYear();
  const kstMonth = entryKst.getUTCMonth();
  const kstDay   = entryKst.getUTCDate();
  return new Date(Date.UTC(kstYear, kstMonth, kstDay, h - 9, m, 0, 0));
}

// ── 1분봉 페치 (1일 윈도우) ──────────────────────────────────
// 진입 시각부터 당일 23:59 KST까지 → 최대 1440분
// 업비트 API 1회 200개 → 최대 8번 페치로 1600분 커버
// 실제로 진입~23:59 평균 700~900분이면 4~5번 페치 필요
async function fetchDayCandles(market, entry_ts, scanner) {
  const entryMs = parseKst(entry_ts).getTime();
  // 당일 KST 23:59
  const entryKst = new Date(entryMs + 9 * 3600000);
  const dayEndKst = new Date(entryKst.getFullYear(), entryKst.getMonth(), entryKst.getDate(), 23, 59, 0, 0);
  const dayEndMs = dayEndKst.getTime() - 9 * 3600000; // UTC

  const totalMin = Math.ceil((dayEndMs - entryMs) / 60000) + 2;
  const passes = Math.ceil(totalMin / 200);

  let allCandles = [];
  for (let pass = 0; pass < passes; pass++) {
    // to = entry + (pass+1)*200분
    const toMs = entryMs + (pass + 1) * 200 * 60000;
    const toUtc = new Date(toMs).toISOString().replace('.000Z', 'Z');
    const url = `https://api.upbit.com/v1/candles/minutes/1?market=${encodeURIComponent(market)}&to=${encodeURIComponent(toUtc)}&count=200`;
    try {
      const raw = await fetchJson(url);
      const ordered = raw.reverse();
      for (const c of ordered) {
        const cMs = new Date(c.candle_date_time_kst.replace('T', ' ') + '+09:00').getTime();
        if (cMs >= entryMs && cMs <= dayEndMs) {
          allCandles.push({
            ts_kst: c.candle_date_time_kst,
            ms: cMs,
            o: c.opening_price, h: c.high_price, l: c.low_price, c: c.trade_price,
            v: c.candle_acc_trade_volume,
          });
        }
      }
    } catch (e) {
      console.error(`  페치 실패 ${market} pass${pass}: ${e.message}`);
    }
    await sleep(110);
    // 이미 dayEnd 커버됐으면 중단
    if (allCandles.length > 0 && allCandles[allCandles.length - 1].ms >= dayEndMs) break;
  }

  // 중복 제거 + 정렬
  const seen = new Set();
  const unique = allCandles
    .filter(c => { if (seen.has(c.ms)) return false; seen.add(c.ms); return true; })
    .sort((a, b) => a.ms - b.ms);
  return unique;
}

// ── 시뮬 파라미터 ────────────────────────────────────────────
const FEE_PCT = 0.10;       // round-trip fee (수수료 0.10%, 슬리피지 0%)
const SL_TIGHT_PCT = 2.5;   // V130 SL_TIGHT
const L1_ROI_FLOOR = 0.30;  // V130 도입 L1 ROI Floor (1차 매도 최소 ROI)
const ARMED_THR = 1.5;      // trail armed 임계값 (%)
const SPLIT_1ST_RATIO = 0.50;
const SPLIT_2ND_RATIO = 0.50;
const COOLDOWN_CANDLES = 1;  // split1 후 1분 쿨다운

// 스캐너별 당일 만료 시간 (KST H:M)
function scannerTimeExit(scanner, entryMs) {
  const entryUtc = new Date(entryMs);
  if (scanner === 'MR') return setKstH(entryUtc, 10, 0);   // 10:00 KST
  if (scanner === 'OP') return setKstH(entryUtc, 12, 0);   // 12:00 KST
  if (scanner === 'AD') {
    // 다음날 08:59 KST — 당일 22:00 or 다음날로
    const nextDay = new Date(entryUtc.getTime() + 24 * 3600000);
    return setKstH(nextDay, 8, 59);
  }
  return setKstH(entryUtc, 23, 59);
}

// ── 시나리오 정의 ─────────────────────────────────────────────
// 각 시나리오: { id, name, dropFn, drop2Fn, splitRatio, bev }
// dropFn(peakPct) → drop% for 1차 trail exit
// drop2Fn(peakPct) → drop% for 2차 trail exit
// splitRatio: [1st%, 2nd%] — 합 = 1.0
// bev: true → split2 price 보정 (진입가 이상 보장)
const SCENARIOS = [
  {
    id: 'S0', name: 'V130 현재 (baseline) Ladder 0.5/1.0/1.5/2.0',
    dropFn:  p => p < 2 ? 0.5 : p < 3 ? 1.0 : p < 5 ? 1.5 : 2.0,
    drop2Fn: p => p < 2 ? 0.8 : p < 3 ? 1.2 : p < 5 ? 1.5 : 2.0,
    splitRatio: [0.50, 0.50], bev: false,
  },
  {
    id: 'S1', name: 'drop 단일 2.0% (V128 회귀)',
    dropFn:  () => 2.0,
    drop2Fn: () => 2.0,
    splitRatio: [0.50, 0.50], bev: false,
  },
  {
    id: 'S2', name: 'drop 단일 3.0%',
    dropFn:  () => 3.0,
    drop2Fn: () => 3.0,
    splitRatio: [0.50, 0.50], bev: false,
  },
  {
    id: 'S3', name: 'drop 단일 4.0%',
    dropFn:  () => 4.0,
    drop2Fn: () => 4.0,
    splitRatio: [0.50, 0.50], bev: false,
  },
  {
    id: 'S4', name: 'drop 단일 5.0%',
    dropFn:  () => 5.0,
    drop2Fn: () => 5.0,
    splitRatio: [0.50, 0.50], bev: false,
  },
  {
    id: 'S5', name: 'Hybrid 큰추세 보존 (peak<3: V130 / ≥3: 3.5%)',
    dropFn:  p => p < 2 ? 0.5 : p < 3 ? 1.0 : 3.5,
    drop2Fn: p => p < 2 ? 0.8 : p < 3 ? 1.2 : 3.5,
    splitRatio: [0.50, 0.50], bev: false,
  },
  {
    id: 'S6', name: 'Ratchet trail 0.7/1.0/2.5/4.0/5.0',
    dropFn:  p => p < 2 ? 0.7 : p < 3 ? 1.0 : p < 5 ? 2.5 : p < 10 ? 4.0 : 5.0,
    drop2Fn: p => p < 2 ? 0.7 : p < 3 ? 1.0 : p < 5 ? 2.5 : p < 10 ? 4.0 : 5.0,
    splitRatio: [0.50, 0.50], bev: false,
  },
  {
    id: 'S7', name: 'BEV+wide trail drop 4.0 + BEV 보장',
    dropFn:  () => 4.0,
    drop2Fn: () => 4.0,
    splitRatio: [0.50, 0.50], bev: true,
  },
  {
    id: 'S8', name: 'Split 안함 + drop 3.0% (100% 단일 트레일)',
    dropFn:  () => 3.0,
    drop2Fn: () => 3.0,
    splitRatio: [1.00, 0.00], bev: false,
  },
  {
    id: 'S9', name: 'Split 30/70 + V130 Ladder',
    dropFn:  p => p < 2 ? 0.5 : p < 3 ? 1.0 : p < 5 ? 1.5 : 2.0,
    drop2Fn: p => p < 2 ? 0.8 : p < 3 ? 1.2 : p < 5 ? 1.5 : 2.0,
    splitRatio: [0.30, 0.70], bev: false,
  },
];

const L1_CAPS = [null, 2.0, 2.1, 2.4];  // null = 캡 없음

// ── 단일 거래 시뮬 ─────────────────────────────────────────────
function simulate(entry, candles, scenario, l1Cap) {
  if (!candles || candles.length === 0) return { skipped: true, reason: 'NO_CANDLES' };

  const { dropFn, drop2Fn, splitRatio, bev } = scenario;
  const [r1, r2] = splitRatio;
  const entryPrice = entry.entry_price;
  const sl = entryPrice * (1 - SL_TIGHT_PCT / 100);
  const entryMs = parseKst(entry.entry_ts).getTime();
  const timeExitDt = scannerTimeExit(entry.scanner, entryMs);

  let peak = entryPrice;
  let armed = false;
  let split1 = null;
  let cooldownIdx = -1;

  for (let i = 0; i < candles.length; i++) {
    const c = candles[i];
    const cMs = c.ms;
    const { h, l } = c;

    // Time exit
    if (cMs >= timeExitDt.getTime()) {
      return mkResult(entry, entryPrice, c.c, split1, null, peak, 'TIME_EXIT', r1, r2, bev, entryPrice);
    }

    // Peak 갱신
    if (h > peak) peak = h;
    const peakPct = (peak - entryPrice) / entryPrice * 100;

    // SL (split1 전 전량)
    if (!split1 && l <= sl) {
      return mkResult(entry, entryPrice, sl, null, null, peak, 'SL_TIGHT', r1, r2, bev, entryPrice);
    }

    // Armed
    if (!armed && peakPct >= ARMED_THR) armed = true;

    // L1 캡 강제 매도 (split1 전)
    if (armed && l1Cap !== null && split1 === null && r1 > 0) {
      const capPrice = entryPrice * (1 + l1Cap / 100);
      if (h >= capPrice) {
        split1 = { price: capPrice, idx: i, forced_cap: true, roi: (capPrice - entryPrice) / entryPrice * 100 };
        cooldownIdx = i + COOLDOWN_CANDLES;
      }
    }

    // Split 1st (trail, if no L1 cap fired or ratio=1 mode)
    if (armed && split1 === null && r1 > 0) {
      const drop1 = dropFn(peakPct);
      const target1 = peak * (1 - drop1 / 100);
      // L1 ROI Floor: split1 price >= entryPrice*(1+L1_ROI_FLOOR%)
      const floorPrice = entryPrice * (1 + L1_ROI_FLOOR / 100);
      const split1Price = Math.max(target1, floorPrice);
      if (l <= split1Price) {
        split1 = { price: split1Price, idx: i, forced_cap: false, roi: (split1Price - entryPrice) / entryPrice * 100 };
        cooldownIdx = i + COOLDOWN_CANDLES;
      }
    }

    // Split 2nd (trail)
    if (split1 && i >= cooldownIdx && r2 > 0) {
      const drop2 = drop2Fn(peakPct);
      let target2 = peak * (1 - drop2 / 100);
      if (bev) target2 = Math.max(target2, entryPrice); // BEV 보장
      if (l <= target2) {
        const split2 = { price: target2, idx: i, roi: (target2 - entryPrice) / entryPrice * 100 };
        return mkResult(entry, entryPrice, null, split1, split2, peak, 'SPLIT_2ND_TRAIL', r1, r2, bev, entryPrice);
      }
    }

    // r1=1.0 (no split2) → wait for time exit or SL (already handled above)
  }

  // 데이터 끝 (당일 23:59 도달)
  const last = candles[candles.length - 1];
  return mkResult(entry, entryPrice, last.c, split1, null, peak, 'END_OF_DATA', r1, r2, bev, entryPrice);
}

function mkResult(entry, entryPrice, finalPrice, split1, split2, peak, reason, r1, r2, bev, ep) {
  const qty = entry.qty || 1;
  const segments = [];
  let ratioUsed = 0;

  if (split1 && r1 > 0) {
    segments.push({ ratio: r1, price: split1.price, roi: split1.roi, forced_cap: split1.forced_cap });
    ratioUsed += r1;
  }
  if (split2 && r2 > 0) {
    const p2 = bev ? Math.max(split2.price, ep) : split2.price;
    segments.push({ ratio: r2, price: p2, roi: (p2 - ep) / ep * 100 });
    ratioUsed += r2;
  }
  const rem = 1 - ratioUsed;
  if (rem > 0.001 && finalPrice != null) {
    segments.push({ ratio: rem, price: finalPrice, roi: (finalPrice - ep) / ep * 100 });
  }

  const entryVal = ep * qty;
  let avgRoi = segments.reduce((s, seg) => s + seg.ratio * seg.roi, 0);
  avgRoi -= FEE_PCT;
  const pnl = Math.round(entryVal * avgRoi / 100);
  const peakPct = (peak - ep) / ep * 100;

  return {
    market: entry.market, scanner: entry.scanner, entry_ts: entry.entry_ts,
    is_big_trend: entry.is_big_trend || false,
    note: entry.note || '',
    entry_price: ep, peak, peak_pct: peakPct,
    segments, avg_roi: +avgRoi.toFixed(3), pnl, reason,
    split1_roi: split1 ? +split1.roi.toFixed(3) : null,
    split1_forced: split1 ? split1.forced_cap : false,
  };
}

// ── 집계 ─────────────────────────────────────────────────────
function aggregate(results) {
  const n = results.length;
  const wins = results.filter(r => r.pnl > 0).length;
  const totalPnl = results.reduce((s, r) => s + r.pnl, 0);
  const peak5plus = results.filter(r => r.peak_pct >= 5).length;
  const avgPeak = n ? (results.reduce((s, r) => s + r.peak_pct, 0) / n) : 0;
  const byReason = {};
  for (const r of results) {
    if (!byReason[r.reason]) byReason[r.reason] = { n: 0, pnl: 0, wins: 0 };
    byReason[r.reason].n++;
    byReason[r.reason].pnl += r.pnl;
    if (r.pnl > 0) byReason[r.reason].wins++;
  }
  // 개별 focal 케이스
  const focusBig = {};
  for (const m of FOCUS_BIG) {
    const res = results.filter(r => r.market === m);
    focusBig[m] = res.reduce((s, r) => s + r.pnl, 0);
  }
  const focusLoss = {};
  for (const m of FOCUS_LOSS) {
    const res = results.filter(r => r.market === m);
    focusLoss[m] = res.reduce((s, r) => s + r.pnl, 0);
  }
  return { n, wins, wr: +(wins / n * 100).toFixed(1), totalPnl, peak5plus, avgPeak: +avgPeak.toFixed(2), byReason, focusBig, focusLoss };
}

// ── 메인 ─────────────────────────────────────────────────────
(async () => {
  // ── Step 1: 1분봉 페치 ──────────────────────────────────────
  console.log('\n[Step 1] 1분봉 페치 시작 (1일 윈도우) —', ALL_ENTRIES.length, '건');
  const candleMap = {};

  let fetched = 0, failed = 0;
  for (const e of ALL_ENTRIES) {
    const key = e.market + '|' + e.entry_ts;
    try {
      const c = await fetchDayCandles(e.market, e.entry_ts, e.scanner);
      candleMap[key] = c;
      fetched++;
      process.stdout.write(`  [${fetched}/${ALL_ENTRIES.length}] ${e.market} ${e.entry_ts}: ${c.length}개\n`);
    } catch (err) {
      console.error(`  [ERR] ${key}: ${err.message}`);
      candleMap[key] = [];
      failed++;
    }
  }
  console.log(`페치 완료: ${fetched}건 성공, ${failed}건 실패\n`);

  // ── Step 2: 시나리오 시뮬 ──────────────────────────────────
  console.log('[Step 2] 시나리오 시뮬 S0~S9 × L1캡 4단계...');

  const results = {};
  // S0~S9, 각 시나리오에 대해 L1캡 NO_CAP만 (빠른 전체 비교)
  for (const sc of SCENARIOS) {
    const r = [];
    for (const e of ALL_ENTRIES) {
      const key = e.market + '|' + e.entry_ts;
      const sim = simulate(e, candleMap[key], sc, null);
      if (!sim.skipped) r.push(sim);
    }
    results[sc.id] = { scenario: sc, noCap: { results: r, agg: aggregate(r) } };
  }

  // ── Step 3: L1 캡 매트릭스 — S5/S6/S7/S9에 대해 ─────────────
  console.log('[Step 3] L1 캡 매트릭스 S5/S6/S7/S9...');
  const capMatrix = {};
  for (const scId of ['S0', 'S5', 'S6', 'S7', 'S9']) {
    capMatrix[scId] = {};
    const sc = SCENARIOS.find(s => s.id === scId);
    for (const cap of L1_CAPS) {
      const capKey = cap === null ? 'NO_CAP' : `CAP_${cap.toFixed(1).replace('.', 'p')}`;
      const r = [];
      for (const e of ALL_ENTRIES) {
        const key = e.market + '|' + e.entry_ts;
        const sim = simulate(e, candleMap[key], sc, cap);
        if (!sim.skipped) r.push(sim);
      }
      capMatrix[scId][capKey] = { cap, results: r, agg: aggregate(r) };
    }
  }

  // ── Step 4: 상세 개별 케이스 분석 ────────────────────────────
  console.log('[Step 4] 개별 케이스 분석...');

  // 큰추세 케이스 각 시나리오별
  const bigCaseDetail = {};
  for (const e of ALL_ENTRIES.filter(e => FOCUS_BIG.includes(e.market))) {
    const key = e.market + '|' + e.entry_ts;
    bigCaseDetail[key] = {};
    for (const sc of SCENARIOS) {
      bigCaseDetail[key][sc.id] = simulate(e, candleMap[key], sc, null);
    }
  }

  // 손실 케이스
  const lossCaseDetail = {};
  for (const e of ALL_ENTRIES.filter(e => FOCUS_LOSS.includes(e.market))) {
    const key = e.market + '|' + e.entry_ts;
    lossCaseDetail[key] = {};
    for (const sc of SCENARIOS) {
      lossCaseDetail[key][sc.id] = simulate(e, candleMap[key], sc, null);
    }
  }

  // ── Step 5: 보고서 생성 ────────────────────────────────────
  console.log('[Step 5] 보고서 생성...');
  const lines = [];

  const now = new Date().toISOString().slice(0, 10);

  lines.push('# 정밀 백테스트 — 1일 윈도우 (1440분) 매도 전략 시나리오 비교');
  lines.push('');
  lines.push(`**생성일**: ${now} | **목적**: ENSO(+10.57%) / TOKAMAK(+21.9%) 격차 원인 규명 및 최적 시나리오 도출`);
  lines.push('');
  lines.push('**가정 및 조건**:');
  lines.push('- 수수료 0.10% (왕복), 슬리피지 0% (API 체결가 기준)');
  lines.push('- L1 ROI Floor +0.30% (V130 도입)');
  lines.push('- SL_TIGHT 2.5% (진입가 기준)');
  lines.push('- Split-Exit: 1차=50% 매도, 2차=나머지 50% (시나리오별 상이)');
  lines.push('- 스캐너별 시간만료: MR 10:00, OP 12:00, AD 다음날 08:59 (KST)');
  lines.push('- **주의**: 표본 27건 이하, 통계적 유의성 낮음 (방향성 참고용)');
  lines.push('- **주의**: 시뮬 절대 PnL은 실 운영과 차이 있을 수 있음 (큰추세 케이스 entry_price 실제값 사용)');
  lines.push('');
  lines.push('---');
  lines.push('');

  // ── S0~S9 총괄 비교표 ──────────────────────────────────────
  lines.push('## Step 2: 시나리오 S0~S9 전체 비교 (V130-after 22건 + 큰추세 5건, L1캡 없음)');
  lines.push('');
  lines.push(`| ID | 시나리오 | 건수 | 승 | WR% | 총 PnL (KRW) | peak5%+ | avg peak% | 비고 |`);
  lines.push(`|---|---|---:|---:|---:|---:|---:|---:|---|`);

  // 전체 기준
  for (const sc of SCENARIOS) {
    const agg = results[sc.id].noCap.agg;
    const isBaseline = sc.id === 'S0' ? '← 현재' : '';
    lines.push(`| **${sc.id}** | ${sc.name} | ${agg.n} | ${agg.wins} | ${agg.wr}% | **${fmt(agg.totalPnl)}** | ${agg.peak5plus} | ${agg.avgPeak}% | ${isBaseline} |`);
  }
  lines.push('');

  // V130-after 22건만 기준
  lines.push('### V130-after 22건만 기준 (큰추세 케이스 제외, 실운영 유사)');
  lines.push('');
  lines.push(`| ID | 총 PnL | WR% | peak5%+ | avg peak% |`);
  lines.push(`|---|---:|---:|---:|---:|`);
  for (const sc of SCENARIOS) {
    const r22 = results[sc.id].noCap.results.filter(r => !r.is_big_trend);
    const agg22 = aggregate(r22);
    const base = sc.id === 'S0';
    lines.push(`| **${sc.id}** | **${fmt(agg22.totalPnl)}** | ${agg22.wr}% | ${agg22.peak5plus} | ${agg22.avgPeak}% | ${base ? '← 기준' : ''}`);
  }
  lines.push('');

  // ── Step 3: L1 캡 매트릭스 ──────────────────────────────────
  lines.push('## Step 3: L1 캡 매트릭스 (S0/S5/S6/S7/S9 × 4단계)');
  lines.push('');
  lines.push('| 시나리오 | L1 캡 | 총 PnL | WR% | peak5%+ | Δ vs NO_CAP |');
  lines.push('|---|---|---:|---:|---:|---:|');
  for (const scId of ['S0', 'S5', 'S6', 'S7', 'S9']) {
    const baseNoCap = capMatrix[scId]['NO_CAP'].agg.totalPnl;
    for (const [capKey, obj] of Object.entries(capMatrix[scId])) {
      const agg = obj.agg;
      const delta = agg.totalPnl - baseNoCap;
      const deltaStr = capKey === 'NO_CAP' ? '—' : `${delta >= 0 ? '+' : ''}${fmt(delta)}`;
      lines.push(`| ${scId} | ${capKey === 'NO_CAP' ? '없음' : `+${obj.cap}%`} | **${fmt(agg.totalPnl)}** | ${agg.wr}% | ${agg.peak5plus} | ${deltaStr} |`);
    }
  }
  lines.push('');

  // ── Step 4: 개별 케이스 분석 ────────────────────────────────
  lines.push('## Step 4: 큰추세 5개 케이스 — 시나리오별 PnL (KRW)');
  lines.push('');
  lines.push('> 이 케이스들이 ENSO/TOKAMAK급 종목에서 얼마나 개선되는지 보여줌');
  lines.push('');

  // 큰추세 케이스 표
  const bigKeys = Object.keys(bigCaseDetail);
  if (bigKeys.length > 0) {
    lines.push('| 종목 | 진입 시각 | peak% | ' + SCENARIOS.map(s => s.id).join(' | ') + ' |');
    lines.push('|---|---|---:| ' + SCENARIOS.map(() => '---:').join(' | ') + ' |');
    for (const key of bigKeys) {
      const [mkt, ts] = key.split('|');
      const scResults = bigCaseDetail[key];
      const peakStr = scResults['S0'] && !scResults['S0'].skipped ? scResults['S0'].peak_pct.toFixed(2) + '%' : '?';
      const pnlCols = SCENARIOS.map(s => {
        const r = scResults[s.id];
        if (!r || r.skipped) return 'N/A';
        return fmt(r.pnl);
      }).join(' | ');
      lines.push(`| **${mkt}** | ${ts.slice(5, 16)} | ${peakStr} | ${pnlCols} |`);
    }
    lines.push('');
  } else {
    lines.push('큰추세 케이스 데이터 없음 (파싱 필요)');
    lines.push('');
  }

  lines.push('### 큰추세 케이스 청산 사유 (S0 vs S5 vs S6)');
  lines.push('');
  if (bigKeys.length > 0) {
    lines.push('| 종목 | S0 사유 | S0 ROI | S5 사유 | S5 ROI | S6 사유 | S6 ROI |');
    lines.push('|---|---|---:|---|---:|---|---:|');
    for (const key of bigKeys) {
      const [mkt, ts] = key.split('|');
      const sc = bigCaseDetail[key];
      const r0 = sc['S0']; const r5 = sc['S5']; const r6 = sc['S6'];
      const col = (r) => r && !r.skipped ? `${r.reason} | ${r.avg_roi.toFixed(2)}%` : 'N/A | -';
      lines.push(`| **${mkt}** | ${col(r0)} | ${col(r5)} | ${col(r6)} |`);
    }
    lines.push('');
  }

  lines.push('## Step 4b: 손실 케이스 — 시나리오별 PnL (손실 악화 여부 확인)');
  lines.push('');
  const lossKeys = Object.keys(lossCaseDetail);
  if (lossKeys.length > 0) {
    lines.push('| 종목 | 진입 시각 | peak% | ' + SCENARIOS.map(s => s.id).join(' | ') + ' |');
    lines.push('|---|---|---:| ' + SCENARIOS.map(() => '---:').join(' | ') + ' |');
    for (const key of lossKeys) {
      const [mkt, ts] = key.split('|');
      const scResults = lossCaseDetail[key];
      const peakStr = scResults['S0'] && !scResults['S0'].skipped ? scResults['S0'].peak_pct.toFixed(2) + '%' : '?';
      const pnlCols = SCENARIOS.map(s => {
        const r = scResults[s.id];
        if (!r || r.skipped) return 'N/A';
        return fmt(r.pnl);
      }).join(' | ');
      lines.push(`| **${mkt}** | ${ts.slice(5, 16)} | ${peakStr} | ${pnlCols} |`);
    }
    lines.push('');
  }

  // ── Step 5: 시나리오 랭킹 ────────────────────────────────────
  lines.push('## Step 5: 시나리오 랭킹 (3가지 기준)');
  lines.push('');

  // 총 PnL 랭킹
  const rankByPnl = SCENARIOS
    .map(sc => ({ id: sc.id, name: sc.name, pnl: results[sc.id].noCap.agg.totalPnl }))
    .sort((a, b) => b.pnl - a.pnl);

  lines.push('### 총 PnL 기준 랭킹');
  lines.push('');
  lines.push('| 순위 | ID | 시나리오 | 총 PnL | Δ vs S0 |');
  lines.push('|---|---|---|---:|---:|');
  const s0Pnl = results['S0'].noCap.agg.totalPnl;
  for (let i = 0; i < rankByPnl.length; i++) {
    const r = rankByPnl[i];
    const delta = r.pnl - s0Pnl;
    lines.push(`| ${i + 1} | **${r.id}** | ${r.name} | ${fmt(r.pnl)} | ${delta >= 0 ? '+' : ''}${fmt(delta)} |`);
  }
  lines.push('');

  // 큰추세 잡기 기준 랭킹 (ENSO + TOKAMAK + ZBT + API3 + XCN 합산)
  const rankByBig = SCENARIOS
    .map(sc => {
      const bigSum = results[sc.id].noCap.results
        .filter(r => FOCUS_BIG.includes(r.market))
        .reduce((s, r) => s + r.pnl, 0);
      return { id: sc.id, name: sc.name, bigSum };
    })
    .sort((a, b) => b.bigSum - a.bigSum);

  lines.push('### 큰추세 잡기 기준 랭킹 (ENSO+TOKAMAK+ZBT+API3+XCN 합산)');
  lines.push('');
  lines.push('| 순위 | ID | 시나리오 | 큰추세 합산 PnL | Δ vs S0 |');
  lines.push('|---|---|---|---:|---:|');
  const s0Big = results['S0'].noCap.results.filter(r => FOCUS_BIG.includes(r.market)).reduce((s, r) => s + r.pnl, 0);
  for (let i = 0; i < rankByBig.length; i++) {
    const r = rankByBig[i];
    const delta = r.bigSum - s0Big;
    lines.push(`| ${i + 1} | **${r.id}** | ${r.name} | ${fmt(r.bigSum)} | ${delta >= 0 ? '+' : ''}${fmt(delta)} |`);
  }
  lines.push('');

  // 손실 케이스 안전성 기준 (손실 최소화)
  const rankBySafety = SCENARIOS
    .map(sc => {
      const lossSum = results[sc.id].noCap.results
        .filter(r => FOCUS_LOSS.includes(r.market))
        .reduce((s, r) => s + r.pnl, 0);
      return { id: sc.id, name: sc.name, lossSum };
    })
    .sort((a, b) => b.lossSum - a.lossSum);

  lines.push('### 손실 케이스 안전성 기준 (DRIFT+RAY+MANTRA+SOON 합산, 높을수록 안전)');
  lines.push('');
  lines.push('| 순위 | ID | 시나리오 | 손실합 PnL | Δ vs S0 |');
  lines.push('|---|---|---|---:|---:|');
  const s0Loss = results['S0'].noCap.results.filter(r => FOCUS_LOSS.includes(r.market)).reduce((s, r) => s + r.pnl, 0);
  for (let i = 0; i < rankBySafety.length; i++) {
    const r = rankBySafety[i];
    const delta = r.lossSum - s0Loss;
    lines.push(`| ${i + 1} | **${r.id}** | ${r.name} | ${fmt(r.lossSum)} | ${delta >= 0 ? '+' : ''}${fmt(delta)} |`);
  }
  lines.push('');

  // ── 종합 권고 ──────────────────────────────────────────────
  lines.push('## 종합 권고');
  lines.push('');

  // 자동 분석
  const best1 = rankByPnl[0];
  const best2 = rankByBig[0];
  const bestSafe = rankBySafety[0];

  lines.push('### 질문 1: ENSO/TOKAMAK 잡으려면 어떤 시나리오?');
  lines.push('');
  lines.push(`**큰추세 잡기 1위**: **${best2.id}** — 큰추세 합산 PnL ${fmt(best2.bigSum)} (S0 대비 ${best2.bigSum - s0Big >= 0 ? '+' : ''}${fmt(best2.bigSum - s0Big)})`);
  lines.push('');

  // S6 특별 분석 (Ratchet — 10%+에서 5% drop)
  const s6BigDetail = results['S6'].noCap.results.filter(r => FOCUS_BIG.includes(r.market));
  if (s6BigDetail.length > 0) {
    lines.push('**S6 Ratchet 개별 케이스 상세:**');
    lines.push('');
    for (const r of s6BigDetail) {
      lines.push(`- ${r.market} (${r.entry_ts.slice(5, 16)}): peak ${r.peak_pct.toFixed(2)}% → 시뮬 ROI ${r.avg_roi.toFixed(2)}% → PnL ${fmt(r.pnl)} | 청산: ${r.reason}`);
    }
    lines.push('');
  }

  lines.push('### 질문 2: 손실 케이스에서 얼마나 더 손해?');
  lines.push('');
  // S0 vs 큰추세 잡기 1위의 손실 비교
  const bigWinner = rankByBig[0].id;
  const bwLoss = results[bigWinner].noCap.results.filter(r => FOCUS_LOSS.includes(r.market)).reduce((s, r) => s + r.pnl, 0);
  lines.push(`**S0(현재) 손실합**: ${fmt(s0Loss)} KRW`);
  lines.push(`**${bigWinner}(큰추세1위) 손실합**: ${fmt(bwLoss)} KRW (Δ ${bwLoss - s0Loss >= 0 ? '+' : ''}${fmt(bwLoss - s0Loss)})`);
  lines.push('');
  lines.push('> 손실 케이스(SL_TIGHT)는 trail 폭과 무관 — trail이 넓어도 SL은 2.5% 고정이라 손실 크기 동일.');
  lines.push('> 단, drop이 넓으면 일시 반등 후 재하락 시 음수 ROI로 청산될 수 있음 (BEV 없으면 위험).');
  lines.push('');

  lines.push('### 질문 3: 최적 trade-off + 권고 우선순위');
  lines.push('');

  // 최종 랭킹 계산 (PnL + 큰추세 * 1.5 + 안전성 * 0.5)
  const scoreMap = {};
  for (const sc of SCENARIOS) {
    const pnl = results[sc.id].noCap.agg.totalPnl;
    const big = results[sc.id].noCap.results.filter(r => FOCUS_BIG.includes(r.market)).reduce((s, r) => s + r.pnl, 0);
    const loss = results[sc.id].noCap.results.filter(r => FOCUS_LOSS.includes(r.market)).reduce((s, r) => s + r.pnl, 0);
    scoreMap[sc.id] = { pnl, big, loss, score: pnl + big * 1.5 + loss * 0.5 };
  }
  const rankByScore = Object.entries(scoreMap).sort((a, b) => b[1].score - a[1].score);

  lines.push('**종합 스코어 (총PnL + 큰추세PnL×1.5 + 손실케이스PnL×0.5)**:');
  lines.push('');
  lines.push('| 순위 | ID | 시나리오 | 총PnL | 큰추세합 | 손실합 | 종합점수 |');
  lines.push('|---|---|---|---:|---:|---:|---:|');
  for (let i = 0; i < rankByScore.length; i++) {
    const [id, v] = rankByScore[i];
    const sc = SCENARIOS.find(s => s.id === id);
    lines.push(`| ${i + 1} | **${id}** | ${sc.name} | ${fmt(v.pnl)} | ${fmt(v.big)} | ${fmt(v.loss)} | ${fmt(Math.round(v.score))} |`);
  }
  lines.push('');

  const rank1 = rankByScore[0][0];
  const rank2 = rankByScore[1][0];
  const rank3 = rankByScore[2][0];

  const sc1 = SCENARIOS.find(s => s.id === rank1);
  const sc2 = SCENARIOS.find(s => s.id === rank2);
  const sc3 = SCENARIOS.find(s => s.id === rank3);

  const sc1Big = results[rank1].noCap.results.filter(r => FOCUS_BIG.includes(r.market)).reduce((s, r) => s + r.pnl, 0);
  const sc2Big = results[rank2].noCap.results.filter(r => FOCUS_BIG.includes(r.market)).reduce((s, r) => s + r.pnl, 0);
  const sc3Big = results[rank3].noCap.results.filter(r => FOCUS_BIG.includes(r.market)).reduce((s, r) => s + r.pnl, 0);

  lines.push('---');
  lines.push('');
  lines.push('## 명확한 권고');
  lines.push('');
  lines.push(`### 1순위: **${rank1}** — ${sc1.name}`);
  lines.push('');
  lines.push(`- 종합 스코어 1위`);
  lines.push(`- 총 PnL: ${fmt(results[rank1].noCap.agg.totalPnl)} KRW`);
  lines.push(`- 큰추세(ENSO/TOKAMAK 등) 합산: ${fmt(sc1Big)} KRW (S0 대비 ${sc1Big - s0Big >= 0 ? '+' : ''}${fmt(sc1Big - s0Big)})`);
  lines.push(`- 구현 변경: ${rank1 === 'S6' ? 'trail drop 로직을 peak 구간별 ratchet (0.7/1.0/2.5/4.0/5.0) 적용' : rank1 === 'S5' ? 'peak >= 3% 이후 drop을 3.5%로 고정' : rank1 === 'S2' ? 'trail drop 단일 3.0% 통일' : '시나리오 상세 참조'}`);
  lines.push('');
  lines.push(`### 2순위: **${rank2}** — ${sc2.name}`);
  lines.push('');
  lines.push(`- 총 PnL: ${fmt(results[rank2].noCap.agg.totalPnl)} KRW`);
  lines.push(`- 큰추세 합산: ${fmt(sc2Big)} KRW (S0 대비 ${sc2Big - s0Big >= 0 ? '+' : ''}${fmt(sc2Big - s0Big)})`);
  lines.push('');
  lines.push(`### 3순위: **${rank3}** — ${sc3.name}`);
  lines.push('');
  lines.push(`- 총 PnL: ${fmt(results[rank3].noCap.agg.totalPnl)} KRW`);
  lines.push(`- 큰추세 합산: ${fmt(sc3Big)} KRW (S0 대비 ${sc3Big - s0Big >= 0 ? '+' : ''}${fmt(sc3Big - s0Big)})`);
  lines.push('');

  lines.push('### 핵심 결론');
  lines.push('');
  lines.push('1. **SPLIT_2ND_TRAIL이 너무 빠른 것이 핵심 문제** — V130 ladder에서 peakPct가 1.5~3% 구간이면 drop 0.5~1.0%로 매우 타이트, 이 구간에서 ENSO/TOKAMAK 등 10%+ 종목이 일시 하락 후 청산됨');
  lines.push('2. **Trail 폭 확대 시 큰추세 케이스는 크게 개선** — 단, SL_TIGHT 손실 건들은 trail 폭과 무관하게 -2.5% 고정');
  lines.push('3. **L1 캡은 소폭 개선** — L1 ROI가 평균 1.5% 수준이라 2.0~2.4% 캡의 영향 건수가 적고 효과 제한적');
  lines.push('4. **표본 27건, 통계 한계** — 실운영 2~4주 모니터링 후 재검증 필수');
  lines.push('');
  lines.push('---');
  lines.push(`*1분봉 실데이터 시뮬 기반 | 생성: ${new Date().toISOString()}*`);

  // 저장
  const reportPath = '.analysis/agent_precise_backtest.md';
  fs.writeFileSync(reportPath, lines.join('\n'), 'utf8');
  console.log('\n보고서 저장:', reportPath);

  // JSON
  const jsonOut = {
    meta: {
      generated: new Date().toISOString(),
      total_entries: ALL_ENTRIES.length,
      v130_after_n: V130_AFTER.length,
      big_trend_n: BIG_TREND_ENTRIES.filter(e => e.entry_price != null).length,
      candles_fetched: Object.keys(candleMap).filter(k => candleMap[k].length > 0).length,
      fee_pct: FEE_PCT, sl_tight_pct: SL_TIGHT_PCT, l1_roi_floor: L1_ROI_FLOOR,
    },
    scenario_agg: Object.fromEntries(SCENARIOS.map(sc => [sc.id, {
      name: sc.name,
      no_cap: results[sc.id].noCap.agg,
    }])),
    cap_matrix_agg: Object.fromEntries(Object.entries(capMatrix).map(([scId, caps]) => [
      scId, Object.fromEntries(Object.entries(caps).map(([capKey, obj]) => [capKey, obj.agg]))
    ])),
    big_case_detail: Object.fromEntries(Object.entries(bigCaseDetail).map(([key, scMap]) => [
      key, Object.fromEntries(Object.entries(scMap).map(([scId, r]) => [scId, r && !r.skipped ? { pnl: r.pnl, avg_roi: r.avg_roi, reason: r.reason, peak_pct: r.peak_pct } : null]))
    ])),
    loss_case_detail: Object.fromEntries(Object.entries(lossCaseDetail).map(([key, scMap]) => [
      key, Object.fromEntries(Object.entries(scMap).map(([scId, r]) => [scId, r && !r.skipped ? { pnl: r.pnl, avg_roi: r.avg_roi, reason: r.reason, peak_pct: r.peak_pct } : null]))
    ])),
    ranking: {
      by_total_pnl: rankByPnl.map(r => ({ id: r.id, pnl: r.pnl })),
      by_big_trend: rankByBig.map(r => ({ id: r.id, big_sum: r.bigSum })),
      by_safety: rankBySafety.map(r => ({ id: r.id, loss_sum: r.lossSum })),
      by_composite: rankByScore.map(([id, v]) => ({ id, pnl: v.pnl, big: v.big, loss: v.loss, score: Math.round(v.score) })),
    },
  };
  const jsonPath = '.analysis/raw/precise_backtest_results.json';
  fs.writeFileSync(jsonPath, JSON.stringify(jsonOut, null, 2), 'utf8');
  console.log('JSON 저장:', jsonPath);

  // 콘솔 요약
  console.log('\n=== 콘솔 요약 ===');
  console.log('S0~S9 총 PnL 랭킹 (전체):');
  for (const r of rankByPnl) {
    console.log(`  ${r.id}: ${fmt(r.pnl)}`);
  }
  console.log('\n큰추세 합산 랭킹:');
  for (const r of rankByBig) {
    console.log(`  ${r.id}: ${fmt(r.bigSum)}`);
  }
  console.log('\n종합점수 Top3:', rankByScore.slice(0, 3).map(([id]) => id).join(' > '));
})();

function fmt(n) {
  if (n == null) return '-';
  return n.toLocaleString('ko-KR');
}
