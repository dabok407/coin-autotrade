package com.example.upbit.strategy;

import com.example.upbit.market.UpbitCandle;

/**
 * 캔들 패턴 판별 유틸.
 * - 스크립트에서 설명한 정의를 '기계적'으로 적용하기 위해,
 *   임계값(몸통/꼬리 비율 등)을 몇 가지 상수로 둡니다.
 *
 * NOTE: 시장/코인/분봉에 따라 최적값은 달라질 수 있으니, 실제 운용 전 튜닝 권장.
 */
public final class CandlePatterns {

    private CandlePatterns() {}

    public static boolean isBullish(UpbitCandle c) { return c.trade_price > c.opening_price; }
    public static boolean isBearish(UpbitCandle c) { return c.trade_price < c.opening_price; }

    public static double body(UpbitCandle c) { return Math.abs(c.trade_price - c.opening_price); }
    public static double range(UpbitCandle c) { return (c.high_price - c.low_price); }
    public static double upperWick(UpbitCandle c) {
        double top = Math.max(c.opening_price, c.trade_price);
        return Math.max(0.0, c.high_price - top);
    }
    public static double lowerWick(UpbitCandle c) {
        double bot = Math.min(c.opening_price, c.trade_price);
        return Math.max(0.0, bot - c.low_price);
    }

    /**
     * 장대(모멘텀) 캔들: 몸통이 range의 대부분을 차지.
     * (ATR 미검증 — 레거시 호환용)
     */
    public static boolean isMomentum(UpbitCandle c) {
        double r = range(c);
        if (r <= 0) return false;
        return body(c) / r >= 0.80; // 80% 이상
    }

    /**
     * 장대(모멘텀) 캔들 — ATR 기반 최소 크기 검증 포함.
     * body/range ≥ 80% AND body ≥ ATR × minAtrMult 일 때만 true.
     * 이렇게 해야 꼬리 없는 작은 양봉이 모멘텀으로 오판되는 것을 방지합니다.
     */
    public static boolean isMomentum(UpbitCandle c, double atr, double minAtrMult) {
        double r = range(c);
        if (r <= 0 || atr <= 0) return false;
        double b = body(c);
        return (b / r >= 0.80) && (b >= atr * minAtrMult);
    }

    /**
     * 모멘텀 캔들의 ATR 대비 강도 (score 산정용).
     * @return body / atr 비율 (예: 2.5 = ATR의 2.5배)
     */
    public static double momentumStrength(UpbitCandle c, double atr) {
        if (atr <= 0) return 0;
        return body(c) / atr;
    }

    /**
     * 핀바(강세): 작은 몸통 + 긴 아래꼬리 + 위꼬리 짧음
     */
    public static boolean isBullishPinbar(UpbitCandle c) {
        double r = range(c);
        if (r <= 0) return false;
        double b = body(c);
        double lw = lowerWick(c);
        double uw = upperWick(c);
        return (b / r <= 0.35) && (lw / r >= 0.55) && (uw / r <= 0.20);
    }

    /**
     * 장악형(상승): 2번째 양봉 몸통이 1번째 음봉 몸통을 완전히 덮음.
     * - 스크립트대로 '저점 더 낮게 시작해서 고점 더 높게 마감'을 몸통 기준으로 구현.
     */
    public static boolean isBullishEngulfing(UpbitCandle firstBear, UpbitCandle secondBull) {
        if (!isBearish(firstBear) || !isBullish(secondBull)) return false;

        // 최소 몸통 크기: 미세 변동 거짓 신호 방지
        double r1 = range(firstBear);
        double r2 = range(secondBull);
        if (r1 <= 0 || body(firstBear) / r1 < 0.30) return false;  // 피장악 캔들: 도지 아닌 명확한 음봉
        if (r2 <= 0 || body(secondBull) / r2 < 0.50) return false;  // 장악 캔들: 강한 양봉

        double firstBodyHigh = Math.max(firstBear.opening_price, firstBear.trade_price);
        double firstBodyLow  = Math.min(firstBear.opening_price, firstBear.trade_price);
        double secondBodyHigh = Math.max(secondBull.opening_price, secondBull.trade_price);
        double secondBodyLow  = Math.min(secondBull.opening_price, secondBull.trade_price);

        return (secondBodyLow < firstBodyLow) && (secondBodyHigh > firstBodyHigh);
    }

