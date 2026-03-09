package com.example.upbit.api;

import java.util.ArrayList;
import java.util.List;

/**
 * v2 FE 호환 응답.
 * - legacy 필드(totalPnl/totalRoi/totalTrades)는 유지
 */
public class BacktestResponse {
    // legacy
    public double totalPnl;
    public double totalRoi;
    public int totalTrades;

    // v2
    public double totalReturn;
    public double roi;
    public int tradesCount;
    public int wins;
    public double winRate;
    public double finalCapital;

    // debug/info (UI가 "0건"일 때 동작 여부 확인용)
    public int candleCount;
    public int candleUnitMin;
    public int periodDays;

    // v3: multi markets echo
    public List<String> markets = new ArrayList<>();

    // info/warn message (optional)
    public String note;

    // echo (debug): request/used strategies (StrategyType enum name)
    public List<String> strategies = new ArrayList<>();

    // echo (debug): 실제 적용된 TP/SL 값
    public Double usedTpPct;
    public Double usedSlPct;

    // 진단: 매도 유형별 카운트
    public int tpSellCount;    // TP에 의한 매도 횟수
    public int slSellCount;    // SL에 의한 매도 횟수
    public int patternSellCount; // 패턴에 의한 매도 횟수
    public int tpMissCount;    // TP 발동 가능했으나 패턴이 먼저 매도한 횟수

    public List<BacktestTradeRow> trades = new ArrayList<>();
}
