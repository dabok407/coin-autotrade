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

  // ── Init ──
  AutoTrade.initTheme();

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
            '<label>Interval <span class="help-icon" data-tooltip="기본 캔들 인터벌(분)입니다.\\n⚙ 상세에서 전략별 개별 인터벌을 설정할 수 있습니다." aria-label="Interval help"></span></label>' +
            '<div class="select-wrap"><select class="select grp-interval">' +
              '<option value="1">1m</option><option value="3">3m</option><option value="5">5m</option>' +
              '<option value="10">10m</option><option value="15">15m</option><option value="30">30m</option>' +
              '<option value="60" selected>1h(60m)</option><option value="240">4h(240m)</option>' +
            '</select></div>' +
          '</div>' +
          '<div class="field" style="min-width:200px">' +
            '<label>Order Size <span class="help-icon" data-tooltip="PCT: 자본금의 비율(%) / Fixed: 고정 금액(KRW)" aria-label="Order Size help"></span></label>' +
            '<div style="display:flex;gap:8px;align-items:center">' +
              '<div class="select-wrap" style="width:90px"><select class="select grp-orderMode"><option value="FIXED">Fixed</option><option value="PCT" selected>% Cap</option></select></div>' +
              '<input class="input grp-orderValue" type="text" value="90" style="width:80px"/>' +
            '</div>' +
          '</div>' +
        '</div>' +
        '<div style="font-size:11px;font-weight:800;text-transform:uppercase;letter-spacing:.5px;color:var(--danger);margin-bottom:10px;padding-bottom:6px;border-bottom:1px solid var(--border);margin-top:16px">Risk Management</div>' +
        '<div class="toolbar">' +
          '<div class="field"><label>TP (%) <span class="help-icon" data-tooltip="이익실현 비율. 평균매수가 대비 이 비율 이상 상승 시 매도" aria-label="TP help"></span></label><input class="input grp-tp" type="text" value="3.0" style="width:80px"/></div>' +
          '<div class="field"><label>SL (%) <span class="help-icon" data-tooltip="손절 비율. 평균매수가 대비 이 비율 이상 하락 시 매도" aria-label="SL help"></span></label><input class="input grp-sl" type="text" value="2.0" style="width:80px"/></div>' +
          '<div class="field"><label>Max Add Buys <span class="help-icon" data-tooltip="최대 추가매수 횟수. 0이면 추가매수 안함" aria-label="Max Add Buys help"></span></label><input class="input grp-maxAdd" type="number" min="0" max="10" value="2" style="width:70px"/></div>' +
          '<div class="field"><label>Min Confidence <span class="help-icon" data-tooltip="최소 신뢰도 점수. 이 값 미만 신호 무시. 0=모두 수용" aria-label="Min Confidence help"></span></label><input class="input grp-minConf" type="number" min="0" max="10" step="0.5" value="0" style="width:70px"/></div>' +
          '<div class="field">' +
            '<label>Strategy Lock <span class="help-icon" data-tooltip="ON: 진입 전략만 청산 가능\\nOFF: 어떤 전략이든 매도 신호 시 청산" aria-label="Strategy Lock help"></span></label>' +
            '<div style="display:flex;align-items:center;gap:8px;height:42px">' +
              '<button class="switch grp-stratLock" aria-pressed="false"><span class="knob"></span></button>' +
              '<span class="grp-stratLockLabel" style="font-size:13px;color:var(--muted)">OFF</span>' +
            '</div>' +
          '</div>' +
          '<div class="field"><label>Time Stop (min) <span class="help-icon" data-tooltip="시간 기반 손절(분). 보유시간 초과+손실 시 청산. 0=미사용" aria-label="Time Stop help"></span></label><input class="input grp-timeStop" type="number" min="0" step="30" value="0" style="width:80px"/></div>' +
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

    // Populate values from groupData
    if (groupData) {
      var intervalSel = card.querySelector('.grp-interval');
      if (intervalSel) intervalSel.value = String(groupData.candleUnitMin || 60);
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
      html += '<td style="text-align:center;vertical-align:middle"><div class="select-wrap" style="width:100px;height:34px;display:inline-block"><select class="select sd-intv-select" data-strat="'+escAttr(skey)+'" style="width:100%;height:34px;font-size:12px">'+options+'</select></div>';
      if (recIntv) html += '<div style="font-size:10px;color:var(--primary);margin-top:2px">권장 '+recIntv+'</div>';
      html += '</td>';
      if (hasEmaCol) {
        if (sd.emaFilterMode === 'CONFIGURABLE') {
          var recEma = sd.recommendedEma || 50;
          var emaVal = inst.emaMap[skey] != null ? inst.emaMap[skey] : recEma;
          html += '<td style="text-align:center;vertical-align:middle"><input class="input sd-ema-input" data-strat="'+escAttr(skey)+'" type="number" min="0" max="500" step="10" value="'+emaVal+'" style="width:70px;height:34px;font-size:12px;text-align:center"/>';
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
      if (result && result.success) {
        showToast('Settings applied! (' + result.groupCount + ' groups)', 'success');
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
      if (apiTestResult) { apiTestResult.style.display = 'block'; apiTestResult.textContent = 'Testing Upbit API key...'; }
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
          apiTestResult.textContent = text;
          apiTestResult.style.borderColor = (res.accountsOk && res.orderTestOk) ? 'var(--success)' : 'var(--danger)';
        }
      } catch (err) {
        if (apiTestResult) { apiTestResult.textContent = 'Error: ' + (err.message || err); apiTestResult.style.borderColor = 'var(--danger)'; }
      } finally {
        apiTestBtn.disabled = false; apiTestBtn.textContent = 'API Test';
      }
    });
  }

})();
