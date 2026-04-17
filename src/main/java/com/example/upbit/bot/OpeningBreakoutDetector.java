package com.example.upbit.bot;

import com.example.upbit.market.PriceUpdateListener;
import com.example.upbit.market.SharedPriceService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 오프닝 레인지 돌파 실시간 감지기.
 *
 * SharedPriceService의 실시간 가격을 구독하여 rangeHigh 돌파를 즉시 감지.
 * confirmCount 연속 통과 시 OpeningScannerService에 콜백.
 *
 * 흐름:
 * 1. OpeningScannerService가 range 수집 완료 후 setRangeHighMap() 호출
 * 2. start()로 SharedPriceService 리스너 등록
 * 3. 실시간 가격 수신 → 돌파 체크 → confirmCount 통과 시 listener.onBreakoutConfirmed() 호출
 * 4. stop()으로 리스너 해제
 */
@Component
public class OpeningBreakoutDetector {

    private static final Logger log = LoggerFactory.getLogger(OpeningBreakoutDetector.class);

    private final SharedPriceService sharedPriceService;

    // Breakout detection state
    private final ConcurrentHashMap<String, Double> rangeHighMap = new ConcurrentHashMap<String, Double>();
    private final ConcurrentHashMap<String, Double> latestPrices = new ConcurrentHashMap<String, Double>();
    private final ConcurrentHashMap<String, Integer> confirmCounts = new ConcurrentHashMap<String, Integer>();
    private final Set<String> confirmedMarkets = ConcurrentHashMap.newKeySet();

    // confirm 방향 확인 (2026-04-11 추가)
    // confirm1 가격 기록 + confirm 간 최소 500ms 간격 + confirm3 or confirm4 ≥ confirm1
    private final ConcurrentHashMap<String, Double> confirmFirstPrice = new ConcurrentHashMap<String, Double>();
    private final ConcurrentHashMap<String, Long> confirmLastTime = new ConcurrentHashMap<String, Long>();
    private volatile long confirmMinIntervalMs = 500; // confirm 간 최소 500ms

    // Configuration
    private volatile double breakoutPct = 1.0;
    private volatile int requiredConfirm = 4; // 2026-04-11: 3→4 (방향 확인용)

    // Entry window 체크 (2026-04-09 추가)
    // 09:00~09:05 사이에 listener가 미리 등록되어 가격 update를 받지만,
    // entry window(예: 09:05~10:30) 안에서만 confirm 카운트를 누적하도록 제한.
    // 그렇지 않으면 09:00 +1% 돌파 후 09:04 +0.5% 떨어진 가격이 09:05:00 시점에
    // 옵션 B 매수로 이어져 stale 매수 위험.
    private volatile int entryStartMinOfDay = -1;  // -1 = 비활성 (모든 시각 허용)
    private volatile int entryEndMinOfDay = -1;

    // Callback
    private volatile BreakoutListener listener;
    private volatile PriceUpdateListener priceListener;

    // 실시간 TP/SL 체크용 캐시 (OpeningScannerService에서 업데이트)
    // 포맷: [avgPrice, openedAtEpochMs, volumeRank]
    private final ConcurrentHashMap<String, double[]> positionCache = new ConcurrentHashMap<String, double[]>();
    private volatile double cachedTpAtrMult = 1.5;
    private volatile double cachedSlPct = 2.8;
    private volatile double cachedTrailAtrMult = 0.7;

    // SL 종합안 캐시 (DB 설정값, OpeningScannerService에서 갱신)
    private volatile long cachedGracePeriodMs = 60_000L;        // grace_period_sec
    private volatile long cachedWidePeriodMs = 15 * 60_000L;    // wide_period_min
    private volatile double cachedWideSlTop10Pct = 6.2;
    private volatile double cachedWideSlTop20Pct = 5.0;
    private volatile double cachedWideSlTop50Pct = 3.5;
    private volatile double cachedWideSlOtherPct = 3.0;
    private volatile double cachedTightSlPct = 3.0;

    /** 돌파 확인 시 호출되는 콜백 인터페이스 */
    public interface BreakoutListener {
        void onBreakoutConfirmed(String market, double price, double rangeHigh, double breakoutPctActual);
        default void onTpSlTriggered(String market, double price, String sellType, String reason) {}
    }

