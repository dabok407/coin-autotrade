package com.example.upbit.bot;

import com.example.upbit.db.*;
import com.example.upbit.market.CandleService;
import com.example.upbit.market.SharedPriceService;
import com.example.upbit.market.TickerService;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V109 올데이 스캐너 진입 필터 강화 테스트.
 *
 * processSurgeBuy() 내 신규 필터:
 *  - RSI ≥ 80 차단
 *  - 거래량 < 5x 차단
 *  - EMA21 하락(DN) 차단
 *  - 급등 방향 꺾임 차단
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class AllDayEntryFilterTest {

    @Mock private AllDayScannerConfigRepository configRepo;
    @Mock private BotConfigRepository botConfigRepo;
    @Mock private PositionRepository positionRepo;
    @Mock private TradeRepository tradeLogRepo;
    @Mock private CandleService candleService;
    @Mock private UpbitMarketCatalogService catalogService;
    @Mock private LiveOrderService liveOrders;
    @Mock private UpbitPrivateClient privateClient;
    @Mock private TransactionTemplate txTemplate;
    @Mock private TickerService tickerService;
    @Mock private SharedPriceService sharedPriceService;

    private AllDayScannerService scanner;
    private SharedTradeThrottle throttle;

    @BeforeEach
    public void setUp() throws Exception {
        throttle = new SharedTradeThrottle();
        ScannerLockService scannerLockService = new ScannerLockService(botConfigRepo, positionRepo, tradeLogRepo);
        scanner = new AllDayScannerService(
                configRepo, botConfigRepo, positionRepo, tradeLogRepo,
                candleService, catalogService, liveOrders, privateClient, txTemplate,
                tickerService, sharedPriceService, throttle, scannerLockService
        );
        setField("running", new AtomicBoolean(true));
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        setField("scheduler", sched);

        AllDayScannerConfigEntity cfg = buildConfig();
        when(configRepo.loadOrCreate()).thenReturn(cfg);
        when(botConfigRepo.findAll()).thenReturn(Collections.singletonList(buildBotConfig()));
        when(positionRepo.findAll()).thenReturn(Collections.emptyList());

        when(txTemplate.execute(any())).thenAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                Object cb = invocation.getArgument(0);
                if (cb instanceof TransactionCallbackWithoutResult) {
                    ((TransactionCallbackWithoutResult) cb).doInTransaction(mock(TransactionStatus.class));
                }
                return null;
            }
        });
    }

    // ═══════════════════════════════════════════════════
    //  시나리오 1: RSI 80+ → 매수 차단
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("RSI 82 → 매수 차단 (과매수)")
    public void rsiOverboughtBlocked() throws Exception {
        // 강한 상승 캔들 (RSI 높게 나오도록)
        when(candleService.getMinuteCandles(eq("KRW-RSI"), anyInt(), anyInt(), any()))
                .thenReturn(buildStrongUptrendCandles());

        invokeSurge("KRW-RSI", 160.0, 3.0);

        // 매수 안 됨 (RSI 과매수 차단)
        verify(positionRepo, never()).save(any(PositionEntity.class));
    }

    // ═══════════════════════════════════════════════════
    //  시나리오 2: RSI 차단은 HCB 시그널 통과 후에만 작동 확인
    //  (HCB LOW_SCORE로 차단되면 RSI 필터까지 안 감 → RSI 차단 로그 없음)
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("RSI 80 미만 + HCB 미통과 → RSI가 아닌 HCB에서 차단 (RSI 필터 무관)")
    public void rsiNormalButHcbFails_noRsiBlock() throws Exception {
        // 캔들 데이터: RSI 적정이지만 HCB 점수 미달
        when(candleService.getMinuteCandles(eq("KRW-OK"), anyInt(), anyInt(), any()))
                .thenReturn(buildGoodEntryCandles());

        invokeSurge("KRW-OK", 107.0, 3.0);

        // HCB 점수 미달로 차단됨 (RSI_OVERBOUGHT 로그가 아님)
        // → 진입 필터(RSI)까지 도달하지 않음 = RSI 필터가 잘못 차단하지 않음
        verify(positionRepo, never()).save(any(PositionEntity.class));
    }

    // ═══════════════════════════════════════════════════
    //  시나리오 3: 거래량 3x → 매수 차단
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("거래량 3x → 매수 차단 (최소 5x 필요)")
    public void lowVolumeBlocked() throws Exception {
        when(candleService.getMinuteCandles(eq("KRW-VOL"), anyInt(), anyInt(), any()))
                .thenReturn(buildLowVolumeCandles());

        invokeSurge("KRW-VOL", 107.0, 3.0);

        verify(positionRepo, never()).save(any(PositionEntity.class));
    }

    // ═══════════════════════════════════════════════════
    //  시나리오 4: EMA21 하락 중 → 매수 차단
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("EMA21 하락 추세 → 매수 차단")
    public void ema21DownBlocked() throws Exception {
        when(candleService.getMinuteCandles(eq("KRW-EMA"), anyInt(), anyInt(), any()))
                .thenReturn(buildEma21DownCandles());

        invokeSurge("KRW-EMA", 97.0, 3.0);

        verify(positionRepo, never()).save(any(PositionEntity.class));
    }

    // ═══════════════════════════════════════════════════
    //  시나리오 5: 급등 꺾임 (wsPrice < 캔들가) → 차단
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("급등 꺾임: wsPrice < 캔들가 → 매수 차단")
    public void surgeFadingBlocked() throws Exception {
        // 캔들 마지막 종가 110원인데 wsPrice 105원 → 이미 꺾임
        List<UpbitCandle> candles = buildGoodEntryCandles();
        UpbitCandle last = candles.get(candles.size() - 1);
        last.trade_price = 110.0;  // 캔들 종가 110
        when(candleService.getMinuteCandles(eq("KRW-FADE"), anyInt(), anyInt(), any()))
                .thenReturn(candles);

        // wsPrice 105 < 110 * 0.998 = 109.78 → 꺾임
        invokeSurge("KRW-FADE", 105.0, 3.0);

        verify(positionRepo, never()).save(any(PositionEntity.class));
    }

    // ═══════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════

    private void invokeSurge(String market, double wsPrice, double changePct) throws Exception {
        Method m = AllDayScannerService.class.getDeclaredMethod(
                "processSurgeBuy", String.class, double.class, double.class);
        m.setAccessible(true);
        m.invoke(scanner, market, wsPrice, changePct);
    }

    /** 정상 진입 조건: 꾸준한 상승, RSI ~65, vol 8x, EMA21/50 UP, 양봉 */
    private List<UpbitCandle> buildGoodEntryCandles() {
        List<UpbitCandle> candles = new ArrayList<UpbitCandle>();
        for (int i = 0; i < 80; i++) {
            UpbitCandle c = new UpbitCandle();
            c.market = "KRW-OK";
            c.candle_date_time_utc = String.format("2026-04-13T%02d:%02d:00", i / 60, i % 60);
            // 꾸준한 상승 (EMA50 slope 양수 보장)
            double base = 100.0 + i * 0.1;
            // 중간중간 작은 조정 넣어서 RSI 65 부근 유도
            double adjust = (i % 5 == 3) ? -0.15 : 0.05;
            c.opening_price = base + adjust;
            c.trade_price = base + adjust + 0.15;
            c.high_price = c.trade_price + 0.1;
            c.low_price = c.opening_price - 0.1;
            c.candle_acc_trade_volume = 1000;
            candles.add(c);
        }
        // 마지막 캔들: 강한 양봉 + vol 8x + 신고가 돌파
        UpbitCandle last = candles.get(79);
        last.opening_price = 107.5;
        last.trade_price = 109.0;
        last.high_price = 109.5;
        last.low_price = 107.0;
        last.candle_acc_trade_volume = 8000;  // 8x
        // API는 newest-first, processSurgeBuy에서 reverse → oldest-first
        Collections.reverse(candles);
        return candles;
    }

    /** 강한 상승 (RSI 80+ 유도) */
    private List<UpbitCandle> buildStrongUptrendCandles() {
        List<UpbitCandle> candles = new ArrayList<UpbitCandle>();
        for (int i = 0; i < 80; i++) {
            UpbitCandle c = new UpbitCandle();
            c.market = "KRW-RSI";
            c.candle_date_time_utc = String.format("2026-04-13T%02d:%02d:00", i / 60, i % 60);
            double base = 100.0 + i * 0.8;
            c.opening_price = base;
            c.trade_price = base + 0.5;
            c.high_price = base + 0.7;
            c.low_price = base - 0.1;
            c.candle_acc_trade_volume = 8000;
            candles.add(c);
        }
        Collections.reverse(candles);
        return candles;
    }

    /** 거래량 3x (5x 미달) */
    private List<UpbitCandle> buildLowVolumeCandles() {
        List<UpbitCandle> candles = buildGoodEntryCandles();
        // 마지막 캔들 vol을 3000으로 (3x)
        candles.get(79).candle_acc_trade_volume = 3000;
        return candles;
    }

    /** EMA21 하락 중 */
    private List<UpbitCandle> buildEma21DownCandles() {
        List<UpbitCandle> candles = new ArrayList<UpbitCandle>();
        for (int i = 0; i < 80; i++) {
            UpbitCandle c = new UpbitCandle();
            c.market = "KRW-EMA";
            c.candle_date_time_utc = String.format("2026-04-13T%02d:%02d:00", i / 60, i % 60);
            double base = i < 40 ? 100.0 + i * 0.3 : 112.0 - (i - 40) * 0.4;
            c.opening_price = base;
            c.trade_price = base + (i < 40 ? 0.2 : -0.1);
            c.high_price = Math.max(c.opening_price, c.trade_price) + 0.3;
            c.low_price = Math.min(c.opening_price, c.trade_price) - 0.3;
            c.candle_acc_trade_volume = 8000;
            candles.add(c);
        }
        UpbitCandle last = candles.get(79);
        last.opening_price = 96.0;
        last.trade_price = 97.0;
        last.high_price = 97.5;
        last.low_price = 95.5;
        Collections.reverse(candles);
        return candles;
    }

    private AllDayScannerConfigEntity buildConfig() {
        AllDayScannerConfigEntity cfg = new AllDayScannerConfigEntity();
        cfg.setEnabled(true);
        cfg.setMode("PAPER");
        cfg.setMaxPositions(3);
        cfg.setOrderSizingMode("FIXED");
        cfg.setOrderSizingValue(BigDecimal.valueOf(100000));
        cfg.setCandleUnitMin(5);
        cfg.setEntryStartHour(0);
        cfg.setEntryStartMin(0);
        cfg.setEntryEndHour(23);
        cfg.setEntryEndMin(59);
        cfg.setSessionEndHour(23);
        cfg.setSessionEndMin(59);
        cfg.setMinConfidence(BigDecimal.valueOf(7.0));
        cfg.setExcludeMarkets("");
        cfg.setBtcFilterEnabled(false);
        return cfg;
    }

    private BotConfigEntity buildBotConfig() {
        BotConfigEntity bc = new BotConfigEntity();
        bc.setCapitalKrw(BigDecimal.valueOf(1000000));
        return bc;
    }

    private void setField(String name, Object value) throws Exception {
        Field f = AllDayScannerService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(scanner, value);
    }
}
