package com.example.upbit.bot;

import com.example.upbit.db.*;
import com.example.upbit.market.CandleService;
import com.example.upbit.market.SharedPriceService;
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
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OpeningScanner 서비스 레벨 Split-Exit 매도 실행 테스트.
 *
 * OpeningDetectorSplitExitTest는 detector(감지) 레벨만 검증.
 * 이 테스트는 서비스의 executeSplitFirstSellForTp/executeSellForTp를 직접 호출하여
 * position.save, tradeLog, cooldown 등 실제 매도 실행을 검증.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class OpeningSplitExitServiceTest {

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

    @BeforeEach
    public void setUp() throws Exception {
        breakoutDetector = new OpeningBreakoutDetector(mock(SharedPriceService.class));
        scanner = new OpeningScannerService(
                configRepo, botConfigRepo, positionRepo, tradeLogRepo,
                candleService, catalogService, liveOrders, privateClient, txTemplate,
                breakoutDetector, new SharedTradeThrottle(), null
        );

        Field running = OpeningScannerService.class.getDeclaredField("running");
        running.setAccessible(true);
        ((AtomicBoolean) running.get(scanner)).set(true);

        OpeningScannerConfigEntity cfg = buildConfig();
        when(configRepo.findById(1)).thenReturn(Optional.of(cfg));
        when(configRepo.loadOrCreate()).thenReturn(cfg);

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

    // ═══════════════════════════════════════════════════
    //  S01: 1차 매도 실행 — position.save + SPLIT_1ST note
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("S01: executeSplitFirstSellForTp — position.save(splitPhase=1) + tradeLog SPLIT_1ST")
    void s01_splitFirstSellExecution() throws Exception {
        PositionEntity pe = buildPosition("KRW-A", 1000.0, 100.0);

        invokeSplitFirstSell(pe, 101.6, "SPLIT_1ST +1.6%");

        // position save (not delete)
        verify(positionRepo, never()).deleteById("KRW-A");
        verify(positionRepo).save(argThat(new ArgumentMatcher<PositionEntity>() {
            @Override
            public boolean matches(PositionEntity p) {
                return "KRW-A".equals(p.getMarket())
                        && p.getSplitPhase() == 1
                        && p.getSplitOriginalQty() != null
                        && p.getSplitOriginalQty().doubleValue() == 1000.0
                        && p.getQty().doubleValue() == 400.0; // 1000 * 0.4
            }
        }));

        // tradeLog SPLIT_1ST
        verify(tradeLogRepo).save(argThat(new ArgumentMatcher<TradeEntity>() {
            @Override
            public boolean matches(TradeEntity t) {
                return "SELL".equals(t.getAction()) && "SPLIT_1ST".equals(t.getNote())
                        && t.getQty().doubleValue() == 600.0; // 1000 * 0.6
            }
        }));

        // sellCooldownMap 미등록 확인
        java.util.concurrent.ConcurrentHashMap<String, Long> cooldown = getCooldownMap();
        assertFalse(cooldown.containsKey("KRW-A"), "1차 매도 시 쿨다운 미등록");
    }

    // ═══════════════════════════════════════════════════
    //  S02: 1차 dust — 잔량 < 5000원 → 전량 매도
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("S02: executeSplitFirstSellForTp dust — 잔량<5000원 → SPLIT_1ST_DUST + deleteById")
    void s02_splitFirstDust() throws Exception {
        // qty=40, avgPrice=100 → 잔량=16*101.6=1625원 < 5000
        PositionEntity pe = buildPosition("KRW-DUST", 40.0, 100.0);

        invokeSplitFirstSell(pe, 101.6, "SPLIT_1ST dust");

        // dust → position 삭제
        verify(positionRepo).deleteById("KRW-DUST");

        // tradeLog SPLIT_1ST_DUST
        verify(tradeLogRepo).save(argThat(new ArgumentMatcher<TradeEntity>() {
            @Override
            public boolean matches(TradeEntity t) {
                return "SELL".equals(t.getAction()) && "SPLIT_1ST_DUST".equals(t.getNote());
            }
        }));

        // dust → 쿨다운 등록
        java.util.concurrent.ConcurrentHashMap<String, Long> cooldown = getCooldownMap();
        assertTrue(cooldown.containsKey("KRW-DUST"), "dust 전량매도 시 쿨다운 등록");
    }

    // ═══════════════════════════════════════════════════
    //  S03: 2차 매도 실행 — deleteById + SPLIT_2ND note + 쿨다운 등록
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("S03: executeSellForTp(note=SPLIT_2ND) — deleteById + tradeLog SPLIT_2ND + cooldown")
    void s03_splitSecondSellExecution() throws Exception {
        PositionEntity pe = buildPosition("KRW-B", 400.0, 100.0);
        pe.setSplitPhase(1);
        pe.setSplitOriginalQty(BigDecimal.valueOf(1000.0));

        invokeSellForTp(pe, 102.0, "SPLIT_2ND_TRAIL drop", "SPLIT_2ND");

        // position 삭제
        verify(positionRepo).deleteById("KRW-B");

        // tradeLog SPLIT_2ND
        verify(tradeLogRepo).save(argThat(new ArgumentMatcher<TradeEntity>() {
            @Override
            public boolean matches(TradeEntity t) {
                return "SELL".equals(t.getAction()) && "SPLIT_2ND".equals(t.getNote());
            }
        }));

        // 2차 → 쿨다운 등록
        java.util.concurrent.ConcurrentHashMap<String, Long> cooldown = getCooldownMap();
        assertTrue(cooldown.containsKey("KRW-B"), "2차 매도 시 쿨다운 등록");
    }

    // ═══════════════════════════════════════════════════
    //  S04: splitPhase≠0이면 1차 매도 거부
    // ═══════════════════════════════════════════════════
    @Test
    @DisplayName("S04: splitPhase=1인 포지션에 executeSplitFirstSellForTp → 무시")
    void s04_splitFirstRejectsPhase1() throws Exception {
        PositionEntity pe = buildPosition("KRW-C", 400.0, 100.0);
        pe.setSplitPhase(1); // 이미 1차 완료

        invokeSplitFirstSell(pe, 101.6, "should be ignored");

        verify(positionRepo, never()).save(any());
        verify(positionRepo, never()).deleteById(anyString());
        verify(tradeLogRepo, never()).save(any());
    }

    // ═══════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════

    private void invokeSplitFirstSell(PositionEntity pe, double price, String reason) throws Exception {
        Method m = OpeningScannerService.class.getDeclaredMethod(
                "executeSplitFirstSellForTp", PositionEntity.class, double.class, String.class);
        m.setAccessible(true);
        m.invoke(scanner, pe, price, reason);
    }

    private void invokeSellForTp(PositionEntity pe, double price, String reason, String note) throws Exception {
        Method m = OpeningScannerService.class.getDeclaredMethod(
                "executeSellForTp", PositionEntity.class, double.class, String.class, String.class);
        m.setAccessible(true);
        m.invoke(scanner, pe, price, reason, note);
    }

    @SuppressWarnings("unchecked")
    private java.util.concurrent.ConcurrentHashMap<String, Long> getCooldownMap() throws Exception {
        Field f = OpeningScannerService.class.getDeclaredField("sellCooldownMap");
        f.setAccessible(true);
        return (java.util.concurrent.ConcurrentHashMap<String, Long>) f.get(scanner);
    }

    private PositionEntity buildPosition(String market, double qty, double avgPrice) {
        PositionEntity pe = new PositionEntity();
        pe.setMarket(market);
        pe.setQty(BigDecimal.valueOf(qty));
        pe.setAvgPrice(BigDecimal.valueOf(avgPrice));
        pe.setAddBuys(0);
        pe.setOpenedAt(Instant.now());
        pe.setEntryStrategy("SCALP_OPENING_BREAK");
        pe.setSplitPhase(0);
        return pe;
    }

    private OpeningScannerConfigEntity buildConfig() {
        OpeningScannerConfigEntity cfg = new OpeningScannerConfigEntity();
        cfg.setEnabled(true);
        cfg.setMode("PAPER");
        cfg.setSplitExitEnabled(true);
        cfg.setSplitTpPct(BigDecimal.valueOf(1.5));
        cfg.setSplitRatio(BigDecimal.valueOf(0.60));
        cfg.setTrailDropAfterSplit(BigDecimal.valueOf(1.0));
        return cfg;
    }
}
