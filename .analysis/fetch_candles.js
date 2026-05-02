'use strict';
const fs = require('fs');
const path = require('path');
const https = require('https');

const ANALYSIS_DIR = __dirname;

// ============================================================
// Step 2: Fetch Upbit candle data for each blocked signal
// and HC_BREAK actual entries
// ============================================================

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function kstToUtcIso(kstStr) {
  // "2026-04-11 10:45:05" KST => UTC ISO
  const [date, time] = kstStr.split(' ');
  const [y, mo, d] = date.split('-').map(Number);
  const [h, m, s] = time.split(':').map(Number);
  // KST = UTC+9, so UTC = KST - 9h
  const utcDate = new Date(Date.UTC(y, mo - 1, d, h - 9, m, s));
  return utcDate.toISOString().replace('.000Z', 'Z');
}

function addMinutes(kstStr, mins) {
  const [date, time] = kstStr.split(' ');
  const [y, mo, d] = date.split('-').map(Number);
  const [h, m, s] = time.split(':').map(Number);
  const ms = Date.UTC(y, mo - 1, d, h, m, s) + mins * 60 * 1000;
  const dt = new Date(ms);
  // Return as KST
  const kst = new Date(ms + 9 * 3600 * 1000);
  return kst.toISOString().replace('T', ' ').replace('.000Z', '');
}

function utcIsoFromKstPlusMins(kstStr, addMins) {
  const [date, time] = kstStr.split(' ');
  const [y, mo, d] = date.split('-').map(Number);
  const [h, m, s] = time.split(':').map(Number);
  const utcMs = Date.UTC(y, mo - 1, d, h - 9, m, s) + addMins * 60 * 1000;
  return new Date(utcMs).toISOString().replace('.000Z', 'Z');
}

function fetchJson(url, retries = 3) {
  return new Promise((resolve, reject) => {
    const attempt = (n) => {
      const req = https.get(url, {
        headers: { 'User-Agent': 'Mozilla/5.0', 'Accept': 'application/json' }
      }, (res) => {
        let data = '';
        res.on('data', chunk => data += chunk);
        res.on('end', () => {
          if (res.statusCode === 200) {
            try { resolve(JSON.parse(data)); }
            catch (e) { reject(new Error('JSON parse error: ' + e.message)); }
          } else {
            if (n > 1) {
              setTimeout(() => attempt(n - 1), 500);
            } else {
              reject(new Error(`HTTP ${res.statusCode}: ${data.substring(0, 200)}`));
            }
          }
        });
      });
      req.on('error', (e) => {
        if (n > 1) setTimeout(() => attempt(n - 1), 500);
        else reject(e);
      });
      req.setTimeout(10000, () => { req.destroy(); });
    };
    attempt(retries);
  });
}

async function fetchCandles(market, toKst, count, label) {
  // to= is the end time (inclusive last candle), Upbit returns count candles up to `to`
  // Add 10 minutes to `to` to ensure we get the candle at the signal time
  const toUtc = utcIsoFromKstPlusMins(toKst, 10);
  const url = `https://api.upbit.com/v1/candles/minutes/5?market=${market}&to=${encodeURIComponent(toUtc)}&count=${count}`;

  try {
    const candles = await fetchJson(url);
    // API returns newest first, reverse to get chronological order
    return candles.reverse();
  } catch (e) {
    console.error(`  ERROR fetching ${market} at ${toKst} (${label}): ${e.message}`);
    return null;
  }
}

function utcToKstStr(utcIso) {
  const d = new Date(utcIso + (utcIso.endsWith('Z') ? '' : 'Z'));
  const kst = new Date(d.getTime() + 9 * 3600 * 1000);
  return kst.toISOString().replace('T', ' ').replace('.000Z', '');
}

