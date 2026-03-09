package com.example.upbit.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class BacktestPageController {
    @GetMapping("/backtest")
    public String backtest() {
        return "backtest";
    }
}
