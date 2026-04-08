package com.example.upbit.bot;

import org.springframework.stereotype.Service;

/**
 * 3개 스캐너(MorningRush, Opening, AllDay)가 공유하는 단일 매수 throttle.
 *
 * 같은 코인에 대해 두 가지 제한을 동시에 적용:
 *  - 1시간 내 최대 2회 매수
 *  - 가장 최근 매수로부터 20분 쿨다운
 *
 * 두 제한을 모두 만족해야 매수 가능. 모든 스캐너가 단일 인스턴스를 사용하므로
 * "모닝러쉬가 매수한 코인을 오프닝이 또 매수"하는 사고가 차단된다.
 *
 * 운영 사고 (2026-04-08 KRW-TREE): 모닝러쉬와 오프닝이 같은 코인을 동시에
 * 매수했고, 한쪽 매도 후 position 행이 삭제되어 업비트 잔고가 orphan 상태.
 * 이를 막기 위해 throttle을 단일 Spring Bean으로 통합.
 */
@Service
public class SharedTradeThrottle {

    private final HourlyTradeThrottle delegate;

    public SharedTradeThrottle() {
        // 기본: 1시간 2회 + 20분 쿨다운
        this.delegate = new HourlyTradeThrottle(2, 20);
    }

    /** 매수 가능 여부 — 두 조건 모두 만족해야 true */
    public boolean canBuy(String market) {
        return delegate.canBuy(market);
    }

    /** 매수 기록 — 매수 성공 직후 호출 */
    public void recordBuy(String market) {
        delegate.recordBuy(market);
    }

    /** 남은 대기 시간 (ms) — 0이면 즉시 매수 가능 */
    public long remainingWaitMs(String market) {
        return delegate.remainingWaitMs(market);
    }

    /** 테스트용 직접 접근 (시나리오 테스트의 reflection 호환) */
    HourlyTradeThrottle getDelegate() {
        return delegate;
    }
}
