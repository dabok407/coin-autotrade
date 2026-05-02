/**
 * 5% 도달 시각 분석 스크립트
 * Step 1: 각 날짜별 +5% 종목에 대해 1분봉 API로 첫 도달 시각 추적
 * Step 2: 시간대별 분포 집계
 * Step 3: 코인봇 진입 종목 격차 분석
 * Step 4: BTC 동조 분석
 * Step 5: 결론 도출
 */

const https = require('https');
const fs = require('fs');
const path = require('path');

const BASE_DIR = path.join(__dirname);
const RAW_DIR = path.join(BASE_DIR, 'raw');
if (!fs.existsSync(RAW_DIR)) fs.mkdirSync(RAW_DIR, { recursive: true });

const MARKET_5P = JSON.parse(fs.readFileSync(path.join(BASE_DIR, 'market_5p_check_result.json')));
const TRADES = JSON.parse(fs.readFileSync(path.join(BASE_DIR, 'trades_19d_parsed.json')));

// 제외 코인
const EXCLUDE = new Set(['KRW-BTC', 'KRW-XRP', 'KRW-DOGE', 'KRW-XLM', 'KRW-SHIB', 'KRW-GAS']);

function sleep(ms) {
  return new Promise(r => setTimeout(r, ms));
}

function fetchJson(url) {
  return new Promise((resolve, reject) => {
    const req = https.get(url, {
      headers: {
        'Accept': 'application/json',
        'User-Agent': 'Mozilla/5.0'
      }
    }, (res) => {
      let data = '';
      res.on('data', d => data += d);
      res.on('end', () => {
        try { resolve(JSON.parse(data)); }
        catch(e) { reject(new Error('Parse error: ' + data.slice(0, 200))); }
      });
    });
    req.on('error', reject);
    req.setTimeout(10000, () => { req.destroy(); reject(new Error('Timeout')); });
  });
}

/**
 * KST datetime string "YYYY-MM-DD" -> UTC date start/end
 * 업비트 API: to 파라미터는 UTC ISO 8601
 * KST = UTC+9, 즉 KST 00:00 = UTC 전날 15:00
 */
function kstDateToUtcRange(kstDate) {
  // kstDate: "2026-04-29"
  const [y, m, d] = kstDate.split('-').map(Number);
  // KST 00:00:00 = UTC 전날 15:00:00
  const utcStart = new Date(Date.UTC(y, m - 1, d - 1, 15, 0, 0));
  // KST 23:59:59 = UTC 당일 14:59:59
  const utcEnd = new Date(Date.UTC(y, m - 1, d, 14, 59, 59));
  return { utcStart, utcEnd };
}

function toUpbitDateStr(date) {
  return date.toISOString().replace(/\.\d+Z$/, 'Z');
}

/**
 * 특정 마켓의 특정 KST 날짜 1분봉 모두 가져오기
 * 업비트 1분봉은 최대 200개씩, to파라미터로 페이징
 * 24시간 = 1440분 → 8회 호출 필요
 */
async function fetch1mCandles(market, kstDate, openPrice) {
  const { utcStart, utcEnd } = kstDateToUtcRange(kstDate);
  const candles = [];

  let currentTo = utcEnd;
  const maxIter = 9;
  let iter = 0;

  while (iter < maxIter) {
    iter++;
    const toStr = toUpbitDateStr(currentTo);
    const url = `https://api.upbit.com/v1/candles/minutes/1?market=${market}&to=${toStr}&count=200`;

    let batch;
    try {
      batch = await fetchJson(url);
    } catch(e) {
      console.warn(`  WARN fetch error ${market} ${kstDate} iter=${iter}: ${e.message}`);
      await sleep(500);
      break;
    }

    if (!Array.isArray(batch) || batch.length === 0) break;

    // 업비트 1분봉 내림차순 (최신이 앞)
    for (const c of batch) {
      const candleUtc = new Date(c.candle_date_time_utc);
      if (candleUtc >= utcStart && candleUtc <= utcEnd) {
        candles.push({
          utc: c.candle_date_time_utc,
          kst: c.candle_date_time_kst,
          open: c.opening_price,
          high: c.high_price,
          low: c.low_price,
          close: c.trade_price,
          volume: c.candle_acc_trade_volume
        });
      }
    }

    // 가장 오래된 캔들 시각 확인
    const oldest = new Date(batch[batch.length - 1].candle_date_time_utc);
    if (oldest <= utcStart) break;

    // 다음 페이지: oldest - 1초
    currentTo = new Date(oldest.getTime() - 60000);
    if (currentTo < utcStart) break;

    await sleep(130);
  }

  // 오름차순 정렬 (시간순)
  candles.sort((a, b) => new Date(a.utc) - new Date(b.utc));

  // 5% 첫 도달 시각 찾기
  const threshold = openPrice * 1.05;
  let first5pTime = null;
  let first5pKst = null;
  let peakPrice = openPrice;
  let peakKst = null;

  for (const c of candles) {
    if (c.high > peakPrice) {
      peakPrice = c.high;
      peakKst = c.kst;
    }
    if (first5pTime === null && c.high >= threshold) {
      first5pTime = c.utc;
      first5pKst = c.kst;
    }
  }

  const pctReached = openPrice > 0 ? ((peakPrice - openPrice) / openPrice * 100) : 0;

  return {
    market,
    date: kstDate,
    openPrice,
    threshold5p: threshold,
    candleCount: candles.length,
    first5pKst,
    first5pUtc: first5pTime,
    first5pHourKst: first5pKst ? parseInt(first5pKst.split('T')[1].split(':')[0]) : null,
    peakPrice,
    peakKst,
    peakPct: Math.round(pctReached * 100) / 100
  };
}

