package com.example.upbit.trade;

/**
 * 업비트 원화(KRW) 마켓 호가 단위(틱 사이즈) 정규화 유틸.
 *
 * LIVE 모드에서 지정가 주문(limit)을 넣거나, 주문 가격을 계산할 때 필요합니다.
 * (본 프로젝트는 기본적으로 bid=price(원화지정) / ask=market(시장가)로 구현했지만,
 * 추후 지정가 전략을 추가할 경우 바로 재사용하도록 분리했습니다.)
 */
public class TickSizeUtil {

    /** 보수적으로 내림(floor)하여 호가 단위에 맞춥니다. */
    public static double normalizeKrwPrice(double price) {
        double unit = tickUnit(price);
        if (unit <= 0) return price;
        return Math.floor(price / unit) * unit;
    }

    public static double tickUnit(double price) {
        if (price >= 2000000) return 1000;
        if (price >= 1000000) return 1000;
        if (price >= 500000) return 500;
        if (price >= 100000) return 100;
        if (price >= 50000) return 50;
        if (price >= 10000) return 10;
        if (price >= 5000) return 5;
        if (price >= 1000) return 1;
        if (price >= 100) return 1;
        if (price >= 10) return 0.1;
        if (price >= 1) return 0.01;
        if (price >= 0.1) return 0.001;
        if (price >= 0.01) return 0.0001;
        if (price >= 0.001) return 0.00001;
        if (price >= 0.0001) return 0.000001;
        if (price >= 0.00001) return 0.0000001;
        return 0.00000001;
    }
}
