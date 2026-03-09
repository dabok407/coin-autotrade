package com.example.upbit.api;

import com.example.upbit.backtest.BacktestService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/backtest")
public class BacktestApiController {

    private final BacktestService backtestService;

    public BacktestApiController(BacktestService backtestService) {
        this.backtestService = backtestService;
    }

    @PostMapping("/run")
    public ResponseEntity<BacktestResponse> run(@RequestBody BacktestRequest req) {
        BacktestResponse body = backtestService.run(req);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(body);
    }
}
