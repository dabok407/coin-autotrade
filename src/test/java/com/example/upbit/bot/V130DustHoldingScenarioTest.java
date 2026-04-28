package com.example.upbit.bot;

import com.example.upbit.db.*;
import com.example.upbit.market.SharedPriceService;
import com.example.upbit.market.TickerService;
import com.example.upbit.market.UpbitMarketCatalogService;
import com.example.upbit.market.CandleService;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V130 ⑤: Dust 보유 판단 제외 시나리오 테스트.
 *
 * dust_threshold=5000이면:
 *   - qty*avgPrice < 5000 → dust → 보유 아님 → 새 진입 허용
 *   - qty*avgPrice >= 5000 → non-dust → 보유 → 새 진입 차단 (ALREADY_HELD)
 * threshold=0이면 (비활성):
 *   - qty>0이면 무조건 보유 (V129 동작)
 *
 * 시나리오:
 *   D01: dust_threshold=5000, qty=10, avgPrice=100 → value=1000 < 5000 → dust → 새 MR 진입 허용
 *   D02: dust_threshold=5000, qty=100, avgPrice=100 → value=10000 >= 5000 → non-dust → 새 진입 차단
 *   D03: threshold=0 (비활성) → qty>0 무조건 보유 → 차단 (V129 동작)
 *   D04: qty=0 → dust → 항상 허용 (threshold 무관)
 *   D05: dust 포지션이 MR 보유 → AllDay도 진입 허용 (cross-scanner + dust)
 *   D06: non-dust 포지션이 MR 보유 → AllDay 차단 (cross-scanner + non-dust)
 *   D07: 경계값 qty=50, avgPrice=100 → value=5000 (경계: < 5000은 아님) → non-dust → 차단
 *
 * isDustPosition은 ScannerLockService.isDustPosition(pe, cfg)에서 판별.
 * 스캐너 내부에서도 positionRepo.findAll()을 통한 자체 보유 체크에 사용됨.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("V130 Dust Holding Scenario Tests")
public class V130DustHoldingScenarioTest {

    @Mock private BotConfigRepository botConfigRepo;
    @Mock private PositionRepository positionRepo;
    @Mock private TradeRepository tradeRepo;

    private ScannerLockService lockService;

    @BeforeEach
    public void setUp() {
        lockService = new ScannerLockService(botConfigRepo, positionRepo, tradeRepo);
    }

    // ─────────────────────────────────────────────────────────────────
    //  Helper builders
    // ─────────────────────────────────────────────────────────────────

    private BotConfigEntity buildCfg(boolean lockEnabled, int dustThreshold) {
        BotConfigEntity cfg = new BotConfigEntity();
        cfg.setCrossScannerLockEnabled(lockEnabled);
        cfg.setSameMarketLossCooldownMin(0);
        cfg.setDustHoldingThresholdKrw(dustThreshold);
        return cfg;
    }

    private PositionEntity buildPosition(String market, double qty, double avgPrice) {
        PositionEntity pe = new PositionEntity();
        pe.setMarket(market);
        pe.setQty(BigDecimal.valueOf(qty));
        pe.setAvgPrice(BigDecimal.valueOf(avgPrice));
        pe.setEntryStrategy("TEST");
        pe.setOpenedAt(Instant.now());
        return pe;
    }

    // ─────────────────────────────────────────────────────────────────
    //  D01: dust → 새 진입 허용
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("D01: threshold=5000, qty=10, avg=100 → value=1000(dust) → MR 진입 허용")
    public void d01_dustPosition_newEntryAllowed() {
        BotConfigEntity cfg = buildCfg(true, 5000);
        when(botConfigRepo.findById(1L)).thenReturn(Optional.of(cfg));

        // value=10*100=1000 < 5000 → dust
        PositionEntity pe = buildPosition("KRW-DOGE", 10.0, 100.0);
        when(positionRepo.findById("KRW-DOGE")).thenReturn(Optional.of(pe));
        when(tradeRepo.existsRecentLoss(eq("KRW-DOGE"), anyLong())).thenReturn(false);

        assertTrue(lockService.canEnter("KRW-DOGE", "MR"),
                "dust 포지션(1000원) → 새 MR 진입 허용");
    }

    @Test
    @DisplayName("D01b: threshold=5000, qty=49, avg=100 → value=4900(dust) → OP 진입 허용")
    public void d01b_dustPositionOP_newEntryAllowed() {
        BotConfigEntity cfg = buildCfg(true, 5000);
        when(botConfigRepo.findById(1L)).thenReturn(Optional.of(cfg));

        PositionEntity pe = buildPosition("KRW-XLM", 49.0, 100.0);
        when(positionRepo.findById("KRW-XLM")).thenReturn(Optional.of(pe));
        when(tradeRepo.existsRecentLoss(eq("KRW-XLM"), anyLong())).thenReturn(false);

        assertTrue(lockService.canEnter("KRW-XLM", "OP"),
                "dust 포지션(4900원) → OP 진입 허용");
    }

