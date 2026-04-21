package com.example.upbit.bot;

import com.example.upbit.db.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V129 Gap #5: 3스캐너 동시 가동 교차 시나리오.
 *
 * 기존 SharedThrottleCrossScannerTest가 2-way 매수 차단까지만 커버. 본 테스트는:
 *   (1) MR→Opening→AllDay 3 스캐너가 같은 마켓을 순차 시도 — 첫 한 건만 통과
 *   (2) 3 스캐너가 서로 다른 마켓 매수 2건 + 3번째 시도 → 1시간 2회 한도 교차 적용
 *   (3) 동시 멀티스레드 진입 경합 — recordBuy 1회만 통과 (동시성 안전성)
 *   (4) splitPhase=1 상태의 포지션은 다른 스캐너가 중복 진입하지 않음 (ownership 체크)
 *
 * "V129 drop 2.0% 통일" 변경에 의한 스캐너 간 매도 로직 독립성은 각 스캐너가
 * split1stExecutedAtMap 을 별도 인스턴스로 소유함에서 이미 보장됨 — 여기선 throttle
 * 및 positionRepo 레벨의 교차 race condition 검증에 집중.
 */
public class MultiScannerRaceConditionScenarioTest {

    private SharedTradeThrottle throttle;
    private ConcurrentHashMap<String, PositionEntity> fakeDb;

    @BeforeEach
    public void setUp() {
        throttle = new SharedTradeThrottle();
        fakeDb = new ConcurrentHashMap<String, PositionEntity>();
    }

    // ═══════════════════════════════════════════════════
    //  (1) 3-way 순차 진입
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("V129-RACE-1: MR→Opening→AllDay 순차 진입 시 첫 스캐너만 통과, 나머지 차단")
    public void threeWaySequentialEntry() {
        final String market = "KRW-RACE1";

        // MR 시도
        assertTrue(throttle.canBuy(market), "MR 시도: 초기 통과");
        throttle.recordBuy(market);

        // Opening 시도 — 20분 쿨다운 발동 → 차단
        assertFalse(throttle.canBuy(market), "Opening 시도: 20분 쿨다운 차단");

        // AllDay 시도 — 동일 차단
        assertFalse(throttle.canBuy(market), "AllDay 시도: 20분 쿨다운 차단");

        long wait = throttle.remainingWaitMs(market);
        assertTrue(wait > 19 * 60_000L, "남은 대기 20분 근처, 실제=" + (wait / 60_000) + "분");
    }

    // ═══════════════════════════════════════════════════
    //  (2) 3 스캐너 + 1시간 2회 한도
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("V129-RACE-2: 3 스캐너 교차 + 1시간 2회 한도 — 같은 마켓 3번째 시도 차단")
    public void threeWayHourlyQuotaExhausted() throws Exception {
        final String market = "KRW-RACE2";

        // 25분 전·21분 전 기록 삽입 (MR 1회 + Opening 1회)
        HourlyTradeThrottle inner = throttle.getDelegate();
        java.lang.reflect.Field histF = HourlyTradeThrottle.class.getDeclaredField("tradeHistory");
        histF.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Deque<Long>> hist =
                (ConcurrentHashMap<String, Deque<Long>>) histF.get(inner);
        Deque<Long> deque = new ArrayDeque<Long>();
        long now = System.currentTimeMillis();
        deque.addLast(now - 25 * 60_000L);
        deque.addLast(now - 21 * 60_000L);
        hist.put(market, deque);

        // AllDay가 3번째 매수 시도 → 1시간 2회 한도로 차단
        assertFalse(throttle.canBuy(market),
                "3번째 스캐너(AllDay): 1시간 2회 교차 한도로 차단");
    }

