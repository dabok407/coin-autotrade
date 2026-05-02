// 19일 거래 로그 파싱 (V130 전/후 비교용)
const fs = require('fs');
const path = require('path');

const LOG = path.join('.analysis', 'trades_19d.txt');
const OUT_JSON = path.join('.analysis', 'trades_19d_parsed.json');
const OUT_MD = path.join('.analysis', 'trades_19d_summary.md');
const V130_DEPLOY = '2026-04-28 18:00:00'; // V130 배포 시점 (대략 - 04-29 09:00 거래부터 V130)

const re_buy = /(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}).*?\[(MorningRush|OpeningScanner|AllDayScanner|BreakoutDetector)\] BUY (KRW-[A-Z0-9]+) mode=(\w+) price=([0-9.eE+-]+) qty=([0-9.eE+-]+)/;
const re_buy_extra = (line) => {
  const m = {};
  let r = line.match(/conf=([0-9.]+)/); if (r) m.conf = parseFloat(r[1]);
  r = line.match(/reason=(\w+)/); if (r) m.reason = r[1];
  r = line.match(/bo=\+?(-?[0-9.]+)%/); if (r) m.bo = parseFloat(r[1]);
  r = line.match(/vol=([0-9.]+)x/); if (r) m.vol = parseFloat(r[1]);
  r = line.match(/rsi=([0-9]+)/); if (r) m.rsi = parseInt(r[1]);
  r = line.match(/qs=([0-9.]+)/); if (r) m.qs = parseFloat(r[1]);
  r = line.match(/gap=\+?(-?[0-9.]+)%/); if (r) m.gap = parseFloat(r[1]);
  return m;
};

const re_sell_main = /(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}).*?\[(MorningRush|OpeningScanner|AllDayScanner|BreakoutDetector)\] (SPLIT_1ST|SPLIT_2ND_TRAIL|SPLIT_2ND_BEV|SPLIT_2ND|SELL|TP_TRAIL SELL|TP_TRAIL|SL|REALTIME TP|HC_TIME_STOP|HC_SESSION_END) (KRW-[A-Z0-9]+)/;
const re_armed = /(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}).*?SPLIT_1ST trail armed: (KRW-[A-Z0-9]+) pnl=\+?(-?[0-9.]+)% peak=([0-9.eE+-]+)/;

function shortScanner(s) {
  return ({MorningRush:'MR', OpeningScanner:'OP', AllDayScanner:'AD', BreakoutDetector:'OP'})[s] || s;
}

function parse() {
  const buys = [], exits = [], armeds = [];
  const text = fs.readFileSync(LOG, 'utf8');
  const lines = text.split(/\r?\n/);
  for (const line of lines) {
    if (!line) continue;
    if (line.includes('BUY BLOCKED')) continue;
    let m = line.match(re_buy);
    if (m) {
      const extra = re_buy_extra(line);
      buys.push({ ts: m[1], scanner: shortScanner(m[2]), market: m[3], mode: m[4], price: parseFloat(m[5]), qty: parseFloat(m[6]), ...extra });
      continue;
    }
    m = line.match(re_armed);
    if (m) { armeds.push({ ts: m[1], market: m[2], armPnl: parseFloat(m[3]), peak: parseFloat(m[4]) }); continue; }
    if (line.includes('BLOCKED')) continue;
    m = line.match(re_sell_main);
    if (m) {
      if (line.includes('trail armed:') || line.includes('triggered:') || line.includes('detected |')) continue;
      const rawKind = m[3];
      let kind = 'SELL';
      if (rawKind === 'SPLIT_1ST') kind = 'SPLIT_1ST';
      else if (rawKind.startsWith('SPLIT_2ND')) kind = 'SPLIT_2ND';
      const e = { ts: m[1], scanner: shortScanner(m[2]), kind, market: m[4], rawKind };
      let r = line.match(/price=([0-9.eE+-]+)/); if (r) e.price = parseFloat(r[1]);
      r = line.match(/pnl=(-?[0-9]+)/); if (r) e.pnl = parseInt(r[1]);
      r = line.match(/roi=(-?[0-9.]+)%/); if (r) e.roi = parseFloat(r[1]);
      r = line.match(/reason=(\w+)/); if (r) e.reason = r[1];
      r = line.match(/peak=([0-9.eE+-]+)/); if (r) e.peak = parseFloat(r[1]);
      r = line.match(/drop=(-?[0-9.]+)%/); if (r) e.drop = parseFloat(r[1]);
      r = line.match(/trough=([0-9.eE+-]+)/); if (r) e.trough = parseFloat(r[1]);
      r = line.match(/troughPnl=(-?[0-9.]+)%/); if (r) e.troughPnl = parseFloat(r[1]);
      if (!e.reason) {
        if (rawKind === 'TP_TRAIL') e.reason = 'TP_TRAIL';
        else if (rawKind === 'SL') e.reason = 'SL_WIDE';
        else if (rawKind === 'REALTIME TP') e.reason = 'SL';
        else if (rawKind === 'HC_TIME_STOP') e.reason = 'HC_TIME_STOP';
        else if (rawKind === 'HC_SESSION_END') e.reason = 'HC_SESSION_END';
      }
      exits.push(e);
    }
  }
  return { buys, exits, armeds };
}