async function fetchBtc1mCandles(kstDate) {
  const { utcStart, utcEnd } = kstDateToUtcRange(kstDate);
  const candles = [];
  let currentTo = utcEnd;

  for (let iter = 0; iter < 9; iter++) {
    const toStr = toUpbitDateStr(currentTo);
    const url = `https://api.upbit.com/v1/candles/minutes/1?market=KRW-BTC&to=${toStr}&count=200`;
    let batch;
    try { batch = await fetchJson(url); }
    catch(e) { console.warn('BTC fetch warn:', e.message); break; }
    if (!Array.isArray(batch) || batch.length === 0) break;

    for (const c of batch) {
      const candleUtc = new Date(c.candle_date_time_utc);
      if (candleUtc >= utcStart && candleUtc <= utcEnd) {
        candles.push({ utc: c.candle_date_time_utc, kst: c.candle_date_time_kst, close: c.trade_price });
      }
    }
    const oldest = new Date(batch[batch.length - 1].candle_date_time_utc);
    if (oldest <= utcStart) break;
    currentTo = new Date(oldest.getTime() - 60000);
    if (currentTo < utcStart) break;
    await sleep(130);
  }

  candles.sort((a, b) => new Date(a.utc) - new Date(b.utc));
  return candles;
}

function getBtcTrendAtTime(btcCandles, utcTimeStr, windowMin = 30) {
  // utcTimeStr 기준으로 앞뒤 windowMin 캔들의 추세
  if (!utcTimeStr) return 'UNKNOWN';
  const t = new Date(utcTimeStr).getTime();
  const before = btcCandles.filter(c => new Date(c.utc).getTime() < t && new Date(c.utc).getTime() >= t - windowMin * 60000);
  const after = btcCandles.filter(c => new Date(c.utc).getTime() >= t && new Date(c.utc).getTime() <= t + windowMin * 60000);
  if (before.length === 0 || after.length === 0) return 'UNKNOWN';
  const avgBefore = before.reduce((s, c) => s + c.close, 0) / before.length;
  const avgAfter = after.reduce((s, c) => s + c.close, 0) / after.length;
  const chg = (avgAfter - avgBefore) / avgBefore * 100;
  if (chg > 0.3) return 'UP';
  if (chg < -0.3) return 'DOWN';
  return 'FLAT';
}

