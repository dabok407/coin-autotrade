package com.example.upbit.web;

import com.example.upbit.strategy.StrategyType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * FE에서 전략 옵션을 동적으로 가져가기 위한 엔드포인트.
 * - 전략 추가 시: StrategyType enum + StrategyFactory 등록만 하면 자동 노출
 */
@RestController
public class StrategyCatalogController {

    @GetMapping("/api/strategies")
    public List<StrategyInfo> strategies() {
        List<StrategyInfo> out = new ArrayList<StrategyInfo>();
        for (StrategyType t : StrategyType.values()) {
            String role = t.isSellOnly() ? "SELL_ONLY" : (t.isSelfContained() ? "SELF_CONTAINED" : "BUY_ONLY");
            out.add(new StrategyInfo(t.name(), displayName(t), description(t), role, t.recommendedIntervalMin(), t.emaTrendFilterMode(), t.recommendedEmaPeriod()));
        }
        return out;
    }

    private String displayName(StrategyType t) {
        // UI 표기(한글) 정책: enum key는 그대로 두고, label만 사람이 읽기 좋게 제공
        // 요구사항: 타임라인/프리픽스 제거하고 "실제 패턴 제목"만 노출
        switch (t) {
            case CONSECUTIVE_DOWN_REBOUND:
                return "연속 하락 반등";

            // 08:14 [1] 장악형 캔들
            case BULLISH_ENGULFING_CONFIRM:
                return "장악형 캔들(상승 확인)";
            case BEARISH_ENGULFING:
                return "장악형 캔들(하락·청산)";

            // 13:38 [2] 모멘텀 캔들
            case MOMENTUM_FVG_PULLBACK:
                return "모멘텀 캔들(FVG 되돌림)";

            // 18:19 [3] 핀바 캔들
            case BULLISH_PINBAR_ORDERBLOCK:
                return "핀바 캔들(오더블록)";

            // 21:20 [4] 인사이드 바 캔들
            case INSIDE_BAR_BREAKOUT:
                return "인사이드 바 캔들(돌파)";

            // 23:52 [5] 삼법형 캔들
            case THREE_METHODS_BULLISH:
                return "삼법형 캔들(상승)";
            case THREE_METHODS_BEARISH:
                return "삼법형 캔들(하락·청산)";

            // 26:47 [6] 모닝스타/이브닝스타 캔들
            case MORNING_STAR:
                return "모닝스타 캔들";
            case EVENING_STAR_SELL:
                return "이브닝스타 캔들(청산)";

            // 30:32 [7] 적삼병/흑삼병 캔들
            case THREE_WHITE_SOLDIERS:
                return "적삼병 캔들";
            case THREE_BLACK_CROWS_SELL:
                return "흑삼병 캔들(청산)";

            case REGIME_PULLBACK:
                return "추세 눌림 매수(ATR)";

            case ADAPTIVE_TREND_MOMENTUM:
                return "적응형 추세 모멘텀(ATM)";

            case SCALP_MOMENTUM:
                return "스캘핑 모멘텀(SM)";

            case EMA_RSI_TREND:
                return "EMA-RSI 추세(ERT)";

            case BOLLINGER_RSI_MEAN_REVERSION:
                return "볼린저-RSI 평균회귀(BMR)";
        }
        return t.name();
    }