    // 실시간 TP 트레일링 상태
    private final ConcurrentHashMap<String, Double> peakPrices = new ConcurrentHashMap<String, Double>();
    private final ConcurrentHashMap<String, Double> troughPrices = new ConcurrentHashMap<String, Double>();
    private final ConcurrentHashMap<String, Boolean> tpActivated = new ConcurrentHashMap<String, Boolean>();
    private volatile double tpActivatePct = 2.0;
    private volatile double trailFromPeakPct = 1.0;

    // V111: Split-Exit 분할 익절
    private final ConcurrentHashMap<String, Integer> splitPhaseMap = new ConcurrentHashMap<String, Integer>();
    private volatile boolean splitExitEnabled = false;
    private volatile double splitTpPct = 1.5;
    private volatile double splitRatio = 0.60;
    private volatile double trailDropAfterSplit = 1.2;
    // V115: 1차 매도 TRAIL 방식
    private final ConcurrentHashMap<String, Boolean> split1stTrailArmed = new ConcurrentHashMap<String, Boolean>();
    private volatile double split1stTrailDropPct = 0.5;

    public OpeningBreakoutDetector(SharedPriceService sharedPriceService) {
        this.sharedPriceService = sharedPriceService;
    }

    /** SharedPriceService 노출 (OpeningScannerService에서 volumeRank 조회용) */
    public SharedPriceService getSharedPriceService() {
        return sharedPriceService;
    }

    // ========== Configuration ==========

    public void setBreakoutPct(double pct) { this.breakoutPct = pct; }
    public void setRequiredConfirm(int count) { this.requiredConfirm = count; }
    public void setListener(BreakoutListener listener) { this.listener = listener; }
    public void setTpActivatePct(double pct) { this.tpActivatePct = pct; }

    /** Test-only: override confirm interval (default 500ms) */
    void setConfirmMinIntervalMs(long ms) { this.confirmMinIntervalMs = ms; }
    public void setTrailFromPeakPct(double pct) { this.trailFromPeakPct = pct; }

    // V111: Split-Exit 설정
    public void setSplitExitEnabled(boolean enabled) { this.splitExitEnabled = enabled; }
    public void setSplitTpPct(double pct) { this.splitTpPct = pct; }
    public void setSplitRatio(double ratio) { this.splitRatio = ratio; }
    public void setTrailDropAfterSplit(double pct) { this.trailDropAfterSplit = pct; }
    /** V115: 1차 매도 TRAIL drop % */
    public void setSplit1stTrailDropPct(double pct) { this.split1stTrailDropPct = pct; }
    public int getSplitPhase(String market) {
        Integer phase = splitPhaseMap.get(market);
        return phase != null ? phase : 0;
    }
    public void setSplitPhase(String market, int phase) { splitPhaseMap.put(market, phase); }

    /** V118: 현재 in-memory peak 가격 조회 (DB 영속화 동기화용). 미등록 시 null */
    public Double getPeak(String market) {
        return peakPrices.get(market);
    }

    /** V118: 1차 TRAIL armed 상태 조회 (DB 영속화 동기화용) */
    public boolean isArmed(String market) {
        Boolean armed = split1stTrailArmed.get(market);
        return armed != null && armed;
    }

    /**
     * Entry window 시각 설정 (KST 기준 분 단위, hour*60+min).
     * checkBreakout()이 이 시각 안에서만 confirm 카운트를 누적한다.
     * -1로 설정하면 시각 제한 비활성 (모든 시각 허용).
     */
    public void setEntryWindow(int startMinOfDay, int endMinOfDay) {
        this.entryStartMinOfDay = startMinOfDay;
        this.entryEndMinOfDay = endMinOfDay;
    }

    public void setRangeHighMap(Map<String, Double> map) {
        rangeHighMap.clear();
        rangeHighMap.putAll(map);
        confirmCounts.clear();
        confirmedMarkets.clear();
        latestPrices.clear();
        log.info("[BreakoutDetector] rangeHighMap set: {} markets", map.size());
    }

    /**
     * 단일 마켓의 rangeHigh를 동적 추가 (entry phase 중 신규 TOP-N 감지 시).
     * 기존 rangeHighMap/confirmedMarkets는 건드리지 않음.
     */
    public void addRangeHigh(String market, double rangeHigh) {
        rangeHighMap.put(market, rangeHigh);
        log.info("[BreakoutDetector] rangeHigh 동적 추가: {} = {}", market, rangeHigh);
    }

