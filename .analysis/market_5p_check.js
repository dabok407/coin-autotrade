// V130 4일 동안 KRW 마켓 중 +5%/+10% 상승한 종목 카운트
// 일봉 기준 (open→high) intraday 최고 상승률, (open→close) 종가 상승률
const https = require('https');
const fs = require('fs');

function fetchJson(url) {
  return new Promise((resolve, reject) => {
    https.get(url, { headers: { 'Accept': 'application/json' } }, res => {
      let data = '';
      res.on('data', c => data += c);
      res.on('end', () => { try { resolve(JSON.parse(data)); } catch (e) { reject(e); } });
    }).on('error', reject);
  });
}

async function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

async function main() {
  const allMarkets = await fetchJson('https://api.upbit.com/v1/market/all?isDetails=false');
  const krw = allMarkets.filter(m => m.market.startsWith('KRW-')).map(m => m.market);
  // 사용자 보유 코인 제외 + BTC 제외
  const exclude = new Set(['KRW-BTC','KRW-XRP','KRW-DOGE','KRW-XLM','KRW-SHIB','KRW-GAS']);
  const targets = krw.filter(m => !exclude.has(m));
  console.log(`KRW markets: ${krw.length}, target (excl. holdings/BTC): ${targets.length}`);

  // 04-26 ~ 05-02 일봉 (count=10이면 충분)
  // KST 기준 일봉
  const dates = ['2026-04-29','2026-04-30','2026-05-01','2026-05-02'];
  const by_date = { '2026-04-29': [], '2026-04-30': [], '2026-05-01': [], '2026-05-02': [] };
  let i = 0;
  for (const m of targets) {
    i++;
    try {
      const url = `https://api.upbit.com/v1/candles/days?market=${m}&count=10`;
      const data = await fetchJson(url);
      for (const c of data) {
        const dt = c.candle_date_time_kst.slice(0,10);
        if (!by_date[dt]) continue;
        const open = c.opening_price;
        const high = c.high_price;
        const close = c.trade_price;
        const intradayUp = (high - open) / open * 100;
        const closeChg = (close - open) / open * 100;
        by_date[dt].push({ market: m, open, high, close, intradayUpPct: +intradayUp.toFixed(2), closeChgPct: +closeChg.toFixed(2) });
      }
    } catch (e) {
      // 무시
    }
    if (i % 30 === 0) {
      await sleep(120); // rate limit
      process.stdout.write(`.${i}.`);
    } else {
      await sleep(70);
    }
  }
  console.log('');

  // 통계 산출
  const summary = {};
  for (const dt of dates) {
    const arr = by_date[dt] || [];
    const intra5 = arr.filter(x => x.intradayUpPct >= 5).sort((a,b) => b.intradayUpPct - a.intradayUpPct);
    const intra10 = arr.filter(x => x.intradayUpPct >= 10).sort((a,b) => b.intradayUpPct - a.intradayUpPct);
    const close5 = arr.filter(x => x.closeChgPct >= 5).sort((a,b) => b.closeChgPct - a.closeChgPct);
    const close10 = arr.filter(x => x.closeChgPct >= 10).sort((a,b) => b.closeChgPct - a.closeChgPct);
    summary[dt] = {
      total: arr.length,
      intraday_5pct: { count: intra5.length, top10: intra5.slice(0,10) },
      intraday_10pct: { count: intra10.length, top10: intra10.slice(0,10) },
      close_5pct: { count: close5.length, top10: close5.slice(0,10) },
      close_10pct: { count: close10.length, top10: close10.slice(0,10) },
    };
  }

  fs.writeFileSync('.analysis/market_5p_check_result.json', JSON.stringify({ by_date, summary }, null, 2));

  // 코인봇 진입 마켓 리스트와 교차 확인
  const trades = JSON.parse(fs.readFileSync('.analysis/trades_19d_parsed.json', 'utf8'));
  const v130entries = (trades.pairs || []).filter(p => p.v130_era === 'after').map(p => ({
    market: p.market, scanner: p.scanner, entry_ts: p.entry_ts, peak_pct: p.peak_pct, exit_reason: p.exit_reason, pnl: p.pnl_total
  }));
  const v130markets = new Set(v130entries.map(p => p.market));

  // 일별 5%+ 종목 중 코인봇 진입 vs 미진입
  console.log('\n=== 일별 5%+ 상승 KRW 마켓 (intraday open→high) ===');
  for (const dt of dates) {
    const s = summary[dt];
    const entered = v130entries.filter(p => p.entry_ts.startsWith(dt));
    const enteredMarkets = entered.map(p => p.market);
    console.log(`\n[${dt}] +5% intraday: ${s.intraday_5pct.count}종목 / +10% intraday: ${s.intraday_10pct.count}종목`);
    console.log(`  코인봇 진입: ${entered.length}건 (${enteredMarkets.join(', ')})`);
    const intra5markets = s.intraday_5pct.top10.map(x => x.market);
    const intersect = intra5markets.filter(m => enteredMarkets.includes(m));
    const missed = intra5markets.filter(m => !enteredMarkets.includes(m));
    console.log(`  Top10 5%+ 중 진입: ${intersect.length}건 [${intersect.join(', ')}]`);
    console.log(`  Top10 5%+ 중 미진입: ${missed.length}건 [${missed.slice(0,5).join(', ')}]`);
    console.log(`  진입한 ${entered.length}건의 실제 peak%: ${entered.map(p => `${p.market}=${p.peak_pct?.toFixed(1) ?? '?'}%`).join(', ')}`);
  }
}

main().catch(e => { console.error(e); process.exit(1); });
