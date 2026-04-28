package com.example.upbit.bot;

import com.example.upbit.db.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V130 ③: Cross-scanner Lock + 손실 cooldown 시나리오 테스트.
 *
 * 시나리오:
 *   C01: OP가 KRW-XYZ 보유 중(non-dust) → MR/AD 동일 종목 진입 시도 → 차단
 *   C02: 당일 OP에서 KRW-XYZ 손실 청산 → 6시간 내 AD 진입 시도 → 차단
 *   C03: 6시간 후(cooldown=0min) → 진입 허용
 *   C04: cross_scanner_lock_enabled=false → 차단 안 됨 (non-dust 보유 중에도 허용)
 *   C05: dust 보유 중 → 진입 허용 (dust는 보유 아님으로 간주)
 *   C06: MR이 보유 중 → AD 차단
 *   C07: AD가 보유 중 → OP 차단
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("V130 Cross-Scanner Lock Scenario Tests")
public class V130CrossScannerLockScenarioTest {

    @Mock private BotConfigRepository botConfigRepo;
    @Mock private PositionRepository positionRepo;
    @Mock private TradeRepository tradeRepo;

    private ScannerLockService lockService;

    @BeforeEach
    public void setUp() {
        lockService = new ScannerLockService(botConfigRepo, positionRepo, tradeRepo);
    }

    // ─────────────────────────────────────────────────────────────────
    //  Helper: config/entity 빌더
    // ─────────────────────────────────────────────────────────────────

    private BotConfigEntity buildCfg(boolean lockEnabled, int cooldownMin, int dustThreshold) {
        BotConfigEntity cfg = new BotConfigEntity();
        cfg.setCrossScannerLockEnabled(lockEnabled);
        cfg.setSameMarketLossCooldownMin(cooldownMin);
        cfg.setDustHoldingThresholdKrw(dustThreshold);
        return cfg;
    }

    private PositionEntity buildPosition(String market, double qty, double avgPrice) {
        PositionEntity pe = new PositionEntity();
        pe.setMarket(market);
        pe.setQty(BigDecimal.valueOf(qty));
        pe.setAvgPrice(BigDecimal.valueOf(avgPrice));
        pe.setEntryStrategy("OPENING_SCANNER");
        return pe;
    }

    // ─────────────────────────────────────────────────────────────────
    //  C01: OP 보유 중(non-dust) → MR/AD 차단
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("C01-MR: OP가 KRW-XYZ non-dust 보유 → MR 진입 차단")
    public void c01_opHoldsNonDust_mrBlocked() {
        BotConfigEntity cfg = buildCfg(true, 0, 5000);
        when(botConfigRepo.findById(1L)).thenReturn(Optional.of(cfg));

        // qty=100, avgPrice=200 → value=20000원 >= 5000 → non-dust
        PositionEntity pe = buildPosition("KRW-XYZ", 100.0, 200.0);
        when(positionRepo.findById("KRW-XYZ")).thenReturn(Optional.of(pe));

        assertFalse(lockService.canEnter("KRW-XYZ", "MR"),
                "OP non-dust 보유 중 → MR 차단");
    }

    @Test
    @DisplayName("C01-AD: OP가 KRW-XYZ non-dust 보유 → AD 진입 차단")
    public void c01_opHoldsNonDust_adBlocked() {
        BotConfigEntity cfg = buildCfg(true, 0, 5000);
        when(botConfigRepo.findById(1L)).thenReturn(Optional.of(cfg));

        PositionEntity pe = buildPosition("KRW-XYZ", 100.0, 200.0);
        when(positionRepo.findById("KRW-XYZ")).thenReturn(Optional.of(pe));

        assertFalse(lockService.canEnter("KRW-XYZ", "AD"),
                "OP non-dust 보유 중 → AD 차단");
    }

    // ─────────────────────────────────────────────────────────────────
    //  C02: 당일 손실 청산 후 6시간 내 → 차단
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("C02: 당일 OP 손실 청산 후 cooldown=360min(6h) 내 → AD 차단")
    public void c02_recentLossWithinCooldown_adBlocked() {
        BotConfigEntity cfg = buildCfg(true, 360, 5000);
        when(botConfigRepo.findById(1L)).thenReturn(Optional.of(cfg));
        // 포지션 없음
        when(positionRepo.findById("KRW-SOL")).thenReturn(Optional.empty());
        // 6시간(360분) 이내 손실 이력 있음
        when(tradeRepo.existsRecentLoss(eq("KRW-SOL"), anyLong())).thenReturn(true);

        assertFalse(lockService.canEnter("KRW-SOL", "AD"),
                "6시간 내 손실 이력 → AD 차단");
    }

