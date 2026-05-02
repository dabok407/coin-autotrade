// 73건 BUY 진입 시점에 대해 진입 시각 ~ 다음날 09:00 (max 200분) 1분봉 페칭
// 출력: .analysis/raw/entry_candles.json (market, entry_ts → minutes 1m candles 200개)
const fs = require('fs');
const https = require('https');

const PARSED = JSON.parse(fs.readFileSync('.analysis/raw/trades_parsed.json', 'utf8'));
const ENTRIES = PARSED.pairs.map(p => ({ market: p.market, entry_ts: p.entry_ts, entry_price: p.entry_price, scanner: p.scanner }));
console.log(`총 진입 ${ENTRIES.length}건`);

function kstAddMinUtcIso(kstStr, addMin) {
  const [d, t] = kstStr.split(' ');
  const [y, mo, day] = d.split('-').map(Number);
  const [h, m, s] = t.split(':').map(Number);
  const utcMs = Date.UTC(y, mo - 1, day, h - 9, m, s) + addMin * 60 * 1000;
  return new Date(utcMs).toISOString().replace('.000Z', 'Z');
}

function fetchJson(url, retries = 3) {
  return new Promise((resolve, reject) => {
    const attempt = (n) => {
      https.get(url, { headers: { 'User-Agent': 'Mozilla/5.0' } }, res => {
        let data = '';
        res.on('data', c => data += c);
        res.on('end', () => {
          if (res.statusCode === 200) {
            try { resolve(JSON.parse(data)); } catch (e) { reject(e); }
          } else {
            if (n > 1) setTimeout(() => attempt(n - 1), 600);
            else reject(new Error('HTTP ' + res.statusCode + ' ' + data.slice(0, 200)));
          }
        });
      }).on('error', err => {
        if (n > 1) setTimeout(() => attempt(n - 1), 600);
        else reject(err);
      });
    };
    attempt(retries);
  });
}

const sleep = ms => new Promise(r => setTimeout(r, ms));

(async () => {
  const cache = {};
  let i = 0;
  for (const e of ENTRIES) {
    i++;
    const cacheKey = `${e.market}|${e.entry_ts}`;
    if (cache[cacheKey]) continue;
    // Fetch 200 minutes — covers OP 09:05 → 12:00 (175분), AD overnight needs more, but we cap at 200
    const to = kstAddMinUtcIso(e.entry_ts, 200);
    const url = `https://api.upbit.com/v1/candles/minutes/1?market=${encodeURIComponent(e.market)}&to=${encodeURIComponent(to)}&count=200`;
    try {
      const candles = await fetchJson(url);
      // Upbit returns latest first; reverse
      const ordered = candles.reverse();
      // Filter post-entry only
      const entryDt = new Date(e.entry_ts.replace(' ', 'T') + '+09:00').getTime();
      const post = ordered.filter(c => new Date(c.candle_date_time_kst).getTime() >= entryDt);
      cache[cacheKey] = post.map(c => ({
        ts_kst: c.candle_date_time_kst,  // YYYY-MM-DDTHH:MM:SS
        o: c.opening_price,
        h: c.high_price,
        l: c.low_price,
        c: c.trade_price,
        v: c.candle_acc_trade_volume,
      }));
      if (i % 10 === 0) console.log(`[${i}/${ENTRIES.length}] ${e.market} ${e.entry_ts}: ${post.length} candles`);
    } catch (err) {
      console.error(`[ERR] ${e.market} ${e.entry_ts}:`, err.message);
      cache[cacheKey] = [];
    }
    await sleep(120); // ~8 req/sec safe
  }
  fs.writeFileSync('.analysis/raw/entry_candles.json', JSON.stringify(cache));
  const sizes = Object.values(cache).map(v => v.length);
  console.log(`완료. ${Object.keys(cache).length}개 entry, 평균 ${(sizes.reduce((s,n)=>s+n,0)/sizes.length).toFixed(0)} 캔들`);
})();