    public boolean isAlreadyConfirmed(String market) {
        return confirmedMarkets.contains(market);
    }

    /**
     * 매도 후 호출 — 동일 마켓의 BREAKOUT 재감지를 허용.
     * confirmedMarkets와 confirmCounts 모두 초기화.
     * 즉시 재매수는 OpeningScannerService.sellCooldownMap이 별도 차단.
     *
     * 운영 사고 (2026-04-08 KRW-DRIFT): SELL 후 confirmedMarkets 미해제로
     * 같은 종목의 두 번째 BREAKOUT을 옵션 B(WS)가 영원히 무시 → 5분봉 폴링이 늦게 fallback.
     */
    public void releaseMarket(String market) {
        if (market == null) return;
        confirmedMarkets.remove(market);
        confirmCounts.remove(market);
        confirmFirstPrice.remove(market);
        confirmLastTime.remove(market);
    }

    public void addPosition(String market, double avgPrice) {
        addPosition(market, avgPrice, System.currentTimeMillis(), 999);
    }

    /** SL 종합안용 — openedAt 포함 (rank 없음) */
    public void addPosition(String market, double avgPrice, long openedAtEpochMs) {
        addPosition(market, avgPrice, openedAtEpochMs, 999);
    }

    /** SL 종합안 + TOP-N 차등용 — rank 포함 */
    public void addPosition(String market, double avgPrice, long openedAtEpochMs, int volumeRank) {
        positionCache.put(market, new double[]{avgPrice, openedAtEpochMs, volumeRank});
        peakPrices.put(market, avgPrice);
        troughPrices.put(market, avgPrice);
        tpActivated.put(market, false);
        splitPhaseMap.put(market, 0);
        split1stTrailArmed.put(market, false);  // V115
    }

    /**
     * SL 종합안 설정 캐시 업데이트 (OpeningScannerService에서 호출).
     */
    public void updateSlConfig(int gracePeriodSec, int widePeriodMin,
                                double wideSlTop10, double wideSlTop20,
                                double wideSlTop50, double wideSlOther, double tightSl) {
        this.cachedGracePeriodMs = gracePeriodSec * 1000L;
        this.cachedWidePeriodMs = widePeriodMin * 60_000L;
        this.cachedWideSlTop10Pct = wideSlTop10;
        this.cachedWideSlTop20Pct = wideSlTop20;
        this.cachedWideSlTop50Pct = wideSlTop50;
        this.cachedWideSlOtherPct = wideSlOther;
        this.cachedTightSlPct = tightSl;
    }

    /** TOP-N 차등 SL 값 계산 (rank 1-based) */
    private double getWideSlForRank(int rank) {
        if (rank <= 10) return cachedWideSlTop10Pct;
        if (rank <= 20) return cachedWideSlTop20Pct;
        if (rank <= 50) return cachedWideSlTop50Pct;
        return cachedWideSlOtherPct;
    }

    public void removePosition(String market) {
        positionCache.remove(market);
        peakPrices.remove(market);
        troughPrices.remove(market);
        tpActivated.remove(market);
        splitPhaseMap.remove(market);
        split1stTrailArmed.remove(market);  // V115
    }

    public void updatePositionCache(Map<String, Double> positions) {
        updatePositionCache(positions, null);
    }

    /** openedAt 맵 포함 — 복구 시 정확한 그레이스 적용 */
    public void updatePositionCache(Map<String, Double> positions, Map<String, Long> openedAtMap) {
        updatePositionCache(positions, openedAtMap, null);
    }

    /** V111: splitPhaseMap 포함 — 재시작 복구 시 splitPhase 복원 */
    public void updatePositionCache(Map<String, Double> positions, Map<String, Long> openedAtMap,
                                     Map<String, Integer> splitPhases) {
        updatePositionCache(positions, openedAtMap, splitPhases, null, null);
    }