    private String description(StrategyType t) {
        // FE tooltip용 설명(조금 더 구체 버전)
        switch (t) {
            case CONSECUTIVE_DOWN_REBOUND:
                return "연속 하락 흐름에서 일정 횟수 이상 하락이 누적된 뒤, 반등 징후가 나타나면 분할 매수로 진입합니다."
                    + "\n\n📊 점수 산출 기준 (기본 4점):"
                    + "\n• 초과 연속 하락 횟수 (+1.5~2.5점): 설정값 이상의 추가 하락이 많을수록 반등 기대↑"
                    + "\n• 마지막 봉 양봉 여부 (+2.0점): 양봉 마감 시 실제 반등 확인"
                    + "\n• 마지막 봉 몸통 강도 (+0.8~1.5점): 몸통/전체비 60%+ 강한 반등";

            case BULLISH_ENGULFING_CONFIRM:
                return "하락 말미/지지 구간에서 상승 장악형(이전 봉 몸통을 완전히 덮는 강한 양봉) 출현 후, 추가 확인 봉까지 동반되면 매수합니다."
                    + "\n\n📊 점수 산출 기준 (기본 4점):"
                    + "\n• 장악 비율 (+0.5~2.0점): 양봉이 음봉을 2배 이상 덮으면 최고점"
                    + "\n• 확인봉(c3) 강도 (+0.3~1.5점): 몸통/전체비 70%+ 강한 확인"
                    + "\n• c3 종가 > c2 고가 (+0.5~1.5점): 전고점 돌파 시 강한 신호";
            case BEARISH_ENGULFING:
                return "상승 후 고점/저항 구간에서 하락 장악형(이전 봉 몸통을 완전히 삼키는 강한 음봉) 출현 시 리스크 회피를 위해 청산(또는 진입 회피)합니다."
                    + "\n\n📊 점수 산출 기준 (기본 4점):"
                    + "\n• 장악 비율 (+0.5~2.5점): 음봉이 양봉을 2배 이상 덮으면 최고점"
                    + "\n• 음봉 몸통 강도 (+0.3~2.0점): 몸통/전체비 70%+ 강한 매도 압력";

            case MOMENTUM_FVG_PULLBACK:
                return "장대 모멘텀 캔들로 돌파가 발생한 뒤, FVG(가격 공백) 영역으로 되돌림이 들어올 때 지지 확인 후 진입합니다."
                    + "\n\n📊 점수 산출 기준 (기본 4점):"
                    + "\n• FVG 갭 크기 (+0.8~2.0점): 가격 대비 1%+ 갭이면 명확한 모멘텀"
                    + "\n• 되돌림 정확도 (+0.3~1.5점): FVG 구간 50%+ 진입 시 정밀한 지지"
                    + "\n• 양봉 강도 (+0.3~1.5점): 몸통/전체비 70%+ 강한 반등"
                    + "\n• FVG 신선도 (+0~1.0점): 최근 5봉 내 형성된 FVG가 더 유효";

            case BULLISH_PINBAR_ORDERBLOCK:
                return "긴 아래꼬리 핀바(저점 방어 흔적)가 오더블록/지지 구간에서 재차 확인되면 반등 가능성에 베팅해 매수합니다."
                    + "\n\n📊 점수 산출 기준 (기본 4점):"
                    + "\n• 아래꼬리 비율 (+0.5~2.0점): 꼬리/전체비 75%+ 강한 매수세 방어"
                    + "\n• 몸통 작음 (+0.3~1.5점): 몸통/전체비 15%이하 전형적 핀바"
                    + "\n• 지지 정확도 (+0.5~2.0점): 10봉 최저점에 정확히 근접할수록 높음";

            case INSIDE_BAR_BREAKOUT:
                return "큰 캔들(마더바) 범위 안에 작은 캔들이 갇히는 인사이드바 이후, 마더바 상단 돌파가 나오면 추세 재개 신호로 보고 진입합니다."
                    + "\n\n📊 점수 산출 기준 (기본 4점):"
                    + "\n• 돌파 강도 (+0.8~2.0점): 마더바 고가 대비 1%+ 초과 돌파"
                    + "\n• 거래량 비율 (+0.5~2.0점): 20봉 평균 대비 2배+ 거래량"
                    + "\n• 인사이드바 압축도 (+0.3~1.5점): inside/mother 범위비 30%이하 강한 압축";

            case THREE_METHODS_BULLISH:
                return "장대 양봉 이후 조정 봉들이 범위 내에서 이어지고, 마지막에 다시 장대 양봉으로 돌파하면 단순 조정 후 추세 지속으로 판단해 진입합니다."
                    + "\n\n📊 점수 산출 기준 (기본 4점):"
                    + "\n• 첫 봉 모멘텀 강도 (+0.5~1.5점): 몸통/전체비 85%+ 장대양봉"
                    + "\n• 마지막 봉 돌파 강도 (+0.5~2.0점): 첫 봉 종가 대비 1%+ 초과"
                    + "\n• 조정 깊이 (+0.5~1.5점): 중간봉이 상위 50%에서 조정 시 강한 지지";
            case THREE_METHODS_BEARISH:
                return "장대 음봉 이후 조정 봉들이 범위 내에서 이어지고, 마지막에 다시 장대 음봉으로 하락하면 하락 추세 지속으로 판단해 포지션을 청산합니다."
                    + "\n\n📊 점수 산출 기준 (기본 4점):"
                    + "\n• 첫 봉 모멘텀 강도 (+0.5~1.5점): 몸통/전체비 85%+ 장대음봉"
                    + "\n• 마지막 봉 하락 강도 (+0.5~2.0점): 첫 봉 종가 대비 1%+ 하락"
                    + "\n• 마지막 봉 몸통 강도 (+0.5~1.5점): 85%+ 강한 매도 압력";

            case MORNING_STAR:
                return "하락 말미에서 3봉 반전(큰 음봉 → 작은 몸통/도지 → 큰 양봉)이 완성되면 매수세 전환 신호로 보고 매수합니다."
                    + "\n\n📊 점수 산출 기준 (기본 4점):"
                    + "\n• c1 음봉 강도 (+0~1.5점): 몸통/전체비 70%+ 명확한 하락"
                    + "\n• c2 도지 정도 (+0.3~1.5점): 몸통/전체비 10%이하 완벽한 도지"
                    + "\n• c3 양봉 회복 (+0.3~2.0점): c1 고가 대비 0.5%+ 위에서 마감"
                    + "\n• c3 양봉 몸통 강도 (+0.3~1.0점): 70%+ 강한 반등";
            case EVENING_STAR_SELL:
                return "상승 말미에서 3봉 반전(큰 양봉 → 작은 몸통/도지 → 큰 음봉)이 완성되면 매도 우위 전환으로 보고 포지션을 청산합니다."
                    + "\n\n📊 점수 산출 기준 (기본 4점):"
                    + "\n• c2 도지 정도 (+0.3~1.5점): 몸통/전체비 10%이하 완벽한 도지"
                    + "\n• c3 음봉 강도 (+0.3~2.0점): 몸통/전체비 70%+ 강한 하락"
                    + "\n• 하락 커버율 (+0.3~2.0점): c1 양봉 중간점 대비 1%+ 아래에서 마감";

            case THREE_WHITE_SOLDIERS:
                return "꼬리가 짧은 양봉 3개가 연속 출현하며 종가가 단계적으로 상승하면 매수세 연속 유입으로 보고 진입합니다."
                    + "\n\n📊 점수 산출 기준 (기본 4점):"
                    + "\n• 상승 균일성 (+0.3~1.5점): 3봉 몸통 크기가 균일할수록 건강한 상승"
                    + "\n• 위꼬리 짧음 (+0.5~2.0점): 평균 위꼬리/전체비 8%이하 매도 압력 없음"
                    + "\n• 거래량 강도 (+0.3~1.5점): 마지막 봉 거래량이 평균 대비 1.8배+";
            case THREE_BLACK_CROWS_SELL:
                return "꼬리가 짧은 음봉 3개가 연속 출현하며 종가가 단계적으로 하락하면 매도세 연속 유입으로 보고 리스크 관리 차원에서 청산합니다."
                    + "\n\n📊 점수 산출 기준 (기본 4점):"
                    + "\n• 하락 균일성 (+0.3~1.5점): 3봉 몸통 크기가 균일할수록 확실한 하락"
                    + "\n• 아래꼬리 짧음 (+0.5~2.0점): 평균 아래꼬리/전체비 8%이하 반등 없음"
                    + "\n• 총 하락폭 (+0.3~2.0점): 첫봉 대비 2%+ 하락 시 강한 신호";

            case REGIME_PULLBACK:
                return "EMA200/EMA50/ADX로 상승 추세를 확인한 뒤, RSI 과매도 또는 볼린저 하단 접촉 시 눌림 매수합니다. ATR 기반 동적 손절/익절 + 트레일링 스탑으로 수익을 보호하고, 추세 붕괴 시 즉시 청산합니다. (1시간봉 권장)"
                    + "\n\n📊 점수 산출 기준 (기본 4점):"
                    + "\n• ADX 강도 (+0.8~2.0점): ADX 30+ 강한 추세"
                    + "\n• RSI 극값 (+0.3~1.5점): RSI2≤3 또는 RSI14<30 극단적 과매도"
                    + "\n• EMA 정렬 마진 (+0.3~1.5점): EMA50-EMA200 차이 3%+ 강한 추세"
                    + "\n• 이중 조건 충족 (+1.0점): RSI+BB 동시 만족 시 보너스";

            case ADAPTIVE_TREND_MOMENTUM:
                return "5중 확인(추세정렬+MACD모멘텀+거래량+눌림+반등)을 모두 통과한 고확률 진입점만 포착합니다. Chandelier Exit + MACD 소멸 감지 + 추세 붕괴 청산으로 수익을 보호합니다. 안정적 승률 우선 전략. (1시간봉·4시간봉 권장)"
                    + "\n\n📊 점수 산출 기준 (기본 5점 — 5중 확인 통과):"
                    + "\n• EMA 정렬 마진 (+0~1.0점): EMA20-EMA50 차이 2%+ 강한 정렬"
                    + "\n• MACD 히스토그램 강도 (+0.3~1.0점): 가격 대비 5bps+ 강한 모멘텀"
                    + "\n• 거래량 비율 (+0.3~1.5점): 평균 대비 2배+ 스마트머니 유입"
                    + "\n• RSI 위치 (+0~1.0점): RSI 45이하 과매수 위험 없음";

            case SCALP_MOMENTUM:
                return "모멘텀 급등 초기 구간을 포착하여 빠르게 진입·청산하는 스캘핑 전략입니다. EMA 정렬 + RSI 반등 + 거래량 급증 + 양봉 확인의 다중 필터로 노이즈를 걸러내고, TP/SL 비율을 타이트하게 유지합니다."
                    + "\n\n📊 점수 산출 기준 (기본 4점):"
                    + "\n• RSI 모멘텀 강도 (+0.5~2.0점): RSI 반등폭 10+ 강한 모멘텀"
                    + "\n• 거래량 급증 (+0.3~1.5점): 평균 대비 2배+ 거래량"
                    + "\n• 양봉 몸통 강도 (+0.3~1.5점): 몸통/전체비 60%+ 강한 매수세"
                    + "\n\n■ 권장: 15~60분봉, TP 2~3%, SL 1~1.5%, minConfidence 7, TimeStop 480분";

            case EMA_RSI_TREND:
                return "EMA 다중 정렬(EMA20>EMA50>EMA200) + RSI 눌림(40~55) + 양봉 반등을 확인하는 품질 중심 추세 추종 전략입니다. 가짜 시그널을 최소화하고 높은 승률을 추구합니다."
                    + "\n\n📊 점수 산출 기준 (기본 5점):"
                    + "\n• EMA 정렬 마진 (+0~1.5점): EMA20-EMA50 차이 2%+ 강한 추세"
                    + "\n• RSI 눌림 깊이 (+0.3~1.0점): RSI 40~45 이상적인 눌림 구간"
                    + "\n• 양봉 강도 (+0.3~1.5점): 몸통/전체비 60%+ 반등 확인"
                    + "\n• 거래량 비율 (+0.3~1.0점): 평균 대비 1.5배+ 유효한 반등"
                    + "\n\n■ 권장: 60분봉, TP 3%, SL 2%, minConfidence 8, PCT 90";

            case BOLLINGER_RSI_MEAN_REVERSION:
                return "볼린저 밴드 하단 터치 + RSI 과매도 + 양봉 반등을 확인하는 평균회귀 전략입니다. 과도한 하락 후 평균으로 복귀하는 움직임을 포착합니다."
                    + "\n\n📊 점수 산출 기준 (기본 4점):"
                    + "\n• BB 하단 이탈 정도 (+0.5~2.0점): 하단 밴드 대비 0.5%+ 이탈"
                    + "\n• RSI 과매도 (+0.3~1.5점): RSI 25이하 극단적 과매도"
                    + "\n• 양봉 반등 강도 (+0.3~1.5점): 몸통/전체비 60%+ 반등 확인"
                    + "\n\n■ 권장: 60분봉, TP 2%, SL 1.5%, minConfidence 6";
        }
        return "";
    }

    public static class StrategyInfo {
        public String key;
        public String label;
        public String desc;
        /** BUY_ONLY, SELL_ONLY, SELF_CONTAINED */
        public String role;
        /** 권장 캔들 인터벌(분) */
        public int recommendedInterval;
        /** EMA 트렌드 필터 모드: CONFIGURABLE, INTERNAL, NONE */
        public String emaFilterMode;
        /** 권장 EMA 기간 (CONFIGURABLE=50, 그 외=0) */
        public int recommendedEma;
        public StrategyInfo(String key, String label, String desc, String role, int recommendedInterval, String emaFilterMode, int recommendedEma) {
            this.key = key;
            this.label = label;
            this.desc = desc;
            this.role = role;
            this.recommendedInterval = recommendedInterval;
            this.emaFilterMode = emaFilterMode;
            this.recommendedEma = recommendedEma;
        }
    }
}
