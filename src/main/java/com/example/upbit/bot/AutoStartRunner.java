package com.example.upbit.bot;

import com.example.upbit.db.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 서버 재시작 시 auto_start_enabled가 true이면
 * 봇과 각 스캐너(enabled=true인 것)를 자동으로 시작합니다.
 */
@Component
public class AutoStartRunner {

    private static final Logger log = LoggerFactory.getLogger(AutoStartRunner.class);

    private final BotConfigRepository botConfigRepo;
    private final OpeningScannerConfigRepository openingConfigRepo;
    private final AllDayScannerConfigRepository alldayConfigRepo;
    private final MorningRushConfigRepository morningRushConfigRepo;
    private final TradingBotService tradingBotService;
    private final OpeningScannerService openingScannerService;
    private final AllDayScannerService allDayScannerService;
    private final MorningRushScannerService morningRushScannerService;

    public AutoStartRunner(BotConfigRepository botConfigRepo,
                           OpeningScannerConfigRepository openingConfigRepo,
                           AllDayScannerConfigRepository alldayConfigRepo,
                           MorningRushConfigRepository morningRushConfigRepo,
                           TradingBotService tradingBotService,
                           OpeningScannerService openingScannerService,
                           AllDayScannerService allDayScannerService,
                           MorningRushScannerService morningRushScannerService) {
        this.botConfigRepo = botConfigRepo;
        this.openingConfigRepo = openingConfigRepo;
        this.alldayConfigRepo = alldayConfigRepo;
        this.morningRushConfigRepo = morningRushConfigRepo;
        this.tradingBotService = tradingBotService;
        this.openingScannerService = openingScannerService;
        this.allDayScannerService = allDayScannerService;
        this.morningRushScannerService = morningRushScannerService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            BotConfigEntity bc = botConfigRepo.findAll().stream().findFirst().orElse(null);
            if (bc == null || !bc.isAutoStartEnabled()) {
                log.info("[AutoStart] auto_start_enabled=false, 자동 시작 건너뜀");
                return;
            }

            log.info("[AutoStart] auto_start_enabled=true, 자동 시작 시작...");

            // 1. 메인 봇 시작
            if (!tradingBotService.isRunning()) {
                boolean started = tradingBotService.start();
                log.info("[AutoStart] 메인 봇 시작: {}", started ? "성공" : "실패");
            }

            // 2. 오프닝 스캐너
            OpeningScannerConfigEntity openingCfg = openingConfigRepo.findById(1).orElse(null);
            if (openingCfg != null && openingCfg.isEnabled() && !openingScannerService.isRunning()) {
                boolean started = openingScannerService.start();
                log.info("[AutoStart] 오프닝 스캐너 시작: {}", started ? "성공" : "실패");
            }

            // 3. 종일 스캐너
            AllDayScannerConfigEntity alldayCfg = alldayConfigRepo.findById(1).orElse(null);
            if (alldayCfg != null && alldayCfg.isEnabled() && !allDayScannerService.isRunning()) {
                boolean started = allDayScannerService.start();
                log.info("[AutoStart] 종일 스캐너 시작: {}", started ? "성공" : "실패");
            }

            // 4. 모닝 러쉬 스캐너
            MorningRushConfigEntity mrCfg = morningRushConfigRepo.findById(1).orElse(null);
            if (mrCfg != null && mrCfg.isEnabled() && !morningRushScannerService.isRunning()) {
                boolean started = morningRushScannerService.start();
                log.info("[AutoStart] 모닝 러쉬 스캐너 시작: {}", started ? "성공" : "실패");
            }

            log.info("[AutoStart] 자동 시작 완료");
        } catch (Exception e) {
            log.error("[AutoStart] 자동 시작 중 오류 발생", e);
        }
    }
}
