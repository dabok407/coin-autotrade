(() => {
  const { API, req, fmt, initMultiSelect, initTheme, showToast } = window.AutoTrade;

  // Dark/Light toggle
  initTheme();

  // Logout
  var logoutBtn = document.getElementById('logoutBtn');
  if (logoutBtn) {
    logoutBtn.addEventListener('click', function() {
      fetch('/api/auth/logout', { method: 'POST' }).then(function() {
        window.location.href = '/login?logout';
      }).catch(function() {
        window.location.href = '/login';
      });
    });
  }

  let page = 1;
  let size = 50;
  let total = null;
  let logs = [];
  let sort = { key: 'tsEpochMs', dir: 'desc' };

  const botStateText = document.getElementById('botStateText');
  const botSwitch = document.getElementById('botSwitch');
  const statusStrategy = document.getElementById('statusStrategy');
  const statusMarkets = document.getElementById('statusMarkets');

  const totalPnl = document.getElementById('totalPnl');
  const roi = document.getElementById('roi');
  const winRate = document.getElementById('winRate');
  const modeSnap = document.getElementById('modeSnap');
  const intervalSnap = document.getElementById('intervalSnap');

  const refreshBtn = document.getElementById('refreshBtn');

  const balanceAvailableKrw = document.getElementById('balanceAvailableKrw');
  const balanceLockedKrw = document.getElementById('balanceLockedKrw');
  const balanceAsOf = document.getElementById('balanceAsOf');
  const balanceRefreshBtn = document.getElementById('balanceRefreshBtn');

  // ── Positions card ──
  const posCountBadge = document.getElementById('posCountBadge');
  const posTbody = document.getElementById('posTbody');

  const guardBadge = document.getElementById('guardBadge');
  const guardSummary = document.getElementById('guardSummary');
  const guardRecent = document.getElementById('guardRecent');
  const guardRefreshBtn = document.getElementById('guardRefreshBtn');
  const guardTbody = document.getElementById('guardTbody');
  const guardTable = document.getElementById('guardTable');
  const guardModal = document.getElementById('guardModal');
  const guardModalKo = document.getElementById('guardModalKo');
  const guardModalDetails = document.getElementById('guardModalDetails');

  // Wire guard modal after DOM bindings (prevents TDZ error)
  wireGuardModal();

  // Generic close for all modals with data-modal-close
  document.addEventListener('click', function(e) {
    var target = e.target;
    if (target && (target.hasAttribute('data-modal-close') || (target.closest && target.closest('[data-modal-close]')))) {
      var modal = target.closest('.modal');
      if (modal) { modal.classList.remove('open'); modal.setAttribute('aria-hidden', 'true'); }
    }
  });
  document.addEventListener('keydown', function(e) {
    if (e.key === 'Escape') {
      var modals = document.querySelectorAll('.modal.open');
      modals.forEach(function(m) { m.classList.remove('open'); m.setAttribute('aria-hidden', 'true'); });
    }
  });


  let balanceInFlight = false;

  const logMarketFilter = document.getElementById('logMarketFilter');
  const logActionFilter = document.getElementById('logActionFilter');

  const logTbody = document.getElementById('logTbody');
  const logPagerInfo = document.getElementById('logPagerInfo');
  const prevPageBtn = document.getElementById('prevPageBtn');
  const nextPageBtn = document.getElementById('nextPageBtn');
  const pageSize = document.getElementById('pageSize');

  let logTypeMs = null;
  let strategyLabel = new Map();
  let intervalLabel = new Map(); // key->label
  let marketLabel = new Map();   // market code -> display label
  let strategyCatalog = []; // [{key, label, role, recommendedInterval, emaFilterMode, recommendedEma}, ...]
  let siEnabled = false;
  let siOverrides = {}; // {STRATEGY_KEY: intervalMinutes, ...} - kept for chart candle unit logic

  function fmtTsEpochMs(ms){
    if(ms == null) return '-';
    const d = new Date(Number(ms));
    if(Number.isNaN(d.getTime())) return '-';
    const pad = (n) => String(n).padStart(2,'0');
    return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
  }

  function parseNum(v){
    if(v == null) return 0;
    const s = String(v).replace(/[,%\s]/g,'').replace(/,/g,'');
    const n = Number(s);
    return Number.isFinite(n) ? n : 0;
  }

  function fmtAsOf(s){
    if(!s) return '-';
    // "2026-02-15T10:11:12+09:00" -> "2026-02-15 10:11:12"
    const t = String(s).replace('T',' ');
    return t.length >= 19 ? t.substring(0,19) : t;
  }

  async function fetchAssetSummary(silent){
    if(balanceInFlight) return;
    balanceInFlight = true;
    if(balanceRefreshBtn) balanceRefreshBtn.disabled = true;

    try{
      const res = await req('/api/dashboard/asset-summary', { method:'GET' });
      if(!res){
        if(!silent) showToast('잔고 조회 응답이 비어있습니다.', 'warn');
        return;
      }

      const configured = !!res.configured;
      if(!configured){
        if(balanceAvailableKrw) balanceAvailableKrw.textContent = '-';
        if(balanceLockedKrw) balanceLockedKrw.textContent = '-';
        if(balanceAsOf) balanceAsOf.textContent = fmtAsOf(res.asOf);
        if(res.message && !silent) console.warn(res.message);
        return;
      }

      const avail = parseNum(res.availableKrw);
      const locked = parseNum(res.lockedKrw);

      if(balanceAvailableKrw) balanceAvailableKrw.textContent = `${fmt(avail)}원`;
      if(balanceLockedKrw) balanceLockedKrw.textContent = `${fmt(locked)}원`;
      if(balanceAsOf) balanceAsOf.textContent = fmtAsOf(res.asOf);

    }catch(e){
      if(!silent){
        showToast(e.message || '잔고 조회 실패', 'error');
      }
      console.error(e);
    }finally{
      balanceInFlight = false;
      if(balanceRefreshBtn) balanceRefreshBtn.disabled = false;
    }
  }


  function fmtTime(epochMs){
    try{
      const d = new Date(epochMs);
      const yyyy = d.getFullYear();
      const mm = String(d.getMonth()+1).padStart(2,'0');
      const dd = String(d.getDate()).padStart(2,'0');
      const hh = String(d.getHours()).padStart(2,'0');
      const mi = String(d.getMinutes()).padStart(2,'0');
      const ss = String(d.getSeconds()).padStart(2,'0');
      return `${yyyy}-${mm}-${dd} ${hh}:${mi}:${ss}`;
    }catch(e){ return String(epochMs||'-'); }
  }

  // Helper: get current candle unit (minutes) from status snapshot
  let currentCandleUnitMin = 5;
  function getCurrentCandleUnit() {
    return currentCandleUnitMin;
  }

  // Store current guard item for chart button
  let currentGuardItem = null;

  function openGuardModal(item){
    if(!guardModal) return;
    currentGuardItem = item;
    guardModal.setAttribute('aria-hidden','false');
    guardModal.classList.add('open');

    if(guardModalKo) guardModalKo.textContent = item && item.reasonKo ? item.reasonKo : '-';
    if(guardModalDetails){
      const det = Object.assign({}, (item && item.details) ? item.details : {});
      det.reasonCode = item ? item.reasonCode : null;
      det.result = item ? item.result : null;
      det.market = item ? item.market : null;
      det.candleUnitMin = item ? item.candleUnitMin : null;
      det.signalAction = item ? item.signalAction : null;
      det.tsEpochMs = item ? item.tsEpochMs : null;
      guardModalDetails.textContent = JSON.stringify(det, null, 2);
    }
  }

  function closeGuardModal(){
    if(!guardModal) return;
    guardModal.setAttribute('aria-hidden','true');
    guardModal.classList.remove('open');
  }

  function wireGuardModal(){
    if(!guardModal) return;
    guardModal.addEventListener('click', (e) => {
      const t = e.target;
      if(t && (t.hasAttribute('data-guard-close') || t.closest && t.closest('[data-guard-close]'))){
        closeGuardModal();
      }
    });
    document.addEventListener('keydown', (e) => {
      if(e.key === 'Escape' && guardModal.classList.contains('open')) closeGuardModal();
    });
    // Guard → chart popup button
    const guardChartBtn = document.getElementById('guardChartBtn');
    if(guardChartBtn){
      guardChartBtn.addEventListener('click', () => {
        if(!currentGuardItem || !currentGuardItem.market) return;
        closeGuardModal();
        const unitVal = currentGuardItem.candleUnitMin || getCurrentCandleUnit();
        if(window.ChartPopup){
          window.ChartPopup.open({
            market: currentGuardItem.market,
            tsEpochMs: currentGuardItem.tsEpochMs || Date.now(),
            action: currentGuardItem.signalAction || '',
            price: currentGuardItem.details ? currentGuardItem.details.closePrice : null,
            qty: null,
            pnlKrw: null,
            patternType: currentGuardItem.reasonCode || '',
            patternLabel: currentGuardItem.reasonKo || currentGuardItem.reasonCode || '',
            candleUnit: unitVal
          });
        }
      });
    }
  }

  // Guard logs sorting
  let guardLogs = [];
  let guardSort = { key: 'tsEpochMs', dir: 'desc' };

  function sortGuardLogs(arr){
    const key = guardSort.key;
    const dir = guardSort.dir;
    const s = (Array.isArray(arr) ? arr.slice() : []);
    s.sort((a,b) => {
      const va = (a && a[key] != null) ? a[key] : 0;
      const vb = (b && b[key] != null) ? b[key] : 0;
      if(va === vb) return 0;
      return (dir === 'asc') ? (va < vb ? -1 : 1) : (va > vb ? -1 : 1);
    });
    return s;
  }

  function renderGuardLogs(items){
    guardLogs = Array.isArray(items) ? items : [];
    const arr = sortGuardLogs(guardLogs);
    if(!guardTbody) return;

    if(arr.length === 0){
      guardTbody.innerHTML = `<tr><td colspan="5" style="color:var(--muted)">No guard logs</td></tr>`;
      if(guardBadge){ guardBadge.className = 'badge'; guardBadge.textContent = 'OK'; }
      if(guardSummary) guardSummary.textContent = '정상';
      if(guardRecent) guardRecent.textContent = '-';
      return;
    }

    // Summary: if any BLOCKED in top 10 -> ENTRY BLOCKED else INFO
    const top = arr.slice(0,10);
    const hasBlocked = top.some(x => x && String(x.result||'').toUpperCase()==='BLOCKED');
    const hasInfo = top.some(x => x && String(x.result||'').toUpperCase()==='INFO');

    if(guardBadge){
      guardBadge.className = 'badge ' + (hasBlocked ? 'stopped' : (hasInfo ? 'warn' : ''));
      guardBadge.textContent = hasBlocked ? 'ENTRY BLOCKED' : (hasInfo ? 'INFO' : 'OK');
    }
    if(guardSummary){
      guardSummary.textContent = hasBlocked
        ? '매수/추가매수 차단 상태(안전장치 발동)'
        : (hasInfo ? '일시 경고/복구 처리 중' : '정상');
    }
    if(guardRecent){
      const first = arr[0];
      const ko = first && first.reasonKo ? first.reasonKo : (first && first.reasonCode ? first.reasonCode : '-');
      guardRecent.textContent = ko;
    }

    guardTbody.innerHTML = top.map((x, idx) => {
      const time = fmtTime(x.tsEpochMs);
      const market = x.market || '-';
      const act = x.signalAction || '-';
      const result = x.result || '-';
      const reason = x.reasonKo || x.reasonCode || '-';
      return `<tr class="clickable" data-gi="${idx}">
        <td>${time}</td>
        <td>${market}</td>
        <td>${act}</td>
        <td>${result}</td>
        <td class="td-truncate" title="${(x.reasonCode||'').replace(/"/g,'&quot;')}">${reason}</td>
      </tr>`;
    }).join('');

    // row click => modal
    Array.from(guardTbody.querySelectorAll('tr[data-gi]')).forEach(tr => {
      tr.addEventListener('click', () => {
        const i = parseInt(tr.getAttribute('data-gi'),10);
        const item = top[i];
        openGuardModal(item);
      });
    });
  }

  let guardInFlight = false;
  async function fetchGuardLogs(silent){
    if(guardInFlight) return;
    guardInFlight = true;
    if(guardRefreshBtn) guardRefreshBtn.disabled = true;
    try{
      const res = await req('/api/dashboard/decision-logs', { method:'GET' });
      renderGuardLogs(res);
    }catch(e){
      console.error(e);
      if(!silent) showToast(e.message || '가드 로그 조회 실패', 'error');
    }finally{
      guardInFlight = false;
      if(guardRefreshBtn) guardRefreshBtn.disabled = false;
    }
  }

  function labelAction(a){
    const x = String(a||'').toUpperCase();
    const map = {
      'BUY': '매수', 'SELL': '매도', 'ADD_BUY': '추가매수',
      'BUY_PENDING': '매수대기', 'BUY_BLOCKED': '매수차단', 'BUY_FAILED': '매수실패',
      'BUY_PARTIAL': '부분매수', 'ADD_BUY_PARTIAL': '부분추가매수',
      'SELL_PENDING': '매도대기', 'TP_SELL': '익절매도', 'SL_SELL': '손절매도',
      'SIGNAL_ONLY': '시그널', 'BUY_SYNC': '매수복구', 'SELL_SYNC': '매도정리',
      'STRATEGY_LOCK': '전략잠금',
      'LOW_CONFIDENCE': '신뢰도미달',
      'TIME_STOP': '시간초과청산'
    };
    return map[x] || a || '-';
  }

  async function initLabelMaps(){
    // Build strategyLabel map from API
    try{
      const list = await req('/api/strategies');
      strategyCatalog = list || [];
      strategyLabel = new Map((list||[]).map(x => [String(x.key), String(x.label)]));
      // 시스템 타입도 strategyLabel에 등록 (테이블 Type 컬럼과 필터 라벨 통일)
      strategyLabel.set('TAKE_PROFIT', '익절(TP)');
      strategyLabel.set('STOP_LOSS', '손절(SL)');
      strategyLabel.set('TIME_STOP', '시간초과');
      strategyLabel.set('STRATEGY_LOCK', '전략잠금');
      strategyLabel.set('LOW_CONFIDENCE', '신뢰도미달');
    }catch(e){
      strategyCatalog = [];
      strategyLabel = new Map();
      strategyLabel.set('TAKE_PROFIT', '익절(TP)');
      strategyLabel.set('STOP_LOSS', '손절(SL)');
      strategyLabel.set('TIME_STOP', '시간초과');
      strategyLabel.set('STRATEGY_LOCK', '전략잠금');
      strategyLabel.set('LOW_CONFIDENCE', '신뢰도미달');
    }

    // Build intervalLabel map from API
    try{
      const list = await req('/api/intervals', { method:'GET' });
      if(Array.isArray(list) && list.length){
        intervalLabel = new Map(list.map(x => [String(x.key), String(x.label)]));
      }
    }catch(e){
      // keep empty
    }

    // Build marketLabel map from API
    try{
      const list = await req('/api/bot/markets', { method:'GET' });
      const configs = Array.isArray(list) ? list : [];
      marketLabel = new Map(configs.map(m => [String(m.market), String(m.displayName || m.market)]));
    }catch(e){
      // keep empty
    }
  }

  function setRunningUI(running){
    botStateText.textContent = running ? 'RUNNING' : 'STOPPED';
    botSwitch.classList.toggle('on', !!running);
    botSwitch.setAttribute('aria-pressed', String(!!running));
  }

  /** Render open positions from BotStatus.markets */
  function renderPositions(marketsMap){
    if(!posTbody) return;
    if(!marketsMap || typeof marketsMap !== 'object'){
      posTbody.innerHTML = '<tr><td colspan="7" style="color:var(--muted)">No data</td></tr>';
      if(posCountBadge) posCountBadge.textContent = '0';
      return;
    }
    var openPositions = Object.values(marketsMap).filter(function(m){ return m && m.positionOpen && m.qty > 0; });
    if(posCountBadge) posCountBadge.textContent = String(openPositions.length);

    if(openPositions.length === 0){
      posTbody.innerHTML = '<tr><td colspan="7" style="color:var(--muted)">보유 포지션 없음</td></tr>';
      return;
    }

    posTbody.innerHTML = openPositions.map(function(p){
      var mkt = p.market || '-';
      var display = marketLabel.get(String(mkt)) || mkt;
      var avgP = p.avgPrice || 0;
      var lastP = p.lastPrice || 0;
      var qty = p.qty || 0;
      var unrealizedKrw = lastP > 0 && avgP > 0 ? (lastP - avgP) * qty : 0;
      var unrealizedPct = avgP > 0 ? ((lastP - avgP) / avgP) * 100 : 0;
      var pnlColor = unrealizedKrw >= 0 ? 'var(--success)' : 'var(--danger)';
      var strat = p.entryStrategy || '-';
      var stratDisplay = strategyLabel.get(String(strat)) || strat;

      return '<tr>' +
        '<td style="font-weight:700" title="' + mkt + '">' + display + '</td>' +
        '<td>' + (qty > 0 ? Number(qty).toFixed(6) : '-') + '</td>' +
        '<td>' + fmt(avgP) + '</td>' +
        '<td>' + (lastP > 0 ? fmt(lastP) : '-') + '</td>' +
        '<td style="color:' + pnlColor + ';font-weight:700">' +
          fmt(Math.round(unrealizedKrw)) +
          ' <span style="font-size:11px">(' + (unrealizedPct >= 0 ? '+' : '') + unrealizedPct.toFixed(2) + '%)</span>' +
        '</td>' +
        '<td style="text-align:center">' + (p.addBuys || 0) + '</td>' +
        '<td style="font-size:12px" title="' + strat + '">' + stratDisplay + '</td>' +
      '</tr>';
    }).join('');
  }

  function applySort(list){
    const { key, dir } = sort;
    const out = [...list];
    out.sort((a,b) => {
      const av = a?.[key], bv = b?.[key];
      if(av == null && bv == null) return 0;
      if(av == null) return 1;
      if(bv == null) return -1;
      if(typeof av === 'number' && typeof bv === 'number'){
        return dir === 'asc' ? av - bv : bv - av;
      }
      return dir === 'asc' ? String(av).localeCompare(String(bv)) : String(bv).localeCompare(String(av));
    });
    return out;
  }

  function renderLogs(){
    const marketQ = (logMarketFilter.value || '').trim().toUpperCase();
    const actionQ = logActionFilter.value;

    let filtered = logs;
    // Market filter: 영문 코드 + 한글 이름 모두 검색
    if(marketQ) filtered = filtered.filter(function(x){
      var code = String(x.market||'').toUpperCase();
      var name = (marketLabel.get(String(x.market||'')) || '').toUpperCase();
      return code.includes(marketQ) || name.includes(marketQ);
    });
    if(actionQ !== 'ALL') filtered = filtered.filter(x => x.action === actionQ);
    // Type 멀티셀렉트 필터
    if(logTypeMs){
      var selectedTypes = new Set(logTypeMs.getSelected());
      if(selectedTypes.size > 0) filtered = filtered.filter(function(x){
        var type = x.patternType || x.orderType || '';
        return selectedTypes.has(type);
      });
    }

    filtered = applySort(filtered);

    if(filtered.length === 0){
      logTbody.innerHTML = '<tr><td colspan="8" style="color:var(--muted)">\ud45c\uc2dc\ud560 \ub85c\uadf8\uac00 \uc5c6\uc2b5\ub2c8\ub2e4.</td></tr>';
      return;
    }

    logTbody.innerHTML = filtered.map((x, idx) => {
      const marketCode = x.market ?? '-';
      const marketText = marketLabel.get(String(marketCode)) || marketCode;
      const actionText = labelAction(x.action);
      const typeKey = x.patternType ?? x.orderType ?? '-';
      const typeText = strategyLabel.get(String(typeKey)) || typeKey;
      const timeText = (x.tsEpochMs != null) ? fmtTsEpochMs(x.tsEpochMs) : (x.ts ?? '-');

      // Confidence score
      const conf = x.confidence;
      const confText = (conf != null && conf > 0) ? Number(conf).toFixed(1) : '-';
      const confColor = (conf != null && conf > 0) ? (conf >= 7 ? 'var(--success)' : conf >= 4 ? '#e0a000' : 'var(--danger)') : 'var(--muted)';

      // 매도건: 매수가 → 매도가 표시
      const isSell = /SELL/i.test(x.action) && !/PENDING|BLOCKED/i.test(x.action);
      const avgBuy = x.avgBuyPrice;
      const hasBuyPrice = isSell && avgBuy != null && avgBuy > 0;
      const priceCell = hasBuyPrice
        ? `<span style="color:var(--muted);font-size:11px">${fmt(avgBuy)}</span> → <span style="font-weight:700">${fmt(x.price)}</span>`
        : (x.price == null ? '-' : fmt(x.price));
      // PnL + ROI%
      const pnlVal = x.pnlKrw;
      const roiVal = x.roiPercent;
      const hasRoi = isSell && roiVal != null && roiVal !== 0;
      const pnlColor = Number(pnlVal||0) >= 0 ? 'var(--success)' : 'var(--danger)';
      const pnlCell = pnlVal == null ? '-' : (
        hasRoi
          ? `<span style="font-weight:700">${fmt(pnlVal)}</span> <span style="color:${pnlColor};font-size:11px;font-weight:700">(${Number(roiVal).toFixed(2)}%)</span>`
          : fmt(pnlVal)
      );

      return `
      <tr class="chart-row" data-logidx="${idx}">
        <td>${timeText}</td>
        <td title="${String(marketCode).replaceAll('"','&quot;')}">${marketText}</td>
        <td>${actionText}</td>
        <td>${typeText}</td>
        <td style="color:${confColor};font-weight:700;text-align:center">${confText}</td>
        <td>${priceCell}</td>
        <td>${x.qty == null ? '-' : x.qty}</td>
        <td>${pnlCell}</td>
      </tr>
    `;
    }).join('');

    // Row click → chart popup
    logTbody.querySelectorAll('tr.chart-row').forEach(tr => {
      tr.addEventListener('click', () => {
        const idx = parseInt(tr.getAttribute('data-logidx'), 10);
        const x = filtered[idx];
        if (!x || !x.market) return;
        const typeKey = x.patternType ?? x.orderType ?? '-';
        const typeLabel = strategyLabel.get(String(typeKey)) || typeKey;
        // 차트 분봉 결정: 거래 기록에 저장된 분봉 > 전략별 인터벌 > 글로벌 인터벌
        var unitVal = x.candleUnitMin || getCurrentCandleUnit();
        // 거래 기록에 분봉이 없는 경우(이전 데이터)에만 전략별 인터벌 추론
        var nonStrategyTypes = ['TAKE_PROFIT','STOP_LOSS','TIME_STOP','STRATEGY_LOCK','LOW_CONFIDENCE','TP_SELL','SL_SELL'];
        if (!x.candleUnitMin && siEnabled && nonStrategyTypes.indexOf(typeKey) < 0) {
          if (siOverrides[typeKey]) { unitVal = siOverrides[typeKey]; }
          else { var cat = strategyCatalog.find(function(c){ return c.key === typeKey; }); if (cat && cat.recommendedInterval) unitVal = cat.recommendedInterval; }
        }
        if (window.ChartPopup) {
          window.ChartPopup.open({
            market: x.market,
            tsEpochMs: x.tsEpochMs || Date.now(),
            action: x.action,
            price: x.price,
            qty: x.qty,
            pnlKrw: x.pnlKrw,
            avgBuyPrice: x.avgBuyPrice || 0,
            patternType: typeKey,
            patternLabel: typeLabel,
            candleUnit: unitVal,
            note: x.note || x.patternReason || '',
            confidence: x.confidence || 0
          });
        }
      });
    });
  }

  async function loadStatus(){
    const s = await req(API.botStatus, { method:'GET' });
    setRunningUI(!!s.running);

    // Groups-aware strategy/market display
    const hasGroups = Array.isArray(s.groups) && s.groups.length > 0;
    if (hasGroups) {
      // Strategy: show per-group summary
      const groupTips = s.groups.map(function(g) {
        var strats = (g.strategies || []).map(function(k) { return strategyLabel.get(String(k)) || String(k); });
        return g.groupName + ': ' + (strats.join(', ') || '-');
      });
      statusStrategy.textContent = s.groups.length + ' groups';
      statusStrategy.setAttribute('title', groupTips.join(' | '));
      statusStrategy.setAttribute('data-tooltip', groupTips.join('\n'));

      // Markets: show per-group summary
      const mktTips = s.groups.map(function(g) {
        var mkts = (g.markets || []).map(function(m) { return marketLabel.get(String(m)) || String(m); });
        return g.groupName + ': ' + (mkts.join(', ') || '-');
      });
      var allMkts = [];
      s.groups.forEach(function(g) { (g.markets || []).forEach(function(m) { allMkts.push(m); }); });
      statusMarkets.textContent = allMkts.length + ' markets';
      statusMarkets.setAttribute('title', mktTips.join(' | '));
      statusMarkets.setAttribute('data-tooltip', mktTips.join('\n'));
    } else {
      const keys = Array.isArray(s.strategies) && s.strategies.length
        ? s.strategies
        : (s.strategyType ? [s.strategyType] : []);
      if(keys.length){
        const labels = keys.map(k => strategyLabel.get(String(k)) || String(k));
        const head = labels[0];
        const rest = labels.length - 1;
        statusStrategy.textContent = rest > 0 ? `${head} 외 ${rest}건` : head;
        statusStrategy.setAttribute('title', labels.join(', '));
        statusStrategy.setAttribute('data-tooltip', labels.join('\n'));
      }else{
        statusStrategy.textContent = '-';
        statusStrategy.removeAttribute('title');
        statusStrategy.removeAttribute('data-tooltip');
      }

      // enabled markets 표시
      const enabled = (s.markets && typeof s.markets === 'object')
        ? Object.values(s.markets).filter(x => x && x.enabled).map(x => x.market)
        : [];
      if(enabled.length){
        const labels = enabled.map(m => marketLabel.get(String(m)) || String(m));
        const head = labels[0];
        const rest = labels.length - 1;
        statusMarkets.textContent = rest > 0 ? `${head} 외 ${rest}건` : head;
        statusMarkets.setAttribute('title', labels.join(', '));
        statusMarkets.setAttribute('data-tooltip', labels.join('\n'));
      }else{
        statusMarkets.textContent = '-';
        statusMarkets.removeAttribute('title');
        statusMarkets.removeAttribute('data-tooltip');
      }
    }
    totalPnl.textContent = fmt(s.totalPnlKrw);
    roi.textContent = (s.roi == null) ? '-' : `${Number(s.roi).toFixed(2)}%`;
    winRate.textContent = (s.winRate == null) ? '-' : `${Number(s.winRate).toFixed(1)}%`;
    modeSnap.textContent = s.mode || '-';

    // Update current candle unit for chart popup fallback
    if(s.candleUnitMin != null) currentCandleUnitMin = Number(s.candleUnitMin);
    const unitMin = (s.candleUnitMin != null ? s.candleUnitMin : null);
    const key = unitMin != null ? ((Number(unitMin) >= 1440) ? '1d' : `${Number(unitMin)}m`) : '';
    const intervalText = intervalLabel.get(String(key)) || (unitMin != null ? `${unitMin}분` : '-');
    intervalSnap.textContent = intervalText;

    // Restore strategy interval overrides for chart candle unit logic
    {
      const csv = s.strategyIntervalsCsv || '';
      siEnabled = csv.length > 0;
      siOverrides = {};
      if (csv) {
        csv.split(',').forEach(function(pair) {
          var kv = pair.trim().split(':');
          if (kv.length === 2) siOverrides[kv[0].trim()] = parseInt(kv[1].trim()) || 60;
        });
      }
    }

    // Render open positions
    renderPositions(s.markets);

    return s;
  }

  async function loadTrades(){
    const qs = new URLSearchParams({ page:String(page), size:String(size) }).toString();
    const t = await req(`${API.botTrades}?${qs}`, { method:'GET' });
    logs = t.items || [];
    total = t.total ?? null;
    logPagerInfo.textContent = `page ${page} · size ${size}${total!=null ? ` · total ${total}` : ''}`;
    nextPageBtn.disabled = (total!=null) ? (page * size >= total) : false;
    prevPageBtn.disabled = (page <= 1);
    renderLogs();
  }

  async function refreshAll(){
    try{
      refreshBtn.disabled = true;
      await loadStatus();
      await loadTrades();
      await fetchAssetSummary(true);
      await fetchGuardLogs(true);
    }catch(e){
      showToast(e.message || 'Refresh 실패', 'error');
      console.error(e);
    }finally{
      refreshBtn.disabled = false;
    }
  }

  botSwitch.addEventListener('click', async () => {
    const next = !botSwitch.classList.contains('on');
    const ok = confirm(next ? '봇을 START 하시겠습니까?' : '봇을 STOP 하시겠습니까?');
    if(!ok) return;

    try{
      botSwitch.disabled = true;
      await req(next ? API.botStart : API.botStop, { method:'POST' });
      setRunningUI(next);
      await loadStatus();
    }catch(e){
      showToast(e.message || '요청 실패', 'error');
      console.error(e);
    }finally{
      botSwitch.disabled = false;
    }
  });

  refreshBtn.addEventListener('click', refreshAll);
  if(balanceRefreshBtn) balanceRefreshBtn.addEventListener('click', () => fetchAssetSummary(false));

  if(guardRefreshBtn){
    guardRefreshBtn.addEventListener('click', async () => {
      await fetchGuardLogs(false);
    });
  }

  logMarketFilter.addEventListener('input', renderLogs);
  logActionFilter.addEventListener('change', renderLogs);

  prevPageBtn.addEventListener('click', async () => { page = Math.max(1, page-1); await loadTrades(); });
  nextPageBtn.addEventListener('click', async () => { page = page+1; await loadTrades(); });
  pageSize.addEventListener('change', async (e) => { size = Number(e.target.value); page = 1; await loadTrades(); });

  document.querySelectorAll('#logTable th[data-sort]').forEach(th => {
    th.addEventListener('click', () => {
      const key = th.getAttribute('data-sort');
      if(sort.key !== key) sort = { key, dir:'asc' };
      else if(sort.dir === 'asc') sort = { key, dir:'desc' };
      else sort = { key:'tsEpochMs', dir:'desc' };
      renderLogs();
    });
  });

  // Order Guard table sorting (Time only for now)
  document.querySelectorAll('#guardTable th[data-guardsort]').forEach(th => {
    th.addEventListener('click', () => {
      const key = th.getAttribute('data-guardsort');
      if(guardSort.key !== key) guardSort = { key, dir:'asc' };
      else if(guardSort.dir === 'asc') guardSort = { key, dir:'desc' };
      else guardSort = { key:'tsEpochMs', dir:'desc' };
      renderGuardLogs(guardLogs);
    });
  });

  (async () => {
    // Load label maps for strategies, intervals, and markets
    await initLabelMaps();

    // Trade Log Type 멀티셀렉트 필터 초기화
    (function initLogTypeFilter(){
      var SYSTEM_TYPES = [
        {value: 'TAKE_PROFIT', label: '익절(TP)'},
        {value: 'STOP_LOSS', label: '손절(SL)'},
        {value: 'TIME_STOP', label: '시간초과'},
        {value: 'STRATEGY_LOCK', label: '전략잠금'},
        {value: 'LOW_CONFIDENCE', label: '신뢰도미달'}
      ];
      var opts = (strategyCatalog || []).map(function(x){ return {value: x.key, label: x.label}; });
      opts = opts.concat(SYSTEM_TYPES);
      // 기본값: STRATEGY_LOCK 제외한 전부
      var initial = opts.filter(function(o){ return o.value !== 'STRATEGY_LOCK'; }).map(function(o){ return o.value; });
      var root = document.getElementById('logTypeMs');
      if(root){
        logTypeMs = initMultiSelect(root, {
          placeholder: 'Type filter',
          options: opts,
          initial: initial,
          onChange: function(){ renderLogs(); }
        });
      }
    })();

    // 첫 화면 렌더
    await refreshAll();
    setInterval(() => { fetchAssetSummary(true); fetchGuardLogs(true); }, 10000);
  })();
})();