    @Test
    @DisplayName("C02-MR: 당일 손실 청산 후 cooldown=360min(6h) 내 → MR 차단")
    public void c02_recentLossWithinCooldown_mrBlocked() {
        BotConfigEntity cfg = buildCfg(true, 360, 5000);
        when(botConfigRepo.findById(1L)).thenReturn(Optional.of(cfg));
        when(positionRepo.findById("KRW-ADA")).thenReturn(Optional.empty());
        when(tradeRepo.existsRecentLoss(eq("KRW-ADA"), anyLong())).thenReturn(true);

        assertFalse(lockService.canEnter("KRW-ADA", "MR"),
                "6시간 내 손실 이력 → MR 차단");
    }

    // ─────────────────────────────────────────────────────────────────
    //  C03: 6시간 후 (손실 이력 없음 or cooldown=0) → 진입 허용
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("C03: cooldown 만료 후 (existsRecentLoss=false) → AD 진입 허용")
    public void c03_cooldownExpired_adAllowed() {
        BotConfigEntity cfg = buildCfg(true, 360, 5000);
        when(botConfigRepo.findById(1L)).thenReturn(Optional.of(cfg));
        when(positionRepo.findById("KRW-SOL")).thenReturn(Optional.empty());
        // 손실 이력 없음 (cooldown 만료)
        when(tradeRepo.existsRecentLoss(eq("KRW-SOL"), anyLong())).thenReturn(false);

        assertTrue(lockService.canEnter("KRW-SOL", "AD"),
                "cooldown 만료 → 진입 허용");
    }

    @Test
    @DisplayName("C03: cooldown=0 → 손실 체크 없이 진입 허용")
    public void c03_cooldownZero_allowedWithoutLossCheck() {
        BotConfigEntity cfg = buildCfg(true, 0, 5000);
        when(botConfigRepo.findById(1L)).thenReturn(Optional.of(cfg));
        when(positionRepo.findById("KRW-SOL")).thenReturn(Optional.empty());

        assertTrue(lockService.canEnter("KRW-SOL", "MR"),
                "cooldown=0 → 손실 체크 없이 허용");
        verify(tradeRepo, never()).existsRecentLoss(anyString(), anyLong());
    }

    // ─────────────────────────────────────────────────────────────────
    //  C04: cross_scanner_lock_enabled=false → 차단 안 됨
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("C04: cross_scanner_lock_enabled=false → non-dust 보유 중에도 진입 허용")
    public void c04_lockDisabled_allowedDespiteHolding() {
        BotConfigEntity cfg = buildCfg(false, 360, 5000);
        when(botConfigRepo.findById(1L)).thenReturn(Optional.of(cfg));

        // lock 비활성 → positionRepo 조회 없이 바로 true
        boolean result = lockService.canEnter("KRW-XYZ", "MR");
        assertTrue(result, "lock 비활성 → 항상 허용");
        verify(positionRepo, never()).findById(anyString());
        verify(tradeRepo, never()).existsRecentLoss(anyString(), anyLong());
    }

    @Test
    @DisplayName("C04-AD: cross_scanner_lock_enabled=false → 손실 이력 있어도 진입 허용")
    public void c04_lockDisabled_allowedDespiteLoss() {
        BotConfigEntity cfg = buildCfg(false, 360, 5000);
        when(botConfigRepo.findById(1L)).thenReturn(Optional.of(cfg));

        assertTrue(lockService.canEnter("KRW-SOL", "AD"),
                "lock 비활성 → 손실 이력 무시하고 허용");
    }

    // ─────────────────────────────────────────────────────────────────
    //  C05: dust 보유 중 → 진입 허용
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("C05: dust 포지션(value<5000) 보유 중 → 차단 없이 진입 허용")
    public void c05_dustPosition_allowedToEnter() {
        BotConfigEntity cfg = buildCfg(true, 360, 5000);
        when(botConfigRepo.findById(1L)).thenReturn(Optional.of(cfg));

        // qty=10, avgPrice=100 → value=1000원 < 5000 → dust
        PositionEntity pe = buildPosition("KRW-DOGE", 10.0, 100.0);
        when(positionRepo.findById("KRW-DOGE")).thenReturn(Optional.of(pe));
        when(tradeRepo.existsRecentLoss(eq("KRW-DOGE"), anyLong())).thenReturn(false);

        assertTrue(lockService.canEnter("KRW-DOGE", "MR"),
                "dust 포지션 → 보유 아님으로 간주, 진입 허용");
    }

