// 거래 로그 파서 (Node) — trades_8d.log → trades_parsed.json + trades_summary.md
const fs = require('fs');
const path = require('path');

const LOG = path.join('.analysis', 'raw', 'trades_clean.log');
const OUT_JSON = path.join('.analysis', 'raw', 'trades_parsed.json');
const OUT_MD = path.join('.analysis', 'raw', 'trades_summary.md');

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

// Match all close-lines that carry pnl/roi: SPLIT_1ST/SPLIT_2ND_*/SELL/TP_TRAIL/SL/REALTIME TP/HC_TIME_STOP
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
      // Skip diagnostic lines (we want concrete fill rows only)
      if (line.includes('trail armed:') || line.includes('triggered:') || line.includes('detected |')) continue;
      const rawKind = m[3];
      // Normalize kind: SPLIT_1ST stays, SPLIT_2ND_*/SPLIT_2ND -> SPLIT_2ND, others -> SELL
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
      // Infer reason if missing
      if (!e.reason) {
        if (rawKind === 'TP_TRAIL') e.reason = 'TP_TRAIL';
        else if (rawKind === 'SL') e.reason = 'SL_WIDE';
        else if (rawKind === 'REALTIME TP') e.reason = 'SL';  // confusingly logged label
        else if (rawKind === 'HC_TIME_STOP') e.reason = 'HC_TIME_STOP';
        else if (rawKind === 'HC_SESSION_END') e.reason = 'HC_SESSION_END';
      }
      exits.push(e);
    }
  }
  return { buys, exits, armeds };
}

function toPairs(buys, exits, armeds) {
  // Group by market, keep chronological
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
      // exits and armeds that fall within (b.ts, nextBuyTs)
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
      pairs.push({
        market: mkt,
        scanner: b.scanner,
        entry_ts: b.ts,
        entry_price: b.price,
        qty: b.qty,
        conf: b.conf,
        entry_reason: b.reason,
        bo: b.bo, vol: b.vol, rsi: b.rsi, qs: b.qs, gap: b.gap,
        armed_pnl_pct: armed ? armed.armPnl : null,
        armed_peak: armed ? armed.peak : null,
        armed_flag: armed ? 'Y' : 'N',
        split1, split2, sell,
        pnl_total: pnlTotal,
        close_count: closes.length,
        roi_segments: roiSegments,
        outcome_kind: outcomeKind,
        exit_reason: exitReason,
        exit_ts: exitTs,
        best_peak_price: peakPrice,
        peak_pct: peakPct,
        worst_trough_pnl_pct: troughPnlPct,
      });
    }
  }
  pairs.sort((x,y) => x.entry_ts.localeCompare(y.entry_ts));
  return pairs;
}

function summarize(pairs) {
  const closed = pairs.filter(p => p.outcome_kind === 'SPLIT_FULL' || p.outcome_kind === 'SELL_ONLY');
  const open = pairs.filter(p => p.outcome_kind === 'OPEN' || p.outcome_kind === 'SPLIT1_OPEN');
  const wins = closed.filter(p => p.pnl_total > 0);
  const losses = closed.filter(p => p.pnl_total <= 0);
  const totalPnl = closed.reduce((s,p) => s + p.pnl_total, 0);
  const winrate = closed.length ? wins.length / closed.length * 100 : 0;

  const byScanner = {};
  for (const p of closed) {
    const s = p.scanner;
    byScanner[s] ||= { n:0, wins:0, pnl:0, armed:0, sumPeak:0, peakN:0, sumWorst:0, worstN:0 };
    byScanner[s].n++;
    if (p.pnl_total > 0) byScanner[s].wins++;
    byScanner[s].pnl += p.pnl_total;
    if (p.armed_flag === 'Y') byScanner[s].armed++;
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
  }

  return {
    totals: { buys: pairs.length, closed: closed.length, open: open.length, wins: wins.length, losses: losses.length, winrate_pct: +winrate.toFixed(2), pnl_total_krw: totalPnl },
    byScanner, byReason,
    losses: losses.map(p => ({
      market: p.market, scanner: p.scanner, entry_ts: p.entry_ts, entry_reason: p.entry_reason,
      conf: p.conf, vol: p.vol, rsi: p.rsi, qs: p.qs, bo: p.bo,
      armed: p.armed_flag, outcome_kind: p.outcome_kind, exit_reason: p.exit_reason,
      pnl_total: p.pnl_total, roi_segments: p.roi_segments,
      peak_pct: p.peak_pct === null ? null : +p.peak_pct.toFixed(2),
      worst_trough_pnl_pct: p.worst_trough_pnl_pct,
      peak_above_zero: p.peak_pct !== null && p.peak_pct > 0,
      peak_above_one: p.peak_pct !== null && p.peak_pct > 1.0,
      peak_above_oneFive: p.peak_pct !== null && p.peak_pct > 1.5,
    })),
    wins: wins.map(p => {
      const avgRoi = p.roi_segments.length ? p.roi_segments.reduce((a,b) => a+b, 0)/p.roi_segments.length : null;
      return {
        market: p.market, scanner: p.scanner, entry_ts: p.entry_ts, entry_reason: p.entry_reason,
        conf: p.conf, vol: p.vol, rsi: p.rsi, qs: p.qs, bo: p.bo,
        armed: p.armed_flag, outcome_kind: p.outcome_kind, exit_reason: p.exit_reason,
        pnl_total: p.pnl_total, roi_segments: p.roi_segments,
        peak_pct: p.peak_pct === null ? null : +p.peak_pct.toFixed(2),
        avg_roi_pct: avgRoi !== null ? +avgRoi.toFixed(2) : null,
        peak_minus_avg: (p.peak_pct !== null && avgRoi !== null) ? +(p.peak_pct - avgRoi).toFixed(2) : null,
      };
    }),
    open_positions: open.map(p => ({ market: p.market, scanner: p.scanner, entry_ts: p.entry_ts, outcome: p.outcome_kind })),
  };
}

