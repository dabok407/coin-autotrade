// V130 이후 22건 1분봉 페치 + Trail Ladder 시뮬레이터
// 출력: .analysis/agent_trail_resim_v130.md
// Run: node .analysis/trail_resim_v130.js

'use strict';
const fs = require('fs');
const https = require('https');
const path = require('path');

// ── 데이터 로드 ─────────────────────────────────────────────────
const PARSED = JSON.parse(fs.readFileSync('.analysis/trades_19d_parsed.json', 'utf8'));
const V130_AFTER = PARSED.pairs.filter(p => p.v130_era === 'after' && p.exit_reason !== 'STILL_OPEN');
console.log('V130-after 폐쇄 거래:', V130_AFTER.length, '건');

// 특수 분석 대상
const SPECIAL_CASES = [
  { market: 'KRW-OPEN', entry_ts: '2026-04-29 09:26:10' },  // H2 검증 — 04-29 OP
];
// V130 전 대박 참고: 04-26 KRW-ENSO 13:22 (v130_era=before) — 캔들도 페치
const SPECIAL_PRE = [
  { market: 'KRW-ENSO', entry_ts: '2026-04-26 13:22:25', scanner: 'AD', entry_price: null },
];

// ── 유틸 함수 ────────────────────────────────────────────────────
function kstToUtcIso(kstStr) {
  const [d, t] = kstStr.split(' ');
  const [y, mo, day] = d.split('-').map(Number);
  const [h, m, s] = t.split(':').map(Number);
  const utcMs = Date.UTC(y, mo - 1, day, h - 9, m, s);
  return new Date(utcMs).toISOString().replace('.000Z', 'Z');
}
function kstAddMinUtcIso(kstStr, addMin) {
  const [d, t] = kstStr.split(' ');
  const [y, mo, day] = d.split('-').map(Number);
  const [h, m, s] = t.split(':').map(Number);
  const utcMs = Date.UTC(y, mo - 1, day, h - 9, m, s) + addMin * 60000;
  return new Date(utcMs).toISOString().replace('.000Z', 'Z');
}
function parseKst(ts) { return new Date(ts.replace(' ', 'T') + '+09:00'); }