    @Test
    @DisplayName("C05-AD: dust 포지션(value=1000원) → AD 진입 허용")
    public void c05_dustPosition_adAllowed() {
        BotConfigEntity cfg = buildCfg(true, 360, 5000);
        when(botConfigRepo.findById(1L)).thenReturn(Optional.of(cfg));

        PositionEntity pe = buildPosition("KRW-DOGE", 10.0, 100.0);
        when(positionRepo.findById("KRW-DOGE")).thenReturn(Optional.of(pe));
        when(tradeRepo.existsRecentLoss(eq("KRW-DOGE"), anyLong())).thenReturn(false);

        assertTrue(lockService.canEnter("KRW-DOGE", "AD"),
                "dust 포지션 → AD 진입 허용");
    }

    // ─────────────────────────────────────────────────────────────────
    //  C06: MR이 보유 중 → AD 차단
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("C06: MR이 KRW-BTC non-dust 보유 → AD 차단")
    public void c06_mrHoldsNonDust_adBlocked() {
        BotConfigEntity cfg = buildCfg(true, 0, 5000);
        when(botConfigRepo.findById(1L)).thenReturn(Optional.of(cfg));

        PositionEntity pe = buildPosition("KRW-BTC", 0.01, 100_000_000.0);
        pe.setEntryStrategy("MORNING_RUSH");
        // value=1,000,000원 >> 5000 → non-dust
        when(positionRepo.findById("KRW-BTC")).thenReturn(Optional.of(pe));

        assertFalse(lockService.canEnter("KRW-BTC", "AD"),
                "MR non-dust 보유 → AD 차단");
    }

    // ─────────────────────────────────────────────────────────────────
    //  C07: AD가 보유 중 → OP 차단
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("C07: AD가 KRW-ETH non-dust 보유 → OP 차단")
    public void c07_adHoldsNonDust_opBlocked() {
        BotConfigEntity cfg = buildCfg(true, 0, 5000);
        when(botConfigRepo.findById(1L)).thenReturn(Optional.of(cfg));

        PositionEntity pe = buildPosition("KRW-ETH", 0.1, 5_000_000.0);
        pe.setEntryStrategy("ALL_DAY_SCANNER");
        // value=500,000원 → non-dust
        when(positionRepo.findById("KRW-ETH")).thenReturn(Optional.of(pe));

        assertFalse(lockService.canEnter("KRW-ETH", "OP"),
                "AD non-dust 보유 → OP 차단");
    }

    // ─────────────────────────────────────────────────────────────────
    //  C08: 포지션 없음 + 손실 이력 없음 → 모든 스캐너 허용
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("C08: 포지션 없음 + 손실 없음 → MR/OP/AD 모두 허용")
    public void c08_noPositionNoLoss_allScannersAllowed() {
        BotConfigEntity cfg = buildCfg(true, 360, 5000);
        when(botConfigRepo.findById(1L)).thenReturn(Optional.of(cfg));
        when(positionRepo.findById("KRW-FREE")).thenReturn(Optional.empty());
        when(tradeRepo.existsRecentLoss(eq("KRW-FREE"), anyLong())).thenReturn(false);

        assertTrue(lockService.canEnter("KRW-FREE", "MR"), "MR 허용");
        assertTrue(lockService.canEnter("KRW-FREE", "OP"), "OP 허용");
        assertTrue(lockService.canEnter("KRW-FREE", "AD"), "AD 허용");
    }

    // ─────────────────────────────────────────────────────────────────
    //  C09: 경계값 — non-dust 기준(threshold=5000) 경계 포지션
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("C09: value=4999원(dust) → 진입 허용, value=5001원(non-dust) → 차단")
    public void c09_boundary_dustVsNonDust() {
        BotConfigEntity cfg = buildCfg(true, 0, 5000);
        when(botConfigRepo.findById(1L)).thenReturn(Optional.of(cfg));
        when(tradeRepo.existsRecentLoss(anyString(), anyLong())).thenReturn(false);

        // dust 경계: qty=49.99, avgPrice=100 → value=4999 < 5000 → dust → 허용
        PositionEntity dustPe = buildPosition("KRW-DUST", 49.99, 100.0);
        when(positionRepo.findById("KRW-DUST")).thenReturn(Optional.of(dustPe));
        assertTrue(lockService.canEnter("KRW-DUST", "MR"), "4999원 → dust → 허용");

        // non-dust 경계: qty=50.01, avgPrice=100 → value=5001 >= 5000 → non-dust → 차단
        PositionEntity nonDustPe = buildPosition("KRW-NODUST", 50.01, 100.0);
        when(positionRepo.findById("KRW-NODUST")).thenReturn(Optional.of(nonDustPe));
        assertFalse(lockService.canEnter("KRW-NODUST", "MR"), "5001원 → non-dust → 차단");
    }
}
