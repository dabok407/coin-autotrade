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
 * V130 ③⑤: ScannerLockService 단위 테스트.
 *
 * 검증 항목:
 *   canEnter:
 *     - 보유 없음 → true
 *     - 다른 스캐너 보유 (non-dust) → false
 *     - 다른 스캐너 보유 (dust) → true (dust는 무시)
 *     - 당일 손실 청산 후 cooldown 내 → false
 *     - cooldown 지난 후 (existsRecentLoss=false) → true
 *     - cross_scanner_lock_enabled=false → 항상 true
 *   isDustPosition:
 *     - qty*avg < threshold → true
 *     - qty*avg >= threshold → false
 *     - qty=0 → true
 *     - threshold=0 (비활성) → false
 *     - pe=null → true
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("V130 ScannerLockService Unit Tests")
public class ScannerLockServiceTest {

    @Mock private BotConfigRepository botConfigRepo;
    @Mock private PositionRepository positionRepo;
    @Mock private TradeRepository tradeRepo;

    private ScannerLockService service;

    @BeforeEach
    public void setUp() {
        service = new ScannerLockService(botConfigRepo, positionRepo, tradeRepo);
    }

    // ─────────────────────────────────────────────────────────────────
    //  Helper: BotConfigEntity 빌더
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
        return pe;
    }

    // ─────────────────────────────────────────────────────────────────
    //  canEnter: cross_scanner_lock_enabled=false → 항상 true
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("canEnter: cross_scanner_lock_enabled=false → 항상 true")
    public void canEnter_lockDisabled_alwaysTrue() {
        BotConfigEntity cfg = buildCfg(false, 360, 5000);
        when(botConfigRepo.findById(1L)).thenReturn(Optional.of(cfg));

        // 포지션 존재해도 lock 비활성이면 true
        PositionEntity pe = buildPosition("KRW-XYZ", 100.0, 200.0);
        when(positionRepo.findById("KRW-XYZ")).thenReturn(Optional.of(pe));

        assertTrue(service.canEnter("KRW-XYZ", "MR"),
                "lock 비활성 → 항상 true");
        // positionRepo/tradeRepo는 호출 안 됨
        verify(positionRepo, never()).findById(anyString());
    }

    @Test
    @DisplayName("canEnter: cfg=null (봇 설정 없음) → 항상 true")
    public void canEnter_cfgNull_alwaysTrue() {
        when(botConfigRepo.findById(1L)).thenReturn(Optional.empty());

        assertTrue(service.canEnter("KRW-XYZ", "AD"),
                "cfg 없음 → true");
    }

    // ─────────────────────────────────────────────────────────────────
    //  canEnter: 보유 없음 → true
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("canEnter: 포지션 없음 → true (차단 없음)")
    public void canEnter_noPosition_returnsTrue() {
        BotConfigEntity cfg = buildCfg(true, 360, 5000);
        when(botConfigRepo.findById(1L)).thenReturn(Optional.of(cfg));
        when(positionRepo.findById("KRW-SOL")).thenReturn(Optional.empty());
        when(tradeRepo.existsRecentLoss(eq("KRW-SOL"), anyLong())).thenReturn(false);

        assertTrue(service.canEnter("KRW-SOL", "MR"),
                "포지션 없으면 차단 안 됨");
    }

    // ─────────────────────────────────────────────────────────────────
    //  canEnter: 다른 스캐너 보유 (non-dust) → false
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("canEnter: non-dust 포지션 보유 중 → false (차단)")
    public void canEnter_nonDustPositionExists_returnsFalse() {
        BotConfigEntity cfg = buildCfg(true, 0, 5000);
        when(botConfigRepo.findById(1L)).thenReturn(Optional.of(cfg));

        // qty=100, avgPrice=200 → value=20000원 >= 5000 → non-dust
        PositionEntity pe = buildPosition("KRW-XYZ", 100.0, 200.0);
        when(positionRepo.findById("KRW-XYZ")).thenReturn(Optional.of(pe));

        assertFalse(service.canEnter("KRW-XYZ", "AD"),
                "non-dust 보유 중 → false");
    }

    // ─────────────────────────────────────────────────────────────────
    //  canEnter: 다른 스캐너 보유 (dust) → true (dust는 무시)
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("canEnter: dust 포지션만 존재 → true (dust 무시)")
    public void canEnter_dustPositionExists_returnsTrue() {
        BotConfigEntity cfg = buildCfg(true, 0, 5000);
        when(botConfigRepo.findById(1L)).thenReturn(Optional.of(cfg));

        // qty=10, avgPrice=100 → value=1000원 < 5000 → dust
        PositionEntity pe = buildPosition("KRW-XYZ", 10.0, 100.0);
        when(positionRepo.findById("KRW-XYZ")).thenReturn(Optional.of(pe));
        when(tradeRepo.existsRecentLoss(eq("KRW-XYZ"), anyLong())).thenReturn(false);

        assertTrue(service.canEnter("KRW-XYZ", "MR"),
                "dust 포지션은 보유 아님으로 간주 → true");
    }

    // ─────────────────────────────────────────────────────────────────
    //  canEnter: 당일 손실 청산 후 cooldown 내 → false
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("canEnter: 당일 손실 이력 (cooldown 내) → false")
    public void canEnter_recentLossWithinCooldown_returnsFalse() {
        BotConfigEntity cfg = buildCfg(true, 360, 5000);
        when(botConfigRepo.findById(1L)).thenReturn(Optional.of(cfg));
        when(positionRepo.findById("KRW-SOL")).thenReturn(Optional.empty());
        when(tradeRepo.existsRecentLoss(eq("KRW-SOL"), anyLong())).thenReturn(true);

        assertFalse(service.canEnter("KRW-SOL", "OP"),
                "cooldown 내 손실 이력 → false");
    }

    // ─────────────────────────────────────────────────────────────────
    //  canEnter: cooldown 지난 후 → true
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("canEnter: 손실 이력 없음 (cooldown 만료) → true")
    public void canEnter_noRecentLoss_returnsTrue() {
        BotConfigEntity cfg = buildCfg(true, 360, 5000);
        when(botConfigRepo.findById(1L)).thenReturn(Optional.of(cfg));
        when(positionRepo.findById("KRW-SOL")).thenReturn(Optional.empty());
        when(tradeRepo.existsRecentLoss(eq("KRW-SOL"), anyLong())).thenReturn(false);

        assertTrue(service.canEnter("KRW-SOL", "AD"),
                "cooldown 만료 → true");
    }

    @Test
    @DisplayName("canEnter: cooldown=0이면 손실 이력 체크 스킵 → true")
    public void canEnter_cooldownZero_skipLossCheck() {
        BotConfigEntity cfg = buildCfg(true, 0, 5000);
        when(botConfigRepo.findById(1L)).thenReturn(Optional.of(cfg));
        when(positionRepo.findById("KRW-SOL")).thenReturn(Optional.empty());

        assertTrue(service.canEnter("KRW-SOL", "MR"),
                "cooldown=0이면 손실 체크 생략");
        verify(tradeRepo, never()).existsRecentLoss(anyString(), anyLong());
    }

    // ─────────────────────────────────────────────────────────────────
    //  isDustPosition: qty*avg < threshold → true
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("isDustPosition: qty=10, avg=100 → value=1000 < threshold=5000 → dust=true")
    public void isDust_valueBelowThreshold_true() {
        BotConfigEntity cfg = buildCfg(true, 0, 5000);
        PositionEntity pe = buildPosition("KRW-X", 10.0, 100.0);
        assertTrue(service.isDustPosition(pe, cfg),
                "1000원 < 5000 → dust");
    }

    @Test
    @DisplayName("isDustPosition: qty=100, avg=100 → value=10000 >= threshold=5000 → dust=false")
    public void isDust_valueAboveThreshold_false() {
        BotConfigEntity cfg = buildCfg(true, 0, 5000);
        PositionEntity pe = buildPosition("KRW-X", 100.0, 100.0);
        assertFalse(service.isDustPosition(pe, cfg),
                "10000원 >= 5000 → non-dust");
    }

    @Test
    @DisplayName("isDustPosition: qty=50, avg=100 → value=5000 (경계: < 5000은 false) → dust=false")
    public void isDust_valueAtBoundary_false() {
        BotConfigEntity cfg = buildCfg(true, 0, 5000);
        PositionEntity pe = buildPosition("KRW-X", 50.0, 100.0);
        // value=5000 → compareTo(5000) == 0 → not < 0 → false (non-dust)
        assertFalse(service.isDustPosition(pe, cfg),
                "5000원 (경계, < 아님) → non-dust");
    }

    // ─────────────────────────────────────────────────────────────────
    //  isDustPosition: qty=0 → true
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("isDustPosition: qty=0 → true (보유 없음)")
    public void isDust_zeroQty_true() {
        BotConfigEntity cfg = buildCfg(true, 0, 5000);
        PositionEntity pe = buildPosition("KRW-X", 0.0, 100.0);
        assertTrue(service.isDustPosition(pe, cfg),
                "qty=0 → dust=true");
    }

    @Test
    @DisplayName("isDustPosition: qty 음수 → true (비정상 포지션)")
    public void isDust_negativeQty_true() {
        BotConfigEntity cfg = buildCfg(true, 0, 5000);
        PositionEntity pe = buildPosition("KRW-X", -1.0, 100.0);
        assertTrue(service.isDustPosition(pe, cfg),
                "qty<0 → dust=true");
    }

    // ─────────────────────────────────────────────────────────────────
    //  isDustPosition: threshold=0 (비활성) → false
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("isDustPosition: threshold=0 → qty>0이면 non-dust (V129 동작)")
    public void isDust_thresholdZero_alwaysNonDust() {
        BotConfigEntity cfg = buildCfg(true, 0, 0);
        // 아무리 작아도 threshold=0이면 non-dust (비활성)
        PositionEntity pe = buildPosition("KRW-X", 1.0, 1.0); // value=1원
        assertFalse(service.isDustPosition(pe, cfg),
                "threshold=0 (비활성) → non-dust");
    }

    @Test
    @DisplayName("isDustPosition: pe=null → true")
    public void isDust_nullPe_true() {
        BotConfigEntity cfg = buildCfg(true, 0, 5000);
        assertTrue(service.isDustPosition(null, cfg),
                "pe=null → dust=true");
    }

    @Test
    @DisplayName("isDustPosition: avgPrice=0 → true (비정상 가격)")
    public void isDust_zeroAvgPrice_true() {
        BotConfigEntity cfg = buildCfg(true, 0, 5000);
        PositionEntity pe = buildPosition("KRW-X", 100.0, 0.0);
        assertTrue(service.isDustPosition(pe, cfg),
                "avgPrice=0 → dust=true");
    }
}