async function main() {
  console.log('=== Step 1: 5% 종목 1분봉 분석 시작 ===');

  const dates = ['2026-04-29', '2026-04-30', '2026-05-01', '2026-05-02'];
  const allResults = [];

  // 날짜별 5% 이상 종목 추출
  for (const dt of dates) {
    const items = MARKET_5P.by_date[dt] || [];
    const over5 = items.filter(x => x.intradayUpPct >= 5 && !EXCLUDE.has(x.market));
    console.log(`\n[${dt}] 5%+ 종목 ${over5.length}개 (제외후)`);

    for (const item of over5) {
      process.stdout.write(`  ${item.market} (open=${item.open}, up=${item.intradayUpPct}%)... `);
      try {
        const result = await fetch1mCandles(item.market, dt, item.open);
        result.dailyUpPct = item.intradayUpPct;
        allResults.push(result);
        console.log(`done. first5p=${result.first5pKst || 'MISS'} peak=${result.peakPct}% candles=${result.candleCount}`);
      } catch(e) {
        console.log(`ERROR: ${e.message}`);
        allResults.push({ market: item.market, date: dt, openPrice: item.open, error: e.message, dailyUpPct: item.intradayUpPct });
      }
      await sleep(150);
    }
  }

  // 저장
  fs.writeFileSync(path.join(RAW_DIR, 'intraday_5p_times.json'), JSON.stringify(allResults, null, 2));
  console.log('\nStep 1 완료. 저장: .analysis/raw/intraday_5p_times.json');

  // === Step 2: 시간대별 분포 ===
  console.log('\n=== Step 2: 시간대별 분포 집계 ===');
  const hourBuckets = Array.from({ length: 24 }, (_, i) => ({ hour: i, count: 0, markets: [] }));
  const noData = [];
  const missed5p = []; // intradayUpPct >= 5 but first5pKst == null

  for (const r of allResults) {
    if (r.error) { noData.push(r.market + '/' + r.date); continue; }
    if (r.first5pHourKst !== null) {
      hourBuckets[r.first5pHourKst].count++;
      hourBuckets[r.first5pHourKst].markets.push(`${r.market}(${r.date})`);
    } else {
      missed5p.push(r);
    }
  }

  console.log('시간대별 첫 5% 도달 분포 (KST):');
  const timeZones = [
    { label: '00~09 (심야/이른아침)', start: 0, end: 8 },
    { label: '09~10:30 (MR+OP 진입)', start: 9, end: 10 },
    { label: '10:30~22 (AD 진입)', start: 10, end: 21 },
    { label: '22~24 (심야)', start: 22, end: 23 },
  ];

  for (let h = 0; h < 24; h++) {
    if (hourBuckets[h].count > 0) {
      console.log(`  ${String(h).padStart(2,'0')}:00~${String(h+1).padStart(2,'0')}:00 : ${hourBuckets[h].count}건`);
    }
  }

  // 스캐너 시간대별 집계
  const mrOpCount = hourBuckets.slice(9, 11).reduce((s, b) => s + b.count, 0);
  const adCount = hourBuckets.slice(11, 22).reduce((s, b) => s + b.count, 0);
  const earlyCount = hourBuckets.slice(0, 9).reduce((s, b) => s + b.count, 0);
  const total5p = allResults.filter(r => !r.error && r.first5pHourKst !== null).length;

  console.log(`\n스캐너 시간대 매핑:`);
  console.log(`  09~11 (MR+OP): ${mrOpCount}건 / ${total5p}건 (${Math.round(mrOpCount/total5p*100)}%)`);
  console.log(`  11~22 (AD):    ${adCount}건 / ${total5p}건 (${Math.round(adCount/total5p*100)}%)`);
  console.log(`  00~09 (심야):  ${earlyCount}건 / ${total5p}건 (${Math.round(earlyCount/total5p*100)}%)`);
  if (missed5p.length > 0) {
    console.log(`  5%도달 명단이지만 1분봉에서 미확인: ${missed5p.length}건`);
    for (const m of missed5p) console.log(`    ${m.market} ${m.date} (일봉up=${m.dailyUpPct}%)`);
  }

  // === Step 3: 코인봇 진입 격차 분석 ===
  console.log('\n=== Step 3: 코인봇 V130 진입 격차 분석 ===');
  const v130Trades = TRADES.pairs.filter(p => p.v130_era === 'after');
  console.log(`V130 이후 진입 총 ${v130Trades.length}건`);

  const overlapResults = [];
  for (const trade of v130Trades) {
    const entryDate = trade.entry_ts.split(' ')[0]; // "2026-04-29"
    const entryTime = trade.entry_ts; // "2026-04-29 09:01:07" KST
    const market = trade.market;

    // 이 종목이 같은 날 5% 도달 명단에 있나?
    const matched = allResults.find(r => r.market === market && r.date === entryDate && !r.error);

    // entry_ts를 UTC로 변환 (KST-9h)
    const entryUtc = new Date(entryDate + 'T' + entryTime.split(' ')[1] + '+09:00');
    const first5pUtc = matched && matched.first5pUtc ? new Date(matched.first5pUtc) : null;

    let timing = 'NOT_IN_5P_LIST';
    let gapMin = null;
    let afterRise = null;

    if (matched) {
      if (first5pUtc) {
        gapMin = Math.round((entryUtc - first5pUtc) / 60000);
        timing = gapMin > 0 ? 'AFTER_5P' : 'BEFORE_5P';
        // 진입 후 추가 상승 여부 (peak 대비)
        if (trade.peak_pct !== null && trade.peak_pct !== undefined) {
          afterRise = trade.peak_pct;
        }
      } else {
        timing = 'IN_5P_LIST_BUT_NO_1M_HIT';
      }
    }

    overlapResults.push({
      market,
      date: entryDate,
      entryTs: trade.entry_ts,
      scanner: trade.scanner,
      first5pKst: matched ? matched.first5pKst : null,
      timing,
      gapMin,
      afterRise,
      botPeakPct: trade.peak_pct,
      exitReason: trade.exit_reason,
      roi: trade.roi_segments ? Math.min(...trade.roi_segments) : null,
      in5pList: !!matched,
      daily5pPct: matched ? matched.dailyUpPct : null
    });

    if (matched) {
      console.log(`  ${trade.entry_ts} ${market} (${trade.scanner}): timing=${timing} gap=${gapMin}min first5p=${matched.first5pKst || 'MISS'} botPeak=${trade.peak_pct !== null ? trade.peak_pct.toFixed(1) + '%' : 'null'}`);
    } else {
      console.log(`  ${trade.entry_ts} ${market} (${trade.scanner}): NOT in 5% list for ${entryDate}`);
    }
  }

  const in5pCount = overlapResults.filter(r => r.in5pList).length;
  const after5pCount = overlapResults.filter(r => r.timing === 'AFTER_5P').length;
  const before5pCount = overlapResults.filter(r => r.timing === 'BEFORE_5P').length;

  console.log(`\n요약:`);
  console.log(`  진입 종목 중 일중5% 명단 겹침: ${in5pCount}/${v130Trades.length}건`);
  console.log(`  5% 도달 후 진입(꼬리 잡기): ${after5pCount}건`);
  console.log(`  5% 도달 전 진입(선행): ${before5pCount}건`);
  console.log(`  명단 없음(5% 미달 종목 진입): ${overlapResults.filter(r => !r.in5pList).length}건`);

  // === Step 4: BTC 동조 분석 ===
  console.log('\n=== Step 4: BTC 동조 분석 ===');
  const btcData = {};
  for (const dt of dates) {
    console.log(`  BTC 1분봉 로드: ${dt}`);
    btcData[dt] = await fetchBtc1mCandles(dt);
    console.log(`  -> ${btcData[dt].length}개 캔들`);
    await sleep(200);
  }

  // 각 5% 도달 종목에 대해 BTC 방향
  const btcSyncResults = [];
  for (const r of allResults) {
    if (!r.first5pUtc || !btcData[r.date]) continue;
    const trend = getBtcTrendAtTime(btcData[r.date], r.first5pUtc, 30);
    btcSyncResults.push({ market: r.market, date: r.date, btcTrend: trend, hour: r.first5pHourKst });
  }

  const btcUp = btcSyncResults.filter(r => r.btcTrend === 'UP').length;
  const btcFlat = btcSyncResults.filter(r => r.btcTrend === 'FLAT').length;
  const btcDown = btcSyncResults.filter(r => r.btcTrend === 'DOWN').length;
  const btcUnk = btcSyncResults.filter(r => r.btcTrend === 'UNKNOWN').length;
  const btcTotal = btcSyncResults.length;

  console.log(`BTC 동조 분석 (5% 도달 시점 BTC 추세):`);
  console.log(`  BTC 상승 동조: ${btcUp}건 (${Math.round(btcUp/btcTotal*100)}%)`);
  console.log(`  BTC 횡보/무관: ${btcFlat}건 (${Math.round(btcFlat/btcTotal*100)}%)`);
  console.log(`  BTC 하락 역행: ${btcDown}건 (${Math.round(btcDown/btcTotal*100)}%)`);

  // === 최종 결과 저장 ===
  const finalData = {
    generated: new Date().toISOString(),
    summary: {
      total5pMarkets: allResults.filter(r => !r.error).length,
      withFirst5pTime: allResults.filter(r => !r.error && r.first5pKst).length,
      noError: allResults.filter(r => !r.error).length,
      errorCount: allResults.filter(r => r.error).length,
      hourDistribution: hourBuckets.map(b => ({ hour: b.hour, count: b.count })),
      mrOpWindow: { hours: '09-11', count: mrOpCount, pct: Math.round(mrOpCount/total5p*100) },
      adWindow: { hours: '11-22', count: adCount, pct: Math.round(adCount/total5p*100) },
      earlyWindow: { hours: '00-09', count: earlyCount, pct: Math.round(earlyCount/total5p*100) },
    },
    v130TradeAnalysis: overlapResults,
    btcSync: { total: btcTotal, up: btcUp, flat: btcFlat, down: btcDown, unknown: btcUnk },
    raw: allResults
  };

  fs.writeFileSync(path.join(RAW_DIR, 'intraday_5p_times.json'), JSON.stringify(finalData, null, 2));
  console.log('\n최종 데이터 저장: .analysis/raw/intraday_5p_times.json');

  return finalData;
}

main().then(data => {
  console.log('\n=== 분석 완료 ===');
  // 리포트 생성은 generate_report.js에서
  fs.writeFileSync(path.join(BASE_DIR, '_5p_analysis_done.json'), JSON.stringify({ done: true, ts: new Date().toISOString() }));
}).catch(e => {
  console.error('FATAL:', e);
  process.exit(1);
});
