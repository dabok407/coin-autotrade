package com.example.upbit.api;

public class BacktestTradeRow {
    /**
     * FE(v2)에서 사용하는 표준 키
     * - ts: 시간(문자열)
     * - orderType: 주문 타입(예: MARKET/LIMIT)
     * - pnlKrw: 손익(KRW)
     */
    public String ts;
    public String orderType;
    public double pnlKrw;

    /**
     * 레거시/내부 호환 필드(기존 코드가 참조할 수 있어 유지)
     */
    public long tsEpochMs;
    public String patternType;
    public double pnl;

    public String market;
    public String action;
    public double price;
    public double qty;
    public String note;

    /** 매도 시 매수 평균가 (매수건에서는 0) */
    public double avgBuyPrice;
    /** 수익률 (%) */
    public double roiPercent;
    /** 패턴 신뢰도 점수 (1~10) */
    public double confidence;
    /** 거래 실행 시 사용된 분봉 단위 */
    public int candleUnitMin;
}
