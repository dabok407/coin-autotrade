package com.example.upbit.api;

import com.example.upbit.backtest.BacktestService;
import com.example.upbit.backtest.BatchOptimizationService;
import com.example.upbit.db.CandleCacheEntity;
import com.example.upbit.db.CandleCacheRepository;
import com.example.upbit.db.OptimizationResultEntity;
import com.example.upbit.market.CandleCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api/backtest")
public class BacktestApiController {

    private static final Logger log = LoggerFactory.getLogger(BacktestApiController.class);

    private final BacktestService backtestService;
    private final CandleCacheService candleCacheService;
    private final BatchOptimizationService optimizationService;
    private final CandleCacheRepository candleCacheRepository;

    // Async backtest storage: jobId -> "running" | BacktestResponse | error message
    private final ConcurrentHashMap<String, Object> asyncJobs = new ConcurrentHashMap<String, Object>();
    private final AtomicLong jobSeq = new AtomicLong(0);

    public BacktestApiController(BacktestService backtestService,
                                  CandleCacheService candleCacheService,
                                  BatchOptimizationService optimizationService,
                                  CandleCacheRepository candleCacheRepository) {
        this.backtestService = backtestService;
        this.candleCacheService = candleCacheService;
        this.optimizationService = optimizationService;
        this.candleCacheRepository = candleCacheRepository;
    }

    @PostMapping("/run")
    public ResponseEntity<BacktestResponse> run(@RequestBody BacktestRequest req) {
        BacktestResponse body = backtestService.run(req);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(body);
    }

    // ===== Async backtest (for long-running opening strategy backtests) =====

