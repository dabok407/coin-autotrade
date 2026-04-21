package com.example.upbit.bot;

import com.example.upbit.db.*;
import com.example.upbit.market.CandleService;
import com.example.upbit.market.SharedPriceService;
import com.example.upbit.market.TickerService;
import com.example.upbit.market.UpbitMarketCatalogService;
import com.example.upbit.trade.LiveOrderService;
import com.example.upbit.upbit.UpbitPrivateClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * V129 Gap #3: #9 DB commit 실패 시 memory 복구.
 *
 * 시나리오: SPLIT_1ST 판정 직후 positionCache[5]=1 설정 + split1stExecutedAtMap.put 을
 * in-memory에 기록한 상태에서 executeSplitFirstSell 내 positionRepo.save()가 예외를 던지면,
 * catch 블록이 positionCache[5]=0 과 split1stExecutedAtMap.remove(market) 을 수행해야 한다.
 *
 * 이 롤백이 없으면:
 *   - 재진입 시 splitPhase=0인데 쿨다운 맵에만 기록 잔존 → 2차 TRAIL 허위 차단
 *   - 또는 splitPhase=1인데 실제 DB는 0 → 재시작 불일치
 *
 * MR이 유일하게 "메모리 put이 DB commit보다 선행"하는 구조라 원자성 fix가 필요.
 * AllDay/Opening은 DB commit 후 put이라 catch에서 복구 대상 없음 — 여기선 MR 중심 + Opening
 * 의 clearSplit1stCooldown 보조 테스트만 작성.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class SplitFirstDbFailureScenarioTest {

    @Mock private MorningRushConfigRepository mrConfigRepo;
    @Mock private BotConfigRepository botConfigRepo;
    @Mock private PositionRepository positionRepo;
    @Mock private TradeRepository tradeLogRepo;
    @Mock private LiveOrderService liveOrders;
    @Mock private UpbitPrivateClient privateClient;
    @Mock private TransactionTemplate txTemplate;
    @Mock private UpbitMarketCatalogService catalogService;
    @Mock private TickerService tickerService;
    @Mock private SharedPriceService sharedPriceService;

    private MorningRushScannerService mr;
    private MorningRushConfigEntity mrCfg;

    @BeforeEach
    public void setUp() throws Exception {
        mr = new MorningRushScannerService(
                mrConfigRepo, botConfigRepo, positionRepo, tradeLogRepo,
                liveOrders, privateClient, txTemplate, catalogService, tickerService,
                sharedPriceService, new SharedTradeThrottle()
        );
        setField(mr, "running", new AtomicBoolean(true));
        setField(mr, "cachedSplitExitEnabled", true);
        setField(mr, "cachedSplitTpPct", 1.5);
        setField(mr, "cachedSplitRatio", 0.60);
        setField(mr, "cachedSplit1stTrailDrop", 2.0);
        setField(mr, "cachedTrailDropAfterSplit", 2.0);
        setField(mr, "cachedSplit1stCooldownMs", 60_000L);
        setField(mr, "cachedGracePeriodMs", 60_000L);

        mrCfg = new MorningRushConfigEntity();
        mrCfg.setMode("PAPER");
        mrCfg.setSplitExitEnabled(true);
        mrCfg.setSplitTpPct(BigDecimal.valueOf(1.5));
        mrCfg.setSplitRatio(BigDecimal.valueOf(0.60));
        mrCfg.setTrailDropAfterSplit(BigDecimal.valueOf(2.0));
        mrCfg.setSplit1stTrailDrop(BigDecimal.valueOf(2.0));
        when(mrConfigRepo.loadOrCreate()).thenReturn(mrCfg);

        when(txTemplate.execute(any())).thenAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock inv) throws Throwable {
                Object cb = inv.getArgument(0);
                if (cb instanceof TransactionCallbackWithoutResult) {
                    ((TransactionCallbackWithoutResult) cb)
                            .doInTransaction(mock(TransactionStatus.class));
                }
                return null;
            }
        });
    }

    @Test
    @DisplayName("V129-DBFAIL-MR-1: positionRepo.save 실패 → splitPhase=0 롤백 + split1stExecutedAtMap 제거")
    public void mr_dbSaveFailureRollsBackMemory() throws Exception {
        final String market = "KRW-FAIL";
        long nowMs = System.currentTimeMillis();

        // 1) 사전 상태: SPLIT_1ST 판정 직후 처럼 메모리에만 선반영된 상태
        ConcurrentHashMap<String, double[]> cache = getMrCache();
        // [avg, qty, openedAt, peak, trough, splitPhase=1(선반영), armed=0]
        cache.put(market, new double[]{100.0, 1000.0, nowMs - 120_000, 102.0, 100.0, 1, 0});

        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Long> execMap = (ConcurrentHashMap<String, Long>)
                getField(mr, "split1stExecutedAtMap");
        execMap.put(market, nowMs);  // 메모리 쿨다운 선반영

        // 2) DB commit 실패 시뮬레이션: positionRepo.save() 호출 시 예외
        doThrow(new RuntimeException("simulated DB failure"))
                .when(positionRepo).save(any(PositionEntity.class));

        PositionEntity pe = buildPosition(market, 1000.0, 100.0, "MORNING_RUSH");
        // 주의: executeSplitFirstSell은 pe.getSplitPhase() != 0 체크로 가드 → splitPhase 0 설정
        pe.setSplitPhase(0);

        // 3) executeSplitFirstSell 직접 호출 (reflection) — peakCaptured/armedCaptured 없이
        invokeExecuteSplitFirst(pe, 101.8, "SPLIT_1ST test failure path");

        // 4) 검증: catch 블록이 memory 롤백 수행
        double[] pos = cache.get(market);
        assertNotNull(pos, "positionCache는 entry 유지 (catch는 remove 안 함)");
        assertEquals(0, (int) pos[5], "V129 #9: splitPhase 롤백=0");
        assertFalse(execMap.containsKey(market),
                "V129 #9: split1stExecutedAtMap 제거 (원자성 복구)");
    }

    @Test
    @DisplayName("V129-DBFAIL-MR-2: save 성공 시 쿨다운 map 유지 (회귀 반대 검증)")
    public void mr_dbSaveSuccessKeepsCooldown() throws Exception {
        final String market = "KRW-OK";
        long nowMs = System.currentTimeMillis();

        ConcurrentHashMap<String, double[]> cache = getMrCache();
        cache.put(market, new double[]{100.0, 1000.0, nowMs - 120_000, 102.0, 100.0, 1, 0});

        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Long> execMap = (ConcurrentHashMap<String, Long>)
                getField(mr, "split1stExecutedAtMap");
        execMap.put(market, nowMs);

        // save 정상 동작 (예외 없음, void라 별도 stub 불필요)
        PositionEntity pe = buildPosition(market, 1000.0, 100.0, "MORNING_RUSH");
        pe.setSplitPhase(0);
        when(positionRepo.findById(market)).thenReturn(Optional.of(pe));

        invokeExecuteSplitFirst(pe, 101.8, "SPLIT_1ST success path");

        // 정상 시 splitPhase 1 유지 + 쿨다운 유지
        double[] pos = cache.get(market);
        assertNotNull(pos, "cache 유지");
        assertEquals(1, (int) pos[5], "정상 커밋 — splitPhase=1 유지");
        assertTrue(execMap.containsKey(market), "정상 커밋 — 쿨다운 map 유지");
    }

    @Test
    @DisplayName("V129-DBFAIL-MR-3: armed=1 상태에서 DB 실패 → armed 유지 (다음 tick 재시도 가능)")
    public void mr_dbFailureKeepsArmed() throws Exception {
        final String market = "KRW-ARMED";
        long nowMs = System.currentTimeMillis();

        ConcurrentHashMap<String, double[]> cache = getMrCache();
        cache.put(market, new double[]{100.0, 1000.0, nowMs - 120_000, 102.0, 100.0, 1, 1});

        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Long> execMap = (ConcurrentHashMap<String, Long>)
                getField(mr, "split1stExecutedAtMap");
        execMap.put(market, nowMs);

        doThrow(new RuntimeException("boom")).when(positionRepo).save(any(PositionEntity.class));

        PositionEntity pe = buildPosition(market, 1000.0, 100.0, "MORNING_RUSH");
        pe.setSplitPhase(0);
        invokeExecuteSplitFirst(pe, 101.8, "SPLIT_1ST armed rollback");

        double[] pos = cache.get(market);
        assertEquals(0, (int) pos[5], "splitPhase 롤백");
        // V115 armed는 catch에서 건드리지 않음 — drop 조건으로 armed된 상태 유지 (다음 tick 재시도)
        assertEquals(1.0, pos[6], 0.01, "armed 유지 (rollback이 건드리지 않음)");
        assertFalse(execMap.containsKey(market), "쿨다운 map 제거");
    }

    @Test
    @DisplayName("V129-DBFAIL-OP: Opening detector clearSplit1stCooldown() 직접 호출 경로 검증")
    public void op_clearSplit1stCooldownMethod() throws Exception {
        OpeningBreakoutDetector det = new OpeningBreakoutDetector(mock(SharedPriceService.class));
        det.setSplitExitEnabled(true);

        final String market = "KRW-OP-RB";
        // split1stExecutedAtMap 에 직접 put 후 clear 호출 → 제거 확인
        Field f = OpeningBreakoutDetector.class.getDeclaredField("split1stExecutedAtMap");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Long> execMap = (ConcurrentHashMap<String, Long>) f.get(det);
        execMap.put(market, System.currentTimeMillis());
        assertTrue(execMap.containsKey(market), "precondition");

        det.clearSplit1stCooldown(market);

        assertFalse(execMap.containsKey(market),
                "V129 #9: clearSplit1stCooldown — 쿨다운 맵만 제거 (포지션 자체는 건드리지 않음)");
    }

    // ═══════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════
    private void invokeExecuteSplitFirst(PositionEntity pe, double price, String reason) throws Exception {
        Method m = MorningRushScannerService.class.getDeclaredMethod(
                "executeSplitFirstSell",
                PositionEntity.class, double.class, String.class, MorningRushConfigEntity.class);
        m.setAccessible(true);
        m.invoke(mr, pe, price, reason, mrCfg);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, double[]> getMrCache() throws Exception {
        Field f = MorningRushScannerService.class.getDeclaredField("positionCache");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, double[]>) f.get(mr);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private Object getField(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private PositionEntity buildPosition(String market, double qty, double avg, String entry) {
        PositionEntity pe = new PositionEntity();
        pe.setMarket(market);
        pe.setQty(BigDecimal.valueOf(qty));
        pe.setAvgPrice(BigDecimal.valueOf(avg));
        pe.setAddBuys(0);
        pe.setOpenedAt(Instant.now());
        pe.setEntryStrategy(entry);
        return pe;
    }
}
