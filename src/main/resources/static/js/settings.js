/**
 * settings.js — Strategy Groups settings page
 *
 * - Load/save strategy groups via /api/bot/groups
 * - Dynamic group cards with per-group strategy/market/risk settings
 * - Market mutual exclusion between groups
 * - Strategy help popup (? icon → modal with full descriptions)
 * - Strategy Detail Settings popup (⚙ icon → per-strategy interval & EMA)
 * - Tooltip help icons on all fields
 */
;(function(){
  'use strict';

  var req = AutoTrade.req;
  var fmt = AutoTrade.fmt;
  var showToast = AutoTrade.showToast;

  // ── State ──
  var allStrategyOpts = [];     // [{value, label}]
  var allMarketOpts = [];       // [{value, label}]
  var allStrategyData = [];     // full API [{key, label, desc, role, recommendedInterval, emaFilterMode, recommendedEma}]
  var groupInstances = [];      // [{idx, stratMs, marketMs, el, stratIntervals:{}, emaMap:{}}]
  var groupCounter = 0;

  // ── Modal close: global handler for data-modal-close ──
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

  // ── Tab Switching ──
  var settingsTabs = document.querySelectorAll('.bt-tab');
  var settingsTabBasic = document.getElementById('settingsTabBasic');
  var settingsTabOpening = document.getElementById('settingsTabOpening');
  var settingsTabAllday = document.getElementById('settingsTabAllday');
  var settingsTabMorningRush = document.getElementById('settingsTabMorningRush');

  for (var ti = 0; ti < settingsTabs.length; ti++) {
    (function(tab) {
      tab.addEventListener('click', function() {
        var target = tab.getAttribute('data-tab');
        for (var j = 0; j < settingsTabs.length; j++) {
          settingsTabs[j].classList.toggle('active', settingsTabs[j].getAttribute('data-tab') === target);
        }
        if (settingsTabBasic) settingsTabBasic.style.display = (target === 'basic') ? '' : 'none';
        if (settingsTabOpening) settingsTabOpening.style.display = (target === 'opening') ? '' : 'none';
        if (settingsTabAllday) settingsTabAllday.style.display = (target === 'allday') ? '' : 'none';
        if (settingsTabMorningRush) settingsTabMorningRush.style.display = (target === 'morningRush') ? '' : 'none';
      });
    })(settingsTabs[ti]);
  }

  // ── Init ──
  AutoTrade.initTheme();

  // Logout
  var logoutBtn = document.getElementById('logoutBtn');
  if (logoutBtn) {
    logoutBtn.addEventListener('click', function() {
      fetch(AutoTrade.basePath + '/api/auth/logout', { method: 'POST' }).then(function() {
        window.location.href = AutoTrade.basePath + '/login?logout';
      }).catch(function() {
        window.location.href = AutoTrade.basePath + '/login';
      });
    });
  }

  // ── Load data ──
  init();

  async function init() {
    try {
      var results = await Promise.all([
        req('/api/strategies'),
        req('/api/bot/markets'),
        req('/api/bot/status'),
        req('/api/bot/groups')
      ]);

      var strategies = results[0];
      var markets = results[1];
      var status = results[2];
      var groups = results[3];

      allStrategyData = strategies || [];
      allStrategyOpts = (strategies || []).map(function(s) {
        return { value: s.key || s.name || s.value, label: s.label || s.key || s.name || s.value };
      });

      allMarketOpts = (markets || []).map(function(m) {
        return { value: m.market, label: m.displayName || m.market };
      });

      if (status) {
        var modeEl = document.getElementById('mode');
        if (modeEl && status.mode) modeEl.value = status.mode;
        var capEl = document.getElementById('capital');
        if (capEl && status.capitalKrw) capEl.value = fmt(status.capitalKrw);
      }

      // 업비트 실제 잔고 표시 (API 키가 설정된 경우)
      try {
        var keyResult = await req('/api/keys/test', { method: 'POST', body: '{}' });
        if (keyResult && keyResult.krwBalance) {
          var bal = parseFloat(keyResult.krwBalance) || 0;
          var hint = document.getElementById('balanceHint');
          var amt = document.getElementById('balanceAmt');
          if (hint && amt) {
            amt.textContent = fmt(bal);
            hint.style.display = 'block';
          }
        }
      } catch (e) { /* API 키 미설정 시 무시 */ }

      if (groups && groups.length > 0) {
        for (var i = 0; i < groups.length; i++) {
          addGroupCard(groups[i]);
        }
      } else {
        addGroupCard(null);
      }

      try { AutoTrade.normalizeTooltips(document); } catch (e) {}
    } catch (err) {
      showToast('Failed to load settings: ' + (err.message || err), 'error');
    }
  }

  // ── Add Group Button ──
  document.getElementById('addGroupBtn').addEventListener('click', function() {
    addGroupCard(null);
  });

  // ── Import from Backtest ──
  document.getElementById('importFromBt').addEventListener('click', function() {
    try {
      var raw = localStorage.getItem('bt_settings_v1');
      if (!raw) { showToast('백테스트에 저장된 설정이 없습니다', 'error'); return; }
      var data = JSON.parse(raw);
      var groups = data && data.groups;
      if (!groups || !groups.length) { showToast('백테스트에 저장된 그룹이 없습니다', 'error'); return; }
      if (!confirm('백테스트 설정 ' + groups.length + '개 그룹을 불러오시겠습니까?\n현재 그룹 설정이 대체됩니다.')) return;
      // Clear existing groups
      var container = document.getElementById('groupsContainer');
      container.innerHTML = '';
      groupCounter = 0;
      // Render imported groups
      for (var i = 0; i < groups.length; i++) {
        addGroupCard(groups[i]);
      }
      showToast('백테스트 설정 ' + groups.length + '개 그룹을 불러왔습니다. Apply Settings를 눌러 저장하세요.', 'success');
    } catch (e) {
      showToast('불러오기 실패: ' + (e.message || e), 'error');
    }
  });

  // ── Create Group Card ──
  function addGroupCard(groupData) {
    var idx = groupCounter++;
    var container = document.getElementById('groupsContainer');

    var card = document.createElement('div');
    card.className = 'strategy-group-card';
    card.setAttribute('data-group-idx', idx);

    var defaultName = groupData ? groupData.groupName : ('Group ' + (groupInstances.length + 1));
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
      emaMap = parseEmaMap(groupData.emaFilterCsv);
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
            '<label>Markets <span class="help-icon" data-tooltip="이 그룹에서 트레이딩할 마켓을 선택합니다.\\n한 마켓은 하나의 그룹에만 속할 수 있습니다." aria-label="Markets help"></span></label>' +
            '<div class="ms" id="grpMarketMs_' + idx + '">' +
              '<button type="button" class="ms-button"><div class="ms-value"><span class="ms-placeholder">Select markets...</span><div class="ms-chips"></div></div><span class="ms-caret">&#9662;</span></button>' +
              '<div class="ms-panel"><input class="ms-search" placeholder="Search market..."/><div class="ms-list"></div>' +
              '<div class="ms-footer"><button type="button" class="ms-link" data-ms="all">Select all</button><button type="button" class="ms-link" data-ms="none">Clear</button></div></div>' +
            '</div>' +
          '</div>' +
          '<div class="field" style="min-width:260px">' +
            '<label class="label-row">' +
              'Strategies ' +
              '<span class="help-icon strategy-help-click grp-strat-help" data-tooltip="? 클릭 → 전략 상세 설명" aria-label="Strategy descriptions" tabindex="0" role="button" style="cursor:pointer"></span>' +
              '<button type="button" class="pill small grp-strat-detail-btn" style="font-size:10px;padding:2px 8px;margin-left:4px;vertical-align:middle" title="전략별 인터벌/EMA 상세 설정">⚙ 상세</button>' +
            '</label>' +
            '<div class="ms" id="grpStratMs_' + idx + '">' +
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

    // ── Init MultiSelects (no intervalDefaults inside dropdown) ──
    var availableMarkets = getAvailableMarkets(idx);
    var marketMs = AutoTrade.initMultiSelect(
      document.getElementById('grpMarketMs_' + idx),
      {
        placeholder: 'Select markets...',
        options: availableMarkets,
        initial: groupData ? (groupData.markets || []) : [],
        onChange: function() { updateAllMarketOptions(); }
      }
    );

    var stratMs = AutoTrade.initMultiSelect(
      document.getElementById('grpStratMs_' + idx),
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
      // Interval chips
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
        openStratDescModal();
      });
    }

    // ── Strategy Detail Settings Button (⚙ → per-strategy interval/EMA popup) ──
    var detailBtn = card.querySelector('.grp-strat-detail-btn');
    if (detailBtn) {
      detailBtn.addEventListener('click', function(e) {
        e.preventDefault();
        e.stopPropagation();
        openStratDetailModal(inst);
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
        if (groupInstances.length <= 1) {
          showToast('At least one group is required.', 'error');
          return;
        }
        card.remove();
        groupInstances = groupInstances.filter(function(g) { return g.idx !== idx; });
        updateAllMarketOptions();
      });
    }

    var inst = {
      idx: idx,
      el: card,
      stratMs: stratMs,
      marketMs: marketMs,
      stratIntervals: stratIntervals,
      emaMap: emaMap,
      selectedPreset: (groupData && groupData.selectedPreset) ? groupData.selectedPreset : null
    };
    groupInstances.push(inst);

    // ── Preset Bar (restore saved preset chips) ──
    AutoTrade.bindPresetBar(card, inst);
    if (inst.selectedPreset) {
      AutoTrade.restorePresetChips(card, inst.selectedPreset);
    }

    try { AutoTrade.normalizeTooltips(card); } catch (e) {}
    updateDeleteButtons();
    return inst;
  }

  // ══════════════════════════════════════════════════
  //  Strategy Descriptions Modal (? icon)
  // ══════════════════════════════════════════════════
  function openStratDescModal() {
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

  // ══════════════════════════════════════════════════
  //  Strategy Detail Settings Modal (⚙ icon)
  // ══════════════════════════════════════════════════
  var currentDetailInst = null;
  var INTERVAL_OPTIONS = [
    {v:0,l:'기본값'},{v:1,l:'1m'},{v:3,l:'3m'},{v:5,l:'5m'},{v:10,l:'10m'},
    {v:15,l:'15m'},{v:30,l:'30m'},{v:60,l:'1h'},{v:120,l:'2h'},{v:240,l:'4h'}
  ];

  function openStratDetailModal(inst) {
    currentDetailInst = inst;
    var modal = document.getElementById('stratDetailModal');
    if (!modal) return;
    var body = modal.querySelector('.modal-body');
    if (!body) return;

    var selected = inst.stratMs.getSelected();
    if (selected.length === 0) {
      showToast('먼저 전략을 선택해주세요.', 'error');
      return;
    }

    // Build strategy data lookup
    var dataMap = {};
    for (var i = 0; i < allStrategyData.length; i++) {
      dataMap[allStrategyData[i].key] = allStrategyData[i];
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
      for (var oi = 0; oi < INTERVAL_OPTIONS.length; oi++) {
        var o = INTERVAL_OPTIONS[oi];
        var sel = (currentVal === o.v) ? ' selected' : '';
        options += '<option value="'+o.v+'"'+sel+'>'+o.l+'</option>';
      }
      var recIntv = recommended > 0 ? (recommended >= 60 ? (recommended/60)+'h' : recommended+'m') : '';
      html += '<tr><td style="font-size:13px">'+escAttr(slabel)+'</td>';
      html += '<td style="text-align:center;vertical-align:middle"><div class="select-wrap" style="width:110px;display:inline-block"><select class="select sd-intv-select" data-strat="'+escAttr(skey)+'" style="width:100%">'+options+'</select></div>';
      if (recIntv) html += '<div style="font-size:10px;color:var(--primary);margin-top:2px">권장 '+recIntv+'</div>';
      html += '</td>';
      if (hasEmaCol) {
        if (sd.emaFilterMode === 'CONFIGURABLE') {
          var recEma = sd.recommendedEma || 50;
          var emaVal = inst.emaMap[skey] != null ? inst.emaMap[skey] : recEma;
          html += '<td style="text-align:center;vertical-align:middle"><input class="input sd-ema-input" data-strat="'+escAttr(skey)+'" type="number" min="0" max="500" step="10" value="'+emaVal+'" style="width:70px;text-align:center"/>';
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

  function saveStratDetailModal() {
    if (!currentDetailInst) return;
    var modal = document.getElementById('stratDetailModal');
    if (!modal) return;

    // Save per-strategy intervals
    var selects = modal.querySelectorAll('.sd-intv-select');
    for (var i = 0; i < selects.length; i++) {
      var key = selects[i].getAttribute('data-strat');
      var val = parseInt(selects[i].value) || 0;
      if (val > 0) {
        currentDetailInst.stratIntervals[key] = val;
      } else {
        delete currentDetailInst.stratIntervals[key];
      }
    }

    // Save per-strategy EMA
    var emaInputs = modal.querySelectorAll('.sd-ema-input');
    for (var i = 0; i < emaInputs.length; i++) {
      var key = emaInputs[i].getAttribute('data-strat');
      var val = parseInt(emaInputs[i].value) || 0;
      if (val > 0) {
        currentDetailInst.emaMap[key] = val;
      } else {
        delete currentDetailInst.emaMap[key];
      }
    }

    modal.classList.remove('open');
    modal.setAttribute('aria-hidden', 'true');
    document.body.style.overflow = '';
    showToast('전략 상세 설정이 저장되었습니다.', 'success');
  }

  // Bind save button (delegated)
  document.addEventListener('click', function(e) {
    if (e.target && e.target.id === 'sdSaveBtn') {
      saveStratDetailModal();
    }
  });

  // ── Helpers ──
  function parseEmaMap(csv) {
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

  function buildStratIntervalsCsv(inst) {
    var parts = [];
    for (var k in inst.stratIntervals) {
      if (inst.stratIntervals.hasOwnProperty(k) && inst.stratIntervals[k] > 0) {
        parts.push(k + ':' + inst.stratIntervals[k]);
      }
    }
    return parts.join(',');
  }

  function buildEmaFilterCsv(inst) {
    var parts = [];
    for (var k in inst.emaMap) {
      if (inst.emaMap.hasOwnProperty(k) && inst.emaMap[k] > 0) {
        parts.push(k + ':' + inst.emaMap[k]);
      }
    }
    return parts.join(',');
  }

  function escAttr(s) {
    return String(s || '').replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  // ── Market Mutual Exclusion ──
  function getUsedMarkets(excludeIdx) {
    var used = {};
    for (var i = 0; i < groupInstances.length; i++) {
      if (groupInstances[i].idx === excludeIdx) continue;
      var sel = groupInstances[i].marketMs.getSelected();
      for (var j = 0; j < sel.length; j++) used[sel[j]] = true;
    }
    return used;
  }

  function getAvailableMarkets(forIdx) {
    var used = getUsedMarkets(forIdx);
    return allMarketOpts.filter(function(o) { return !used[o.value]; });
  }

  function updateAllMarketOptions() {
    for (var i = 0; i < groupInstances.length; i++) {
      var inst = groupInstances[i];
      var available = getAvailableMarkets(inst.idx);
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
      if (inst.marketMs.updateOptions) inst.marketMs.updateOptions(opts);
    }
  }

  function updateDeleteButtons() {
    var btns = document.querySelectorAll('.group-delete-btn');
    for (var i = 0; i < btns.length; i++) {
      btns[i].style.display = groupInstances.length <= 1 ? 'none' : '';
    }
  }

  // ── Collect groups from UI ──
  function collectGroups() {
    var groups = [];
    for (var i = 0; i < groupInstances.length; i++) {
      var inst = groupInstances[i];
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
        strategyIntervalsCsv: buildStratIntervalsCsv(inst),
        emaFilterCsv: buildEmaFilterCsv(inst),
        selectedPreset: inst.selectedPreset || null
      });
    }
    return groups;
  }

  // ── Apply ──
  document.getElementById('applyBtn').addEventListener('click', async function() {
    var applyBtn = document.getElementById('applyBtn');
    applyBtn.disabled = true;
    applyBtn.textContent = 'Applying...';
    try {
      var groups = collectGroups();
      var hasMarkets = false;
      for (var i = 0; i < groups.length; i++) {
        if (groups[i].markets.length > 0) hasMarkets = true;
        if (groups[i].strategies.length === 0) {
          showToast(groups[i].groupName + ': 전략을 선택해주세요.', 'error');
          applyBtn.disabled = false; applyBtn.textContent = 'Apply'; return;
        }
      }
      if (!hasMarkets) {
        showToast('마켓을 선택해주세요.', 'error');
        applyBtn.disabled = false; applyBtn.textContent = 'Apply'; return;
      }

      var mode = document.getElementById('mode').value;
      var capitalRaw = document.getElementById('capital').value.replace(/,/g, '');
      var capital = parseFloat(capitalRaw) || 0;
      var allStrats = []; var stratSet = {};
      for (var i = 0; i < groups.length; i++) {
        var gs = groups[i].strategies;
        for (var j = 0; j < gs.length; j++) {
          if (!stratSet[gs[j]]) { allStrats.push(gs[j]); stratSet[gs[j]] = true; }
        }
      }

      await req('/api/bot/settings', {
        method: 'POST',
        body: JSON.stringify({
          mode: mode, capitalKrw: capital, strategies: allStrats,
          candleUnitMin: groups[0].candleUnitMin, orderSizingMode: groups[0].orderSizingMode,
          orderSizingValue: groups[0].orderSizingValue, takeProfitPct: groups[0].takeProfitPct,
          stopLossPct: groups[0].stopLossPct, maxAddBuysGlobal: groups[0].maxAddBuys,
          strategyLock: groups[0].strategyLock, minConfidence: groups[0].minConfidence,
          timeStopMinutes: groups[0].timeStopMinutes
        })
      });

      var result = await req('/api/bot/groups', { method: 'POST', body: JSON.stringify(groups) });

      // Save scanner configs together
      await saveScannerConfig();
      await saveAlldayConfig();
      await saveMorningRushConfig();

      if (result && result.success) {
        showToast('Settings applied! (' + result.groupCount + ' groups + scanners)', 'success');
      } else {
        showToast('Error: ' + (result.error || 'Unknown'), 'error');
      }
    } catch (err) {
      showToast('Failed: ' + (err.message || err), 'error');
    } finally {
      applyBtn.disabled = false; applyBtn.textContent = 'Apply';
    }
  });

  // ── API Key Test ──
  var apiTestBtn = document.getElementById('apiTestBtn');
  var apiTestResult = document.getElementById('apiTestResult');
  if (apiTestBtn) {
    apiTestBtn.addEventListener('click', async function() {
      apiTestBtn.disabled = true; apiTestBtn.textContent = 'Testing...';
      var apiTestText = document.getElementById('apiTestResultText');
      if (apiTestResult) {
        apiTestResult.style.display = 'block';
        if (apiTestText) { apiTestText.textContent = 'Testing Upbit API key...'; }
      }
      try {
        var res = await req('/api/keys/test', { method: 'POST', body: JSON.stringify({ market: 'KRW-BTC', amount: 5001 }) });
        var text = '';
        if (!res.keyConfigured) { text = 'API key not configured.'; }
        else {
          text = 'Key: OK\nBalance: ' + (res.accountsOk ? 'OK' : 'FAIL') + '\n';
          if (res.accountsOk) text += '  KRW: ' + (res.krwBalance || '-') + '  (locked: ' + (res.krwLocked || '0') + ')\n';
          text += 'Order Test: ' + (res.orderTestOk ? 'OK' : 'FAIL') + '\n';
          if (!res.orderTestOk && res.orderTestError) text += '  Error: ' + res.orderTestError + '\n';
        }
        if (apiTestResult) {
          if (apiTestText) { apiTestText.textContent = text; }
          apiTestResult.style.borderColor = (res.accountsOk && res.orderTestOk) ? 'var(--success)' : 'var(--danger)';
        }
      } catch (err) {
        if (apiTestResult) {
          if (apiTestText) { apiTestText.textContent = 'Error: ' + (err.message || err); }
          apiTestResult.style.borderColor = 'var(--danger)';
        }
      } finally {
        apiTestBtn.disabled = false; apiTestBtn.textContent = 'API Test';
      }
    });
  }

  // ── API Test Result Dismiss ──
  var apiTestResultDismiss = document.getElementById('apiTestResultDismiss');
  if (apiTestResultDismiss && apiTestResult) {
    apiTestResultDismiss.addEventListener('click', function() {
      apiTestResult.style.display = 'none';
    });
  }

  // ═══════════════════════════════════════════
  //  Opening Scanner Settings
  // ═══════════════════════════════════════════

  var scannerEnabledToggle = document.getElementById('scannerEnabledToggle');
  var scEnabled = false;

  function parseHHMM(str) {
    if (!str) return [0, 0];
    var parts = String(str).split(':');
    return [parseInt(parts[0]) || 0, parseInt(parts[1]) || 0];
  }

  function fmtHHMM(h, m) {
    return String(h).padStart(2, '0') + ':' + String(m).padStart(2, '0');
  }

  function setScannerToggleUI(enabled) {
    scEnabled = !!enabled;
    if (!scannerEnabledToggle) return;
    scannerEnabledToggle.classList.toggle('on', scEnabled);
    scannerEnabledToggle.setAttribute('aria-pressed', String(scEnabled));
    var label = scannerEnabledToggle.querySelector('.bot-toggle-label');
    if (label) label.textContent = scEnabled ? 'ON' : 'OFF';
  }

  if (scannerEnabledToggle) {
    scannerEnabledToggle.addEventListener('click', function() {
      setScannerToggleUI(!scEnabled);
    });
  }

  async function loadScannerConfig() {
    try {
      var cfg = await req('/api/scanner/config', { method: 'GET' });
      setScannerToggleUI(cfg.enabled);
      var el = function(id) { return document.getElementById(id); };
      if (el('scMode')) el('scMode').value = cfg.mode || 'PAPER';
      if (el('scOrderMode')) el('scOrderMode').value = cfg.orderSizingMode || 'PCT';
      // Global Capital 표시 (읽기 전용)
      if (el('scGlobalCapDisplay') && cfg.globalCapitalKrw) {
        el('scGlobalCapDisplay').textContent = '(현재 Capital: ' + fmt(cfg.globalCapitalKrw) + '원)';
      }
      if (el('scOrderValue')) el('scOrderValue').value = cfg.orderSizingValue || 30;
      if (el('scRangeStart')) el('scRangeStart').value = fmtHHMM(cfg.rangeStartHour, cfg.rangeStartMin);
      if (el('scRangeEnd')) el('scRangeEnd').value = fmtHHMM(cfg.rangeEndHour, cfg.rangeEndMin);
      if (el('scEntryStart')) el('scEntryStart').value = fmtHHMM(cfg.entryStartHour, cfg.entryStartMin);
      if (el('scEntryEnd')) el('scEntryEnd').value = fmtHHMM(cfg.entryEndHour, cfg.entryEndMin);
      if (el('scSessionEnd')) el('scSessionEnd').value = fmtHHMM(cfg.sessionEndHour, cfg.sessionEndMin);
      if (el('scTpAtr')) el('scTpAtr').value = cfg.tpAtrMult || 1.2;
      if (el('scSlPct')) el('scSlPct').value = cfg.slPct || 10;
      if (el('scTrailAtr')) el('scTrailAtr').value = cfg.trailAtrMult || 0.8;
      // SL 종합안 (옵션 B)
      if (el('scGracePeriod')) el('scGracePeriod').value = cfg.gracePeriodSec != null ? cfg.gracePeriodSec : 60;
      if (el('scWidePeriod')) el('scWidePeriod').value = cfg.widePeriodMin != null ? cfg.widePeriodMin : 15;
      if (el('scTightSl')) el('scTightSl').value = cfg.tightSlPct != null ? cfg.tightSlPct : 3.0;
      // TOP-N 차등 SL_WIDE (권장 모두 동일 6.0)
      if (el('scWideSlTop10')) el('scWideSlTop10').value = cfg.wideSlTop10Pct != null ? cfg.wideSlTop10Pct : 6.0;
      if (el('scWideSlTop20')) el('scWideSlTop20').value = cfg.wideSlTop20Pct != null ? cfg.wideSlTop20Pct : 6.0;
      if (el('scWideSlTop50')) el('scWideSlTop50').value = cfg.wideSlTop50Pct != null ? cfg.wideSlTop50Pct : 6.0;
      if (el('scWideSlOther')) el('scWideSlOther').value = cfg.wideSlOtherPct != null ? cfg.wideSlOtherPct : 6.0;
      if (el('scCandleUnit')) el('scCandleUnit').value = String(cfg.candleUnitMin || 5);
      if (el('scTopN')) el('scTopN').value = cfg.topN || 15;
      if (el('scMaxPos')) el('scMaxPos').value = cfg.maxPositions || 3;
      if (el('scBtcFilter')) el('scBtcFilter').value = String(cfg.btcFilterEnabled !== false);
      if (el('scOpenFailed')) el('scOpenFailed').value = String(cfg.openFailedEnabled !== false);
      if (el('scVolMult')) el('scVolMult').value = cfg.volumeMult || 1.5;
      if (el('scBodyRatio')) el('scBodyRatio').value = cfg.minBodyRatio || 0.40;
      if (el('scMinPrice')) el('scMinPrice').value = cfg.minPriceKrw != null ? cfg.minPriceKrw : 20;
      if (el('scExcludeMarkets')) el('scExcludeMarkets').value = cfg.excludeMarkets || '';
      // Split-Exit
      if (el('scSplitEnabled')) el('scSplitEnabled').value = String(cfg.splitExitEnabled === true);
      if (el('scSplitTpPct')) el('scSplitTpPct').value = cfg.splitTpPct || 1.5;
      if (el('scSplitRatio')) el('scSplitRatio').value = cfg.splitRatio || 0.60;
      if (el('scTrailDropAfterSplit')) el('scTrailDropAfterSplit').value = cfg.trailDropAfterSplit || 1.0;
    } catch(e) {
      console.warn('Scanner config load failed:', e);
    }
  }

  async function saveScannerConfig() {
    var el = function(id) { return document.getElementById(id); };
    var rs = parseHHMM(el('scRangeStart') ? el('scRangeStart').value : '08:00');
    var re = parseHHMM(el('scRangeEnd') ? el('scRangeEnd').value : '08:59');
    var es = parseHHMM(el('scEntryStart') ? el('scEntryStart').value : '09:05');
    var ee = parseHHMM(el('scEntryEnd') ? el('scEntryEnd').value : '10:30');
    var se = parseHHMM(el('scSessionEnd') ? el('scSessionEnd').value : '12:00');

    var body = {
      enabled: scEnabled,
      mode: el('scMode') ? el('scMode').value : 'PAPER',
      orderSizingMode: el('scOrderMode') ? el('scOrderMode').value : 'PCT',
      orderSizingValue: parseFloat(el('scOrderValue') ? el('scOrderValue').value : '30') || 30,
      rangeStartHour: rs[0], rangeStartMin: rs[1],
      rangeEndHour: re[0], rangeEndMin: re[1],
      entryStartHour: es[0], entryStartMin: es[1],
      entryEndHour: ee[0], entryEndMin: ee[1],
      sessionEndHour: se[0], sessionEndMin: se[1],
      tpAtrMult: parseFloat(el('scTpAtr') ? el('scTpAtr').value : '1.2') || 1.2,
      slPct: parseFloat(el('scSlPct') ? el('scSlPct').value : '10') || 10,
      trailAtrMult: parseFloat(el('scTrailAtr') ? el('scTrailAtr').value : '0.8') || 0.8,
      // SL 종합안 (옵션 B)
      gracePeriodSec: parseInt(el('scGracePeriod') ? el('scGracePeriod').value : '60') || 60,
      widePeriodMin: parseInt(el('scWidePeriod') ? el('scWidePeriod').value : '15') || 15,
      tightSlPct: parseFloat(el('scTightSl') ? el('scTightSl').value : '3.0') || 3.0,
      // TOP-N 차등 SL_WIDE (모두 동일 6.0 권장)
      wideSlTop10Pct: parseFloat(el('scWideSlTop10') ? el('scWideSlTop10').value : '6.0') || 6.0,
      wideSlTop20Pct: parseFloat(el('scWideSlTop20') ? el('scWideSlTop20').value : '6.0') || 6.0,
      wideSlTop50Pct: parseFloat(el('scWideSlTop50') ? el('scWideSlTop50').value : '6.0') || 6.0,
      wideSlOtherPct: parseFloat(el('scWideSlOther') ? el('scWideSlOther').value : '6.0') || 6.0,
      candleUnitMin: parseInt(el('scCandleUnit') ? el('scCandleUnit').value : '5') || 5,
      topN: parseInt(el('scTopN') ? el('scTopN').value : '15') || 15,
      maxPositions: parseInt(el('scMaxPos') ? el('scMaxPos').value : '3') || 3,
      btcFilterEnabled: (el('scBtcFilter') ? el('scBtcFilter').value : 'true') === 'true',
      openFailedEnabled: (el('scOpenFailed') ? el('scOpenFailed').value : 'true') === 'true',
      volumeMult: parseFloat(el('scVolMult') ? el('scVolMult').value : '1.5') || 1.5,
      minBodyRatio: parseFloat(el('scBodyRatio') ? el('scBodyRatio').value : '0.40') || 0.40,
      minPriceKrw: parseInt(el('scMinPrice') ? el('scMinPrice').value : '20') || 0,
      excludeMarkets: el('scExcludeMarkets') ? el('scExcludeMarkets').value.trim() : '',
      // Split-Exit
      splitExitEnabled: (el('scSplitEnabled') ? el('scSplitEnabled').value : 'false') === 'true',
      splitTpPct: parseFloat(el('scSplitTpPct') ? el('scSplitTpPct').value : '1.5') || 1.5,
      splitRatio: parseFloat(el('scSplitRatio') ? el('scSplitRatio').value : '0.60') || 0.60,
      trailDropAfterSplit: parseFloat(el('scTrailDropAfterSplit') ? el('scTrailDropAfterSplit').value : '1.0') || 1.0
    };

    await req('/api/scanner/config', { method: 'POST', body: JSON.stringify(body) });
  }

  // Load scanner config on page load
  loadScannerConfig();

  // ═══════════════════════════════════════════
  //  AllDay Scanner Settings
  // ═══════════════════════════════════════════

  var adEnabledToggle = document.getElementById('adEnabledToggle');
  var adEnabled = false;

  function setAlldayToggleUI(enabled) {
    adEnabled = !!enabled;
    if (!adEnabledToggle) return;
    adEnabledToggle.classList.toggle('on', adEnabled);
    adEnabledToggle.setAttribute('aria-pressed', String(adEnabled));
    var label = adEnabledToggle.querySelector('.bot-toggle-label');
    if (label) label.textContent = adEnabled ? 'ON' : 'OFF';
  }

  if (adEnabledToggle) {
    adEnabledToggle.addEventListener('click', function() {
      setAlldayToggleUI(!adEnabled);
    });
  }

  async function loadAlldayConfig() {
    try {
      var cfg = await req('/api/allday-scanner/config', { method: 'GET' });
      setAlldayToggleUI(cfg.enabled);
      var el = function(id) { return document.getElementById(id); };
      if (el('adMode')) el('adMode').value = cfg.mode || 'PAPER';
      if (el('adOrderMode')) el('adOrderMode').value = cfg.orderSizingMode || 'PCT';
      if (el('adGlobalCapDisplay') && cfg.globalCapitalKrw) {
        el('adGlobalCapDisplay').textContent = '(현재 Capital: ' + fmt(cfg.globalCapitalKrw) + '원)';
      }
      if (el('adOrderValue')) el('adOrderValue').value = cfg.orderSizingValue || 20;
      if (el('adEntryStart')) el('adEntryStart').value = fmtHHMM(cfg.entryStartHour, cfg.entryStartMin);
      if (el('adEntryEnd')) el('adEntryEnd').value = fmtHHMM(cfg.entryEndHour, cfg.entryEndMin);
      if (el('adSessionEnd')) el('adSessionEnd').value = fmtHHMM(cfg.sessionEndHour, cfg.sessionEndMin);
      if (el('adSlPct')) el('adSlPct').value = cfg.slPct || 1.5;
      if (el('adTrailAtr')) el('adTrailAtr').value = cfg.trailAtrMult || 0.8;
      if (el('adMinConf')) el('adMinConf').value = cfg.minConfidence || 9.4;
      if (el('adCandleUnit')) el('adCandleUnit').value = String(cfg.candleUnitMin || 5);
      if (el('adTsCandles')) el('adTsCandles').value = cfg.timeStopCandles || 12;
      if (el('adTsMinPnl')) el('adTsMinPnl').value = cfg.timeStopMinPnl || 0.3;
      if (el('adTopN')) el('adTopN').value = cfg.topN || 15;
      if (el('adMaxPos')) el('adMaxPos').value = cfg.maxPositions || 2;
      if (el('adBtcFilter')) el('adBtcFilter').value = String(cfg.btcFilterEnabled !== false);
      if (el('adVolSurge')) el('adVolSurge').value = cfg.volumeSurgeMult || 3.0;
      if (el('adBodyRatio')) el('adBodyRatio').value = cfg.minBodyRatio || 0.60;
      if (el('adMinPrice')) el('adMinPrice').value = cfg.minPriceKrw != null ? cfg.minPriceKrw : 20;
      if (el('adExcludeMarkets')) el('adExcludeMarkets').value = cfg.excludeMarkets || '';
      // Quick TP
      if (el('adQuickTpEnabled')) el('adQuickTpEnabled').value = String(cfg.quickTpEnabled !== false);
      if (el('adQuickTpPct')) el('adQuickTpPct').value = cfg.quickTpPct || 0.7;
      if (el('adQuickTpInterval')) el('adQuickTpInterval').value = cfg.quickTpIntervalSec || 5;
      // Split-Exit
      if (el('adSplitEnabled')) el('adSplitEnabled').value = String(cfg.splitExitEnabled === true);
      if (el('adSplitTpPct')) el('adSplitTpPct').value = cfg.splitTpPct || 1.5;
      if (el('adSplitRatio')) el('adSplitRatio').value = cfg.splitRatio || 0.60;
      if (el('adTrailDropAfterSplit')) el('adTrailDropAfterSplit').value = cfg.trailDropAfterSplit || 1.0;
    } catch(e) {
      console.warn('AllDay Scanner config load failed:', e);
    }
  }

  async function saveAlldayConfig() {
    var el = function(id) { return document.getElementById(id); };
    var es = parseHHMM(el('adEntryStart') ? el('adEntryStart').value : '10:35');
    var ee = parseHHMM(el('adEntryEnd') ? el('adEntryEnd').value : '22:00');
    var se = parseHHMM(el('adSessionEnd') ? el('adSessionEnd').value : '23:00');

    var body = {
      enabled: adEnabled,
      mode: el('adMode') ? el('adMode').value : 'PAPER',
      orderSizingMode: el('adOrderMode') ? el('adOrderMode').value : 'PCT',
      orderSizingValue: parseFloat(el('adOrderValue') ? el('adOrderValue').value : '20') || 20,
      entryStartHour: es[0], entryStartMin: es[1],
      entryEndHour: ee[0], entryEndMin: ee[1],
      sessionEndHour: se[0], sessionEndMin: se[1],
      slPct: parseFloat(el('adSlPct') ? el('adSlPct').value : '1.5') || 1.5,
      trailAtrMult: parseFloat(el('adTrailAtr') ? el('adTrailAtr').value : '0.8') || 0.8,
      minConfidence: parseFloat(el('adMinConf') ? el('adMinConf').value : '9.4') || 9.4,
      candleUnitMin: parseInt(el('adCandleUnit') ? el('adCandleUnit').value : '5') || 5,
      timeStopCandles: parseInt(el('adTsCandles') ? el('adTsCandles').value : '12') || 12,
      timeStopMinPnl: parseFloat(el('adTsMinPnl') ? el('adTsMinPnl').value : '0.3') || 0.3,
      topN: parseInt(el('adTopN') ? el('adTopN').value : '15') || 15,
      maxPositions: parseInt(el('adMaxPos') ? el('adMaxPos').value : '2') || 2,
      btcFilterEnabled: (el('adBtcFilter') ? el('adBtcFilter').value : 'true') === 'true',
      volumeSurgeMult: parseFloat(el('adVolSurge') ? el('adVolSurge').value : '3.0') || 3.0,
      minBodyRatio: parseFloat(el('adBodyRatio') ? el('adBodyRatio').value : '0.60') || 0.60,
      minPriceKrw: parseInt(el('adMinPrice') ? el('adMinPrice').value : '20') || 0,
      excludeMarkets: el('adExcludeMarkets') ? el('adExcludeMarkets').value.trim() : '',
      // Quick TP
      quickTpEnabled: (el('adQuickTpEnabled') ? el('adQuickTpEnabled').value : 'true') === 'true',
      quickTpPct: parseFloat(el('adQuickTpPct') ? el('adQuickTpPct').value : '0.7') || 0.7,
      quickTpIntervalSec: parseInt(el('adQuickTpInterval') ? el('adQuickTpInterval').value : '5') || 5,
      // Split-Exit
      splitExitEnabled: (el('adSplitEnabled') ? el('adSplitEnabled').value : 'false') === 'true',
      splitTpPct: parseFloat(el('adSplitTpPct') ? el('adSplitTpPct').value : '1.5') || 1.5,
      splitRatio: parseFloat(el('adSplitRatio') ? el('adSplitRatio').value : '0.60') || 0.60,
      trailDropAfterSplit: parseFloat(el('adTrailDropAfterSplit') ? el('adTrailDropAfterSplit').value : '1.0') || 1.0
    };

    await req('/api/allday-scanner/config', { method: 'POST', body: JSON.stringify(body) });
  }

  // Load allday scanner config on page load
  loadAlldayConfig();

  // ═══════════════════════════════════════════
  //  Morning Rush Scanner Settings
  // ═══════════════════════════════════════════

  var mrEnabledToggle = document.getElementById('mrEnabledToggle');
  var mrEnabled = false;

  function setMrToggleUI(enabled) {
    mrEnabled = !!enabled;
    if (!mrEnabledToggle) return;
    mrEnabledToggle.classList.toggle('on', mrEnabled);
    mrEnabledToggle.setAttribute('aria-pressed', String(mrEnabled));
    var label = mrEnabledToggle.querySelector('.bot-toggle-label');
    if (label) label.textContent = mrEnabled ? 'ON' : 'OFF';
  }

  if (mrEnabledToggle) {
    mrEnabledToggle.addEventListener('click', function() {
      setMrToggleUI(!mrEnabled);
    });
  }

  async function loadMorningRushConfig() {
    try {
      var cfg = await req('/api/morning-rush/config', { method: 'GET' });
      setMrToggleUI(cfg.enabled);
      var el = function(id) { return document.getElementById(id); };
      if (el('mrMode')) el('mrMode').value = cfg.mode || 'PAPER';
      if (el('mrOrderMode')) el('mrOrderMode').value = cfg.orderSizingMode || 'PCT';
      if (el('mrGlobalCapDisplay') && cfg.globalCapitalKrw) {
        el('mrGlobalCapDisplay').textContent = '(현재 Capital: ' + fmt(cfg.globalCapitalKrw) + '원)';
      }
      if (el('mrOrderValue')) el('mrOrderValue').value = cfg.orderSizingValue || 20;
      if (el('mrGapThreshold')) el('mrGapThreshold').value = cfg.gapThresholdPct || 5.0;
      if (el('mrVolMult')) el('mrVolMult').value = cfg.volumeMult || 5.0;
      if (el('mrConfirmCount')) el('mrConfirmCount').value = cfg.confirmCount || 3;
      if (el('mrCheckInterval')) el('mrCheckInterval').value = cfg.checkIntervalSec || 5;
      if (el('mrTpPct')) el('mrTpPct').value = cfg.tpPct || 2.0;
      if (el('mrSlPct')) el('mrSlPct').value = cfg.slPct || 3.0;
      if (el('mrGracePeriod')) el('mrGracePeriod').value = cfg.gracePeriodSec != null ? cfg.gracePeriodSec : 60;
      if (el('mrWidePeriod')) el('mrWidePeriod').value = cfg.widePeriodMin != null ? cfg.widePeriodMin : 30;
      if (el('mrWideSlPct')) el('mrWideSlPct').value = cfg.wideSlPct != null ? cfg.wideSlPct : 6.0;
      if (el('mrSessionEnd')) el('mrSessionEnd').value = fmtHHMM(cfg.sessionEndHour, cfg.sessionEndMin);
      if (el('mrTopN')) el('mrTopN').value = cfg.topN || 30;
      if (el('mrMaxPos')) el('mrMaxPos').value = cfg.maxPositions || 2;
      if (el('mrMinTradeAmount')) el('mrMinTradeAmount').value = cfg.minTradeAmountBillion || 10;
      if (el('mrMinPrice')) el('mrMinPrice').value = cfg.minPriceKrw != null ? cfg.minPriceKrw : 20;
      if (el('mrExcludeMarkets')) el('mrExcludeMarkets').value = cfg.excludeMarkets || '';
      // Split-Exit
      if (el('mrSplitEnabled')) el('mrSplitEnabled').value = String(cfg.splitExitEnabled === true);
      if (el('mrSplitTpPct')) el('mrSplitTpPct').value = cfg.splitTpPct || 1.5;
      if (el('mrSplitRatio')) el('mrSplitRatio').value = cfg.splitRatio || 0.60;
      if (el('mrTrailDropAfterSplit')) el('mrTrailDropAfterSplit').value = cfg.trailDropAfterSplit || 1.0;
    } catch(e) {
      console.warn('Morning Rush config load failed:', e);
    }
  }

  async function saveMorningRushConfig() {
    var el = function(id) { return document.getElementById(id); };
    var se = parseHHMM(el('mrSessionEnd') ? el('mrSessionEnd').value : '10:00');

    var body = {
      enabled: mrEnabled,
      mode: el('mrMode') ? el('mrMode').value : 'PAPER',
      orderSizingMode: el('mrOrderMode') ? el('mrOrderMode').value : 'PCT',
      orderSizingValue: parseFloat(el('mrOrderValue') ? el('mrOrderValue').value : '20') || 20,
      gapThresholdPct: parseFloat(el('mrGapThreshold') ? el('mrGapThreshold').value : '5.0') || 5.0,
      volumeMult: parseFloat(el('mrVolMult') ? el('mrVolMult').value : '5.0') || 5.0,
      confirmCount: parseInt(el('mrConfirmCount') ? el('mrConfirmCount').value : '3') || 3,
      checkIntervalSec: parseInt(el('mrCheckInterval') ? el('mrCheckInterval').value : '5') || 5,
      tpPct: parseFloat(el('mrTpPct') ? el('mrTpPct').value : '2.0') || 2.0,
      slPct: parseFloat(el('mrSlPct') ? el('mrSlPct').value : '3.0') || 3.0,
      gracePeriodSec: parseInt(el('mrGracePeriod') ? el('mrGracePeriod').value : '60') || 60,
      widePeriodMin: parseInt(el('mrWidePeriod') ? el('mrWidePeriod').value : '30') || 30,
      wideSlPct: parseFloat(el('mrWideSlPct') ? el('mrWideSlPct').value : '6.0') || 6.0,
      sessionEndHour: se[0], sessionEndMin: se[1],
      topN: parseInt(el('mrTopN') ? el('mrTopN').value : '30') || 30,
      maxPositions: parseInt(el('mrMaxPos') ? el('mrMaxPos').value : '2') || 2,
      minTradeAmountBillion: parseInt(el('mrMinTradeAmount') ? el('mrMinTradeAmount').value : '10') || 10,
      minPriceKrw: parseInt(el('mrMinPrice') ? el('mrMinPrice').value : '20') || 0,
      excludeMarkets: el('mrExcludeMarkets') ? el('mrExcludeMarkets').value.trim() : '',
      // Split-Exit
      splitExitEnabled: (el('mrSplitEnabled') ? el('mrSplitEnabled').value : 'false') === 'true',
      splitTpPct: parseFloat(el('mrSplitTpPct') ? el('mrSplitTpPct').value : '1.5') || 1.5,
      splitRatio: parseFloat(el('mrSplitRatio') ? el('mrSplitRatio').value : '0.60') || 0.60,
      trailDropAfterSplit: parseFloat(el('mrTrailDropAfterSplit') ? el('mrTrailDropAfterSplit').value : '1.0') || 1.0
    };

    await req('/api/morning-rush/config', { method: 'POST', body: JSON.stringify(body) });
  }

  // Load morning rush config on page load
  loadMorningRushConfig();

  // ── SSO Partner Button ──
  (function() {
    var ssoBtn = document.getElementById('ssoPartnerBtn');
    if (!ssoBtn) return;
    var bp = (window.AutoTrade && window.AutoTrade.basePath) || '';
    setTimeout(function() {
      fetch(bp + '/api/auth/sso-info', { credentials: 'same-origin' })
        .then(function(r) { return r.json(); })
        .then(function(info) {
          if (info && info.enabled === 'true' && info.partnerUrl) {
            ssoBtn.title = info.partnerLabel || 'Partner';
            ssoBtn.setAttribute('data-tooltip', info.partnerLabel || 'Partner');
            ssoBtn.style.display = '';
            ssoBtn.addEventListener('click', function() {
              var popup = window.open('about:blank', '_blank');
              fetch(bp + '/api/auth/sso-token', { credentials: 'same-origin' })
                .then(function(r) { return r.json(); })
                .then(function(td) {
                  if (td && td.success) {
                    var url = info.partnerUrl + '/api/auth/sso-login?token=' +
                      encodeURIComponent(td.token) + '&username=' + encodeURIComponent(td.username) + '&ts=' + td.timestamp;
                    if (popup && !popup.closed) popup.location.href = url;
                    else window.open(url, '_blank');
                  } else { if (popup && !popup.closed) popup.close(); }
                }).catch(function() { if (popup && !popup.closed) popup.close(); });
            });
          }
        })
        .catch(function() {});
    }, 1000);
  })();

})();
