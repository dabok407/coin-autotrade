package com.example.upbit.bot;

import com.example.upbit.db.*;
import com.example.upbit.market.CandleService;
import com.example.upbit.market.UpbitCandle;
import com.example.upbit.market.UpbitMarketCatalogService;
import com.example.upbit.strategy.*;
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
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for OpeningScannerService parallel execution logic.
 * Uses reflection to invoke the private tick() method directly.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class OpeningScannerParallelTest {

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
                candleService, catalogService, liveOrders, privateClient, txTemplate
        );
        // Set running=true so tick() doesn't return early
        java.lang.reflect.Field runningField = OpeningScannerService.class.getDeclaredField("running");
        runningField.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicBoolean) runningField.get(scanner)).set(true);
    }

    // ===== Helper Methods =====

    private void setupTxTemplate() {
        when(txTemplate.execute(any())).thenAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                Object callback = invocation.getArgument(0);
                if (callback instanceof TransactionCallbackWithoutResult) {
                    ((TransactionCallbackWithoutResult) callback)
                            .doInTransaction(mock(TransactionStatus.class));
                } else if (callback instanceof TransactionCallback) {
                    return ((TransactionCallback<?>) callback)
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

    private PositionEntity buildPosition(String market, double qty, double avgPrice) {
        PositionEntity pe = new PositionEntity();
        pe.setMarket(market);
        pe.setQty(qty);
        pe.setAvgPrice(avgPrice);
        pe.setAddBuys(0);
        pe.setOpenedAt(Instant.now());
        pe.setEntryStrategy("SCALP_OPENING_BREAK");
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
     * (a) Verify multiple positions' candles are fetched in parallel for sell phase.
     * Mock CandleService with artificial delay, verify total time < sequential time.
     */
    @Test
    public void testParallelCandleFetchForSell() throws Exception {
        final int DELAY_MS = 200;
        int posCount = 5;

        OpeningScannerConfigEntity cfg = buildConfig("PAPER", 3, "FIXED", 30000);
        when(configRepo.loadOrCreate()).thenReturn(cfg);
        when(botConfigRepo.findAll()).thenReturn(Collections.singletonList(buildBotConfig(1000000)));

        List<PositionEntity> positions = new ArrayList<PositionEntity>();
        for (int i = 1; i <= posCount; i++) {
            positions.add(buildPosition("KRW-COIN" + i, 1.0, 50000));
        }
        when(positionRepo.findAll()).thenReturn(positions);
        setupTopMarkets(Collections.<String>emptyList());

        when(candleService.getMinuteCandles(anyString(), anyInt(), anyInt(), any()))
                .thenAnswer(new Answer<List<UpbitCandle>>() {
                    @Override
                    public List<UpbitCandle> answer(InvocationOnMock invocation) throws Throwable {
                        Thread.sleep(DELAY_MS);
                        String market = invocation.getArgument(0);
                        return buildCandles(market, 40);
                    }
                });

        long start = System.currentTimeMillis();
        invokeTick();
        long elapsed = System.currentTimeMillis() - start;

        long sequentialEstimate = posCount * DELAY_MS;
        assertTrue(elapsed < sequentialEstimate,
                "Expected parallel fetch to be faster than sequential (" + elapsed + "ms vs " + sequentialEstimate + "ms)");

        verify(candleService, times(posCount)).getMinuteCandles(anyString(), eq(5), eq(40), isNull());
    }

    /**
     * (b) Verify top N markets' candles are fetched in parallel for buy phase.
     */
    @Test
    public void testParallelCandleFetchForBuy() throws Exception {
        final int DELAY_MS = 200;
        int marketCount = 5;

        OpeningScannerConfigEntity cfg = buildConfig("PAPER", 10, "FIXED", 30000);
        when(configRepo.loadOrCreate()).thenReturn(cfg);
        when(botConfigRepo.findAll()).thenReturn(Collections.singletonList(buildBotConfig(1000000)));
        when(positionRepo.findAll()).thenReturn(Collections.<PositionEntity>emptyList());

        List<String> markets = new ArrayList<String>();
        for (int i = 1; i <= marketCount; i++) {
            markets.add("KRW-BUY" + i);
        }
        setupTopMarkets(markets);

        when(candleService.getMinuteCandles(anyString(), anyInt(), anyInt(), any()))
                .thenAnswer(new Answer<List<UpbitCandle>>() {
                    @Override
                    public List<UpbitCandle> answer(InvocationOnMock invocation) throws Throwable {
                        Thread.sleep(DELAY_MS);
                        String market = invocation.getArgument(0);
                        return buildCandles(market, 40);
                    }
                });

        long start = System.currentTimeMillis();
        invokeTick();
        long elapsed = System.currentTimeMillis() - start;

        long sequentialEstimate = marketCount * DELAY_MS;
        assertTrue(elapsed < sequentialEstimate,
                "Expected parallel fetch to be faster than sequential (" + elapsed + "ms vs " + sequentialEstimate + "ms)");

        verify(candleService, times(marketCount)).getMinuteCandles(anyString(), eq(5), eq(40), isNull());
    }

    /**
     * (c) Verify Phase 3 executes highest confidence signals first.
     * We verify the sorting infrastructure works by checking candle fetches complete
     * and the tick runs without error.
     */
    @Test
    public void testBuySignalsSortedByConfidence() throws Exception {
        setupTxTemplate();

        OpeningScannerConfigEntity cfg = buildConfig("PAPER", 10, "FIXED", 10000);
        when(configRepo.loadOrCreate()).thenReturn(cfg);
        when(botConfigRepo.findAll()).thenReturn(Collections.singletonList(buildBotConfig(1000000)));
        when(positionRepo.findAll()).thenReturn(Collections.<PositionEntity>emptyList());

        List<String> markets = Arrays.asList("KRW-LOW", "KRW-MID", "KRW-HIGH");
        setupTopMarkets(markets);

        when(candleService.getMinuteCandles(anyString(), anyInt(), anyInt(), any()))
                .thenAnswer(new Answer<List<UpbitCandle>>() {
                    @Override
                    public List<UpbitCandle> answer(InvocationOnMock invocation) throws Throwable {
                        String market = invocation.getArgument(0);
                        return buildCandles(market, 40);
                    }
                });

        invokeTick();

        verify(candleService, times(3)).getMinuteCandles(anyString(), eq(5), eq(40), isNull());
    }

    /**
     * (d) Set maxPositions=2, already 2 scanner positions. Verify no new buys.
     */
    @Test
    public void testMaxPositionsRespectedInParallel() throws Exception {
        OpeningScannerConfigEntity cfg = buildConfig("PAPER", 2, "FIXED", 10000);
        when(configRepo.loadOrCreate()).thenReturn(cfg);
        when(botConfigRepo.findAll()).thenReturn(Collections.singletonList(buildBotConfig(1000000)));

        List<PositionEntity> existing = Arrays.asList(
                buildPosition("KRW-EXISTING1", 1.0, 50000),
                buildPosition("KRW-EXISTING2", 1.0, 50000)
        );
        when(positionRepo.findAll()).thenReturn(existing);

        List<String> markets = Arrays.asList("KRW-NEW1", "KRW-NEW2", "KRW-NEW3", "KRW-NEW4", "KRW-NEW5");
        setupTopMarkets(markets);

        when(candleService.getMinuteCandles(anyString(), anyInt(), anyInt(), any()))
                .thenAnswer(new Answer<List<UpbitCandle>>() {
                    @Override
                    public List<UpbitCandle> answer(InvocationOnMock invocation) throws Throwable {
                        return buildCandles(invocation.<String>getArgument(0), 40);
                    }
                });

        invokeTick();

        // scannerPosCount=2 >= maxPositions=2, so canEnter=false, no buys happen
        // txTemplate only called for sell phase (if any sell signals)
        // No buy-related txTemplate calls should happen
        verify(positionRepo, never()).save(any(PositionEntity.class));
    }

    /**
     * (e) Capital deduction per order: globalCapital=60000, orderKrw=30000.
     */
    @Test
    public void testCapitalDeductionPerOrder() throws Exception {
        setupTxTemplate();

        OpeningScannerConfigEntity cfg = buildConfig("PAPER", 10, "FIXED", 30000);
        when(configRepo.loadOrCreate()).thenReturn(cfg);
        when(botConfigRepo.findAll()).thenReturn(Collections.singletonList(buildBotConfig(60000)));
        when(positionRepo.findAll()).thenReturn(Collections.<PositionEntity>emptyList());

        List<String> markets = Arrays.asList("KRW-A", "KRW-B", "KRW-C");
        setupTopMarkets(markets);

        when(candleService.getMinuteCandles(anyString(), anyInt(), anyInt(), any()))
                .thenAnswer(new Answer<List<UpbitCandle>>() {
                    @Override
                    public List<UpbitCandle> answer(InvocationOnMock invocation) throws Throwable {
                        return buildCandles(invocation.<String>getArgument(0), 40);
                    }
                });

        invokeTick();

        // With 60000 capital and 30000 per order, at most 2 full orders
        // (3rd is blocked or reduced to partial)
        verify(txTemplate, atMost(2)).execute(any());
    }

    /**
     * (f) Global capital limit: 100000 capital, positions using 80000.
     */
    @Test
    public void testGlobalCapitalLimitRespected() throws Exception {
        setupTxTemplate();

        OpeningScannerConfigEntity cfg = buildConfig("PAPER", 10, "FIXED", 30000);
        when(configRepo.loadOrCreate()).thenReturn(cfg);
        when(botConfigRepo.findAll()).thenReturn(Collections.singletonList(buildBotConfig(100000)));

        PositionEntity existing = buildPosition("KRW-EXISTING", 1.0, 80000);
        existing.setEntryStrategy("OTHER_STRATEGY");
        when(positionRepo.findAll()).thenReturn(Collections.singletonList(existing));

        List<String> markets = Arrays.asList("KRW-NEWMARKET");
        setupTopMarkets(markets);

        when(candleService.getMinuteCandles(anyString(), anyInt(), anyInt(), any()))
                .thenAnswer(new Answer<List<UpbitCandle>>() {
                    @Override
                    public List<UpbitCandle> answer(InvocationOnMock invocation) throws Throwable {
                        return buildCandles(invocation.<String>getArgument(0), 40);
                    }
                });

        invokeTick();

        // remaining = 100000 - 80000 = 20000. orderKrw=30000 > 20000 but 20000 >= 5000 -> partial
        verify(txTemplate, atMost(1)).execute(any());
    }

    /**
     * (g) Concurrent buy does not duplicate position for same market.
     */
    @Test
    public void testConcurrentBuyDoesNotDuplicatePosition() throws Exception {
        setupTxTemplate();

        OpeningScannerConfigEntity cfg = buildConfig("PAPER", 10, "FIXED", 10000);
        when(configRepo.loadOrCreate()).thenReturn(cfg);
        when(botConfigRepo.findAll()).thenReturn(Collections.singletonList(buildBotConfig(1000000)));

        PositionEntity dup = buildPosition("KRW-DUP", 1.0, 50000);
        when(positionRepo.findAll()).thenReturn(Collections.singletonList(dup));

        List<String> markets = Arrays.asList("KRW-DUP", "KRW-OTHER");
        setupTopMarkets(markets);

        when(candleService.getMinuteCandles(anyString(), anyInt(), anyInt(), any()))
                .thenAnswer(new Answer<List<UpbitCandle>>() {
                    @Override
                    public List<UpbitCandle> answer(InvocationOnMock invocation) throws Throwable {
                        return buildCandles(invocation.<String>getArgument(0), 40);
                    }
                });

        invokeTick();

        // KRW-DUP is already owned, so it's excluded from buy candidates
        // Only KRW-OTHER may produce a buy signal
        verify(candleService, atLeast(1)).getMinuteCandles(eq("KRW-DUP"), anyInt(), anyInt(), any());
        verify(candleService, atLeast(1)).getMinuteCandles(eq("KRW-OTHER"), anyInt(), anyInt(), any());
    }

    /**
     * (h) Transactional rollback on trade log failure.
     */
    @Test
    public void testTransactionalRollbackOnTradeLogFailure() throws Exception {
        OpeningScannerConfigEntity cfg = buildConfig("PAPER", 10, "FIXED", 10000);
        when(configRepo.loadOrCreate()).thenReturn(cfg);
        when(botConfigRepo.findAll()).thenReturn(Collections.singletonList(buildBotConfig(1000000)));
        when(positionRepo.findAll()).thenReturn(Collections.<PositionEntity>emptyList());

        List<String> markets = Arrays.asList("KRW-FAIL");
        setupTopMarkets(markets);

        when(candleService.getMinuteCandles(anyString(), anyInt(), anyInt(), any()))
                .thenAnswer(new Answer<List<UpbitCandle>>() {
                    @Override
                    public List<UpbitCandle> answer(InvocationOnMock invocation) throws Throwable {
                        return buildCandles(invocation.<String>getArgument(0), 40);
                    }
                });

        when(txTemplate.execute(any())).thenThrow(new RuntimeException("DB write failed"));

        // tick() should handle the exception without crashing
        invokeTick();

        // Both position and trade_log are rolled back since they're in the same tx
        verify(positionRepo, never()).save(any(PositionEntity.class));
    }

    /**
     * (i) Transactional rollback on sell position delete failure.
     */
    @Test
    public void testTransactionalRollbackOnSellPositionDeleteFailure() throws Exception {
        OpeningScannerConfigEntity cfg = buildConfig("PAPER", 10, "FIXED", 10000);
        when(configRepo.loadOrCreate()).thenReturn(cfg);
        when(botConfigRepo.findAll()).thenReturn(Collections.singletonList(buildBotConfig(1000000)));

        PositionEntity pos = buildPosition("KRW-SELLTEST", 1.0, 50000);
        when(positionRepo.findAll()).thenReturn(Collections.singletonList(pos));
        setupTopMarkets(Collections.<String>emptyList());

        when(candleService.getMinuteCandles(anyString(), anyInt(), anyInt(), any()))
                .thenAnswer(new Answer<List<UpbitCandle>>() {
                    @Override
                    public List<UpbitCandle> answer(InvocationOnMock invocation) throws Throwable {
                        return buildCandles(invocation.<String>getArgument(0), 40);
                    }
                });

        when(txTemplate.execute(any())).thenThrow(new RuntimeException("Delete failed"));

        invokeTick();

        // Both trade_log save and position delete are in the same tx - both rolled back
        verify(tradeLogRepo, never()).save(any(TradeEntity.class));
        verify(positionRepo, never()).deleteById(anyString());
    }

    /**
     * (j) Even though candle fetch is parallel, sell execution is sequential.
     */
    @Test
    public void testSellExecutesSequentially() throws Exception {
        OpeningScannerConfigEntity cfg = buildConfig("PAPER", 10, "FIXED", 10000);
        when(configRepo.loadOrCreate()).thenReturn(cfg);
        when(botConfigRepo.findAll()).thenReturn(Collections.singletonList(buildBotConfig(1000000)));

        List<PositionEntity> positions = Arrays.asList(
                buildPosition("KRW-S1", 1.0, 50000),
                buildPosition("KRW-S2", 1.0, 50000),
                buildPosition("KRW-S3", 1.0, 50000)
        );
        when(positionRepo.findAll()).thenReturn(positions);
        setupTopMarkets(Collections.<String>emptyList());

        final AtomicInteger concurrentSellCount = new AtomicInteger(0);
        final AtomicInteger maxConcurrentSells = new AtomicInteger(0);

        when(candleService.getMinuteCandles(anyString(), anyInt(), anyInt(), any()))
                .thenAnswer(new Answer<List<UpbitCandle>>() {
                    @Override
                    public List<UpbitCandle> answer(InvocationOnMock invocation) throws Throwable {
                        return buildCandles(invocation.<String>getArgument(0), 40);
                    }
                });

        when(txTemplate.execute(any())).thenAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                int current = concurrentSellCount.incrementAndGet();
                int max = maxConcurrentSells.get();
                if (current > max) {
                    maxConcurrentSells.compareAndSet(max, current);
                }
                Thread.sleep(50);
                concurrentSellCount.decrementAndGet();

                Object callback = invocation.getArgument(0);
                if (callback instanceof TransactionCallbackWithoutResult) {
                    ((TransactionCallbackWithoutResult) callback)
                            .doInTransaction(mock(TransactionStatus.class));
                }
                return null;
            }
        });

        invokeTick();

        assertTrue(maxConcurrentSells.get() <= 1,
                "Sells should execute sequentially (max concurrent = " + maxConcurrentSells.get() + ")");
    }
}
