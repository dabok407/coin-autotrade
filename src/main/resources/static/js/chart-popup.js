/**
 * chart-popup.js — 거래 이벤트 시점 캔들스틱 차트 팝업
 *
 * 사용:
 *   window.ChartPopup.open({
 *     market: 'KRW-SOL',
 *     tsEpochMs: 1708234567890,
 *     action: 'BUY',
 *     price: 123000,
 *     qty: 0.05,
 *     pnlKrw: null,
 *     patternType: 'ADAPTIVE_TREND_MOMENTUM',
 *     patternLabel: '적응형 추세 모멘텀(ATM)',
 *     candleUnit: 5,      // 분봉 단위 (옵션, 기본 5)
 *     note: 'ATM_CHANDELIER_EXIT avg=418.42 ...',
 *     confidence: 7.5
 *   });
 */
window.ChartPopup = (() => {
  let modalEl = null;
  let chartInstance = null;
  let candleSeries = null;
  let volumeSeries = null;
  let currentReqId = 0; // prevent stale fetch overwriting

  const KST_OFFSET = 9 * 3600; // +9h in seconds

  // ───── lazy init: create modal DOM ─────
  function ensureModal() {
    if (modalEl) return modalEl;

    modalEl = document.createElement('div');
    modalEl.className = 'modal';
    modalEl.id = 'chartPopupModal';
    modalEl.setAttribute('aria-hidden', 'true');
    modalEl.innerHTML = `
      <div class="modal-backdrop" data-chart-close></div>
      <div class="modal-dialog chart-popup-dialog" role="dialog" aria-modal="true">
        <div class="modal-header">
          <div class="modal-title" id="chartPopupTitle">Trade Chart</div>
          <button type="button" class="modal-close" aria-label="Close" data-chart-close>×</button>
        </div>
        <div class="modal-body" style="padding:0;display:flex;flex-direction:column;overflow:hidden">
          <!-- Chart -->
          <div id="chartPopupContainer" style="width:100%;flex:1;min-height:340px;position:relative">
            <div id="chartPopupLoading" style="position:absolute;inset:0;display:flex;align-items:center;justify-content:center;z-index:10;background:rgba(0,0,0,.5)">
              <span style="color:var(--muted);font-size:14px">Loading chart…</span>
            </div>
          </div>
          <!-- Trade Info -->
          <div id="chartPopupInfo" style="border-top:1px solid var(--border);padding:14px 18px;display:flex;flex-direction:column;gap:12px;font-size:13px"></div>
        </div>
      </div>
    `;
    document.body.appendChild(modalEl);

    // close handlers
    modalEl.querySelectorAll('[data-chart-close]').forEach(el => {
      el.addEventListener('click', close);
    });
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && modalEl.classList.contains('open')) close();
    });

    return modalEl;
  }

  // ───── open ─────
  async function open(opts) {
    const modal = ensureModal();
    const reqId = ++currentReqId;

    const market = opts.market || '-';
    const tsMs = opts.tsEpochMs || Date.now();
    const action = opts.action || '';
    const price = opts.price;
    const qty = opts.qty;
    const pnlKrw = opts.pnlKrw;
    const patternType = opts.patternType || '';
    const patternLabel = opts.patternLabel || patternType;
    const unit = opts.candleUnit || 5;
    const avgBuyPrice = opts.avgBuyPrice || 0;
    const note = opts.note || '';
    const confidence = opts.confidence || 0;

    // Title
    const titleEl = modal.querySelector('#chartPopupTitle');
    if (titleEl) titleEl.textContent = `${market}  ·  ${unit >= 1440 ? '일봉' : unit + '분봉'}`;

    // Show modal immediately (loading state)
    modal.classList.add('open');
    modal.setAttribute('aria-hidden', 'false');
    document.body.style.overflow = 'hidden';

    const loading = modal.querySelector('#chartPopupLoading');
    if (loading) loading.style.display = 'flex';

    // Render info panel
    renderInfo(modal, { market, tsMs, action, price, qty, pnlKrw, patternType, patternLabel, avgBuyPrice, note, confidence });

    // Fetch candles
    try {
      const url = `/api/chart/candles?market=${encodeURIComponent(market)}&unit=${unit}&tsEpochMs=${tsMs}&count=80`;
      const resp = await fetch(url, { cache: 'no-store' });
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
      const candles = await resp.json();

      if (reqId !== currentReqId) return; // stale

      renderChart(modal, candles, { tsMs, action, price, avgBuyPrice: opts.avgBuyPrice || 0, patternType, note });
    } catch (e) {
      if (reqId !== currentReqId) return;
      const container = modal.querySelector('#chartPopupContainer');
      if (loading) loading.innerHTML = `<span style="color:var(--danger)">차트 로드 실패: ${esc(e.message)}</span>`;
    }
  }

  // ───── close ─────
  function close() {
    if (!modalEl) return;
    modalEl.classList.remove('open');
    modalEl.setAttribute('aria-hidden', 'true');
    document.body.style.overflow = '';
    // destroy chart to free memory
    if (chartInstance) {
      chartInstance.remove();
      chartInstance = null;
      candleSeries = null;
      volumeSeries = null;
    }
  }

  // ───── render info panel ─────
  function renderInfo(modal, d) {
    const info = modal.querySelector('#chartPopupInfo');
    if (!info) return;

    const isBuy = /BUY/i.test(d.action);
    const isSell = /SELL/i.test(d.action) && !/PENDING|BLOCKED/i.test(d.action);
    const actionColor = isBuy ? '#ff4d6d' : (isSell ? '#4dabf7' : 'var(--text)');
    const actionLabel = labelAction(d.action);

    const ts = new Date(d.tsMs);
    const timeStr = `${ts.getFullYear()}-${pad(ts.getMonth()+1)}-${pad(ts.getDate())} ${pad(ts.getHours())}:${pad(ts.getMinutes())}:${pad(ts.getSeconds())}`;

    // Confidence display
    const conf = d.confidence;
    const confText = (conf != null && conf > 0) ? Number(conf).toFixed(1) : '-';
    const confColor = (conf > 0) ? (conf >= 7 ? 'var(--success)' : conf >= 4 ? '#e0a000' : 'var(--danger)') : 'var(--muted)';

    // === Row 1: Core fields ===
    let html = `<div class="cp-row">`;

    html += `
      <div class="cp-field">
        <div class="cp-label">유형</div>
        <div class="cp-value" style="color:${actionColor};font-weight:900">${actionLabel}</div>
      </div>
      <div class="cp-field">
        <div class="cp-label">전략</div>
        <div class="cp-value">${esc(d.patternLabel || d.patternType || '-')}</div>
      </div>
      <div class="cp-field">
        <div class="cp-label">Score</div>
        <div class="cp-value" style="color:${confColor};font-weight:900;font-size:15px">${confText}</div>
      </div>
      <div class="cp-field">
        <div class="cp-label">마켓</div>
        <div class="cp-value">${esc(d.market)}</div>
      </div>`;

    // 매도건: 매수가 표시
    if (isSell && d.avgBuyPrice != null && d.avgBuyPrice > 0) {
      html += `
      <div class="cp-field">
        <div class="cp-label">매수 평균가</div>
        <div class="cp-value">${fmt(d.avgBuyPrice)}</div>
      </div>`;
    }

    html += `
      <div class="cp-field">
        <div class="cp-label">${isSell ? '매도가' : '체결가'}</div>
        <div class="cp-value">${d.price != null ? fmt(d.price) : '-'}</div>
      </div>
      <div class="cp-field">
        <div class="cp-label">수량</div>
        <div class="cp-value">${d.qty != null ? d.qty : '-'}</div>
      </div>`;

    if (d.pnlKrw != null && d.pnlKrw !== 0) {
      const pnlColor = Number(d.pnlKrw) >= 0 ? 'var(--success)' : 'var(--danger)';
      const roiStr = (d.avgBuyPrice > 0 && d.price > 0)
        ? ` (${(((d.price - d.avgBuyPrice) / d.avgBuyPrice) * 100).toFixed(2)}%)`
        : '';
      html += `
      <div class="cp-field">
        <div class="cp-label">실현손익</div>
        <div class="cp-value" style="color:${pnlColor};font-weight:900">${fmt(d.pnlKrw)} ₩${roiStr}</div>
      </div>`;
    }

    html += `
      <div class="cp-field">
        <div class="cp-label">시각</div>
        <div class="cp-value">${timeStr}</div>
      </div>`;

    html += `</div>`; // end cp-row

    // === Row 2: Reason (매매 사유) ===
    if (d.note) {
      const reasonText = parseReason(d.note, d.action, d.patternType);
      html += `
      <div class="cp-reason">
        <div class="cp-reason-label">📋 매매 사유</div>
        <div class="cp-reason-text">${reasonText}</div>
        <div class="cp-reason-raw" style="margin-top:4px;padding:4px 8px;background:var(--surface,#f8f9fa);border-radius:6px;font-size:11px;color:var(--muted,#888);word-break:break-all;font-family:monospace">${d.note}</div>
      </div>`;
    }

    info.innerHTML = html;
  }

  // ───── parse reason into human-readable text ─────
  function parseReason(note, action, patternType) {
    if (!note) return '-';
    const s = String(note);

    // ATM strategy internal exits
    if (s.includes('ATM_CHANDELIER_EXIT') || s.includes('CHANDELIER_EXIT'))
      return '📉 <b>샹들리에 추적 손절</b> — 진입 후 최고점 대비 ATR×2.5 하락하여 트레일링 스탑 발동';
    if (s.includes('ATM_HARD_STOP') || s.includes('HARD_STOP'))
      return '🛑 <b>ATR 하드 손절</b> — 매수 평균가 대비 ATR×2.0 하락하여 손절 발동';
    if (s.includes('ATM_TAKE_PROFIT'))
      return '🎯 <b>ATR 익절</b> — 매수 평균가 대비 ATR×4.0 상승하여 익절 발동';
    if (s.includes('ATM_TREND_BREAK'))
      return '⚠️ <b>추세 붕괴 청산</b> — EMA20이 EMA50 아래로 하락 (골든크로스 해소)';
    if (s.includes('ATM_MOMENTUM_LOSS'))
      return '📊 <b>모멘텀 소멸 청산</b> — MACD 히스토그램 3봉 연속 음수';
    if (s.includes('ATM_BUY'))
      return '✅ <b>5중 확인 매수</b> — 추세정렬 + MACD↑ + 거래량↑ + 눌림 + 반등 모두 통과';
    if (s.includes('ATM_ADD_BUY'))
      return '➕ <b>ATM 추가매수</b> — 추세 유지 중 EMA50 근처까지 하락 + RSI 과매도';

    // Regime pullback exits
    if (s.includes('TRAIL_STOP'))
      return '📉 <b>ATR 트레일링 스탑</b> — 진입 후 최고점 대비 ATR×2.0 하락하여 추적 손절 발동';
    if (s.includes('ATR_STOP_LOSS'))
      return '🛑 <b>ATR 손절</b> — 매수 평균가 대비 ATR×2.5 하락하여 손절 발동';
    if (s.includes('ATR_TAKE_PROFIT'))
      return '🎯 <b>ATR 익절</b> — 매수 평균가 대비 ATR×3.5 상승하여 익절 발동';
    if (s.includes('REGIME_BREAK'))
      return '⚠️ <b>레짐(추세) 붕괴 청산</b> — close가 EMA50 아래로 하락하여 상승 추세 이탈';
    if (s.includes('ADD_BUY_ATR'))
      return '➕ <b>ATR 추가매수</b> — 추세 유지 중 평균가 대비 ATR×1.0 하락';

    // Global TP/SL
    if (s.includes('TAKE_PROFIT'))
      return '🎯 <b>익절(TP)</b> — 설정된 이익 목표(%)에 도달하여 자동 매도';
    if (s.includes('STOP_LOSS'))
      return '🛑 <b>손절(SL)</b> — 설정된 손실 한도(%)에 도달하여 자동 매도';

    // Pattern-based signals
    if (s.includes('FVG pullback'))
      return '✅ <b>FVG 되돌림 매수</b> — 모멘텀 캔들의 갭 구간으로 되돌림 진입 후 양봉 반등 확인';
    if (s.includes('PULLBACK_RSI2'))
      return '✅ <b>RSI2 과매도 눌림</b> — 상승추세 + RSI(2)≤10 + close<EMA20';
    if (s.includes('PULLBACK_BB'))
      return '✅ <b>볼린저 하단 눌림</b> — 상승추세 + 볼린저 하단 접촉 + RSI(14)<45';
    if (s.includes('Bullish engulfing'))
      return '✅ <b>상승 장악형 확인</b> — 음봉을 완전히 덮는 양봉 + 추가 확인 양봉';
    if (s.includes('Bearish engulfing'))
      return '📉 <b>하락 장악형 청산</b> — 양봉을 완전히 삼키는 강한 음봉 출현';
    if (s.includes('Morning star'))
      return '✅ <b>모닝스타 반전 매수</b> — 큰 음봉→도지→큰 양봉 3봉 반전 완성';
    if (s.includes('Evening star'))
      return '📉 <b>이브닝스타 청산</b> — 큰 양봉→도지→큰 음봉 3봉 반전 완성';
    if (s.includes('Three white soldiers'))
      return '✅ <b>적삼병 매수</b> — 꼬리 짧은 양봉 3연속 + 거래량 확인';
    if (s.includes('Three black crows'))
      return '📉 <b>흑삼병 청산</b> — 꼬리 짧은 음봉 3연속 + 거래량 확인';
    if (s.includes('Inside bar breakout'))
      return '✅ <b>인사이드바 돌파 매수</b> — 마더바 범위 돌파 + 거래량 증가';
    if (s.includes('Bullish pinbar'))
      return '✅ <b>핀바 매수</b> — 긴 아래꼬리 핀바가 지지 구간에서 출현';
    if (s.includes('Three methods bullish'))
      return '✅ <b>상승 삼법형 매수</b> — 장대양봉→조정→장대양봉 돌파';
    if (s.includes('Three methods bearish'))
      return '📉 <b>하락 삼법형 청산</b> — 장대음봉→조정→장대음봉 하락 지속';
    if (s.includes('DownStreak'))
      return '✅ <b>연속 하락 반등 매수</b> — 설정된 연속 하락 횟수 도달';
    if (/Extra down candle|add buy/i.test(s))
      return '➕ <b>연속 하락 추가매수</b> — 포지션 보유 중 추가 하락으로 평단가 낮추기';
    if (s.includes('TP reached'))
      return '🎯 <b>전략 내부 익절</b> — 평균매수가 대비 목표 수익률 도달';

    // Strategy lock / confidence / time stop
    if (s.includes('STRATEGY_LOCK'))
      return '🔒 <b>전략잠금 차단</b> — 매수한 전략과 다른 전략의 매도/추가매수 신호 차단';
    if (s.includes('LOW_CONFIDENCE'))
      return '⚡ <b>신뢰도 미달 차단</b> — 패턴 점수가 설정된 최소 기준 미달';
    if (s.includes('TIME_STOP'))
      return '⏰ <b>시간 초과 청산</b> — 매수 전용 전략 포지션이 설정 시간 초과 + 손실 상태';

    // Sync
    if (s.includes('BUY_SYNC') || /매수복구/i.test(s))
      return 'ℹ️ <b>포지션 복구</b> — 업비트 보유 자산이 봇 DB에 없어 자동 동기화';

    // Fallback: show raw note
    return esc(s);
  }

  // ───── render candlestick chart ─────
  function renderChart(modal, candles, event) {
    const container = modal.querySelector('#chartPopupContainer');
    const loading = modal.querySelector('#chartPopupLoading');
    if (loading) loading.style.display = 'none';

    if (!candles || candles.length === 0) {
      container.innerHTML = '<div style="display:flex;align-items:center;justify-content:center;height:100%;color:var(--muted)">캔들 데이터 없음</div>';
      return;
    }

    // Detect theme
    const isDark = document.body.getAttribute('data-theme') !== 'light';

    const bgColor = isDark ? '#0a0e1a' : '#f8f9fc';
    const textColor = isDark ? '#9fb0d6' : '#4a5b86';
    const gridColor = isDark ? 'rgba(255,255,255,.06)' : 'rgba(16,27,61,.08)';
    const borderColor = isDark ? 'rgba(255,255,255,.10)' : 'rgba(16,27,61,.12)';
    const upColor = '#ff4d6d';    // 상승 = 빨강 (Korean convention)
    const downColor = '#4dabf7';  // 하락 = 파랑 (Korean convention)
    const crosshairColor = isDark ? 'rgba(255,255,255,.3)' : 'rgba(0,0,0,.3)';

    // Clear previous
    if (chartInstance) {
      chartInstance.remove();
      chartInstance = null;
    }
    // Remove old chart canvas if any
    Array.from(container.querySelectorAll('div:not(#chartPopupLoading)')).forEach(el => el.remove());

    const chartDiv = document.createElement('div');
    chartDiv.style.cssText = 'width:100%;height:100%';
    container.appendChild(chartDiv);

    // Create chart
    chartInstance = LightweightCharts.createChart(chartDiv, {
      width: chartDiv.clientWidth,
      height: chartDiv.clientHeight || 340,
      layout: {
        background: { type: 'solid', color: bgColor },
        textColor: textColor,
        fontFamily: 'ui-sans-serif, system-ui, -apple-system, sans-serif',
        fontSize: 11
      },
      grid: {
        vertLines: { color: gridColor },
        horzLines: { color: gridColor }
      },
      crosshair: {
        mode: LightweightCharts.CrosshairMode.Normal,
        vertLine: { color: crosshairColor, labelBackgroundColor: isDark ? '#1a2644' : '#e0e4ed' },
        horzLine: { color: crosshairColor, labelBackgroundColor: isDark ? '#1a2644' : '#e0e4ed' }
      },
      rightPriceScale: {
        borderColor: borderColor,
        scaleMargins: { top: 0.08, bottom: 0.25 }
      },
      timeScale: {
        borderColor: borderColor,
        timeVisible: true,
        secondsVisible: false
      },
      localization: {
        locale: 'ko-KR',
        timeFormatter: (ts) => {
          // ★ 캔들 time에 이미 KST_OFFSET 적용됨 → 추가 변환 불필요
          const d = new Date(ts * 1000);
          return `${pad(d.getUTCMonth()+1)}/${pad(d.getUTCDate())} ${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}`;
        }
      }
    });

    // Candlestick series
    candleSeries = chartInstance.addCandlestickSeries({
      upColor: upColor,
      downColor: downColor,
      borderUpColor: upColor,
      borderDownColor: downColor,
      wickUpColor: upColor,
      wickDownColor: downColor,
      lastValueVisible: false,
      priceLineVisible: false
    });
    // ★ KST 변환: LightweightCharts는 시간축을 UTC로 표시하므로,
    //   캔들 time에 KST_OFFSET(+9h)를 더해 x축이 한국 시간을 표시하도록 함
    candleSeries.setData(candles.map(c => ({
      time: c.time + KST_OFFSET,
      open: c.open,
      high: c.high,
      low: c.low,
      close: c.close
    })));

    // Volume histogram
    volumeSeries = chartInstance.addHistogramSeries({
      priceFormat: { type: 'volume' },
      priceScaleId: 'vol',
      color: 'rgba(43,118,255,.2)'
    });
    chartInstance.priceScale('vol').applyOptions({
      scaleMargins: { top: 0.82, bottom: 0 }
    });
    volumeSeries.setData(candles.map(c => ({
      time: c.time + KST_OFFSET,
      value: c.volume,
      color: c.close >= c.open ? 'rgba(255,77,109,.25)' : 'rgba(77,171,247,.25)'
    })));

    // ─── Event marker + Strategy Annotations ───
    if (event.tsMs && event.price) {
      const eventTimeSec = Math.floor(event.tsMs / 1000);
      // 캔들 time = open(시작) 시각이므로, 이벤트가 속한 캔들 =
      // open time <= eventTime 인 캔들 중 가장 가까운(마지막) 캔들
      let closestCandle = candles[0];
      let bestDiff = Infinity;
      for (var ci = 0; ci < candles.length; ci++) {
        var diff = eventTimeSec - candles[ci].time;
        if (diff >= 0 && diff < bestDiff) {
          bestDiff = diff;
          closestCandle = candles[ci];
        }
      }
      // Fallback: 모든 캔들이 이벤트 이후인 경우 가장 가까운 캔들 사용
      if (bestDiff === Infinity) {
        var minDiff = Infinity;
        for (var ci2 = 0; ci2 < candles.length; ci2++) {
          var d = Math.abs(candles[ci2].time - eventTimeSec);
          if (d < minDiff) { minDiff = d; closestCandle = candles[ci2]; }
        }
      }

      const isBuy = /BUY/i.test(event.action);
      const isSell = /SELL/i.test(event.action) && !/PENDING|BLOCKED/i.test(event.action);
      const priceLabel = event.price ? Number(event.price).toLocaleString() : '';
      const idx = candles.indexOf(closestCandle);

      // ★ 마커/가격선에 사용할 시간은 KST 오프셋 적용 (캔들 데이터와 일치시킴)
      const closestTimeKST = closestCandle.time + KST_OFFSET;

      // Main event marker — 비매매 액션(전략잠금, 신뢰도미달 등)도 올바른 텍스트 표시
      var markerText = isBuy ? 'BUY' : (isSell ? 'SELL' : labelAction(event.action));
      const mainMarker = {
        time: closestTimeKST,
        position: isBuy ? 'belowBar' : 'aboveBar',
        color: isBuy ? upColor : (isSell ? downColor : '#ffa726'),
        shape: isBuy ? 'arrowUp' : 'arrowDown',
        text: markerText + (priceLabel ? ' ₩' + priceLabel : '')
      };

      // Build pattern annotations
      const ann = buildAnnotations(candles, idx, event.patternType || '', event.note || '', event.action || '');
      const allMarkers = [...ann.markers, mainMarker].sort((a, b) => a.time - b.time);
      candleSeries.setMarkers(allMarkers);

      // Scroll to marker with padding (show more history for annotations)
      const lookBack = Math.max(40, ann.lookBack || 40);
      const from = candles[Math.max(0, idx - lookBack)];
      const to = candles[Math.min(candles.length - 1, idx + 15)];
      if (from && to) {
        chartInstance.timeScale().setVisibleRange({ from: from.time + KST_OFFSET, to: to.time + KST_OFFSET });
      }

      // Price line at event price
      var plTitle = isBuy ? '매수가' : (isSell ? '매도가' : labelAction(event.action));
      var plColor = isBuy ? upColor : (isSell ? downColor : '#ffa726');
      candleSeries.createPriceLine({
        price: event.price,
        color: plColor,
        lineWidth: 1,
        lineStyle: LightweightCharts.LineStyle.Dashed,
        axisLabelVisible: true,
        title: plTitle
      });

      // SELL: draw avg buy price line
      if (!isBuy && event.avgBuyPrice > 0) {
        candleSeries.createPriceLine({
          price: event.avgBuyPrice,
          color: '#ffa726',
          lineWidth: 1,
          lineStyle: LightweightCharts.LineStyle.Dotted,
          axisLabelVisible: true,
          title: '매수 평균가'
        });
      }

      // Annotation price lines
      ann.priceLines.forEach(pl => candleSeries.createPriceLine(pl));

      // ─── Auto-fit: ensure all price lines are visible in chart ───
      try {
        const allPrices = [event.price];
        if (event.avgBuyPrice > 0) allPrices.push(event.avgBuyPrice);
        ann.priceLines.forEach(pl => { if (pl.price) allPrices.push(pl.price); });

        // Find candle min/max in visible range
        let candleMin = Infinity, candleMax = -Infinity;
        const startIdx = Math.max(0, idx - 60);
        const endIdx = Math.min(candles.length - 1, idx + 15);
        for (let i = startIdx; i <= endIdx; i++) {
          if (candles[i]) {
            if (candles[i].low < candleMin) candleMin = candles[i].low;
            if (candles[i].high > candleMax) candleMax = candles[i].high;
          }
        }

        // Check if any price line is outside candle range
        let needRefit = false;
        for (const p of allPrices) {
          if (p && (p < candleMin * 0.995 || p > candleMax * 1.005)) {
            needRefit = true;
            break;
          }
        }

        if (needRefit) {
          // Include all price lines in visible range by adjusting scale margins
          let visMin = candleMin, visMax = candleMax;
          for (const p of allPrices) {
            if (p && p < visMin) visMin = p;
            if (p && p > visMax) visMax = p;
          }
          const range = visMax - visMin;
          const padding = range * 0.08;
          visMin -= padding;
          visMax += padding;

          // Use logical range to force price scale to include price lines
          chartInstance.priceScale('right').applyOptions({
            autoScale: true,
            scaleMargins: { top: 0.05, bottom: 0.25 }
          });

          // Force re-fit by temporarily adding invisible series at extreme prices
          const helperSeries = chartInstance.addLineSeries({
            color: 'transparent',
            lineWidth: 0,
            priceScaleId: 'right',
            lastValueVisible: false,
            priceLineVisible: false,
            crosshairMarkerVisible: false
          });
          // Add invisible data points at the price extremes to force scale
          const t1 = candles[startIdx] ? candles[startIdx].time + KST_OFFSET : candles[0].time + KST_OFFSET;
          const t2 = candles[endIdx] ? candles[endIdx].time + KST_OFFSET : candles[candles.length - 1].time + KST_OFFSET;
          helperSeries.setData([
            { time: t1, value: visMin },
            { time: t2, value: visMax }
          ]);
        }
      } catch (e) {
        console.warn('Auto-fit error:', e);
      }
    }

    // Responsive
    const ro = new ResizeObserver(() => {
      if (chartInstance && chartDiv.clientWidth > 0) {
        chartInstance.applyOptions({ width: chartDiv.clientWidth, height: chartDiv.clientHeight || 340 });
      }
    });
    ro.observe(chartDiv);
  }

  // ───── Build strategy-specific chart annotations ─────
  // Returns { markers: [], priceLines: [], lookBack: number }
  function buildAnnotations(candles, eventIdx, patternType, note, action) {
    const markers = [];
    const priceLines = [];
    let lookBack = 40;
    const ORANGE = '#ffa726';
    const CYAN = '#26c6da';
    const PURPLE = '#ab47bc';
    const BLUE = '#2b76ff';
    const BULL = '#ff4d6d';    // 상승/양봉 마커 (red, Korean convention)
    const BEAR = '#4dabf7';    // 하락/음봉 마커 (blue, Korean convention)
    const PL_TP = '#20c997';   // 익절 가격선 (green)
    const PL_SL = '#ff7043';   // 손절 가격선 (orange-red)

    function c(i) { return (i >= 0 && i < candles.length) ? candles[i] : null; }
    function isBear(cd) { return cd && cd.close < cd.open; }
    function isBull(cd) { return cd && cd.close >= cd.open; }
    function mk(i, text, color, pos, shape) {
      var cd = c(i);
      if (!cd) return;
      markers.push({ time: cd.time + KST_OFFSET, position: pos || 'aboveBar', color: color || ORANGE, shape: shape || 'circle', text: text, size: 1 });
    }
    function pl(price, title, color, style) {
      if (!price || isNaN(price)) return;
      priceLines.push({
        price: price,
        color: color || BLUE,
        lineWidth: 1,
        lineStyle: style != null ? style : LightweightCharts.LineStyle.Dotted,
        axisLabelVisible: true,
        title: title || ''
      });
    }

    try {
      switch (patternType) {

        // ── 1. 연속 하락 반등 ──
        case 'CONSECUTIVE_DOWN_REBOUND': {
          if (/DownStreak|add buy/i.test(note)) {
            // Parse streak count from "DownStreak >= N"
            var m = note.match(/DownStreak\s*>=?\s*(\d+)/i);
            var N = m ? parseInt(m[1]) : 3;
            // Mark consecutive down candles going back from event
            var count = 0;
            for (var i = eventIdx; i >= 0 && count < N; i--) {
              if (isBear(c(i))) {
                count++;
                mk(i, '↓' + (N - count + 1), BEAR, 'aboveBar', 'circle');
              } else if (count > 0) {
                break; // streak broken
              }
            }
            if (count < N) {
              // Fallback: just mark N candles back
              for (var j = 1; j <= N && eventIdx - j >= 0; j++) {
                mk(eventIdx - j, '↓' + j, BEAR, 'aboveBar', 'circle');
              }
            }
            lookBack = Math.max(lookBack, N + 10);
          }
          if (/TP reached/i.test(note)) {
            // Internal TP — parse avg from context (shown in avg buy price line already)
          }
          break;
        }

        // ── 2. 모멘텀 FVG 되돌림 ──
        case 'MOMENTUM_FVG_PULLBACK': {
          // Parse zone: "FVG pullback buy (zone 123.45~234.56) momATR=2.1x gapATR=0.35x"
          var zm = note.match(/zone\s+([\d.]+)\s*~\s*([\d.]+)/);
          if (zm) {
            var zoneLow = parseFloat(zm[1]);
            var zoneHigh = parseFloat(zm[2]);
            pl(zoneHigh, 'FVG 상단', CYAN, LightweightCharts.LineStyle.Dashed);
            pl(zoneLow, 'FVG 하단', CYAN, LightweightCharts.LineStyle.Dashed);
          }
          // Parse momentum strength
          var momM = note.match(/momATR=([\d.]+)x/);
          var gapM = note.match(/gapATR=([\d.]+)x/);

          // Find momentum candle: look back for large bullish candle
          for (var i = eventIdx - 1; i >= Math.max(0, eventIdx - 20); i--) {
            var cd = c(i);
            if (cd && isBull(cd)) {
              var body = Math.abs(cd.close - cd.open);
              var range = cd.high - cd.low;
              if (range > 0 && body / range >= 0.7) {
                var label = '모멘텀';
                if (momM) label += ' ' + momM[1] + 'x';
                mk(i, label, ORANGE, 'aboveBar', 'square');
                // Check if next candle forms the gap
                var next = c(i + 1);
                if (next && next.low > cd.close * 0.998) {
                  mk(i + 1, '갭', PURPLE, 'belowBar', 'circle');
                }
                break;
              }
            }
          }
          mk(eventIdx, '진입', BULL, 'belowBar', 'circle');
          lookBack = 25;
          break;
        }

        // ── 3. 상승 장악형 확인 ──
        case 'BULLISH_ENGULFING_CONFIRM': {
          if (eventIdx >= 2) {
            mk(eventIdx - 2, '음봉', BEAR, 'aboveBar', 'circle');
            mk(eventIdx - 1, '장악', BULL, 'belowBar', 'square');
            mk(eventIdx, '확인', BULL, 'belowBar', 'circle');
          }
          break;
        }

        // ── 4. 하락 장악형 ──
        case 'BEARISH_ENGULFING': {
          if (eventIdx >= 1) {
            mk(eventIdx - 1, '양봉', BULL, 'belowBar', 'circle');
            mk(eventIdx, '장악', BEAR, 'aboveBar', 'square');
          }
          break;
        }

        // ── 5. 모닝스타 ──
        case 'MORNING_STAR': {
          if (eventIdx >= 2) {
            mk(eventIdx - 2, '①음봉', BEAR, 'aboveBar', 'circle');
            mk(eventIdx - 1, '②도지', ORANGE, 'belowBar', 'circle');
            mk(eventIdx, '③양봉', BULL, 'belowBar', 'circle');
          }
          break;
        }

        // ── 6. 이브닝스타 ──
        case 'EVENING_STAR_SELL': {
          if (eventIdx >= 2) {
            mk(eventIdx - 2, '①양봉', BULL, 'belowBar', 'circle');
            mk(eventIdx - 1, '②도지', ORANGE, 'aboveBar', 'circle');
            mk(eventIdx, '③음봉', BEAR, 'aboveBar', 'circle');
          }
          break;
        }

        // ── 7. 적삼병 ──
        case 'THREE_WHITE_SOLDIERS': {
          if (eventIdx >= 2) {
            mk(eventIdx - 2, '1봉', BULL, 'belowBar', 'circle');
            mk(eventIdx - 1, '2봉', BULL, 'belowBar', 'circle');
            mk(eventIdx, '3봉', BULL, 'belowBar', 'circle');
          }
          break;
        }

        // ── 8. 흑삼병 ──
        case 'THREE_BLACK_CROWS_SELL': {
          if (eventIdx >= 2) {
            mk(eventIdx - 2, '1봉', BEAR, 'aboveBar', 'circle');
            mk(eventIdx - 1, '2봉', BEAR, 'aboveBar', 'circle');
            mk(eventIdx, '3봉', BEAR, 'aboveBar', 'circle');
          }
          break;
        }

        // ── 9. 인사이드바 돌파 ──
        case 'INSIDE_BAR_BREAKOUT': {
          if (eventIdx >= 2) {
            mk(eventIdx - 2, '마더바', ORANGE, 'aboveBar', 'square');
            mk(eventIdx - 1, '인사이드', PURPLE, 'belowBar', 'circle');
            mk(eventIdx, '돌파', BULL, 'belowBar', 'circle');
            // Draw mother bar range
            var mother = c(eventIdx - 2);
            if (mother) {
              pl(mother.high, '마더 고가', ORANGE, LightweightCharts.LineStyle.Dotted);
              pl(mother.low, '마더 저가', ORANGE, LightweightCharts.LineStyle.Dotted);
            }
          }
          break;
        }

        // ── 10. 강세 핀바 ──
        case 'BULLISH_PINBAR_ORDERBLOCK': {
          mk(eventIdx, '핀바', BULL, 'belowBar', 'square');
          // Mark long lower wick visually via price line at low
          var pin = c(eventIdx);
          if (pin) {
            pl(pin.low, '핀바 저점', ORANGE, LightweightCharts.LineStyle.Dotted);
          }
          break;
        }

        // ── 11. 상승 삼법형 ──
        case 'THREE_METHODS_BULLISH': {
          // Java: pull=3~5 → total=5~7 (1 momentum + 3~5 pullback + 1 breakout)
          // 가장 큰 패턴부터 체크: 첫 캔들이 양봉이고 모멘텀(body≥50%)인 경우 매칭
          var tmFound = false;
          for (var span = 6; span >= 4 && !tmFound; span--) {
            if (eventIdx >= span) {
              var fi = eventIdx - span;
              var fc = c(fi);
              if (fc && isBull(fc) && (fc.high - fc.low) > 0
                  && Math.abs(fc.close - fc.open) / (fc.high - fc.low) >= 0.4) {
                mk(fi, '①장대양봉', BULL, 'belowBar', 'square');
                for (var m = fi + 1; m < eventIdx; m++) mk(m, '조정', ORANGE, 'aboveBar', 'circle');
                mk(eventIdx, '돌파', BULL, 'belowBar', 'square');
                tmFound = true;
              }
            }
          }
          break;
        }

        // ── 12. 하락 삼법형 ──
        case 'THREE_METHODS_BEARISH': {
          // Java: pull=3~5 → total=5~7 (1 momentum + 3~5 pullback + 1 breakdown)
          var tmfB = false;
          for (var span2 = 6; span2 >= 4 && !tmfB; span2--) {
            if (eventIdx >= span2) {
              var fi2 = eventIdx - span2;
              var fc2 = c(fi2);
              if (fc2 && isBear(fc2) && (fc2.high - fc2.low) > 0
                  && Math.abs(fc2.close - fc2.open) / (fc2.high - fc2.low) >= 0.4) {
                mk(fi2, '①장대음봉', BEAR, 'aboveBar', 'square');
                for (var m2 = fi2 + 1; m2 < eventIdx; m2++) mk(m2, '조정', ORANGE, 'belowBar', 'circle');
                mk(eventIdx, '하락', BEAR, 'aboveBar', 'square');
                tmfB = true;
              }
            }
          }
          break;
        }

        // ── 13. ATM (적응형 추세 모멘텀) ──
        case 'ADAPTIVE_TREND_MOMENTUM': {
          // BUY: parse EMA values
          var emaM = note.match(/ema20=([\d.]+).*?ema50=([\d.]+).*?ema100=([\d.]+)/);
          if (emaM) {
            pl(parseFloat(emaM[1]), 'EMA20', '#ffa726', LightweightCharts.LineStyle.Dotted);
            pl(parseFloat(emaM[2]), 'EMA50', '#42a5f5', LightweightCharts.LineStyle.Dotted);
            pl(parseFloat(emaM[3]), 'EMA100', '#ab47bc', LightweightCharts.LineStyle.Dotted);
          }
          // SELL exits: parse key levels
          if (/CHANDELIER_EXIT|HARD_STOP/.test(note)) {
            var slm = note.match(/sl=([\d.]+)/);
            var chm = note.match(/chandelier=([\d.]+)/);
            var peakm = note.match(/peak=([\d.]+)/);
            if (slm) pl(parseFloat(slm[1]), '하드 손절', PL_SL, LightweightCharts.LineStyle.Dashed);
            if (chm) pl(parseFloat(chm[1]), '샹들리에 스탑', PL_SL, LightweightCharts.LineStyle.Dashed);
            if (peakm) pl(parseFloat(peakm[1]), '최고점', PL_TP, LightweightCharts.LineStyle.Dotted);
          }
          if (/ATM_TAKE_PROFIT/.test(note)) {
            var tpm = note.match(/tp=([\d.]+)/);
            if (tpm) pl(parseFloat(tpm[1]), '익절 목표', PL_TP, LightweightCharts.LineStyle.Dashed);
          }
          if (/ATM_TREND_BREAK/.test(note)) {
            var tbm = note.match(/ema20=([\d.]+).*?ema50=([\d.]+)/);
            if (tbm) {
              pl(parseFloat(tbm[1]), 'EMA20↓', '#ff7043', LightweightCharts.LineStyle.Dotted);
              pl(parseFloat(tbm[2]), 'EMA50', '#42a5f5', LightweightCharts.LineStyle.Dotted);
            }
          }
          if (/ATM_MOMENTUM_LOSS/.test(note)) {
            // MACD 히스토그램 3봉 연속 음수 — 특정 가격 레벨 없음, 이벤트 캔들 전후에 마커 표시
            if (eventIdx >= 2) {
              mk(eventIdx - 2, 'MACD↓', BEAR, 'aboveBar', 'circle');
              mk(eventIdx - 1, 'MACD↓', BEAR, 'aboveBar', 'circle');
              mk(eventIdx, 'MACD↓', BEAR, 'aboveBar', 'circle');
            }
          }
          // ADD_BUY: parse near EMA50
          if (/ATM_ADD_BUY/.test(note)) {
            var abm = note.match(/near_ema50=([\d.]+)/);
            if (abm) pl(parseFloat(abm[1]), 'EMA50', '#42a5f5', LightweightCharts.LineStyle.Dotted);
          }
          break;
        }

        // ── 14. 레짐 눌림 ──
        case 'REGIME_PULLBACK': {
          // BUY: parse key levels
          if (/PULLBACK_RSI2|PULLBACK_BB/.test(note)) {
            var em = note.match(/ema20=([\d.]+)/);
            var bb = note.match(/bb_low=([\d.]+)/);
            if (em) pl(parseFloat(em[1]), 'EMA20', '#ffa726', LightweightCharts.LineStyle.Dotted);
            if (bb) pl(parseFloat(bb[1]), 'BB 하단', PURPLE, LightweightCharts.LineStyle.Dotted);
          }
          // SELL exits
          if (/TRAIL_STOP|ATR_STOP/.test(note)) {
            var sm = note.match(/sl=([\d.]+)/);
            var tm = note.match(/trail=([\d.]+)/);
            var peakm2 = note.match(/peak=([\d.]+)/);
            if (sm) pl(parseFloat(sm[1]), 'ATR 손절', PL_SL, LightweightCharts.LineStyle.Dashed);
            if (tm) pl(parseFloat(tm[1]), '트레일 스탑', PL_SL, LightweightCharts.LineStyle.Dashed);
            if (peakm2) pl(parseFloat(peakm2[1]), '최고점', PL_TP, LightweightCharts.LineStyle.Dotted);
          }
          if (/ATR_TAKE_PROFIT/.test(note)) {
            var tpm2 = note.match(/tp=([\d.]+)/);
            if (tpm2) pl(parseFloat(tpm2[1]), '익절 목표', PL_TP, LightweightCharts.LineStyle.Dashed);
          }
          if (/REGIME_BREAK/.test(note)) {
            var rbm = note.match(/ema50=([\d.]+)/);
            if (rbm) pl(parseFloat(rbm[1]), 'EMA50 (붕괴)', '#ff7043', LightweightCharts.LineStyle.Dashed);
          }
          // ADD_BUY
          if (/ADD_BUY_ATR/.test(note)) {
            var abm2 = note.match(/threshold=([\d.]+)/);
            if (abm2) pl(parseFloat(abm2[1]), '추매 기준가', CYAN, LightweightCharts.LineStyle.Dotted);
          }
          break;
        }

        // ── TP / SL (global) ──
        case 'TAKE_PROFIT': case 'TP_SELL': {
          // TP line is already shown via event price
          break;
        }
        case 'STOP_LOSS': case 'SL_SELL': {
          break;
        }
        case 'TIME_STOP': {
          break;
        }
      } // end switch
    } catch (e) {
      console.warn('Annotation error:', e);
    }

    return { markers: markers, priceLines: priceLines, lookBack: lookBack };
  }

  // ───── helpers ─────
  function esc(s) { return String(s ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); }
  function fmt(n) { return (n == null || isNaN(n)) ? '-' : Number(n).toLocaleString(); }
  function pad(n) { return String(n).padStart(2, '0'); }

  function labelAction(a) {
    if (!a) return '-';
    const map = {
      'BUY': '매수', 'SELL': '매도', 'ADD_BUY': '추가매수',
      'BUY_BLOCKED': '매수차단', 'SELL_PENDING': '매도대기',
      'BUY_PENDING': '매수대기', 'BUY_PARTIAL': '부분매수',
      'ADD_BUY_PARTIAL': '부분추가매수', 'BUY_FAILED': '매수실패',
      'SIGNAL_ONLY': '시그널', 'TP_SELL': '익절매도', 'SL_SELL': '손절매도',
      'BUY_SYNC': '매수복구', 'SELL_SYNC': '매도정리',
      'STRATEGY_LOCK': '전략잠금',
      'LOW_CONFIDENCE': '신뢰도미달',
      'TIME_STOP': '시간초과청산'
    };
    return map[a] || a;
  }

  return { open, close };
})();
