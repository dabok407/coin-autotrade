window.AutoTrade = (() => {

  // ── CSRF 토큰 (Spring Security CookieCsrfTokenRepository) ──
  function getCsrfToken() {
    var match = document.cookie.match(/XSRF-TOKEN=([^;]+)/);
    return match ? decodeURIComponent(match[1]) : '';
  }

  // ── Global fetch interceptor: CSRF + 401 redirect ──
  (function() {
    var originalFetch = window.fetch;
    window.fetch = function(url, options) {
      options = options || {};
      var method = (options.method || 'GET').toUpperCase();
      // Add CSRF token to non-GET requests
      if (method !== 'GET' && method !== 'HEAD') {
        if (!options.headers) options.headers = {};
        var token = getCsrfToken();
        if (token) {
          if (options.headers instanceof Headers) {
            options.headers.set('X-XSRF-TOKEN', token);
          } else {
            options.headers['X-XSRF-TOKEN'] = token;
          }
        }
      }
      return originalFetch.call(this, url, options).then(function(resp) {
        // 401 → redirect to login (except for auth endpoints)
        if (resp.status === 401 && String(url).indexOf('/api/auth/') === -1) {
          window.location.href = '/login?expired';
        }
        return resp;
      });
    };
  })();

  const API = {
    botStart:  '/api/bot/start',
    botStop:   '/api/bot/stop',
    botStatus: '/api/bot/status',
    botTrades: '/api/bot/trades',
    botSettings:'/api/bot/settings',
    backtestRun:'/api/backtest/run'
  };

  async function req(url, options){
    const res = await fetch(url, Object.assign({
      headers: { 'Content-Type': 'application/json' },
      cache: 'no-store'
    }, options || {}));

    // 세션 만료 감지: 다른 기기에서 로그인하면 이 세션이 만료됨
    if(res.status === 401){
      if(!url.includes('/api/auth/login') && !url.includes('/api/auth/pubkey')){
        window.location.href = '/login?expired';
        throw new Error('세션이 만료되었습니다.');
      }
    }

    if(!res.ok){
      const t = await res.text().catch(()=> '');
      throw new Error(`HTTP ${res.status}: ${t || res.statusText}`);
    }

    const ct = res.headers.get('content-type') || '';
    if(ct.includes('application/json')) return res.json();
    return res.text();
  }

  const fmt = (n) => (n==null || Number.isNaN(n)) ? '-' : Number(n).toLocaleString();

  // ===== Strategy help modal =====
  function ensureStrategyModal(){
    const modal = document.getElementById('strategyModal');
    if(!modal) return null;
    const closeEls = modal.querySelectorAll('[data-modal-close]');
    closeEls.forEach(el => {
      el.addEventListener('click', () => closeStrategyModal());
    });
    // esc close
    document.addEventListener('keydown', (e) => {
      if(e.key === 'Escape') closeStrategyModal();
    });
    return modal;
  }

  function closeStrategyModal(){
    const modal = document.getElementById('strategyModal');
    if(!modal) return;
    modal.classList.remove('open');
    modal.setAttribute('aria-hidden', 'true');
    document.body.style.overflow = '';
  }

  function openStrategyModal(list){
    const modal = ensureStrategyModal();
    if(!modal) return;
    // NOTE: avoid relying on global IDs (dashboard/backtest can coexist in DOM).
    const body = modal.querySelector('.modal-body');
    const title = modal.querySelector('.modal-title');
    if(title) title.textContent = 'Strategy Descriptions';

    const esc = (s) => String(s ?? '').replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;');
    const nl2br = (s) => esc(s).replaceAll('\n', '<br>');
    const roleBadge = (role) => {
      if (role === 'BUY_ONLY') return '<span style="display:inline-block;padding:2px 6px;border-radius:4px;font-size:10px;font-weight:800;background:rgba(32,201,151,.15);color:#20c997;margin-left:6px">매수 전용</span>';
      if (role === 'SELL_ONLY') return '<span style="display:inline-block;padding:2px 6px;border-radius:4px;font-size:10px;font-weight:800;background:rgba(255,77,109,.15);color:#ff4d6d;margin-left:6px">매도 전용</span>';
      if (role === 'SELF_CONTAINED') return '<span style="display:inline-block;padding:2px 6px;border-radius:4px;font-size:10px;font-weight:800;background:rgba(43,118,255,.15);color:#2b76ff;margin-left:6px">자급자족</span>';
      return '';
    };
    const intervalBadge = (mins) => {
      if (!mins || mins <= 0) return '';
      const label = mins >= 60 ? (mins / 60) + 'h' : mins + 'm';
      return '<span style="display:inline-block;padding:2px 6px;border-radius:4px;font-size:10px;font-weight:800;background:rgba(255,193,7,.15);color:#ffc107;margin-left:4px">' + label + '봉</span>';
    };
    const emaBadge = (mode, ema) => {
      if (mode === 'CONFIGURABLE' && ema > 0) return '<span style="display:inline-block;padding:2px 6px;border-radius:4px;font-size:10px;font-weight:800;background:rgba(43,118,255,.15);color:#4dabf7;margin-left:4px">EMA' + ema + '</span>';
      if (mode === 'INTERNAL') return '<span style="display:inline-block;padding:2px 6px;border-radius:4px;font-size:10px;font-weight:800;background:rgba(171,71,188,.15);color:#ab47bc;margin-left:4px">자체EMA</span>';
      return '';
    };
    const rows = (list||[]).map(x => `<tr><td>${esc(x.label)}${roleBadge(x.role || '')}${intervalBadge(x.recommendedInterval)}${emaBadge(x.emaFilterMode, x.recommendedEma)}</td><td>${nl2br(x.desc || '')}</td></tr>`).join('');
    const table = `
      <table class="tooltip-table">
        <colgroup>
          <col style="width:30%" />
          <col style="width:70%" />
        </colgroup>
        <tbody>${rows}</tbody>
      </table>`;
    if(body) body.innerHTML = table;

    modal.classList.add('open');
    modal.setAttribute('aria-hidden', 'false');
    document.body.style.overflow = 'hidden';
  }

  async function bindStrategyHelp(triggerEl){
    if(!triggerEl) return;
    const handler = async (e) => {
      e.preventDefault();
      try{
        const list = await req('/api/strategies');
        openStrategyModal(list);
      }catch(err){
        openStrategyModal([{label:'Error', desc:String(err && err.message ? err.message : err)}]);
      }
    };
    triggerEl.addEventListener('click', handler);
    triggerEl.addEventListener('keydown', (e) => {
      if(e.key === 'Enter' || e.key === ' ') handler(e);
    });
  }

  // Bind strategy help buttons automatically if present.
  // This avoids pages where initStrategies fails early (e.g., API hiccup)
  // leaving the help icon unresponsive.
  function autoBindStrategyHelp(){
    try{ bindStrategyHelp(document.getElementById('strategyHelpBtn')); }catch(e){}
    try{ bindStrategyHelp(document.getElementById('btStrategyHelpBtn')); }catch(e){}
  }

  // ===== Theme (Dark/Light) =====
  const THEME_KEY = 'autotrade_theme';
  function getTheme(){
    return localStorage.getItem(THEME_KEY) || 'dark';
  }
  function applyTheme(theme){
    const t = (theme === 'light') ? 'light' : 'dark';
    document.body.setAttribute('data-theme', t);
    localStorage.setItem(THEME_KEY, t);
    // aria label update helper
    const btn = document.getElementById('themeToggle');
    if(btn){
      btn.setAttribute('aria-pressed', String(t === 'light'));
      btn.textContent = (t === 'light') ? '\u263E' : '\u2600';
    }
  }
  function toggleTheme(){
    const next = (getTheme() === 'light') ? 'dark' : 'light';
    applyTheme(next);
  }
  function initTheme(){
    applyTheme(getTheme());
    const btn = document.getElementById('themeToggle');
    if(btn){
      btn.addEventListener('click', toggleTheme);
    }
  }

  function initMultiSelect(root, { placeholder='Select', options=[], initial=[], intervalDefaults={}, intervalOverrides={}, onChange }){
  const panel = root.querySelector('.ms-panel');
  const search = root.querySelector('.ms-search');
  const list = root.querySelector('.ms-list');
  const chips = root.querySelector('.ms-chips');
  const placeholderEl = root.querySelector('.ms-placeholder');

  // 인터벌 관련
  const hasIntervals = Object.keys(intervalDefaults).length > 0;
  const intervalMap = {}; // value → current interval
  const INTERVAL_OPTIONS = [5, 15, 30, 60, 240, 1440];
  const intervalLabel = (min) => {
    if (min >= 1440) return (min / 1440) + 'd';
    if (min >= 60) return (min / 60) + 'h';
    return min + 'm';
  };

  const norm = (options || []).map(o => {
    if (typeof o === 'string') return { value: o, label: o };
    return { value: o.value, label: (o.label == null ? o.value : o.label) };
  });
  const valueToLabel = new Map(norm.map(o => [String(o.value), String(o.label)]));

  let selected = new Set((initial || []).map(String));

  function setDataset(){
    root.dataset.value = JSON.stringify(Array.from(selected));
  }

  function renderButton(){
    chips.innerHTML = '';
    const arr = Array.from(selected);
    const buttonEl = root.querySelector('.ms-button');

    if(arr.length === 0){
      placeholderEl.style.display = 'block';
      if(buttonEl){
        buttonEl.removeAttribute('data-tooltip');
        buttonEl.removeAttribute('title');
      }
    }else{
      placeholderEl.style.display = 'none';

      // 요구사항:
      // - 버튼에는 "첫 번째 선택 외 N건" 형태로 표시
      // - hover 시 말풍선(tooltip)로 전체 목록 제공
      const labels = arr.map(v => valueToLabel.get(String(v)) || String(v));
      const head = labels[0];
      const rest = labels.length - 1;
      const summaryText = (rest > 0) ? `${head} 외 ${rest}건` : head;

      const summary = document.createElement('span');
      summary.className = 'ms-summary';
      summary.textContent = summaryText;
      chips.appendChild(summary);

      const tooltip = labels.join('\n');
      if(buttonEl){
        buttonEl.setAttribute('data-tooltip', tooltip);
        // native title은 fallback
        buttonEl.setAttribute('title', labels.join(', '));
      }
    }
    setDataset();
  }

  function renderList(){
    const q = (search.value || '').trim().toLowerCase();
    list.innerHTML = '';
    norm.forEach(opt => {
      const v = String(opt.value);
      const label = String(opt.label);
      if(q && !(label.toLowerCase().includes(q) || v.toLowerCase().includes(q))) return;

      const li = document.createElement('div');
      li.className = 'ms-item';
      li.setAttribute('role','option');
      li.setAttribute('aria-selected', selected.has(v) ? 'true' : 'false');

      const cb = document.createElement('span');
      cb.className = 'ms-check';
      cb.textContent = selected.has(v) ? '✓' : '';
      const text = document.createElement('span');
      text.textContent = label;
      text.style.flex = '1';

      li.appendChild(cb);
      li.appendChild(text);

      // 인터벌 배지 (intervalDefaults가 있는 경우에만)
      if (hasIntervals && intervalDefaults[v] != null) {
        if (!intervalMap[v]) intervalMap[v] = intervalOverrides[v] || intervalDefaults[v];
        const badge = document.createElement('select');
        badge.className = 'ms-intv-select';
        badge.style.cssText = 'width:52px;padding:1px 2px;font-size:11px;border-radius:4px;border:1px solid var(--border,#ddd);background:var(--surface,#fff);color:var(--text,#333);cursor:pointer;margin-left:auto;text-align:center;';
        INTERVAL_OPTIONS.forEach(iv => {
          const opt = document.createElement('option');
          opt.value = iv;
          opt.textContent = intervalLabel(iv);
          if (intervalMap[v] === iv) opt.selected = true;
          badge.appendChild(opt);
        });
        badge.addEventListener('click', (e) => e.stopPropagation());
        badge.addEventListener('change', (e) => {
          e.stopPropagation();
          intervalMap[v] = parseInt(badge.value);
        });
        li.appendChild(badge);
      }

      li.addEventListener('click', (e) => {
        // 요구사항: 항목을 선택해도 드롭다운이 닫히지 않도록(항상 열린 상태 유지)
        // document click 핸들러와의 상호작용을 피하기 위해 이벤트 전파를 차단합니다.
        if(e) e.stopPropagation();
        if(selected.has(v)) selected.delete(v);
        else selected.add(v);
        renderButton();
        renderList();
        if(typeof onChange === 'function') onChange(Array.from(selected));
      });

      list.appendChild(li);
    });
  }

  function open(){
    // CSS는 .ms-panel.open 으로 display를 제어하므로 class를 같이 토글합니다.
    panel.hidden = false;
    panel.classList.add('open');
    root.classList.add('open');

    // overflow:hidden 부모 컨테이너를 벗어나도록 fixed 포지셔닝 사용
    var rect = root.getBoundingClientRect();
    var spaceBelow = window.innerHeight - rect.bottom - 12;
    panel.style.position = 'fixed';
    panel.style.left = rect.left + 'px';
    panel.style.width = rect.width + 'px';
    panel.style.zIndex = '9999';
    // 아래 공간이 충분하면 아래로, 아니면 위로 표시 (120px 이상이면 아래로 우선)
    if(spaceBelow >= 120){
      panel.style.top = (rect.bottom + 4) + 'px';
      panel.style.bottom = '';
      panel.style.maxHeight = Math.min(spaceBelow, 380) + 'px';
    } else {
      panel.style.top = '';
      panel.style.bottom = (window.innerHeight - rect.top + 4) + 'px';
      panel.style.maxHeight = Math.min(rect.top - 12, 380) + 'px';
    }

    renderList();
    setTimeout(()=> search && search.focus(), 0);
  }

  function close(){
    panel.hidden = true;
    panel.classList.remove('open');
    root.classList.remove('open');
    if(search) search.value = '';
    // fixed 포지셔닝 초기화
    panel.style.position = '';
    panel.style.top = '';
    panel.style.bottom = '';
    panel.style.left = '';
    panel.style.width = '';
    panel.style.zIndex = '';
    panel.style.maxHeight = '';
  }

  // events
  const button = root.querySelector('.ms-button');
  button.addEventListener('click', () => {
    if(panel.classList.contains('open')) close();
    else open();
  });

  document.addEventListener('click', (e) => {
    if(!root.contains(e.target)) close();
  });

  // 패널 내부 클릭은 닫힘 처리/토글에 영향을 주지 않도록(요구사항: 열린 상태 유지)
  if(panel){
    panel.addEventListener('click', (e) => e.stopPropagation());
  }

  // fixed 포지셔닝 사용 시 외부 스크롤하면 패널 닫기 (패널 내부 스크롤은 유지)
  window.addEventListener('scroll', function(e){ if(panel.classList.contains('open') && !panel.contains(e.target)) close(); }, true);

  search.addEventListener('input', renderList);

  // footer actions (v1: toggleAll, v2: all/none)
  const selectAll = root.querySelector('[data-ms="all"]');
  if(selectAll){
    selectAll.addEventListener('click', () => {
      selected = new Set(norm.map(o => String(o.value)));
      renderButton();
      renderList();
      if(typeof onChange === 'function') onChange(Array.from(selected));
    });
  }

  const clearAll = root.querySelector('[data-ms="none"]');
  if(clearAll){
    clearAll.addEventListener('click', () => {
      selected = new Set();
      renderButton();
      renderList();
      if(typeof onChange === 'function') onChange(Array.from(selected));
    });
  }

  const toggleAll = root.querySelector('[data-ms="toggleAll"]');
  if(toggleAll){
    toggleAll.addEventListener('click', () => {
      const arr = Array.from(selected);
      if(arr.length === norm.length) selected = new Set();
      else selected = new Set(norm.map(o => String(o.value)));
      renderButton();
      renderList();
      if(typeof onChange === 'function') onChange(Array.from(selected));
    });
  }

  // initial state: closed
  close();
  renderButton();
  return {
    getSelected(){ return Array.from(selected); },
    setSelected(arr, silent){ selected = new Set((arr||[]).map(String)); renderButton(); renderList(); if(!silent && typeof onChange === 'function') onChange(Array.from(selected)); },
    /** Update available options while preserving current selections.
     *  Options removed from newOpts will be deselected. */
    updateOptions(newOpts){
      const newNorm = (newOpts || []).map(o => {
        if (typeof o === 'string') return { value: o, label: o };
        return { value: o.value, label: (o.label == null ? o.value : o.label) };
      });
      // Replace norm array contents
      norm.length = 0;
      for (let i = 0; i < newNorm.length; i++) norm.push(newNorm[i]);
      // Update label map
      valueToLabel.clear();
      norm.forEach(o => valueToLabel.set(String(o.value), String(o.label)));
      // Remove selections that are no longer available
      const validValues = new Set(norm.map(o => String(o.value)));
      for (const v of selected) {
        if (!validValues.has(v)) selected.delete(v);
      }
      renderButton();
      renderList();
    },
    getIntervalsCsv(){
      // 선택된 전략 중 글로벌 인터벌과 다른 것만 CSV로 반환
      const parts = [];
      for (const v of selected) {
        if (intervalMap[v] != null && intervalMap[v] !== intervalDefaults[v]) {
          parts.push(v + ':' + intervalMap[v]);
        }
      }
      return parts.join(',');
    },
    setIntervalsCsv(csv){
      if (!csv) return;
      csv.split(',').forEach(pair => {
        const [k, val] = pair.trim().split(':');
        if (k && val) intervalMap[k.trim()] = parseInt(val.trim());
      });
      renderList();
    },
    getAllIntervalsCsv(){
      // 모든 선택된 전략의 인터벌 (기본값 포함)
      const parts = [];
      for (const v of selected) {
        const intv = intervalMap[v] || intervalDefaults[v];
        if (intv != null) parts.push(v + ':' + intv);
      }
      return parts.join(',');
    },
    open, close
  };
}

  // Replace literal "\\n" sequences in data-tooltip with real newlines
  // AND set up floating tooltip (fixed-position, never clipped by overflow)
  var _tooltipEl = null;
  var _tooltipHideTimer = null;

  function _ensureTooltipEl() {
    if (_tooltipEl) return _tooltipEl;
    _tooltipEl = document.createElement('div');
    _tooltipEl.className = 'tooltip-float';
    document.body.appendChild(_tooltipEl);
    return _tooltipEl;
  }

  function _showTooltip(e) {
    var el = e.currentTarget || e.target;
    var text = el.getAttribute('data-tooltip');
    if (!text) return;
    if (_tooltipHideTimer) { clearTimeout(_tooltipHideTimer); _tooltipHideTimer = null; }
    var tip = _ensureTooltipEl();
    tip.textContent = text;
    tip.classList.add('visible');
    // Position: above the element, centered
    var rect = el.getBoundingClientRect();
    tip.style.left = '0px'; tip.style.top = '0px';
    var tw = tip.offsetWidth, th = tip.offsetHeight;
    var left = rect.left + rect.width / 2 - tw / 2;
    var top = rect.top - th - 8;
    // Clamp to viewport
    if (left < 8) left = 8;
    if (left + tw > window.innerWidth - 8) left = window.innerWidth - tw - 8;
    if (top < 8) { top = rect.bottom + 8; } // flip below if no space above
    tip.style.left = left + 'px';
    tip.style.top = top + 'px';
  }

  function _hideTooltip() {
    if (_tooltipHideTimer) clearTimeout(_tooltipHideTimer);
    _tooltipHideTimer = setTimeout(function() {
      if (_tooltipEl) _tooltipEl.classList.remove('visible');
    }, 80);
  }

  function normalizeTooltips(root) {
    root = root || document;
    try {
      var elms = root.querySelectorAll ? root.querySelectorAll('[data-tooltip]') : [];
      for (var i = 0; i < elms.length; i++) {
        var elm = elms[i];
        // Normalize \\n → real newlines
        var t = elm.getAttribute('data-tooltip');
        if (t && t.indexOf('\\n') >= 0) {
          elm.setAttribute('data-tooltip', t.replace(/\\n/g, '\n'));
        }
        // Bind hover events (only once)
        if (!elm._ttBound) {
          elm._ttBound = true;
          elm.addEventListener('mouseenter', _showTooltip);
          elm.addEventListener('mouseleave', _hideTooltip);
          elm.addEventListener('focus', _showTooltip);
          elm.addEventListener('blur', _hideTooltip);
        }
      }
    } catch(e) { /* ignore */ }
  }

  // ===== Toast Notification System =====
  // type: 'success' | 'error' | 'warn' | 'info'
  function showToast(message, type, duration){
    type = type || 'info';
    duration = duration || 3500;
    var container = document.getElementById('toastContainer');
    if(!container){
      container = document.createElement('div');
      container.className = 'toast-container';
      container.id = 'toastContainer';
      document.body.appendChild(container);
    }
    var icons = { success:'\u2705', error:'\u274C', warn:'\u26A0\uFE0F', info:'\u2139\uFE0F' };
    var toast = document.createElement('div');
    toast.className = 'toast toast-' + type;
    toast.innerHTML =
      '<span class="toast-icon">' + (icons[type] || '') + '</span>' +
      '<span class="toast-msg">' + String(message).replace(/</g, '&lt;').replace(/>/g, '&gt;') + '</span>' +
      '<button class="toast-close" aria-label="Close">&times;</button>';
    container.appendChild(toast);
    var closeBtn = toast.querySelector('.toast-close');
    var timer = null;
    function dismiss(){
      if(timer) clearTimeout(timer);
      toast.classList.add('toast-out');
      setTimeout(function(){ try{ container.removeChild(toast); }catch(e){} }, 300);
    }
    closeBtn.addEventListener('click', dismiss);
    timer = setTimeout(dismiss, duration);
  }

  // ===== Confirm Dialog (styled, returns Promise) =====
  function showConfirm(message){
    return new Promise(function(resolve){
      // Fallback to native confirm for simplicity; can be replaced with custom modal later
      resolve(confirm(message));
    });
  }

  // ===== Strategy Group Presets (Phase1+2 최적화 결과 기반, 2026-03-14 갱신) =====
  // Phase 1: 89.8M 조합 전수 검사 + Phase 2: 3.1M 다중 전략 조합 검사
  // NOTE: CDR, TWS, TMB, SM, BMR 전략은 deprecated 처리됨 (거래 0건 또는 일관된 손실)
  // 최적 결과 요약 (활성 전략 기준):
  // - SOL: IBB(240)+MORNING_STAR(30) TP15/SL20 → 1Y ROI +94.8% (Phase 2 최고)
  // - ADA: IBB(30) TP20/SL7 → 1Y ROI +65.2%
  // - BTC: IBB(240) TP10/SL5 → 1Y ROI (활성 전략 기준 재최적화 필요)
  // - ETH: IBB(240) TP15/SL7 → 1Y ROI (활성 전략 기준 재최적화 필요)
  var GROUP_PRESETS = {
    BULL_AGG: {
      label: '상승장 · 공격형',
      desc: 'TP15/SL7 · 5전략 · IBB+MS · 추매2',
      condition: 'BULL', style: 'AGG',
      strategies: ['INSIDE_BAR_BREAKOUT','MORNING_STAR','BEARISH_ENGULFING','EVENING_STAR_SELL','THREE_BLACK_CROWS_SELL'],
      candleUnitMin: 240,
      strategyIntervals: {MORNING_STAR:30, EVENING_STAR_SELL:240, THREE_BLACK_CROWS_SELL:240, BEARISH_ENGULFING:240},
      emaMap: {},
      takeProfitPct: 15, stopLossPct: 7, minConfidence: 6,
      maxAddBuys: 2, timeStopMinutes: 4320, strategyLock: false,
      orderSizingMode: 'PCT', orderSizingValue: 90
    },
    BULL_STB: {
      label: '상승장 · 안정형',
      desc: 'TP10/SL5 · 4전략 · IBB · 추매1',
      condition: 'BULL', style: 'STB',
      strategies: ['INSIDE_BAR_BREAKOUT','BEARISH_ENGULFING','EVENING_STAR_SELL','THREE_BLACK_CROWS_SELL'],
      candleUnitMin: 240,
      strategyIntervals: {EVENING_STAR_SELL:240, THREE_BLACK_CROWS_SELL:240, BEARISH_ENGULFING:240},
      emaMap: {},
      takeProfitPct: 10, stopLossPct: 5, minConfidence: 7,
      maxAddBuys: 1, timeStopMinutes: 4320, strategyLock: false,
      orderSizingMode: 'PCT', orderSizingValue: 90
    },
    SIDE_AGG: {
      label: '횡보장 · 공격형',
      desc: 'TP10/SL7 · 6전략 · IBB+BPO · 추매2',
      condition: 'SIDE', style: 'AGG',
      strategies: ['INSIDE_BAR_BREAKOUT','BULLISH_PINBAR_ORDERBLOCK','MORNING_STAR','BEARISH_ENGULFING','EVENING_STAR_SELL','THREE_BLACK_CROWS_SELL'],
      candleUnitMin: 240,
      strategyIntervals: {BULLISH_PINBAR_ORDERBLOCK:60, MORNING_STAR:30, EVENING_STAR_SELL:240, THREE_BLACK_CROWS_SELL:240, BEARISH_ENGULFING:240},
      emaMap: {},
      takeProfitPct: 10, stopLossPct: 7, minConfidence: 6,
      maxAddBuys: 2, timeStopMinutes: 4320, strategyLock: false,
      orderSizingMode: 'PCT', orderSizingValue: 90
    },
    SIDE_STB: {
      label: '횡보장 · 안정형',
      desc: 'TP8/SL5 · 5전략 · IBB+MS · 추매1',
      condition: 'SIDE', style: 'STB',
      strategies: ['INSIDE_BAR_BREAKOUT','MORNING_STAR','BEARISH_ENGULFING','EVENING_STAR_SELL','THREE_BLACK_CROWS_SELL'],
      candleUnitMin: 240,
      strategyIntervals: {MORNING_STAR:30, EVENING_STAR_SELL:240, THREE_BLACK_CROWS_SELL:240, BEARISH_ENGULFING:240},
      emaMap: {},
      takeProfitPct: 8, stopLossPct: 5, minConfidence: 7,
      maxAddBuys: 1, timeStopMinutes: 2880, strategyLock: false,
      orderSizingMode: 'PCT', orderSizingValue: 90
    },
    BEAR_AGG: {
      label: '하락장 · 공격형',
      desc: 'TP8/SL3 · 4전략 · IBB · 추매1',
      condition: 'BEAR', style: 'AGG',
      strategies: ['INSIDE_BAR_BREAKOUT','BEARISH_ENGULFING','EVENING_STAR_SELL','THREE_BLACK_CROWS_SELL'],
      candleUnitMin: 30,
      strategyIntervals: {EVENING_STAR_SELL:60, THREE_BLACK_CROWS_SELL:60, BEARISH_ENGULFING:60},
      emaMap: {},
      takeProfitPct: 8, stopLossPct: 3, minConfidence: 7,
      maxAddBuys: 1, timeStopMinutes: 1440, strategyLock: false,
      orderSizingMode: 'PCT', orderSizingValue: 90
    },
    BEAR_STB: {
      label: '하락장 · 안정형',
      desc: 'TP5/SL2 · 4전략 · IBB · 추매0',
      condition: 'BEAR', style: 'STB',
      strategies: ['INSIDE_BAR_BREAKOUT','BEARISH_ENGULFING','EVENING_STAR_SELL','THREE_BLACK_CROWS_SELL'],
      candleUnitMin: 30,
      strategyIntervals: {EVENING_STAR_SELL:60, THREE_BLACK_CROWS_SELL:60, BEARISH_ENGULFING:60},
      emaMap: {},
      takeProfitPct: 5, stopLossPct: 2, minConfidence: 8,
      maxAddBuys: 0, timeStopMinutes: 1440, strategyLock: false,
      orderSizingMode: 'PCT', orderSizingValue: 90
    },
    // ===== 단타(스캘핑) 프리셋 =====
    SCALP_AGG: {
      label: '단타 · 공격형',
      desc: '자급자족 · 3전략 · 5분봉 · 추매1',
      condition: 'SCALP', style: 'AGG',
      strategies: ['SCALP_RSI_BOUNCE','SCALP_EMA_PULLBACK','SCALP_BREAKOUT_RANGE'],
      candleUnitMin: 5,
      strategyIntervals: {SCALP_BREAKOUT_RANGE: 15},
      emaMap: {},
      takeProfitPct: 0, stopLossPct: 0, minConfidence: 5,
      maxAddBuys: 1, timeStopMinutes: 0, strategyLock: true,
      orderSizingMode: 'PCT', orderSizingValue: 50
    },
    SCALP_STB: {
      label: '단타 · 안정형',
      desc: '자급자족 · 2전략 · 15분봉 · 추매0',
      condition: 'SCALP', style: 'STB',
      strategies: ['SCALP_EMA_PULLBACK','SCALP_BREAKOUT_RANGE'],
      candleUnitMin: 15,
      strategyIntervals: {},
      emaMap: {},
      takeProfitPct: 0, stopLossPct: 0, minConfidence: 6,
      maxAddBuys: 0, timeStopMinutes: 0, strategyLock: true,
      orderSizingMode: 'PCT', orderSizingValue: 30
    },
    // ===== 오프닝 레인지 돌파 프리셋 =====
    OPEN_AGG: {
      label: '오프닝 · 공격형',
      desc: '자급자족 · 3전략 · 5분봉 · 추매1',
      condition: 'OPEN', style: 'AGG',
      strategies: ['SCALP_OPENING_BREAK','SCALP_EMA_PULLBACK','SCALP_RSI_BOUNCE'],
      candleUnitMin: 5,
      strategyIntervals: {},
      emaMap: {},
      takeProfitPct: 0, stopLossPct: 0, minConfidence: 5,
      maxAddBuys: 1, timeStopMinutes: 0, strategyLock: true,
      orderSizingMode: 'PCT', orderSizingValue: 50
    },
    OPEN_STB: {
      label: '오프닝 · 안정형',
      desc: '자급자족 · 1전략 · 5분봉 · 추매0',
      condition: 'OPEN', style: 'STB',
      strategies: ['SCALP_OPENING_BREAK'],
      candleUnitMin: 5,
      strategyIntervals: {},
      emaMap: {},
      takeProfitPct: 0, stopLossPct: 0, minConfidence: 6,
      maxAddBuys: 0, timeStopMinutes: 0, strategyLock: true,
      orderSizingMode: 'PCT', orderSizingValue: 30
    }
  };

  /**
   * Build the HTML for preset bar (inserted into group card)
   */
  function buildPresetBarHtml() {
    return '<div class="preset-bar">' +
      '<span class="preset-bar-label">Preset</span>' +
      '<div class="preset-chips">' +
        '<div class="preset-chip-group">' +
          '<button type="button" class="preset-cond-chip" data-cond="BULL"><span class="preset-dot bull"></span>상승장</button>' +
          '<button type="button" class="preset-cond-chip" data-cond="SIDE"><span class="preset-dot side"></span>횡보장</button>' +
          '<button type="button" class="preset-cond-chip" data-cond="BEAR"><span class="preset-dot bear"></span>하락장</button>' +
          '<button type="button" class="preset-cond-chip" data-cond="SCALP"><span class="preset-dot scalp"></span>단타</button>' +
          '<button type="button" class="preset-cond-chip" data-cond="OPEN"><span class="preset-dot open"></span>오프닝</button>' +
        '</div>' +
        '<span class="preset-sep">|</span>' +
        '<div class="preset-chip-group">' +
          '<button type="button" class="preset-style-chip" data-style="AGG">&#9889; 공격형</button>' +
          '<button type="button" class="preset-style-chip" data-style="STB">&#128737; 안정형</button>' +
        '</div>' +
      '</div>' +
    '</div>';
  }

  /**
   * Apply a preset to a group card instance.
   * @param {Object} inst - group instance {el, stratMs, marketMs, stratIntervals, emaMap}
   * @param {string} presetKey - e.g. 'BULL_AGG'
   */
  function applyGroupPreset(inst, presetKey) {
    var p = GROUP_PRESETS[presetKey];
    if (!p) return;
    var card = inst.el;

    // Strategies
    if (inst.stratMs && inst.stratMs.setSelected) {
      inst.stratMs.setSelected(p.strategies, false);
    }

    // Interval — update chips + hidden input
    var intVal = String(p.candleUnitMin || 240);
    var intv = card.querySelector('.grp-interval');
    if (intv) intv.value = intVal;
    var chips = card.querySelectorAll('.interval-chip');
    for (var ci = 0; ci < chips.length; ci++) {
      chips[ci].classList.toggle('active', chips[ci].getAttribute('data-val') === intVal);
    }

    // Order sizing
    var om = card.querySelector('.grp-orderMode');
    if (om) om.value = p.orderSizingMode || 'PCT';
    var ov = card.querySelector('.grp-orderValue');
    if (ov) ov.value = fmt(p.orderSizingValue || 90);

    // Risk fields
    var tp = card.querySelector('.grp-tp');
    if (tp) tp.value = p.takeProfitPct;
    var sl = card.querySelector('.grp-sl');
    if (sl) sl.value = p.stopLossPct;
    var ma = card.querySelector('.grp-maxAdd');
    if (ma) ma.value = p.maxAddBuys;
    var mc = card.querySelector('.grp-minConf');
    if (mc) mc.value = p.minConfidence;
    var ts = card.querySelector('.grp-timeStop');
    if (ts) ts.value = p.timeStopMinutes;

    // Strategy Lock — toggle switch
    var lockBtn = card.querySelector('.grp-stratLock');
    var lockLbl = card.querySelector('.grp-stratLockLabel');
    if (lockBtn) {
      if (p.strategyLock) {
        lockBtn.classList.add('on');
        lockBtn.setAttribute('aria-pressed', 'true');
      } else {
        lockBtn.classList.remove('on');
        lockBtn.setAttribute('aria-pressed', 'false');
      }
      if (lockLbl) lockLbl.textContent = p.strategyLock ? 'ON' : 'OFF';
    }

    // Strategy intervals (per-strategy overrides)
    inst.stratIntervals = {};
    for (var k in (p.strategyIntervals || {})) {
      if (p.strategyIntervals.hasOwnProperty(k)) {
        inst.stratIntervals[k] = p.strategyIntervals[k];
      }
    }

    // EMA map
    inst.emaMap = {};
    for (var k in (p.emaMap || {})) {
      if (p.emaMap.hasOwnProperty(k)) {
        inst.emaMap[k] = p.emaMap[k];
      }
    }

    showToast(p.label + ' 프리셋이 적용되었습니다.', 'success');
  }

  /**
   * Bind preset chip click events on a group card.
   * @param {HTMLElement} card - the group card element
   * @param {Object} inst - group instance
   */
  function bindPresetBar(card, inst) {
    var condChips = card.querySelectorAll('.preset-cond-chip');
    var styleChips = card.querySelectorAll('.preset-style-chip');
    var selectedCond = null;
    var selectedStyle = null;

    // Restore internal state from saved preset (e.g. 'BULL_AGG' → selectedCond='BULL', selectedStyle='AGG')
    if (inst.selectedPreset) {
      var _parts = inst.selectedPreset.split('_');
      if (_parts.length === 2) {
        selectedCond = _parts[0];
        selectedStyle = _parts[1];
      }
    }

    function doApply() {
      // 시장상황만 선택 → 안정형(STB) 기본
      // 운용스타일만 선택 → 횡보장(SIDE) 기본
      // 둘 다 선택 → 정확한 조합
      var cond = selectedCond || 'SIDE';
      var style = selectedStyle || 'STB';
      var key = cond + '_' + style;
      inst.selectedPreset = key;
      applyGroupPreset(inst, key);
    }

    for (var i = 0; i < condChips.length; i++) {
      condChips[i].addEventListener('click', function(e) {
        e.preventDefault();
        e.stopPropagation();
        var cond = this.getAttribute('data-cond');
        // Toggle: 같은 칩 다시 클릭 시 해제
        if (selectedCond === cond) {
          this.classList.remove('active');
          selectedCond = null;
          inst.selectedPreset = null;
          return;
        }
        for (var j = 0; j < condChips.length; j++) condChips[j].classList.remove('active');
        this.classList.add('active');
        selectedCond = cond;
        doApply();
      });
    }

    for (var i = 0; i < styleChips.length; i++) {
      styleChips[i].addEventListener('click', function(e) {
        e.preventDefault();
        e.stopPropagation();
        var style = this.getAttribute('data-style');
        // Toggle: 같은 칩 다시 클릭 시 해제
        if (selectedStyle === style) {
          this.classList.remove('active');
          selectedStyle = null;
          inst.selectedPreset = null;
          return;
        }
        for (var j = 0; j < styleChips.length; j++) styleChips[j].classList.remove('active');
        this.classList.add('active');
        selectedStyle = style;
        doApply();
      });
    }
  }

  /**
   * Restore preset chip active states from a saved preset key.
   * Called on page load to show which preset was previously applied.
   * @param {HTMLElement} card - the group card element
   * @param {string} presetKey - e.g. 'BULL_AGG'
   */
  function restorePresetChips(card, presetKey) {
    if (!presetKey) return;
    var parts = presetKey.split('_');
    if (parts.length !== 2) return;
    var cond = parts[0];  // BULL, SIDE, BEAR
    var style = parts[1]; // AGG, STB

    var condChips = card.querySelectorAll('.preset-cond-chip');
    for (var i = 0; i < condChips.length; i++) {
      if (condChips[i].getAttribute('data-cond') === cond) {
        condChips[i].classList.add('active');
      }
    }

    var styleChips = card.querySelectorAll('.preset-style-chip');
    for (var i = 0; i < styleChips.length; i++) {
      if (styleChips[i].getAttribute('data-style') === style) {
        styleChips[i].classList.add('active');
      }
    }
  }

  return { API, req, fmt, initMultiSelect, initTheme, applyTheme, getTheme, bindStrategyHelp, autoBindStrategyHelp, closeStrategyModal, normalizeTooltips, showToast, showConfirm, GROUP_PRESETS, buildPresetBarHtml, applyGroupPreset, bindPresetBar, restorePresetChips };
})();

// Auto-bind on DOM ready
document.addEventListener('DOMContentLoaded', () => {
  try{ window.AutoTrade && window.AutoTrade.autoBindStrategyHelp && window.AutoTrade.autoBindStrategyHelp(); }catch(e){}
  try{ window.AutoTrade && window.AutoTrade.normalizeTooltips && window.AutoTrade.normalizeTooltips(document); }catch(e){}
});
