// SL_WIDE/TIGHT 25건의 진입 후 1분봉 30개를 Upbit API로 가져와서
// "진입 시점 + 첫 1~5분 가격 흐름"을 분석 (꼬리 매수 검증)
const fs = require('fs');
const https = require('https');

// 8일치 SL 발동 케이스 (entry_ts/market/entry_price)
const SL_CASES = [
  // SL_WIDE 20건
  { mkt:'KRW-ZAMA',     ts:'2026-04-20 09:00:07', entry:41.9,  exit:40.6 },
  { mkt:'KRW-ONT',      ts:'2026-04-20 09:05:35', entry:117.0, exit:113.0 },
  { mkt:'KRW-ORDER',    ts:'2026-04-20 09:29:55', entry:79.74, exit:77.43 },
  { mkt:'KRW-CFG',      ts:'2026-04-21 09:00:32', entry:395.0, exit:384.0 },
  { mkt:'KRW-CPOOL',    ts:'2026-04-22 09:04:13', entry:41.7,  exit:40.5 },
  { mkt:'KRW-ZAMA',     ts:'2026-04-23 09:00:20', entry:44.04, exit:42.8 },
  { mkt:'KRW-AXL',      ts:'2026-04-24 09:00:39', entry:90.5,  exit:86.4 },
  { mkt:'KRW-CPOOL',    ts:'2026-04-24 09:05:05', entry:44.30, exit:43.0 },
  { mkt:'KRW-MET2',     ts:'2026-04-24 09:06:06', entry:266.0, exit:258.0 },
  { mkt:'KRW-ZAMA',     ts:'2026-04-25 09:00:08', entry:51.7,  exit:50.2 },
  { mkt:'KRW-RED',      ts:'2026-04-25 09:05:08', entry:214.0, exit:209.0 },
  { mkt:'KRW-ZBT',      ts:'2026-04-25 09:12:53', entry:222.0, exit:214.0 },
  { mkt:'KRW-ZKP',      ts:'2026-04-25 09:34:23', entry:122.0, exit:118.0 },
  { mkt:'KRW-HYPER',    ts:'2026-04-26 09:01:44', entry:247.0, exit:236.0 },
  { mkt:'KRW-SAFE',     ts:'2026-04-26 09:43:47', entry:241.0, exit:231.0 },
  { mkt:'KRW-ORCA',     ts:'2026-04-26 09:49:03', entry:2036.0, exit:1965.8 },
  { mkt:'KRW-ZBT',      ts:'2026-04-26 09:55:24', entry:259.0, exit:259.0 }, // SL after SPLIT_1ST
  { mkt:'KRW-ORCA',     ts:'2026-04-27 09:01:09', entry:2068.0, exit:1990.0 },
  { mkt:'KRW-FLUID',    ts:'2026-04-27 09:15:54', entry:6750.0, exit:6561.0 },
  { mkt:'KRW-SPK',      ts:'2026-04-27 09:34:26', entry:158.0, exit:153.4 },
  // SL_TIGHT 5건
  { mkt:'KRW-PIEVERSE', ts:'2026-04-21 09:27:11', entry:1503.0, exit:1466.0 },
  { mkt:'KRW-CPOOL',    ts:'2026-04-23 09:16:53', entry:44.80, exit:43.5 },
  { mkt:'KRW-PLUME',    ts:'2026-04-24 09:31:18', entry:21.10, exit:20.5 },
  { mkt:'KRW-MET2',     ts:'2026-04-24 09:43:23', entry:269.0, exit:262.0 },
  { mkt:'KRW-MIRA',     ts:'2026-04-26 09:43:13', entry:null, exit:null }, // entry unknown
];

function kstToUtcParam(kstStr) {
  const [d, t] = kstStr.split(' ');
  const [y, mo, day] = d.split('-').map(Number);
  const [h, m, s] = t.split(':').map(Number);
  const utc = new Date(Date.UTC(y, mo-1, day, h-9, m, s));
  // Upbit candles?to=&count= : to는 UTC ISO. 진입 시각으로부터 30분 후를 to로 (count=30 = 진입~30분)
  const utcAfter = new Date(utc.getTime() + 30 * 60 * 1000);
  return utcAfter.toISOString().replace('.000Z', 'Z');
}

