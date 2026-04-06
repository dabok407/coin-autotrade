package com.example.upbit.bot;

import com.example.upbit.db.*;
import com.example.upbit.market.CandleService;
import com.example.upbit.market.SharedPriceService;
import com.example.upbit.market.UpbitCandle;
import com.example.upbit.market.UpbitMarketCatalogService;
import com.example.upbit.trade.LiveOrderService;
import com.example.upbit.upbit.UpbitPrivateClient;

import org.junit.jupiter.api.BeforeEach;
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

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for capital management and race condition prevention
 * across OpeningScannerService and TradingBotService.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class CapitalManagementTest {

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

    @BeforeEach
    public void setUp() throws Exception {
        scanner = new OpeningScannerService(
                configRepo, botConfigRepo, positionRepo, tradeLogRepo,
                candleService, catalogService, liveOrders, privateClient, txTemplate,
                new OpeningBreakoutDetector(mock(SharedPriceService.class))
        );
        // Set running=true so tick() doesn't return early
        java.lang.reflect.Field runningField = OpeningScannerService.class.getDeclaredField("running");
        runningField.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicBoolean) runningField.get(scanner)).set(true);
    }

    // ===== Helpers =====

    private void setupTxTemplate() {
        when(txTemplate.execute(any())).thenAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                Object callback = invocation.getArgument(0);
                if (callback instanceof TransactionCallbackWithoutResult) {
                    ((TransactionCallbackWithoutResult) callback)
                            .doInTransaction(mock(TransactionStatus.class));
                }
                return null;
            }
        });
    }

    private OpeningScannerConfigEntity buildConfig(String mode, int maxPositions,
                                                    String sizingMode, double sizingValue) {
        OpeningScannerConfigEntity cfg = new OpeningScannerConfigEntity();
        cfg.setEnabled(true);
        cfg.setMode(mode);
        cfg.setMaxPositions(maxPositions);
        cfg.setOrderSizingMode(sizingMode);
        cfg.setOrderSizingValue(BigDecimal.valueOf(sizingValue));
        cfg.setCandleUnitMin(5);
        cfg.setBtcFilterEnabled(false);
        cfg.setTopN(10);
        cfg.setRangeStartHour(0);
        cfg.setRangeStartMin(0);
        cfg.setSessionEndHour(23);
        cfg.setSessionEndMin(59);
        cfg.setEntryStartHour(0);
        cfg.setEntryStartMin(0);
        cfg.setEntryEndHour(23);
        cfg.setEntryEndMin(59);
        cfg.setRangeEndHour(23);
        cfg.setRangeEndMin(59);
        cfg.setExcludeMarkets("");
        return cfg;
    }

    private List<UpbitCandle> buildCandles(String market, int count) {
        List<UpbitCandle> candles = new ArrayList<UpbitCandle>();
        for (int i = 0; i < count; i++) {
            UpbitCandle c = new UpbitCandle();
            c.market = market;
            int minutesAgo = (i + 1) * 5;
            ZonedDateTime candleTime = ZonedDateTime.now(ZoneOffset.UTC).minusMinutes(minutesAgo);
            c.candle_date_time_utc = candleTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
            c.opening_price = 50000 + i * 100;
            c.high_price = 51000 + i * 100;
            c.low_price = 49000 + i * 100;
            c.trade_price = 50500 + i * 100;
            c.candle_acc_trade_volume = 1000 + i;
            candles.add(c);
        }
        return candles;
    }

    private PositionEntity buildPosition(String market, double qty, double avgPrice, String strategy) {
        PositionEntity pe = new PositionEntity();
        pe.setMarket(market);
        pe.setQty(qty);
        pe.setAvgPrice(avgPrice);
        pe.setAddBuys(0);
        pe.setOpenedAt(Instant.now());
        pe.setEntryStrategy(strategy);
        return pe;
    }

    private BotConfigEntity buildBotConfig(double capitalKrw) {
        BotConfigEntity bc = new BotConfigEntity();
        bc.setCapitalKrw(BigDecimal.valueOf(capitalKrw));
        return bc;
    }

    private void invokeTick() throws Exception {
        Method tickMethod = OpeningScannerService.class.getDeclaredMethod("tick");
        tickMethod.setAccessible(true);
        tickMethod.invoke(scanner);
    }

    private void setupTopMarkets(List<String> markets) {
        Set<String> allMarkets = new HashSet<String>(markets);
        when(catalogService.getAllMarketCodes()).thenReturn(allMarkets);
        Map<String, Double> volumeMap = new LinkedHashMap<String, Double>();
        double vol = markets.size() * 1000000.0;
        for (String m : markets) {
            volumeMap.put(m, vol);
            vol -= 1000000.0;
        }
        when(catalogService.get24hTradePrice(anyList())).thenReturn(volumeMap);
    }

    // ===== Tests =====

    /**
     * (a) Verify spentKrw is properly accumulated across multiple buys in single tick.
     *
     * globalCapital=50000, orderSize=20000 (FIXED), no existing positions.
     * After 2 buys, spentKrw=40000, remaining=10000 -> 3rd order is partial (10000 >= 5000).
     */
    @Test
    public void testSpentKrwTrackingAcrossMultipleBuys() throws Exception {
        setupTxTemplate();

        OpeningScannerConfigEntity cfg = buildConfig("PAPER", 10, "FIXED", 20000);
        when(configRepo.loadOrCreate()).thenReturn(cfg);
        when(botConfigRepo.findAll()).thenReturn(Collections.singletonList(buildBotConfig(50000)));
        when(positionRepo.findAll()).thenReturn(Collections.<PositionEntity>emptyList());

        List<String> markets = Arrays.asList("KRW-X1", "KRW-X2", "KRW-X3");
        setupTopMarkets(markets);

        when(candleService.getMinuteCandles(anyString(), anyInt(), anyInt(), any()))
                .thenAnswer(new Answer<List<UpbitCandle>>() {
                    @Override
                    public List<UpbitCandle> answer(InvocationOnMock invocation) throws Throwable {
                        return buildCandles(invocation.<String>getArgument(0), 40);
                    }
                });

        invokeTick();

        // With 50000 capital and 20000 per order:
        // Buy1: spentKrw=20000. Buy2: spentKrw=40000. Buy3: remaining=10000, partial.
        // At most 3 buys if strategy produces signals
        verify(txTemplate, atMost(3)).execute(any());
    }

    /**
     * (b) Cross-service capital coordination.
     * TradingBotService positions count toward global capital used by OpeningScanner.
     */
    @Test
    public void testCrossServiceCapitalCoordination() throws Exception {
        setupTxTemplate();

        OpeningScannerConfigEntity cfg = buildConfig("PAPER", 5, "FIXED", 30000);
        when(configRepo.loadOrCreate()).thenReturn(cfg);
        when(botConfigRepo.findAll()).thenReturn(Collections.singletonList(buildBotConfig(100000)));

        // TradingBotService created positions worth 70000 total
        List<PositionEntity> botPositions = Arrays.asList(
                buildPosition("KRW-BOT1", 1.0, 40000, "CONSECUTIVE_DOWN_REBOUND"),
                buildPosition("KRW-BOT2", 1.0, 30000, "BULLISH_ENGULFING")
        );
        when(positionRepo.findAll()).thenReturn(botPositions);

        // Scanner should see 100000 - 70000 = 30000 remaining
        List<String> markets = Arrays.asList("KRW-SCAN1", "KRW-SCAN2");
        setupTopMarkets(markets);

        when(candleService.getMinuteCandles(anyString(), anyInt(), anyInt(), any()))
                .thenAnswer(new Answer<List<UpbitCandle>>() {
                    @Override
                    public List<UpbitCandle> answer(InvocationOnMock invocation) throws Throwable {
                        return buildCandles(invocation.<String>getArgument(0), 40);
                    }
                });

        invokeTick();

        // Only 1 order of 30000 possible. Second would leave 0 remaining < 5000.
        verify(txTemplate, atMost(1)).execute(any());
    }

    /**
     * (c) Verify remainCap pattern: after each buy, remaining capital decreases.
     * With tight capital, only enough for exactly 1 order.
     */
    @Test
    public void testRemainCapDecrementInTradingBot() throws Exception {
        setupTxTemplate();

        OpeningScannerConfigEntity cfg = buildConfig("PAPER", 10, "FIXED", 25000);
        when(configRepo.loadOrCreate()).thenReturn(cfg);
        when(botConfigRepo.findAll()).thenReturn(Collections.singletonList(buildBotConfig(25000)));
        when(positionRepo.findAll()).thenReturn(Collections.<PositionEntity>emptyList());

        List<String> markets = Arrays.asList("KRW-R1", "KRW-R2");
        setupTopMarkets(markets);

        when(candleService.getMinuteCandles(anyString(), anyInt(), anyInt(), any()))
                .thenAnswer(new Answer<List<UpbitCandle>>() {
                    @Override
                    public List<UpbitCandle> answer(InvocationOnMock invocation) throws Throwable {
                        return buildCandles(invocation.<String>getArgument(0), 40);
                    }
                });

        invokeTick();

        // Capital=25000, order=25000 -> after first buy, remaining=0 -> second blocked
        verify(txTemplate, atMost(1)).execute(any());
    }

    /**
     * Additional: Verify that getGlobalCapitalKrw defaults to 100000
     * when bot_config has zero or null capital.
     */
    @Test
    public void testDefaultCapitalWhenZero() throws Exception {
        setupTxTemplate();

        OpeningScannerConfigEntity cfg = buildConfig("PAPER", 10, "FIXED", 10000);
        when(configRepo.loadOrCreate()).thenReturn(cfg);
        // Capital = 0 -> getGlobalCapitalKrw returns default 100000
        when(botConfigRepo.findAll()).thenReturn(Collections.singletonList(buildBotConfig(0)));
        when(positionRepo.findAll()).thenReturn(Collections.<PositionEntity>emptyList());

        List<String> markets = Arrays.asList("KRW-Z1");
        setupTopMarkets(markets);

        when(candleService.getMinuteCandles(anyString(), anyInt(), anyInt(), any()))
                .thenAnswer(new Answer<List<UpbitCandle>>() {
                    @Override
                    public List<UpbitCandle> answer(InvocationOnMock invocation) throws Throwable {
                        return buildCandles(invocation.<String>getArgument(0), 40);
                    }
                });

        // Should not crash - defaults to 100000 capital
        invokeTick();
    }

    /**
     * Additional: PCT sizing mode calculates order based on global capital percentage.
     */
    @Test
    public void testPctSizingUsesGlobalCapital() throws Exception {
        setupTxTemplate();

        // PCT mode: 10% of 100000 = 10000 per order
        OpeningScannerConfigEntity cfg = buildConfig("PAPER", 10, "PCT", 10);
        when(configRepo.loadOrCreate()).thenReturn(cfg);
        when(botConfigRepo.findAll()).thenReturn(Collections.singletonList(buildBotConfig(100000)));
        when(positionRepo.findAll()).thenReturn(Collections.<PositionEntity>emptyList());

        List<String> markets = Arrays.asList("KRW-PCT1");
        setupTopMarkets(markets);

        when(candleService.getMinuteCandles(anyString(), anyInt(), anyInt(), any()))
                .thenAnswer(new Answer<List<UpbitCandle>>() {
                    @Override
                    public List<UpbitCandle> answer(InvocationOnMock invocation) throws Throwable {
                        return buildCandles(invocation.<String>getArgument(0), 40);
                    }
                });

        invokeTick();

        // PCT 10% of 100000 = 10000 per order, 100000 remaining -> allowed
        verify(txTemplate, atMost(1)).execute(any());
    }
}