    /**
     * V118: DB 영속 peak/armed 맵 포함 — 재시작 시 실제 peak/armed 그대로 복원.
     * dbPeaks/dbArmedAt이 null이면 V115 fallback(현재가 재판정)으로 동작.
     */
    public void updatePositionCache(Map<String, Double> positions, Map<String, Long> openedAtMap,
                                     Map<String, Integer> splitPhases,
                                     Map<String, Double> dbPeaks,
                                     Map<String, Boolean> dbArmedAt) {
        for (Map.Entry<String, Double> e : positions.entrySet()) {
            String market = e.getKey();
            if (!positionCache.containsKey(market)) {
                long openedAt = (openedAtMap != null && openedAtMap.get(market) != null)
                        ? openedAtMap.get(market)
                        : System.currentTimeMillis();
                positionCache.put(market, new double[]{e.getValue(), openedAt});
                int recPhase = 0;
                if (splitPhases != null && splitPhases.containsKey(market)) {
                    recPhase = splitPhases.get(market);
                }
                double avgP = e.getValue();

                // V118: DB peak/armed 우선 복원
                Double dbPeak = (dbPeaks != null) ? dbPeaks.get(market) : null;
                Boolean dbArmed = (dbArmedAt != null) ? dbArmedAt.get(market) : null;
                double recPeak;
                boolean recArmed;
                if (dbPeak != null && dbPeak > 0) {
                    recPeak = dbPeak;
                    recArmed = (dbArmed != null && dbArmed);
                    log.info("[BreakoutDetector] V118 재시작 복구(DB): {} peak={} armed={} phase={}",
                            market, String.format(java.util.Locale.ROOT, "%.2f", recPeak),
                            recArmed, recPhase);
                } else {
                    // V115 fallback: 현재가 기준 armed/peak 재판정
                    Double curPrice = sharedPriceService.getPrice(market);
                    recPeak = avgP;
                    recArmed = false;
                    if (curPrice != null && curPrice > 0 && recPhase == 0) {
                        double recPnl = (curPrice - avgP) / avgP * 100.0;
                        recPeak = Math.max(avgP, curPrice);
                        if (recPnl >= splitTpPct) {
                            recArmed = true;
                            log.info("[BreakoutDetector] V115 fallback 복구: {} armed=1 pnl=+{}% peak={}",
                                    market, String.format(java.util.Locale.ROOT, "%.2f", recPnl),
                                    String.format(java.util.Locale.ROOT, "%.2f", recPeak));
                        }
                    }
                }
                peakPrices.put(market, recPeak);
                troughPrices.put(market, avgP);
                tpActivated.put(market, false);
                split1stTrailArmed.put(market, recArmed);
            }
            // V111: splitPhase 복원 (DB 기준)
            if (splitPhases != null && splitPhases.containsKey(market)) {
                splitPhaseMap.put(market, splitPhases.get(market));
            } else if (!splitPhaseMap.containsKey(market)) {
                splitPhaseMap.put(market, 0);
            }
        }
        for (String market : new ArrayList<String>(positionCache.keySet())) {
            if (!positions.containsKey(market)) {
                removePosition(market);
            }
        }
    }

    public Double getLatestPrice(String market) {
        Double wsPrice = sharedPriceService.getPrice(market);
        return wsPrice != null ? wsPrice : latestPrices.get(market);
    }

    // ========== Lifecycle (SharedPriceService 기반) ==========

    /**
     * 리스너 등록 + 마켓 구독 요청.
     * 이전의 connect()를 대체.
     */
    public void connect(List<String> markets) {
        disconnect();
        if (markets.isEmpty()) return;

        // SharedPriceService에 마켓 구독 요청
        sharedPriceService.ensureMarketsSubscribed(markets);

        // 가격 업데이트 리스너 등록
        priceListener = new PriceUpdateListener() {
            @Override
            public void onPriceUpdate(String market, double price) {
                latestPrices.put(market, price);
                checkBreakout(market, price);
                checkRealtimeTp(market, price);
            }
        };
        sharedPriceService.addGlobalListener(priceListener);

        log.info("[BreakoutDetector] SharedPriceService 리스너 등록: {} markets", markets.size());
    }

    /**
     * 리스너 해제.
     * 이전의 disconnect()를 대체.
     */
    public void disconnect() {
        if (priceListener != null) {
            sharedPriceService.removeGlobalListener(priceListener);
            priceListener = null;
        }
        latestPrices.clear();
        confirmCounts.clear();
    }

    public boolean isConnected() {
        return sharedPriceService.isConnected();
    }

    // ========== Breakout Detection ==========

