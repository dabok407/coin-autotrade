package com.example.upbit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 거래(주문 금액, 슬리피지, 라이브 주문 폴링/타임아웃 등) 관련 설정.
 * application.yml 의 trade.* 를 매핑합니다.
 */
@Component
@ConfigurationProperties(prefix = "trade")
public class TradeProperties {

    /** 모든 코인에 공통으로 적용되는 기본 투입금(원). 코인별 설정이 있으면 코인별이 우선합니다. */
    private double globalBaseOrderKrw = 10000;

    /** 추가매수 금액 배수. (1.0이면 동일 금액, 2.0이면 2배씩) */
    private double addBuyMultiplier = 1.0;

    /** 최대 추가매수 횟수 (5→2: 마틴게일 노출 제한) */
    private int maxAddBuys = 2;

    /** 페이퍼 모드에서 체결가를 보수적으로 잡기 위한 슬리피지(0.002 = 0.2%, 알트코인 현실적 반영) */
    private double slippageRate = 0.002;

    /** 원화 마켓 최소 주문 가능 금액(업비트 정책: 5,000 KRW) */
    private double minOrderKrw = 5000;

    private Live live = new Live();

    public double getGlobalBaseOrderKrw() {
        return globalBaseOrderKrw;
    }

    public void setGlobalBaseOrderKrw(double globalBaseOrderKrw) {
        this.globalBaseOrderKrw = globalBaseOrderKrw;
    }

    public double getAddBuyMultiplier() {
        return addBuyMultiplier;
    }

    public void setAddBuyMultiplier(double addBuyMultiplier) {
        this.addBuyMultiplier = addBuyMultiplier;
    }

    public int getMaxAddBuys() {
        return maxAddBuys;
    }

    public void setMaxAddBuys(int maxAddBuys) {
        this.maxAddBuys = maxAddBuys;
    }

    public double getSlippageRate() {
        return slippageRate;
    }

    public void setSlippageRate(double slippageRate) {
        this.slippageRate = slippageRate;
    }

    public double getMinOrderKrw() {
        return minOrderKrw;
    }

    public void setMinOrderKrw(double minOrderKrw) {
        this.minOrderKrw = minOrderKrw;
    }

    public Live getLive() {
        return live;
    }

    public void setLive(Live live) {
        this.live = live;
    }

    public static class Live {
        /** 주문 상태 폴링 간격(ms) */
        private long orderPollIntervalMs = 800;
        /** 주문 상태 폴링 총 타임아웃(ms) */
        private long orderPollTimeoutMs = 30000;
        /** 실 주문 전 /v1/orders/test로 사전 검증할지 */
        private boolean orderTestBeforePlace = true;
        /** 타임아웃 시 취소 요청을 넣을지(운영 정책, true 권장: 미체결 주문 방치 방지) */
        private boolean cancelOnTimeout = true;

        public long getOrderPollIntervalMs() {
            return orderPollIntervalMs;
        }

        public void setOrderPollIntervalMs(long orderPollIntervalMs) {
            this.orderPollIntervalMs = orderPollIntervalMs;
        }

        public long getOrderPollTimeoutMs() {
            return orderPollTimeoutMs;
        }

        public void setOrderPollTimeoutMs(long orderPollTimeoutMs) {
            this.orderPollTimeoutMs = orderPollTimeoutMs;
        }

        public boolean isOrderTestBeforePlace() {
            return orderTestBeforePlace;
        }

        public void setOrderTestBeforePlace(boolean orderTestBeforePlace) {
            this.orderTestBeforePlace = orderTestBeforePlace;
        }

        public boolean isCancelOnTimeout() {
            return cancelOnTimeout;
        }

        public void setCancelOnTimeout(boolean cancelOnTimeout) {
            this.cancelOnTimeout = cancelOnTimeout;
        }
    }
}
