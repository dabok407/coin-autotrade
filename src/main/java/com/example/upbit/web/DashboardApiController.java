package com.example.upbit.web;

import com.example.upbit.dashboard.AssetSummaryResponse;
import com.example.upbit.dashboard.AssetSummaryService;
import com.example.upbit.bot.TradingBotService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DashboardApiController {

    private final AssetSummaryService assetSummaryService;
    private final TradingBotService bot;

    public DashboardApiController(AssetSummaryService assetSummaryService, TradingBotService bot) {
        this.assetSummaryService = assetSummaryService;
        this.bot = bot;
    }

    @GetMapping("/api/dashboard/asset-summary")
    public AssetSummaryResponse assetSummary() {
        return assetSummaryService.getSummary();
    }

    @GetMapping("/api/dashboard/decision-logs")
    public Object decisionLogs() {
        // newest-first, max 200
        return bot.getRecentDecisionLogs(200);
    }

}
