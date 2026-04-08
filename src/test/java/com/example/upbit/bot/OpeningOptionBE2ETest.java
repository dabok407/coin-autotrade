package com.example.upbit.bot;

import com.example.upbit.db.*;
import com.example.upbit.market.CandleService;
import com.example.upbit.market.SharedPriceService;
import com.example.upbit.market.UpbitCandle;
import com.example.upbit.market.UpbitMarketCatalogService;
import com.example.upbit.trade.LiveOrderService;
import com.example.upbit.upbit.UpbitPrivateClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 옵션 B end-to-end 시나리오 테스트.
 *
 * 운영 메서드 OpeningScannerService.tryWsBreakoutBuy()를 reflection으로 직접 호출하여
 * WS 콜백 → 매수까지의 전체 흐름을 PAPER 모드에서 검증.
 *
 * 검증 시나리오:
 *  1. Happy path — 캐시 hit + 모든 필터 통과 → executeBuy + position 저장 + throttle 기록
 *  2. 사전 중복 포지션 — 이미 SCALP_OPENING_BREAK 보유 → 차단
 *  3. SharedTradeThrottle 차단 — 직전 매수 기록 → throttle.canBuy false
 *  4. 매도 쿨다운 — sellCooldownMap 5분 이내 → 차단
 *  5. 캐시 miss → fallback fetch → 통과
 *  6. 캐시 부족 (< 25) + fetch 실패 → INSUFFICIENT_CANDLES
 *  7. 동시 호출 (wsBreakoutProcessing) — 같은 market 두 번째는 차단
 *  8. RSI 과매수 → 차단
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class OpeningOptionBE2ETest {

    @Mock private OpeningScannerConfigRepository configRepo;
    @Mock private BotConfigRepository botConfigRepo;
    @Mock private PositionRepository positionRepo;
    @Mock private TradeRepository tradeLogRepo;
    @Mock private CandleService candleService;
    @Mock private UpbitMarketCatalogService catalogService;
    @Mock private LiveOrderService liveOrders;
    @Mock private UpbitPrivateClient privateClient;
    @Mock private TransactionTemplate txTemplate;

    private OpeningScannerService scanner;
    private SharedTradeThrottle sharedThrottle;

    @BeforeEach
    public void setUp() throws Exception {
        sharedThrottle = new SharedTradeThrottle();
        scanner = new OpeningScannerService(
                configRepo, botConfigRepo, positionRepo, tradeLogRepo,
                candleService, catalogService, liveOrders, privateClient, txTemplate,
                new OpeningBreakoutDetector(mock(SharedPriceService.class)),
                sharedThrottle
        );
        // running=true 강제
        setField("running", new AtomicBoolean(true));

        // 기본 config (PAPER, 항상 enabled, 모든 시간대 통과)
        OpeningScannerConfigEntity cfg = buildConfig();
        when(configRepo.findById(1)).thenReturn(Optional.of(cfg));

        // botConfig (capital 100K)
        BotConfigEntity botCfg = new BotConfigEntity();
        botCfg.setCapitalKrw(BigDecimal.valueOf(100000));
        when(botConfigRepo.findAll()).thenReturn(Collections.singletonList(botCfg));

        // 기본 빈 포지션
        when(positionRepo.findAll()).thenReturn(Collections.emptyList());

        // txTemplate: 콜백 즉시 실행
        when(txTemplate.execute(any())).thenAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                Object cb = invocation.getArgument(0);
                if (cb instanceof TransactionCallbackWithoutResult) {
                    ((TransactionCallbackWithoutResult) cb)
                            .doInTransaction(mock(TransactionStatus.class));
                }
                return null;
            }
        });
    }

    // ───────────────────── 시나리오 1: Happy path ─────────────────────
    @Test
    @DisplayName("시나리오 1: 캐시 hit + 모든 필터 통과 → executeBuy + throttle 기록")
    public void scenario1_happyPath() throws Exception {
        String market = "KRW-TEST";
        // 1분봉 캐시 직접 주입 (양봉 + 거래량 1.5x+ + RSI ~50 + EMA20 아래 가격)
        getOneMinCandleCache().put(market, sidewaysCandlesPass());

        invokeTry(market, 100.5, 99.0, 1.5);

        // executeBuy 통해 position 저장 + tradeLog 저장
        verify(positionRepo, times(1)).save(any(PositionEntity.class));
        verify(tradeLogRepo, times(1)).save(any(TradeEntity.class));

        // throttle 기록 확인
        assertFalse(sharedThrottle.canBuy(market),
                "happy path 후 SharedTradeThrottle에 매수 기록되어야 함");
    }

    // ───────────────────── 시나리오 2: 사전 중복 포지션 ─────────────────────
    @Test
    @DisplayName("시나리오 2: 이미 SCALP_OPENING_BREAK 보유 → 차단 (executeBuy 호출 안 됨)")
    public void scenario2_alreadyHeld() throws Exception {
        String market = "KRW-TEST";

        PositionEntity existing = new PositionEntity();
        existing.setMarket(market);
        existing.setQty(BigDecimal.valueOf(10));
        existing.setAvgPrice(BigDecimal.valueOf(100));
        existing.setEntryStrategy("SCALP_OPENING_BREAK");
        when(positionRepo.findAll()).thenReturn(Collections.singletonList(existing));

        getOneMinCandleCache().put(market, sidewaysCandlesPass());

        invokeTry(market, 100.5, 99.0, 1.5);

        // 이미 보유 — executeBuy 호출 안 됨
        verify(positionRepo, never()).save(any(PositionEntity.class));
        // throttle도 기록 안 됨
        assertTrue(sharedThrottle.canBuy(market),
                "차단된 경우 throttle 기록되면 안 됨");
    }

    // ───────────────────── 시나리오 3: SharedTradeThrottle 차단 ─────────────────────
    @Test
    @DisplayName("시나리오 3: throttle 직전 매수 기록 → 20분 쿨다운 차단")
    public void scenario3_throttleBlocked() throws Exception {
        String market = "KRW-TEST";

        // 사전에 다른 스캐너(가상)가 매수했다고 throttle에 기록
        sharedThrottle.recordBuy(market);

        getOneMinCandleCache().put(market, sidewaysCandlesPass());

        invokeTry(market, 100.5, 99.0, 1.5);

        verify(positionRepo, never()).save(any(PositionEntity.class));
    }

    // ───────────────────── 시나리오 4: 매도 쿨다운 ─────────────────────
    @Test
    @DisplayName("시나리오 4: sellCooldownMap 5분 이내 → 차단")
    public void scenario4_sellCooldown() throws Exception {
        String market = "KRW-TEST";

        // 1분 전 매도 기록
        getSellCooldownMap().put(market, System.currentTimeMillis() - 60_000L);

        getOneMinCandleCache().put(market, sidewaysCandlesPass());

        invokeTry(market, 100.5, 99.0, 1.5);

        verify(positionRepo, never()).save(any(PositionEntity.class));
    }

    // ───────────────────── 시나리오 5: 캐시 miss → fallback fetch ─────────────────────
    @Test
    @DisplayName("시나리오 5: 1분봉 캐시 miss → candleService fallback fetch → 매수 성공")
    public void scenario5_cacheMissFallback() throws Exception {
        String market = "KRW-TEST";

        // 캐시 비어있음 — fallback fetch 동작 확인
        when(candleService.getMinuteCandlesPaged(eq(market), eq(1), eq(60)))
                .thenReturn(sidewaysCandlesPass());

        invokeTry(market, 100.5, 99.0, 1.5);

        verify(candleService, times(1)).getMinuteCandlesPaged(market, 1, 60);
        verify(positionRepo, times(1)).save(any(PositionEntity.class));
    }

    // ───────────────────── 시나리오 6: 캐시 부족 + fetch 실패 ─────────────────────
    @Test
    @DisplayName("시나리오 6: 캐시 부족 + fetch 예외 → CANDLE_FETCH_FAIL → 차단")
    public void scenario6_fetchFail() throws Exception {
        String market = "KRW-TEST";

        when(candleService.getMinuteCandlesPaged(anyString(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("Upbit API down"));

        invokeTry(market, 100.5, 99.0, 1.5);

        verify(positionRepo, never()).save(any(PositionEntity.class));
    }

    // ───────────────────── 시나리오 7: 동시 호출 중복 차단 ─────────────────────
    @Test
    @DisplayName("시나리오 7: 같은 market 동시 호출 시 두번째는 wsBreakoutProcessing 차단")
    public void scenario7_concurrentDuplicateBlocked() throws Exception {
        String market = "KRW-TEST";

        // 첫번째 호출이 처리 중이라고 가정 — Set에 미리 추가
        getWsBreakoutProcessing().add(market);

        getOneMinCandleCache().put(market, sidewaysCandlesPass());

        invokeTry(market, 100.5, 99.0, 1.5);

        // 두번째 호출은 즉시 return (executeBuy 호출 안 됨)
        verify(positionRepo, never()).save(any(PositionEntity.class));

        // 첫번째 호출이 끝났다고 가정 — Set에서 제거 후 재시도하면 통과해야 함
        getWsBreakoutProcessing().remove(market);
        invokeTry(market, 100.5, 99.0, 1.5);
        verify(positionRepo, times(1)).save(any(PositionEntity.class));
    }

    // ───────────────────── 시나리오 8: RSI 과매수 ─────────────────────
    @Test
    @DisplayName("시나리오 8: 강한 상승 캔들 (RSI ≥83) → 차단")
    public void scenario8_rsiOverbought() throws Exception {
        String market = "KRW-TEST";

        getOneMinCandleCache().put(market, strongUptrendCandles());

        // wsPrice는 EMA20 위로 충분히 높게
        invokeTry(market, 200.0, 150.0, 1.5);

        verify(positionRepo, never()).save(any(PositionEntity.class));
    }

    // ═══════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════

    private void invokeTry(String market, double wsPrice, double rangeHigh, double bo) throws Exception {
        Method m = OpeningScannerService.class.getDeclaredMethod(
                "tryWsBreakoutBuy", String.class, double.class, double.class, double.class);
        m.setAccessible(true);
        m.invoke(scanner, market, wsPrice, rangeHigh, bo);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = OpeningScannerService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(scanner, value);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, List<UpbitCandle>> getOneMinCandleCache() throws Exception {
        Field f = OpeningScannerService.class.getDeclaredField("oneMinCandleCache");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, List<UpbitCandle>>) f.get(scanner);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, Long> getSellCooldownMap() throws Exception {
        Field f = OpeningScannerService.class.getDeclaredField("sellCooldownMap");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, Long>) f.get(scanner);
    }

    @SuppressWarnings("unchecked")
    private Set<String> getWsBreakoutProcessing() throws Exception {
        Field f = OpeningScannerService.class.getDeclaredField("wsBreakoutProcessing");
        f.setAccessible(true);
        return (Set<String>) f.get(scanner);
    }

    /** 모든 필터 통과하는 1분봉 60개 (양봉 + vol 2x + RSI ~50) */
    private List<UpbitCandle> sidewaysCandlesPass() {
        List<UpbitCandle> candles = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            UpbitCandle c = new UpbitCandle();
            c.market = "KRW-TEST";
            c.candle_date_time_utc = String.format("2026-04-08T%02d:%02d:00", i / 60, i % 60);
            // 횡보 + 약간 상승 (EMA20이 wsPrice 100.5보다 낮아야 함)
            double base = 99.0 + (i % 4 == 0 ? 0.3 : (i % 4 == 1 ? -0.2 : (i % 4 == 2 ? 0.2 : -0.3)));
            c.opening_price = base;
            c.trade_price = base + (i % 2 == 0 ? 0.1 : -0.1);
            c.high_price = Math.max(c.opening_price, c.trade_price) + 0.1;
            c.low_price = Math.min(c.opening_price, c.trade_price) - 0.1;
            c.candle_acc_trade_volume = 1000;
            candles.add(c);
        }
        // 마지막 캔들: 양봉 + 거래량 2x
        UpbitCandle last = candles.get(59);
        last.opening_price = 99.0;
        last.trade_price = 100.0;
        last.high_price = 100.5;
        last.low_price = 98.9;
        last.candle_acc_trade_volume = 2500; // avg ~1075 → ratio ~2.3x
        return candles;
    }

    /** 강한 상승 캔들 (RSI 100) — 마지막 캔들 vol 평균 대비 2x 보장 */
    private List<UpbitCandle> strongUptrendCandles() {
        List<UpbitCandle> candles = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            UpbitCandle c = new UpbitCandle();
            c.market = "KRW-TEST";
            c.candle_date_time_utc = String.format("2026-04-08T%02d:%02d:00", i / 60, i % 60);
            double price = 100.0 + i * 1.0;
            c.opening_price = price;
            c.trade_price = price + 0.5;
            c.high_price = c.trade_price + 0.1;
            c.low_price = c.opening_price - 0.1;
            c.candle_acc_trade_volume = 1000;
            candles.add(c);
        }
        // 마지막 캔들 vol bump (vol 필터 통과 → RSI 필터에서 차단되도록)
        candles.get(59).candle_acc_trade_volume = 5000;
        return candles;
    }

    private OpeningScannerConfigEntity buildConfig() {
        OpeningScannerConfigEntity cfg = new OpeningScannerConfigEntity();
        cfg.setEnabled(true);
        cfg.setMode("PAPER");
        cfg.setMaxPositions(3);
        cfg.setOrderSizingMode("PCT");
        cfg.setOrderSizingValue(BigDecimal.valueOf(30)); // 30% of 100K = 30K
        cfg.setCandleUnitMin(5);
        cfg.setBtcFilterEnabled(false);
        cfg.setTopN(10);
        cfg.setRangeStartHour(0);
        cfg.setRangeStartMin(0);
        cfg.setRangeEndHour(23);
        cfg.setRangeEndMin(59);
        cfg.setEntryStartHour(0);
        cfg.setEntryStartMin(0);
        cfg.setEntryEndHour(23);
        cfg.setEntryEndMin(59);
        cfg.setSessionEndHour(23);
        cfg.setSessionEndMin(59);
        cfg.setExcludeMarkets("");
        return cfg;
    }
}
