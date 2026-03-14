package com.example.upbit.api;

import com.example.upbit.backtest.BacktestService;
import com.example.upbit.backtest.BatchOptimizationService;
import com.example.upbit.db.OptimizationResultEntity;
import com.example.upbit.market.CandleCacheService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/backtest")
public class BacktestApiController {

    private final BacktestService backtestService;
    private final CandleCacheService candleCacheService;
    private final BatchOptimizationService optimizationService;

    public BacktestApiController(BacktestService backtestService,
                                  CandleCacheService candleCacheService,
                                  BatchOptimizationService optimizationService) {
        this.backtestService = backtestService;
        this.candleCacheService = candleCacheService;
        this.optimizationService = optimizationService;
    }

    @PostMapping("/run")
    public ResponseEntity<BacktestResponse> run(@RequestBody BacktestRequest req) {
        BacktestResponse body = backtestService.run(req);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(body);
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