    private void checkBreakout(String market, double price) {
        if (confirmedMarkets.contains(market)) return;

        // ★ Entry window 체크 (2026-04-09 추가)
        // listener가 entry window 전에 미리 등록되어 있어도, 윈도우 안에서만 confirm 누적.
        // 윈도우 밖에서는 가격 update를 받기만 하고 confirm 카운트 안 함.
        if (entryStartMinOfDay >= 0 && entryEndMinOfDay >= 0) {
            java.time.ZonedDateTime nowKst = java.time.ZonedDateTime.now(
                    java.time.ZoneId.of("Asia/Seoul"));
            int nowMin = nowKst.getHour() * 60 + nowKst.getMinute();
            if (nowMin < entryStartMinOfDay || nowMin > entryEndMinOfDay) {
                return;  // entry window 밖, confirm 누적 안 함
            }
        }

        Double rangeHigh = rangeHighMap.get(market);
        if (rangeHigh == null || rangeHigh <= 0) return;

        double threshold = rangeHigh * (1.0 + breakoutPct / 100.0);
        boolean breakout = price >= threshold;

        if (breakout) {
            // confirm 간 최소 500ms 간격 체크 (2026-04-11)
            Long lastConfirmTime = confirmLastTime.get(market);
            long now = System.currentTimeMillis();
            if (lastConfirmTime != null && now - lastConfirmTime < confirmMinIntervalMs) {
                return; // 500ms 미경과 → 이번 tick은 무시
            }

            Integer count = confirmCounts.get(market);
            int newCount = (count != null ? count : 0) + 1;
            confirmCounts.put(market, newCount);
            confirmLastTime.put(market, now);

            // confirm1 가격 기록
            if (newCount == 1) {
                confirmFirstPrice.put(market, price);
            }

            if (newCount >= requiredConfirm) {
                // 방향 확인 (2026-04-11): confirm3 or confirm4 ≥ confirm1
                // confirm3에서 이미 통과했으면(newCount==requiredConfirm이 4일 때 3에서 체크 안 함)
                // confirm4(마지막)에서 최종 체크
                Double firstPrice = confirmFirstPrice.get(market);
                if (firstPrice != null && price < firstPrice) {
                    // confirm#last < confirm#1 → 하락 중 매수 차단
                    // confirm3에서 실패 시 confirm4에서 한 번 더 기회
                    if (newCount == requiredConfirm) {
                        // 마지막 confirm인데도 confirm1보다 낮음 → 진짜 하락 → 리셋
                        log.info("[BreakoutDetector] {} confirm REJECTED (falling): confirm1={} confirm{}={} → 리셋",
                                market, firstPrice, newCount, price);
                        confirmCounts.remove(market);
                        confirmFirstPrice.remove(market);
                        confirmLastTime.remove(market);
                        return;
                    }
                    // requiredConfirm-1 (confirm3)에서 실패 → confirm4까지 기회 줌
                    log.debug("[BreakoutDetector] {} confirm{} < confirm1 ({}<{}), confirm{} 기회 남음",
                            market, newCount, price, firstPrice, requiredConfirm);
                    return;
                }

                double actualPct = (price - rangeHigh) / rangeHigh * 100.0;
                confirmedMarkets.add(market);
                confirmCounts.remove(market);
                confirmFirstPrice.remove(market);
                confirmLastTime.remove(market);

                log.info("[BreakoutDetector] BREAKOUT CONFIRMED: {} price={} rangeHigh={} bo=+{}% confirm={}/{}",
                        market, price, rangeHigh, String.format(java.util.Locale.ROOT, "%.2f", actualPct), newCount, requiredConfirm);

                if (listener != null) {
                    try {
                        listener.onBreakoutConfirmed(market, price, rangeHigh, actualPct);
                    } catch (Exception e) {
                        log.error("[BreakoutDetector] listener callback error for {}", market, e);
                    }
                }
            } else {
                log.debug("[BreakoutDetector] {} breakout confirm {}/{}", market, newCount, requiredConfirm);
            }
        } else {
            if (confirmCounts.containsKey(market)) {
                confirmCounts.put(market, 0);
                confirmFirstPrice.remove(market);
                confirmLastTime.remove(market);
            }
        }
    }

