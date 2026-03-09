package com.example.upbit.web;

import com.example.upbit.bot.BotStatus;
import com.example.upbit.bot.TradingBotService;
import com.example.upbit.market.UpbitMarketCatalogService;
import com.example.upbit.db.MarketConfigEntity;
import com.example.upbit.db.MarketConfigRepository;
import com.example.upbit.db.StrategyGroupEntity;
import com.example.upbit.db.StrategyGroupRepository;
import com.example.upbit.db.TradeEntity;

import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Dashboard/Backtest UI API.
 *
 * NOTE:
 * - 기존 엔드포인트(/api/bot/config 등) 호환 유지
 * - FE(v2) 호환을 위해 /api/bot/settings + /api/bot/trades?page&size 응답 형태를 추가
 */
@RestController
public class BotApiController {

    private final TradingBotService bot;
    private final UpbitMarketCatalogService marketCatalog;
    private final StrategyGroupRepository groupRepo;
    private final MarketConfigRepository marketConfigRepo;

    public BotApiController(TradingBotService bot, UpbitMarketCatalogService marketCatalog,
                            StrategyGroupRepository groupRepo, MarketConfigRepository marketConfigRepo) {
        this.bot = bot;
        this.marketCatalog = marketCatalog;
        this.groupRepo = groupRepo;
        this.marketConfigRepo = marketConfigRepo;
    }

    @GetMapping("/api/bot/start")
    public BotStatus start() {
        bot.start();
        return bot.getStatus();
    }

    @GetMapping("/api/bot/stop")
    public BotStatus stop() {
        bot.stop();
        return bot.getStatus();
    }

    @GetMapping("/api/bot/status")
    public BotStatus status() {
        return bot.getStatus();
    }

    /**
     * Trades (legacy): list
     * Trades (v2): paging {items,total,page,size}
     */
    @GetMapping("/api/bot/trades")
    public Object trades(
            @RequestParam(value="page", required=false) Integer page,
            @RequestParam(value="size", required=false) Integer size
    ) {
        if (page == null || size == null) {
            return bot.recentTrades();
        }
        int p = Math.max(1, page.intValue());
        int s = Math.max(1, Math.min(500, size.intValue()));
        List<TradeEntity> all = bot.recentTrades();
        int total = all.size();
        int from = Math.min(total, (p - 1) * s);
        int to = Math.min(total, from + s);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("page", p);
        out.put("size", s);
        out.put("total", total);
        out.put("items", all.subList(from, to));
        return out;
    }

    /**
     * UI에서 분봉/모드/자본/전략(멀티)을 변경하면 즉시 반영.
     * - v2 FE 호환: strategies(string[])
     */
    @PostMapping("/api/bot/config")
    public BotStatus updateConfig(@RequestBody UpdateConfigRequest req) {
        Double cap = (req.capitalKrw != null ? req.capitalKrw : req.capital);
        bot.updateBotConfig(req.mode, req.candleUnitMin, cap, req.strategyType, req.strategies,
                req.orderSizingMode, req.orderSizingValue, req.maxAddBuysGlobal, req.takeProfitPct, req.stopLossPct, req.strategyLock, req.minConfidence, null, null, null);
        return bot.getStatus();
    }

    /**
     * v2 FE 호환 (dashboard.js): /api/bot/settings
     * - interval: "5분" / "15분" / "60분" 등 -> candleUnitMin로 변환
     * - strategies: string[] (StrategyType enum name)
     */
    @PostMapping("/api/bot/settings")
    public BotStatus updateSettings(@RequestBody SettingsRequest req) {
        Integer unit = req.candleUnitMin;
        if (unit == null && req.interval != null) unit = parseIntervalToMin(req.interval);
        Double cap = (req.capitalKrw != null ? req.capitalKrw : req.capital);
        bot.updateBotConfig(req.mode, unit, cap, req.strategyType, req.strategies,
                req.orderSizingMode, req.orderSizingValue, req.maxAddBuysGlobal, req.takeProfitPct, req.stopLossPct, req.strategyLock, req.minConfidence, req.timeStopMinutes, req.strategyIntervalsCsv, req.emaFilterCsv);
        return bot.getStatus();
    }

    private Integer parseIntervalToMin(String interval) {
        if (interval == null) return null;
        String s = interval.trim();

        // FE(v3) 권장: "1m", "3m", "60m", "240m", "1d"
        if (s.equalsIgnoreCase("1d")) return 1440;
        if (s.toLowerCase(Locale.ROOT).endsWith("m")) {
            try {
                return Integer.valueOf(Integer.parseInt(s.substring(0, s.length() - 1)));
            } catch (Exception ignore) {
                return null;
            }
        }

        // Legacy: "5분", "15분", "60분" 등
        s = s.replace("분", "");
        try { return Integer.valueOf(Integer.parseInt(s)); } catch (Exception ignore) {}
        return null;
    }

