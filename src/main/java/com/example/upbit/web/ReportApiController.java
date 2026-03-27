package com.example.upbit.web;

import com.example.upbit.db.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 외부 스케줄러용 거래 리포트 API.
 * API 키 인증 (X-API-Key 헤더) — 로그인 불필요.
 */
@RestController
@RequestMapping("/api/report")
public class ReportApiController {

    @Value("${report.apiKey:}")
    private String reportApiKey;

    private final TradeRepository tradeRepo;
    private final PositionRepository positionRepo;
    private final BotConfigRepository botConfigRepo;

    public ReportApiController(TradeRepository tradeRepo,
                               PositionRepository positionRepo,
                               BotConfigRepository botConfigRepo) {
        this.tradeRepo = tradeRepo;
        this.positionRepo = positionRepo;
        this.botConfigRepo = botConfigRepo;
    }

    /**
     * 오프닝 전략 중간 리포트 (12:10 KST 스케줄용).
     * 오늘 오프닝 스캐너 거래만 반환.
     */
    @GetMapping("/opening")
    public ResponseEntity<?> openingReport(@RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        ResponseEntity<?> authCheck = checkApiKey(apiKey);
        if (authCheck != null) return authCheck;

        return ResponseEntity.ok(buildReport("opening", LocalDate.now(ZoneId.of("Asia/Seoul"))));
    }

    /**
     * 전일 전체 거래 최종 리포트 (자정 스케줄용).
     * 어제 전체 거래 반환.
     */
    @GetMapping("/daily")
    public ResponseEntity<?> dailyReport(@RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        ResponseEntity<?> authCheck = checkApiKey(apiKey);
        if (authCheck != null) return authCheck;

        LocalDate yesterday = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1);
        return ResponseEntity.ok(buildReport("all", yesterday));
    }

    /**
     * 특정 날짜 거래 리포트.
     */
    @GetMapping("/date/{date}")
    public ResponseEntity<?> dateReport(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @PathVariable String date) {
        ResponseEntity<?> authCheck = checkApiKey(apiKey);
        if (authCheck != null) return authCheck;

        LocalDate targetDate = LocalDate.parse(date);
        return ResponseEntity.ok(buildReport("all", targetDate));
    }

    // ===== Internal =====

    private Map<String, Object> buildReport(String type, LocalDate targetDate) {
        ZonedDateTime startKst = targetDate.atStartOfDay(ZoneId.of("Asia/Seoul"));
        ZonedDateTime endKst = startKst.plusDays(1);
        long fromMs = startKst.toInstant().toEpochMilli();
        long toMs = endKst.toInstant().toEpochMilli();

        List<TradeEntity> allTrades = tradeRepo.findAll();
        List<Map<String, Object>> trades = new ArrayList<Map<String, Object>>();

        int sellCount = 0, wins = 0;
        double totalPnl = 0;

        for (TradeEntity t : allTrades) {
            if (t.getTsEpochMs() < fromMs || t.getTsEpochMs() >= toMs) continue;

            // opening 리포트면 오프닝 전략만
            if ("opening".equals(type) && t.getPatternType() != null
                    && !"SCALP_OPENING_BREAK".equals(t.getPatternType())) continue;

            Map<String, Object> trade = new LinkedHashMap<String, Object>();
            trade.put("id", t.getId());
            trade.put("tsEpochMs", t.getTsEpochMs());
            trade.put("time", Instant.ofEpochMilli(t.getTsEpochMs())
                    .atZone(ZoneId.of("Asia/Seoul"))
                    .format(DateTimeFormatter.ofPattern("HH:mm")));
            trade.put("market", t.getMarket());
            trade.put("action", t.getAction());
            trade.put("price", t.getPrice());
            trade.put("qty", t.getQty());
            trade.put("pnlKrw", t.getPnlKrw());
            trade.put("roiPercent", t.getRoiPercent());
            trade.put("mode", t.getMode());
            trade.put("patternType", t.getPatternType());
            trade.put("patternReason", t.getPatternReason());
            trade.put("avgBuyPrice", t.getAvgBuyPrice());
            trade.put("confidence", t.getConfidence());
            trades.add(trade);

            if ("SELL".equals(t.getAction()) || "PAPER_SELL".equals(t.getAction())) {
                sellCount++;
                double pnl = t.getPnlKrw() != null ? t.getPnlKrw().doubleValue() : 0;
                totalPnl += pnl;
                if (pnl > 0) wins++;
            }
        }

        // 현재 포지션
        List<Map<String, Object>> positions = new ArrayList<Map<String, Object>>();
        for (PositionEntity p : positionRepo.findAll()) {
            if (p.getQty() != null && p.getQty().compareTo(BigDecimal.ZERO) > 0) {
                Map<String, Object> pos = new LinkedHashMap<String, Object>();
                pos.put("market", p.getMarket());
                pos.put("qty", p.getQty());
                pos.put("avgPrice", p.getAvgPrice());
                pos.put("entryStrategy", p.getEntryStrategy());
                positions.add(pos);
            }
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("type", type);
        result.put("date", targetDate.toString());
        result.put("trades", trades);
        result.put("sellCount", sellCount);
        result.put("wins", wins);
        result.put("losses", sellCount - wins);
        result.put("winRate", sellCount > 0 ? Math.round(wins * 100.0 / sellCount) : 0);
        result.put("totalPnlKrw", Math.round(totalPnl));
        result.put("openPositions", positions);
        return result;
    }

    private ResponseEntity<?> checkApiKey(String apiKey) {
        if (reportApiKey == null || reportApiKey.isEmpty() || "changeme-replace-with-random-key".equals(reportApiKey)) {
            // 키가 설정되지 않은 경우 — 보안 경고
            Map<String, String> err = new HashMap<String, String>();
            err.put("error", "REPORT_API_KEY_NOT_CONFIGURED");
            err.put("message", "환경변수 REPORT_API_KEY를 설정해주세요.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
        }
        if (apiKey == null || !reportApiKey.equals(apiKey)) {
            Map<String, String> err = new HashMap<String, String>();
            err.put("error", "UNAUTHORIZED");
            err.put("message", "유효한 X-API-Key 헤더가 필요합니다.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(err);
        }
        return null; // OK
    }
}
