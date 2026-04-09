package com.example.upbit.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 실제 thread race condition을 simulate하는 테스트.
 *
 * 2026-04-09 KRW-CBK 사고 재현 시나리오:
 *  - 같은 스캐너 안의 여러 thread가 동시에 같은 코인 매수 시도
 *  - SharedTradeThrottle.tryClaim() + buyingMarkets Set 패턴이 정확히 1개 thread만 통과시키는지 검증
 *
 * CountDownLatch로 모든 thread를 같은 순간에 출발시켜 실제 race 발생을 강제한다.
 */
public class RaceConditionFixTest {

    private static final int THREAD_COUNT = 50;
    private static final int RACE_ITERATIONS = 200;

    // ═══════════════════════════════════════════════════════════
    //  시나리오 1: SharedTradeThrottle.tryClaim() race
    //  → 50 thread가 동시에 같은 market tryClaim 호출
    //  → 정확히 1개만 true 반환되어야 함 (1시간 2회 제한 + 20분 쿨다운)
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 1: 50 thread 동시 tryClaim → 정확히 1개만 통과")
    public void scenario1_concurrentTryClaim_onlyOneSucceeds() throws Exception {
        SharedTradeThrottle throttle = new SharedTradeThrottle();
        String market = "KRW-CBK";

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(THREAD_COUNT);
        AtomicInteger successCount = new AtomicInteger(0);

        ExecutorService exec = Executors.newFixedThreadPool(THREAD_COUNT);
        for (int i = 0; i < THREAD_COUNT; i++) {
            exec.submit(() -> {
                try {
                    startGate.await();  // 모든 thread 동시 출발
                    if (throttle.tryClaim(market)) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();  // GO!
        assertTrue(doneGate.await(10, TimeUnit.SECONDS), "모든 thread 완료 대기 실패");
        exec.shutdown();

        // 정확히 1개 thread만 통과해야 함
        assertEquals(1, successCount.get(),
                "tryClaim race: 50 thread 중 정확히 1개만 통과해야 함, 실제: " + successCount.get());
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 2: 200회 반복 race test (확률적 안정성 검증)
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 2: 200회 반복 race test (한 번이라도 실패하면 fail)")
    public void scenario2_repeatedRaceStressTest() throws Exception {
        for (int iter = 0; iter < RACE_ITERATIONS; iter++) {
            SharedTradeThrottle throttle = new SharedTradeThrottle();
            String market = "KRW-CBK-" + iter;  // iteration 마다 다른 market (throttle reset)

            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch doneGate = new CountDownLatch(10);
            AtomicInteger successCount = new AtomicInteger(0);

            ExecutorService exec = Executors.newFixedThreadPool(10);
            for (int i = 0; i < 10; i++) {
                exec.submit(() -> {
                    try {
                        startGate.await();
                        if (throttle.tryClaim(market)) {
                            successCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneGate.countDown();
                    }
                });
            }

            startGate.countDown();
            doneGate.await(5, TimeUnit.SECONDS);
            exec.shutdown();

            assertEquals(1, successCount.get(),
                    "iter " + iter + ": race fix 실패! " + successCount.get() + "개 thread 통과");
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 3: buyingMarkets Set 패턴 race
    //  → 50 thread가 동시에 같은 market.add() 호출
    //  → 정확히 1개만 true 반환되어야 함 (lock-free atomic)
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 3: 50 thread 동시 buyingMarkets.add → 정확히 1개만 통과")
    public void scenario3_concurrentInflightAdd_onlyOneSucceeds() throws Exception {
        Set<String> buyingMarkets = ConcurrentHashMap.newKeySet();
        String market = "KRW-CBK";

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(THREAD_COUNT);
        AtomicInteger successCount = new AtomicInteger(0);

        ExecutorService exec = Executors.newFixedThreadPool(THREAD_COUNT);
        for (int i = 0; i < THREAD_COUNT; i++) {
            exec.submit(() -> {
                try {
                    startGate.await();
                    if (buyingMarkets.add(market)) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        assertTrue(doneGate.await(10, TimeUnit.SECONDS));
        exec.shutdown();

        assertEquals(1, successCount.get(),
                "ConcurrentHashMap.newKeySet().add race fix 실패: " + successCount.get());
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 4: KRW-CBK 사고 정확 재현
    //  매수 task 두 개가 morning-rush-0, morning-rush-1 thread에서 동시 실행
    //  → 한 thread만 buyingMarkets 통과 + tryClaim 통과
    //  → 한 thread만 매수 권한 획득
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 4: KRW-CBK 사고 정확 재현 (morning-rush-0/1 동시 시뮬레이션)")
    public void scenario4_kobaIncidentReproduction() throws Exception {
        SharedTradeThrottle throttle = new SharedTradeThrottle();
        Set<String> buyingMarkets = ConcurrentHashMap.newKeySet();
        String market = "KRW-CBK";

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(2);
        AtomicInteger successfulBuys = new AtomicInteger(0);

        // morning-rush-0 thread
        Thread thread0 = new Thread(() -> {
            try {
                startGate.await();
                if (!buyingMarkets.add(market)) return;  // in-flight 차단
                try {
                    if (!throttle.tryClaim(market)) return;
                    // 매수 성공
                    successfulBuys.incrementAndGet();
                    Thread.sleep(50);  // 실제 Upbit API call latency 시뮬레이션
                } finally {
                    buyingMarkets.remove(market);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneGate.countDown();
            }
        }, "morning-rush-0");

        // morning-rush-1 thread (동시 실행)
        Thread thread1 = new Thread(() -> {
            try {
                startGate.await();
                if (!buyingMarkets.add(market)) return;
                try {
                    if (!throttle.tryClaim(market)) return;
                    successfulBuys.incrementAndGet();
                    Thread.sleep(50);
                } finally {
                    buyingMarkets.remove(market);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneGate.countDown();
            }
        }, "morning-rush-1");

        thread0.start();
        thread1.start();
        startGate.countDown();  // GO!

        assertTrue(doneGate.await(5, TimeUnit.SECONDS));

        assertEquals(1, successfulBuys.get(),
                "KRW-CBK 사고 재발: 두 thread 중 정확히 1개만 매수해야 함, 실제: " + successfulBuys.get());
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 5: 매도 race (mainLoop SL + WS realtime SL 두 path 동시)
    //  → sellingMarkets Set이 한 path만 통과시켜야 함
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 5: 매도 race (mainLoop + WS) 동시 → 1개만 매도")
    public void scenario5_sellRaceMainLoopVsWs() throws Exception {
        Set<String> sellingMarkets = ConcurrentHashMap.newKeySet();
        String market = "KRW-CBK";

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(2);
        AtomicInteger sellsExecuted = new AtomicInteger(0);

        // mainLoop monitorPositions thread
        Thread mainLoopThread = new Thread(() -> {
            try {
                startGate.await();
                if (!sellingMarkets.add(market)) return;
                try {
                    sellsExecuted.incrementAndGet();
                    Thread.sleep(50);  // Upbit sell API simulation
                } finally {
                    sellingMarkets.remove(market);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneGate.countDown();
            }
        }, "mainLoop-SL");

        // WS realtime SL listener thread
        Thread wsThread = new Thread(() -> {
            try {
                startGate.await();
                if (!sellingMarkets.add(market)) return;
                try {
                    sellsExecuted.incrementAndGet();
                    Thread.sleep(50);
                } finally {
                    sellingMarkets.remove(market);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneGate.countDown();
            }
        }, "ws-realtime-SL");

        mainLoopThread.start();
        wsThread.start();
        startGate.countDown();

        assertTrue(doneGate.await(5, TimeUnit.SECONDS));

        assertEquals(1, sellsExecuted.get(),
                "매도 race fix 실패: 두 path 중 1개만 매도해야 함, 실제: " + sellsExecuted.get());
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 6: throttle release 후 다음 thread 통과 가능
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 6: 매수 실패 → releaseClaim → 다음 thread 통과 가능")
    public void scenario6_releaseClaimAllowsNextThread() {
        SharedTradeThrottle throttle = new SharedTradeThrottle();
        String market = "KRW-CBK";

        // 1. tryClaim 통과
        assertTrue(throttle.tryClaim(market));

        // 2. 두번째 시도 → 차단 (쿨다운)
        assertFalse(throttle.tryClaim(market));

        // 3. release (매수 실패 시뮬레이션)
        throttle.releaseClaim(market);

        // 4. 다음 thread 다시 통과 가능
        assertTrue(throttle.tryClaim(market),
                "releaseClaim 후 다음 thread는 통과해야 함");
    }
}
