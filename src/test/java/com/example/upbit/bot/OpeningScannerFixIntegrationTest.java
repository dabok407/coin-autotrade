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
 * 2026-04-09 오프닝 스캐너 fix 실제 시나리오 통합 테스트.
 *
 * tryWsBreakoutBuy() 운영 메서드를 직접 호출해서 fix 동작 검증.
 * - vol 0.2x로도 매수 통과 (vol 필터 제거)
 * - SKIP 시 releaseMarket 호출
 * - entry window 밖 매수 안 함
 * - 양봉/RSI/EMA20만으로 필터링
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class OpeningScannerFixIntegrationTest {

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
    private OpeningBreakoutDetector breakoutDetector;
    private SharedTradeThrottle sharedThrottle;

    @BeforeEach
    public void setUp() throws Exception {
        sharedThrottle = new SharedTradeThrottle();
        breakoutDetector = new OpeningBreakoutDetector(mock(SharedPriceService.class));
        // entry window 시각 제한 비활성 (테스트는 모든 시각에서 동작)
        breakoutDetector.setEntryWindow(-1, -1);

        scanner = new OpeningScannerService(
                configRepo, botConfigRepo, positionRepo, tradeLogRepo,
                candleService, catalogService, liveOrders, privateClient, txTemplate,
                breakoutDetector, sharedThrottle,
                null
        );

        Field running = OpeningScannerService.class.getDeclaredField("running");
        running.setAccessible(true);
        ((AtomicBoolean) running.get(scanner)).set(true);

        // 기본 config (entry window: 모든 시각 허용)
        OpeningScannerConfigEntity cfg = buildConfigAllHours();
        when(configRepo.findById(1)).thenReturn(Optional.of(cfg));

        BotConfigEntity botCfg = new BotConfigEntity();
        botCfg.setCapitalKrw(BigDecimal.valueOf(1000000));
        when(botConfigRepo.findAll()).thenReturn(Collections.singletonList(botCfg));

        when(positionRepo.findAll()).thenReturn(Collections.emptyList());

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

    // ═══════════════════════════════════════════════════════════
    //  시나리오 1: vol3Ratio < 1.5x → 매수 차단 (2026-04-13 필터 복원)
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 1: vol3Ratio 0.7x → 매수 차단 (3분봉 볼륨 필터)")
    public void scenario1_lowVol3RatioBlocks() throws Exception {
        String market = "KRW-FLOCK";
        // 1분봉 캐시: 마지막 1분봉 vol 0.2x → vol3Ratio ~0.73 < 1.5x → 차단
        getOneMinCandleCache().put(market, lowVolPassingCandles());

        invokeTry(market, 100.5, 99.0, 1.5);

        // ★ vol3Ratio < 1.5x → 매수 차단
        verify(positionRepo, never()).save(any(PositionEntity.class));
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 2: 양봉 실패 → SKIP + releaseMarket 호출
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 2: 음봉 SKIP → releaseMarket 호출 (재시도 가능)")
    public void scenario2_bearishSkipReleasesMarket() throws Exception {
        String market = "KRW-TEST";
        getOneMinCandleCache().put(market, bearishLastCandles());

        // Detector에 미리 confirm 설정
        breakoutDetector.setRangeHighMap(new HashMap<String, Double>() {{
            put(market, 100.0);
        }});
        Field cm = OpeningBreakoutDetector.class.getDeclaredField("confirmedMarkets");
        cm.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> confirmedMarkets = (Set<String>) cm.get(breakoutDetector);
        confirmedMarkets.add(market);
        assertTrue(breakoutDetector.isAlreadyConfirmed(market), "사전 setup");

        invokeTry(market, 102.0, 100.0, 2.0);

        // 매수 안 됨
        verify(positionRepo, never()).save(any(PositionEntity.class));
        // releaseMarket 호출됨 → confirmedMarkets에서 제거
        assertFalse(breakoutDetector.isAlreadyConfirmed(market),
                "음봉 SKIP 후 releaseMarket 호출되어 confirmedMarkets에서 제거되어야 함");
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 3: RSI 과매수 SKIP → releaseMarket 호출
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 3: RSI 100 SKIP → releaseMarket 호출")
    public void scenario3_rsiOverboughtSkipReleases() throws Exception {
        String market = "KRW-TEST";
        getOneMinCandleCache().put(market, strongUptrendCandles());

        breakoutDetector.setRangeHighMap(new HashMap<String, Double>() {{
            put(market, 100.0);
        }});
        Field cm = OpeningBreakoutDetector.class.getDeclaredField("confirmedMarkets");
        cm.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> confirmedMarkets = (Set<String>) cm.get(breakoutDetector);
        confirmedMarkets.add(market);

        // 가격은 EMA20 위로 충분히 높게 (RSI 필터에서 차단)
        invokeTry(market, 200.0, 100.0, 100.0);

        verify(positionRepo, never()).save(any(PositionEntity.class));
        assertFalse(breakoutDetector.isAlreadyConfirmed(market),
                "RSI SKIP 후 releaseMarket 호출되어야 함");
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 4: EMA20 아래 SKIP → releaseMarket 호출
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 4: EMA20 아래 SKIP → releaseMarket 호출")
    public void scenario4_belowEma20SkipReleases() throws Exception {
        String market = "KRW-TEST";
        getOneMinCandleCache().put(market, lowVolPassingCandles());  // EMA20 ~99.5

        breakoutDetector.setRangeHighMap(new HashMap<String, Double>() {{
            put(market, 100.0);
        }});
        Field cm = OpeningBreakoutDetector.class.getDeclaredField("confirmedMarkets");
        cm.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> confirmedMarkets = (Set<String>) cm.get(breakoutDetector);
        confirmedMarkets.add(market);

        // 가격을 EMA20 아래로 (90은 EMA20 99.5보다 한참 아래)
        invokeTry(market, 90.0, 100.0, -10.0);

        verify(positionRepo, never()).save(any(PositionEntity.class));
        assertFalse(breakoutDetector.isAlreadyConfirmed(market),
                "EMA20 SKIP 후 releaseMarket 호출되어야 함");
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 5: entry window 밖 호출 → 매수 안 함
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 5: entry window 밖 (00:00~00:01) → tryWsBreakoutBuy return")
    public void scenario5_outsideEntryWindowNoBuy() throws Exception {
        // entry window를 00:00~00:01로 설정 (현재 시각이 거의 확실히 밖)
        OpeningScannerConfigEntity cfg = buildConfigAllHours();
        cfg.setEntryStartHour(0);
        cfg.setEntryStartMin(0);
        cfg.setEntryEndHour(0);
        cfg.setEntryEndMin(1);
        when(configRepo.findById(1)).thenReturn(Optional.of(cfg));

        String market = "KRW-TEST";
        getOneMinCandleCache().put(market, lowVolPassingCandles());

        invokeTry(market, 100.5, 99.0, 1.5);

        // entry window 밖 → 매수 안 함
        verify(positionRepo, never()).save(any(PositionEntity.class));
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 6: 정상 happy path — 모든 필터 통과
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 6: happy path — 양봉 + RSI 50 + EMA20 위 + 돌파 1.5% + vol3≥1.5x → 매수")
    public void scenario6_happyPath() throws Exception {
        String market = "KRW-TEST";
        getOneMinCandleCache().put(market, highVolPassingCandles());

        invokeTry(market, 100.5, 99.0, 1.5);

        verify(positionRepo, times(1)).save(any(PositionEntity.class));
        verify(tradeLogRepo, times(1)).save(any(TradeEntity.class));
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 7: KRW-FLOCK 시간 흐름 정확 재현
    //  T1: 옵션 B SKIP (양봉이 음봉이라 가정) + releaseMarket
    //  T2: 다음 가격 update에 다시 confirm 가능 (releaseMarket 효과)
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 7: SKIP 후 다음 시그널에 재시도 (releaseMarket 효과)")
    public void scenario7_skipThenRetry() throws Exception {
        String market = "KRW-FLOCK";

        // T1: 음봉 캐시로 SKIP
        getOneMinCandleCache().put(market, bearishLastCandles());
        breakoutDetector.setRangeHighMap(new HashMap<String, Double>() {{
            put(market, 79.9);
        }});
        Field cm = OpeningBreakoutDetector.class.getDeclaredField("confirmedMarkets");
        cm.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> confirmedMarkets = (Set<String>) cm.get(breakoutDetector);
        confirmedMarkets.add(market);

        invokeTry(market, 80.7, 79.9, 1.0);

        // SKIP + release 확인
        verify(positionRepo, never()).save(any(PositionEntity.class));
        assertFalse(breakoutDetector.isAlreadyConfirmed(market),
                "T1 SKIP 후 release됨");

        // T2: 양봉 + 높은 볼륨 캐시로 교체 (가격 더 올라간 시뮬레이션)
        getOneMinCandleCache().put(market, highVolPassingCandles());
        // 다시 confirmed 추가 (실제 환경에서 BreakoutDetector가 새 시그널 생성한 것 시뮬레이션)
        confirmedMarkets.add(market);

        invokeTry(market, 100.5, 99.0, 1.5);

        // T2: 양봉이라 매수 성공
        verify(positionRepo, times(1)).save(any(PositionEntity.class));
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

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, List<UpbitCandle>> getOneMinCandleCache() throws Exception {
        Field f = OpeningScannerService.class.getDeclaredField("oneMinCandleCache");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, List<UpbitCandle>>) f.get(scanner);
    }

    /** 양봉 + vol 2.0x (3분봉 평균) + RSI ~50 + EMA20 ~99.5 — 모든 필터 통과 */
    private List<UpbitCandle> highVolPassingCandles() {
        List<UpbitCandle> candles = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            UpbitCandle c = new UpbitCandle();
            c.market = "KRW-TEST";
            c.candle_date_time_utc = String.format("2026-04-09T%02d:%02d:00", i / 60, i % 60);
            double base = 99.0 + (i % 4 == 0 ? 0.3 : (i % 4 == 1 ? -0.2 : (i % 4 == 2 ? 0.2 : -0.3)));
            c.opening_price = base;
            c.trade_price = base + (i % 2 == 0 ? 0.1 : -0.1);
            c.high_price = Math.max(c.opening_price, c.trade_price) + 0.1;
            c.low_price = Math.min(c.opening_price, c.trade_price) - 0.1;
            c.candle_acc_trade_volume = 1000;
            candles.add(c);
        }
        // 마지막 3분봉: vol 2000 (2.0x → vol3Ratio = 2.0 ≥ 1.5 통과)
        for (int i = 57; i < 60; i++) {
            candles.get(i).candle_acc_trade_volume = 2000;
        }
        // 마지막 1분봉: 양봉 (close > open)
        UpbitCandle last = candles.get(59);
        last.opening_price = 99.0;
        last.trade_price = 100.0;
        last.high_price = 100.5;
        last.low_price = 98.9;
        return candles;
    }

    /** 양봉 + vol 0.2x + RSI ~50 + EMA20 ~99.5 — vol3Ratio 차단 테스트용 */
    private List<UpbitCandle> lowVolPassingCandles() {
        List<UpbitCandle> candles = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            UpbitCandle c = new UpbitCandle();
            c.market = "KRW-TEST";
            c.candle_date_time_utc = String.format("2026-04-09T%02d:%02d:00", i / 60, i % 60);
            double base = 99.0 + (i % 4 == 0 ? 0.3 : (i % 4 == 1 ? -0.2 : (i % 4 == 2 ? 0.2 : -0.3)));
            c.opening_price = base;
            c.trade_price = base + (i % 2 == 0 ? 0.1 : -0.1);
            c.high_price = Math.max(c.opening_price, c.trade_price) + 0.1;
            c.low_price = Math.min(c.opening_price, c.trade_price) - 0.1;
            c.candle_acc_trade_volume = 1000;
            candles.add(c);
        }
        // 마지막 1분봉: 양봉 + vol 0.2x (200 vs 평균 1000)
        UpbitCandle last = candles.get(59);
        last.opening_price = 99.0;
        last.trade_price = 100.0;  // 양봉 (close > open)
        last.high_price = 100.5;
        last.low_price = 98.9;
        last.candle_acc_trade_volume = 200;  // 0.2배 (vol 필터에 차단되면 안 됨)
        return candles;
    }

    /** 음봉 — 1번 필터(양봉)에서 차단되어야 함 */
    private List<UpbitCandle> bearishLastCandles() {
        List<UpbitCandle> candles = lowVolPassingCandles();
        UpbitCandle last = candles.get(59);
        last.opening_price = 100.5;  // 음봉 (open > close)
        last.trade_price = 100.0;
        return candles;
    }

    /** 강한 상승 (RSI 100) — RSI 필터에서 차단되어야 함 */
    private List<UpbitCandle> strongUptrendCandles() {
        List<UpbitCandle> candles = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            UpbitCandle c = new UpbitCandle();
            c.market = "KRW-TEST";
            c.candle_date_time_utc = String.format("2026-04-09T%02d:%02d:00", i / 60, i % 60);
            double price = 100.0 + i * 1.0;
            c.opening_price = price;
            c.trade_price = price + 0.5;
            c.high_price = c.trade_price + 0.1;
            c.low_price = c.opening_price - 0.1;
            c.candle_acc_trade_volume = 1000;
            candles.add(c);
        }
        return candles;
    }

    private OpeningScannerConfigEntity buildConfigAllHours() {
        OpeningScannerConfigEntity cfg = new OpeningScannerConfigEntity();
        cfg.setEnabled(true);
        cfg.setMode("PAPER");
        cfg.setMaxPositions(3);
        cfg.setOrderSizingMode("PCT");
        cfg.setOrderSizingValue(BigDecimal.valueOf(30));
        cfg.setCandleUnitMin(5);
        cfg.setBtcFilterEnabled(false);
        cfg.setTopN(10);
        cfg.setRangeStartHour(0);
        cfg.setRangeStartMin(0);
        cfg.setRangeEndHour(23);
        cfg.setRangeEndMin(59);
        // entry window: 모든 시각 (00:00 ~ 23:59)
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