    // ─────────────────────────────────────────────────────────────────
    //  D02: non-dust → 새 진입 차단
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("D02: threshold=5000, qty=100, avg=100 → value=10000(non-dust) → 새 진입 차단")
    public void d02_nonDustPosition_newEntryBlocked() {
        BotConfigEntity cfg = buildCfg(true, 5000);
        when(botConfigRepo.findById(1L)).thenReturn(Optional.of(cfg));

        // value=100*100=10000 >= 5000 → non-dust
        PositionEntity pe = buildPosition("KRW-ADA", 100.0, 100.0);
        when(positionRepo.findById("KRW-ADA")).thenReturn(Optional.of(pe));

        assertFalse(lockService.canEnter("KRW-ADA", "MR"),
                "non-dust(10000원) → 새 MR 진입 차단");
    }

    @Test
    @DisplayName("D02b: threshold=5000, qty=100, avg=200 → value=20000(non-dust) → AD 차단")
    public void d02b_nonDustPositionAD_blocked() {
        BotConfigEntity cfg = buildCfg(true, 5000);
        when(botConfigRepo.findById(1L)).thenReturn(Optional.of(cfg));

        PositionEntity pe = buildPosition("KRW-SOL", 100.0, 200.0);
        when(positionRepo.findById("KRW-SOL")).thenReturn(Optional.of(pe));

        assertFalse(lockService.canEnter("KRW-SOL", "AD"),
                "non-dust(20000원) → AD 차단");
    }

    // ─────────────────────────────────────────────────────────────────
    //  D03: threshold=0 (비활성) → qty>0 무조건 보유
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("D03: threshold=0(비활성), qty=1, avg=1 → value=1원이어도 non-dust(V129 동작) → 차단")
    public void d03_thresholdZero_qtyPosIsNonDust() {
        BotConfigEntity cfg = buildCfg(true, 0);  // threshold=0
        when(botConfigRepo.findById(1L)).thenReturn(Optional.of(cfg));

        // value=1원이어도 threshold=0이면 비활성 → non-dust 간주
        PositionEntity pe = buildPosition("KRW-TINY", 1.0, 1.0);
        when(positionRepo.findById("KRW-TINY")).thenReturn(Optional.of(pe));

        assertFalse(lockService.canEnter("KRW-TINY", "MR"),
                "threshold=0 (V129 호환): qty>0이면 non-dust → 차단");
    }

    @Test
    @DisplayName("D03b: threshold=0(비활성), qty=1, avg=1 → isDustPosition=false")
    public void d03b_thresholdZero_isDustFalse() {
        BotConfigEntity cfg = buildCfg(true, 0);
        PositionEntity pe = buildPosition("KRW-TINY", 1.0, 1.0);

        assertFalse(lockService.isDustPosition(pe, cfg),
                "threshold=0 → isDustPosition=false (비활성)");
    }

    // ─────────────────────────────────────────────────────────────────
    //  D04: qty=0 → dust → 항상 허용
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("D04: qty=0 → dust=true → 새 진입 허용 (threshold 무관)")
    public void d04_zeroQty_alwaysDust() {
        BotConfigEntity cfg = buildCfg(true, 5000);
        when(botConfigRepo.findById(1L)).thenReturn(Optional.of(cfg));

        PositionEntity pe = buildPosition("KRW-ZERO", 0.0, 100.0);
        when(positionRepo.findById("KRW-ZERO")).thenReturn(Optional.of(pe));
        when(tradeRepo.existsRecentLoss(eq("KRW-ZERO"), anyLong())).thenReturn(false);

        assertTrue(lockService.canEnter("KRW-ZERO", "MR"),
                "qty=0 → dust → 진입 허용");
    }

    @Test
    @DisplayName("D04b: qty=0, threshold=0 → dust=true (qty=0은 threshold 무관하게 항상 dust)")
    public void d04b_zeroQty_dustEvenWithThresholdZero() {
        BotConfigEntity cfg = buildCfg(true, 0);
        PositionEntity pe = buildPosition("KRW-ZERO2", 0.0, 100.0);

        assertTrue(lockService.isDustPosition(pe, cfg),
                "qty=0 → dust=true (threshold=0이어도)");
    }

    // ─────────────────────────────────────────────────────────────────
    //  D05: dust 포지션이 MR 보유 → AD도 진입 허용
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("D05: MR이 KRW-DOGE dust(1000원) 보유 → AD 진입 허용 (cross-scanner + dust)")
    public void d05_mrDustPosition_adAllowed() {
        BotConfigEntity cfg = buildCfg(true, 5000);
        when(botConfigRepo.findById(1L)).thenReturn(Optional.of(cfg));

        // MR이 dust 포지션 보유 (value=10*100=1000 < 5000)
        PositionEntity pe = buildPosition("KRW-DOGE", 10.0, 100.0);
        pe.setEntryStrategy("MORNING_RUSH");
        when(positionRepo.findById("KRW-DOGE")).thenReturn(Optional.of(pe));
        when(tradeRepo.existsRecentLoss(eq("KRW-DOGE"), anyLong())).thenReturn(false);

        assertTrue(lockService.canEnter("KRW-DOGE", "AD"),
                "MR dust 보유 → AD 진입 허용 (dust는 보유 아님)");
    }

