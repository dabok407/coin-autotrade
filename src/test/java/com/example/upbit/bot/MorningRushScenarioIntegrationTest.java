package com.example.upbit.bot;

import com.example.upbit.db.*;
import com.example.upbit.market.SharedPriceService;
import com.example.upbit.market.TickerService;
import com.example.upbit.market.UpbitMarketCatalogService;
import com.example.upbit.trade.LiveOrderService;
import com.example.upbit.upbit.UpbitPrivateClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 모닝러쉬 시나리오 통합 테스트.
 *
 * 매수 → 시간 경과 → 가격 변동 → 매도 트리거의 전체 사이클을 시뮬레이션.
 * 각 시나리오는 가격 시퀀스 + 시간 경과를 직접 조작하여 SL 종합안의 모든 분기 검증.
 *
 * positionCache 포맷: [avgPrice, qty, openedAtEpochMs, _, _]
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class MorningRushScenarioIntegrationTest {

    @Mock private MorningRushConfigRepository configRepo;
    @Mock private BotConfigRepository botConfigRepo;
    @Mock private PositionRepository positionRepo;
    @Mock private TradeRepository tradeLogRepo;
    @Mock private LiveOrderService liveOrders;
    @Mock private UpbitPrivateClient privateClient;
    @Mock private TransactionTemplate txTemplate;
    @Mock private UpbitMarketCatalogService catalogService;
    @Mock private TickerService tickerService;
    @Mock private SharedPriceService sharedPriceService;

    private MorningRushScannerService scanner;

    /** 가격 시퀀스 + 매수 후 경과시간(초)을 정의하는 시나리오. */
    static class PriceTick {
        final long elapsedSec;  // 매수 후 경과 시간 (초)
        final double price;
        PriceTick(long elapsedSec, double price) {
            this.elapsedSec = elapsedSec;
            this.price = price;
        }
    }

    @BeforeEach
    public void setUp() throws Exception {
        scanner = new MorningRushScannerService(
                configRepo, botConfigRepo, positionRepo, tradeLogRepo,
                liveOrders, privateClient, txTemplate, catalogService, tickerService,
                sharedPriceService, new SharedTradeThrottle()
        );
        setField("running", new AtomicBoolean(true));

        // SL 종합안 캐시 (DB 설정값 시뮬레이션)
        // 90일 백테스트 검증값: grace 60s, wide 30분, wide_sl 6%, tight_sl 3%, tp 2.3%
        setField("cachedTpPct", 2.3);
        setField("cachedSlPct", 3.0);
        setField("cachedTpTrailDropPct", 1.0);  // V110: 기존 테스트 기준값
        setField("cachedGracePeriodMs", 60_000L);
        setField("cachedWidePeriodMs", 30 * 60_000L);
        setField("cachedWideSlPct", 6.0);
    }

    /**
     * 시나리오 실행기: 매수 + 가격 시퀀스 시뮬레이션 → 매도 결과 반환.
     */
    private boolean runScenario(double avgPrice, long openedSecAgo, PriceTick[] ticks) throws Exception {
        long now = System.currentTimeMillis();
        long openedAt = now - openedSecAgo * 1000L;
        getPositionCache().put("KRW-TEST", new double[]{avgPrice, 1.0, openedAt, avgPrice, 0});

        for (PriceTick tick : ticks) {
            // 시간 조작: openedAt을 elapsedSec 만큼 과거로 옮김
            long virtualNow = now;
            long virtualOpened = virtualNow - tick.elapsedSec * 1000L;
            double[] pos = getPositionCache().get("KRW-TEST");
            if (pos == null) return true; // 이미 매도됨
            pos[2] = virtualOpened;

            invokeCheckRealtimeTpSl("KRW-TEST", tick.price);
            if (!getPositionCache().containsKey("KRW-TEST")) {
                return true; // 매도 발생
            }
        }
        return false; // 매도 없음
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 1: 정상 TP 익절
    // ═══════════════════════════════════════════════════════════
    // 2026-04-09 변경: TP 즉시 매도 → TP trail 매도
    @Test
    @DisplayName("시나리오 1: 매수 직후 +2.5% → trail 활성 → peak drop → TP trail 매도")
    public void scenario1_normalTp() throws Exception {
        PriceTick[] ticks = {
            new PriceTick(10, 100.5),  // 10초 후 +0.5%
            new PriceTick(30, 102.5),  // 30초 후 +2.5% → trail 활성화 (peak=102.5)
            new PriceTick(40, 101.3),  // 40초 후 peak에서 -1.17% drop → TP_TRAIL 매도
        };
        boolean sold = runScenario(100.0, 0, ticks);
        assertTrue(sold, "TP trail 활성 후 peak drop 시 매도되어야 함");
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 2: 그레이스 보호 (1분 안에 -10% 흔들기 후 회복)
    // ═══════════════════════════════════════════════════════════
    // 2026-04-09 변경: TP 즉시 매도 → TP trail 매도 (peak 추적 후 drop)
    @Test
    @DisplayName("시나리오 2: 매수 후 30초 -10% 급락 → 그레이스 보호 → 회복 → TP trail 매도")
    public void scenario2_graceProtection() throws Exception {
        PriceTick[] ticks = {
            new PriceTick(10, 95.0),    // 10초 후 -5% (그레이스 보호)
            new PriceTick(30, 90.0),    // 30초 후 -10% (그레이스 보호 - SL 무시)
            new PriceTick(50, 95.0),    // 50초 후 -5% (그레이스 보호)
            new PriceTick(120, 102.5),  // 2분 후 +2.5% → trail 활성화 (peak=102.5)
            new PriceTick(130, 101.3),  // peak에서 -1.17% drop → TP_TRAIL 매도
        };
        boolean sold = runScenario(100.0, 0, ticks);
        assertTrue(sold, "그레이스 동안 SL 무시되고 회복 후 TP trail 매도");
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 3: SL_WIDE 발동 (1~30분, -6% 초과)
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 3: 매수 후 5분 -7% → SL_WIDE 발동")
    public void scenario3_slWide() throws Exception {
        PriceTick[] ticks = {
            new PriceTick(120, 96.0),   // 2분 후 -4% (SL_WIDE 6% 미달, 유지)
            new PriceTick(300, 95.0),   // 5분 후 -5% (미달, 유지)
            new PriceTick(310, 93.0),   // 5분 10초 후 -7% → SL_WIDE 발동
        };
        boolean sold = runScenario(100.0, 0, ticks);
        assertTrue(sold, "-7%는 SL_WIDE -6% 초과로 발동");
        assertFalse(getPositionCache().containsKey("KRW-TEST"));
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 4: SL_WIDE 미달 → 유지
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 4: 매수 후 -5% → SL_WIDE 6% 미달이라 유지")
    public void scenario4_slWideMissing() throws Exception {
        PriceTick[] ticks = {
            new PriceTick(120, 95.0),   // 2분 후 -5% (6% 미달)
            new PriceTick(600, 95.5),   // 10분 후 -4.5% (미달)
            new PriceTick(1200, 96.0),  // 20분 후 -4% (미달)
        };
        boolean sold = runScenario(100.0, 0, ticks);
        assertFalse(sold, "-5%는 SL_WIDE 6% 미달이라 유지");
        assertTrue(getPositionCache().containsKey("KRW-TEST"));
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 5: SL_TIGHT 발동 (30분 이후, -3% 초과)
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 5: 매수 후 31분 -4% → SL_TIGHT 발동")
    public void scenario5_slTight() throws Exception {
        PriceTick[] ticks = {
            new PriceTick(60, 99.0),     // 1분 후 -1%
            new PriceTick(1800, 97.5),   // 30분 후 -2.5% (SL_TIGHT 3% 미달, 유지)
            new PriceTick(1860, 96.0),   // 31분 후 -4% → SL_TIGHT 발동
        };
        boolean sold = runScenario(100.0, 0, ticks);
        assertTrue(sold, "30분 이후 -4%는 SL_TIGHT 3% 발동");
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 6: 흔들기 후 회복 (5분 -5% → 회복 → TP)
    // ═══════════════════════════════════════════════════════════
    // 2026-04-09 변경: TP 즉시 매도 → TP trail 매도
    @Test
    @DisplayName("시나리오 6: 매수 후 5분 -5% (SL_WIDE 미달) → 회복 → TP trail 매도")
    public void scenario6_shakeRecovery() throws Exception {
        PriceTick[] ticks = {
            new PriceTick(120, 95.0),    // 2분 후 -5% (보호)
            new PriceTick(300, 95.5),    // 5분 후 -4.5% (보호)
            new PriceTick(600, 99.0),    // 10분 후 -1% (회복 중)
            new PriceTick(900, 102.5),   // 15분 후 +2.5% → trail 활성화
            new PriceTick(910, 101.3),   // peak에서 -1.17% drop → TP_TRAIL 매도
        };
        boolean sold = runScenario(100.0, 0, ticks);
        assertTrue(sold, "흔들기 후 회복하여 TP trail 매도");
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 7: TP는 그레이스 무관하게 작동
    // ═══════════════════════════════════════════════════════════
    // 2026-04-09 변경: TP 즉시 매도 → TP trail 매도
    @Test
    @DisplayName("시나리오 7: 그레이스 안에도 TP trail 작동")
    public void scenario7_tpDuringGrace() throws Exception {
        PriceTick[] ticks = {
            new PriceTick(20, 102.5),   // 20초 후 +2.5% → trail 활성화 (그레이스 안)
            new PriceTick(30, 101.3),   // peak에서 -1.17% drop → TP_TRAIL 매도 (그레이스 안)
        };
        boolean sold = runScenario(100.0, 0, ticks);
        assertTrue(sold, "그레이스 안에도 TP trail 작동");
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 8: TP 미달 + SL 미달 → 유지
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 8: 횡보 (-2% ~ +1%) → 어떤 매도도 없음")
    public void scenario8_sideways() throws Exception {
        PriceTick[] ticks = {
            new PriceTick(120, 99.0),    // 2분 후 -1%
            new PriceTick(600, 98.0),    // 10분 후 -2%
            new PriceTick(1200, 100.5),  // 20분 후 +0.5%
            new PriceTick(1800, 101.5),  // 30분 후 +1.5% (TP 미달)
        };
        boolean sold = runScenario(100.0, 0, ticks);
        assertFalse(sold, "횡보 구간은 어떤 매도도 없음");
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 9: 1분 그레이스 직후 -7% 급락 → SL_WIDE 발동
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 9: 그레이스(60s) 직후 -7% → SL_WIDE 발동")
    public void scenario9_postGraceCrash() throws Exception {
        PriceTick[] ticks = {
            new PriceTick(30, 98.0),    // 그레이스 안 -2% (보호)
            new PriceTick(70, 93.0),    // 그레이스 직후 -7% → SL_WIDE 발동
        };
        boolean sold = runScenario(100.0, 0, ticks);
        assertTrue(sold, "그레이스 직후 SL_WIDE 발동");
    }

    // ═══════════════════════════════════════════════════════════
    //  시나리오 10: SL_WIDE → SL_TIGHT 전환 시점
    // ═══════════════════════════════════════════════════════════
    @Test
    @DisplayName("시나리오 10: 30분 경계 — 29분 -5% 유지, 31분 -3.5% SL_TIGHT 발동")
    public void scenario10_widePeriodBoundary() throws Exception {
        PriceTick[] ticks = {
            new PriceTick(1740, 95.0),   // 29분 후 -5% (SL_WIDE 6% 미달, 유지)
        };
        boolean sold = runScenario(100.0, 0, ticks);
        assertFalse(sold, "29분 -5%는 SL_WIDE 6% 미달");

        // 31분 후 -3.5% → SL_TIGHT 발동
        // (별도 시나리오로)
        getPositionCache().clear();
        long now = System.currentTimeMillis();
        getPositionCache().put("KRW-TEST", new double[]{100.0, 1.0, now - 1860_000L, 100.0, 0});
        invokeCheckRealtimeTpSl("KRW-TEST", 96.5);
        assertFalse(getPositionCache().containsKey("KRW-TEST"),
                "31분 -3.5%는 SL_TIGHT 3% 발동");
    }

    // ═══════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════

    private void setField(String name, Object value) throws Exception {
        Field f = MorningRushScannerService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(scanner, value);
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, double[]> getPositionCache() throws Exception {
        Field f = MorningRushScannerService.class.getDeclaredField("positionCache");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, double[]>) f.get(scanner);
    }

    private void invokeCheckRealtimeTpSl(String market, double price) throws Exception {
        Method m = MorningRushScannerService.class.getDeclaredMethod(
                "checkRealtimeTpSl", String.class, double.class);
        m.setAccessible(true);
        m.invoke(scanner, market, price);
    }
}
