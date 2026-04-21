package com.example.upbit.web;

import com.example.upbit.bot.MorningRushScannerService;
import com.example.upbit.db.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/api/morning-rush")
public class MorningRushApiController {

    private static final Logger log = LoggerFactory.getLogger(MorningRushApiController.class);

    @Autowired
    private MorningRushScannerService scannerService;

    @Autowired
    private MorningRushConfigRepository configRepo;

    @Autowired
    private BotConfigRepository botConfigRepo;

    @Autowired
    private PositionRepository positionRepo;

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start() {
        MorningRushConfigEntity cfg = configRepo.loadOrCreate();
        cfg.setEnabled(true);
        configRepo.save(cfg);
        scannerService.start();
        return ResponseEntity.ok(buildStatus());
    }

    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stop() {
        MorningRushConfigEntity cfg = configRepo.loadOrCreate();
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
        MorningRushConfigEntity cfg = configRepo.loadOrCreate();
        return ResponseEntity.ok(configToMap(cfg));
    }

    @PostMapping("/config")
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody Map<String, Object> body) {
        MorningRushConfigEntity cfg = configRepo.loadOrCreate();

        if (body.containsKey("enabled")) cfg.setEnabled(Boolean.TRUE.equals(body.get("enabled")));
        if (body.containsKey("mode")) cfg.setMode(String.valueOf(body.get("mode")));
        if (body.containsKey("topN")) cfg.setTopN(toInt(body.get("topN"), 30));
        if (body.containsKey("maxPositions")) cfg.setMaxPositions(toInt(body.get("maxPositions"), 2));

        // Order sizing
        if (body.containsKey("orderSizingMode")) cfg.setOrderSizingMode(String.valueOf(body.get("orderSizingMode")));
        if (body.containsKey("orderSizingValue")) cfg.setOrderSizingValue(toBD(body.get("orderSizingValue")));

        // Gap-up params
        if (body.containsKey("gapThresholdPct")) cfg.setGapThresholdPct(toBD(body.get("gapThresholdPct")));
        if (body.containsKey("volumeMult")) cfg.setVolumeMult(toBD(body.get("volumeMult")));
        if (body.containsKey("confirmCount")) cfg.setConfirmCount(toInt(body.get("confirmCount"), 3));
        if (body.containsKey("checkIntervalSec")) cfg.setCheckIntervalSec(toInt(body.get("checkIntervalSec"), 5));

        // TP/SL (SL_TIGHT)
        if (body.containsKey("tpPct")) cfg.setTpPct(toBD(body.get("tpPct")));
        if (body.containsKey("slPct")) cfg.setSlPct(toBD(body.get("slPct")));

        // SL 종합안: 그레이스, Wide period, Wide SL
        if (body.containsKey("gracePeriodSec")) cfg.setGracePeriodSec(toInt(body.get("gracePeriodSec"), 60));
        if (body.containsKey("widePeriodMin")) cfg.setWidePeriodMin(toInt(body.get("widePeriodMin"), 5));
        if (body.containsKey("wideSlPct")) cfg.setWideSlPct(toBD(body.get("wideSlPct")));

        // Session timing
        if (body.containsKey("sessionEndHour")) cfg.setSessionEndHour(toInt(body.get("sessionEndHour"), 10));
        if (body.containsKey("sessionEndMin")) cfg.setSessionEndMin(toInt(body.get("sessionEndMin"), 0));

        // V105: 페이즈 타이밍 (DB 설정값)
        if (body.containsKey("rangeStartHour")) cfg.setRangeStartHour(toInt(body.get("rangeStartHour"), 8));
        if (body.containsKey("rangeStartMin"))  cfg.setRangeStartMin(toInt(body.get("rangeStartMin"), 50));
        if (body.containsKey("entryStartHour")) cfg.setEntryStartHour(toInt(body.get("entryStartHour"), 9));
        if (body.containsKey("entryStartMin"))  cfg.setEntryStartMin(toInt(body.get("entryStartMin"), 0));
        if (body.containsKey("entryEndHour"))   cfg.setEntryEndHour(toInt(body.get("entryEndHour"), 9));
        if (body.containsKey("entryEndMin"))    cfg.setEntryEndMin(toInt(body.get("entryEndMin"), 5));

        // Filters
        if (body.containsKey("minTradeAmount")) cfg.setMinTradeAmount(toLong(body.get("minTradeAmount"), 1000000000L));
        if (body.containsKey("excludeMarkets")) cfg.setExcludeMarkets(String.valueOf(body.get("excludeMarkets")));
        if (body.containsKey("minPriceKrw")) cfg.setMinPriceKrw(toInt(body.get("minPriceKrw"), 20));

        // TP_TRAIL
        if (body.containsKey("tpTrailDropPct")) cfg.setTpTrailDropPct(toBD(body.get("tpTrailDropPct")));

        // Split-Exit
        if (body.containsKey("splitExitEnabled")) cfg.setSplitExitEnabled(Boolean.TRUE.equals(body.get("splitExitEnabled")));
        if (body.containsKey("splitTpPct")) cfg.setSplitTpPct(toBD(body.get("splitTpPct")));
        if (body.containsKey("splitRatio")) cfg.setSplitRatio(toBD(body.get("splitRatio")));
        if (body.containsKey("trailDropAfterSplit")) cfg.setTrailDropAfterSplit(toBD(body.get("trailDropAfterSplit")));
        if (body.containsKey("split1stTrailDrop")) cfg.setSplit1stTrailDrop(toBD(body.get("split1stTrailDrop")));
        // V129: SPLIT_1ST → SPLIT_2ND_TRAIL 쿨다운
        if (body.containsKey("split1stCooldownSec")) cfg.setSplit1stCooldownSec(toInt(body.get("split1stCooldownSec"), 60));

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
        MorningRushConfigEntity cfg = configRepo.loadOrCreate();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("running", scannerService.isRunning());
        m.put("status", scannerService.getStatusText());
        m.put("scanCount", scannerService.getScanCount());
        m.put("activePositions", positionRepo.countActiveByEntryStrategy("MORNING_RUSH"));
        m.put("lastScannedMarkets", scannerService.getLastScannedMarkets());
        m.put("lastTickEpochMs", scannerService.getLastTickEpochMs());
        m.put("config", configToMap(cfg));
        return m;
    }

    private Map<String, Object> configToMap(MorningRushConfigEntity cfg) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("enabled", cfg.isEnabled());
        m.put("mode", cfg.getMode());
        m.put("topN", cfg.getTopN());
        m.put("maxPositions", cfg.getMaxPositions());

        // Global Capital (read-only from bot_config)
        List<BotConfigEntity> bcs = botConfigRepo.findAll();
        BigDecimal globalCap = (!bcs.isEmpty() && bcs.get(0).getCapitalKrw() != null)
                ? bcs.get(0).getCapitalKrw() : BigDecimal.valueOf(100000);
        m.put("globalCapitalKrw", globalCap);

        m.put("orderSizingMode", cfg.getOrderSizingMode());
        m.put("orderSizingValue", cfg.getOrderSizingValue());

        // Gap-up params
        m.put("gapThresholdPct", cfg.getGapThresholdPct());
        m.put("volumeMult", cfg.getVolumeMult());
        m.put("confirmCount", cfg.getConfirmCount());
        m.put("checkIntervalSec", cfg.getCheckIntervalSec());

        // TP/SL (SL_TIGHT)
        m.put("tpPct", cfg.getTpPct());
        m.put("slPct", cfg.getSlPct());

        // SL 종합안
        m.put("gracePeriodSec", cfg.getGracePeriodSec());
        m.put("widePeriodMin", cfg.getWidePeriodMin());
        m.put("wideSlPct", cfg.getWideSlPct());

        // Session
        m.put("sessionEndHour", cfg.getSessionEndHour());
        m.put("sessionEndMin", cfg.getSessionEndMin());

        // V105: 페이즈 타이밍
        m.put("rangeStartHour", cfg.getRangeStartHour());
        m.put("rangeStartMin", cfg.getRangeStartMin());
        m.put("entryStartHour", cfg.getEntryStartHour());
        m.put("entryStartMin", cfg.getEntryStartMin());
        m.put("entryEndHour", cfg.getEntryEndHour());
        m.put("entryEndMin", cfg.getEntryEndMin());

        // Filters
        m.put("minTradeAmount", cfg.getMinTradeAmount());
        m.put("excludeMarkets", cfg.getExcludeMarkets());
        m.put("minPriceKrw", cfg.getMinPriceKrw());
        // TP_TRAIL
        m.put("tpTrailDropPct", cfg.getTpTrailDropPct());
        // Split-Exit
        m.put("splitExitEnabled", cfg.isSplitExitEnabled());
        m.put("splitTpPct", cfg.getSplitTpPct());
        m.put("splitRatio", cfg.getSplitRatio());
        m.put("trailDropAfterSplit", cfg.getTrailDropAfterSplit());
        m.put("split1stTrailDrop", cfg.getSplit1stTrailDrop());
        m.put("split1stCooldownSec", cfg.getSplit1stCooldownSec());

        return m;
    }

    private static int toInt(Object v, int def) {
        if (v == null) return def;
        try { return ((Number) v).intValue(); } catch (Exception e) {
            try { return Integer.parseInt(v.toString()); } catch (Exception e2) { return def; }
        }
    }

    private static long toLong(Object v, long def) {
        if (v == null) return def;
        try { return ((Number) v).longValue(); } catch (Exception e) {
            try { return Long.parseLong(v.toString()); } catch (Exception e2) { return def; }
        }
    }

    private static BigDecimal toBD(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof Number) return BigDecimal.valueOf(((Number) v).doubleValue());
        try { return new BigDecimal(v.toString()); } catch (Exception e) { return BigDecimal.ZERO; }
    }
}
