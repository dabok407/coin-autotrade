async function getJson(url){
  const res = await fetch(url, {method:'GET'});
  if(!res.ok) throw new Error('HTTP ' + res.status);
  return await res.json();
}

async function postJson(url, body){
  const res = await fetch(url, {
    method:'POST',
    headers: {'Content-Type':'application/json'},
    body: JSON.stringify(body)
  });
  if(!res.ok) throw new Error('HTTP ' + res.status);
  return await res.json();
}


function fmtNum(n){
  if(n === null || n === undefined) return '-';
  const x = Number(n);
  if(Number.isNaN(x)) return '-';
  return x.toLocaleString('ko-KR', {maximumFractionDigits: 0});
}

async function postJson(url, body){
  const res = await fetch(url, {
    method:'POST',
    headers: {'Content-Type':'application/json'},
    body: JSON.stringify(body)
  });
  if(!res.ok) throw new Error('HTTP ' + res.status);
  return await res.json();
}


function fmtNum2(n){
  if(n === null || n === undefined) return '-';
  const x = Number(n);
  if(Number.isNaN(x)) return '-';
  return x.toLocaleString('ko-KR', {maximumFractionDigits: 2});
}

function fmtTime(ms){
  if(!ms) return '-';
  const d = new Date(ms);
  return d.toLocaleString('ko-KR');
}

function setRunningUi(running){
  const badge = document.getElementById('statusBadge');
  const btnStart = document.getElementById('btnStart');
  const btnStop = 

document.getElementById('btnApply').addEventListener('click', async () => {
  const mode = document.getElementById('cfgMode').value;
  const candleUnitMin = Number(document.getElementById('cfgUnit').value);
  const capitalKrw = Number(document.getElementById('cfgCapital').value);
  const strategyType = document.getElementById('cfgStrategy').value;

  await postJson('/api/bot/config', { mode, candleUnitMin, capitalKrw, strategyType });
  await refresh();
});

document.getElementById('btnStop');

  if(running){
    badge.textContent = 'RUNNING';
    badge.classList.remove('badge-off');
    badge.classList.add('badge-on');
    btnStart.disabled = true;
    btnStop.disabled = false;
  }else{
    badge.textContent = 'STOPPED';
    badge.classList.remove('badge-on');
    badge.classList.add('badge-off');
    btnStart.disabled = false;
    btnStop.disabled = true;
  }
}

function renderMarkets(markets){
  const tbody = document.querySelector('#marketTable tbody');
  tbody.innerHTML = '';
  if(!markets) return;
  for(const k of Object.keys(markets)){
    const m = markets[k];
    const tr = document.createElement('tr');
    const openPill = m.positionOpen ? '<span class="pill pill-good">OPEN</span>' : '<span class="pill pill-bad">NONE</span>';
    tr.innerHTML = `
      <td>${m.market}</td>
      <td>${fmtNum2(m.lastPrice)}</td>
      <td>${openPill}</td>
      <td>${fmtNum2(m.avgPrice)}</td>
      <td>${fmtNum2(m.qty)}</td>
      <td>${m.downStreak ?? '-'}</td>
      <td>${m.addBuys ?? '-'}</td>
    `;
    tbody.appendChild(tr);
  }
}

function renderTrades(trades){
  const tbody = document.querySelector('#tradeTable tbody');
  tbody.innerHTML = '';
  if(!trades) return;
  const max = Math.min(trades.length, 200);
  for(let i=0;i<max;i++){
    const t = trades[i];
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td>${fmtTime(t.epochMillis)}</td>
      <td>${t.market}</td>
      <td>${t.action}</td>
      <td>${t.patternType ?? ''}</td>
      <td>${fmtNum2(t.price)}</td>
      <td>${fmtNum2(t.qty)}</td>
      <td>${fmtNum(t.pnlKrw)}</td>
      <td>${t.note ?? ''}</td>
    `;
    tbody.appendChild(tr);
  }
}

async function refresh(){
  try{
    const status = await getJson('/api/bot/status');
    setRunningUi(status.running);
    document.getElementById('startedAt').textContent = status.startedAtEpochMillis ? ('Started: ' + fmtTime(status.startedAtEpochMillis)) : '-';
    document.getElementById('realizedPnl').textContent = fmtNum(status.realizedPnlKrw);
    document.getElementById('unrealizedPnl').textContent = fmtNum(status.unrealizedPnlKrw);
    document.getElementById('totalPnl').textContent = fmtNum(status.totalPnlKrw);
    document.getElementById('trades').textContent = status.totalTrades ?? '-';
    document.getElementById('wins').textContent = status.wins ?? '-';
    document.getElementById('winRate').textContent = (status.winRate ?? 0).toFixed(2);
    document.getElementById('strategyType').textContent = status.strategyType ?? '-' ;
    renderMarkets(status.markets);

    // 설정 UI 동기화 (status -> form)
    const modeEl = document.getElementById('cfgMode');
    const unitEl = document.getElementById('cfgUnit');
    const capEl  = document.getElementById('cfgCapital');
    const stEl   = document.getElementById('cfgStrategy');

    if(modeEl && status.mode) modeEl.value = status.mode;
    if(unitEl && status.candleUnitMin !== undefined) unitEl.value = String(status.candleUnitMin);
    if(capEl && status.capitalKrw !== undefined) capEl.value = String(Math.floor(status.capitalKrw));
    if(stEl && status.strategyType) stEl.value = status.strategyType;

const trades = await getJson('/api/bot/trades');
    renderTrades(trades);
  }catch(e){
    console.error(e);
  }
}

document.getElementById('btnStart').addEventListener('click', async () => {
  await getJson('/api/bot/start');
  await refresh();
});



document.getElementById('btnApply').addEventListener('click', async () => {
  const mode = document.getElementById('cfgMode').value;
  const candleUnitMin = Number(document.getElementById('cfgUnit').value);
  const capitalKrw = Number(document.getElementById('cfgCapital').value);
  const strategyType = document.getElementById('cfgStrategy').value;

  await postJson('/api/bot/config', { mode, candleUnitMin, capitalKrw, strategyType });
  await refresh();
});

document.getElementById('btnStop').addEventListener('click', async () => {
  await getJson('/api/bot/stop');
  await refresh();
});

refresh();
setInterval(refresh, 3000);
