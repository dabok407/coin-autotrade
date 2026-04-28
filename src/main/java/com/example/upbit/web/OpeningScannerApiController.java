package com.example.upbit.web;

import com.example.upbit.bot.OpeningScannerService;
import com.example.upbit.db.*;
import com.example.upbit.market.UpbitMarketCatalogService;
import com.example.upbit.upbit.UpbitAccount;
import com.example.upbit.upbit.UpbitPrivateClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/api/scanner")
public class OpeningScannerApiController {

    private static final Logger log = LoggerFactory.getLogger(OpeningScannerApiController.class);

    private final OpeningScannerService scannerService;
    private final OpeningScannerConfigRepository configRepo;
    private final BotConfigRepository botConfigRepo;
    private final UpbitMarketCatalogService catalogService;
    private final UpbitPrivateClient privateClient;
    private final PositionRepository positionRepo;

    public OpeningScannerApiController(OpeningScannerService scannerService,
                                        OpeningScannerConfigRepository configRepo,
                                        BotConfigRepository botConfigRepo,
                                        UpbitMarketCatalogService catalogService,
                                        UpbitPrivateClient privateClient,
                                        PositionRepository positionRepo) {
        this.scannerService = scannerService;
        this.configRepo = configRepo;
        this.botConfigRepo = botConfigRepo;
        this.catalogService = catalogService;
        this.privateClient = privateClient;
        this.positionRepo = positionRepo;
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
        // capitalKrw 제거됨 — Global Capital(bot_config) 사용
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

        // SL 종합안 + TOP-N 차등
        if (body.containsKey("gracePeriodSec")) cfg.setGracePeriodSec(toInt(body.get("gracePeriodSec"), 60));
        if (body.containsKey("widePeriodMin")) cfg.setWidePeriodMin(toInt(body.get("widePeriodMin"), 15));
        if (body.containsKey("wideSlTop10Pct")) cfg.setWideSlTop10Pct(toBD(body.get("wideSlTop10Pct")));
        if (body.containsKey("wideSlTop20Pct")) cfg.setWideSlTop20Pct(toBD(body.get("wideSlTop20Pct")));
        if (body.containsKey("wideSlTop50Pct")) cfg.setWideSlTop50Pct(toBD(body.get("wideSlTop50Pct")));
        if (body.containsKey("wideSlOtherPct")) cfg.setWideSlOtherPct(toBD(body.get("wideSlOtherPct")));
        if (body.containsKey("tightSlPct")) cfg.setTightSlPct(toBD(body.get("tightSlPct")));

        // 필터
        if (body.containsKey("btcFilterEnabled")) cfg.setBtcFilterEnabled(Boolean.TRUE.equals(body.get("btcFilterEnabled")));
        if (body.containsKey("btcEmaPeriod")) cfg.setBtcEmaPeriod(toInt(body.get("btcEmaPeriod"), 20));
        if (body.containsKey("volumeMult")) cfg.setVolumeMult(toBD(body.get("volumeMult")));
        if (body.containsKey("minBodyRatio")) cfg.setMinBodyRatio(toBD(body.get("minBodyRatio")));
        if (body.containsKey("excludeMarkets")) cfg.setExcludeMarkets(String.valueOf(body.get("excludeMarkets")));
        if (body.containsKey("openFailedEnabled")) cfg.setOpenFailedEnabled(Boolean.TRUE.equals(body.get("openFailedEnabled")));
        if (body.containsKey("minPriceKrw")) cfg.setMinPriceKrw(toInt(body.get("minPriceKrw"), 20));

        // TP_TRAIL
        if (body.containsKey("tpTrailActivatePct")) cfg.setTpTrailActivatePct(toBD(body.get("tpTrailActivatePct")));
        if (body.containsKey("tpTrailDropPct")) cfg.setTpTrailDropPct(toBD(body.get("tpTrailDropPct")));

        // Split-Exit
        if (body.containsKey("splitExitEnabled")) cfg.setSplitExitEnabled(Boolean.TRUE.equals(body.get("splitExitEnabled")));
        if (body.containsKey("splitTpPct")) cfg.setSplitTpPct(toBD(body.get("splitTpPct")));
        if (body.containsKey("splitRatio")) cfg.setSplitRatio(toBD(body.get("splitRatio")));
        if (body.containsKey("trailDropAfterSplit")) cfg.setTrailDropAfterSplit(toBD(body.get("trailDropAfterSplit")));
        if (body.containsKey("split1stTrailDrop")) cfg.setSplit1stTrailDrop(toBD(body.get("split1stTrailDrop")));
        // V126: 2차 매도 쿨다운
        if (body.containsKey("split1stCooldownSec")) cfg.setSplit1stCooldownSec(toInt(body.get("split1stCooldownSec"), 60));
        // V127: C4 vol3 임계값 DB화
        if (body.containsKey("vol3RatioThreshold")) cfg.setVol3RatioThreshold(toBD(body.get("vol3RatioThreshold")));

        // V130 ①: Trail Ladder A
        if (body.containsKey("trailLadderEnabled")) cfg.setTrailLadderEnabled(Boolean.TRUE.equals(body.get("trailLadderEnabled")));
        if (body.containsKey("split1stDropUnder2")) cfg.setSplit1stDropUnder2(toBD(body.get("split1stDropUnder2")));
        if (body.containsKey("split1stDropUnder3")) cfg.setSplit1stDropUnder3(toBD(body.get("split1stDropUnder3")));
        if (body.containsKey("split1stDropUnder5")) cfg.setSplit1stDropUnder5(toBD(body.get("split1stDropUnder5")));
        if (body.containsKey("split1stDropAbove5")) cfg.setSplit1stDropAbove5(toBD(body.get("split1stDropAbove5")));
        if (body.containsKey("trailAfterDropUnder2")) cfg.setTrailAfterDropUnder2(toBD(body.get("trailAfterDropUnder2")));
        if (body.containsKey("trailAfterDropUnder3")) cfg.setTrailAfterDropUnder3(toBD(body.get("trailAfterDropUnder3")));
        if (body.containsKey("trailAfterDropUnder5")) cfg.setTrailAfterDropUnder5(toBD(body.get("trailAfterDropUnder5")));
        if (body.containsKey("trailAfterDropAbove5")) cfg.setTrailAfterDropAbove5(toBD(body.get("trailAfterDropAbove5")));
        // V130 ②: L1 지연 진입
        if (body.containsKey("l1DelaySec")) cfg.setL1DelaySec(toInt(body.get("l1DelaySec"), 60));
        // V130 ④: SPLIT_1ST roi 하한
        if (body.containsKey("split1stRoiFloorPct")) cfg.setSplit1stRoiFloorPct(toBD(body.get("split1stRoiFloorPct")));

        configRepo.save(cfg);
        return ResponseEntity.ok(configToMap(cfg));
    }