function toPairs(buys, exits, armeds) {
  const byBuy = {}, byExit = {}, byArmed = {};
  buys.forEach(b => (byBuy[b.market] ||= []).push(b));
  exits.forEach(e => (byExit[e.market] ||= []).push(e));
  armeds.forEach(a => (byArmed[a.market] ||= []).push(a));

  const pairs = [];
  for (const mkt of Object.keys(byBuy)) {
    const bs = byBuy[mkt].sort((x,y) => x.ts.localeCompare(y.ts));
    const es = (byExit[mkt] || []).sort((x,y) => x.ts.localeCompare(y.ts));
    const as = (byArmed[mkt] || []).sort((x,y) => x.ts.localeCompare(y.ts));
    for (let bi = 0; bi < bs.length; bi++) {
      const b = bs[bi];
      const nextBuyTs = bi + 1 < bs.length ? bs[bi+1].ts : '9999-99-99 99:99:99';
      let armed = null;
      for (const a of as) {
        if (a.ts >= b.ts && a.ts < nextBuyTs) { armed = a; break; }
      }
      let split1 = null, split2 = null, sell = null;
      for (const e of es) {
        if (e.ts < b.ts || e.ts >= nextBuyTs) continue;
        if (e.kind === 'SPLIT_1ST' && !split1) { split1 = e; continue; }
        if (e.kind === 'SPLIT_2ND' && !split2) { split2 = e; continue; }
        if (e.kind === 'SELL' && !sell) { sell = e; continue; }
      }
      const closes = [split1, split2, sell].filter(Boolean);
      const pnlTotal = closes.reduce((s, x) => s + (x.pnl || 0), 0);
      const roiSegments = closes.map(x => x.roi).filter(v => v !== undefined);
      let outcomeKind, exitReason, exitTs;
      if (split2) { outcomeKind='SPLIT_FULL'; exitReason=split2.reason||'?'; exitTs=split2.ts; }
      else if (sell) { outcomeKind='SELL_ONLY'; exitReason=sell.reason||'?'; exitTs=sell.ts; }
      else if (split1) { outcomeKind='SPLIT1_OPEN'; exitReason='OPEN_POSITION'; exitTs=split1.ts; }
      else { outcomeKind='OPEN'; exitReason='STILL_OPEN'; exitTs=null; }
      const lastE = split2 || sell || split1;
      const peakPrice = lastE && lastE.peak;
      const troughPnlPct = lastE && lastE.troughPnl;
      const peakPct = (peakPrice && b.price) ? ((peakPrice - b.price) / b.price * 100) : null;
      // hold time minutes
      let holdMin = null;
      if (exitTs) {
        const a = new Date(b.ts.replace(' ','T') + '+09:00');
        const c = new Date(exitTs.replace(' ','T') + '+09:00');
        holdMin = Math.round((c - a) / 60000);
      }
      pairs.push({
        market: mkt, scanner: b.scanner, entry_ts: b.ts, entry_price: b.price, qty: b.qty,
        conf: b.conf, entry_reason: b.reason, bo: b.bo, vol: b.vol, rsi: b.rsi, qs: b.qs, gap: b.gap,
        armed_pnl_pct: armed ? armed.armPnl : null, armed_peak: armed ? armed.peak : null,
        armed_flag: armed ? 'Y' : 'N',
        split1, split2, sell, pnl_total: pnlTotal, close_count: closes.length,
        roi_segments: roiSegments, outcome_kind: outcomeKind, exit_reason: exitReason, exit_ts: exitTs,
        best_peak_price: peakPrice, peak_pct: peakPct, worst_trough_pnl_pct: troughPnlPct,
        hold_min: holdMin,
        v130_era: b.ts >= V130_DEPLOY ? 'after' : 'before',
      });
    }
  }
  pairs.sort((x,y) => x.entry_ts.localeCompare(y.entry_ts));
  return pairs;
}

