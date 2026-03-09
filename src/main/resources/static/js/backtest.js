(function() {
  'use strict';

  var API = window.AutoTrade.API;
  var req = window.AutoTrade.req;
  var fmt = window.AutoTrade.fmt;
  var initMultiSelect = window.AutoTrade.initMultiSelect;
  var initTheme = window.AutoTrade.initTheme;

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
            '<label>Interval <span class="help-icon" data-tooltip="기본 캔들 인터벌(분)입니다.\\n⚙ 상세에서 전략별 개별 인터벌을 설정할 수 있습니다." aria-label="Interval help"></span></label>' +
            '<div class="select-wrap"><select class="select grp-interval">' +
              '<option value="1">1m</option><option value="3">3m</option><option value="5">5m</option>' +
              '<option value="10">10m</option><option value="15">15m</option><option value="30">30m</option>' +
              '<option value="60" selected>1h(60m)</option><option value="240">4h(240m)</option>' +
            '</select></div>' +
          '</div>' +
          '<div class="field" style="min-width:200px">' +
            '<label>Order Size <span class="help-icon" data-tooltip="주문 크기 설정입니다.\\nPCT: 자본금의 비율(%) / Fixed: 고정 금액(KRW)" aria-label="Order Size help"></span></label>' +
            '<div style="display:flex;gap:8px;align-items:center">' +
              '<div class="select-wrap" style="width:90px"><select class="select grp-orderMode"><option value="FIXED">Fixed</option><option value="PCT" selected>% Cap</option></select></div>' +
              '<input class="input grp-orderValue" type="text" value="90" style="width:80px"/>' +
            '</div>' +
          '</div>' +
        '</div>' +
        '<div style="font-size:11px;font-weight:800;text-transform:uppercase;letter-spacing:.5px;color:var(--danger);margin-bottom:10px;padding-bottom:6px;border-bottom:1px solid var(--border);margin-top:16px">Risk Management</div>' +
        '<div class="toolbar">' +
          '<div class="field"><label>TP (%) <span class="help-icon" data-tooltip="이익실현(Take Profit) 비율입니다.\\n평균매수가 대비 이 비율 이상 상승하면 자동 매도합니다." aria-label="TP help"></span></label><input class="input grp-tp" type="text" value="3.0" style="width:80px"/></div>' +
          '<div class="field"><label>SL (%) <span class="help-icon" data-tooltip="손절(Stop Loss) 비율입니다.\\n평균매수가 대비 이 비율 이상 하락하면 자동 매도합니다." aria-label="SL help"></span></label><input class="input grp-sl" type="text" value="2.0" style="width:80px"/></div>' +
          '<div class="field"><label>Max Add Buys <span class="help-icon" data-tooltip="최대 추가매수 횟수입니다.\\n0이면 추가매수를 하지 않습니다." aria-label="Max Add Buys help"></span></label><input class="input grp-maxAdd" type="number" min="0" max="10" value="2" style="width:70px"/></div>' +
          '<div class="field"><label>Min Confidence <span class="help-icon" data-tooltip="최소 신뢰도 점수입니다.\\n전략이 산출한 신뢰도(score)가 이 값 미만이면 신호를 무시합니다.\\n0이면 모든 신호를 수용합니다." aria-label="Min Confidence help"></span></label><input class="input grp-minConf" type="number" min="0" max="10" step="0.5" value="0" style="width:70px"/></div>' +
          '<div class="field">' +
            '<label>Strategy Lock <span class="help-icon" data-tooltip="전략 잠금 기능입니다.\\nON: 진입한 전략만 해당 포지션을 청산(매도)할 수 있습니다.\\nOFF: 어떤 전략이든 매도 신호를 내면 청산합니다." aria-label="Strategy Lock help"></span></label>' +
            '<div style="display:flex;align-items:center;gap:8px;height:42px">' +
              '<button class="switch grp-stratLock" aria-pressed="false"><span class="knob"></span></button>' +
              '<span class="grp-stratLockLabel" style="font-size:13px;color:var(--muted)">OFF</span>' +
            '</div>' +
          '</div>' +
          '<div class="field"><label>Time Stop (min) <span class="help-icon" data-tooltip="시간 기반 손절(분)입니다.\\n포지션 보유 시간이 이 값을 초과하고 손실 중이면 자동 청산합니다.\\n0이면 사용하지 않습니다." aria-label="Time Stop help"></span></label><input class="input grp-timeStop" type="number" min="0" step="30" value="0" style="width:80px"/></div>' +
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
          var emaVal = inst.emaMap[skey] != null ? inst.emaMap[skey] : recEma;
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
      if (val > 0) {
        btCurrentDetailInst.emaMap[key] = val;
      } else {
        delete btCurrentDetailInst.emaMap[key];
      }
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
      if (inst.emaMap.hasOwnProperty(k) && inst.emaMap[k] > 0) {
        parts.push(k + ':' + inst.emaMap[k]);
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

  function fmtTs(ts) {
    if (!ts) return '-';
    var d = new Date(ts);
    if (!isNaN(d.getTime())) {
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
  //  Event Handlers
  // ═══════════════════════════════════════════════════════════════

  btRun.addEventListener('click', function() {
    setError('');
    btRun.disabled = true;

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

    console.log('[Backtest] request params:', JSON.stringify({
      groups: p.groups ? p.groups.length : 0,
      market: p.market,
      period: p.period
    }));

    req(API.backtestRun, {
      method: 'POST',
      body: JSON.stringify(p),
      cache: 'no-store'
    }).then(function(res) {
      console.log('[Backtest] response:', JSON.stringify({
        totalReturn: res.totalReturn,
        tradesCount: res.tradesCount,
        roi: res.roi
      }));

      btTotalReturn.textContent = fmt(res.totalReturn);
      btRoi.textContent = (res.roi == null ? '-' : Number(res.roi).toFixed(2) + '%');
      btTrades.textContent = fmt(res.tradesCount);
      btWinRate.textContent = (res.winRate == null ? '-' : Number(res.winRate).toFixed(2) + '%');

      logs = res.trades || [];
      page = 1;
      render();

      if ((res.tradesCount || 0) === 0) {
        var info = [];
        if (res.candleCount != null) info.push('\uce94\ub4e4: ' + res.candleCount + '\uac1c');
        if (res.candleUnitMin != null) info.push('\ub2e8\uc704: ' + res.candleUnitMin + '\ubd84');
        if (res.periodDays != null) info.push('\uae30\uac04: ' + res.periodDays + '\uc77c');
        setError('\uac70\ub798 0\uac74 (\uc2e0\ud638 \uc5c6\uc74c). ' + info.join(' \u00b7 ') + '\n\uc804\ub7b5/\uae30\uac04/\ubd84\ubd09\uc744 \ubc14\uafb8\uba74 \uac70\ub798\uac00 \ubc1c\uc0dd\ud560 \uc218 \uc788\uc2b5\ub2c8\ub2e4.');
      }
    }).catch(function(e) {
      setError(e.message || String(e));
    }).then(function() {
      btRun.disabled = false;
    });
  });

  // Date range validation on user input
  if (btFromDate) btFromDate.addEventListener('change', normalizeDateRange);
  if (btToDate) btToDate.addEventListener('change', normalizeDateRange);
  if (btFromDate) btFromDate.addEventListener('blur', normalizeDateRange);
  if (btToDate) btToDate.addEventListener('blur', normalizeDateRange);

  btReset.addEventListener('click', function() {
    logs = [];
    page = 1;
    btTotalReturn.textContent = '-';
    btRoi.textContent = '-';
    btTrades.textContent = '-';
    btWinRate.textContent = '-';
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

})();