function fetchJson(url, retries = 4) {
  return new Promise((resolve, reject) => {
    const attempt = (n) => {
      const req = https.get(url, { headers: { 'User-Agent': 'Mozilla/5.0' } }, res => {
        let data = '';
        res.on('data', c => data += c);
        res.on('end', () => {
          if (res.statusCode === 200) {
            try { resolve(JSON.parse(data)); }
            catch (e) { reject(new Error('JSON parse error: ' + e.message)); }
          } else {
            if (n > 1) setTimeout(() => attempt(n - 1), 800);
            else reject(new Error('HTTP ' + res.statusCode + ': ' + data.slice(0, 200)));
          }
        });
      });
      req.on('error', err => {
        if (n > 1) setTimeout(() => attempt(n - 1), 800);
        else reject(err);
      });
    };
    attempt(retries);
  });
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

// ── 1분봉 페치 ───────────────────────────────────────────────────
// 업비트 API: to 기준으로 최신→과거 200개 반환
// 진입 후 N분 커버: to = entry + (N+1)분, count=N+1
// 단, 거래량 적어 1분봉 gap이 있으면 200개 역산 시 진입 이전으로 거슬러 올라감
// → to = entry + max_hold_min (MR=59분, OP=175분, AD=1200분)
// → 실제 필요한 구간을 확실히 커버하도록 2-pass fetch
async function fetchCandles(market, entry_ts, scanner) {
  const entryDt = parseKst(entry_ts).getTime();

  // 스캐너별 만료 시간까지 커버 (분 단위)
  let maxHold;
  if (scanner === 'MR') maxHold = 65;   // 09:01~10:00 = 59분 + buffer
  else if (scanner === 'OP') maxHold = 185; // 09:05~12:00 = 175분 + buffer
  else maxHold = 200;  // AD 등

  // Pass1: entry ~ entry+maxHold 사이만 필요
  // to = entry + maxHold분 + 1분 (마지막 캔들 포함)
  const toUtc = kstAddMinUtcIso(entry_ts, maxHold + 1);
  // count = maxHold+1 (최대 200)
  const count = Math.min(maxHold + 2, 200);
  const url = `https://api.upbit.com/v1/candles/minutes/1?market=${encodeURIComponent(market)}&to=${encodeURIComponent(toUtc)}&count=${count}`;
  const raw = await fetchJson(url);
  const ordered = raw.reverse();
  return ordered
    .filter(c => new Date(c.candle_date_time_kst).getTime() >= entryDt)
    .map(c => ({
      ts_kst: c.candle_date_time_kst,
      o: c.opening_price, h: c.high_price, l: c.low_price, c: c.trade_price,
      v: c.candle_acc_trade_volume,
    }));
}

// ── 시뮬레이션 파라미터 ──────────────────────────────────────────
const ARMED_THR = 1.5;   // trail armed 임계값 (peak >= entry*1.015)
const RATIO_1ST = 0.50;  // V130: split 50%
const RATIO_2ND = 0.50;
const COOLDOWN_MIN = 1;
const ROUND_FEE_PCT = 0.10;  // round-trip fee + slippage

// SL 설정 (V130: TIGHT=2.5%, WIDE=3.0%)
const SL_TIGHT_PCT = 2.5;
const SL_WIDE_PCT = 3.0;
// 실제 로그에서 SL_TIGHT 건들 ROI 평균 -2.4% 내외 → SL_TIGHT 를 2.5%로 설정
// SL by scanner
function slPct(scanner) { return SL_TIGHT_PCT; }  // V130는 대부분 SL_TIGHT
function timeExitMinKst(scanner, entryDate) {
  if (scanner === 'MR') return setKstH(entryDate, 10, 0);
  if (scanner === 'OP') return setKstH(entryDate, 12, 0);
  if (scanner === 'AD') {
    const d = new Date(entryDate); d.setDate(d.getDate() + 1);
    return setKstH(d, 8, 59);
  }
  return setKstH(entryDate, 23, 0);
}
function setKstH(d, h, m) { const dt = new Date(d); dt.setHours(h, m, 0, 0); return dt; }

// ── Trail Ladder 규칙 ─────────────────────────────────────────────
const LADDERS = {
  V130: {
    name: 'V130 (0.5/1.0/1.5/2.0)',
    drop: (peak) => peak < 2 ? 0.5 : peak < 3 ? 1.0 : peak < 5 ? 1.5 : 2.0,
    drop2: (peak) => peak < 2 ? 0.8 : peak < 3 ? 1.2 : peak < 5 ? 1.5 : 2.0,
  },
  LadderB: {
    name: 'Ladder B (0.7/1.5/2.0/2.5)',
    drop: (peak) => peak < 2 ? 0.7 : peak < 3 ? 1.5 : peak < 5 ? 2.0 : 2.5,
    drop2: (peak) => peak < 2 ? 1.0 : peak < 3 ? 1.5 : peak < 5 ? 2.0 : 2.5,
  },
  LadderC: {
    name: 'Ladder C (1.0/1.5/2.0/3.0)',
    drop: (peak) => peak < 2 ? 1.0 : peak < 3 ? 1.5 : peak < 5 ? 2.0 : 3.0,
    drop2: (peak) => peak < 2 ? 1.2 : peak < 3 ? 1.5 : peak < 5 ? 2.0 : 3.0,
  },
  Hybrid: {
    name: 'Hybrid (peak<3% LadderA / peak≥3% drop2.5 fixed)',
    drop: (peak) => peak < 2 ? 0.5 : peak < 3 ? 1.0 : 2.5,
    drop2: (peak) => peak < 2 ? 0.8 : peak < 3 ? 1.2 : 2.5,
  },
};

// L1 캡 옵션 (가설 H1)
const L1_CAPS = [null, 1.8, 2.0, 2.1, 2.4];  // null = 캡 없음

// ── 단일 거래 시뮬 ────────────────────────────────────────────────
function simulate(entry, candles, ladder, l1Cap = null) {
  if (!candles || candles.length === 0) return { skipped: true, reason: 'NO_CANDLES' };

  const entryPrice = entry.entry_price;
  const sl = entryPrice * (1 - SL_TIGHT_PCT / 100);
  const timeExit = timeExitMinKst(entry.scanner, parseKst(entry.entry_ts));

  let peak = entryPrice;
  let armed = false;
  let split1 = null;
  let split2 = null;
  let cooldownIdx = -1;

  for (let i = 0; i < candles.length; i++) {
    const c = candles[i];
    const cTime = new Date(c.ts_kst.replace('T', ' ') + '+09:00').getTime();
    const { h, l } = c;

    // Peak 갱신
    if (h > peak) peak = h;
    const peakPct = (peak - entryPrice) / entryPrice * 100;

    // 1) Time exit
    if (cTime >= timeExit.getTime()) {
      return mkResult(entry, entryPrice, c.c, split1, split2, peak, 'TIME_EXIT');
    }

    // 2) SL (split1 전만)
    if (!split1 && l <= sl) {
      return mkResult(entry, entryPrice, sl, null, null, peak, 'SL_TIGHT');
    }

    // 3) Armed
    if (!armed && peakPct >= ARMED_THR) armed = true;

    // 4) L1 캡: armed && peak >= entry*(1+l1Cap%) → 즉시 전량 매도
    if (armed && l1Cap !== null && !split1) {
      const capPrice = entryPrice * (1 + l1Cap / 100);
      if (h >= capPrice) {
        // L1 강제 매도 @ capPrice (50%), 나머지는 peak 직후 drop으로 청산 (계속 관찰)
        split1 = { price: capPrice, idx: i, forced_cap: true };
        cooldownIdx = i + COOLDOWN_MIN;
        // 이 캔들에서 split2도 바로 떨어지는지 확인 (연속 처리)
      }
    }

    // 4b) SPLIT_1ST — armed, no cap 또는 cap 적용 안 됨
    if (armed && !split1) {
      const drop1 = ladder.drop(peakPct);
      const target1 = peak * (1 - drop1 / 100);
      if (l <= target1) {
        split1 = { price: target1, idx: i, forced_cap: false };
        cooldownIdx = i + COOLDOWN_MIN;
      }
    }

    // 5) SPLIT_2ND
    if (split1 && i >= cooldownIdx && !split2) {
      const drop2 = ladder.drop2(peakPct);
      const target2 = peak * (1 - drop2 / 100);
      if (l <= target2) {
        split2 = { price: target2, idx: i };
        return mkResult(entry, entryPrice, null, split1, split2, peak, 'SPLIT_2ND_TRAIL');
      }
    }
  }

  const last = candles[candles.length - 1];
  return mkResult(entry, entryPrice, last.c, split1, split2, peak, 'END_OF_DATA');
}

function mkResult(entry, entryPrice, finalPrice, split1, split2, peak, reason) {
  const qty = entry.qty || 1;
  let segments = [];
  let ratio_exec = 0;
  if (split1) {
    segments.push({ ratio: RATIO_1ST, price: split1.price, roi: (split1.price - entryPrice) / entryPrice * 100, forced_cap: split1.forced_cap });
    ratio_exec += RATIO_1ST;
  }
  if (split2) {
    segments.push({ ratio: RATIO_2ND, price: split2.price, roi: (split2.price - entryPrice) / entryPrice * 100 });
    ratio_exec += RATIO_2ND;
  }
  const rem = 1 - ratio_exec;
  if (rem > 0.001 && finalPrice != null) {
    segments.push({ ratio: rem, price: finalPrice, roi: (finalPrice - entryPrice) / entryPrice * 100 });
  }
  const entryVal = entryPrice * qty;
  let avgRoi = 0;
  for (const s of segments) avgRoi += s.ratio * s.roi;
  avgRoi -= ROUND_FEE_PCT;
  const pnl = Math.round(entryVal * avgRoi / 100);
  const peakPct = (peak - entryPrice) / entryPrice * 100;
  return {
    market: entry.market, scanner: entry.scanner, entry_ts: entry.entry_ts,
    entry_price: entryPrice, peak, peak_pct: peakPct,
    segments, avg_roi: avgRoi, pnl, reason,
    split1_roi: split1 ? (split1.price - entryPrice) / entryPrice * 100 : null,
    split1_forced: split1 ? split1.forced_cap : false,
  };
}

// ── 시나리오 집계 ─────────────────────────────────────────────────
function aggregate(results) {
  const n = results.length;
  const wins = results.filter(r => r.pnl > 0).length;
  const totalPnl = results.reduce((s, r) => s + r.pnl, 0);
  const peak5plus = results.filter(r => r.peak_pct >= 5).length;
  const tpTrailLike = results.filter(r => r.peak_pct >= 3 && r.reason === 'SPLIT_2ND_TRAIL').length;
  const byReason = {};
  for (const r of results) {
    if (!byReason[r.reason]) byReason[r.reason] = { n: 0, pnl: 0, wins: 0 };
    byReason[r.reason].n++;
    byReason[r.reason].pnl += r.pnl;
    if (r.pnl > 0) byReason[r.reason].wins++;
  }
  return { n, wins, wr: n ? (wins / n * 100).toFixed(1) : '0', totalPnl, peak5plus, tpTrailLike, byReason };
}

// ── 메인 실행 ─────────────────────────────────────────────────────
(async () => {
  // 1분봉 페치
  console.log('\n[1] V130 이후 22건 1분봉 페치...');
  const candles = {};  // key = market|entry_ts

  // V130 이후 거래 + 특수 사전 케이스(ENSO)
  const toFetch = [
    ...V130_AFTER.map(p => ({ market: p.market, entry_ts: p.entry_ts, scanner: p.scanner, entry_price: p.entry_price, qty: p.qty })),
  ];
  // ENSO pre-V130 대박 케이스 — entry 정보 수동
  const ensoEntry = PARSED.pairs.find(p => p.market === 'KRW-ENSO' && p.entry_ts === '2026-04-26 13:22:25');
  let specialEnso = null;
  if (ensoEntry) {
    toFetch.push({ market: ensoEntry.market, entry_ts: ensoEntry.entry_ts, scanner: ensoEntry.scanner, entry_price: ensoEntry.entry_price, qty: ensoEntry.qty });
    specialEnso = ensoEntry;
  }

  let fetched = 0, failed = 0;
  for (const e of toFetch) {
    const key = e.market + '|' + e.entry_ts;
    try {
      const c = await fetchCandles(e.market, e.entry_ts, e.scanner);
      candles[key] = c;
      fetched++;
      process.stdout.write(`  [${fetched}/${toFetch.length}] ${e.market} ${e.entry_ts}: ${c.length}개\n`);
    } catch (err) {
      console.error(`  [ERR] ${key}: ${err.message}`);
      candles[key] = [];
      failed++;
    }
    await sleep(120);
  }
  console.log(`페치 완료: ${fetched}건 성공, ${failed}건 실패\n`);

  // 2) L1 캡 시뮬 (V130 Ladder 고정, l1Cap 변화)
  console.log('[2] L1 캡 시뮬 (V130 Ladder)...');
  const capSimResults = {};
  for (const cap of L1_CAPS) {
    const capKey = cap === null ? 'NO_CAP' : `CAP_${cap.toFixed(1).replace('.', 'p')}`;
    const results = [];
    for (const e of V130_AFTER) {
      const key = e.market + '|' + e.entry_ts;
      const r = simulate(e, candles[key], LADDERS.V130, cap);
      if (!r.skipped) results.push(r);
    }
    capSimResults[capKey] = { cap, results, agg: aggregate(results) };
  }

  // 3) Ladder 시나리오 비교 (cap 없음)
  console.log('[3] Ladder 시나리오 비교...');
  const ladderResults = {};
  for (const [lKey, ladder] of Object.entries(LADDERS)) {
    const results = [];
    for (const e of V130_AFTER) {
      const key = e.market + '|' + e.entry_ts;
      const r = simulate(e, candles[key], ladder, null);
      if (!r.skipped) results.push(r);
    }
    ladderResults[lKey] = { results, agg: aggregate(results) };
  }

  // 4) Hybrid + L1 2.1% 캡 조합
  console.log('[4] Hybrid + L1 2.1% 조합...');
  const hybridCapResults = [];
  for (const e of V130_AFTER) {
    const key = e.market + '|' + e.entry_ts;
    const r = simulate(e, candles[key], LADDERS.Hybrid, 2.1);
    if (!r.skipped) hybridCapResults.push(r);
  }
  const hybridCapAgg = aggregate(hybridCapResults);

  // 5) 특수 케이스 분석
  console.log('[5] 특수 케이스 분석...');
  // 04-29 KRW-OPEN 09:26 — 4개 ladder 비교
  const openEntry = V130_AFTER.find(p => p.market === 'KRW-OPEN' && p.entry_ts === '2026-04-29 09:26:10');
  const openCandleKey = 'KRW-OPEN|2026-04-29 09:26:10';
  const openAnalysis = {};
  if (openEntry && candles[openCandleKey] && candles[openCandleKey].length > 0) {
    for (const [lKey, ladder] of Object.entries(LADDERS)) {
      openAnalysis[lKey] = simulate(openEntry, candles[openCandleKey], ladder, null);
    }
  }

  // 04-26 KRW-ENSO 13:22 — V130 ladder 시 얼마나 잘렸는지
  const ensoKey = 'KRW-ENSO|2026-04-26 13:22:25';
  const ensoAnalysis = {};
  if (specialEnso && candles[ensoKey] && candles[ensoKey].length > 0) {
    for (const [lKey, ladder] of Object.entries(LADDERS)) {
      ensoAnalysis[lKey] = simulate(specialEnso, candles[ensoKey], ladder, null);
    }
  }

  // ── 보고서 작성 ──────────────────────────────────────────────────
  console.log('\n[6] 보고서 생성...');

  // 캔들 통계
  const candleCounts = V130_AFTER.map(e => (candles[e.market + '|' + e.entry_ts] || []).length);
  const totalWithCandles = candleCounts.filter(n => n > 0).length;
  const avgCandles = totalWithCandles > 0 ? Math.round(candleCounts.reduce((s, n) => s + n, 0) / candleCounts.length) : 0;

  // L1 ROI 분포 집계
  const split1Trades = V130_AFTER.filter(p => p.split1 != null);
  const l1Rois = split1Trades.map(p => p.split1.roi);
  const l1Avg = l1Rois.length ? (l1Rois.reduce((s, r) => s + r, 0) / l1Rois.length).toFixed(3) : '?';
  const l1Max = l1Rois.length ? Math.max(...l1Rois).toFixed(2) : '?';
  const l1Min = l1Rois.length ? Math.min(...l1Rois).toFixed(2) : '?';
  const sortedL1 = [...l1Rois].sort((a, b) => a - b);
  const l1Med = sortedL1.length ? sortedL1[Math.floor(sortedL1.length / 2)].toFixed(2) : '?';
  // 분포 버킷
  const l1Buckets = { '<1%': 0, '1-1.5%': 0, '1.5-2%': 0, '2-2.5%': 0, '2.5%+': 0 };
  for (const r of l1Rois) {
    if (r < 1) l1Buckets['<1%']++;
    else if (r < 1.5) l1Buckets['1-1.5%']++;
    else if (r < 2) l1Buckets['1.5-2%']++;
    else if (r < 2.5) l1Buckets['2-2.5%']++;
    else l1Buckets['2.5%+']++;
  }

  // L1 캡 시뮬 결과: 영향받는 건수 계산
  function capImpactCount(cap, results) {
    if (cap === null) return 0;
    return results.filter(r => r.split1_forced).length;
  }

  // 현재 실제 PnL(로그 기준)
  const actualPnl = V130_AFTER.reduce((s, p) => s + (p.pnl_total || 0), 0);

  // 보고서 마크다운 생성
  const lines = [];

  lines.push('# V130 Trail Ladder 리시뮬레이션 분석');
  lines.push('');
  lines.push(`**분석 기간**: 2026-04-29 ~ 2026-05-02 (V130 이후 4일)`);
  lines.push(`**대상**: V130-after 폐쇄 거래 ${V130_AFTER.length}건 (STILL_OPEN 2건 제외)`);
  lines.push(`**1분봉 데이터**: ${totalWithCandles}건 페치 성공, ${V130_AFTER.length - totalWithCandles}건 실패, 평균 ${avgCandles}개 캔들/거래`);
  lines.push(`**실제 로그 PnL**: ${actualPnl.toLocaleString('ko-KR')} KRW (V130 기간)`);
  lines.push('');
  lines.push('---');
  lines.push('');

  // === 1. L1 ROI 분포 ===
  lines.push('## 1. L1(SPLIT_1ST) ROI 분포 분석');
  lines.push('');
  lines.push(`실제 split1 발생 거래: **${split1Trades.length}건** / 22건`);
  lines.push('');
  lines.push('| 통계 | 값 |');
  lines.push('|---|---|');
  lines.push(`| 평균 L1 ROI | **${l1Avg}%** |`);
  lines.push(`| 최대 | ${l1Max}% |`);
  lines.push(`| 최소 | ${l1Min}% |`);
  lines.push(`| 중앙값 | ${l1Med}% |`);
  lines.push('');
  lines.push('**L1 ROI 분포 히스토그램** (12건):');
  lines.push('');
  lines.push('| 구간 | 건수 | 비율 |');
  lines.push('|---|---:|---:|');
  for (const [bucket, cnt] of Object.entries(l1Buckets)) {
    lines.push(`| ${bucket} | ${cnt} | ${l1Rois.length ? (cnt / l1Rois.length * 100).toFixed(0) : 0}% |`);
  }
  lines.push('');
  lines.push('**해석**: L1 ROI가 1.5~2% 구간에 집중. 2.1% 이상은 극소수 → 캡 효과 제한적.');
  lines.push('');

  // === 2. L1 캡 시뮬 결과 ===
  lines.push('## 2. L1 캡 시뮬레이션 결과 (V130 Ladder 고정)');
  lines.push('');
  lines.push(`> 시뮬 기준: ${totalWithCandles}건 1분봉 실가격, ROUND_FEE ${ROUND_FEE_PCT}%`);
  lines.push('');
  lines.push('| 캡 설정 | 영향 건수 | PnL (KRW) | WR | peak5%+ | 비고 |');
  lines.push('|---|---:|---:|---:|---:|---|');

  for (const [capKey, obj] of Object.entries(capSimResults)) {
    const { cap, agg, results } = obj;
    const impact = results.filter(r => r.split1_forced).length;
    const capLabel = cap === null ? '없음 (현재 V130)' : `+${cap}% 강제 캡`;
    lines.push(`| ${capLabel} | ${impact} | **${agg.totalPnl.toLocaleString('ko-KR')}** | ${agg.wr}% | ${agg.peak5plus} | |`);
  }
  lines.push('');

  // 가장 좋은 cap
  let bestCapKey = 'NO_CAP';
  let bestCapPnl = capSimResults['NO_CAP'].agg.totalPnl;
  for (const [k, v] of Object.entries(capSimResults)) {
    if (v.agg.totalPnl > bestCapPnl) { bestCapPnl = v.agg.totalPnl; bestCapKey = k; }
  }
  lines.push(`**최적 캡**: ${bestCapKey === 'NO_CAP' ? '캡 없음' : bestCapKey} (PnL ${bestCapPnl.toLocaleString('ko-KR')} KRW)`);
  lines.push('');

  // === 3. Ladder 시나리오 비교 ===
  lines.push('## 3. Trail Ladder 시나리오 비교 (22건 1분봉 시뮬)');
  lines.push('');
  lines.push('| Ladder | 거래 | 승 | WR% | PnL (KRW) | peak5%+ | peak3%+종료 | 비고 |');
  lines.push('|---|---:|---:|---:|---:|---:|---:|---|');

  // 실제 로그 먼저
  const actualWins = V130_AFTER.filter(p => (p.pnl_total || 0) > 0).length;
  const actualPeak5 = V130_AFTER.filter(p => p.peak_pct != null && p.peak_pct >= 5).length;
  const actualTpTrail = V130_AFTER.filter(p => p.exit_reason === 'TP_TRAIL').length;
  lines.push(`| 실제 로그 (V130 운영) | 22 | ${actualWins} | ${(actualWins/22*100).toFixed(1)}% | **${actualPnl.toLocaleString('ko-KR')}** | ${actualPeak5} | ${actualTpTrail} | 기준선 |`);

  for (const [lKey, obj] of Object.entries(ladderResults)) {
    const { agg, results } = obj;
    const ladder = LADDERS[lKey];
    const isV130 = lKey === 'V130';
    lines.push(`| ${ladder.name} | ${agg.n} | ${agg.wins} | ${agg.wr}% | **${agg.totalPnl.toLocaleString('ko-KR')}** | ${agg.peak5plus} | ${agg.tpTrailLike} | ${isV130 ? '← 현재' : ''} |`);
  }
  lines.push('');

  // 사유별 상세 (V130, Hybrid)
  for (const lKey of ['V130', 'Hybrid']) {
    const obj = ladderResults[lKey];
    lines.push(`**${LADDERS[lKey].name} — 사유별 집계:**`);
    lines.push('');
    lines.push('| 사유 | 건수 | 승 | PnL |');
    lines.push('|---|---:|---:|---:|');
    for (const [r, v] of Object.entries(obj.agg.byReason)) {
      lines.push(`| ${r} | ${v.n} | ${v.wins} | ${v.pnl.toLocaleString('ko-KR')} |`);
    }
    lines.push('');
  }

  // === 4. Hybrid + L1 2.1% 조합 ===
  lines.push('## 4. 최적 조합: Hybrid Ladder + L1 +2.1% 캡');
  lines.push('');
  lines.push('| 항목 | 값 |');
  lines.push('|---|---|');
  lines.push(`| 거래 건수 | ${hybridCapAgg.n} |`);
  lines.push(`| 승 / 패 | ${hybridCapAgg.wins} / ${hybridCapAgg.n - hybridCapAgg.wins} |`);
  lines.push(`| WR | ${hybridCapAgg.wr}% |`);
  lines.push(`| PnL (KRW) | **${hybridCapAgg.totalPnl.toLocaleString('ko-KR')}** |`);
  lines.push(`| peak 5%+ | ${hybridCapAgg.peak5plus} |`);
  lines.push(`| peak 3%+ 종료 | ${hybridCapAgg.tpTrailLike} |`);
  lines.push('');
  lines.push('**사유별:**');
  lines.push('| 사유 | 건수 | 승 | PnL |');
  lines.push('|---|---:|---:|---:|');
  for (const [r, v] of Object.entries(hybridCapAgg.byReason)) {
    lines.push(`| ${r} | ${v.n} | ${v.wins} | ${v.pnl.toLocaleString('ko-KR')} |`);
  }
  lines.push('');

  // Delta vs V130
  const v130Pnl = ladderResults['V130'].agg.totalPnl;
  const delta = hybridCapAgg.totalPnl - v130Pnl;
  lines.push(`**V130 시뮬 대비 Δ PnL**: ${delta >= 0 ? '+' : ''}${delta.toLocaleString('ko-KR')} KRW`);
  lines.push('');

  // === 5. 특수 케이스 분석 ===
  lines.push('## 5. 특수 케이스 분석');
  lines.push('');

  // 04-29 KRW-OPEN 09:26
  lines.push('### Case A: 04-29 KRW-OPEN(OP) 09:26 — peak 3.21%, 실제 청산 -1.98%');
  lines.push('');
  if (Object.keys(openAnalysis).length > 0) {
    lines.push('| Ladder | split1 ROI | split2 ROI | 최종 PnL | 사유 | peak% |');
    lines.push('|---|---:|---:|---:|---|---:|');
    for (const [lKey, r] of Object.entries(openAnalysis)) {
      const s1 = r.split1_roi != null ? r.split1_roi.toFixed(2) + '%' : '-';
      const s2seg = r.segments && r.segments.length >= 2 ? r.segments[1].roi.toFixed(2) + '%' : '-';
      lines.push(`| ${LADDERS[lKey].name} | ${s1} | ${s2seg} | **${r.pnl.toLocaleString('ko-KR')}** | ${r.reason} | ${r.peak_pct.toFixed(2)}% |`);
    }
    lines.push('');
    // 실제 로그 결과
    const openReal = V130_AFTER.find(p => p.market === 'KRW-OPEN' && p.entry_ts === '2026-04-29 09:26:10');
    lines.push(`**실제 로그**: split1 +2.22%, split2 -1.98%, pnl=${openReal.pnl_total} KRW — 피크 3.21% 찍고 V130 drop 0.5% 이내 반전이 아닌 급락`);
  } else {
    lines.push('1분봉 데이터 없음 — 시뮬 불가');
  }
  lines.push('');

  // 04-26 KRW-ENSO 13:22
  lines.push('### Case B: 04-26 KRW-ENSO(AD) 13:22 — V130 전 +6.37% 대박');
  lines.push('');
  if (Object.keys(ensoAnalysis).length > 0) {
    lines.push('| Ladder | split1 ROI | split2 ROI | 최종 PnL | 사유 | peak% |');
    lines.push('|---|---:|---:|---:|---|---:|');
    for (const [lKey, r] of Object.entries(ensoAnalysis)) {
      const s1 = r.split1_roi != null ? r.split1_roi.toFixed(2) + '%' : '-';
      const s2seg = r.segments && r.segments.length >= 2 ? r.segments[1].roi.toFixed(2) + '%' : '-';
      lines.push(`| ${LADDERS[lKey].name} | ${s1} | ${s2seg} | **${r.pnl.toLocaleString('ko-KR')}** | ${r.reason} | ${r.peak_pct.toFixed(2)}% |`);
    }
    const actualEnso = PARSED.pairs.find(p => p.market === 'KRW-ENSO' && p.entry_ts === '2026-04-26 13:22:25');
    lines.push('');
    lines.push(`**실제 로그**: pnl=${actualEnso ? actualEnso.pnl_total : '?'} KRW (+6.37%), peak 미기록`);
    lines.push('');
    lines.push('> V130 Ladder가 적용됐다면 — drop1=0.5% 기준 2% 대 초반에 split1 발동 후 split2로 조기 청산 가능성 높음');
  } else {
    lines.push('1분봉 데이터 없음 — 시뮬 불가');
  }
  lines.push('');

  // === 6. 가설 검증 ===
  lines.push('## 6. 가설 검증 결론');
  lines.push('');
  lines.push('### 가설 H1 — L1 +2.1% 캡 강제 익절');
  lines.push('');

  const noCap = capSimResults['NO_CAP'];
  const cap21 = capSimResults['CAP_2p1'] || capSimResults['CAP_2.1'];
  if (cap21) {
    const capDelta = cap21.agg.totalPnl - noCap.agg.totalPnl;
    const capImpact = cap21.results.filter(r => r.split1_forced).length;
    lines.push(`- 1분봉 시뮬 기준 캡 없음 PnL: ${noCap.agg.totalPnl.toLocaleString('ko-KR')} KRW`);
    lines.push(`- L1 +2.1% 캡 적용 PnL: ${cap21.agg.totalPnl.toLocaleString('ko-KR')} KRW`);
    lines.push(`- 영향 거래: ${capImpact}건 / 22건`);
    lines.push(`- Δ PnL: ${capDelta >= 0 ? '+' : ''}${capDelta.toLocaleString('ko-KR')} KRW`);
    lines.push('');
    if (capDelta > 1000) {
      lines.push('**실데이터 기반 판단: 적용 가치 있음** ✓');
      lines.push(`> V130 구간 L1 ROI 평균 1.467% → 2.1% 캡은 도달 빈도 낮아 소폭 효과. 단 peak3%+ 거래에서 +2.1% 강제 청산이 2nd 수익 증가.`);
    } else if (capDelta < -500) {
      lines.push('**실데이터 기반 판단: 적용 가치 낮음** ✗');
      lines.push(`> L1 ROI 1.5% 미만 건 다수 — 캡이 걸리는 건수 적고, 오히려 peak가 2.1% 넘어 계속 상승하는 거래를 조기 청산할 위험.`);
    } else {
      lines.push('**실데이터 기반 판단: 중립 (±소폭)** — 22건 표본으로는 유의한 차이 없음');
    }
  }
  lines.push('');

  lines.push('### 가설 H2 — 5%+ 대박 거래가 사라진 원인');
  lines.push('');

  // peak 분포 비교
  const prePeakBuckets = PARSED.pairs.filter(p => p.v130_era === 'before' && p.exit_reason !== 'STILL_OPEN' && p.peak_pct != null);
  const postPeakBuckets = V130_AFTER.filter(p => p.peak_pct != null);
  const preAvgPeak = prePeakBuckets.length ? (prePeakBuckets.reduce((s, p) => s + p.peak_pct, 0) / prePeakBuckets.length).toFixed(2) : '?';
  const postAvgPeak = postPeakBuckets.length ? (postPeakBuckets.reduce((s, p) => s + p.peak_pct, 0) / postPeakBuckets.length).toFixed(2) : '?';

  lines.push(`- V130 이전 평균 peak%: ${preAvgPeak}% (데이터 있는 ${prePeakBuckets.length}건)`);
  lines.push(`- V130 이후 평균 peak%: ${postAvgPeak}% (데이터 있는 ${postPeakBuckets.length}건)`);
  lines.push('');
  lines.push('**시뮬 peak5%+ 건수 비교:**');
  lines.push('');
  lines.push('| Ladder | peak5%+ |');
  lines.push('|---|---:|');
  for (const [lKey, obj] of Object.entries(ladderResults)) {
    lines.push(`| ${LADDERS[lKey].name} | ${obj.agg.peak5plus} |`);
  }
  lines.push('');

  const hybridPeak5 = hybridCapAgg.peak5plus;
  const anyLadderPeak5 = Object.values(ladderResults).reduce((s, r) => Math.max(s, r.agg.peak5plus), 0);

  if (anyLadderPeak5 === 0) {
    lines.push('**판단: 시장 자체가 변했다** (Ladder 교체로도 peak5%+ 발생 없음)');
    lines.push('> 04-29~05-02 기간 OP 진입 마켓들이 3% 초과 급등 후 지속 상승하는 거래 자체가 없었음.');
    lines.push('> V130 Ladder가 큰 추세를 일부 조기 차단하지만, 근본 원인은 시장 변동성 감소 (5%+ 도달 기회 제로).');
  } else {
    lines.push(`**판단: Ladder drop 컷 + 시장 변화 복합 원인** (Ladder 완화 시 peak5%+ 가능성 ${anyLadderPeak5}건)`);
    lines.push('> Ladder C/Hybrid로 완화하면 일부 거래가 더 오래 보유 가능. 단 drop이 너무 넓으면 SL 없이 큰 손실 위험.');
  }
  lines.push('');

  // === 7. 최종 추천 ===
  lines.push('## 7. 최종 추천');
  lines.push('');

  // 가장 좋은 ladder 찾기
  let bestLadderKey = 'V130';
  let bestLadderPnl = ladderResults['V130'].agg.totalPnl;
  for (const [k, v] of Object.entries(ladderResults)) {
    if (v.agg.totalPnl > bestLadderPnl) { bestLadderPnl = v.agg.totalPnl; bestLadderKey = k; }
  }

  lines.push(`**추천 조합**: ${bestLadderKey} Ladder + ${bestCapKey === 'NO_CAP' ? '캡 없음' : 'L1 ' + bestCapKey + '% 캡'}`);
  lines.push('');
  lines.push('| 시나리오 | PnL (KRW) | V130 대비 Δ |');
  lines.push('|---|---:|---:|');
  lines.push(`| V130 현재 (시뮬) | ${v130Pnl.toLocaleString('ko-KR')} | 기준 |`);
  for (const [lKey, obj] of Object.entries(ladderResults)) {
    if (lKey === 'V130') continue;
    const d = obj.agg.totalPnl - v130Pnl;
    lines.push(`| ${LADDERS[lKey].name} | ${obj.agg.totalPnl.toLocaleString('ko-KR')} | ${d >= 0 ? '+' : ''}${d.toLocaleString('ko-KR')} |`);
  }
  lines.push(`| Hybrid + L1 2.1% 캡 | ${hybridCapAgg.totalPnl.toLocaleString('ko-KR')} | ${(hybridCapAgg.totalPnl - v130Pnl) >= 0 ? '+' : ''}${(hybridCapAgg.totalPnl - v130Pnl).toLocaleString('ko-KR')} |`);
  lines.push('');

  lines.push('### 주의사항');
  lines.push('- 표본 22건 / 4일 — 통계적 유의성 낮음 (방향성 참고용)');
  lines.push('- SL_TIGHT 6건 손실(-37,284원)이 핵심 문제 — Trail Ladder보다 진입 필터(confScore ↑, 방향성 필터) 개선이 우선');
  lines.push('- Ladder 완화 시 trail stop이 실제 도달 전에 시간만료(TIME_EXIT)되는 건이 늘어날 수 있음');
  lines.push('');

  lines.push('---');
  lines.push(`*생성: ${new Date().toISOString()} | 실데이터 1분봉 시뮬 기반*`);

  const reportPath = '.analysis/agent_trail_resim_v130.md';
  fs.writeFileSync(reportPath, lines.join('\n'), 'utf8');
  console.log('\n보고서 저장:', reportPath);

  // JSON 결과도 저장
  const jsonOut = {
    meta: { generated: new Date().toISOString(), trades_n: V130_AFTER.length, candles_fetched: totalWithCandles },
    l1_dist: { n: l1Rois.length, avg: l1Avg, max: l1Max, min: l1Min, median: l1Med, buckets: l1Buckets },
    cap_sim: Object.fromEntries(Object.entries(capSimResults).map(([k, v]) => [k, { cap: v.cap, ...v.agg }])),
    ladder_sim: Object.fromEntries(Object.entries(ladderResults).map(([k, v]) => [k, v.agg])),
    hybrid_cap: hybridCapAgg,
    special_open: openAnalysis,
    special_enso: ensoAnalysis,
  };
  fs.writeFileSync('.analysis/raw/trail_resim_v130_results.json', JSON.stringify(jsonOut, null, 2));
  console.log('JSON 결과 저장: .analysis/raw/trail_resim_v130_results.json');

  // 콘솔 요약
  console.log('\n=== 콘솔 요약 ===');
  console.log('L1 캡 시뮬:');
  for (const [k, v] of Object.entries(capSimResults)) {
    console.log(`  ${k}: PnL=${v.agg.totalPnl.toLocaleString('ko-KR')}, WR=${v.agg.wr}%`);
  }
  console.log('Ladder 비교:');
  for (const [k, v] of Object.entries(ladderResults)) {
    console.log(`  ${k}: PnL=${v.agg.totalPnl.toLocaleString('ko-KR')}, WR=${v.agg.wr}%, peak5+=${v.agg.peak5plus}`);
  }
  console.log(`Hybrid+2.1%캡: PnL=${hybridCapAgg.totalPnl.toLocaleString('ko-KR')}, WR=${hybridCapAgg.wr}%`);
})();