    @PostMapping("/run-async")
    public ResponseEntity<Map<String, Object>> runAsync(@RequestBody final BacktestRequest req) {
        final String jobId = "bt-" + System.currentTimeMillis() + "-" + jobSeq.incrementAndGet();
        asyncJobs.put(jobId, "running");

        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    BacktestResponse body = backtestService.run(req);
                    asyncJobs.put(jobId, body);
                } catch (Exception e) {
                    asyncJobs.put(jobId, "error:" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                }
            }
        }, "async-backtest-" + jobId);
        t.setDaemon(true);
        t.start();

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("status", "started");
        result.put("jobId", jobId);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/async-result/{jobId}")
    public ResponseEntity<Object> asyncResult(@PathVariable String jobId) {
        Object val = asyncJobs.get(jobId);
        if (val == null) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("status", "not_found");
            return ResponseEntity.status(404).body((Object) result);
        }
        if ("running".equals(val)) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("status", "running");
            result.put("jobId", jobId);
            return ResponseEntity.ok((Object) result);
        }
        if (val instanceof String && ((String) val).startsWith("error:")) {
            asyncJobs.remove(jobId);
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("status", "error");
            result.put("message", ((String) val).substring(6));
            return ResponseEntity.ok((Object) result);
        }
        // Completed: return BacktestResponse and clean up
        asyncJobs.remove(jobId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(val);
    }

    // ===== 캔들 캐시 API =====

    @PostMapping("/candle-cache/download")
    public ResponseEntity<Map<String, Object>> downloadCandleCache() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (candleCacheService.isDownloading()) {
            result.put("status", "already_running");
            result.put("progress", candleCacheService.getCompletedJobs() + "/" + candleCacheService.getTotalJobs());
            result.put("currentTask", candleCacheService.getCurrentTask());
        } else {
            candleCacheService.downloadAllAsync();
            result.put("status", "started");
            result.put("totalJobs", candleCacheService.getTotalJobs());
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/candle-cache/status")
    public ResponseEntity<Map<String, Object>> candleCacheStatus() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("downloading", candleCacheService.isDownloading());
        result.put("progress", candleCacheService.getCompletedJobs() + "/" + candleCacheService.getTotalJobs());
        result.put("currentTask", candleCacheService.getCurrentTask());
        result.put("cache", candleCacheService.getCacheStatus());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/candle-cache/download-market")
    public ResponseEntity<Map<String, Object>> downloadMarketCache(@RequestBody Map<String, Object> body) {
        String market = body.containsKey("market") ? String.valueOf(body.get("market")) : null;
        int interval = body.containsKey("interval") ? ((Number) body.get("interval")).intValue() : 5;
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (market == null || market.isEmpty()) {
            result.put("error", "market required");
            return ResponseEntity.badRequest().body(result);
        }
        try {
            candleCacheService.downloadMarketInterval(market, interval);
            result.put("status", "done");
            result.put("market", market);
            result.put("interval", interval);
            result.put("count", candleCacheService.getCacheStatus().get(market + "/" + interval));
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping(value = "/candle-cache/import-csv", consumes = "text/csv")
    @Transactional
    public ResponseEntity<Map<String, Object>> importCandleCacheCsv(
            @RequestBody String csvBody,
            @RequestParam(value = "interval", defaultValue = "5") int interval) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        int inserted = 0;
        int skipped = 0;
        int errors = 0;
        try {
            BufferedReader reader = new BufferedReader(new StringReader(csvBody));
            String line;
            List<CandleCacheEntity> batch = new ArrayList<CandleCacheEntity>();
            // Collect existing timestamps per market to avoid per-row DB queries
            Map<String, Set<String>> existingKeys = new HashMap<String, Set<String>>();

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                // Skip header row
                if (line.startsWith("symbol,") || line.startsWith("symbol\t")) continue;

                String[] parts = line.split(",", -1);
                if (parts.length < 7) {
                    errors++;
                    continue;
                }
                try {
                    String market = parts[0].trim();
                    String timestamp = parts[1].trim();
                    double open = Double.parseDouble(parts[2].trim());
                    double high = Double.parseDouble(parts[3].trim());
                    double low = Double.parseDouble(parts[4].trim());
                    double close = Double.parseDouble(parts[5].trim());
                    double volume = Double.parseDouble(parts[6].trim());

                    // Lazy-load existing timestamps for this market+interval
                    if (!existingKeys.containsKey(market)) {
                        Set<String> tsSet = new HashSet<String>();
                        List<CandleCacheEntity> existing = candleCacheRepository
                                .findByMarketAndIntervalMinOrderByCandleTsUtcAsc(market, interval);
                        for (CandleCacheEntity e : existing) {
                            tsSet.add(e.getCandleTsUtc());
                        }
                        existingKeys.put(market, tsSet);
                    }

                    if (existingKeys.get(market).contains(timestamp)) {
                        skipped++;
                        continue;
                    }

                    CandleCacheEntity entity = new CandleCacheEntity();
                    entity.setMarket(market);
                    entity.setIntervalMin(interval);
                    entity.setCandleTsUtc(timestamp);
                    entity.setOpenPrice(open);
                    entity.setHighPrice(high);
                    entity.setLowPrice(low);
                    entity.setClosePrice(close);
                    entity.setVolume(volume);

                    batch.add(entity);
                    existingKeys.get(market).add(timestamp);
                    inserted++;

                    // Flush in batches of 500
                    if (batch.size() >= 500) {
                        candleCacheRepository.saveAll(batch);
                        candleCacheRepository.flush();
                        batch.clear();
                    }
                } catch (NumberFormatException e) {
                    errors++;
                }
            }
            // Save remaining
            if (!batch.isEmpty()) {
                candleCacheRepository.saveAll(batch);
                candleCacheRepository.flush();
            }

            result.put("status", "done");
            result.put("inserted", inserted);
            result.put("skipped", skipped);
            result.put("errors", errors);
            log.info("CSV candle import completed: inserted={}, skipped={}, errors={}, interval={}",
                    inserted, skipped, errors, interval);
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
            result.put("inserted", inserted);
            result.put("skipped", skipped);
            result.put("errors", errors);
            return ResponseEntity.status(500).body(result);
        }
        return ResponseEntity.ok(result);
    }

    // ===== 최적화 API =====

    @PostMapping("/optimization/run")
    public ResponseEntity<Map<String, Object>> startOptimization() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (optimizationService.isRunning()) {
            result.put("status", "already_running");
            result.put("runId", optimizationService.getCurrentRunId());
            result.put("progress", optimizationService.getCompletedCombinations() + "/" + optimizationService.getTotalCombinations());
        } else {
            String runId = optimizationService.startOptimization();
            result.put("status", "started");
            result.put("runId", runId);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/optimization/status/{runId}")
    public ResponseEntity<Map<String, Object>> optimizationStatus(@PathVariable String runId) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("runId", runId);
        result.put("running", optimizationService.isRunning());
        result.put("completed", optimizationService.getCompletedCombinations());
        result.put("total", optimizationService.getTotalCombinations());
        long total = optimizationService.getTotalCombinations();
        long done = optimizationService.getCompletedCombinations();
        result.put("progressPct", total > 0 ? (done * 100.0 / total) : 0);
        result.put("message", optimizationService.getStatusMessage());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/optimization/results/{runId}")
    public ResponseEntity<List<OptimizationResultEntity>> optimizationResults(@PathVariable String runId) {
        return ResponseEntity.ok(optimizationService.getResults(runId));
    }

    @GetMapping("/optimization/results/{runId}/{market}")
    public ResponseEntity<List<OptimizationResultEntity>> optimizationResultsByMarket(
            @PathVariable String runId, @PathVariable String market) {
        return ResponseEntity.ok(optimizationService.getResultsByMarket(runId, market));
    }

    @GetMapping("/optimization/top")
    public ResponseEntity<List<BatchOptimizationService.ResultEntry>> optimizationTopLive() {
        return ResponseEntity.ok(optimizationService.getTopResultsInMemory());
    }

    @GetMapping("/optimization/top/{market}")
    public ResponseEntity<List<BatchOptimizationService.ResultEntry>> optimizationTopByMarket(
            @PathVariable String market) {
        return ResponseEntity.ok(optimizationService.getTopResultsByMarketInMemory(market));
    }

    @GetMapping("/optimization/top-all-markets")
    public ResponseEntity<Map<String, List<BatchOptimizationService.ResultEntry>>> optimizationTopAllMarkets() {
        return ResponseEntity.ok(optimizationService.getAllMarketsTopInMemory());
    }

    // ===== Phase 2: 다중 전략 조합 최적화 API =====

    @PostMapping("/optimization/phase2/run")
    public ResponseEntity<Map<String, Object>> startPhase2Optimization(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (optimizationService.isRunning()) {
            result.put("status", "already_running");
            result.put("runId", optimizationService.getCurrentRunId());
            result.put("progress", optimizationService.getCompletedCombinations() + "/" + optimizationService.getTotalCombinations());
        } else {
            String phase1RunId = (String) body.get("phase1RunId");
            int topN = body.containsKey("topN") ? ((Number) body.get("topN")).intValue() : 5;
            if (phase1RunId == null || phase1RunId.trim().isEmpty()) {
                result.put("status", "error");
                result.put("message", "phase1RunId is required");
                return ResponseEntity.badRequest().body(result);
            }
            String runId = optimizationService.startPhase2Optimization(phase1RunId.trim(), topN);
            result.put("status", "started");
            result.put("runId", runId);
            result.put("phase1RunId", phase1RunId.trim());
            result.put("topN", topN);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/optimization/phase2/status/{runId}")
    public ResponseEntity<Map<String, Object>> phase2Status(@PathVariable String runId) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("runId", runId);
        result.put("running", optimizationService.isRunning());
        result.put("completed", optimizationService.getCompletedCombinations());
        result.put("total", optimizationService.getTotalCombinations());
        long total = optimizationService.getTotalCombinations();
        long done = optimizationService.getCompletedCombinations();
        result.put("progressPct", total > 0 ? (done * 100.0 / total) : 0);
        result.put("message", optimizationService.getStatusMessage());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/optimization/phase2/results/{runId}")
    public ResponseEntity<List<OptimizationResultEntity>> phase2Results(@PathVariable String runId) {
        return ResponseEntity.ok(optimizationService.getResultsByPhase(runId, 2));
    }

    @GetMapping("/optimization/phase2/results/{runId}/{market}")
    public ResponseEntity<List<OptimizationResultEntity>> phase2ResultsByMarket(
            @PathVariable String runId, @PathVariable String market) {
        return ResponseEntity.ok(optimizationService.getResultsByPhaseAndMarket(runId, 2, market));
    }
}