    /**
     * UI에서 코인 ON/OFF, 투입금 등을 수정하면 즉시 반영.
     */
    @PostMapping("/api/bot/markets")
    public BotStatus updateMarkets(@RequestBody List<MarketConfigEntity> markets) {
        bot.updateMarkets(markets);
        return bot.getStatus();
    }

    @GetMapping("/api/bot/markets")
public List<MarketConfigEntity> markets() {
    List<MarketConfigEntity> list = bot.getMarketConfigs();
    // add displayName (transient) for UI labels e.g. "솔라나(SOL)"
    for (MarketConfigEntity m : list) {
        try {
            m.setDisplayName(marketCatalog.displayLabel(m.getMarket()));
        } catch (Exception ignore) {}
    }
    return list;
}

    // ===== Strategy Groups API =====

    /**
     * 전략 그룹 목록 조회.
     * 그룹이 없으면 빈 배열 반환 (하위호환: bot_config 사용).
     */
    @GetMapping("/api/bot/groups")
    public List<GroupDto> getGroups() {
        List<StrategyGroupEntity> entities = groupRepo.findAllByOrderBySortOrderAsc();
        List<GroupDto> result = new ArrayList<GroupDto>();
        for (StrategyGroupEntity e : entities) {
            result.add(toGroupDto(e));
        }
        return result;
    }

    /**
     * 전략 그룹 전체 저장 (기존 그룹 삭제 후 새로 저장).
     * 마켓 중복 검증: 한 마켓은 하나의 그룹에만 속해야 함.
     * 저장 후 market_config 동기화 (그룹에 포함된 마켓 활성화).
     */
    @PostMapping("/api/bot/groups")
    public Map<String, Object> saveGroups(@RequestBody List<GroupDto> groups) {
        Map<String, Object> response = new LinkedHashMap<String, Object>();

        // 마켓 중복 검증
        Set<String> allMarkets = new LinkedHashSet<String>();
        for (GroupDto g : groups) {
            if (g.markets != null) {
                for (String m : g.markets) {
                    if (m == null || m.trim().isEmpty()) continue;
                    String mk = m.trim();
                    if (allMarkets.contains(mk)) {
                        response.put("success", false);
                        response.put("error", "마켓 '" + mk + "'이(가) 여러 그룹에 중복됩니다.");
                        return response;
                    }
                    allMarkets.add(mk);
                }
            }
        }

        // 기존 그룹 삭제
        groupRepo.deleteAll();
        groupRepo.flush();

        // 새 그룹 저장
        int sortOrder = 0;
        for (GroupDto g : groups) {
            StrategyGroupEntity e = new StrategyGroupEntity();
            e.setGroupName(g.groupName != null ? g.groupName : "Group " + (sortOrder + 1));
            e.setSortOrder(sortOrder++);

            // markets CSV
            if (g.markets != null && !g.markets.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (String mk : g.markets) {
                    if (mk == null || mk.trim().isEmpty()) continue;
                    if (sb.length() > 0) sb.append(",");
                    sb.append(mk.trim());
                }
                e.setMarketsCsv(sb.toString());
            }

            // strategies CSV
            if (g.strategies != null && !g.strategies.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (String s : g.strategies) {
                    if (s == null || s.trim().isEmpty()) continue;
                    if (sb.length() > 0) sb.append(",");
                    sb.append(s.trim());
                }
                e.setStrategyTypesCsv(sb.toString());
            }

            e.setCandleUnitMin(g.candleUnitMin > 0 ? g.candleUnitMin : 60);
            e.setOrderSizingMode(g.orderSizingMode != null ? g.orderSizingMode : "PCT");
            e.setOrderSizingValue(BigDecimal.valueOf(g.orderSizingValue > 0 ? g.orderSizingValue : 90));
            e.setTakeProfitPct(BigDecimal.valueOf(g.takeProfitPct >= 0 ? g.takeProfitPct : 3.0));
            e.setStopLossPct(BigDecimal.valueOf(g.stopLossPct >= 0 ? g.stopLossPct : 2.0));
            e.setMaxAddBuys(Math.max(0, g.maxAddBuys));
            e.setStrategyLock(g.strategyLock);
            e.setMinConfidence(Math.max(0, g.minConfidence));
            e.setTimeStopMinutes(Math.max(0, g.timeStopMinutes));
            e.setStrategyIntervalsCsv(g.strategyIntervalsCsv != null ? g.strategyIntervalsCsv : "");
            e.setEmaFilterCsv(g.emaFilterCsv != null ? g.emaFilterCsv : "");
            e.setSelectedPreset(g.selectedPreset);

            groupRepo.save(e);
        }

        // market_config 동기화: 그룹에 포함된 마켓은 enabled=true
        syncMarketConfigs(allMarkets);

        response.put("success", true);
        response.put("groupCount", groups.size());
        return response;
    }