    /**
     * 실시간 TP/SL 종합안 체크 (DB 접근 없음).
     *
     * SL 종합안 (DB 설정 + TOP-N 차등):
     *  - 0~grace_period:  그레이스 (SL 무시)
     *  - grace~wide_period: SL_WIDE — 거래대금 순위별 차등
     *      · TOP 1~10:   wide_sl_top10_pct
     *      · TOP 11~20:  wide_sl_top20_pct
     *      · TOP 21~50:  wide_sl_top50_pct
     *      · TOP 51~:    wide_sl_other_pct
     *  - wide_period 이후: SL_TIGHT — tight_sl_pct (단일)
     *
     * TP 트레일링:
     *  - +tpActivatePct 도달 → 활성화 → peak에서 -trailFromPeakPct 떨어지면 매도
     */
    private void checkRealtimeTp(String market, double price) {
        double[] pos = positionCache.get(market);
        if (pos == null) return;

        double avgPrice = pos[0];
        if (avgPrice <= 0) return;
        long openedAtMs = pos.length >= 2 ? (long) pos[1] : System.currentTimeMillis();
        int volumeRank = pos.length >= 3 ? (int) pos[2] : 999;

        double pnlPct = (price - avgPrice) / avgPrice * 100.0;
        long elapsedMs = System.currentTimeMillis() - openedAtMs;

        // 피크 업데이트
        Double peak = peakPrices.get(market);
        if (peak == null || price > peak) {
            peakPrices.put(market, price);
            peak = price;
        }

        // 트로프(최저가) 업데이트
        Double trough = troughPrices.get(market);
        if (trough == null || price < trough) {
            troughPrices.put(market, price);
            trough = price;
        }

        String sellType = null;
        String reason = null;

        // 1. SL 종합안 체크 (DB 설정값 + TOP-N 차등)
        if (elapsedMs < cachedGracePeriodMs) {
            // 그레이스 — SL 무시
        } else if (elapsedMs < cachedWidePeriodMs) {
            // SL_WIDE — TOP-N 차등
            double wideSlPct = getWideSlForRank(volumeRank);
            if (pnlPct <= -wideSlPct) {
                sellType = "SL_WIDE";
                double troughPnl = (trough - avgPrice) / avgPrice * 100.0;
                reason = String.format(java.util.Locale.ROOT,
                        "SL_WIDE pnl=%.2f%% <= -%.2f%% price=%.2f avg=%.2f rank=%d trough=%.2f troughPnl=%.2f%% (realtime)",
                        pnlPct, wideSlPct, price, avgPrice, volumeRank, trough, troughPnl);
            }
        } else {
            // SL_TIGHT — 단일
            if (pnlPct <= -cachedTightSlPct) {
                sellType = "SL_TIGHT";
                double troughPnl = (trough - avgPrice) / avgPrice * 100.0;
                reason = String.format(java.util.Locale.ROOT,
                        "SL_TIGHT pnl=%.2f%% <= -%.2f%% price=%.2f avg=%.2f trough=%.2f troughPnl=%.2f%% (realtime)",
                        pnlPct, cachedTightSlPct, price, avgPrice, trough, troughPnl);
            }
        }

        // 2. V111: Split-Exit 체크 (SL 미발동 시)
        int splitPhase = splitPhaseMap.containsKey(market) ? splitPhaseMap.get(market) : 0;

        // V115: Split-Exit 1차 매도 TRAIL 방식 (splitPhase=0)
        // (1) +splitTpPct 도달 → armed (매도 안 함, peak 추적 시작)
        // (2) armed 상태에서 peak 대비 drop >= split_1st_trail_drop → SPLIT_1ST 매도
        if (sellType == null && splitExitEnabled && splitPhase == 0) {
            Boolean armedBox = split1stTrailArmed.get(market);
            boolean armed = armedBox != null && armedBox;
            if (!armed && pnlPct >= splitTpPct) {
                split1stTrailArmed.put(market, true);
                log.info("[BreakoutDetector] SPLIT_1ST trail armed: {} pnl=+{}% peak={}",
                        market, String.format(java.util.Locale.ROOT, "%.2f", pnlPct),
                        String.format(java.util.Locale.ROOT, "%.2f", peak));
            } else if (armed && peak > avgPrice) {
                double dropFromPeak = (peak - price) / peak * 100.0;
                if (dropFromPeak >= split1stTrailDropPct) {
                    double troughPnl = (trough - avgPrice) / avgPrice * 100.0;
                    sellType = "SPLIT_1ST";
                    reason = String.format(java.util.Locale.ROOT,
                            "SPLIT_1ST_TRAIL avg=%.2f peak=%.2f now=%.2f drop=%.2f%% >= %.2f%% pnl=+%.2f%% ratio=%.0f%% trough=%.2f troughPnl=%.2f%% (realtime)",
                            avgPrice, peak, price, dropFromPeak, split1stTrailDropPct, pnlPct, splitRatio * 100, trough, troughPnl);
                }
            }
        }
        // V111: Split 2차 관리 (splitPhase=1)
        else if (sellType == null && splitExitEnabled && splitPhase == 1) {
            // Breakeven SL
            if (pnlPct <= 0) {
                double troughPnl = (trough - avgPrice) / avgPrice * 100.0;
                sellType = "SPLIT_2ND_BEV";
                reason = String.format(java.util.Locale.ROOT,
                        "SPLIT_2ND_BEV pnl=%.2f%% <= 0%% (breakeven) avg=%.2f now=%.2f trough=%.2f troughPnl=%.2f%% (realtime)",
                        pnlPct, avgPrice, price, trough, troughPnl);
            }
            // TP_TRAIL drop (trailDropAfterSplit)
            else if (peak > avgPrice) {
                double dropFromPeak = (peak - price) / peak * 100.0;
                if (dropFromPeak >= trailDropAfterSplit) {
                    double troughPnl = (trough - avgPrice) / avgPrice * 100.0;
                    sellType = "SPLIT_2ND_TRAIL";
                    reason = String.format(java.util.Locale.ROOT,
                            "SPLIT_2ND_TRAIL avg=%.2f peak=%.2f now=%.2f drop=%.2f%% >= %.2f%% pnl=%.2f%% trough=%.2f troughPnl=%.2f%% (realtime)",
                            avgPrice, peak, price, dropFromPeak, trailDropAfterSplit, pnlPct, trough, troughPnl);
                }
            }
        }
        // 3. 기존 TP 트레일링 체크 (SL/Split 미발동 시)
        else if (sellType == null) {
            Boolean activated = tpActivated.get(market);
            if (activated == null) activated = false;

            if (!activated && pnlPct >= tpActivatePct) {
                tpActivated.put(market, true);
                activated = true;
                log.info("[BreakoutDetector] TP activated: {} pnl=+{}% peak={} (realtime)",
                        market, String.format(java.util.Locale.ROOT, "%.2f", pnlPct), price);
            }

            if (activated && peak > avgPrice) {
                double dropFromPeak = (peak - price) / peak * 100.0;
                if (dropFromPeak >= trailFromPeakPct) {
                    double trailPnl = (price - avgPrice) / avgPrice * 100.0;
                    double troughPnl = (trough - avgPrice) / avgPrice * 100.0;
                    sellType = "TP_TRAIL";
                    reason = String.format(java.util.Locale.ROOT,
                            "TP_TRAIL avg=%.2f peak=%.2f now=%.2f drop=%.2f%% pnl=%.2f%% trough=%.2f troughPnl=%.2f%% (realtime)",
                            avgPrice, peak, price, dropFromPeak, trailPnl, trough, troughPnl);
                }
            }
        }

        if (sellType == null) return;

        log.info("[BreakoutDetector] {} triggered: {} | {}", sellType, market, reason);

        // V111: 1차 매도는 position 유지 (캐시 제거 안 함), 2차/기타는 제거
        if ("SPLIT_1ST".equals(sellType)) {
            // 1차 매도 후: splitPhase=1, peak 리셋, trail 재초기화
            splitPhaseMap.put(market, 1);
            peakPrices.put(market, price);
            tpActivated.put(market, false);
            split1stTrailArmed.put(market, false);  // V115: 1차 trail armed 리셋
        } else {
            removePosition(market);
        }

        if (listener != null) {
            try {
                listener.onTpSlTriggered(market, price, sellType, reason);
            } catch (Exception e) {
                log.error("[BreakoutDetector] TP/SL callback error for {}", market, e);
            }
        }
    }

    /** 레인지 고점 맵 초기화 (세션 종료 또는 다음 날 준비) */
    public void reset() {
        rangeHighMap.clear();
        confirmCounts.clear();
        confirmedMarkets.clear();
        latestPrices.clear();
        positionCache.clear();
        peakPrices.clear();
        troughPrices.clear();
        tpActivated.clear();
        confirmFirstPrice.clear();
        confirmLastTime.clear();
        splitPhaseMap.clear();
    }
}
