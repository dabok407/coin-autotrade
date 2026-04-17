package com.example.upbit.web;

import com.example.upbit.bot.AllDayScannerService;
import com.example.upbit.db.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/api/allday-scanner")
public class AllDayScannerApiController {

    private static final Logger log = LoggerFactory.getLogger(AllDayScannerApiController.class);

    @Autowired
    private AllDayScannerService scannerService;

    @Autowired
    private AllDayScannerConfigRepository configRepo;

    @Autowired
    private BotConfigRepository botConfigRepo;

    @Autowired
    private PositionRepository positionRepo;

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start() {
        AllDayScannerConfigEntity cfg = configRepo.loadOrCreate();
        cfg.setEnabled(true);
        configRepo.save(cfg);
        scannerService.start();
        return ResponseEntity.ok(buildStatus());
    }

    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stop() {
        AllDayScannerConfigEntity cfg = configRepo.loadOrCreate();
        cfg.setEnabled(false);
        configRepo.save(cfg);
        scannerService.stop();
        return ResponseEntity.ok(buildStatus());
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(buildStatus());
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        AllDayScannerConfigEntity cfg = configRepo.loadOrCreate();
        return ResponseEntity.ok(configToMap(cfg));
    }

    @PostMapping("/config")
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody Map<String, Object> body) {
        AllDayScannerConfigEntity cfg = configRepo.loadOrCreate();

        if (body.containsKey("enabled")) cfg.setEnabled(Boolean.TRUE.equals(body.get("enabled")));
        if (body.containsKey("mode")) cfg.setMode(String.valueOf(body.get("mode")));
        if (body.containsKey("topN")) cfg.setTopN(toInt(body.get("topN"), 15));
        if (body.containsKey("maxPositions")) cfg.setMaxPositions(toInt(body.get("maxPositions"), 2));

        // 주문 사이징
        if (body.containsKey("orderSizingMode")) cfg.setOrderSizingMode(String.valueOf(body.get("orderSizingMode")));
        if (body.containsKey("orderSizingValue")) cfg.setOrderSizingValue(toBD(body.get("orderSizingValue")));
        if (body.containsKey("candleUnitMin")) cfg.setCandleUnitMin(toInt(body.get("candleUnitMin"), 5));

        // 타이밍
        if (body.containsKey("entryStartHour")) cfg.setEntryStartHour(toInt(body.get("entryStartHour"), 10));
        if (body.containsKey("entryStartMin")) cfg.setEntryStartMin(toInt(body.get("entryStartMin"), 35));
        if (body.containsKey("entryEndHour")) cfg.setEntryEndHour(toInt(body.get("entryEndHour"), 22));
        if (body.containsKey("entryEndMin")) cfg.setEntryEndMin(toInt(body.get("entryEndMin"), 0));
        if (body.containsKey("sessionEndHour")) cfg.setSessionEndHour(toInt(body.get("sessionEndHour"), 23));
        if (body.containsKey("sessionEndMin")) cfg.setSessionEndMin(toInt(body.get("sessionEndMin"), 0));

        // 리스크
        if (body.containsKey("slPct")) cfg.setSlPct(toBD(body.get("slPct")));
        if (body.containsKey("trailAtrMult")) cfg.setTrailAtrMult(toBD(body.get("trailAtrMult")));
        if (body.containsKey("minConfidence")) cfg.setMinConfidence(toBD(body.get("minConfidence")));
        if (body.containsKey("timeStopCandles")) cfg.setTimeStopCandles(toInt(body.get("timeStopCandles"), 12));
        if (body.containsKey("timeStopMinPnl")) cfg.setTimeStopMinPnl(toBD(body.get("timeStopMinPnl")));

        // 필터
        if (body.containsKey("btcFilterEnabled")) cfg.setBtcFilterEnabled(Boolean.TRUE.equals(body.get("btcFilterEnabled")));
        if (body.containsKey("btcEmaPeriod")) cfg.setBtcEmaPeriod(toInt(body.get("btcEmaPeriod"), 20));
        if (body.containsKey("volumeSurgeMult")) cfg.setVolumeSurgeMult(toBD(body.get("volumeSurgeMult")));
        if (body.containsKey("minBodyRatio")) cfg.setMinBodyRatio(toBD(body.get("minBodyRatio")));
        if (body.containsKey("excludeMarkets")) cfg.setExcludeMarkets(String.valueOf(body.get("excludeMarkets")));
        if (body.containsKey("minPriceKrw")) cfg.setMinPriceKrw(toInt(body.get("minPriceKrw"), 20));

        // Quick TP
        if (body.containsKey("quickTpEnabled")) cfg.setQuickTpEnabled(Boolean.TRUE.equals(body.get("quickTpEnabled")));
        if (body.containsKey("quickTpPct")) cfg.setQuickTpPct(toBD(body.get("quickTpPct")));
        if (body.containsKey("quickTpIntervalSec")) cfg.setQuickTpIntervalSec(toInt(body.get("quickTpIntervalSec"), 5));

        // Split-Exit
        if (body.containsKey("splitExitEnabled")) cfg.setSplitExitEnabled(Boolean.TRUE.equals(body.get("splitExitEnabled")));
        if (body.containsKey("splitTpPct")) cfg.setSplitTpPct(toBD(body.get("splitTpPct")));
        if (body.containsKey("splitRatio")) cfg.setSplitRatio(toBD(body.get("splitRatio")));
        if (body.containsKey("trailDropAfterSplit")) cfg.setTrailDropAfterSplit(toBD(body.get("trailDropAfterSplit")));
        if (body.containsKey("split1stTrailDrop")) cfg.setSplit1stTrailDrop(toBD(body.get("split1stTrailDrop")));

        configRepo.save(cfg);
        return ResponseEntity.ok(configToMap(cfg));
    }

    @GetMapping("/decisions")
    public ResponseEntity<List<Map<String, Object>>> decisions(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(scannerService.getRecentDecisions(Math.min(limit, 200)));
    }

    // ===== Helpers =====

    private Map<String, Object> buildStatus() {
        AllDayScannerConfigEntity cfg = configRepo.loadOrCreate();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("running", scannerService.isRunning());
        m.put("status", scannerService.getStatusText());
        m.put("scanCount", scannerService.getScanCount());
        m.put("activePositions", positionRepo.countActiveByEntryStrategy("HIGH_CONFIDENCE_BREAKOUT"));
        m.put("lastScannedMarkets", scannerService.getLastScannedMarkets());
        m.put("lastTickEpochMs", scannerService.getLastTickEpochMs());
        m.put("config", configToMap(cfg));
        return m;
    }

    private Map<String, Object> configToMap(AllDayScannerConfigEntity cfg) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("enabled", cfg.isEnabled());
        m.put("mode", cfg.getMode());
        m.put("topN", cfg.getTopN());
        m.put("maxPositions", cfg.getMaxPositions());
        // Global Capital 읽기 전용 반환 (bot_config에서 조회)
        List<BotConfigEntity> bcs = botConfigRepo.findAll();
        BigDecimal globalCap = (!bcs.isEmpty() && bcs.get(0).getCapitalKrw() != null)
                ? bcs.get(0).getCapitalKrw() : BigDecimal.valueOf(100000);
        m.put("globalCapitalKrw", globalCap);
        m.put("orderSizingMode", cfg.getOrderSizingMode());
        m.put("orderSizingValue", cfg.getOrderSizingValue());
        m.put("candleUnitMin", cfg.getCandleUnitMin());
        // 타이밍
        m.put("entryStartHour", cfg.getEntryStartHour());
        m.put("entryStartMin", cfg.getEntryStartMin());
        m.put("entryEndHour", cfg.getEntryEndHour());
        m.put("entryEndMin", cfg.getEntryEndMin());
        m.put("sessionEndHour", cfg.getSessionEndHour());
        m.put("sessionEndMin", cfg.getSessionEndMin());
        // 리스크
        m.put("slPct", cfg.getSlPct());
        m.put("trailAtrMult", cfg.getTrailAtrMult());
        m.put("minConfidence", cfg.getMinConfidence());
        m.put("timeStopCandles", cfg.getTimeStopCandles());
        m.put("timeStopMinPnl", cfg.getTimeStopMinPnl());
        // 필터
        m.put("btcFilterEnabled", cfg.isBtcFilterEnabled());
        m.put("btcEmaPeriod", cfg.getBtcEmaPeriod());
        m.put("volumeSurgeMult", cfg.getVolumeSurgeMult());
        m.put("minBodyRatio", cfg.getMinBodyRatio());
        m.put("excludeMarkets", cfg.getExcludeMarkets());
        m.put("minPriceKrw", cfg.getMinPriceKrw());
        // Quick TP
        m.put("quickTpEnabled", cfg.isQuickTpEnabled());
        m.put("quickTpPct", cfg.getQuickTpPctBD());
        m.put("quickTpIntervalSec", cfg.getQuickTpIntervalSec());
        // Split-Exit
        m.put("splitExitEnabled", cfg.isSplitExitEnabled());
        m.put("splitTpPct", cfg.getSplitTpPct());
        m.put("splitRatio", cfg.getSplitRatio());
        m.put("trailDropAfterSplit", cfg.getTrailDropAfterSplit());
        m.put("split1stTrailDrop", cfg.getSplit1stTrailDrop());
        return m;
    }

    private static int toInt(Object v, int def) {
        if (v == null) return def;
        try { return ((Number) v).intValue(); } catch (Exception e) {
            try { return Integer.parseInt(v.toString()); } catch (Exception e2) { return def; }
        }
    }

    private static BigDecimal toBD(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof Number) return BigDecimal.valueOf(((Number) v).doubleValue());
        try { return new BigDecimal(v.toString()); } catch (Exception e) { return BigDecimal.ZERO; }
    }
}
