package com.example.upbit.bot;

import com.example.upbit.market.NewMarketListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MINA 케이스 재현: 신규 TOP-N 코인이 entry phase 중 감지되었을 때
 * 모닝러쉬/오프닝 스캐너에 rangeHigh가 즉시 등록되는지 시나리오 테스트.
 */
class DynamicTopNScenarioTest {

    // 모닝러쉬 rangeHighMap 시뮬레이션
    private ConcurrentHashMap<String, Double> rangeHighMap;
    // 오프닝 스캐너 rangeHighCache + breakoutDetector rangeHighMap 시뮬레이션
    private ConcurrentHashMap<String, Double> rangeHighCache;
    private ConcurrentHashMap<String, Double> detectorRangeHighMap;

    private AtomicBoolean rangeCollected;
    private AtomicBoolean entryPhaseComplete;
    private AtomicBoolean breakoutDetectorConnected;

    // 가격 소스 시뮬레이션
    private ConcurrentHashMap<String, Double> latestPrices;

    @BeforeEach
    void setUp() {
        rangeHighMap = new ConcurrentHashMap<String, Double>();
        rangeHighCache = new ConcurrentHashMap<String, Double>();
        detectorRangeHighMap = new ConcurrentHashMap<String, Double>();
        rangeCollected = new AtomicBoolean(false);
        entryPhaseComplete = new AtomicBoolean(false);
        breakoutDetectorConnected = new AtomicBoolean(false);
        latestPrices = new ConcurrentHashMap<String, Double>();

        // 08:50 시점 TOP-50 (MINA 없음)
        rangeHighMap.put("KRW-ETH", 2500000.0);
        rangeHighMap.put("KRW-SOL", 150000.0);
        rangeHighCache.put("KRW-ETH", 2500000.0);
        rangeHighCache.put("KRW-SOL", 150000.0);
        detectorRangeHighMap.put("KRW-ETH", 2500000.0);
        detectorRangeHighMap.put("KRW-SOL", 150000.0);
    }

    @Test
    @DisplayName("시나리오 1: MINA가 entry phase 중 TOP-50 진입 → 모닝러쉬 rangeHighMap에 즉시 등록")
    void testMorningRushDynamicAdd() {
        rangeCollected.set(true);
        entryPhaseComplete.set(false);

        // SharedPriceService가 MINA 가격 수신 시작
        latestPrices.put("KRW-MINA", 850.0);

        // 신규 마켓 콜백 시뮬레이션 (모닝러쉬 리스너)
        NewMarketListener morningRushListener = new NewMarketListener() {
            @Override
            public void onNewMarketsAdded(List<String> newMarkets) {
                if (entryPhaseComplete.get() || !rangeCollected.get()) return;
                for (String market : newMarkets) {
                    if (rangeHighMap.containsKey(market)) continue;
                    Double price = latestPrices.get(market);
                    if (price == null || price <= 0) continue;
                    rangeHighMap.put(market, price);
                }
            }
        };

        // 콜백 실행
        assertFalse(rangeHighMap.containsKey("KRW-MINA"));
        morningRushListener.onNewMarketsAdded(Arrays.asList("KRW-MINA"));
        assertTrue(rangeHighMap.containsKey("KRW-MINA"));
        assertEquals(850.0, rangeHighMap.get("KRW-MINA"));
    }

    @Test
    @DisplayName("시나리오 2: MINA가 entry phase 종료 후 감지 → 무시")
    void testMorningRushIgnoreAfterEntryPhase() {
        rangeCollected.set(true);
        entryPhaseComplete.set(true); // entry phase 끝남

        latestPrices.put("KRW-MINA", 850.0);

        NewMarketListener morningRushListener = new NewMarketListener() {
            @Override
            public void onNewMarketsAdded(List<String> newMarkets) {
                if (entryPhaseComplete.get() || !rangeCollected.get()) return;
                for (String market : newMarkets) {
                    if (rangeHighMap.containsKey(market)) continue;
                    Double price = latestPrices.get(market);
                    if (price == null || price <= 0) continue;
                    rangeHighMap.put(market, price);
                }
            }
        };

        morningRushListener.onNewMarketsAdded(Arrays.asList("KRW-MINA"));
        assertFalse(rangeHighMap.containsKey("KRW-MINA")); // 무시됨
    }

    @Test
    @DisplayName("시나리오 3: range 수집 전에 콜백 → 무시")
    void testMorningRushIgnoreBeforeRangeCollected() {
        rangeCollected.set(false);
        entryPhaseComplete.set(false);

        latestPrices.put("KRW-MINA", 850.0);

        NewMarketListener morningRushListener = new NewMarketListener() {
            @Override
            public void onNewMarketsAdded(List<String> newMarkets) {
                if (entryPhaseComplete.get() || !rangeCollected.get()) return;
                for (String market : newMarkets) {
                    if (rangeHighMap.containsKey(market)) continue;
                    Double price = latestPrices.get(market);
                    if (price == null || price <= 0) continue;
                    rangeHighMap.put(market, price);
                }
            }
        };

        morningRushListener.onNewMarketsAdded(Arrays.asList("KRW-MINA"));
        assertFalse(rangeHighMap.containsKey("KRW-MINA")); // 무시됨
    }