    /**
     * 거래대금 상위 N개 KRW 마켓 조회 (보유코인 + 수동 제외 마켓 제거).
     * 백테스트 오프닝 전략에서 실전과 동일한 TOP N 마켓을 선택하기 위한 API.
     */
    @GetMapping("/top-markets")
    public ResponseEntity<List<Map<String, Object>>> topMarkets(
            @RequestParam(defaultValue = "15") int topN) {

        // 1. 제외할 마켓 수집: position table + LIVE 계좌 + 수동 설정
        Set<String> excludeMarkets = new HashSet<String>();

        // position table에서 보유 중인 코인
        List<PositionEntity> positions = positionRepo.findAll();
        for (PositionEntity pe : positions) {
            if (pe.getQty() != null && pe.getQty().compareTo(BigDecimal.ZERO) > 0) {
                excludeMarkets.add(pe.getMarket());
            }
        }

        // 업비트 실계좌 보유 코인
        if (privateClient.isConfigured()) {
            try {
                List<UpbitAccount> accounts = privateClient.getAccounts();
                if (accounts != null) {
                    for (UpbitAccount a : accounts) {
                        if ("KRW".equals(a.currency)) continue;
                        BigDecimal bal = a.balanceAsBigDecimal().add(a.lockedAsBigDecimal());
                        if (bal.compareTo(BigDecimal.ZERO) > 0) {
                            excludeMarkets.add("KRW-" + a.currency);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("업비트 잔고 조회 실패, position table만 사용", e);
            }
        }

        // 수동 제외 마켓
        OpeningScannerConfigEntity cfg = configRepo.loadOrCreate();
        excludeMarkets.addAll(cfg.getExcludeMarketsSet());

        // 2. 전체 KRW 마켓에서 제외 목록 빼고 거래대금 조회
        Set<String> allCodes = catalogService.getAllMarketCodes();
        List<String> krwMarkets = new ArrayList<String>();
        for (String m : allCodes) {
            if (m.startsWith("KRW-") && !excludeMarkets.contains(m)) {
                krwMarkets.add(m);
            }
        }

        final Map<String, Double> volumeMap = catalogService.get24hTradePrice(krwMarkets);
        krwMarkets.sort(new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                double va = volumeMap.containsKey(a) ? volumeMap.get(a) : 0;
                double vb = volumeMap.containsKey(b) ? volumeMap.get(b) : 0;
                return Double.compare(vb, va);
            }
        });

        List<String> top = krwMarkets.subList(0, Math.min(topN, krwMarkets.size()));

        // 3. 응답 빌드
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (String market : top) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("market", market);
            item.put("displayName", catalogService.displayLabel(market));
            item.put("volume24h", volumeMap.containsKey(market) ? volumeMap.get(market) : 0);
            result.add(item);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * v2: 스캐너 디시전 로그 (차단/실행/에러 사유 확인용)
     */
    @GetMapping("/decisions")
    public ResponseEntity<List<Map<String, Object>>> decisions(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(scannerService.getRecentDecisions(Math.min(limit, 200)));
    }

    // ===== Helpers =====

    private Map<String, Object> buildStatus() {
        OpeningScannerConfigEntity cfg = configRepo.loadOrCreate();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("running", scannerService.isRunning());
        m.put("status", scannerService.getStatusText());
        m.put("scanCount", scannerService.getScanCount());
        m.put("activePositions", positionRepo.countActiveByEntryStrategy("SCALP_OPENING_BREAK"));
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
        // Global Capital 읽기 전용 반환 (bot_config에서 조회)
        List<BotConfigEntity> bcs = botConfigRepo.findAll();
        BigDecimal globalCap = (!bcs.isEmpty() && bcs.get(0).getCapitalKrw() != null)
                ? bcs.get(0).getCapitalKrw() : BigDecimal.valueOf(100000);
        m.put("globalCapitalKrw", globalCap);
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
        // SL 종합안 + TOP-N 차등
        m.put("gracePeriodSec", cfg.getGracePeriodSec());
        m.put("widePeriodMin", cfg.getWidePeriodMin());
        m.put("wideSlTop10Pct", cfg.getWideSlTop10Pct());
        m.put("wideSlTop20Pct", cfg.getWideSlTop20Pct());
        m.put("wideSlTop50Pct", cfg.getWideSlTop50Pct());
        m.put("wideSlOtherPct", cfg.getWideSlOtherPct());
        m.put("tightSlPct", cfg.getTightSlPct());
        // 필터
        m.put("btcFilterEnabled", cfg.isBtcFilterEnabled());
        m.put("btcEmaPeriod", cfg.getBtcEmaPeriod());
        m.put("volumeMult", cfg.getVolumeMult());
        m.put("minBodyRatio", cfg.getMinBodyRatio());
        m.put("excludeMarkets", cfg.getExcludeMarkets());
        m.put("openFailedEnabled", cfg.isOpenFailedEnabled());
        m.put("minPriceKrw", cfg.getMinPriceKrw());
        // TP_TRAIL
        m.put("tpTrailActivatePct", cfg.getTpTrailActivatePct());
        m.put("tpTrailDropPct", cfg.getTpTrailDropPct());
        // Split-Exit
        m.put("splitExitEnabled", cfg.isSplitExitEnabled());
        m.put("splitTpPct", cfg.getSplitTpPct());
        m.put("splitRatio", cfg.getSplitRatio());
        m.put("trailDropAfterSplit", cfg.getTrailDropAfterSplit());
        m.put("split1stTrailDrop", cfg.getSplit1stTrailDrop());
        m.put("split1stCooldownSec", cfg.getSplit1stCooldownSec());
        m.put("vol3RatioThreshold", cfg.getVol3RatioThreshold());
        // V130 ①: Trail Ladder A
        m.put("trailLadderEnabled", cfg.isTrailLadderEnabled());
        m.put("split1stDropUnder2", cfg.getSplit1stDropUnder2());
        m.put("split1stDropUnder3", cfg.getSplit1stDropUnder3());
        m.put("split1stDropUnder5", cfg.getSplit1stDropUnder5());
        m.put("split1stDropAbove5", cfg.getSplit1stDropAbove5());
        m.put("trailAfterDropUnder2", cfg.getTrailAfterDropUnder2());
        m.put("trailAfterDropUnder3", cfg.getTrailAfterDropUnder3());
        m.put("trailAfterDropUnder5", cfg.getTrailAfterDropUnder5());
        m.put("trailAfterDropAbove5", cfg.getTrailAfterDropAbove5());
        // V130 ②: L1 지연 진입
        m.put("l1DelaySec", cfg.getL1DelaySec());
        // V130 ④: SPLIT_1ST roi 하한
        m.put("split1stRoiFloorPct", cfg.getSplit1stRoiFloorPct());
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
