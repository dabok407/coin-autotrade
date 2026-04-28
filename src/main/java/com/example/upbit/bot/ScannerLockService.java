package com.example.upbit.bot;

import com.example.upbit.db.BotConfigEntity;
import com.example.upbit.db.BotConfigRepository;
import com.example.upbit.db.PositionEntity;
import com.example.upbit.db.TradeRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * V130 ③: 스캐너 간 동일종목 재진입 차단 + V130 ⑤: Dust 포지션 판별.
 *
 * canEnter(market, scanner) — 진입 직전 호출:
 *   (a) 다른 스캐너가 현재 보유한 종목이면 차단 (dust 제외)
 *   (b) 당일 동일 종목 손실 청산 후 sameMarketLossCooldownMin 분 이내면 차단
 *
 * isDustPosition(pe, cfg) — qty * avgPrice < dustHoldingThresholdKrw 이면 dust = 보유 아님.
 */
@Service
public class ScannerLockService {

    private static final Logger log = LoggerFactory.getLogger(ScannerLockService.class);

    private final BotConfigRepository botConfigRepo;
    private final com.example.upbit.db.PositionRepository positionRepo;
    private final TradeRepository tradeRepo;

    public ScannerLockService(BotConfigRepository botConfigRepo,
                               com.example.upbit.db.PositionRepository positionRepo,
                               TradeRepository tradeRepo) {
        this.botConfigRepo = botConfigRepo;
        this.positionRepo = positionRepo;
        this.tradeRepo = tradeRepo;
    }

    /**
     * 스캐너가 market에 진입 가능한지 판단.
     * cross_scanner_lock_enabled=false이면 항상 true (기존 V129 동작).
     *
     * @param market  예: "KRW-SOL"
     * @param scanner 로그용 스캐너 식별자 예: "OP", "MR", "AD"
     * @return true=진입 허용, false=차단
     */
    public boolean canEnter(String market, String scanner) {
        BotConfigEntity cfg = botConfigRepo.findById(1L).orElse(null);
        if (cfg == null || !cfg.isCrossScannerLockEnabled()) {
            return true;
        }

        // (a) 다른 스캐너가 현재 보유 중 (dust 제외)
        PositionEntity existing = positionRepo.findById(market).orElse(null);
        if (existing != null && !isDustPosition(existing, cfg)) {
            log.info("[ScannerLock] [{}] {} BLOCKED — already held by {} qty={}",
                    scanner, market, existing.getEntryStrategy(), existing.getQty());
            return false;
        }

        // (b) 당일 동일 종목 손실 청산 이력 쿨다운
        int cooldownMin = cfg.getSameMarketLossCooldownMin();
        if (cooldownMin > 0) {
            long sinceMs = System.currentTimeMillis() - (long) cooldownMin * 60_000L;
            boolean recentLoss = tradeRepo.existsRecentLoss(market, sinceMs);
            if (recentLoss) {
                log.info("[ScannerLock] [{}] {} BLOCKED — recent loss within {}min cooldown",
                        scanner, market, cooldownMin);
                return false;
            }
        }

        return true;
    }

    /**
     * 포지션이 dust(5000원 미만 잔여)인지 판별.
     * threshold=0이면 비활성 → 항상 false (보유로 간주, V129 동작 유지).
     *
     * @param pe  포지션 엔티티
     * @param cfg BotConfigEntity (dustHoldingThresholdKrw 참조)
     * @return true=dust(보유 아님), false=유효 포지션(보유)
     */
    public boolean isDustPosition(PositionEntity pe, BotConfigEntity cfg) {
        if (pe == null) return true;
        if (pe.getQty() == null || pe.getQty().compareTo(BigDecimal.ZERO) <= 0) return true;
        if (cfg == null) return false;

        int threshold = cfg.getDustHoldingThresholdKrw();
        if (threshold <= 0) return false; // 비활성 — qty > 0이면 유효 포지션

        BigDecimal avgPrice = pe.getAvgPrice();
        if (avgPrice == null || avgPrice.compareTo(BigDecimal.ZERO) <= 0) return true;

        BigDecimal value = pe.getQty().multiply(avgPrice);
        return value.compareTo(BigDecimal.valueOf(threshold)) < 0;
    }

    /**
     * BotConfigEntity 조회 없이 isDustPosition을 호출하는 편의 메소드.
     * cfg를 botConfigRepo에서 직접 조회.
     */
    public boolean isDustPosition(PositionEntity pe) {
        BotConfigEntity cfg = botConfigRepo.findById(1L).orElse(null);
        return isDustPosition(pe, cfg);
    }
}
