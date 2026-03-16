(function() {
  'use strict';

  var API = window.AutoTrade.API;
  var req = window.AutoTrade.req;
  var fmt = window.AutoTrade.fmt;
  var initMultiSelect = window.AutoTrade.initMultiSelect;
  var initTheme = window.AutoTrade.initTheme;
  var showToast = window.AutoTrade.showToast;

  // Dark/Light toggle
  initTheme();

  // ===== Loading Overlay =====
  var loadingOverlay = document.createElement('div');
  loadingOverlay.className = 'loading-overlay hidden';
  loadingOverlay.innerHTML = '<div class="loading-spinner"></div>'
    + '<div class="loading-text" id="loadingText">\ubc31\ud14c\uc2a4\ud2b8 \uc2e4\ud589 \uc911...</div>'
    + '<div class="loading-elapsed" id="loadingElapsed">0s</div>';
  document.body.appendChild(loadingOverlay);
  var loadingTimer = null;

  function showLoading(msg) {
    var textEl = document.getElementById('loadingText');
    var elapsedEl = document.getElementById('loadingElapsed');
    if (textEl) textEl.textContent = msg || '\ubc31\ud14c\uc2a4\ud2b8 \uc2e4\ud589 \uc911...';
    if (elapsedEl) elapsedEl.textContent = '0s';
    loadingOverlay.classList.remove('hidden');
    var startTime = Date.now();
    if (loadingTimer) clearInterval(loadingTimer);
    loadingTimer = setInterval(function() {
      var elapsed = Math.floor((Date.now() - startTime) / 1000);
      if (elapsedEl) elapsedEl.textContent = elapsed + 's';
    }, 1000);
  }

  function hideLoading() {
    loadingOverlay.classList.add('hidden');
    if (loadingTimer) { clearInterval(loadingTimer); loadingTimer = null; }
  }

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

  // Generic modal close (with body overflow fix)
  document.addEventListener('click', function(e) {
    var target = e.target;
    if (target && (target.hasAttribute('data-modal-close') || (target.closest && target.closest('[data-modal-close]')))) {
      var modal = target.closest('.modal');
      if (modal) {
        modal.classList.remove('open');
        modal.setAttribute('aria-hidden', 'true');
        document.body.style.overflow = '';
      }
    }
  });
  document.addEventListener('keydown', function(e) {
    if (e.key === 'Escape') {
      var modals = document.querySelectorAll('.modal.open');
      for (var i = 0; i < modals.length; i++) {
        modals[i].classList.remove('open');
        modals[i].setAttribute('aria-hidden', 'true');
      }
      document.body.style.overflow = '';
    }
  });

  var logs = [];
  var page = 1;
  var size = 50;
  var sort = { key: 'ts', dir: 'desc' };
  var filterAction = 'ALL';
  var intervalLabel = new Map();
  var strategyLabel = new Map();
  var marketLabel = new Map();
  var btStrategyCatalog = [];
  var btAllStrategyData = [];   // full API [{key, label, desc, role, recommendedInterval, emaFilterMode, recommendedEma}]
  var btLogTypeMs = null;

  // Strategy Group state
  var allStrategyOpts = [];
  var allMarketOpts = [];
  var btGroupInstances = [];
  var btGroupCounter = 0;
  var activeTab = 'basic'; // 'basic' or 'opening'
  var obMarketsMs = null;  // opening markets multi-select instance

  function el(id) { return document.getElementById(id); }

  function escAttr(s) {
    return String(s || '').replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  // Helper: get current backtest candle unit (minutes) from first group
  function getBtCandleUnit() {
    if (btGroupInstances.length > 0) {
      var card = btGroupInstances[0].el;
      var intervalSel = card.querySelector('.grp-interval');
      if (intervalSel) return parseInt(intervalSel.value) || 60;
    }
    return 60;
  }

  var btError = el('btError');
  var btRun = el('btRun');
  var btReset = el('btReset');

  var btTotalReturn = el('btTotalReturn');
  var btRoi = el('btRoi');
  var btTrades = el('btTrades');
  var btWinRate = el('btWinRate');

  // Results sections (initially hidden)
  var btResultsHeader = el('btResultsHeader');
  var btResultsBadge = el('btResultsBadge');
  var btKpiGrid = el('btKpiGrid');
  var btFinalCapital = el('btFinalCapital');
  var btWinRateText = el('btWinRateText');
  var btWinRateCircle = el('btWinRateCircle');
  var btEquitySection = el('btEquitySection');
  var btEquityChart = el('btEquityChart');
  var btEquityMeta = el('btEquityMeta');
  var btDistTp = el('btDistTp');
  var btDistSl = el('btDistSl');
  var btDistPattern = el('btDistPattern');
  var btDistTpCount = el('btDistTpCount');
  var btDistSlCount = el('btDistSlCount');
  var btDistPatternCount = el('btDistPatternCount');
  var btEquityChartInstance = null;

  var btTbody = el('btTbody');
  var btPagerInfo = el('btPagerInfo');
  var btPrev = el('btPrev');
  var btNext = el('btNext');

  var btPeriod = el('btPeriod');
  var btFromDate = el('btFromDate');
  var btFromTime = null; // removed (date-only)
  var btToDate = el('btToDate');
  var btToTime = null; // removed (date-only)

  // ═══════════════════════════════════════════════════════════════
  //  localStorage settings persistence
  // ═══════════════════════════════════════════════════════════════
  var BT_STORAGE_KEY = 'bt_settings_v1';

  function saveBtSettings() {
    try {
      var settings = {
        capitalKrw: parseNum(el('btCapital').value),
        period: el('btPeriod') ? el('btPeriod').value : '1\uc8fc',
        groups: collectBtGroups(),
        savedAt: Date.now()
      };
      localStorage.setItem(BT_STORAGE_KEY, JSON.stringify(settings));
    } catch (e) { /* ignore */ }
  }

  function loadBtSavedSettings() {
    try {
      var raw = localStorage.getItem(BT_STORAGE_KEY);
      return raw ? JSON.parse(raw) : null;
    } catch (e) { return null; }
  }

  function parseNum(v) {
    if (v == null) return 0;
    var s = String(v).replace(/[,%\s]/g, '').replace(/,/g, '');
    var n = Number(s);
    return isFinite(n) ? n : 0;
  }

  function formatInputWithCommas(inputEl) {
    if (!inputEl) return;
    inputEl.addEventListener('blur', function() {
      var n = parseNum(inputEl.value);
      if (n > 0) inputEl.value = Number(n).toLocaleString();
    });
  }

  function setError(msg) {
    btError.style.display = msg ? 'block' : 'none';
    btError.textContent = msg || '';
  }

  // ═══════════════════════════════════════════════════════════════
  //  Strategy Group Card Management
  // ═══════════════════════════════════════════════════════════════

  function addBtGroupCard(groupData) {
    var idx = btGroupCounter++;
    var container = document.getElementById('btGroupsContainer');

    var card = document.createElement('div');
    card.className = 'strategy-group-card';
    card.setAttribute('data-group-idx', idx);

    var defaultName = groupData ? groupData.groupName : ('Group ' + (btGroupInstances.length + 1));
    var collapsed = false;

    // Per-strategy state for this group
    var stratIntervals = {};  // {stratKey: intervalMin}
    var emaMap = {};           // {stratKey: emaPeriod}

    // Restore from saved data
    if (groupData && groupData.strategyIntervalsCsv) {
      var pairs = groupData.strategyIntervalsCsv.split(',');
      for (var pi = 0; pi < pairs.length; pi++) {
        var kv = pairs[pi].trim().split(':');
        if (kv.length === 2 && kv[0] && kv[1]) {
          stratIntervals[kv[0].trim()] = parseInt(kv[1].trim());
        }
      }
    }
    if (groupData && groupData.emaFilterCsv) {
      emaMap = parseBtEmaMap(groupData.emaFilterCsv);
    }

    card.innerHTML =
      '<div class="group-card-header">' +
        '<div style="display:flex;align-items:center;gap:8px;flex:1;min-width:0">' +
          '<span class="group-collapse-icon" style="cursor:pointer;font-size:14px;user-select:none">&#9660;</span>' +
          '<input type="text" class="group-name-input" value="' + escAttr(defaultName) + '" maxlength="100"/>' +
        '</div>' +
        '<button type="button" class="group-delete-btn pill small" style="color:var(--danger);font-size:12px" title="Delete group">&times;</button>' +
      '</div>' +
      AutoTrade.buildPresetBarHtml() +
      '<div class="group-card-body">' +
        '<div style="font-size:11px;font-weight:800;text-transform:uppercase;letter-spacing:.5px;color:var(--primary);margin-bottom:10px;padding-bottom:6px;border-bottom:1px solid var(--border)">Trading</div>' +
        '<div class="toolbar">' +
          '<div class="field" style="min-width:260px">' +
            '<label>Markets <span class="help-icon" data-tooltip="이 그룹에서 백테스트할 마켓(코인)을 선택합니다.\\n한 마켓은 하나의 그룹에만 속할 수 있습니다(상호배제)." aria-label="Markets help"></span></label>' +
            '<div class="ms" id="btGrpMarketMs_' + idx + '">' +
              '<button type="button" class="ms-button"><div class="ms-value"><span class="ms-placeholder">Select markets...</span><div class="ms-chips"></div></div><span class="ms-caret">&#9662;</span></button>' +
              '<div class="ms-panel"><input class="ms-search" placeholder="Search market..."/><div class="ms-list"></div>' +
              '<div class="ms-footer"><button type="button" class="ms-link" data-ms="all">Select all</button><button type="button" class="ms-link" data-ms="none">Clear</button></div></div>' +
            '</div>' +
          '</div>' +
          '<div class="field" style="min-width:260px">' +
            '<label class="label-row">' +
              'Strategies ' +
              '<span class="help-icon strategy-help-click grp-strat-help" data-tooltip="? 클릭 → 전략 상세 설명" aria-label="Strategy descriptions" tabindex="0" role="button" style="cursor:pointer"></span>' +
              '<button type="button" class="pill small grp-strat-detail-btn" style="font-size:10px;padding:2px 8px;margin-left:4px;vertical-align:middle" title="전략별 인터벌/EMA 상세 설정">&#9881; 상세</button>' +
            '</label>' +
            '<div class="ms" id="btGrpStratMs_' + idx + '">' +
              '<button type="button" class="ms-button"><div class="ms-value"><span class="ms-placeholder">Select strategies...</span><div class="ms-chips"></div></div><span class="ms-caret">&#9662;</span></button>' +
              '<div class="ms-panel"><input class="ms-search" placeholder="Search strategy..."/><div class="ms-list"></div>' +
              '<div class="ms-footer"><button type="button" class="ms-link" data-ms="all">Select all</button><button type="button" class="ms-link" data-ms="none">Clear</button></div></div>' +
            '</div>' +
          '</div>' +
          '<div class="field">' +
            '<label>캔들 간격 <span class="help-icon" data-tooltip="기본 캔들 인터벌(분)입니다.\\n⚙ 상세에서 전략별 개별 인터벌을 설정할 수 있습니다." aria-label="Interval help"></span></label>' +
            '<div class="interval-chips grp-interval-chips">' +
              '<button type="button" class="interval-chip" data-val="1">1m</button>' +
              '<button type="button" class="interval-chip" data-val="3">3m</button>' +
              '<button type="button" class="interval-chip" data-val="5">5m</button>' +
              '<button type="button" class="interval-chip" data-val="10">10m</button>' +
              '<button type="button" class="interval-chip" data-val="15">15m</button>' +
              '<button type="button" class="interval-chip" data-val="30">30m</button>' +
              '<button type="button" class="interval-chip" data-val="60">60m</button>' +
              '<button type="button" class="interval-chip active" data-val="240">240m</button>' +
            '</div>' +
            '<input type="hidden" class="grp-interval" value="240"/>' +
          '</div>' +
          '<div class="field" style="min-width:200px">' +
            '<label>Order Size <span class="help-icon" data-tooltip="PCT: 자본금의 비율(%) / Fixed: 고정 금액(KRW)" aria-label="Order Size help"></span></label>' +
            '<div style="display:flex;gap:8px;align-items:center">' +
              '<div class="select-wrap" style="width:120px"><select class="select grp-orderMode"><option value="FIXED">Fixed</option><option value="PCT" selected>% Cap</option></select></div>' +
              '<input class="input grp-orderValue" type="text" value="90" style="width:80px"/>' +
            '</div>' +
          '</div>' +
        '</div>' +
        /* Risk Parameters — collapsible */
        '<div class="risk-toggle">' +
          '<span class="risk-icon">&#9660;</span>' +
          '<span class="risk-label">리스크 파라미터</span>' +
        '</div>' +
        '<div class="risk-body">' +
          '<div class="risk-grid">' +
            '<div class="field"><label>TP (%) <span class="help-icon" data-tooltip="이익실현 비율. 평균매수가 대비 이 비율 이상 상승 시 매도" aria-label="TP help"></span></label><input class="input grp-tp" type="text" value="3.0"/></div>' +
            '<div class="field"><label>SL (%) <span class="help-icon" data-tooltip="손절 비율. 평균매수가 대비 이 비율 이상 하락 시 매도" aria-label="SL help"></span></label><input class="input grp-sl" type="text" value="2.0"/></div>' +
            '<div class="field"><label>최대 추가매수 <span class="help-icon" data-tooltip="최대 추가매수 횟수. 0이면 추가매수 안함" aria-label="Max Add Buys help"></span></label><input class="input grp-maxAdd" type="number" min="0" max="10" value="2"/></div>' +
            '<div class="field"><label>Min Confidence <span class="help-icon" data-tooltip="최소 신뢰도 점수. 이 값 미만 신호 무시. 0=모두 수용" aria-label="Min Confidence help"></span></label><input class="input grp-minConf" type="number" min="0" max="10" step="0.5" value="0"/></div>' +
            '<div class="field">' +
              '<label>전략 락 <span class="help-icon" data-tooltip="ON: 진입 전략만 청산 가능\\nOFF: 어떤 전략이든 매도 신호 시 청산" aria-label="Strategy Lock help"></span></label>' +
              '<div style="display:flex;align-items:center;gap:8px;height:42px">' +
                '<button class="switch grp-stratLock" aria-pressed="false"><span class="knob"></span></button>' +
                '<span class="grp-stratLockLabel" style="font-size:13px;color:var(--muted)">OFF</span>' +
              '</div>' +
            '</div>' +
            '<div class="field"><label>타임스탑 (min) <span class="help-icon" data-tooltip="시간 기반 손절(분). 보유시간 초과+손실 시 청산. 0=미사용" aria-label="Time Stop help"></span></label><input class="input grp-timeStop" type="number" min="0" step="30" value="0"/></div>' +
          '</div>' +
        '</div>' +
      '</div>';

    container.appendChild(card);

    // ── Init MultiSelects (clean, no intervalDefaults inside dropdown) ──
    var availableMarkets = getBtAvailableMarkets(idx);
    var marketMs = initMultiSelect(
      document.getElementById('btGrpMarketMs_' + idx),
      {
        placeholder: 'Select markets...',
        options: availableMarkets,
        initial: groupData ? (groupData.markets || []) : [],
        onChange: function() { updateBtAllMarketOptions(); }
      }
    );

    var stratMs = initMultiSelect(
      document.getElementById('btGrpStratMs_' + idx),
      {
        placeholder: 'Select strategies...',
        options: allStrategyOpts,
        initial: groupData ? (groupData.strategies || []) : []
      }
    );

    // ── Interval Chips ──
    var intervalChips = card.querySelectorAll('.interval-chip');
    var intervalHidden = card.querySelector('.grp-interval');
    for (var ci = 0; ci < intervalChips.length; ci++) {
      intervalChips[ci].addEventListener('click', function() {
        for (var cj = 0; cj < intervalChips.length; cj++) intervalChips[cj].classList.remove('active');
        this.classList.add('active');
        intervalHidden.value = this.getAttribute('data-val');
      });
    }

    // ── Risk Section Toggle ──
    var riskToggle = card.querySelector('.risk-toggle');
    var riskBody = card.querySelector('.risk-body');
    var riskIcon = card.querySelector('.risk-icon');
    if (riskToggle && riskBody) {
      riskToggle.addEventListener('click', function() {
        var isCollapsed = riskBody.classList.toggle('collapsed');
        if (riskIcon) riskIcon.innerHTML = isCollapsed ? '&#9654;' : '&#9660;';
      });
    }

    // Populate values from groupData
    if (groupData) {
      var intVal = String(groupData.candleUnitMin || 240);
      intervalHidden.value = intVal;
      for (var ci2 = 0; ci2 < intervalChips.length; ci2++) {
        intervalChips[ci2].classList.toggle('active', intervalChips[ci2].getAttribute('data-val') === intVal);
      }
      var orderMode = card.querySelector('.grp-orderMode');
      if (orderMode) orderMode.value = groupData.orderSizingMode || 'PCT';
      var orderVal = card.querySelector('.grp-orderValue');
      if (orderVal) orderVal.value = fmt(groupData.orderSizingValue || 90);
      var tpIn = card.querySelector('.grp-tp');
      if (tpIn) tpIn.value = groupData.takeProfitPct != null ? groupData.takeProfitPct : 3.0;
      var slIn = card.querySelector('.grp-sl');
      if (slIn) slIn.value = groupData.stopLossPct != null ? groupData.stopLossPct : 2.0;
      var maxAdd = card.querySelector('.grp-maxAdd');
      if (maxAdd) maxAdd.value = groupData.maxAddBuys != null ? groupData.maxAddBuys : 2;
      var minConf = card.querySelector('.grp-minConf');
      if (minConf) minConf.value = groupData.minConfidence || 0;
      var timeStop = card.querySelector('.grp-timeStop');
      if (timeStop) timeStop.value = groupData.timeStopMinutes || 0;
      var lockBtn = card.querySelector('.grp-stratLock');
      var lockLabel = card.querySelector('.grp-stratLockLabel');
      if (lockBtn && groupData.strategyLock) {
        lockBtn.classList.add('on');
        lockBtn.setAttribute('aria-pressed', 'true');
        if (lockLabel) lockLabel.textContent = 'ON';
      }
    }

    // ── Strategy Lock Toggle ──
    var lockSwitch = card.querySelector('.grp-stratLock');
    var lockLbl = card.querySelector('.grp-stratLockLabel');
    if (lockSwitch) {
      lockSwitch.addEventListener('click', function() {
        var isOn = lockSwitch.classList.toggle('on');
        lockSwitch.setAttribute('aria-pressed', String(isOn));
        if (lockLbl) lockLbl.textContent = isOn ? 'ON' : 'OFF';
      });
    }

    // ── Strategy Help Icon (? → descriptions modal) ──
    var stratHelp = card.querySelector('.grp-strat-help');
    if (stratHelp) {
      stratHelp.addEventListener('click', function(e) {
        e.preventDefault();
        e.stopPropagation();
        openBtStratDescModal();
      });
    }

    // ── Strategy Detail Settings Button (⚙ → per-strategy interval/EMA popup) ──
    var detailBtn = card.querySelector('.grp-strat-detail-btn');
    if (detailBtn) {
      detailBtn.addEventListener('click', function(e) {
        e.preventDefault();
        e.stopPropagation();
        openBtStratDetailModal(inst);
      });
    }

    // ── Collapse/Expand ──
    var collapseIcon = card.querySelector('.group-collapse-icon');
    var cardBody = card.querySelector('.group-card-body');
    if (collapseIcon) {
      collapseIcon.addEventListener('click', function() {
        collapsed = !collapsed;
        cardBody.style.display = collapsed ? 'none' : '';
        collapseIcon.innerHTML = collapsed ? '&#9654;' : '&#9660;';
      });
    }

    // ── Delete ──
    var delBtn = card.querySelector('.group-delete-btn');
    if (delBtn) {
      delBtn.addEventListener('click', function() {
        if (btGroupInstances.length <= 1) {
          if (window.AutoTrade && window.AutoTrade.showToast) {
            window.AutoTrade.showToast('At least one group is required.', 'error');
          }
          return;
        }
        card.remove();
        btGroupInstances = btGroupInstances.filter(function(g) { return g.idx !== idx; });
        updateBtAllMarketOptions();
        updateBtDeleteButtons();
      });
    }

    var inst = {
      idx: idx,
      el: card,
      stratMs: stratMs,
      marketMs: marketMs,
      stratIntervals: stratIntervals,
      emaMap: emaMap
    };
    btGroupInstances.push(inst);

    // ── Preset Bar ──
    AutoTrade.bindPresetBar(card, inst);

    // Normalize tooltips for this card
    try { AutoTrade.normalizeTooltips(card); } catch (e) { /* ignore */ }

    updateBtDeleteButtons();
    return inst;
  }

  // ══════════════════════════════════════════════════════════════
  //  Strategy Descriptions Modal (? icon)
  // ══════════════════════════════════════════════════════════════
  function openBtStratDescModal() {
    req('/api/strategies').then(function(list) {
      var modal = document.getElementById('strategyModal');
      if (!modal) return;
      var body = modal.querySelector('.modal-body');
      var title = modal.querySelector('.modal-title');
      if (title) title.textContent = 'Strategy Descriptions';

      var esc = function(s) { return String(s == null ? '' : s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); };
      var nl2br = function(s) { return esc(s).replace(/\n/g, '<br>'); };
      var badge = function(text, bg, fg) { return '<span style="display:inline-block;padding:2px 6px;border-radius:4px;font-size:10px;font-weight:800;background:'+bg+';color:'+fg+';margin-left:4px">'+text+'</span>'; };

      var rows = (list || []).map(function(x) {
        var b = '';
        if (x.role === 'BUY_ONLY') b += badge('\uB9E4\uC218\uC804\uC6A9','rgba(32,201,151,.15)','#20c997');
        if (x.role === 'SELL_ONLY') b += badge('\uB9E4\uB3C4\uC804\uC6A9','rgba(255,77,109,.15)','#ff4d6d');
        if (x.role === 'SELF_CONTAINED') b += badge('\uC790\uAE09\uC790\uC871','rgba(43,118,255,.15)','#2b76ff');
        if (x.recommendedInterval > 0) {
          var lbl = x.recommendedInterval >= 60 ? (x.recommendedInterval/60)+'h' : x.recommendedInterval+'m';
          b += badge(lbl+'\uBD09','rgba(255,193,7,.15)','#ffc107');
        }
        if (x.emaFilterMode === 'CONFIGURABLE' && x.recommendedEma > 0) b += badge('EMA'+x.recommendedEma,'rgba(43,118,255,.15)','#4dabf7');
        if (x.emaFilterMode === 'INTERNAL') b += badge('\uC790\uCCB4EMA','rgba(171,71,188,.15)','#ab47bc');
        return '<tr><td>'+esc(x.label)+b+'</td><td>'+nl2br(x.desc||'')+'</td></tr>';
      }).join('');

      if (body) body.innerHTML = '<table class="tooltip-table"><colgroup><col style="width:30%"/><col style="width:70%"/></colgroup><tbody>'+rows+'</tbody></table>';
      modal.classList.add('open');
      modal.setAttribute('aria-hidden', 'false');
      document.body.style.overflow = 'hidden';
    }).catch(function() {});
  }

  // ══════════════════════════════════════════════════════════════
  //  Strategy Detail Settings Modal (⚙ icon)
  // ══════════════════════════════════════════════════════════════
  var btCurrentDetailInst = null;
  var BT_INTERVAL_OPTIONS = [
    {v:0,l:'\uAE30\uBCF8\uAC12'},{v:1,l:'1m'},{v:3,l:'3m'},{v:5,l:'5m'},{v:10,l:'10m'},
    {v:15,l:'15m'},{v:30,l:'30m'},{v:60,l:'1h'},{v:120,l:'2h'},{v:240,l:'4h'}
  ];

  function openBtStratDetailModal(inst) {
    btCurrentDetailInst = inst;
    var modal = document.getElementById('stratDetailModal');
    if (!modal) return;
    var body = modal.querySelector('.modal-body');
    if (!body) return;

    var selected = inst.stratMs.getSelected();
    if (selected.length === 0) {
      if (window.AutoTrade && window.AutoTrade.showToast) {
        window.AutoTrade.showToast('\uBA3C\uC800 \uC804\uB7B5\uC744 \uC120\uD0DD\uD574\uC8FC\uC138\uC694.', 'error');
      }
      return;
    }

    // Build strategy data lookup
    var dataMap = {};
    for (var i = 0; i < btAllStrategyData.length; i++) {
      dataMap[btAllStrategyData[i].key] = btAllStrategyData[i];
    }

    var html = '<div style="margin-bottom:12px;font-size:13px;color:var(--muted)">선택된 전략별로 개별 캔들 인터벌과 EMA 필터를 설정합니다.<br>인터벌 "기본값" = 그룹의 Interval 설정을 따릅니다. EMA 0=OFF</div>';

    // Per-strategy table with Interval + EMA columns
    var hasEmaCol = false;
    for (var ci = 0; ci < selected.length; ci++) {
      var csd = dataMap[selected[ci]] || {};
      if (csd.emaFilterMode === 'CONFIGURABLE') { hasEmaCol = true; break; }
    }

    html += '<table class="tooltip-table" style="width:100%"><thead><tr><th style="text-align:left">전략</th><th style="width:130px;text-align:center">인터벌</th>';
    if (hasEmaCol) html += '<th style="width:100px;text-align:center">EMA</th>';
    html += '</tr></thead><tbody>';
    for (var si = 0; si < selected.length; si++) {
      var skey = selected[si];
      var sd = dataMap[skey] || {};
      var slabel = sd.label || skey;
      var recommended = sd.recommendedInterval || 0;
      var currentVal = inst.stratIntervals[skey] || 0;

      var options = '';
      for (var oi = 0; oi < BT_INTERVAL_OPTIONS.length; oi++) {
        var o = BT_INTERVAL_OPTIONS[oi];
        var sel = (currentVal === o.v) ? ' selected' : '';
        options += '<option value="'+o.v+'"'+sel+'>'+o.l+'</option>';
      }
      var recIntv = recommended > 0 ? (recommended >= 60 ? (recommended/60)+'h' : recommended+'m') : '';
      html += '<tr><td style="font-size:13px">'+escAttr(slabel)+'</td>';
      html += '<td style="text-align:center;vertical-align:middle"><div class="select-wrap" style="width:100px;height:34px;display:inline-block"><select class="select bt-sd-intv-select" data-strat="'+escAttr(skey)+'" style="width:100%;height:34px;font-size:12px">'+options+'</select></div>';
      if (recIntv) html += '<div style="font-size:10px;color:var(--primary);margin-top:2px">권장 '+recIntv+'</div>';
      html += '</td>';
      if (hasEmaCol) {
        if (sd.emaFilterMode === 'CONFIGURABLE') {
          var recEma = sd.recommendedEma || 50;
          var emaVal = skey in inst.emaMap ? inst.emaMap[skey] : recEma;
          html += '<td style="text-align:center;vertical-align:middle"><input class="input bt-sd-ema-input" data-strat="'+escAttr(skey)+'" type="number" min="0" max="500" step="10" value="'+emaVal+'" style="width:70px;height:34px;font-size:12px;text-align:center"/>';
          html += '<div style="font-size:10px;color:var(--primary);margin-top:2px">권장 '+recEma+'</div></td>';
        } else if (sd.emaFilterMode === 'INTERNAL') {
          html += '<td style="text-align:center;font-size:11px;color:var(--muted)">자체</td>';
        } else {
          html += '<td style="text-align:center;font-size:11px;color:var(--muted)">-</td>';
        }
      }
      html += '</tr>';
    }
    html += '</tbody></table>';

    body.innerHTML = html;

    modal.classList.add('open');
    modal.setAttribute('aria-hidden', 'false');
    document.body.style.overflow = 'hidden';
  }

  function saveBtStratDetailModal() {
    if (!btCurrentDetailInst) return;
    var modal = document.getElementById('stratDetailModal');
    if (!modal) return;

    // Save per-strategy intervals
    var selects = modal.querySelectorAll('.bt-sd-intv-select');
    for (var i = 0; i < selects.length; i++) {
      var key = selects[i].getAttribute('data-strat');
      var val = parseInt(selects[i].value) || 0;
      if (val > 0) {
        btCurrentDetailInst.stratIntervals[key] = val;
      } else {
        delete btCurrentDetailInst.stratIntervals[key];
      }
    }

    // Save per-strategy EMA
    var emaInputs = modal.querySelectorAll('.bt-sd-ema-input');
    for (var i = 0; i < emaInputs.length; i++) {
      var key = emaInputs[i].getAttribute('data-strat');
      var val = parseInt(emaInputs[i].value) || 0;
      btCurrentDetailInst.emaMap[key] = val;
    }

    modal.classList.remove('open');
    modal.setAttribute('aria-hidden', 'true');
    document.body.style.overflow = '';
    if (window.AutoTrade && window.AutoTrade.showToast) {
      window.AutoTrade.showToast('전략 상세 설정이 저장되었습니다.', 'success');
    }
  }

  // Bind save button (delegated)
  document.addEventListener('click', function(e) {
    if (e.target && e.target.id === 'btSdSaveBtn') {
      saveBtStratDetailModal();
    }
  });

  // ── Market Mutual Exclusion ──
  function getBtUsedMarkets(excludeIdx) {
    var used = {};
    for (var i = 0; i < btGroupInstances.length; i++) {
      if (btGroupInstances[i].idx === excludeIdx) continue;
      var sel = btGroupInstances[i].marketMs.getSelected();
      for (var j = 0; j < sel.length; j++) {
        used[sel[j]] = true;
      }
    }
    return used;
  }

  function getBtAvailableMarkets(forIdx) {
    var used = getBtUsedMarkets(forIdx);
    return allMarketOpts.filter(function(o) {
      return !used[o.value];
    });
  }

  function updateBtAllMarketOptions() {
    for (var i = 0; i < btGroupInstances.length; i++) {
      var inst = btGroupInstances[i];
      var available = getBtAvailableMarkets(inst.idx);
      var currentSelected = inst.marketMs.getSelected();

      var optSet = {};
      var opts = [];
      for (var j = 0; j < currentSelected.length; j++) {
        var val = currentSelected[j];
        optSet[val] = true;
        var lbl = val;
        for (var k = 0; k < allMarketOpts.length; k++) {
          if (allMarketOpts[k].value === val) { lbl = allMarketOpts[k].label; break; }
        }
        opts.push({ value: val, label: lbl });
      }
      for (var j = 0; j < available.length; j++) {
        if (!optSet[available[j].value]) {
          opts.push(available[j]);
          optSet[available[j].value] = true;
        }
      }

      if (inst.marketMs.updateOptions) {
        inst.marketMs.updateOptions(opts);
      }
    }
  }

  function updateBtDeleteButtons() {
    var btns = document.querySelectorAll('#btGroupsContainer .group-delete-btn');
    for (var i = 0; i < btns.length; i++) {
      btns[i].style.display = btGroupInstances.length <= 1 ? 'none' : '';
    }
  }

  // ── Helpers ──
  function parseBtEmaMap(csv) {
    var map = {};
    if (!csv) return map;
    csv = String(csv).trim();
    if (csv.indexOf(':') >= 0) {
      var pairs = csv.split(',');
      for (var i = 0; i < pairs.length; i++) {
        var kv = pairs[i].trim().split(':');
        if (kv.length === 2 && kv[0] && kv[1]) {
          var v = parseInt(kv[1].trim());
          if (v > 0) map[kv[0].trim()] = v;
        }
      }
    }
    return map;
  }

  function buildBtStratIntervalsCsv(inst) {
    var parts = [];
    for (var k in inst.stratIntervals) {
      if (inst.stratIntervals.hasOwnProperty(k) && inst.stratIntervals[k] > 0) {
        parts.push(k + ':' + inst.stratIntervals[k]);
      }
    }
    return parts.join(',');
  }

  function buildBtEmaFilterCsv(inst) {
    var parts = [];
    for (var k in inst.emaMap) {
      if (inst.emaMap.hasOwnProperty(k)) {
        parts.push(k + ':' + (inst.emaMap[k] || 0));
      }
    }
    return parts.join(',');
  }

  // ── Collect groups from UI ──
  function collectBtGroups() {
    var groups = [];
    for (var i = 0; i < btGroupInstances.length; i++) {
      var inst = btGroupInstances[i];
      var card = inst.el;
      var nameInput = card.querySelector('.group-name-input');

      groups.push({
        groupName: nameInput ? nameInput.value.trim() : ('Group ' + (i + 1)),
        markets: inst.marketMs.getSelected(),
        strategies: inst.stratMs.getSelected(),
        candleUnitMin: parseInt(card.querySelector('.grp-interval').value) || 60,
        orderSizingMode: card.querySelector('.grp-orderMode').value || 'PCT',
        orderSizingValue: parseFloat(card.querySelector('.grp-orderValue').value.replace(/,/g, '')) || 90,
        takeProfitPct: parseFloat(card.querySelector('.grp-tp').value) || 0,
        stopLossPct: parseFloat(card.querySelector('.grp-sl').value) || 0,
        maxAddBuys: parseInt(card.querySelector('.grp-maxAdd').value) || 0,
        minConfidence: parseFloat(card.querySelector('.grp-minConf').value) || 0,
        strategyLock: card.querySelector('.grp-stratLock').classList.contains('on'),
        timeStopMinutes: parseInt(card.querySelector('.grp-timeStop').value) || 0,
        strategyIntervalsCsv: buildBtStratIntervalsCsv(inst),
        emaFilterCsv: buildBtEmaFilterCsv(inst)
      });
    }
    return groups;
  }

  // ═══════════════════════════════════════════════════════════════
  //  Params, Period, Date Range
  // ═══════════════════════════════════════════════════════════════

  function getParams() {
    var groups = collectBtGroups();
    // Collect all markets from groups
    var allMarkets = [];
    groups.forEach(function(g) {
      (g.markets || []).forEach(function(m) { allMarkets.push(m); });
    });
    var market = allMarkets.length > 0 ? allMarkets[0] : 'KRW-BTC';

    return {
      groups: groups,
      // Flat fields for backward compat (BacktestService checks groups first)
      strategies: [],
      period: el('btPeriod').value,
      interval: '60m', // not used when groups present
      market: market,
      markets: allMarkets,
      fromDate: getDateTimeLocalValue(btFromDate, btFromTime),
      toDate: getDateTimeLocalValue(btToDate, btToTime),
      capitalKrw: parseNum(el('btCapital').value)
    };
  }

  // Period -> From/To auto-calculation
  function periodToDays(period) {
    var p = String(period || '').trim();
    if (p === '1\uc77c') return 1;
    if (p === '1\uc8fc') return 7;
    if (p === '1\ub2ec') return 30;
    if (p === '3\ub2ec') return 90;
    var m = p.match(/(\d+)/);
    return m ? Number(m[1]) : 7;
  }

  function pad2(n) { return String(n).length < 2 ? '0' + n : String(n); }

  function toDateValue(d) {
    return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate());
  }

  function toTimeValue(d) {
    return pad2(d.getHours()) + ':' + pad2(d.getMinutes());
  }

  function setDateTimeInputs(dateEl, timeEl, d) {
    if (dateEl) dateEl.value = toDateValue(d);
    if (timeEl) timeEl.value = toTimeValue(d);
  }

  function formatYmd(d) {
    var y = d.getFullYear();
    var m = String(d.getMonth() + 1);
    if (m.length < 2) m = '0' + m;
    var dd = String(d.getDate());
    if (dd.length < 2) dd = '0' + dd;
    return y + '-' + m + '-' + dd;
  }

  function getDateTimeLocalValue(dateEl, timeEl) {
    var date = dateEl ? (dateEl.value || '') : '';
    if (!date) return null;
    return date; // date-only (KST)
  }

  function applyPeriodToRange() {
    if (!btFromDate || !btToDate || !btPeriod) return;
    var days = periodToDays(btPeriod.value);
    if (!days || days <= 0) return;
    var now = new Date();
    var to = new Date(now.getTime());
    var from = new Date(now.getTime());
    from.setDate(from.getDate() - days);
    btFromDate.value = formatYmd(from);
    btToDate.value = formatYmd(to);
    normalizeDateRange();
  }

  function normalizeDateRange() {
    if (!btFromDate || !btToDate) return;
    var f = btFromDate.value;
    var t = btToDate.value;
    if (!f || !t) return;
    if (String(f) > String(t)) {
      btToDate.value = f;
      setError('\uae30\uac04 \uc624\ub958: From \ub0a0\uc9dc\uac00 To \ub0a0\uc9dc\ubcf4\ub2e4 \uc774\ud6c4\uc785\ub2c8\ub2e4. To\ub97c From\uacfc \ub3d9\uc77c\ud558\uac8c \uc790\ub3d9 \uc870\uc815\ud588\uc2b5\ub2c8\ub2e4.');
      window.clearTimeout(normalizeDateRange._tm);
      normalizeDateRange._tm = window.setTimeout(function() { setError(''); }, 2000);
    }
  }

  // ═══════════════════════════════════════════════════════════════
  //  Trade log rendering / sorting / pagination
  // ═══════════════════════════════════════════════════════════════

  var btIsMobile = window.innerWidth <= 640;
  function fmtTs(ts) {
    if (!ts) return '-';
    var d = new Date(ts);
    if (!isNaN(d.getTime())) {
      if (btIsMobile) {
        return pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()) + ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes());
      }
      return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()) + ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + ':' + pad2(d.getSeconds());
    }
    return String(ts);
  }

  function labelAction(a) {
    var x = String(a || '').toUpperCase();
    if (x === 'BUY') return '\ub9e4\uc218';
    if (x === 'SELL') return '\ub9e4\ub3c4';
    if (x === 'ADD_BUY') return '\ucd94\uac00\ub9e4\uc218';
    return a || '-';
  }

  function applySort(list) {
    var k = sort.key;
    var d = sort.dir;
    var out = list.slice();
    out.sort(function(a, b) {
      var av = a ? a[k] : null;
      var bv = b ? b[k] : null;
      if (av == null && bv == null) return 0;
      if (av == null) return 1;
      if (bv == null) return -1;
      if (typeof av === 'number' && typeof bv === 'number') {
        return d === 'asc' ? av - bv : bv - av;
      }
      return d === 'asc' ? String(av).localeCompare(String(bv)) : String(bv).localeCompare(String(av));
    });
    return out;
  }

  function render() {
    var out = logs;
    // Market filter
    var btMarketEl = el('btMarketFilter');
    var marketQ = btMarketEl ? (btMarketEl.value || '').trim().toUpperCase() : '';
    if (marketQ) {
      out = out.filter(function(x) {
        var code = String(x.market || '').toUpperCase();
        var name = (marketLabel.get(String(x.market || '')) || '').toUpperCase();
        return code.indexOf(marketQ) >= 0 || name.indexOf(marketQ) >= 0;
      });
    }
    if (filterAction !== 'ALL') {
      out = out.filter(function(x) { return x.action === filterAction; });
    }
    // Type multiselect filter
    if (btLogTypeMs) {
      var selectedTypes = {};
      var selArr = btLogTypeMs.getSelected();
      for (var si = 0; si < selArr.length; si++) { selectedTypes[selArr[si]] = true; }
      var hasFilter = selArr.length > 0;
      if (hasFilter) {
        out = out.filter(function(x) {
          var type = x.orderType || '';
          return selectedTypes[type];
        });
      }
    }
    out = applySort(out);

    var total = out.length;
    var start = (page - 1) * size;
    var items = out.slice(start, start + size);

    btPagerInfo.textContent = 'page ' + page + ' \u00b7 size ' + size + ' \u00b7 total ' + total;
    btPrev.disabled = page <= 1;
    btNext.disabled = start + size >= total;

    btTbody.innerHTML = '';
    items.forEach(function(r, idx) {
      var tr = document.createElement('tr');
      tr.className = 'chart-row';
      var marketCode = r.market || '-';
      var marketText = marketLabel.get(String(marketCode)) || marketCode;
      var actionText = labelAction(r.action);
      var typeKey = r.orderType || '-';
      var typeText = strategyLabel.get(String(typeKey)) || typeKey;

      // Confidence score
      var conf = r.confidence;
      var confText = (conf != null && conf > 0) ? Number(conf).toFixed(1) : '-';
      var confColor = (conf != null && conf > 0) ? (conf >= 7 ? 'var(--success)' : conf >= 4 ? '#e0a000' : 'var(--danger)') : 'var(--muted)';

      // Sell row: show buy price -> sell price
      var isSell = /SELL/i.test(r.action);
      var avgBuy = r.avgBuyPrice;
      var hasBuyPrice = isSell && avgBuy != null && avgBuy > 0;
      var priceHtml = hasBuyPrice
        ? '<span style="color:var(--muted);font-size:11px">' + fmt(avgBuy) + '</span> \u2192 <span style="font-weight:700">' + fmt(r.price) + '</span>'
        : fmt(r.price);
      // PnL + ROI%
      var roiVal = r.roiPercent;
      var hasRoi = isSell && roiVal != null && roiVal !== 0;
      var pnlColor = Number(r.pnlKrw || 0) >= 0 ? 'var(--success)' : 'var(--danger)';
      var pnlHtml = hasRoi
        ? '<span style="font-weight:700">' + fmt(r.pnlKrw) + '</span> <span style="color:' + pnlColor + ';font-size:11px;font-weight:700">(' + Number(roiVal).toFixed(2) + '%)</span>'
        : fmt(r.pnlKrw);

      tr.innerHTML =
        '<td class="mono">' + fmtTs(r.ts) + '</td>' +
        '<td title="' + escAttr(marketCode) + '">' + marketText + '</td>' +
        '<td><span class="pill ' + (r.action || '') + '">' + actionText + '</span></td>' +
        '<td>' + typeText + '</td>' +
        '<td style="color:' + confColor + ';font-weight:700;text-align:center">' + confText + '</td>' +
        '<td class="num">' + priceHtml + '</td>' +
        '<td class="num">' + fmt(r.qty) + '</td>' +
        '<td class="num ' + (Number(r.pnlKrw || 0) >= 0 ? 'pos' : 'neg') + '">' + pnlHtml + '</td>';

      // Row click -> chart popup
      (function(row, record, tKey) {
        row.addEventListener('click', function() {
          if (!record.market) return;
          var typeLabel2 = strategyLabel.get(String(tKey)) || tKey;
          var unitVal = record.candleUnitMin || getBtCandleUnit();
          if (window.ChartPopup) {
            window.ChartPopup.open({
              market: record.market,
              tsEpochMs: record.ts ? new Date(record.ts).getTime() : Date.now(),
              action: record.action,
              price: record.price,
              qty: record.qty,
              pnlKrw: record.pnlKrw,
              avgBuyPrice: record.avgBuyPrice || 0,
              patternType: tKey,
              patternLabel: typeLabel2,
              candleUnit: unitVal,
              note: record.note || '',
              confidence: record.confidence || 0
            });
          }
        });
      })(tr, r, typeKey);

      btTbody.appendChild(tr);
    });
  }

  // ═══════════════════════════════════════════════════════════════
  //  Intervals (for intervalLabel map used in chart popup display)
  // ═══════════════════════════════════════════════════════════════

  function initIntervals() {
    var sel = el('btInterval');
    return req('/api/intervals', { method: 'GET' }).then(function(list) {
      if (Array.isArray(list) && list.length) {
        intervalLabel = new Map();
        for (var i = 0; i < list.length; i++) {
          intervalLabel.set(String(list[i].key), String(list[i].label));
        }
        if (sel) {
          sel.innerHTML = '';
          for (var i = 0; i < list.length; i++) {
            var o = document.createElement('option');
            o.value = list[i].key;
            o.textContent = list[i].label;
            sel.appendChild(o);
          }
        }
      } else if (sel) {
        intervalLabel = new Map();
        var opts = sel.options;
        for (var i = 0; i < opts.length; i++) {
          intervalLabel.set(String(opts[i].value), String(opts[i].textContent));
        }
      }
      if (sel && Array.prototype.some.call(sel.options, function(o) { return o.value === '5m'; })) {
        sel.value = '5m';
      }
    }).catch(function(e) {
      if (sel) {
        intervalLabel = new Map();
        var opts = sel.options;
        for (var i = 0; i < opts.length; i++) {
          intervalLabel.set(String(opts[i].value), String(opts[i].textContent));
        }
      }
    });
  }

  // ═══════════════════════════════════════════════════════════════
  //  Equity Curve Rendering (LightweightCharts)
  // ═══════════════════════════════════════════════════════════════
  function renderEquityCurve(trades, initialCapital) {
    if (!btEquitySection || !btEquityChart) return;
    if (!trades || trades.length === 0) {
      btEquitySection.style.display = 'none';
      return;
    }

    btEquitySection.style.display = '';

    // Build equity data from trades
    var equity = initialCapital || 100000;
    var data = []; // {time, value}
    var maxEquity = equity, minEquity = equity;

    // Helper: parse ts string or epochMs to unix seconds
    function toUnixSec(t) {
      if (t.tsEpochMs && Number(t.tsEpochMs) > 0) return Math.floor(Number(t.tsEpochMs) / 1000);
      if (t.ts) {
        var d = new Date(String(t.ts).replace(' ', 'T'));
        if (!isNaN(d.getTime())) return Math.floor(d.getTime() / 1000);
      }
      return 0;
    }

    // Sort trades by timestamp
    var sorted = trades.slice().sort(function(a, b) {
      return toUnixSec(a) - toUnixSec(b);
    });

    for (var i = 0; i < sorted.length; i++) {
      var t = sorted[i];
      if ((t.action === 'SELL' || t.action === 'sell') && t.pnlKrw != null) {
        equity += Number(t.pnlKrw);
      }
      var ts = toUnixSec(t);
      if (ts > 0) {
        data.push({ time: ts, value: Math.round(equity) });
        if (equity > maxEquity) maxEquity = equity;
        if (equity < minEquity) minEquity = equity;
      }
    }

    if (data.length === 0) {
      btEquitySection.style.display = 'none';
      return;
    }

    // Deduplicate timestamps (keep last)
    var deduped = [];
    for (var di = 0; di < data.length; di++) {
      if (di === data.length - 1 || data[di].time !== data[di + 1].time) {
        deduped.push(data[di]);
      }
    }

    // Equity meta stats
    if (btEquityMeta) {
      var change = equity - initialCapital;
      var changePct = initialCapital > 0 ? (change / initialCapital * 100) : 0;
      var changeColor = change >= 0 ? 'var(--success)' : 'var(--danger)';
      btEquityMeta.innerHTML =
        '<span class="equity-stat">Max <span style="color:' + changeColor + '">' + fmt(Math.round(maxEquity)) + '</span></span>' +
        '<span class="equity-stat">Min <span>' + fmt(Math.round(minEquity)) + '</span></span>' +
        '<span class="equity-stat">Change <span style="color:' + changeColor + '">' + (change >= 0 ? '+' : '') + changePct.toFixed(2) + '%</span></span>';
    }

    // Use LightweightCharts if available
    if (typeof LightweightCharts !== 'undefined') {
      btEquityChart.innerHTML = '';
      var isDark = !document.body.getAttribute('data-theme') || document.body.getAttribute('data-theme') !== 'light';
      if (btEquityChartInstance) { try { btEquityChartInstance.remove(); } catch(e){} }
      btEquityChartInstance = LightweightCharts.createChart(btEquityChart, {
        width: btEquityChart.clientWidth,
        height: 232,
        layout: { background: { type: 'solid', color: 'transparent' }, textColor: isDark ? '#5a6a94' : '#7a8aaa', fontFamily: 'Inter, sans-serif', fontSize: 11 },
        grid: { vertLines: { color: isDark ? 'rgba(255,255,255,0.03)' : 'rgba(0,0,0,0.04)' }, horzLines: { color: isDark ? 'rgba(255,255,255,0.03)' : 'rgba(0,0,0,0.04)' } },
        rightPriceScale: { borderColor: 'transparent' },
        timeScale: { borderColor: 'transparent', timeVisible: true, secondsVisible: false },
        crosshair: { mode: 0 },
        handleScroll: false, handleScale: false
      });
      var areaSeries = btEquityChartInstance.addAreaSeries({
        topColor: equity >= initialCapital ? 'rgba(32,201,151,0.3)' : 'rgba(255,77,109,0.3)',
        bottomColor: equity >= initialCapital ? 'rgba(32,201,151,0.02)' : 'rgba(255,77,109,0.02)',
        lineColor: equity >= initialCapital ? '#20c997' : '#ff4d6d',
        lineWidth: 2
      });
      areaSeries.setData(deduped);
      btEquityChartInstance.timeScale().fitContent();

      // Responsive resize
      var ro = new ResizeObserver(function() {
        if (btEquityChartInstance) btEquityChartInstance.applyOptions({ width: btEquityChart.clientWidth });
      });
      ro.observe(btEquityChart);
    } else {
      // Fallback: simple text
      btEquityChart.innerHTML = '<div style="color:var(--text-muted);padding:24px;text-align:center">차트 라이브러리를 불러올 수 없습니다.</div>';
    }
  }

  // ═══════════════════════════════════════════════════════════════
  //  Tab Switching
  // ═══════════════════════════════════════════════════════════════

  var btTabs = document.querySelectorAll('.bt-tab');
  var btTabBasic = el('btTabBasic');
  var btTabOpening = el('btTabOpening');

  for (var ti = 0; ti < btTabs.length; ti++) {
    (function(tab) {
      tab.addEventListener('click', function() {
        var target = tab.getAttribute('data-tab');
        activeTab = target;
        for (var j = 0; j < btTabs.length; j++) {
          btTabs[j].classList.toggle('active', btTabs[j].getAttribute('data-tab') === target);
        }
        if (btTabBasic) btTabBasic.style.display = target === 'basic' ? '' : 'none';
        if (btTabOpening) btTabOpening.style.display = target === 'opening' ? '' : 'none';
      });
    })(btTabs[ti]);
  }

  // ═══════════════════════════════════════════════════════════════
  //  Event Handlers
  // ═══════════════════════════════════════════════════════════════

  btRun.addEventListener('click', function() {
    setError('');
    btRun.disabled = true;

    if (activeTab === 'opening') {
      runOpeningBacktest();
      return;
    }

    // Guard: From <= To
    normalizeDateRange();
    if (btFromDate && btToDate && btFromDate.value && btToDate.value && String(btFromDate.value) > String(btToDate.value)) {
      setError('\uae30\uac04 \uc124\uc815 \uc624\ub958: From \ub0a0\uc9dc\uac00 To \ub0a0\uc9dc\ubcf4\ub2e4 \uc774\ud6c4\uc77c \uc218 \uc5c6\uc2b5\ub2c8\ub2e4.');
      btRun.disabled = false;
      return;
    }

    var p = getParams();
    saveBtSettings();
    if (!p.market) {
      setError('Market is required. Example: KRW-BTC');
      btRun.disabled = false;
      return;
    }

    // Use async endpoint to avoid timeout on long-period backtests
    showLoading('\ubc31\ud14c\uc2a4\ud2b8 \uc2e4\ud589 \uc911...');
    req('/api/backtest/run-async', {
      method: 'POST',
      body: JSON.stringify(p),
      cache: 'no-store'
    }).then(function(startRes) {
      if (!startRes || !startRes.jobId) {
        throw new Error('Failed to start async backtest');
      }
      var jobId = startRes.jobId;

      function pollResult() {
        req('/api/backtest/async-result/' + jobId, { method: 'GET' }).then(function(pollRes) {
          if (pollRes && pollRes.status === 'running') {
            setTimeout(pollResult, 3000);
            return;
          }
          hideLoading();
          if (pollRes && pollRes.status === 'error') {
            setError(pollRes.message || 'Backtest failed');
            btRun.disabled = false;
            btRun.textContent = '\ubc31\ud14c\uc2a4\ud2b8 \uc2e4\ud589';
            return;
          }
          displayBasicResult(pollRes);
          btRun.disabled = false;
          btRun.textContent = '\ubc31\ud14c\uc2a4\ud2b8 \uc2e4\ud589';
        }).catch(function(err) {
          hideLoading();
          setError(err.message || 'Failed to fetch backtest result');
          btRun.disabled = false;
          btRun.textContent = '\ubc31\ud14c\uc2a4\ud2b8 \uc2e4\ud589';
        });
      }
      setTimeout(pollResult, 3000);

    }).catch(function(e) {
      hideLoading();
      setError(e.message || String(e));
      btRun.disabled = false;
      btRun.textContent = '\ubc31\ud14c\uc2a4\ud2b8 \uc2e4\ud589';
    });
  });

  function displayBasicResult(res) {
    console.log('[Backtest] response:', JSON.stringify({
      totalReturn: res.totalReturn,
      tradesCount: res.tradesCount,
      roi: res.roi
    }));

    // Show results sections
    if (btResultsHeader) btResultsHeader.style.display = '';
    if (btKpiGrid) btKpiGrid.style.display = '';

    // KPI values
    var roiVal = res.roi == null ? 0 : Number(res.roi);
    btRoi.textContent = (res.roi == null ? '-' : roiVal.toFixed(2) + '%');
    btRoi.style.color = roiVal >= 0 ? 'var(--success)' : 'var(--danger)';

    btTotalReturn.textContent = fmt(res.totalReturn);
    if (res.totalReturn != null) {
      btTotalReturn.style.color = res.totalReturn >= 0 ? 'var(--success)' : 'var(--danger)';
    }

    btTrades.textContent = fmt(res.tradesCount);

    // Win rate
    var wr = res.winRate == null ? 0 : Number(res.winRate);
    btWinRate.textContent = wr.toFixed(1) + '%';
    if (btWinRateText) btWinRateText.textContent = Math.round(wr) + '%';
    // Win rate circle animation
    if (btWinRateCircle) {
      var circleFg = btWinRateCircle.querySelector('.circle-fg');
      if (circleFg) {
        var circumference = 157; // 2 * PI * 25
        circleFg.style.strokeDashoffset = circumference - (circumference * wr / 100);
      }
    }

    // Results badge
    if (btResultsBadge) {
      var tc = res.tradesCount || 0;
      btResultsBadge.textContent = tc + '\uac74 \uac70\ub798';
      btResultsBadge.style.background = roiVal >= 0 ? 'var(--success-bg)' : 'var(--danger-bg)';
      btResultsBadge.style.color = roiVal >= 0 ? 'var(--success)' : 'var(--danger)';
      btResultsBadge.style.borderColor = roiVal >= 0 ? 'rgba(32,201,151,0.2)' : 'rgba(255,77,109,0.2)';
    }

    // Final capital
    if (btFinalCapital) {
      var initialCap = parseNum(el('btCapital').value);
      var finalCap = initialCap + (res.totalReturn || 0);
      btFinalCapital.textContent = fmt(Math.round(finalCap));
    }

    // Distribution counts (TP/SL/Pattern)
    var trades = res.trades || [];
    var tpCount = 0, slCount = 0, patternCount = 0;
    for (var ti = 0; ti < trades.length; ti++) {
      var t = trades[ti];
      if (t.action === 'SELL' || t.action === 'sell') {
        var ot = (t.orderType || t.patternType || '').toUpperCase();
        if (ot.indexOf('TAKE_PROFIT') >= 0 || ot.indexOf('TP') >= 0) tpCount++;
        else if (ot.indexOf('STOP_LOSS') >= 0 || ot.indexOf('SL') >= 0 || ot.indexOf('TIME_STOP') >= 0) slCount++;
        else patternCount++;
      }
    }
    var distTotal = Math.max(tpCount + slCount + patternCount, 1);
    if (btDistTp) btDistTp.style.width = (tpCount / distTotal * 100) + '%';
    if (btDistSl) btDistSl.style.width = (slCount / distTotal * 100) + '%';
    if (btDistPattern) btDistPattern.style.width = (patternCount / distTotal * 100) + '%';
    if (btDistTpCount) btDistTpCount.textContent = tpCount;
    if (btDistSlCount) btDistSlCount.textContent = slCount;
    if (btDistPatternCount) btDistPatternCount.textContent = patternCount;

    // Equity curve
    renderEquityCurve(trades, parseNum(el('btCapital').value));

    logs = trades;
    page = 1;
    render();

    if ((res.tradesCount || 0) === 0) {
      var info = [];
      if (res.candleCount != null) info.push('\uce94\ub4e4: ' + res.candleCount + '\uac1c');
      if (res.candleUnitMin != null) info.push('\ub2e8\uc704: ' + res.candleUnitMin + '\ubd84');
      if (res.periodDays != null) info.push('\uae30\uac04: ' + res.periodDays + '\uc77c');
      setError('\uac70\ub798 0\uac74 (\uc2e0\ud638 \uc5c6\uc74c). ' + info.join(' \u00b7 ') + '\n\uc804\ub7b5/\uae30\uac04/\ubd84\ubd09\uc744 \ubc14\uafb8\uba74 \uac70\ub798\uac00 \ubc1c\uc0dd\ud560 \uc218 \uc788\uc2b5\ub2c8\ub2e4.');
      // Hide results sections if no trades
      if (btResultsHeader) btResultsHeader.style.display = 'none';
      if (btKpiGrid) btKpiGrid.style.display = 'none';
      if (btEquitySection) btEquitySection.style.display = 'none';
    }
  }

  // Date range validation on user input
  if (btFromDate) btFromDate.addEventListener('change', normalizeDateRange);
  if (btToDate) btToDate.addEventListener('change', normalizeDateRange);
  if (btFromDate) btFromDate.addEventListener('blur', normalizeDateRange);
  if (btToDate) btToDate.addEventListener('blur', normalizeDateRange);

  btReset.addEventListener('click', function() {
    logs = [];
    page = 1;
    btTotalReturn.textContent = '-';
    btTotalReturn.style.color = '';
    btRoi.textContent = '-';
    btRoi.style.color = '';
    btTrades.textContent = '-';
    btWinRate.textContent = '-';
    // Hide results sections
    if (btResultsHeader) btResultsHeader.style.display = 'none';
    if (btKpiGrid) btKpiGrid.style.display = 'none';
    if (btEquitySection) btEquitySection.style.display = 'none';
    if (btEquityChartInstance) { try { btEquityChartInstance.remove(); } catch(e){} btEquityChartInstance = null; }
    render();
  });

  btPrev.addEventListener('click', function() { if (page > 1) { page--; render(); } });
  btNext.addEventListener('click', function() { page++; render(); });

  var sortHeaders = document.querySelectorAll('#btTable th[data-sort]');
  for (var hi = 0; hi < sortHeaders.length; hi++) {
    (function(th) {
      th.addEventListener('click', function() {
        var key = th.getAttribute('data-sort');
        if (sort.key !== key) sort = { key: key, dir: 'asc' };
        else if (sort.dir === 'asc') sort = { key: key, dir: 'desc' };
        else sort = { key: 'ts', dir: 'desc' };
        render();
      });
    })(sortHeaders[hi]);
  }

  // market filter (input)
  var btMarketFilterEl = el('btMarketFilter');
  if (btMarketFilterEl) {
    btMarketFilterEl.addEventListener('input', function() { page = 1; render(); });
  }

  // action filter (select)
  el('btActionFilter').addEventListener('change', function(e) {
    filterAction = e.target.value;
    page = 1;
    render();
  });

  // page size
  el('btSize').addEventListener('change', function(e) {
    size = Number(e.target.value);
    page = 1;
    render();
  });

  // Add group button
  document.getElementById('btAddGroupBtn').addEventListener('click', function() {
    addBtGroupCard(null);
  });

  // ── Import from Settings ──
  document.getElementById('importFromSettings').addEventListener('click', function() {
    req('/api/bot/groups').then(function(groups) {
      if (!groups || !groups.length) { showToast('저장된 설정이 없습니다', 'error'); return; }
      if (!confirm('설정 ' + groups.length + '개 그룹을 불러오시겠습니까?\n현재 백테스트 설정이 대체됩니다.')) return;
      // Clear existing groups and instances
      var container = document.getElementById('btGroupsContainer');
      container.innerHTML = '';
      btGroupCounter = 0;
      btGroupInstances = [];
      // Render imported groups
      for (var i = 0; i < groups.length; i++) {
        addBtGroupCard(groups[i]);
      }
      saveBtSettings();
      showToast('설정 ' + groups.length + '개 그룹을 불러왔습니다.', 'success');
    }).catch(function(e) {
      showToast('불러오기 실패: ' + (e.message || e), 'error');
    });
  });

  // ═══════════════════════════════════════════════════════════════
  //  Initialization
  // ═══════════════════════════════════════════════════════════════

  (function() {
    var initPromise = Promise.resolve();

    // 1. Load strategy catalog + strategy label map
    initPromise = initPromise.then(function() {
      return req('/api/strategies').then(function(list) {
        btStrategyCatalog = list || [];
        btAllStrategyData = list || [];
        allStrategyOpts = (list || []).map(function(x) { return { value: x.key, label: x.label }; });
        strategyLabel = new Map();
        for (var i = 0; i < (list || []).length; i++) {
          strategyLabel.set(String(list[i].key), String(list[i].label));
        }
        strategyLabel.set('TAKE_PROFIT', '\uc775\uc808(TP)');
        strategyLabel.set('STOP_LOSS', '\uc190\uc808(SL)');
        strategyLabel.set('TIME_STOP', '\uc2dc\uac04\ucd08\uacfc');
        strategyLabel.set('STRATEGY_LOCK', '\uc804\ub7b5\uc7a0\uae08');
        strategyLabel.set('LOW_CONFIDENCE', '\uc2e0\ub8b0\ub3c4\ubbf8\ub2ec');
      }).catch(function(e) {
        strategyLabel = new Map();
        strategyLabel.set('TAKE_PROFIT', '\uc775\uc808(TP)');
        strategyLabel.set('STOP_LOSS', '\uc190\uc808(SL)');
        strategyLabel.set('TIME_STOP', '\uc2dc\uac04\ucd08\uacfc');
        strategyLabel.set('STRATEGY_LOCK', '\uc804\ub7b5\uc7a0\uae08');
        strategyLabel.set('LOW_CONFIDENCE', '\uc2e0\ub8b0\ub3c4\ubbf8\ub2ec');
      });
    });

    // 2. Strategy help binding (legacy, not needed now but kept for compatibility)
    initPromise = initPromise.then(function() {
      try { AutoTrade.bindStrategyHelp(document.getElementById('btStrategyHelpBtn')); } catch (e) { /* ignore */ }
    });

    // 3. Load intervals (for intervalLabel map)
    initPromise = initPromise.then(function() {
      return initIntervals();
    });

    // 4. Load markets
    initPromise = initPromise.then(function() {
      return req('/api/bot/markets', { method: 'GET' }).then(function(mlist) {
        var all = Array.isArray(mlist) ? mlist : [];
        allMarketOpts = all.map(function(m) { return { value: m.market, label: m.displayName || m.market }; });
        marketLabel = new Map();
        for (var i = 0; i < all.length; i++) {
          marketLabel.set(String(all[i].market), String(all[i].displayName || all[i].market));
        }
        // Init opening markets multi-select with ALL KRW markets (not just bot-configured ones)
        var obMsRoot = document.getElementById('obMarketsMs');
        if (obMsRoot) {
          // Load all KRW markets from top-markets API (topN=200 to get all)
          return req('/api/scanner/top-markets?topN=200', { method: 'GET' }).then(function(topList) {
            var topArr = Array.isArray(topList) ? topList : [];
            var obOpts = topArr.map(function(m) { return { value: m.market, label: m.displayName || m.market }; });
            // Enrich marketLabel map with all KRW markets
            for (var i = 0; i < topArr.length; i++) {
              if (!marketLabel.has(String(topArr[i].market))) {
                marketLabel.set(String(topArr[i].market), String(topArr[i].displayName || topArr[i].market));
              }
            }
            obMarketsMs = initMultiSelect(obMsRoot, {
              placeholder: 'Select markets...',
              options: obOpts.length > 0 ? obOpts : allMarketOpts,
              initial: ['KRW-SOL', 'KRW-ADA']
            });
            // Setup TOP N button
            initTopNButton();
          }).catch(function() {
            // Fallback to bot-configured markets
            obMarketsMs = initMultiSelect(obMsRoot, {
              placeholder: 'Select markets...',
              options: allMarketOpts,
              initial: ['KRW-SOL', 'KRW-ADA']
            });
          });
        }
      }).catch(function(e) { /* ignore */ });
    });

    // 5. Load saved groups or API groups
    initPromise = initPromise.then(function() {
      var saved = loadBtSavedSettings();

      // If we have saved settings with groups, use those
      if (saved && saved.groups && saved.groups.length > 0) {
        for (var i = 0; i < saved.groups.length; i++) {
          addBtGroupCard(saved.groups[i]);
        }
        // Restore capital and period from saved settings
        if (saved.capitalKrw) el('btCapital').value = Number(saved.capitalKrw).toLocaleString();
        if (saved.period && el('btPeriod')) el('btPeriod').value = saved.period;
        return Promise.resolve();
      }

      // Otherwise load saved groups from Settings page as defaults
      return req('/api/bot/groups').then(function(groups) {
        if (groups && groups.length > 0) {
          for (var i = 0; i < groups.length; i++) {
            addBtGroupCard(groups[i]);
          }
        } else {
          addBtGroupCard(null); // Empty default group
        }
      }).catch(function(e) {
        addBtGroupCard(null);
      });
    });

    // 6. Trade log type filter init
    initPromise = initPromise.then(function() {
      var SYSTEM_TYPES = [
        { value: 'TAKE_PROFIT', label: '\uc775\uc808(TP)' },
        { value: 'STOP_LOSS', label: '\uc190\uc808(SL)' },
        { value: 'TIME_STOP', label: '\uc2dc\uac04\ucd08\uacfc' },
        { value: 'STRATEGY_LOCK', label: '\uc804\ub7b5\uc7a0\uae08' },
        { value: 'LOW_CONFIDENCE', label: '\uc2e0\ub8b0\ub3c4\ubbf8\ub2ec' }
      ];
      var opts = (btStrategyCatalog || []).map(function(x) { return { value: x.key, label: x.label }; });
      opts = opts.concat(SYSTEM_TYPES);
      var initial = opts.filter(function(o) { return o.value !== 'STRATEGY_LOCK'; }).map(function(o) { return o.value; });
      var root = el('btLogTypeMs');
      if (root) {
        btLogTypeMs = initMultiSelect(root, {
          placeholder: 'Type filter',
          options: opts,
          initial: initial,
          onChange: function() { page = 1; render(); }
        });
      }
    });

    // 7. Capital from bot status
    initPromise = initPromise.then(function() {
      return req(API.botStatus, { method: 'GET' }).then(function(s) {
        if (s && s.capitalKrw) {
          // Only set capital if not already restored from localStorage
          var saved = loadBtSavedSettings();
          if (!saved || !saved.capitalKrw) {
            el('btCapital').value = Number(s.capitalKrw).toLocaleString();
          }
        }
      }).catch(function(e) { /* ignore */ });
    });

    // 8. Final setup
    initPromise = initPromise.then(function() {
      // Period -> date range
      if (btPeriod) {
        btPeriod.addEventListener('change', function() {
          applyPeriodToRange();
        });
      }
      applyPeriodToRange();

      // Input formatting
      formatInputWithCommas(el('btCapital'));

      render();
    });
  })();

  // ═══════════════════════════════════════════
  //  Opening Strategy Backtest (tab-integrated)
  // ═══════════════════════════════════════════

  var obLoadSettings = document.getElementById('obLoadSettings');

  function parseHHMM(str) {
    if (!str) return [0, 0];
    var parts = String(str).split(':');
    return [parseInt(parts[0]) || 0, parseInt(parts[1]) || 0];
  }

  if (obLoadSettings) {
    obLoadSettings.addEventListener('click', function() {
      req('/api/scanner/config', { method: 'GET' }).then(function(cfg) {
        function fmtHM(h, m) { return String(h).padStart(2, '0') + ':' + String(m).padStart(2, '0'); }
        var e = function(id) { return document.getElementById(id); };
        if (e('obRangeStart')) e('obRangeStart').value = fmtHM(cfg.rangeStartHour, cfg.rangeStartMin);
        if (e('obRangeEnd')) e('obRangeEnd').value = fmtHM(cfg.rangeEndHour, cfg.rangeEndMin);
        if (e('obEntryStart')) e('obEntryStart').value = fmtHM(cfg.entryStartHour, cfg.entryStartMin);
        if (e('obEntryEnd')) e('obEntryEnd').value = fmtHM(cfg.entryEndHour, cfg.entryEndMin);
        if (e('obSessionEnd')) e('obSessionEnd').value = fmtHM(cfg.sessionEndHour, cfg.sessionEndMin);
        if (e('obTpAtr')) e('obTpAtr').value = cfg.tpAtrMult || 1.2;
        if (e('obSlPct')) e('obSlPct').value = cfg.slPct || 10;
        if (e('obTrailAtr')) e('obTrailAtr').value = cfg.trailAtrMult || 0.8;
        if (e('obVolMult')) e('obVolMult').value = cfg.volumeMult || 1.5;
        if (e('obCandleUnit')) e('obCandleUnit').value = String(cfg.candleUnitMin || 5);
        showToast('Scanner settings loaded', 'success');
      }).catch(function(err) {
        showToast('Failed to load scanner settings', 'error');
      });
    });
  }

  function runOpeningBacktest() {
    var e = function(id) { return document.getElementById(id); };
    var markets = obMarketsMs ? obMarketsMs.getSelected() : [];
    if (markets.length === 0) {
      setError('Opening Backtest: Market을 선택해주세요.');
      btRun.disabled = false;
      return;
    }

    var rs = parseHHMM(e('obRangeStart') ? e('obRangeStart').value : '08:00');
    var re = parseHHMM(e('obRangeEnd') ? e('obRangeEnd').value : '08:59');
    var es = parseHHMM(e('obEntryStart') ? e('obEntryStart').value : '09:05');
    var ee = parseHHMM(e('obEntryEnd') ? e('obEntryEnd').value : '10:30');
    var se = parseHHMM(e('obSessionEnd') ? e('obSessionEnd').value : '12:00');
    var candleUnit = parseInt(e('obCandleUnit') ? e('obCandleUnit').value : '5') || 5;

    normalizeDateRange();

    var params = {
      strategies: ['SCALP_OPENING_BREAK'],
      markets: markets,
      market: markets[0],
      period: el('btPeriod').value,
      fromDate: getDateTimeLocalValue(btFromDate, btFromTime),
      toDate: getDateTimeLocalValue(btToDate, btToTime),
      capitalKrw: parseNum(el('btCapital').value),
      candleUnitMin: candleUnit,
      takeProfitPct: 0,
      stopLossPct: 0,
      maxAddBuysGlobal: 0,
      timeStopMinutes: 0,
      openingParams: {
        rangeStartHour: rs[0], rangeStartMin: rs[1],
        rangeEndHour: re[0], rangeEndMin: re[1],
        entryStartHour: es[0], entryStartMin: es[1],
        entryEndHour: ee[0], entryEndMin: ee[1],
        sessionEndHour: se[0], sessionEndMin: se[1],
        tpAtrMult: parseFloat(e('obTpAtr') ? e('obTpAtr').value : '1.2') || 1.2,
        slPct: parseFloat(e('obSlPct') ? e('obSlPct').value : '10') || 10,
        trailAtrMult: parseFloat(e('obTrailAtr') ? e('obTrailAtr').value : '0.8') || 0.8,
        volumeMult: parseFloat(e('obVolMult') ? e('obVolMult').value : '1.5') || 1.5,
        minBodyRatio: 0.40
      }
    };

    // Use async endpoint to avoid nginx 504 timeout on long backtests
    showLoading('\uc624\ud504\ub2dd \ubc31\ud14c\uc2a4\ud2b8 \uc2e4\ud589 \uc911...');
    req('/api/backtest/run-async', {
      method: 'POST',
      body: JSON.stringify(params),
      cache: 'no-store'
    }).then(function(startRes) {
      if (!startRes || !startRes.jobId) {
        throw new Error('Failed to start async backtest');
      }
      var jobId = startRes.jobId;

      function pollResult() {
        req('/api/backtest/async-result/' + jobId, { method: 'GET' }).then(function(pollRes) {
          if (pollRes && pollRes.status === 'running') {
            setTimeout(pollResult, 3000);
            return;
          }
          hideLoading();
          if (pollRes && pollRes.status === 'error') {
            setError(pollRes.message || 'Opening backtest failed');
            btRun.disabled = false;
            btRun.textContent = '\ubc31\ud14c\uc2a4\ud2b8 \uc2e4\ud589';
            return;
          }
          displayOpeningResult(pollRes, markets);
          btRun.disabled = false;
          btRun.textContent = '\ubc31\ud14c\uc2a4\ud2b8 \uc2e4\ud589';
        }).catch(function(err) {
          hideLoading();
          setError(err.message || 'Failed to fetch backtest result');
          btRun.disabled = false;
          btRun.textContent = '\ubc31\ud14c\uc2a4\ud2b8 \uc2e4\ud589';
        });
      }
      setTimeout(pollResult, 3000);

    }).catch(function(err) {
      hideLoading();
      setError(err.message || 'Opening backtest failed');
      btRun.disabled = false;
      btRun.textContent = '\ubc31\ud14c\uc2a4\ud2b8 \uc2e4\ud589';
    });
  }

  // ===== TOP N auto-select button =====
  function initTopNButton() {
    var btn = document.getElementById('obTopNBtn');
    var info = document.getElementById('obTopNInfo');
    if (!btn) return;
    btn.addEventListener('click', function() {
      btn.disabled = true;
      btn.textContent = '\u23F3 조회중...';
      if (info) { info.style.display = 'none'; info.textContent = ''; }
      req('/api/scanner/top-markets?topN=15', { method: 'GET' }).then(function(topList) {
        var arr = Array.isArray(topList) ? topList : [];
        if (arr.length === 0) {
          btn.textContent = '\uD83D\uDD04 TOP N';
          btn.disabled = false;
          if (info) { info.textContent = '\uB9C8\uCF13 \uC870\uD68C \uC2E4\uD328'; info.style.display = 'inline'; }
          return;
        }
        // Select only the TOP N markets
        var topCodes = arr.map(function(m) { return m.market; });
        if (obMarketsMs) {
          obMarketsMs.setSelected(topCodes);
        }
        // Enrich marketLabel map
        for (var k = 0; k < arr.length; k++) {
          if (!marketLabel.has(String(arr[k].market))) {
            marketLabel.set(String(arr[k].market), String(arr[k].displayName || arr[k].market));
          }
        }
        btn.textContent = '\uD83D\uDD04 TOP N';
        btn.disabled = false;
        if (info) {
          info.textContent = '24h \uAC70\uB798\uB300\uAE08 TOP ' + arr.length + ' (\uBCF4\uC720\uCF54\uC778 \uC81C\uC678)';
          info.style.display = 'inline';
        }
      }).catch(function(e) {
        btn.textContent = '\uD83D\uDD04 TOP N';
        btn.disabled = false;
        if (info) { info.textContent = '\uC870\uD68C \uC2E4\uD328'; info.style.display = 'inline'; }
      });
    });
  }

  function displayOpeningResult(res, markets) {
    if (btResultsHeader) btResultsHeader.style.display = '';
    if (btKpiGrid) btKpiGrid.style.display = '';

    var roiVal = res.roi == null ? 0 : Number(res.roi);
    btRoi.textContent = (res.roi == null ? '-' : roiVal.toFixed(2) + '%');
    btRoi.style.color = roiVal >= 0 ? 'var(--success)' : 'var(--danger)';
    btTotalReturn.textContent = fmt(res.totalReturn);
    if (res.totalReturn != null) btTotalReturn.style.color = res.totalReturn >= 0 ? 'var(--success)' : 'var(--danger)';
    btTrades.textContent = fmt(res.tradesCount);

    var wr = res.winRate == null ? 0 : Number(res.winRate);
    btWinRate.textContent = wr.toFixed(1) + '%';
    if (btWinRateText) btWinRateText.textContent = Math.round(wr) + '%';
    if (btWinRateCircle) {
      var circleFg = btWinRateCircle.querySelector('.circle-fg');
      if (circleFg) {
        var c = 2 * Math.PI * 25;
        circleFg.style.strokeDasharray = c;
        circleFg.style.strokeDashoffset = c * (1 - wr / 100);
      }
    }
    if (btFinalCapital) {
      var initialCap = parseNum(el('btCapital').value);
      var finalCap = initialCap + (res.totalReturn || 0);
      btFinalCapital.textContent = fmt(Math.round(finalCap));
    }

    var trades = res.trades || [];
    var tpCount = 0, slCount = 0, patternCount = 0;
    for (var ti = 0; ti < trades.length; ti++) {
      var t = trades[ti];
      if (t.action === 'SELL' || t.action === 'sell') {
        var ot = (t.orderType || t.patternType || '').toUpperCase();
        if (ot.indexOf('TAKE_PROFIT') >= 0 || ot.indexOf('TP') >= 0) tpCount++;
        else if (ot.indexOf('STOP_LOSS') >= 0 || ot.indexOf('SL') >= 0 || ot.indexOf('TIME_STOP') >= 0) slCount++;
        else patternCount++;
      }
    }
    var distTotal = Math.max(tpCount + slCount + patternCount, 1);
    if (btDistTp) btDistTp.style.width = (tpCount / distTotal * 100) + '%';
    if (btDistSl) btDistSl.style.width = (slCount / distTotal * 100) + '%';
    if (btDistPattern) btDistPattern.style.width = (patternCount / distTotal * 100) + '%';
    if (btDistTpCount) btDistTpCount.textContent = tpCount;
    if (btDistSlCount) btDistSlCount.textContent = slCount;
    if (btDistPatternCount) btDistPatternCount.textContent = patternCount;

    if (btResultsBadge) {
      btResultsBadge.textContent = 'Opening | ' + (res.candleUnitMin || 5) + 'min | ' + markets.join(',');
      btResultsBadge.style.background = roiVal >= 0 ? 'var(--success-bg)' : 'var(--danger-bg)';
      btResultsBadge.style.color = roiVal >= 0 ? 'var(--success)' : 'var(--danger)';
      btResultsBadge.style.borderColor = roiVal >= 0 ? 'rgba(32,201,151,0.2)' : 'rgba(255,77,109,0.2)';
      btResultsBadge.style.display = '';
    }

    logs = trades;
    page = 1;
    render();
    renderEquityCurve(trades, parseNum(el('btCapital').value));
  }

})();