    @Test
    @DisplayName("시나리오 4: 이미 등록된 코인은 중복 추가 안 함")
    void testNoDuplicateAdd() {
        rangeCollected.set(true);
        entryPhaseComplete.set(false);

        rangeHighMap.put("KRW-ETH", 2500000.0);
        latestPrices.put("KRW-ETH", 2600000.0); // 가격 변동

        NewMarketListener morningRushListener = new NewMarketListener() {
            @Override
            public void onNewMarketsAdded(List<String> newMarkets) {
                if (entryPhaseComplete.get() || !rangeCollected.get()) return;
                for (String market : newMarkets) {
                    if (rangeHighMap.containsKey(market)) continue;
                    Double price = latestPrices.get(market);
                    if (price == null || price <= 0) continue;
                    rangeHighMap.put(market, price);
                }
            }
        };

        morningRushListener.onNewMarketsAdded(Arrays.asList("KRW-ETH"));
        assertEquals(2500000.0, rangeHighMap.get("KRW-ETH")); // 원래 값 유지
    }

    @Test
    @DisplayName("시나리오 5: 오프닝 스캐너 - breakoutDetector 연결 전 콜백 → 무시")
    void testOpeningScannerIgnoreBeforeDetectorConnected() {
        breakoutDetectorConnected.set(false);

        latestPrices.put("KRW-MINA", 850.0);

        NewMarketListener openingListener = new NewMarketListener() {
            @Override
            public void onNewMarketsAdded(List<String> newMarkets) {
                if (!breakoutDetectorConnected.get()) return;
                for (String market : newMarkets) {
                    if (rangeHighCache.containsKey(market)) continue;
                    Double price = latestPrices.get(market);
                    if (price == null || price <= 0) continue;
                    rangeHighCache.put(market, price);
                    detectorRangeHighMap.put(market, price);
                }
            }
        };

        openingListener.onNewMarketsAdded(Arrays.asList("KRW-MINA"));
        assertFalse(rangeHighCache.containsKey("KRW-MINA")); // 무시됨
    }

    @Test
    @DisplayName("시나리오 6: 오프닝 스캐너 - entry phase 중 동적 추가 → rangeHighCache + detectorMap 동시 등록")
    void testOpeningScannerDynamicAdd() {
        breakoutDetectorConnected.set(true);

        latestPrices.put("KRW-MINA", 850.0);

        NewMarketListener openingListener = new NewMarketListener() {
            @Override
            public void onNewMarketsAdded(List<String> newMarkets) {
                if (!breakoutDetectorConnected.get()) return;
                for (String market : newMarkets) {
                    if (rangeHighCache.containsKey(market)) continue;
                    Double price = latestPrices.get(market);
                    if (price == null || price <= 0) continue;
                    rangeHighCache.put(market, price);
                    detectorRangeHighMap.put(market, price);
                }
            }
        };

        openingListener.onNewMarketsAdded(Arrays.asList("KRW-MINA"));
        assertTrue(rangeHighCache.containsKey("KRW-MINA"));
        assertTrue(detectorRangeHighMap.containsKey("KRW-MINA"));
        assertEquals(850.0, rangeHighCache.get("KRW-MINA"));
        assertEquals(850.0, detectorRangeHighMap.get("KRW-MINA"));
    }

    @Test
    @DisplayName("시나리오 7: 가격 정보 없는 코인은 등록되지 않음")
    void testNoPriceAvailable() {
        rangeCollected.set(true);
        entryPhaseComplete.set(false);
        // latestPrices에 MINA 없음 (가격 아직 수신 안됨)

        NewMarketListener listener = new NewMarketListener() {
            @Override
            public void onNewMarketsAdded(List<String> newMarkets) {
                if (entryPhaseComplete.get() || !rangeCollected.get()) return;
                for (String market : newMarkets) {
                    if (rangeHighMap.containsKey(market)) continue;
                    Double price = latestPrices.get(market);
                    if (price == null || price <= 0) continue;
                    rangeHighMap.put(market, price);
                }
            }
        };

        listener.onNewMarketsAdded(Arrays.asList("KRW-MINA"));
        assertFalse(rangeHighMap.containsKey("KRW-MINA")); // 가격 없어서 등록 안됨
    }

    @Test
    @DisplayName("시나리오 8: 동시에 여러 코인 추가 → 모두 등록")
    void testMultipleCoinsAdded() {
        rangeCollected.set(true);
        entryPhaseComplete.set(false);

        latestPrices.put("KRW-MINA", 850.0);
        latestPrices.put("KRW-PYTH", 320.0);
        latestPrices.put("KRW-EDGE", 1200.0);

        NewMarketListener listener = new NewMarketListener() {
            @Override
            public void onNewMarketsAdded(List<String> newMarkets) {
                if (entryPhaseComplete.get() || !rangeCollected.get()) return;
                for (String market : newMarkets) {
                    if (rangeHighMap.containsKey(market)) continue;
                    Double price = latestPrices.get(market);
                    if (price == null || price <= 0) continue;
                    rangeHighMap.put(market, price);
                }
            }
        };

        listener.onNewMarketsAdded(Arrays.asList("KRW-MINA", "KRW-PYTH", "KRW-EDGE"));
        assertTrue(rangeHighMap.containsKey("KRW-MINA"));
        assertTrue(rangeHighMap.containsKey("KRW-PYTH"));
        assertTrue(rangeHighMap.containsKey("KRW-EDGE"));
        // 기존 코인도 여전히 있음
        assertTrue(rangeHighMap.containsKey("KRW-ETH"));
        assertTrue(rangeHighMap.containsKey("KRW-SOL"));
    }
}