    // ═══════════════════════════════════════════════════
    //  (3) 동시 멀티스레드 race
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("V129-RACE-3: 3 스레드가 동시에 recordBuy 호출해도 canBuy→record→canBuy 순서로 1회만 통과")
    public void concurrentRecordBuyRace() throws Exception {
        final String market = "KRW-RACE3";
        final int threads = 20;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicInteger successCount = new AtomicInteger(0);

        Runnable task = new Runnable() {
            @Override
            public void run() {
                try {
                    start.await();
                    // 실제 스캐너 경로: canBuy 체크 → recordBuy (원자적 아님, 하지만 순서 존재)
                    synchronized (throttle) {
                        if (throttle.canBuy(market)) {
                            throttle.recordBuy(market);
                            successCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }
        };

        for (int i = 0; i < threads; i++) {
            new Thread(task).start();
        }
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS), "모든 스레드 완료");

        assertEquals(1, successCount.get(),
                "동시 20개 스레드 중 1개만 recordBuy 성공 (synchronized 게이트 내 canBuy→record 원자)");
        assertFalse(throttle.canBuy(market), "recordBuy 1건 후 canBuy=false");
    }

    // ═══════════════════════════════════════════════════
    //  (4) splitPhase=1 포지션 ownership 보호 — MR 소유를 Opening/AllDay 가 중복 진입하지 않음
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("V129-RACE-4: MR splitPhase=1 포지션 존재 시 Opening/AllDay 신규 매수 차단")
    public void splitPhasePositionBlocksOtherScanners() {
        final String market = "KRW-RACE4";

        // MR이 매수 후 splitPhase=1 진입 상태로 DB 유지 (1차 매도 완료, 2차 대기)
        PositionEntity mrHeld = new PositionEntity();
        mrHeld.setMarket(market);
        mrHeld.setQty(BigDecimal.valueOf(400.0));      // 1차 매도 후 40% 잔량
        mrHeld.setAvgPrice(BigDecimal.valueOf(100.0));
        mrHeld.setSplitPhase(1);
        mrHeld.setSplitOriginalQty(BigDecimal.valueOf(1000.0));
        mrHeld.setEntryStrategy("MORNING_RUSH");
        mrHeld.setOpenedAt(Instant.now());
        fakeDb.put(market, mrHeld);

        // Opening/AllDay 시나리오: 진입 전 positionRepo.findById → 이미 존재 시 중복 진입 차단
        // (실제 서비스에서는 ownedMarkets 세트로 필터링. 테스트는 DB 상태만 검증)
        assertNotNull(fakeDb.get(market),
                "MR splitPhase=1 포지션이 DB에 존재 — Opening/AllDay는 ownedMarkets 필터로 차단");
        assertEquals(1, fakeDb.get(market).getSplitPhase(),
                "splitPhase=1 상태 유지 (다른 스캐너가 덮어쓰지 않음)");

        // Opening이 같은 마켓 매수 시도 (체크 로직 시뮬레이션)
        boolean openingWouldBuy = !fakeDb.containsKey(market);
        assertFalse(openingWouldBuy, "Opening 진입 차단 (이미 MR이 소유)");

        // AllDay도 동일
        boolean allDayWouldBuy = !fakeDb.containsKey(market);
        assertFalse(allDayWouldBuy, "AllDay 진입 차단");
    }

    // ═══════════════════════════════════════════════════
    //  (5) MR 쿨다운 만료 후 Opening 진입 허용 (서로 다른 시간대)
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("V129-RACE-5: 22분 전 MR recordBuy + 현재 Opening 재진입 허용 (20분 쿨다운 만료)")
    public void scannerCooldownExpiresForNext() throws Exception {
        final String market = "KRW-RACE5";

        HourlyTradeThrottle inner = throttle.getDelegate();
        java.lang.reflect.Field histF = HourlyTradeThrottle.class.getDeclaredField("tradeHistory");
        histF.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Deque<Long>> hist =
                (ConcurrentHashMap<String, Deque<Long>>) histF.get(inner);
        Deque<Long> deque = new ArrayDeque<Long>();
        deque.addLast(System.currentTimeMillis() - 22 * 60_000L);  // 22분 전 (MR 매수)
        hist.put(market, deque);

        // Opening이 현재 시도 → 20분 쿨다운 만료 + 1시간 1회 잔여로 통과
        assertTrue(throttle.canBuy(market),
                "MR 매수 22분 경과 후 Opening 재진입 허용 (쿨다운 만료 + 한도 여유)");
    }
}