function summarize(pairs, label) {
  const closed = pairs.filter(p => p.outcome_kind === 'SPLIT_FULL' || p.outcome_kind === 'SELL_ONLY');
  const open = pairs.filter(p => p.outcome_kind === 'OPEN' || p.outcome_kind === 'SPLIT1_OPEN');
  const wins = closed.filter(p => p.pnl_total > 0);
  const losses = closed.filter(p => p.pnl_total <= 0);
  const totalPnl = closed.reduce((s,p) => s + p.pnl_total, 0);
  const winrate = closed.length ? wins.length / closed.length * 100 : 0;
  const avgWin = wins.length ? wins.reduce((s,p) => s + p.pnl_total, 0) / wins.length : 0;
  const avgLoss = losses.length ? losses.reduce((s,p) => s + p.pnl_total, 0) / losses.length : 0;
  const profitFactor = (avgLoss !== 0 && losses.length > 0) ? Math.abs((wins.reduce((s,p) => s + p.pnl_total, 0)) / losses.reduce((s,p) => s + p.pnl_total, 0)) : null;
  const expectancy = closed.length ? totalPnl / closed.length : 0;

  const byScanner = {};
  for (const p of closed) {
    const s = p.scanner;
    byScanner[s] ||= { n:0, wins:0, pnl:0, sumPeak:0, peakN:0, sumWorst:0, worstN:0 };
    byScanner[s].n++;
    if (p.pnl_total > 0) byScanner[s].wins++;
    byScanner[s].pnl += p.pnl_total;
    if (p.peak_pct !== null) { byScanner[s].sumPeak += p.peak_pct; byScanner[s].peakN++; }
    if (p.worst_trough_pnl_pct !== null && p.worst_trough_pnl_pct !== undefined) { byScanner[s].sumWorst += p.worst_trough_pnl_pct; byScanner[s].worstN++; }
  }
  for (const k of Object.keys(byScanner)) {
    const v = byScanner[k];
    v.winrate = v.n ? +(v.wins/v.n*100).toFixed(2) : 0;
    v.avgPeakPct = v.peakN ? +(v.sumPeak/v.peakN).toFixed(2) : null;
    v.avgWorstTroughPct = v.worstN ? +(v.sumWorst/v.worstN).toFixed(2) : null;
  }

  const byReason = {};
  for (const p of closed) {
    const r = p.exit_reason || '?';
    byReason[r] ||= { n:0, wins:0, pnl:0 };
    byReason[r].n++;
    byReason[r].pnl += p.pnl_total;
    if (p.pnl_total > 0) byReason[r].wins++;
  }
  for (const k of Object.keys(byReason)) {
    byReason[k].winrate = byReason[k].n ? +(byReason[k].wins/byReason[k].n*100).toFixed(2) : 0;
    byReason[k].avgPnl = byReason[k].n ? Math.round(byReason[k].pnl / byReason[k].n) : 0;
  }

  // L1 ROI distribution
  const l1rois = pairs.filter(p => p.split1 && p.split1.roi !== undefined).map(p => p.split1.roi);
  const l1Stats = l1rois.length ? {
    n: l1rois.length,
    avg: +(l1rois.reduce((a,b) => a+b, 0)/l1rois.length).toFixed(3),
    max: Math.max(...l1rois),
    min: Math.min(...l1rois),
    median: l1rois.sort((a,b) => a-b)[Math.floor(l1rois.length/2)],
  } : null;
  // Peak distribution buckets
  const peaks = closed.map(p => p.peak_pct).filter(v => v !== null);
  const peakBuckets = { lt1:0, '1-2':0, '2-3':0, '3-5':0, '5+':0 };
  for (const v of peaks) {
    if (v < 1) peakBuckets.lt1++;
    else if (v < 2) peakBuckets['1-2']++;
    else if (v < 3) peakBuckets['2-3']++;
    else if (v < 5) peakBuckets['3-5']++;
    else peakBuckets['5+']++;
  }

  return {
    label,
    totals: { buys: pairs.length, closed: closed.length, open: open.length, wins: wins.length, losses: losses.length,
      winrate_pct: +winrate.toFixed(2), pnl_total_krw: totalPnl,
      avgWin: Math.round(avgWin), avgLoss: Math.round(avgLoss), profitFactor: profitFactor ? +profitFactor.toFixed(2) : null,
      expectancy: Math.round(expectancy) },
    byScanner, byReason, l1Stats, peakBuckets,
    losses_top: [...losses].sort((a,b) => a.pnl_total - b.pnl_total).slice(0,15).map(p => ({
      market: p.market, scanner: p.scanner, entry_ts: p.entry_ts, entry_reason: p.entry_reason,
      pnl_total: p.pnl_total, roi_segments: p.roi_segments, peak_pct: p.peak_pct, worst_trough_pnl_pct: p.worst_trough_pnl_pct,
      exit_reason: p.exit_reason, hold_min: p.hold_min,
    })),
    wins_top: [...wins].sort((a,b) => b.pnl_total - a.pnl_total).slice(0,15).map(p => ({
      market: p.market, scanner: p.scanner, entry_ts: p.entry_ts, entry_reason: p.entry_reason,
      pnl_total: p.pnl_total, roi_segments: p.roi_segments, peak_pct: p.peak_pct,
      exit_reason: p.exit_reason, hold_min: p.hold_min,
    })),
  };
}