    public static boolean isBearishEngulfing(UpbitCandle firstBull, UpbitCandle secondBear) {
        if (!isBullish(firstBull) || !isBearish(secondBear)) return false;

        // 최소 몸통 크기: 미세 변동 거짓 신호 방지
        double r1 = range(firstBull);
        double r2 = range(secondBear);
        if (r1 <= 0 || body(firstBull) / r1 < 0.30) return false;  // 피장악 캔들: 도지 아닌 명확한 양봉
        if (r2 <= 0 || body(secondBear) / r2 < 0.50) return false;  // 장악 캔들: 강한 음봉

        double firstBodyHigh = Math.max(firstBull.opening_price, firstBull.trade_price);
        double firstBodyLow  = Math.min(firstBull.opening_price, firstBull.trade_price);
        double secondBodyHigh = Math.max(secondBear.opening_price, secondBear.trade_price);
        double secondBodyLow  = Math.min(secondBear.opening_price, secondBear.trade_price);

        return (secondBodyHigh > firstBodyHigh) && (secondBodyLow < firstBodyLow);
    }

    /**
     * 인사이드바: 두번째 캔들의 고가/저가가 첫번째(마더바) 범위 안에 존재
     */
    public static boolean isInsideBar(UpbitCandle mother, UpbitCandle inside) {
        return inside.high_price <= mother.high_price && inside.low_price >= mother.low_price;
    }

    /**
     * 모닝스타:
     * 1) 큰 음봉 -> 2) 작은 몸통(도지/짧은 몸통) -> 3) 큰 양봉
     *
     * [수정] 스크립트: "세 번째 양봉 종가가 첫 번째 음봉 고점 위에서 마감 = 가장 강력한 신호"
     * 기존: 몸통 50% 회복이면 OK → 거짓 신호 과다 발생
     * 변경: 3봉 종가 >= 1봉 고가 (시가, 음봉이므로 시가가 고가 측)
     */
    public static boolean isMorningStar(UpbitCandle c1, UpbitCandle c2, UpbitCandle c3) {
        if (!isBearish(c1) || !isBullish(c3)) return false;
        if (!isSmallBody(c2)) return false;

        // c1(음봉), c3(양봉)는 유의미한 몸통 필요 — 작은 캔들 거짓 신호 방지
        double r1 = range(c1);
        double r3 = range(c3);
        if (r1 <= 0 || body(c1) / r1 < 0.40) return false;
        if (r3 <= 0 || body(c3) / r3 < 0.40) return false;

        double c1High = Math.max(c1.opening_price, c1.trade_price);
        return c3.trade_price >= c1High;
    }

    public static boolean isEveningStar(UpbitCandle c1, UpbitCandle c2, UpbitCandle c3) {
        if (!isBullish(c1) || !isBearish(c3)) return false;
        if (!isSmallBody(c2)) return false;

        // c1(양봉), c3(음봉)는 유의미한 몸통 필요 — 작은 캔들 거짓 신호 방지
        double r1 = range(c1);
        double r3 = range(c3);
        if (r1 <= 0 || body(c1) / r1 < 0.40) return false;
        if (r3 <= 0 || body(c3) / r3 < 0.40) return false;

        double mid = c1.opening_price + (body(c1) * 0.5);
        return c3.trade_price <= mid;
    }

    public static boolean isSmallBody(UpbitCandle c) {
        double r = range(c);
        if (r <= 0) return false;
        return body(c) / r <= 0.25;
    }

    /**
     * 3연속 양봉(적삼병) - 꼬리 짧고 점진 상승
     */
    public static boolean isThreeWhiteSoldiers(UpbitCandle a, UpbitCandle b, UpbitCandle c) {
        if (!isBullish(a) || !isBullish(b) || !isBullish(c)) return false;
        if (a.trade_price >= b.trade_price) return false;
        if (b.trade_price >= c.trade_price) return false;

        double ra = range(a), rb = range(b), rc = range(c);
        if (ra <= 0 || rb <= 0 || rc <= 0) return false;

        // 최소 몸통 크기: 3봉 모두 body/range ≥ 50% (미세 변동 거짓 신호 방지)
        if (body(a) / ra < 0.50 || body(b) / rb < 0.50 || body(c) / rc < 0.50) return false;

        // 꼬리 길이 제한(대략)
        return upperWick(a)/ra <= 0.25 && upperWick(b)/rb <= 0.25 && upperWick(c)/rc <= 0.25;
    }

    public static boolean isThreeBlackCrows(UpbitCandle a, UpbitCandle b, UpbitCandle c) {
        if (!isBearish(a) || !isBearish(b) || !isBearish(c)) return false;
        if (a.trade_price <= b.trade_price) return false;
        if (b.trade_price <= c.trade_price) return false;

        double ra = range(a), rb = range(b), rc = range(c);
        if (ra <= 0 || rb <= 0 || rc <= 0) return false;

        // 최소 몸통 크기: 3봉 모두 body/range ≥ 50% (미세 변동 거짓 신호 방지)
        if (body(a) / ra < 0.50 || body(b) / rb < 0.50 || body(c) / rc < 0.50) return false;

        return lowerWick(a)/ra <= 0.25 && lowerWick(b)/rb <= 0.25 && lowerWick(c)/rc <= 0.25;
    }
}