function fmt(n) {
  if (typeof n !== 'number') return n;
  return n.toLocaleString('ko-KR');
}

function md(s) {
  const out = [];
  out.push(`# Trades Summary (8 days, 2026-04-20 ~ 2026-04-27)\n`);
  out.push(`- BUY entries: ${s.totals.buys}`);
  out.push(`- Closed: ${s.totals.closed}, Open: ${s.totals.open}`);
  out.push(`- Wins: ${s.totals.wins}, Losses: ${s.totals.losses}, **Winrate: ${s.totals.winrate_pct}%**`);
  out.push(`- **Total PnL: ${fmt(s.totals.pnl_total_krw)} KRW**\n`);
  out.push(`## By Scanner`);
  out.push(`| Scanner | N | Wins | WR | PnL | Armed% | avgPeak% | avgWorst% |`);
  out.push(`|---|---:|---:|---:|---:|---:|---:|---:|`);
  for (const [k,v] of Object.entries(s.byScanner)) {
    const armedPct = v.n ? (v.armed/v.n*100).toFixed(0) : 0;
    out.push(`| ${k} | ${v.n} | ${v.wins} | ${v.winrate}% | ${fmt(v.pnl)} | ${armedPct}% | ${v.avgPeakPct ?? '?'} | ${v.avgWorstTroughPct ?? '?'} |`);
  }
  out.push(``);
  out.push(`## By Exit Reason`);
  out.push(`| Reason | N | Wins | WR | PnL |`);
  out.push(`|---|---:|---:|---:|---:|`);
  const byR = Object.entries(s.byReason).sort((a,b) => b[1].n - a[1].n);
  for (const [k,v] of byR) {
    out.push(`| ${k} | ${v.n} | ${v.wins} | ${v.winrate}% | ${fmt(v.pnl)} |`);
  }
  out.push(``);
  out.push(`## Losses Detail`);
  out.push(`| ts | mkt | sc | entry_reason | exit_reason | pnl | roi% | peak% | trough% | armed |`);
  out.push(`|---|---|---|---|---|---:|---|---:|---:|---|`);
  for (const p of s.losses) {
    const peak = p.peak_pct === null ? '?' : p.peak_pct.toFixed(2);
    const trough = (p.worst_trough_pnl_pct === null || p.worst_trough_pnl_pct === undefined) ? '?' : p.worst_trough_pnl_pct.toFixed(2);
    const roi = p.roi_segments.length ? p.roi_segments.map(x => x.toFixed(2)).join(',') : '?';
    out.push(`| ${p.entry_ts.slice(5)} | ${p.market} | ${p.scanner} | ${p.entry_reason} | ${p.exit_reason} | ${fmt(p.pnl_total)} | ${roi} | ${peak} | ${trough} | ${p.armed} |`);
  }
  out.push(``);
  out.push(`## Wins Detail`);
  out.push(`| ts | mkt | sc | entry_reason | exit_reason | pnl | avg_roi% | peak% | peak-avg | armed |`);
  out.push(`|---|---|---|---|---|---:|---:|---:|---:|---|`);
  for (const p of s.wins) {
    const peak = p.peak_pct === null ? '?' : p.peak_pct.toFixed(2);
    const avg = p.avg_roi_pct === null ? '?' : p.avg_roi_pct.toFixed(2);
    const diff = p.peak_minus_avg === null ? '?' : p.peak_minus_avg.toFixed(2);
    out.push(`| ${p.entry_ts.slice(5)} | ${p.market} | ${p.scanner} | ${p.entry_reason} | ${p.exit_reason} | ${fmt(p.pnl_total)} | ${avg} | ${peak} | ${diff} | ${p.armed} |`);
  }
  out.push(``);
  out.push(`## Open Positions`);
  for (const p of s.open_positions) out.push(`- ${p.market} (${p.scanner}, ${p.entry_ts}, ${p.outcome})`);
  return out.join('\n');
}

const { buys, exits, armeds } = parse();
const pairs = toPairs(buys, exits, armeds);
const summary = summarize(pairs);
fs.writeFileSync(OUT_JSON, JSON.stringify({ pairs, summary }, null, 2));
fs.writeFileSync(OUT_MD, md(summary));
console.log(`buys=${buys.length} exits=${exits.length} armeds=${armeds.length} pairs=${pairs.length}`);
console.log(`closed=${summary.totals.closed} winrate=${summary.totals.winrate_pct}% totalPnL=${summary.totals.pnl_total_krw}`);
console.log(`Wrote ${OUT_JSON}, ${OUT_MD}`);