function fetchJson(url) {
  return new Promise((resolve, reject) => {
    https.get(url, { headers: { 'User-Agent': 'Mozilla/5.0' } }, res => {
      let data = '';
      res.on('data', c => data += c);
      res.on('end', () => {
        if (res.statusCode === 200) {
          try { resolve(JSON.parse(data)); } catch (e) { reject(e); }
        } else { reject(new Error('HTTP ' + res.statusCode + ' ' + data.slice(0, 200))); }
      });
    }).on('error', reject);
  });
}

const sleep = ms => new Promise(r => setTimeout(r, ms));

(async () => {
  const out = [];
  for (const c of SL_CASES) {
    if (!c.entry) { out.push({...c, candles: null, note: 'skip - no entry price'}); continue; }
    const to = kstToUtcParam(c.ts);
    const url = `https://api.upbit.com/v1/candles/minutes/1?market=${encodeURIComponent(c.mkt)}&to=${encodeURIComponent(to)}&count=30`;
    try {
      const candles = await fetchJson(url);
      // Upbit candles return latest first; reverse to chronological
      const ordered = candles.reverse();
      // Track post-entry: find candle whose KST timestamp >= ts
      const entryDt = new Date(c.ts.replace(' ', 'T') + '+09:00');
      const post = ordered.filter(k => new Date(k.candle_date_time_kst) >= entryDt);
      const minLow = Math.min(...post.map(k => k.low_price));
      const maxHigh = Math.max(...post.map(k => k.high_price));
      const minLowPct = (minLow - c.entry) / c.entry * 100;
      const maxHighPct = (maxHigh - c.entry) / c.entry * 100;
      // 첫 5분 동안의 trough%
      const first5 = post.slice(0, 5);
      const f5MinLow = first5.length ? Math.min(...first5.map(k => k.low_price)) : null;
      const f5MinPct = f5MinLow !== null ? (f5MinLow - c.entry) / c.entry * 100 : null;
      // 진입 시점 (1분 이내) 캔들의 close 대비 high 비율 (꼬리 윗꼬리)
      const entryC = post[0];
      const entryWick = entryC ? (entryC.high_price - entryC.close_price) / entryC.high_price * 100 : null;
      out.push({
        mkt: c.mkt, ts: c.ts, entry: c.entry,
        max_high_pct: +maxHighPct.toFixed(2),
        min_low_pct: +minLowPct.toFixed(2),
        first5_trough_pct: f5MinPct !== null ? +f5MinPct.toFixed(2) : null,
        entry_candle_upper_wick_pct: entryWick !== null ? +entryWick.toFixed(2) : null,
        entry_candle_close: entryC ? entryC.close_price : null,
        entry_vs_close_pct: entryC ? +((c.entry - entryC.close_price) / entryC.close_price * 100).toFixed(2) : null,
      });
    } catch (e) {
      out.push({ mkt: c.mkt, ts: c.ts, error: String(e.message) });
    }
    await sleep(150); // rate limit safety
  }
  fs.writeFileSync('.analysis/raw/sl_candle_analysis.json', JSON.stringify(out, null, 2));
  console.log('Saved:', '.analysis/raw/sl_candle_analysis.json');
  // Summary
  const valid = out.filter(o => o.first5_trough_pct !== null && o.first5_trough_pct !== undefined);
  console.log(`Valid cases: ${valid.length}`);
  const avg5min = valid.reduce((s,o) => s + o.first5_trough_pct, 0) / valid.length;
  console.log(`평균 진입 후 5분 trough%: ${avg5min.toFixed(2)}%`);
  const wickGtZero = valid.filter(o => o.entry_vs_close_pct > 0);
  console.log(`진입가 > 진입봉 close (꼬리 매수 의심): ${wickGtZero.length}/${valid.length}`);
  // Top 5
  console.log('\nWorst 5 by first-5min trough:');
  valid.sort((a,b) => a.first5_trough_pct - b.first5_trough_pct).slice(0, 5).forEach(o => {
    console.log(`  ${o.mkt} ${o.ts}: trough5m=${o.first5_trough_pct}% high=${o.max_high_pct}% entry_vs_close=${o.entry_vs_close_pct}%`);
  });
})();
