package com.example.upbit.web;

import com.example.upbit.bot.OpeningScannerService;
import com.example.upbit.db.OpeningScannerConfigEntity;
import com.example.upbit.db.OpeningScannerConfigRepository;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/api/scanner")
public class OpeningScannerApiController {

    private final OpeningScannerService scannerService;
    private final OpeningScannerConfigRepository configRepo;

    public OpeningScannerApiController(OpeningScannerService scannerService,
                                        OpeningScannerConfigRepository configRepo) {
        this.scannerService = scannerService;
        this.configRepo = configRepo;
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start() {
        OpeningScannerConfigEntity cfg = configRepo.loadOrCreate();
        cfg.setEnabled(true);
        configRepo.save(cfg);
        scannerService.start();
        return ResponseEntity.ok(buildStatus());
    }

    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stop() {
        OpeningScannerConfigEntity cfg = configRepo.loadOrCreate();
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
        OpeningScannerConfigEntity cfg = configRepo.loadOrCreate();
        return ResponseEntity.ok(configToMap(cfg));
    }

    @PostMapping("/config")
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody Map<String, Object> body) {
        OpeningScannerConfigEntity cfg = configRepo.loadOrCreate();

        if (body.containsKey("enabled")) cfg.setEnabled(Boolean.TRUE.equals(body.get("enabled")));
        if (body.containsKey("mode")) cfg.setMode(String.valueOf(body.get("mode")));
        if (body.containsKey("topN")) cfg.setTopN(toInt(body.get("topN"), 15));
        if (body.containsKey("maxPositions")) cfg.setMaxPositions(toInt(body.get("maxPositions"), 3));
        if (body.containsKey("capitalKrw")) cfg.setCapitalKrw(toBD(body.get("capitalKrw")));
        if (body.containsKey("orderSizingMode")) cfg.setOrderSizingMode(String.valueOf(body.get("orderSizingMode")));
        if (body.containsKey("orderSizingValue")) cfg.setOrderSizingValue(toBD(body.get("orderSizingValue")));
        if (body.containsKey("candleUnitMin")) cfg.setCandleUnitMin(toInt(body.get("candleUnitMin"), 5));

        // 타이밍
        if (body.containsKey("rangeStartHour")) cfg.setRangeStartHour(toInt(body.get("rangeStartHour"), 8));
        if (body.containsKey("rangeStartMin")) cfg.setRangeStartMin(toInt(body.get("rangeStartMin"), 0));
        if (body.containsKey("rangeEndHour")) cfg.setRangeEndHour(toInt(body.get("rangeEndHour"), 8));
        if (body.containsKey("rangeEndMin")) cfg.setRangeEndMin(toInt(body.get("rangeEndMin"), 59));
        if (body.containsKey("entryStartHour")) cfg.setEntryStartHour(toInt(body.get("entryStartHour"), 9));
        if (body.containsKey("entryStartMin")) cfg.setEntryStartMin(toInt(body.get("entryStartMin"), 5));
        if (body.containsKey("entryEndHour")) cfg.setEntryEndHour(toInt(body.get("entryEndHour"), 10));
        if (body.containsKey("entryEndMin")) cfg.setEntryEndMin(toInt(body.get("entryEndMin"), 30));
        if (body.containsKey("sessionEndHour")) cfg.setSessionEndHour(toInt(body.get("sessionEndHour"), 12));
        if (body.containsKey("sessionEndMin")) cfg.setSessionEndMin(toInt(body.get("sessionEndMin"), 0));

        // 리스크
        if (body.containsKey("tpAtrMult")) cfg.setTpAtrMult(toBD(body.get("tpAtrMult")));
        if (body.containsKey("slPct")) cfg.setSlPct(toBD(body.get("slPct")));
        if (body.containsKey("trailAtrMult")) cfg.setTrailAtrMult(toBD(body.get("trailAtrMult")));

        // 필터
        if (body.containsKey("btcFilterEnabled")) cfg.setBtcFilterEnabled(Boolean.TRUE.equals(body.get("btcFilterEnabled")));
        if (body.containsKey("btcEmaPeriod")) cfg.setBtcEmaPeriod(toInt(body.get("btcEmaPeriod"), 20));
        if (body.containsKey("volumeMult")) cfg.setVolumeMult(toBD(body.get("volumeMult")));
        if (body.containsKey("minBodyRatio")) cfg.setMinBodyRatio(toBD(body.get("minBodyRatio")));

        configRepo.save(cfg);
        return ResponseEntity.ok(configToMap(cfg));
    }

    // ===== Helpers =====

    private Map<String, Object> buildStatus() {
        OpeningScannerConfigEntity cfg = configRepo.loadOrCreate();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("running", scannerService.isRunning());
        m.put("status", scannerService.getStatusText());
        m.put("scanCount", scannerService.getScanCount());
        m.put("activePositions", scannerService.getActivePositions());
        m.put("lastScannedMarkets", scannerService.getLastScannedMarkets());
        m.put("lastTickEpochMs", scannerService.getLastTickEpochMs());
        m.put("config", configToMap(cfg));
        return m;
    }

    private Map<String, Object> configToMap(OpeningScannerConfigEntity cfg) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("enabled", cfg.isEnabled());
        m.put("mode", cfg.getMode());
        m.put("topN", cfg.getTopN());
        m.put("maxPositions", cfg.getMaxPositions());
        m.put("capitalKrw", cfg.getCapitalKrw());
        m.put("orderSizingMode", cfg.getOrderSizingMode());
        m.put("orderSizingValue", cfg.getOrderSizingValue());
        m.put("candleUnitMin", cfg.getCandleUnitMin());
        // 타이밍
        m.put("rangeStartHour", cfg.getRangeStartHour());
        m.put("rangeStartMin", cfg.getRangeStartMin());
        m.put("rangeEndHour", cfg.getRangeEndHour());
        m.put("rangeEndMin", cfg.getRangeEndMin());
        m.put("entryStartHour", cfg.getEntryStartHour());
        m.put("entryStartMin", cfg.getEntryStartMin());
        m.put("entryEndHour", cfg.getEntryEndHour());
        m.put("entryEndMin", cfg.getEntryEndMin());
        m.put("sessionEndHour", cfg.getSessionEndHour());
        m.put("sessionEndMin", cfg.getSessionEndMin());
        // 리스크
        m.put("tpAtrMult", cfg.getTpAtrMult());
        m.put("slPct", cfg.getSlPct());
        m.put("trailAtrMult", cfg.getTrailAtrMult());
        // 필터
        m.put("btcFilterEnabled", cfg.isBtcFilterEnabled());
        m.put("btcEmaPeriod", cfg.getBtcEmaPeriod());
        m.put("volumeMult", cfg.getVolumeMult());
        m.put("minBodyRatio", cfg.getMinBodyRatio());
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