function fmt(n) { if (typeof n !== 'number') return n; return n.toLocaleString('ko-KR'); }

function md(allSummary, beforeSummary, afterSummary) {
  const out = [];
  out.push(`# Trades Summary (19일, 2026-04-13 ~ 2026-05-01)\n`);
  for (const s of [allSummary, beforeSummary, afterSummary]) {
    out.push(`## [${s.label}] 전체 통계`);
    out.push(`- BUY: ${s.totals.buys}, Closed: ${s.totals.closed}, Open: ${s.totals.open}`);
    out.push(`- Wins: ${s.totals.wins}, Losses: ${s.totals.losses}, **WR: ${s.totals.winrate_pct}%**`);
    out.push(`- **PnL: ${fmt(s.totals.pnl_total_krw)} KRW**`);
    out.push(`- avgWin: ${fmt(s.totals.avgWin)}, avgLoss: ${fmt(s.totals.avgLoss)}, **PF: ${s.totals.profitFactor}**, Exp/trade: ${fmt(s.totals.expectancy)}`);
    if (s.l1Stats) out.push(`- L1(SPLIT_1ST) ROI: n=${s.l1Stats.n}, avg=${s.l1Stats.avg}%, max=${s.l1Stats.max}%, min=${s.l1Stats.min}%, median=${s.l1Stats.median}%`);
    out.push(`- Peak buckets: <1%=${s.peakBuckets.lt1}, 1-2%=${s.peakBuckets['1-2']}, 2-3%=${s.peakBuckets['2-3']}, 3-5%=${s.peakBuckets['3-5']}, 5%+=${s.peakBuckets['5+']}`);
    out.push(``);
    out.push(`### By Scanner`);
    out.push(`| Sc | N | Wins | WR | PnL | avgPeak% | avgWorst% |`);
    out.push(`|---|---:|---:|---:|---:|---:|---:|`);
    for (const [k,v] of Object.entries(s.byScanner)) {
      out.push(`| ${k} | ${v.n} | ${v.wins} | ${v.winrate}% | ${fmt(v.pnl)} | ${v.avgPeakPct ?? '?'} | ${v.avgWorstTroughPct ?? '?'} |`);
    }
    out.push(``);
    out.push(`### By Exit Reason`);
    out.push(`| Reason | N | Wins | WR | PnL | avgPnl |`);
    out.push(`|---|---:|---:|---:|---:|---:|`);
    const byR = Object.entries(s.byReason).sort((a,b) => b[1].n - a[1].n);
    for (const [k,v] of byR) out.push(`| ${k} | ${v.n} | ${v.wins} | ${v.winrate}% | ${fmt(v.pnl)} | ${fmt(v.avgPnl)} |`);
    out.push(``);
    out.push(`### Losses Top 10`);
    out.push(`| ts | mkt | sc | reason | pnl | roi% | peak% | worstTrough% | hold(m) |`);
    out.push(`|---|---|---|---|---:|---|---:|---:|---:|`);
    for (const p of s.losses_top.slice(0,10)) {
      const peak = p.peak_pct === null || p.peak_pct === undefined ? '?' : p.peak_pct.toFixed(2);
      const trough = (p.worst_trough_pnl_pct === null || p.worst_trough_pnl_pct === undefined) ? '?' : p.worst_trough_pnl_pct.toFixed(2);
      const roi = p.roi_segments.length ? p.roi_segments.map(x => x.toFixed(2)).join(',') : '?';
      out.push(`| ${p.entry_ts.slice(5)} | ${p.market} | ${p.scanner} | ${p.exit_reason} | ${fmt(p.pnl_total)} | ${roi} | ${peak} | ${trough} | ${p.hold_min ?? '?'} |`);
    }
    out.push(``);
    out.push(`### Wins Top 10`);
    out.push(`| ts | mkt | sc | reason | pnl | roi% | peak% | hold(m) |`);
    out.push(`|---|---|---|---|---:|---|---:|---:|`);
    for (const p of s.wins_top.slice(0,10)) {
      const peak = p.peak_pct === null || p.peak_pct === undefined ? '?' : p.peak_pct.toFixed(2);
      const roi = p.roi_segments.length ? p.roi_segments.map(x => x.toFixed(2)).join(',') : '?';
      out.push(`| ${p.entry_ts.slice(5)} | ${p.market} | ${p.scanner} | ${p.exit_reason} | ${fmt(p.pnl_total)} | ${roi} | ${peak} | ${p.hold_min ?? '?'} |`);
    }
    out.push(``);
  }
  return out.join('\n');
}