async function processSignals(signals, label) {
  const results = [];
  let fetched = 0;
  let failed = 0;

  for (let i = 0; i < signals.length; i++) {
    const sig = { ...signals[i] };
    console.log(`[${label}] ${i + 1}/${signals.length} ${sig.market} @ ${sig.timestamp_kst} score=${sig.score}`);

    // Fetch 30 candles ending at signal_time + 120min (to get ~24 candles after entry + some before)
    // We need: entry candle + 24 candles after = 25 candles total
    // Add buffer: fetch 30 candles ending at signal_time + 130min
    const toKst = sig.timestamp_kst; // The signal is AT this time, so to=signal+130min

    // Fetch 30 candles ending ~130min after signal (gives us entry + 24 future candles)
    const toUtcFuture = utcIsoFromKstPlusMins(sig.timestamp_kst, 130);
    const url = `https://api.upbit.com/v1/candles/minutes/5?market=${sig.market}&to=${encodeURIComponent(toUtcFuture)}&count=30`;

    let candles = null;
    try {
      const raw = await fetchJson(url);
      candles = raw.reverse(); // chronological
    } catch (e) {
      console.error(`  FAILED: ${e.message}`);
      failed++;
      sig.candles_after = null;
      sig.fetch_error = e.message;
      results.push(sig);
      await sleep(200);
      continue;
    }

    // Find the entry candle: the candle whose open time <= signal time <= close time
    // 5min candle: candle_date_time_utc is the open time
    const sigEpoch = new Date(utcIsoFromKstPlusMins(sig.timestamp_kst, 0).replace('Z','+00:00')).getTime();

    let entryIdx = -1;
    for (let j = 0; j < candles.length; j++) {
      const c = candles[j];
      const openEpoch = new Date(c.candle_date_time_utc + 'Z').getTime();
      const closeEpoch = openEpoch + 5 * 60 * 1000;
      if (openEpoch <= sigEpoch && sigEpoch < closeEpoch) {
        entryIdx = j;
        break;
      }
      // If signal is between candles, take the next candle
      if (j > 0) {
        const prevOpen = new Date(candles[j-1].candle_date_time_utc + 'Z').getTime();
        const prevClose = prevOpen + 5 * 60 * 1000;
        if (sigEpoch >= prevClose && sigEpoch < openEpoch) {
          entryIdx = j;
          break;
        }
      }
    }

    if (entryIdx === -1) {
      // fallback: find closest candle after signal
      for (let j = 0; j < candles.length; j++) {
        const openEpoch = new Date(candles[j].candle_date_time_utc + 'Z').getTime();
        if (openEpoch >= sigEpoch) {
          entryIdx = j;
          break;
        }
      }
    }

    if (entryIdx === -1) {
      console.error(`  Could not find entry candle for ${sig.market} @ ${sig.timestamp_kst}`);
      failed++;
      sig.fetch_error = 'entry candle not found';
      sig.candles_after = null;
      results.push(sig);
      await sleep(150);
      continue;
    }

    const entryCandle = candles[entryIdx];
    sig.entry_price_estimate = entryCandle.trade_price; // close of entry candle
    sig.entry_candle_kst = utcToKstStr(entryCandle.candle_date_time_utc);

    // Get next 24 candles (after entry)
    const after = candles.slice(entryIdx + 1, entryIdx + 25);
    sig.candles_after = after.map(c => ({
      time_kst: utcToKstStr(c.candle_date_time_utc),
      open: c.opening_price,
      high: c.high_price,
      low: c.low_price,
      close: c.trade_price,
      volume: c.candle_acc_trade_volume,
    }));

    console.log(`  entry candle: ${sig.entry_candle_kst} close=${sig.entry_price_estimate}, after=${sig.candles_after.length} candles`);

    fetched++;
    results.push(sig);
    await sleep(150); // Rate limit
  }

  console.log(`\n[${label}] Done: ${fetched} fetched, ${failed} failed`);
  return results;
}

async function main() {
  const blockedSignals = JSON.parse(fs.readFileSync(path.join(ANALYSIS_DIR, 'blocked_signals.json'), 'utf8'));
  const hcBreakEntries = JSON.parse(fs.readFileSync(path.join(ANALYSIS_DIR, 'hc_break_entries.json'), 'utf8'));

  console.log(`Processing ${blockedSignals.length} blocked signals...`);
  const blockedWithCandles = await processSignals(blockedSignals, 'BLOCKED');

  console.log(`\nProcessing ${hcBreakEntries.length} HC_BREAK actual entries...`);
  const hcWithCandles = await processSignals(hcBreakEntries, 'HC_BREAK');

  // Save price_data.json (combined)
  const priceData = {
    blocked: blockedWithCandles,
    hc_break: hcWithCandles,
  };
  fs.writeFileSync(path.join(ANALYSIS_DIR, 'price_data.json'), JSON.stringify(priceData, null, 2));
  console.log(`\nSaved: price_data.json`);

  // Summary
  const blockedOk = blockedWithCandles.filter(s => s.candles_after && s.candles_after.length > 0).length;
  const hcOk = hcWithCandles.filter(s => s.candles_after && s.candles_after.length > 0).length;
  console.log(`Price data coverage: blocked=${blockedOk}/${blockedSignals.length}, hc_break=${hcOk}/${hcBreakEntries.length}`);
}

main().catch(e => { console.error(e); process.exit(1); });
