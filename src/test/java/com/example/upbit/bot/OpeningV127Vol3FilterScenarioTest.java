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
 * V127 vol3 ratio 임계값 필터 실전 시나리오 통합테스트.
 *
 * tryWsBreakoutBuy() 운영 경로를 직접 호출해서
 * DB 기반 임계값(OpeningScannerConfigEntity.vol3RatioThreshold)이
 * 실제 매수 차단/통과 분기에 정확히 반영되는지 검증한다.
 *
 * - 양봉/RSI/EMA20 등 나머지 필터는 모두 통과하도록 픽스처 구성
 * - vol3Ratio 만 조작해서 임계값 분기만 순수 검증
 * - OpeningScannerFixIntegrationTest 템플릿을 그대로 재사용 (동일 mock 세팅)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class OpeningV127Vol3FilterScenarioTest {

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

    /** 기본 config 빌더 — 각 테스트에서 vol3RatioThreshold만 바꿔서 사용 */
    private OpeningScannerConfigEntity currentCfg;

    @BeforeEach
    public void setUp() throws Exception {
        sharedThrottle = new SharedTradeThrottle();
        breakoutDetector = new OpeningBreakoutDetector(mock(SharedPriceService.class));
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

        currentCfg = buildConfigAllHours();
        when(configRepo.findById(1)).thenAnswer(new Answer<Optional<OpeningScannerConfigEntity>>() {
            @Override
            public Optional<OpeningScannerConfigEntity> answer(InvocationOnMock invocation) {
                return Optional.of(currentCfg);
            }
        });

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
    //  시나리오 1: 기본 임계값 2.5 — vol3=2.0 차단
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("V127 S1: threshold=2.5, vol3=2.0 → VOL_3MIN_WEAK 차단")
    public void v127_s1_defaultThresholdBlocksWeakSurge() throws Exception {
        currentCfg.setVol3RatioThreshold(BigDecimal.valueOf(2.5));

        String market = "KRW-TEST";
        getOneMinCandleCache().put(market, candlesWithVol3Ratio(2.0));

        invokeTry(market, 100.5, 99.0, 1.5);

        verify(positionRepo, never()).save(any(PositionEntity.class));
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 2: 기본 임계값 2.5 — vol3=3.0 통과 매수
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("V127 S2: threshold=2.5, vol3=3.0 → 매수 성공")
    public void v127_s2_defaultThresholdPassesStrongSurge() throws Exception {
        currentCfg.setVol3RatioThreshold(BigDecimal.valueOf(2.5));

        String market = "KRW-TEST";
        getOneMinCandleCache().put(market, candlesWithVol3Ratio(3.0));

        invokeTry(market, 100.5, 99.0, 1.5);

        verify(positionRepo, times(1)).save(any(PositionEntity.class));
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 3: 경계값 — vol3=2.5 정확히 임계값과 동일 (>=) → 통과
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("V127 S3: threshold=2.5, vol3=2.5 → 경계 통과 (>=)")
    public void v127_s3_boundaryExactPasses() throws Exception {
        currentCfg.setVol3RatioThreshold(BigDecimal.valueOf(2.5));

        String market = "KRW-TEST";
        getOneMinCandleCache().put(market, candlesWithVol3Ratio(2.5));

        invokeTry(market, 100.5, 99.0, 1.5);

        verify(positionRepo, times(1)).save(any(PositionEntity.class));
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 4: 경계값 바로 아래 — vol3=2.4 < 2.5 → 차단
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("V127 S4: threshold=2.5, vol3=2.4 → 임계값 미만 차단")
    public void v127_s4_boundaryJustBelowBlocks() throws Exception {
        currentCfg.setVol3RatioThreshold(BigDecimal.valueOf(2.5));

        String market = "KRW-TEST";
        getOneMinCandleCache().put(market, candlesWithVol3Ratio(2.4));

        invokeTry(market, 100.5, 99.0, 1.5);

        verify(positionRepo, never()).save(any(PositionEntity.class));
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 5: UI 롤백 임계값 1.5 — vol3=1.5 통과 (이전 동작 보존)
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("V127 S5: threshold=1.5 (롤백값), vol3=1.5 → 기존 동작 통과")
    public void v127_s5_rollbackThresholdBackwardCompat() throws Exception {
        currentCfg.setVol3RatioThreshold(BigDecimal.valueOf(1.5));

        String market = "KRW-TEST";
        getOneMinCandleCache().put(market, candlesWithVol3Ratio(1.5));

        invokeTry(market, 100.5, 99.0, 1.5);

        verify(positionRepo, times(1)).save(any(PositionEntity.class));
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 6: 최대 임계값 5.0 — vol3=4.9 차단 (극단 surge만 허용)
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("V127 S6: threshold=5.0 (UI max), vol3=4.9 → 차단")
    public void v127_s6_maxThresholdBlocksBelow() throws Exception {
        currentCfg.setVol3RatioThreshold(BigDecimal.valueOf(5.0));

        String market = "KRW-TEST";
        getOneMinCandleCache().put(market, candlesWithVol3Ratio(4.9));

        invokeTry(market, 100.5, 99.0, 1.5);

        verify(positionRepo, never()).save(any(PositionEntity.class));
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 7: 최대 임계값 5.0 — vol3=6.0 통과
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("V127 S7: threshold=5.0 (UI max), vol3=6.0 → 통과")
    public void v127_s7_maxThresholdPassesExtreme() throws Exception {
        currentCfg.setVol3RatioThreshold(BigDecimal.valueOf(5.0));

        String market = "KRW-TEST";
        getOneMinCandleCache().put(market, candlesWithVol3Ratio(6.0));

        invokeTry(market, 100.5, 99.0, 1.5);

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

    /**
     * vol3Ratio = (마지막 3봉 평균 vol) / (그 직전 20봉 평균 vol) 가 정확히 지정된 값이 되도록
     * 60봉 1분봉을 생성한다. 양봉/RSI/EMA20 조건은 highVolPassingCandles와 동일.
     */
    private List<UpbitCandle> candlesWithVol3Ratio(double ratio) {
        List<UpbitCandle> candles = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            UpbitCandle c = new UpbitCandle();
            c.market = "KRW-TEST";
            c.candle_date_time_utc = String.format("2026-04-20T%02d:%02d:00", i / 60, i % 60);
            double base = 99.0 + (i % 4 == 0 ? 0.3 : (i % 4 == 1 ? -0.2 : (i % 4 == 2 ? 0.2 : -0.3)));
            c.opening_price = base;
            c.trade_price = base + (i % 2 == 0 ? 0.1 : -0.1);
            c.high_price = Math.max(c.opening_price, c.trade_price) + 0.1;
            c.low_price = Math.min(c.opening_price, c.trade_price) - 0.1;
            c.candle_acc_trade_volume = 1000;
            candles.add(c);
        }
        // 마지막 3봉의 vol만 ratio * 1000으로 덮어쓰면 vol3Ratio = ratio
        double recentVol = 1000.0 * ratio;
        for (int i = 57; i < 60; i++) {
            candles.get(i).candle_acc_trade_volume = recentVol;
        }
        // 마지막 1분봉: 양봉 (close > open) — 양봉 필터 통과 확정
        UpbitCandle last = candles.get(59);
        last.opening_price = 99.0;
        last.trade_price = 100.0;
        last.high_price = 100.5;
        last.low_price = 98.9;
        last.candle_acc_trade_volume = recentVol;
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
        cfg.setEntryStartHour(0);
        cfg.setEntryStartMin(0);
        cfg.setEntryEndHour(23);
        cfg.setEntryEndMin(59);
        cfg.setSessionEndHour(23);
        cfg.setSessionEndMin(59);
        cfg.setExcludeMarkets("");
        // 기본값 2.5 — 개별 테스트에서 setVol3RatioThreshold로 덮어씀
        return cfg;
    }
}