    /**
     * 그룹에 포함된 마켓을 market_config에 동기화.
     * - 그룹에 있는 마켓 → enabled=true (없으면 생성)
     * - 그룹에 없는 기존 마켓 → enabled=false
     */
    private void syncMarketConfigs(Set<String> groupMarkets) {
        // 기존 모든 마켓 업데이트
        List<MarketConfigEntity> existing = marketConfigRepo.findAllByOrderByMarketAsc();
        Set<String> existingMarkets = new LinkedHashSet<String>();
        for (MarketConfigEntity mc : existing) {
            existingMarkets.add(mc.getMarket());
            mc.setEnabled(groupMarkets.contains(mc.getMarket()));
            marketConfigRepo.save(mc);
        }
        // 그룹에는 있지만 market_config에 없는 마켓 추가
        for (String m : groupMarkets) {
            if (!existingMarkets.contains(m)) {
                MarketConfigEntity mc = new MarketConfigEntity();
                mc.setMarket(m);
                mc.setEnabled(true);
                mc.setBaseOrderKrw(BigDecimal.valueOf(10000));
                marketConfigRepo.save(mc);
            }
        }
    }

    private GroupDto toGroupDto(StrategyGroupEntity e) {
        GroupDto dto = new GroupDto();
        dto.id = e.getId();
        dto.groupName = e.getGroupName();
        dto.sortOrder = e.getSortOrder();
        dto.markets = e.getMarketsList();
        dto.strategies = e.getStrategyTypesList();
        dto.candleUnitMin = e.getCandleUnitMin();
        dto.orderSizingMode = e.getOrderSizingMode();
        dto.orderSizingValue = e.getOrderSizingValue() != null ? e.getOrderSizingValue().doubleValue() : 90;
        dto.takeProfitPct = e.getTakeProfitPct() != null ? e.getTakeProfitPct().doubleValue() : 3.0;
        dto.stopLossPct = e.getStopLossPct() != null ? e.getStopLossPct().doubleValue() : 2.0;
        dto.maxAddBuys = e.getMaxAddBuys();
        dto.strategyLock = Boolean.TRUE.equals(e.getStrategyLock());
        dto.minConfidence = e.getMinConfidence();
        dto.timeStopMinutes = e.getTimeStopMinutes();
        dto.strategyIntervalsCsv = e.getStrategyIntervalsCsv();
        dto.emaFilterCsv = e.getEmaFilterCsv();
        dto.selectedPreset = e.getSelectedPreset();
        return dto;
    }

    public static class GroupDto {
        public Long id;
        public String groupName;
        public int sortOrder;
        public List<String> markets;
        public List<String> strategies;
        public int candleUnitMin = 60;
        public String orderSizingMode = "PCT";
        public double orderSizingValue = 90;
        public double takeProfitPct = 3.0;
        public double stopLossPct = 2.0;
        public int maxAddBuys = 2;
        public boolean strategyLock = false;
        public double minConfidence = 0;
        public int timeStopMinutes = 0;
        public String strategyIntervalsCsv = "";
        public String emaFilterCsv = "";
        public String selectedPreset;
    }

    public static class UpdateConfigRequest {
        public String mode; // PAPER/LIVE
        public Integer candleUnitMin;
        public Double capitalKrw;
        public Double capital; // alias
        public String strategyType;       // legacy single
        public List<String> strategies;   // v2 multi
        public String orderSizingMode;    // FIXED/PCT
        public Double orderSizingValue;   // FIXED: KRW, PCT: percent
        public Integer maxAddBuysGlobal;  // global risk limit (default 2)
        public Double takeProfitPct;  // TP percent (0 disables)
        public Double stopLossPct;    // SL percent (0 disables)
        public Boolean strategyLock;  // strategy lock toggle
        public Double minConfidence;  // min confidence score (0~10)
    }

    public static class SettingsRequest {
        public String mode;
        public Integer candleUnitMin;
        public String interval;
        public Double capitalKrw;
        public Double capital; // alias
        public String strategyType;
        public List<String> strategies;
        public String orderSizingMode;
        public Double orderSizingValue;
        public Integer maxAddBuysGlobal;
        public Double takeProfitPct;  // TP percent (0 disables)
        public Double stopLossPct;    // SL percent (0 disables)
        public Boolean strategyLock;  // strategy lock toggle
        public Double minConfidence;  // min confidence score (0~10)
        public Integer timeStopMinutes; // time stop in minutes (0 = disabled)
        public String strategyIntervalsCsv; // per-strategy interval overrides
        public String emaFilterCsv; // per-strategy EMA trend filter overrides
    }
}