    // ─────────────────────────────────────────────────────────────────
    //  D06: non-dust 포지션이 MR 보유 → AD 차단
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("D06: MR이 KRW-BTC non-dust(100만원) 보유 → AD 차단")
    public void d06_mrNonDustPosition_adBlocked() {
        BotConfigEntity cfg = buildCfg(true, 5000);
        when(botConfigRepo.findById(1L)).thenReturn(Optional.of(cfg));

        // value=1*1_000_000=1,000,000 >> 5000 → non-dust
        PositionEntity pe = buildPosition("KRW-BTC", 1.0, 1_000_000.0);
        pe.setEntryStrategy("MORNING_RUSH");
        when(positionRepo.findById("KRW-BTC")).thenReturn(Optional.of(pe));

        assertFalse(lockService.canEnter("KRW-BTC", "AD"),
                "MR non-dust 보유 → AD 차단");
    }

    // ─────────────────────────────────────────────────────────────────
    //  D07: 경계값 value=5000 → non-dust (< 5000 아님)
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("D07: threshold=5000, qty=50, avg=100 → value=5000(경계, non-dust) → 차단")
    public void d07_boundaryValue5000_nonDustBlocked() {
        BotConfigEntity cfg = buildCfg(true, 5000);
        when(botConfigRepo.findById(1L)).thenReturn(Optional.of(cfg));

        // value=50*100=5000 → compareTo(5000)==0 → not < 5000 → non-dust
        PositionEntity pe = buildPosition("KRW-BOUND", 50.0, 100.0);
        when(positionRepo.findById("KRW-BOUND")).thenReturn(Optional.of(pe));

        assertFalse(lockService.canEnter("KRW-BOUND", "MR"),
                "value=5000(경계) → non-dust → 차단");
    }

    @Test
    @DisplayName("D07b: threshold=5000, qty=49.99, avg=100 → value=4999(dust) → 허용")
    public void d07b_justBelowBoundary_dustAllowed() {
        BotConfigEntity cfg = buildCfg(true, 5000);
        when(botConfigRepo.findById(1L)).thenReturn(Optional.of(cfg));

        PositionEntity pe = buildPosition("KRW-BOUND2", 49.99, 100.0);
        when(positionRepo.findById("KRW-BOUND2")).thenReturn(Optional.of(pe));
        when(tradeRepo.existsRecentLoss(eq("KRW-BOUND2"), anyLong())).thenReturn(false);

        assertTrue(lockService.canEnter("KRW-BOUND2", "MR"),
                "value=4999(경계 이하) → dust → 허용");
    }

    // ─────────────────────────────────────────────────────────────────
    //  D08: isDustPosition 직접 테스트 (다양한 케이스)
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("D08: isDustPosition 직접 — 다양한 threshold + qty 조합 검증")
    public void d08_isDustPosition_multipleScenarios() {
        BotConfigEntity cfg5000 = buildCfg(true, 5000);
        BotConfigEntity cfg0 = buildCfg(true, 0);

        // case1: value=1000 < 5000 → dust
        PositionEntity pe1 = buildPosition("KRW-A", 10.0, 100.0);
        assertTrue(lockService.isDustPosition(pe1, cfg5000), "1000원 dust");

        // case2: value=10000 >= 5000 → non-dust
        PositionEntity pe2 = buildPosition("KRW-B", 100.0, 100.0);
        assertFalse(lockService.isDustPosition(pe2, cfg5000), "10000원 non-dust");

        // case3: qty=0 → dust (threshold 무관)
        PositionEntity pe3 = buildPosition("KRW-C", 0.0, 100.0);
        assertTrue(lockService.isDustPosition(pe3, cfg5000), "qty=0 dust");
        assertTrue(lockService.isDustPosition(pe3, cfg0), "qty=0 dust (threshold=0도)");

        // case4: threshold=0 → 비활성 (qty>0이면 non-dust)
        PositionEntity pe4 = buildPosition("KRW-D", 1.0, 1.0);  // value=1원
        assertFalse(lockService.isDustPosition(pe4, cfg0), "threshold=0 비활성 → non-dust");

        // case5: pe=null → dust
        assertTrue(lockService.isDustPosition(null, cfg5000), "null → dust");

        // case6: avgPrice=0 → dust (비정상)
        PositionEntity pe6 = buildPosition("KRW-F", 100.0, 0.0);
        assertTrue(lockService.isDustPosition(pe6, cfg5000), "avgPrice=0 → dust");
    }
}