const { buys, exits, armeds } = parse();
const pairs = toPairs(buys, exits, armeds);
const before = pairs.filter(p => p.v130_era === 'before');
const after = pairs.filter(p => p.v130_era === 'after');
const allS = summarize(pairs, '전체 19일');
const beforeS = summarize(before, 'V130 이전 (~04-28)');
const afterS = summarize(after, 'V130 이후 (04-29~)');
fs.writeFileSync(OUT_JSON, JSON.stringify({ pairs, all: allS, before: beforeS, after: afterS }, null, 2));
fs.writeFileSync(OUT_MD, md(allS, beforeS, afterS));
console.log(`buys=${buys.length} exits=${exits.length} armeds=${armeds.length} pairs=${pairs.length}`);
console.log(`전체: closed=${allS.totals.closed} WR=${allS.totals.winrate_pct}% PnL=${allS.totals.pnl_total_krw} PF=${allS.totals.profitFactor}`);
console.log(`V130 전: closed=${beforeS.totals.closed} WR=${beforeS.totals.winrate_pct}% PnL=${beforeS.totals.pnl_total_krw} PF=${beforeS.totals.profitFactor}`);
console.log(`V130 후: closed=${afterS.totals.closed} WR=${afterS.totals.winrate_pct}% PnL=${afterS.totals.pnl_total_krw} PF=${afterS.totals.profitFactor}`);
console.log(`Wrote ${OUT_JSON}, ${OUT_MD}`);
